/* BlueZ GATT server + HCI advertising for the KiepDisplay service. Runs on its own thread. Task 4. */
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  void (*on_connected)(int connected, void* user);
  void (*on_text)(uint16_t offset, const uint8_t* value, size_t len, void* user); /* raw chunk; offset 0 starts a frame */
  void (*on_attention)(const uint8_t* value, size_t len, void* user);
  void* user;
} kiep_gatt_callbacks;

int kiep_gatt_start(const kiep_gatt_callbacks* cb); /* 0 = thread started */
void kiep_gatt_stop(void);

#ifdef __cplusplus
}
#endif
