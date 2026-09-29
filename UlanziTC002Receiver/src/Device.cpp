#include "Device.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstring>

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
