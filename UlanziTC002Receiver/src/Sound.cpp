// Stub until Task 8.
#include "Sound.h"

namespace kiep {
void Sound::init(const std::string& resDir) { path_ = resDir + "/beep.wav"; }
void Sound::beep(int) {}
}  // namespace kiep
