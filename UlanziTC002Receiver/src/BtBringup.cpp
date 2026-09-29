// Bluetooth bring-up without a Wi-Fi network: load the combo-chip modules Bluetooth needs, start the
// stock `hciattach` service (/res/bin/hciattach -n ttyS3 aic, defined in /etc/init.rc), bring hci0 up.
// No wpa_supplicant, no SSID, no DHCP: wlan0 is forced down.
#include "BtBringup.h"

#include <os/SystemProperties.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdlib>
#include <string>

#include "Log.h"

namespace kiep {

static bool exists(const char* p) { struct stat st; return stat(p, &st) == 0; }
static int sh(const char* cmd) { int r = system(cmd); KLOG("bt: `%s` -> %d", cmd, r); return r; }

// Verified on hardware (2026-09-29): bsp alone gives no hci0; bsp + fdrv does. fdrv creates wlan0,
// which stays down and unconfigured.
static const char* MODULES[] = {"/lib/modules/4.9.84/aic8800_bsp.ko", "/lib/modules/4.9.84/aic8800_fdrv.ko"};

static bool hciUp() {
  sh("/res/bin/hciconfig hci0 down");
  SystemProperties::setString("ctl.stop", "hciattach");
  usleep(500 * 1000);
  SystemProperties::setString("ctl.start", "hciattach");
  for (int i = 0; i < 500 && !exists("/sys/class/bluetooth/hci0"); i++) usleep(10 * 1000);
  if (!exists("/sys/class/bluetooth/hci0")) return false;
  return sh("/res/bin/hciconfig hci0 up") == 0;
}

bool btBringup() {
  // The EasyUI framework starts wpa_supplicant when persist.wifi.on=1 (stock setting). Never on our watch.
  SystemProperties::setString("persist.wifi.on", "0");
  SystemProperties::setString("persist.softap.on", "0");
  SystemProperties::setString("ctl.stop", "wpa_supplicant");
  sh("ifconfig wlan0 down");
  for (const char* m : MODULES) { std::string c = std::string("insmod ") + m; sh(c.c_str()); }  // "already loaded" is fine
  if (hciUp()) {
    KLOG("bt: hci0 up");
    sh("ifconfig wlan0 down");  // the radio's Wi-Fi side never associates; verified BLE survives this
    return true;
  }
  KLOGW("bt: no hci0 -- running without Bluetooth");
  return false;
}

}  // namespace kiep
