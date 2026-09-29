#include "Device.h"

#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>

#include <cstring>
#include <fstream>

#include "BtBringup.h"
#include "KiepGatt.h"
#include "Log.h"

namespace kiep {

// ponytail: prepared-write chunks (BlueZ delivers long writes per chunk with offsets) are committed once
// no chunk arrived for one settle window. Upgrade: hook BT_ATT_OP_EXEC_WRITE_REQ if the latency matters.
static const int SETTLE_MS = 20;

Device& Device::instance() { static Device d; return d; }

int64_t Device::nowMs() {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return int64_t(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// Make adb over USB-C available from boot: the otg node takes the role name (verified on hardware;
// the sibling "action" files are not writable even as root).
static void enableUsbAdb() {
  const char* p = "/sys/bus/platform/devices/soc:usbotg/otg_role";
  int fd = open(p, O_WRONLY);
  if (fd < 0) { KLOGW("usb: %s: %s", p, strerror(errno)); return; }
  ssize_t n = write(fd, "usb_device", 10);
  close(fd);
  KLOG("usb: otg_role <- usb_device (%zd)", n);
}

static void gattConnected(int c, void* u) { static_cast<Device*>(u)->onConnected(c != 0); }
static void gattText(uint16_t off, const uint8_t* v, size_t len, void* u) { static_cast<Device*>(u)->onTextChunk(off, v, len); }
static void gattAttention(const uint8_t* v, size_t len, void* u) { static_cast<Device*>(u)->onAttention(v, len); }

void Device::start() {
  enableUsbAdb();
  std::ifstream idle("/tmp/kiep/idle_ms");  // debug knob for the verify cycle
  int64_t ms;
  if (idle >> ms) { presenter_.setIdleMs(ms); KLOG("idle override %lld ms", (long long)ms); }
  if (!panel_.open()) KLOGW("panel unavailable");
  tap_.open();
  sound_.init("/tmp/kiep/ui");
  running_ = true;
  render_ = std::thread(&Device::renderLoop, this);
  std::thread([this] {  // bring-up takes seconds; never block the loader thread
    if (btBringup()) {
      kiep_gatt_callbacks cb{gattConnected, gattText, gattAttention, this};
      if (kiep_gatt_start(&cb) != 0) KLOGW("gatt start failed");
    }
  }).detach();
}

void Device::stop() {
  running_ = false;
  if (render_.joinable()) render_.join();
  kiep_gatt_stop();
  input_.stop();
  panel_.close();
}

void Device::onConnected(bool c) {
  std::lock_guard<std::mutex> l(mu_);
  presenter_.onConnected(c, nowMs());
  pendingDirty_ = false;
  KLOG("ble %s", c ? "connected" : "disconnected");
}

void Device::onTextChunk(uint16_t off, const uint8_t* v, size_t len) {
  std::lock_guard<std::mutex> l(mu_);
  if (off == 0) {
    pendingText_.assign(reinterpret_cast<const char*>(v), len);
  } else {
    if (pendingText_.size() < off) pendingText_.resize(off, ' ');
    pendingText_.replace(off, std::string::npos, reinterpret_cast<const char*>(v), len);
  }
  pendingDirty_ = true;
  pendingSinceMs_ = nowMs();
}

void Device::onAttention(const uint8_t* v, size_t len) {
  if (len < 1) return;
  uint8_t count = len >= 2 ? v[1] : 1;
  std::lock_guard<std::mutex> l(mu_);
  presenter_.onAttention(v[0], count, nowMs());
  KLOG("attention effect %u count %u", v[0], count);
}

void Device::onKnob(int d) {
  std::lock_guard<std::mutex> l(mu_);
  presenter_.setBrightnessLevel(presenter_.brightnessLevel() + d);
}
void Device::onKnobPress() {
  std::lock_guard<std::mutex> l(mu_);
  presenter_.replay(nowMs());
}
void Device::setBatteryLow(bool low) {
  std::lock_guard<std::mutex> l(mu_);
  presenter_.setBatteryLow(low);
}

void Device::renderLoop() {
  sched_param sp{};
  sp.sched_priority = 1;
  if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0) {
    if (nice(-10) == -1) KLOGW("render: could not raise priority");
  }
  timespec next;
  clock_gettime(CLOCK_MONOTONIC, &next);
  Frame f;
  while (running_) {
    next.tv_nsec += TICK_MS * 1000000L;
    while (next.tv_nsec >= 1000000000L) { next.tv_nsec -= 1000000000L; next.tv_sec++; }
    clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, nullptr);
    int64_t now = nowMs();
    bool changed;
    int beeps;
    {
      std::lock_guard<std::mutex> l(mu_);
      if (pendingDirty_ && now - pendingSinceMs_ >= SETTLE_MS && !pendingText_.empty()) {
        presenter_.onText(pendingText_[0], pendingText_.substr(1), now);
        pendingDirty_ = false;
        KLOG("frame '%c' %zu bytes", pendingText_[0], pendingText_.size() - 1);
      }
      changed = presenter_.tick(now, f);
      beeps = presenter_.takeBeeps();
    }
    if (changed) {
      panel_.send(f);
      tap_.write(f, now);
    }
    if (beeps) sound_.beep(beeps);
  }
}

}  // namespace kiep
