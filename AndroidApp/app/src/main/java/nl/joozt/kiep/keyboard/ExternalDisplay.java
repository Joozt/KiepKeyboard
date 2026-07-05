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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    private static final byte TYPE_MESSAGE = 'M';
    private static final byte TYPE_LIVE = 'L';
    private static final long LIVE_DEBOUNCE_MS = 100;
    private static final int LIVE_TAIL_CHARS = 200;
    private static final String END_CHARS = " .,\n?!:;"; // Word boundaries, same set as Abbreviations
    private static final String TERMINATORS = ".!?\n"; // Sentence boundaries, same set as TTS

    private final Context context;
    private final EditText editText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Connection> connections = new ArrayList<>();
    private final String sendKey;
    private final String attentionKey;
    private final boolean autoAttention;
    private Runnable onConnectionChanged = null;
    private String lastLiveText = null;

    public ExternalDisplay(Context context, GlobalKeyPressListener keyPressListener, final EditText editText) {
        this.context = context;
        this.editText = editText;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(SettingsActivity.ENABLE_EXTERNAL_DISPLAY, SettingsActivity.ENABLE_EXTERNAL_DISPLAY_DEFAULT)) {
            sendKey = "";
            attentionKey = "";
            autoAttention = false;
            return;
        }

        autoAttention = preferences.getBoolean(SettingsActivity.AUTO_ATTENTION, SettingsActivity.AUTO_ATTENTION_DEFAULT);

        sendKey = preferences.getString(SettingsActivity.SEND_KEY, SettingsActivity.SEND_KEY_DEFAULT);
        keyPressListener.addListener(sendKey, () -> {
            sendSentenceUnderCursor();
            return true;
        });

        attentionKey = preferences.getString(SettingsActivity.ATTENTION_KEY, SettingsActivity.ATTENTION_KEY_DEFAULT);
        if (!attentionKey.isEmpty()) {
            keyPressListener.addListener(attentionKey, () -> {
                sendAttention();
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
                        sendFrame(TYPE_MESSAGE, text);
                        if (autoAttention) {
                            sendAttention();
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
            sendAttention();
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
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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
            try {
                Connection connection = new Connection();
                // autoConnect: reconnects by itself whenever the display is in range
                connection.gatt = adapter.getRemoteDevice(address)
                        .connectGatt(context, true, connection.callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE);
                connections.add(connection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid display address: " + address, e);
            }
        }
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

    private void sendAttention() {
        for (Connection connection : connections) {
            connection.submitAttention();
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
        BluetoothGattCharacteristic text;
        BluetoothGattCharacteristic attention;
        boolean ready = false;
        boolean busy = false;
        byte[] pendingText = null;
        boolean pendingAttention = false;

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

        void submitAttention() {
            pendingAttention = true;
            pump();
        }

        void pump() {
            if (busy || !ready) {
                return;
            }
            if (pendingText != null) {
                text.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                text.setValue(pendingText);
                Log.d(TAG, "Writing text frame '" + (char) pendingText[0] + "' (" + pendingText.length + " bytes)");
                pendingText = null;
                busy = gatt.writeCharacteristic(text);
                if (!busy) {
                    Log.w(TAG, "writeCharacteristic (text) refused");
                }
            } else if (pendingAttention) {
                attention.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                attention.setValue(new byte[]{1});
                Log.d(TAG, "Writing attention");
                pendingAttention = false;
                busy = gatt.writeCharacteristic(attention);
                if (!busy) {
                    Log.w(TAG, "writeCharacteristic (attention) refused");
                }
            }
        }
    }
}
