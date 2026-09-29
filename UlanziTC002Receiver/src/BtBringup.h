// Bluetooth radio bring-up: kernel modules, hciattach, hci0 up. No Wi-Fi network, ever. Task 4.
#pragma once

namespace kiep {
bool btBringup();  // false = no Bluetooth; the app keeps running without it
}
