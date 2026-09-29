// Pure presentation logic for the KiepDisplay TC002 receiver: protocol frames in, 52x16 RGB frames out.
// No SDK, no threads, no clock: the caller passes time in, which is what makes it host-testable.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace kiep {

constexpr int W = 52, H = 16;
constexpr int TICK_MS = 30;               // render tick; scrolling moves one pixel per tick
constexpr int HEARTBEAT_TOGGLE_MS = 500;  // blink period while advertising
constexpr int ATTENTION_ON_MS = 120, ATTENTION_OFF_MS = 80, ANSWER_MS = 800;
constexpr int64_t IDLE_CLEAR_MS = 10 * 60 * 1000;
constexpr uint8_t BRIGHTNESS_LEVELS[5] = {16, 48, 96, 160, 255};

struct Frame {
  uint8_t rgb[W * H * 3];  // row-major, 3 bytes per pixel
  void clear();
  void set(int x, int y, uint8_t r, uint8_t g, uint8_t b);
  bool lit(int x, int y) const;
};

class Presenter {
 public:
  void onText(char type, const std::string& utf8, int64_t nowMs);  // 'M' message, 'W' word-append, 'L' live
  void onAttention(uint8_t effect, uint8_t count, int64_t nowMs);  // 1 attention, 2 yes, 3 no
  void onConnected(bool connected, int64_t nowMs);                 // false clears text and flash
  void replay(int64_t nowMs);                                      // knob press: last message from the start
  void setBrightnessLevel(int level);                              // 0..4
  int brightnessLevel() const { return brightness_; }
  void setBatteryLow(bool low) { batteryLow_ = low; }
  void setIdleMs(int64_t ms) { idleMs_ = ms; }
  bool tick(int64_t nowMs, Frame& out);  // advances state, renders; true when `out` differs from the previous tick
  int takeBeeps();                       // attention "on" onsets since the last call

  const std::string& text() const { return text_; }
  bool scrolling() const { return scrolling_; }
  int scrollX() const { return x_; }
  static int textWidth(const std::string& utf8);
  static std::vector<uint32_t> decode(const std::string& utf8);  // UTF-8 -> codepoints; bad -> '?'; \n \r -> ' '

 private:
  void layout(bool extension);
  void clearText();
  void render(Frame& f) const;

  enum Flash { NONE, ATTENTION, YES, NO };
  std::string text_, lastMessage_;
  std::vector<uint32_t> cps_;
  char type_ = 0;
  int width_ = 0, x_ = 0;
  bool scrolling_ = false, connected_ = false, heartbeatOn_ = false, batteryLow_ = false, attentionOn_ = false;
  int brightness_ = 4;
  int64_t idleMs_ = IDLE_CLEAR_MS, lastTextMs_ = 0, lastHeartbeatMs_ = 0, flashStartMs_ = 0;
  Flash flash_ = NONE;
  int flashCount_ = 0, beeps_ = 0, beepedOnsets_ = 0;
  Frame last_{};
  bool haveLast_ = false;
};

}  // namespace kiep
