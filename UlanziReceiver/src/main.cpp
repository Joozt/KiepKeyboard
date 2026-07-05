// KiepDisplay receiver for the Ulanzi TC001 pixel clock.
//
// BLE peripheral for the KiepKeyboard app (docs/proposals/external-display.md
// and ulanzi-receiver.md in the app repository). No WiFi, no server: the app
// connects directly over BLE and writes stateless frames.
//
//   Text characteristic:      1 type byte + UTF-8 text
//     'M' = message to read   -> scroll once, then hold the tail
//     'L' = live typing buffer -> static tail view, updated in place
//   Attention characteristic: any write -> beep + white flash
//
// Heartbeat pixel (top-right, KiepKeyboard accent blue): blinking = powered
// and advertising, solid = app connected. The display clears on disconnect
// and after 10 minutes without frames. The middle button replays the last
// message. Serial debug logging on 115200.
//
// Hardware map per https://github.com/rroels/ulanzi_tc001_hardware

#include <Arduino.h>
#include <Adafruit_GFX.h>
#include <Adafruit_NeoMatrix.h>
#include <NimBLEDevice.h>

// --- Hardware ---------------------------------------------------------------
static const uint8_t MATRIX_PIN = 32;
static const uint8_t BUZZER_PIN = 15;
static const uint8_t BUTTON_MIDDLE_PIN = 27; // active low
static const int MATRIX_WIDTH = 32;
static const int MATRIX_HEIGHT = 8;
static const uint8_t BRIGHTNESS = 40; // 0-255; fixed in Phase 1

// --- KiepDisplay protocol -----------------------------------------------------
static const char *DEVICE_NAME = "KiepDisplay";
static const char *SERVICE_UUID = "b1e60001-8f2a-4d0c-9e4b-5c3d2a1f0e9d";
static const char *TEXT_CHAR_UUID = "b1e60002-8f2a-4d0c-9e4b-5c3d2a1f0e9d";
static const char *ATTENTION_CHAR_UUID = "b1e60003-8f2a-4d0c-9e4b-5c3d2a1f0e9d";

// --- Behavior ----------------------------------------------------------------
static const unsigned long IDLE_CLEAR_MS = 10UL * 60UL * 1000UL;
static const unsigned long SCROLL_STEP_MS = 40;
static const unsigned long HEARTBEAT_TOGGLE_MS = 500;
static const int CHAR_WIDTH = 6; // classic GFX 5x7 font + 1px spacing
static const uint8_t BUZZER_CHANNEL = 0;

// If the text renders mirrored or interleaved on your unit, adjust the
// NEO_MATRIX_* flags below (serpentine: even rows left-to-right).
Adafruit_NeoMatrix matrix(MATRIX_WIDTH, MATRIX_HEIGHT, MATRIX_PIN,
                          NEO_MATRIX_TOP + NEO_MATRIX_LEFT + NEO_MATRIX_ROWS + NEO_MATRIX_ZIGZAG,
                          NEO_GRB + NEO_KHZ800);

// Frame handoff: the BLE host task writes, loop() reads. Single writer,
// single reader, guarded by the pendingFrame flag.
static char pendingBuffer[256];
static volatile size_t pendingLength = 0;
static volatile char pendingType = 0;
static volatile bool pendingFrame = false;
static volatile bool attentionRequested = false;
static volatile bool connected = false;
static volatile bool disconnected = false;

// Display state (loop() only)
static String currentText;
static char currentType = 0;
static int currentX = 0;
static int scrollX = MATRIX_WIDTH;
static bool scrolling = false;
static unsigned long lastScrollStep = 0;
static unsigned long lastFrameMillis = 0;
static bool buttonWasDown = false;
static bool heartbeatOn = false;
static unsigned long lastHeartbeatToggle = 0;
static bool wasConnected = false;

// Strips UTF-8 down to ASCII: multi-byte sequences become '?'
static String toAscii(const char *bytes, size_t length) {
    String result;
    result.reserve(length);
    for (size_t i = 0; i < length; i++) {
        uint8_t b = (uint8_t) bytes[i];
        if (b < 0x80) {
            result += (char) b;
        } else if (b >= 0xC0) {
            result += '?'; // lead byte of a multi-byte sequence
        } // continuation bytes are skipped
    }
    return result;
}

static int textPixelWidth(const String &text) {
    return text.length() * CHAR_WIDTH;
}

// Composes the full frame: current text plus the heartbeat pixel
// (blinking while advertising, solid while the app is connected)
static void render() {
    matrix.fillScreen(0);
    if (currentText.length() > 0) {
        matrix.setCursor(currentX, 0);
        matrix.print(currentText);
    }
    if (connected || heartbeatOn) {
        matrix.drawPixel(MATRIX_WIDTH - 1, 0, matrix.Color(51, 181, 229)); // #33B5E5, app accent
    }
    matrix.show();
}

static void drawText(int x) {
    currentX = x;
    render();
}

// Show the end of the text when it is wider than the matrix
static int tailPosition() {
    int width = textPixelWidth(currentText);
    return width <= MATRIX_WIDTH ? 0 : MATRIX_WIDTH - width;
}

static void startScroll() {
    scrolling = true;
    scrollX = MATRIX_WIDTH;
    lastScrollStep = 0;
}

static void beep(unsigned int frequency, unsigned long duration) {
    ledcSetup(BUZZER_CHANNEL, frequency, 8);
    ledcAttachPin(BUZZER_PIN, BUZZER_CHANNEL);
    ledcWriteTone(BUZZER_CHANNEL, frequency);
    delay(duration);
    ledcWriteTone(BUZZER_CHANNEL, 0);
    ledcDetachPin(BUZZER_PIN);
    pinMode(BUZZER_PIN, INPUT_PULLDOWN); // prevents buzzer noise when idle
}

static void attention() {
    Serial.println("[kiep] attention");
    for (int i = 0; i < 2; i++) {
        matrix.fillScreen(matrix.Color(255, 255, 255));
        matrix.show();
        beep(1400, 120);
        matrix.fillScreen(0);
        matrix.show();
        delay(80);
    }
    render(); // Restore whatever was showing
}

static void clearDisplay() {
    currentText = "";
    currentType = 0;
    scrolling = false;
    render();
}

// --- BLE ----------------------------------------------------------------------
class TextCallback : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic) override {
        std::string value = characteristic->getValue();
        if (value.empty() || pendingFrame) {
            return; // a pending frame is still being picked up; latest-wins is handled app-side
        }
        size_t length = min(value.size() - 1, sizeof(pendingBuffer));
        memcpy(pendingBuffer, value.data() + 1, length);
        pendingLength = length;
        pendingType = value[0];
        pendingFrame = true;
    }
};

class AttentionCallback : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic) override {
        attentionRequested = true;
    }
};

class ServerCallback : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *server) override {
        connected = true;
    }

    void onDisconnect(NimBLEServer *server) override {
        connected = false;
        disconnected = true; // loop() clears the display (privacy)
        NimBLEDevice::startAdvertising();
    }
};

void setup() {
    Serial.begin(115200);
    Serial.println("[kiep] KiepDisplay receiver starting");

    pinMode(BUZZER_PIN, INPUT_PULLDOWN);
    pinMode(BUTTON_MIDDLE_PIN, INPUT_PULLUP);

    matrix.begin();
    matrix.setTextWrap(false);
    matrix.setBrightness(BRIGHTNESS);
    matrix.setTextColor(matrix.Color(0, 180, 255)); // KiepKeyboard accent blue
    clearDisplay();

    NimBLEDevice::init(DEVICE_NAME);
    NimBLEDevice::setSecurityAuth(true, false, true); // bonding, no MITM, secure connections

    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallback());

    NimBLEService *service = server->createService(SERVICE_UUID);
    NimBLECharacteristic *text = service->createCharacteristic(
            TEXT_CHAR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    text->setCallbacks(new TextCallback());
    NimBLECharacteristic *attentionCharacteristic = service->createCharacteristic(
            ATTENTION_CHAR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    attentionCharacteristic->setCallbacks(new AttentionCallback());
    service->start();

    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->start();
    Serial.println("[kiep] advertising as KiepDisplay");
}

void loop() {
    unsigned long now = millis();

    if (connected != wasConnected) {
        wasConnected = connected;
        Serial.println(connected ? "[kiep] app connected" : "[kiep] app disconnected");
        render();
    }

    if (disconnected) {
        disconnected = false;
        clearDisplay();
    }

    if (pendingFrame) {
        currentText = toAscii(pendingBuffer, pendingLength);
        currentType = pendingType;
        pendingFrame = false;
        lastFrameMillis = now;
        Serial.printf("[kiep] frame '%c' (%u bytes): %s\n",
                      currentType, (unsigned) pendingLength, currentText.c_str());
        if (currentType == 'M' && textPixelWidth(currentText) > MATRIX_WIDTH) {
            startScroll();
        } else {
            scrolling = false;
            drawText(tailPosition());
        }
    }

    if (attentionRequested) {
        attentionRequested = false;
        lastFrameMillis = now;
        attention();
    }

    if (scrolling && now - lastScrollStep >= SCROLL_STEP_MS) {
        lastScrollStep = now;
        drawText(scrollX);
        scrollX--;
        if (scrollX < -textPixelWidth(currentText)) {
            // Scrolled through once: hold the tail
            scrolling = false;
            drawText(tailPosition());
        }
    }

    // Heartbeat: blink while advertising; solid (no re-render needed) while connected
    if (!connected && now - lastHeartbeatToggle >= HEARTBEAT_TOGGLE_MS) {
        lastHeartbeatToggle = now;
        heartbeatOn = !heartbeatOn;
        if (!scrolling) {
            render();
        }
    }

    // Middle button replays the last message
    bool buttonDown = digitalRead(BUTTON_MIDDLE_PIN) == LOW;
    if (buttonDown && !buttonWasDown && currentType == 'M' && currentText.length() > 0) {
        Serial.println("[kiep] replaying last message");
        if (textPixelWidth(currentText) > MATRIX_WIDTH) {
            startScroll();
        } else {
            drawText(tailPosition());
        }
    }
    buttonWasDown = buttonDown;

    // Privacy: never keep old text on the table indefinitely
    if (currentText.length() > 0 && now - lastFrameMillis > IDLE_CLEAR_MS) {
        Serial.println("[kiep] idle clear");
        clearDisplay();
    }

    delay(5);
}
