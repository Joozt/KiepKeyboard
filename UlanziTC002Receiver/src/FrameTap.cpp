// Debug-only ring of the frames handed to the panel (KIEP_FRAME_TAP, Debug builds). tools/screen.py reads it.
#include "FrameTap.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>

#include "Log.h"

namespace kiep {

static const int SLOTS = 64, SLOT = 8 + W * H * 3, HDR = 16;

bool FrameTap::open() {
#ifndef KIEP_FRAME_TAP
  return false;
#else
  mkdir("/tmp/kiep", 0755);
  fd_ = ::open("/tmp/kiep/frames.bin", O_RDWR | O_CREAT | O_TRUNC, 0644);
  if (fd_ < 0) return false;
  uint8_t hdr[HDR] = {'K', 'I', 'E', 'P', 'T', 'A', 'P', '1'};
  uint32_t slots = SLOTS, next = 0;
  memcpy(hdr + 8, &slots, 4);
  memcpy(hdr + 12, &next, 4);
  pwrite(fd_, hdr, HDR, 0);
  ftruncate(fd_, HDR + SLOTS * SLOT);
  KLOG("frame tap on");
  return true;
#endif
}

void FrameTap::write(const Frame& f, int64_t nowMs) {
  if (fd_ < 0) return;
  uint8_t slot[SLOT];
  memcpy(slot, &nowMs, 8);
  memcpy(slot + 8, f.rgb, W * H * 3);
  pwrite(fd_, slot, SLOT, HDR + next_ * SLOT);
  next_ = (next_ + 1) % SLOTS;
  uint32_t n = next_;
  pwrite(fd_, &n, 4, 12);
}

}  // namespace kiep
