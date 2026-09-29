#include "Device.h"

#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>

#include <cstring>
#include <fstream>

#include <sys/stat.h>

#include <manager/ConfigManager.h>

#include "BtBringup.h"
#include "KiepGatt.h"
#include "Log.h"
#include "Mcu.h"

namespace kiep {

static const char* BRIGHTNESS_FILE = "/data/kiep/brightness";  // /data is the only persistent writable filesystem
static const char* VOLUME_FILE = "/data/kiep/volume";
static const int KEY_KNOB = 0x67, KEY_LEFT = 0x6C, KEY_MIDDLE = 0x69, KEY_RIGHT = 0x6A;  // evdev codes
static const int BATTERY_LOW_PERCENT = 15;
static const int VOLUME_PREVIEW_MS = 300;  // knob rest time before the volume preview sound

// resPath from the loader config (/tmp/kiep/ui/ when deployed, /res/ui/kiep/ when installed): ask the SDK,
// fall back to reading the config files in the loader's own lookup order.
static std::string resourceDir() {
  std::string p = CONFIGMANAGER->getResFilePath("beep.wav");
  size_t slash = p.rfind('/');
  if (slash != std::string::npos && slash > 0) return p.substr(0, slash);
  for (const char* cfg : {"/tmp/EasyUI.cfg", "/res/etc/EasyUI.cfg"}) {
    std::ifstream f(cfg);
    std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    size_t k = s.find("\"resPath\"");
    if (k == std::string::npos) continue;
    size_t a = s.find('"', s.find(':', k) + 1), b = s.find('"', a + 1);
    if (a == std::string::npos || b == std::string::npos) continue;
    std::string dir = s.substr(a + 1, b - a - 1);
    while (!dir.empty() && dir.back() == '/') dir.pop_back();
    return dir;
  }
  return "/tmp/kiep/ui";
}

static int readLevel(const char* file, int fallback) {
  std::ifstream f(file);
  int v;
  return (f >> v) ? v : fallback;
}
static void writeLevel(const char* file, int v) {
  mkdir("/data/kiep", 0755);
  std::ofstream(file) << v;
}

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
  std::string resDir = resourceDir();
  volume_ = readLevel(VOLUME_FILE, 6);
  sound_.init(resDir, volume_);
  presenter_.setBrightnessLevel(readLevel(BRIGHTNESS_FILE, 4));
  KLOG("brightness level %d, volume level %d", presenter_.brightnessLevel(), volume_);
  running_ = true;
  render_ = std::thread(&Device::renderLoop, this);
  input_.start([this](int code, int value) {
                 if (value != 1) return;  // press only
                 if (code == KEY_KNOB || code == KEY_MIDDLE) onKnobPress();
                 else if (code == KEY_LEFT) onBrightness(-1);
                 else if (code == KEY_RIGHT) onBrightness(+1);
               },
               [this](int delta) { onKnob(delta); });
  std::thread([this] {  // battery poll; the MCU link also proves itself here
    Mcu mcu;
    if (!mcu.open()) return;
    std::string v;
    if (mcu.queryVersion(v)) KLOG("mcu version %s", v.c_str()); else KLOGW("mcu: no version reply");
    while (running_) {
      int pct; bool chg;
      if (mcu.queryBattery(pct, chg)) setBatteryLow(pct < BATTERY_LOW_PERCENT && !chg);
      for (int i = 0; i < 300 && running_; i++) usleep(100 * 1000);  // 30 s
    }
  }).detach();
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
  int v = volume_ + d;
  volume_ = v < 0 ? 0 : v > 6 ? 6 : v;
  sound_.setLevel(volume_);
  writeLevel(VOLUME_FILE, volume_);
  KLOG("volume level %d", volume_);
  previewAtMs_ = nowMs() + VOLUME_PREVIEW_MS;  // one sound at the final level once the knob rests (silent at 0)
}

void Device::onBrightness(int d) {
  int level;
  {
    std::lock_guard<std::mutex> l(mu_);
    presenter_.setBrightnessLevel(presenter_.brightnessLevel() + d);
    level = presenter_.brightnessLevel();
  }
  writeLevel(BRIGHTNESS_FILE, level);
  KLOG("brightness level %d", level);
}
void Device::onKnobPress() {
  std::lock_guard<std::mutex> l(mu_);
  presenter_.replay(nowMs());
  KLOG("replay");
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
    int64_t deadline = int64_t(next.tv_sec) * 1000 + next.tv_nsec / 1000000;
    if (now - deadline > TICK_MS) {  // stalled: drop the missed ticks instead of replaying them back to back
      clock_gettime(CLOCK_MONOTONIC, &next);  // (the panel needs >= 15 ms between frames)
      KLOGW("render: %lld ms late, resyncing", (long long)(now - deadline));
    }
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
    int64_t preview = previewAtMs_.load();
    if (preview && now >= preview && previewAtMs_.compare_exchange_strong(preview, 0)) sound_.beep(1);
  }
}

}  // namespace kiep
