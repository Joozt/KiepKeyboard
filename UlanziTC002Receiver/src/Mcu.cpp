// Stub until Task 7.
#include "Mcu.h"

namespace kiep {
bool Mcu::open() { return false; }
bool Mcu::queryVersion(std::string&) { return false; }
bool Mcu::queryBattery(int&, bool&) { return false; }
bool Mcu::transact(uint8_t, const uint8_t*, int, std::vector<uint8_t>&) { return false; }
}  // namespace kiep
