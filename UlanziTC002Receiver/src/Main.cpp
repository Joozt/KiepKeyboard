// Entry points the stock zkgui loader resolves by name (dlsym) from libzkgui.so.
#include <app/Activity.h>
#include <entry/EasyUIContext.h>
#include <os/SystemProperties.h>

#include "Device.h"
#include "Log.h"

// The loader insists on an activity + UI resource even though the LED panel bypasses the LCD pipeline.
class mainActivity : public Activity {
 protected:
  const char* getAppName() const override { return "main.ftu"; }
};
REGISTER_ACTIVITY(mainActivity);

extern "C" {
void onEasyUIInit(EasyUIContext*) {
  SystemProperties::setString("sys.zkapp.state", "running");  // first statement: 15 s boot watchdog
  KLOG("KiepDisplay TC002 starting");
  kiep::Device::instance().start();
}
void onEasyUIDeinit(EasyUIContext*) { kiep::Device::instance().stop(); }
const char* onStartupApp(EasyUIContext*) { return "mainActivity"; }
}
