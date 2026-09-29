// Wires the presenter to the hardware: BLE callbacks in, panel frames out, one render thread at a
// fixed cadence. Everything here is device-only; the logic lives in Presenter (host-tested).
#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "FrameTap.h"
#include "Input.h"
#include "Panel.h"
#include "Presenter.h"
#include "Sound.h"

namespace kiep {

class Device {
 public:
  static Device& instance();
  void start();  // never throws, never exits: every subsystem failure is logged and skipped
  void stop();

  // BLE thread
  void onConnected(bool connected);
  void onTextChunk(uint16_t offset, const uint8_t* value, size_t len);
  void onAttention(const uint8_t* value, size_t len);
  // input thread
  void onKnob(int delta);        // +1 clockwise, -1 counter-clockwise: volume, with the sound as feedback
  void onBrightness(int delta);  // right button +1, left button -1
  void onKnobPress();            // knob press or middle button: replay the last message
  // battery thread
  void setBatteryLow(bool low);

 private:
  void renderLoop();
  static int64_t nowMs();

  std::mutex mu_;  // guards presenter_ and the pending text
  Presenter presenter_;
  std::string pendingText_;
  bool pendingDirty_ = false;
  int64_t pendingSinceMs_ = 0;

  Panel panel_;
  FrameTap tap_;
  Sound sound_;
  Input input_;
  int volume_ = 6;
  std::atomic<int64_t> previewAtMs_{0};  // when to play the volume preview; 0 = none pending
  std::thread render_;
  std::atomic<bool> running_{false};
};

}  // namespace kiep
