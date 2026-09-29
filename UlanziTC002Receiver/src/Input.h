// Knob and buttons via evdev. Implemented in Task 7.
#pragma once
#include <atomic>
#include <functional>
#include <thread>
#include <vector>

namespace kiep {

// evdev key codes: knob press 0x67, left 0x6C, middle 0x69, right 0x6A; value 1 = press, 0 = release
class Input {
 public:
  using Fn = std::function<void(int code, int value)>;
  bool start(Fn onKey, std::function<void(int delta)> onKnob);  // delta +1 clockwise, -1 counter-clockwise
  void stop();

 private:
  std::vector<int> fds_;
  std::thread thread_;
  std::atomic<bool> running_{false};
};

}  // namespace kiep
