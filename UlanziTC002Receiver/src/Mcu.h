// UART protocol to the pixel MCU on /dev/ttyS1 (version, battery). Implemented in Task 7.
#pragma once
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace kiep {

class Mcu {
 public:
  bool open();
  bool queryVersion(std::string& out);
  bool queryBattery(int& percent, bool& charging);

 private:
  bool transact(uint8_t cmd, const uint8_t* payload, int plen, std::vector<uint8_t>& resp);
  int fd_ = -1;
  std::mutex mu_;
};

}  // namespace kiep
