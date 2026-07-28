package nl.joozt.kiep.keyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends typed text to nearby paired displays over BLE - never the internet,
 * by design (the radio range is the privacy boundary). See
 * docs/proposals/external-display.md for the protocol and decisions.
 */
@SuppressLint("MissingPermission") // Guarded once in connectAll; all BLE calls need the same permission
public class ExternalDisplay {
    /**
     * KiepDisplay GATT service: receivers advertise this. The text
     * characteristic takes one type byte ('M' = message to read, 'L' = live
     * typing buffer) followed by UTF-8 text; the full value replaces the
     * receiver's display content. The attention characteristic takes a single
     * byte and makes the receiver flash/beep.
     */
    public static final UUID SERVICE_UUID = UUID.fromString("b1e60001-8f2a-4d0c-9e4b-5c3d2a1f0e9d");
    public static final UUID TEXT_CHARACTERISTIC_UUID = UUID.fromString("b1e60002-8f2a-4d0c-9e4b-5c3d2a1f0e9d");
    public static final UUID ATTENTION_CHARACTERISTIC_UUID = UUID.fromString("b1e60003-8f2a-4d0c-9e4b-5c3d2a1f0e9d");

    private static final String TAG = ExternalDisplay.class.getSimpleName();
    private static final byte TYPE_MESSAGE = 'M'; // scrolls from the start when overflowing
    private static final byte TYPE_WORD = 'W'; // word-append: receivers extend the shown text
    private static final byte TYPE_LIVE = 'L';
    // Attention characteristic payload: [effect, count]
    private static final byte EFFECT_ATTENTION = 1; // white flash + beep, repeated count times
    private static final byte EFFECT_YES = 2; // green flash
    private static final byte EFFECT_NO = 3; // red flash
    private static final long LIVE_DEBOUNCE_MS = 100;
    private static final int LIVE_TAIL_CHARS = 200;
    private static final String END_CHARS = " .,\n?!:;"; // Word boundaries, same set as Abbreviations
    private static final String TERMINATORS = ".!?\n"; // Sentence boundaries, same set as TTS

    private final Context context;
    private final EditText editText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Connection> connections = new ArrayList<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private final String sendKey;
    private final String attentionKey;
    private final boolean autoAttention;
    private final int attentionRepeat;
    private final long attentionIntervalMillis;
    private final boolean yesNoFlash;
    private Runnable onConnectionChanged = null;
    private String lastLiveText = null;
    private long lastAttentionMillis = 0;

    public ExternalDisplay(Context context, GlobalKeyPressListener keyPressListener, final EditText editText) {
        this.context = context;
        this.editText = editText;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(SettingsActivity.ENABLE_EXTERNAL_DISPLAY, SettingsActivity.ENABLE_EXTERNAL_DISPLAY_DEFAULT)) {
            sendKey = "";
            attentionKey = "";
            autoAttention = false;
            attentionRepeat = SettingsActivity.ATTENTION_REPEAT_DEFAULT;
            attentionIntervalMillis = 0;
            yesNoFlash = false;
            return;
        }

        autoAttention = preferences.getBoolean(SettingsActivity.AUTO_ATTENTION, SettingsActivity.AUTO_ATTENTION_DEFAULT);
        attentionRepeat = preferences.getInt(SettingsActivity.ATTENTION_REPEAT, SettingsActivity.ATTENTION_REPEAT_DEFAULT);
        attentionIntervalMillis = preferences.getInt(SettingsActivity.ATTENTION_INTERVAL, SettingsActivity.ATTENTION_INTERVAL_DEFAULT) * 1000L;
        yesNoFlash = preferences.getBoolean(SettingsActivity.ENABLE_YES_NO_FLASH, SettingsActivity.ENABLE_YES_NO_FLASH_DEFAULT);

        sendKey = preferences.getString(SettingsActivity.SEND_KEY, SettingsActivity.SEND_KEY_DEFAULT);
        keyPressListener.addListener(sendKey, () -> {
            sendSentenceUnderCursor();
            return true;
        });

        attentionKey = preferences.getString(SettingsActivity.ATTENTION_KEY, SettingsActivity.ATTENTION_KEY_DEFAULT);
        if (!attentionKey.isEmpty()) {
            keyPressListener.addListener(attentionKey, () -> {
                sendAttention(true);
                return true;
            });
        }

        String mode = preferences.getString(SettingsActivity.EXTERNAL_DISPLAY_MODE, SettingsActivity.EXTERNAL_DISPLAY_MODE_DEFAULT);
        addModeWatcher(mode);

        connectAll(preferences.getStringSet(SettingsActivity.EXTERNAL_DISPLAYS, Collections.emptySet()));
    }

    private void addModeWatcher(String mode) {
        boolean character = SettingsActivity.EXTERNAL_DISPLAY_MODE_CHARACTER.equals(mode);
        boolean word = SettingsActivity.EXTERNAL_DISPLAY_MODE_WORD.equals(mode);
        boolean sentence = SettingsActivity.EXTERNAL_DISPLAY_MODE_SENTENCE.equals(mode);
        if (!character && !word && !sentence) {
            return;
        }

        final Runnable sendLive = () -> {
            String live = tail(editText.getText().toString());
            // Unchanged text sends nothing - crucially, a command keypress
            // (typed and stripped in place) must not overwrite a scrolling
            // message the send hotkey just put on the displays
            if (live.equals(lastLiveText)) {
                return;
            }
            lastLiveText = live;
            sendFrame(TYPE_LIVE, live);
        };
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Only single typed characters trigger word/sentence sends,
                // mirroring the command-key dispatch: programmatic changes
                // (loading files, expansions) never leave the device
                if (character || count != 1) {
                    return;
                }
                String typed = Character.toString(s.charAt(start));
                boolean boundary = word ? END_CHARS.contains(typed) : TERMINATORS.contains(typed);
                if (boundary) {
                    String text = TTS.sentenceAt(s.toString(), start, true);
                    if (!text.isEmpty()) {
                        sendFrame(word ? TYPE_WORD : TYPE_MESSAGE, text);
                        if (autoAttention) {
                            sendAttention(false);
                        }
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (character) {
                    handler.removeCallbacks(sendLive);
                    handler.postDelayed(sendLive, LIVE_DEBOUNCE_MS);
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
        });
    }

    private void sendSentenceUnderCursor() {
        String sentence = TTS.sentenceAt(editText.getText().toString(), editText.getSelectionStart(), true);
        if (sentence.isEmpty()) {
            return;
        }
        sendFrame(TYPE_MESSAGE, sentence);
        if (autoAttention) {
            // A deliberate hotkey send always gets its attention; only the
            // continuous word/sentence streams are rate-limited
            sendAttention(true);
        }
    }

    private static String tail(String text) {
        return text.length() <= LIVE_TAIL_CHARS ? text : text.substring(text.length() - LIVE_TAIL_CHARS);
    }

    private void connectAll(Set<String> displays) {
        if (displays.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "Bluetooth permission missing; displays not connected");
            return;
        }
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth off; displays not connected");
            return;
        }
        for (String display : displays) {
            String address = display.substring(0, display.indexOf('|'));
            String name = display.substring(display.indexOf('|') + 1);
            Connection connection = new Connection();
            connection.name = name;
            if (isStaticAddress(address)) {
                // Static address (the Ulanzi): dial it directly, autoConnect
                // reconnects by itself whenever the display is in range.
                // ponytail: connectGatt(Context, ...) is deprecated as of API 37;
                // its BluetoothGattConnectionSettings replacement needs API 37+,
                // so switch when minSdk catches up
                try {
                    @SuppressWarnings("deprecation")
                    BluetoothGatt gatt = adapter.getRemoteDevice(address)
                            .connectGatt(context, true, connection.callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
                    connection.gatt = gatt;
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid display address: " + address, e);
                    continue;
                }
            } else {
                // Android receivers advertise with a resolvable private address
                // that rotates; the address stored at add time goes stale (the
                // stack then rejects connects with status 135). Find them by
                // scanning for the KiepDisplay service and match on the stored
                // name, connecting to whatever address they use right now.
                connection.scanBased = true;
            }
            connections.add(connection);
        }
        this.scanner = adapter.getBluetoothLeScanner();
        startScanIfNeeded();
    }

    /** Top two address bits 11 = static random: never rotates, safe to store. */
    private static boolean isStaticAddress(String address) {
        try {
            return (Integer.parseInt(address.substring(0, 2), 16) & 0xC0) == 0xC0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void startScanIfNeeded() {
        if (scanner == null || scanCallback != null || !hasUnconnectedScanBased()) {
            return;
        }
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
                handler.post(() -> onDisplayFound(result, name));
            }
        };
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build());
        // ponytail: BALANCED finds a display within a few seconds; LOW_POWER
        // if hours-long keyboard sessions ever hurt battery
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();
        Log.d(TAG, "Scanning for displays");
        scanner.startScan(filters, settings, scanCallback);
    }

    private void stopScanIfDone() {
        if (scanCallback != null && !hasUnconnectedScanBased()) {
            Log.d(TAG, "All displays found, scan stopped");
            scanner.stopScan(scanCallback);
            scanCallback = null;
        }
    }

    private boolean hasUnconnectedScanBased() {
        for (Connection connection : connections) {
            if (connection.scanBased && connection.gatt == null) {
                return true;
            }
        }
        return false;
    }

    private void onDisplayFound(ScanResult result, String name) {
        if (name == null) {
            return;
        }
        for (Connection connection : connections) {
            if (connection.scanBased && connection.gatt == null && name.equals(connection.name)) {
                Log.d(TAG, "Found display '" + name + "', connecting");
                // Direct connect (no autoConnect): the address rotates, so a
                // failed or dropped link goes back to scanning instead
                @SuppressWarnings("deprecation")
                BluetoothGatt gatt = result.getDevice()
                        .connectGatt(context, false, connection.callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
                connection.gatt = gatt;
                break;
            }
        }
        stopScanIfDone();
    }

    private void sendFrame(byte type, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[textBytes.length + 1];
        frame[0] = type;
        System.arraycopy(textBytes, 0, frame, 1, textBytes.length);
        for (Connection connection : connections) {
            connection.submitText(frame);
        }
    }

    private void sendAttention(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastAttentionMillis < attentionIntervalMillis) {
            return;
        }
        lastAttentionMillis = now;
        sendEffect(new byte[]{EFFECT_ATTENTION, (byte) attentionRepeat});
    }

    /**
     * Mirrors a yes/no answer as a green/red flash on the displays, when
     * enabled in Settings. Wired by the activity, like the speech interrupt.
     */
    public void sendYesNo(boolean yes) {
        if (!yesNoFlash) {
            return;
        }
        sendEffect(new byte[]{yes ? EFFECT_YES : EFFECT_NO, 1});
    }

    private void sendEffect(byte[] payload) {
        for (Connection connection : connections) {
            connection.submitEffect(payload);
        }
    }

    public String getSendKey() {
        return sendKey;
    }

    public String getAttentionKey() {
        return attentionKey;
    }

    public int getConnectedCount() {
        int count = 0;
        for (Connection connection : connections) {
            if (connection.ready) {
                count++;
            }
        }
        return count;
    }

    public void setOnConnectionChanged(Runnable onConnectionChanged) {
        this.onConnectionChanged = onConnectionChanged;
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        if (scanCallback != null) {
            scanner.stopScan(scanCallback);
            scanCallback = null;
        }
        for (Connection connection : connections) {
            if (connection.gatt != null) {
                connection.gatt.close();
            }
        }
        connections.clear();
    }

    private void notifyConnectionChanged() {
        if (onConnectionChanged != null) {
            onConnectionChanged.run();
        }
    }

    /**
     * One BLE link. Writes are serialized with a single pending slot where
     * the latest text frame wins.
     */
    // ponytail: a rapid burst of MSG frames can drop intermediates; fine for
    // reading flow since word-mode messages are cumulative
    private class Connection {
        BluetoothGatt gatt;
        String name;
        boolean scanBased = false;
        BluetoothGattCharacteristic text;
        BluetoothGattCharacteristic attention;
        boolean ready = false;
        boolean busy = false;
        byte[] pendingText = null;
        byte[] pendingEffect = null;

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                handler.post(() -> {
                    Log.d(TAG, "Connection state " + newState + " (status " + status + ") for " + gatt.getDevice().getAddress());
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // One outstanding GATT operation at a time: discovery
                        // starts from onMtuChanged, not here
                        if (!gatt.requestMtu(247)) {
                            Log.w(TAG, "MTU request refused, discovering services directly");
                            gatt.discoverServices();
                        }
                    } else {
                        ready = false;
                        busy = false;
                        if (scanBased && Connection.this.gatt != null) {
                            // The next advertised address may differ; drop the
                            // dead link and let the scan find the display again
                            Connection.this.gatt.close();
                            Connection.this.gatt = null;
                            startScanIfNeeded();
                        }
                        notifyConnectionChanged();
                    }
                });
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                handler.post(() -> {
                    Log.d(TAG, "MTU " + mtu + " (status " + status + "), discovering services");
                    gatt.discoverServices();
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                handler.post(() -> {
                    BluetoothGattService service = gatt.getService(SERVICE_UUID);
                    if (service == null) {
                        Log.w(TAG, "Display lacks the KiepDisplay service (status " + status + ")");
                        return;
                    }
                    text = service.getCharacteristic(TEXT_CHARACTERISTIC_UUID);
                    attention = service.getCharacteristic(ATTENTION_CHARACTERISTIC_UUID);
                    ready = text != null && attention != null;
                    Log.d(TAG, "Services discovered, ready=" + ready + " for " + gatt.getDevice().getAddress());
                    notifyConnectionChanged();
                });
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                handler.post(() -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Write failed with status " + status);
                    }
                    busy = false;
                    pump();
                });
            }
        };

        void submitText(byte[] frame) {
            pendingText = frame;
            pump();
        }

        void submitEffect(byte[] payload) {
            pendingEffect = payload;
            pump();
        }

        void pump() {
            if (busy || !ready) {
                return;
            }
            if (pendingText != null) {
                byte[] frame = pendingText;
                pendingText = null;
                Log.d(TAG, "Writing text frame '" + (char) frame[0] + "' (" + frame.length + " bytes)");
                busy = write(text, frame);
                if (!busy) {
                    Log.w(TAG, "writeCharacteristic (text) refused");
                }
            } else if (pendingEffect != null) {
                byte[] payload = pendingEffect;
                pendingEffect = null;
                Log.d(TAG, "Writing effect " + payload[0]);
                busy = write(attention, payload);
                if (!busy) {
                    Log.w(TAG, "writeCharacteristic (attention) refused");
                }
            }
        }

        // API 33+ takes the value in the write call; below that only the
        // deprecated setValue/writeCharacteristic pair exists
        @SuppressWarnings("deprecation")
        private boolean write(BluetoothGattCharacteristic characteristic, byte[] value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        == BluetoothStatusCodes.SUCCESS;
            }
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(value);
            return gatt.writeCharacteristic(characteristic);
        }
    }
}
