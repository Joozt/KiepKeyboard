package nl.joozt.kiep.keyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phone/tablet as a read-only KiepDisplay receiver: advertises the KiepDisplay
 * service and renders the frames a nearby KiepKeyboard writes, mirroring the
 * Ulanzi firmware (UlanziReceiver/). Launched from its own launcher entry;
 * receives only while visible - leaving the screen stops advertising and
 * clears it (privacy). See docs/proposals/external-display.md.
 */
@SuppressLint("MissingPermission") // Guarded once in startBle; all BLE calls need the same permissions
public class DisplayActivity extends AppCompatActivity {
    private static final String TAG = DisplayActivity.class.getSimpleName();
    // Attention characteristic payload [effect, count]; same codes as the
    // Ulanzi firmware - every receiver implements the protocol independently
    private static final byte EFFECT_ATTENTION = 1;
    private static final byte EFFECT_YES = 2;
    private static final byte EFFECT_NO = 3;
    private static final long IDLE_CLEAR_MS = 10 * 60 * 1000L; // parity with the Ulanzi receiver
    private static final long HEARTBEAT_TOGGLE_MS = 500;
    // Highlight timings identical to YesNo.highlightBackground on the phone
    private static final long FLASH_FADE_MS = 250;
    private static final long FLASH_RESET_DELAY_MS = 600;
    private static final long ATTENTION_PERIOD_MS = 850; // fade-in + hold + fade-out per repeat

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> connectedDevices = new HashSet<>();
    // ponytail: prepared-write buffers keyed by characteristic only - a single
    // sender is the design; key by device too if multiple senders ever matter
    private final Map<UUID, ByteArrayOutputStream> preparedWrites = new HashMap<>();

    private View root;
    private View flash;
    private ScrollView scroll;
    private TextView text;
    private TextView status;
    private View heartbeat;
    private BatteryStatus batteryStatus;
    private Runnable resetFlashRunnable = null;
    private SoundPool soundPool;
    private int yesSoundId;
    private int noSoundId;
    private Ringtone attentionSound;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising = false;
    private boolean heartbeatOn = false;
    private boolean permissionAsked = false;
    private boolean enableBluetoothAsked = false;
    private ActivityResultLauncher<String[]> permissionRequest;

    private final Runnable idleClear = this::clearText;

    private final Runnable heartbeatToggle = new Runnable() {
        @Override
        public void run() {
            heartbeatOn = !heartbeatOn;
            heartbeat.setVisibility(heartbeatOn ? View.VISIBLE : View.GONE);
            handler.postDelayed(this, HEARTBEAT_TOGGLE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display);
        root = findViewById(R.id.displayRoot);
        flash = findViewById(R.id.displayFlash);
        scroll = findViewById(R.id.displayScroll);
        text = findViewById(R.id.displayText);
        status = findViewById(R.id.displayStatus);
        heartbeat = findViewById(R.id.displayHeartbeat);

        // Fixed size, set on the receiver device: autosizing made the size
        // jump between a short hotkey sentence and the full live buffer
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                preferences.getInt(SettingsActivity.DISPLAY_TEXT_SIZE, SettingsActivity.DISPLAY_TEXT_SIZE_DEFAULT));

        ProgressBar progressBar = findViewById(R.id.progressBar);
        batteryStatus = new BatteryStatus(this, progressBar, root);

        // A display on the table: screen stays on at full brightness, no bars
        // (bars are hidden in onWindowFocusChanged - the permission and
        // Bluetooth-enable dialogs steal focus and bring them back)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = 1f;
        getWindow().setAttributes(attributes);

        // Same samples as the in-app yes/no keys; attention uses the system
        // notification sound instead of the Ulanzi beep
        soundPool = new SoundPool.Builder().setMaxStreams(1).build();
        yesSoundId = soundPool.load(this, R.raw.yes, 1);
        noSoundId = soundPool.load(this, R.raw.no, 1);
        attentionSound = RingtoneManager.getRingtone(this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
                    if (!grants.containsValue(false)) {
                        startBle();
                    } else {
                        status.setText(R.string.display_permission_needed);
                    }
                });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ImmersiveMode.apply(getWindow());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startBle();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopBle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        batteryStatus.destroy();
        soundPool.release();
    }

    private String[] missingPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return new String[0]; // Legacy Bluetooth permissions are install-time
        }
        String[] required = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT};
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return required;
            }
        }
        return new String[0];
    }

    private void startBle() {
        if (gattServer != null) {
            return;
        }
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            status.setText(R.string.display_permission_needed);
            if (!permissionAsked) {
                permissionAsked = true;
                permissionRequest.launch(missing);
            }
            return;
        }
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.bluetooth_disabled);
            if (adapter != null && !enableBluetoothAsked) {
                enableBluetoothAsked = true;
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            }
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            status.setText(R.string.display_unsupported);
            return;
        }

        gattServer = bluetoothManager.openGattServer(this, serverCallback);
        BluetoothGattService service = new BluetoothGattService(
                ExternalDisplay.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(writableCharacteristic(ExternalDisplay.TEXT_CHARACTERISTIC_UUID));
        service.addCharacteristic(writableCharacteristic(ExternalDisplay.ATTENTION_CHARACTERISTIC_UUID));
        gattServer.addService(service);

        status.setText(R.string.display_waiting);
        startAdvertising();
    }

    private static BluetoothGattCharacteristic writableCharacteristic(UUID uuid) {
        return new BluetoothGattCharacteristic(uuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
    }

    private void startAdvertising() {
        if (advertising) {
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build();
        // The 128-bit service UUID fills the advertisement; the device name
        // (how the picker in Settings tells receivers apart) rides in the
        // scan response
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(ExternalDisplay.SERVICE_UUID))
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        advertising = true;
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        handler.removeCallbacks(heartbeatToggle);
        handler.post(heartbeatToggle);
    }

    private void stopAdvertising() {
        if (!advertising) {
            return;
        }
        advertising = false;
        advertiser.stopAdvertising(advertiseCallback);
        handler.removeCallbacks(heartbeatToggle);
    }

    private void stopBle() {
        stopAdvertising();
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        advertiser = null;
        connectedDevices.clear();
        preparedWrites.clear();
        handler.removeCallbacksAndMessages(null);
        clearText();
        heartbeat.setVisibility(View.GONE);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            handler.post(() -> {
                Log.w(TAG, "Advertising failed to start: " + errorCode);
                advertising = false;
                handler.removeCallbacks(heartbeatToggle);
                status.setText(R.string.display_unsupported);
            });
        }
    };

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            handler.post(() -> {
                Log.d(TAG, "Connection state " + newState + " (status " + status + ") for " + device.getAddress());
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.add(device.getAddress());
                    // Parity with the Ulanzi: one sender at a time; advertising
                    // resumes when it disconnects
                    stopAdvertising();
                    heartbeat.setVisibility(View.VISIBLE);
                    DisplayActivity.this.status.setText("");
                } else {
                    connectedDevices.remove(device.getAddress());
                    if (connectedDevices.isEmpty()) {
                        clearText(); // Privacy: nothing lingers once the keyboard is gone
                        if (gattServer != null) {
                            startAdvertising();
                        }
                    }
                }
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                                                 boolean responseNeeded, int offset, byte[] value) {
            // Long writes (frames beyond the MTU) arrive as prepared chunks
            // and are assembled in onExecuteWrite - NimBLE does this for the
            // Ulanzi transparently, Android's server side does not
            if (preparedWrite) {
                synchronized (preparedWrites) {
                    ByteArrayOutputStream buffer = preparedWrites.get(characteristic.getUuid());
                    if (buffer == null) {
                        buffer = new ByteArrayOutputStream();
                        preparedWrites.put(characteristic.getUuid(), buffer);
                    }
                    buffer.write(value, 0, value.length);
                }
            } else {
                UUID uuid = characteristic.getUuid();
                handler.post(() -> handleWrite(uuid, value));
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            Map<UUID, byte[]> frames = new HashMap<>();
            synchronized (preparedWrites) {
                if (execute) {
                    for (Map.Entry<UUID, ByteArrayOutputStream> entry : preparedWrites.entrySet()) {
                        frames.put(entry.getKey(), entry.getValue().toByteArray());
                    }
                }
                preparedWrites.clear();
            }
            handler.post(() -> {
                for (Map.Entry<UUID, byte[]> frame : frames.entrySet()) {
                    handleWrite(frame.getKey(), frame.getValue());
                }
            });
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    };

    private void handleWrite(UUID characteristic, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        if (ExternalDisplay.TEXT_CHARACTERISTIC_UUID.equals(characteristic)) {
            // 1 type byte ('M'/'W'/'L') + UTF-8 text. Every frame carries the
            // complete text; the types only steer scrolling, which a phone
            // screen does not need - autosizing shows the whole text
            showText(new String(value, 1, value.length - 1, StandardCharsets.UTF_8));
        } else if (ExternalDisplay.ATTENTION_CHARACTERISTIC_UUID.equals(characteristic)) {
            int count = value.length > 1 ? Math.min(Math.max(value[1], 1), 5) : 1;
            if (value[0] == EFFECT_ATTENTION) {
                attention(count);
            } else if (value[0] == EFFECT_YES || value[0] == EFFECT_NO) {
                answerFlash(value[0] == EFFECT_YES);
            }
        }
    }

    private void showText(String message) {
        Log.d(TAG, "Frame (" + message.length() + " chars)");
        text.setText(message);
        status.setText("");
        // Newest text stays visible at the bottom; older lines move off the
        // top, like the Ulanzi tail view with more rows
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        handler.removeCallbacks(idleClear);
        handler.postDelayed(idleClear, IDLE_CLEAR_MS); // Privacy: never keep old text on the table
    }

    private void clearText() {
        text.setText("");
        handler.removeCallbacks(idleClear);
        if (connectedDevices.isEmpty() && gattServer != null) {
            status.setText(R.string.display_waiting);
        }
    }

    private void attention(int count) {
        Log.d(TAG, "Attention x" + count);
        for (int i = 0; i < count; i++) {
            handler.postDelayed(() -> {
                highlight(getColor(R.color.battery_low)); // Amber: white would drown the white text
                if (attentionSound != null) {
                    attentionSound.play();
                }
            }, i * ATTENTION_PERIOD_MS);
        }
    }

    // Same look and sound as the yes/no keys during normal operation
    private void answerFlash(boolean yes) {
        Log.d(TAG, "Answer flash: " + (yes ? "yes" : "no"));
        soundPool.play(yes ? yesSoundId : noSoundId, 1, 1, 1, 0, 1);
        highlight(getColor(yes ? R.color.yes_green : R.color.no_red));
    }

    /**
     * Fade the flash overlay to a color and back, exactly like
     * YesNo.highlightBackground on the phone: a repeat fades in from the
     * current color and postpones the single fade-out until after the last
     * one - no flashing in between.
     */
    private void highlight(int highlightColor) {
        int currentColor = Color.TRANSPARENT;
        if (flash.getBackground() instanceof ColorDrawable) {
            currentColor = ((ColorDrawable) flash.getBackground()).getColor();
        }
        YesNo.animateBackgroundColorManually(flash, currentColor, highlightColor, FLASH_FADE_MS);

        if (resetFlashRunnable != null) {
            handler.removeCallbacks(resetFlashRunnable);
        }
        resetFlashRunnable = () -> YesNo.animateBackgroundColorManually(flash, highlightColor, Color.TRANSPARENT, FLASH_FADE_MS);
        handler.postDelayed(resetFlashRunnable, FLASH_RESET_DELAY_MS);
    }
}
