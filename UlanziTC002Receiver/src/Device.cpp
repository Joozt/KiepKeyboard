#include "Device.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstring>
#include <thread>

#include "BtBringup.h"
#include "KiepGatt.h"
#include "Log.h"
#include "Panel.h"

namespace kiep {

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

Device& Device::instance() { static Device d; return d; }

static Panel panel;

// Spike 1 wiring (Task 6 replaces this with the presenter): text length -> lit columns, attention -> white.
static void showColumns(size_t n) {
  Frame f;
  f.clear();
  for (size_t x = 0; x < n && x < (size_t)W; x++) f.set((int)x, 8, 255, 255, 255);
  f.set(51, 0, 51, 181, 229);
  panel.send(f);
}
static void gattConnected(int c, void*) { KLOG("ble %s", c ? "connected" : "disconnected"); if (!c) showColumns(0); }
static void gattText(uint16_t off, const uint8_t* v, size_t len, void*) {
  KLOG("text chunk offset %u len %zu: %.*s", off, len, (int)len, (const char*)v);
  showColumns(len);
}
static void gattAttention(const uint8_t* v, size_t len, void*) {
  KLOG("attention effect %u count %u", len > 0 ? v[0] : 0, len > 1 ? v[1] : 0);
  Frame f;
  for (uint8_t& b : f.rgb) b = 255;
  panel.send(f);
  usleep(150 * 1000);
  showColumns(0);
}

void Device::start() {
  enableUsbAdb();
  if (panel.open()) showColumns(0); else KLOGW("panel unavailable");
  std::thread([] {  // bring-up takes seconds; never block the loader thread
    if (btBringup()) {
      kiep_gatt_callbacks cb{gattConnected, gattText, gattAttention, nullptr};
      if (kiep_gatt_start(&cb) != 0) KLOGW("gatt start failed");
    }
  }).detach();
}

void Device::stop() {
  kiep_gatt_stop();
  panel.close();
}

}  // namespace kiep
