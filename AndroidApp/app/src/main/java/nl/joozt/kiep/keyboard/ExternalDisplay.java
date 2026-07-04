package nl.joozt.kiep.keyboard;

import java.util.UUID;

/**
 * Sends typed text to nearby paired displays over BLE - never the internet,
 * by design. See docs/proposals/external-display.md for the protocol.
 */
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
}
