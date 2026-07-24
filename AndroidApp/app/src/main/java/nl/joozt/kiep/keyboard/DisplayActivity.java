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
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
    private static final long ATTENTION_FLASH_MS = 300;
    private static final long ATTENTION_PERIOD_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> connectedDevices = new HashSet<>();
    // ponytail: prepared-write buffers keyed by characteristic only - a single
    // sender is the design; key by device too if multiple senders ever matter
    private final Map<UUID, ByteArrayOutputStream> preparedWrites = new HashMap<>();

    private View root;
    private TextView text;
    private TextView status;
    private View heartbeat;
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
        text = findViewById(R.id.displayText);
        status = findViewById(R.id.displayStatus);
        heartbeat = findViewById(R.id.displayHeartbeat);

        // A display on the table: screen stays on at full brightness, no bars
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = 1f;
        getWindow().setAttributes(attributes);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), root);
        insets.hide(WindowInsetsCompat.Type.systemBars());
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

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
                root.setBackgroundColor(Color.WHITE);
                if (attentionSound != null) {
                    attentionSound.play();
                }
                handler.postDelayed(() -> root.setBackgroundColor(Color.BLACK), ATTENTION_FLASH_MS);
            }, i * ATTENTION_PERIOD_MS);
        }
    }

    // Same look and sound as the yes/no keys during normal operation
    private void answerFlash(boolean yes) {
        Log.d(TAG, "Answer flash: " + (yes ? "yes" : "no"));
        soundPool.play(yes ? yesSoundId : noSoundId, 1, 1, 1, 0, 1);
        int color = getColor(yes ? R.color.yes_green : R.color.no_red);
        YesNo.animateBackgroundColorManually(root, Color.BLACK, color, 250);
        handler.postDelayed(() -> YesNo.animateBackgroundColorManually(root, color, Color.BLACK, 250), 600);
    }
}
