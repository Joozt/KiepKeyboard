// Wires the presenter to the hardware. Spike 0 version: USB adb + one pixel.
#pragma once

namespace kiep {

class Device {
 public:
  static Device& instance();
  void start();  // never throws, never exits: every subsystem failure is logged and skipped
  void stop();
};

}  // namespace kiep
