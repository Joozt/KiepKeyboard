#include "Device.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstring>

#include "Log.h"
#include "Panel.h"

namespace kiep {

// Make adb over USB-C available from boot. The otg node takes a role name; the sibling files are
// actions (see atomicstack DEVICE.md). Writing both is harmless; the spike records which one works.
static void enableUsbAdb() {
  static const char* paths[] = {"/sys/bus/platform/devices/soc:usbotg/otg_role",
                                "/sys/bus/platform/devices/soc:usbotg/usb_device"};
  for (const char* p : paths) {
    int fd = open(p, O_WRONLY);
    if (fd < 0) { KLOGW("usb: %s: %s", p, strerror(errno)); continue; }
    const char* v = strstr(p, "otg_role") ? "usb_device" : "1";
    ssize_t n = write(fd, v, strlen(v));
    close(fd);
    KLOG("usb: wrote '%s' to %s (%zd)", v, p, n);
  }
}

Device& Device::instance() { static Device d; return d; }

static Panel panel;

void Device::start() {
  enableUsbAdb();
  if (panel.open()) {
    Frame f;
    f.clear();
    f.set(51, 0, 51, 181, 229);
    panel.send(f);
    usleep(20000);
    panel.send(f);
    KLOG("spike0: pixel sent");
  }
}

void Device::stop() { panel.close(); }

}  // namespace kiep
