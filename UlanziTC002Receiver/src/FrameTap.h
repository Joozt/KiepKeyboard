// Debug-only ring of the frames handed to the panel, for tools/screen.py. Task 6.
#pragma once
#include <cstdint>

#include "Presenter.h"

namespace kiep {

// /tmp/kiep/frames.bin: 16-byte header "KIEPTAP1" + uint32 slots + uint32 next; then slots of [int64 ms][3072 bytes rgb]
class FrameTap {
 public:
  bool open();
  void write(const Frame& f, int64_t nowMs);

 private:
  int fd_ = -1;
  uint32_t next_ = 0;
};

}  // namespace kiep
