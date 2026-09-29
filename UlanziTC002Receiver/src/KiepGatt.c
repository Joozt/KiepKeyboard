/* KiepDisplay GATT server: BlueZ shared GATT library over a raw L2CAP ATT socket, advertising via
 * raw HCI commands. Runs the BlueZ mainloop on its own thread. Single central at a time.
 * Protocol: docs/proposals/external-display.md. */
#include "KiepGatt.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <android/log.h>

/* BlueZ headers: order matters (att-types.h has no include guard; gatt-db.h needs bt_uuid_t) */
#include "ble/bluetooth.h"
#include "ble/hci.h"
#include "ble/hci_lib.h"
#include "ble/l2cap.h"
#include "ble/uuid.h"
#include "ble/mainloop.h"
#include "ble/att.h"
#include "ble/gatt-db.h"
#include "ble/gatt-server.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "kiep", __VA_ARGS__)
#define ATT_CID 4
#define SERVICE_UUID "b1e60001-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
#define TEXT_UUID "b1e60002-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
#define ATTENTION_UUID "b1e60003-8f2a-4d0c-9e4b-5c3d2a1f0e9d"
#define SERVER_MTU 517 /* the app negotiates 247; long writes are reassembled by the library */

static const char NAME[] = "KiepDisplay";
/* Service UUID, little-endian, for the advertising payload (0x07 = complete list of 128-bit UUIDs) */
static const uint8_t SERVICE_UUID_LE[16] = {0x9d, 0x0e, 0x1f, 0x2a, 0x3d, 0x5c, 0x4b, 0x9e,
                                            0x0c, 0x4d, 0x2a, 0x8f, 0x01, 0x00, 0xe6, 0xb1};

static kiep_gatt_callbacks g_cb;
static struct gatt_db* g_db;
static int g_hci = -1, g_listen = -1;
static struct bt_att* g_att;
static struct bt_gatt_server* g_server;
static pthread_t g_thread;
static int g_running;

/* LE controller command with status check and retries: right after hciattach the AIC controller was
 * seen answering LE Set Advertising Data with a Hardware Error event instead of Command Complete. */
static int le_cmd(uint16_t ocf, void* param, uint8_t plen) {
  struct hci_request rq;
  uint8_t status;
  memset(&rq, 0, sizeof rq);
  rq.ogf = OGF_LE_CTL; rq.ocf = ocf; rq.cparam = param; rq.clen = plen; rq.rparam = &status; rq.rlen = 1;
  for (int i = 0; i < 5; i++) {
    status = 0xff;
    if (hci_send_req(g_hci, &rq, 1000) == 0 && status == 0) return 0;
    LOG("gatt: LE cmd 0x%04x attempt %d failed (status 0x%02x)", ocf, i + 1, status);
    usleep(100 * 1000);
  }
  return -1;
}

static void advertise(int on) {
  if (g_hci < 0) return;
  if (on) {
    le_set_advertising_parameters_cp p;
    memset(&p, 0, sizeof p);
    p.min_interval = htobs(0x00A0); /* 100 ms */
    p.max_interval = htobs(0x00F0); /* 150 ms */
    p.advtype = 0x00;               /* ADV_IND: connectable, undirected */
    p.own_bdaddr_type = LE_PUBLIC_ADDRESS;
    p.chan_map = 7;
    le_cmd(OCF_LE_SET_ADVERTISING_PARAMETERS, &p, LE_SET_ADVERTISING_PARAMETERS_CP_SIZE);

    le_set_advertising_data_cp ad;
    memset(&ad, 0, sizeof ad);
    uint8_t* d = ad.data;
    d[0] = 2; d[1] = 0x01; d[2] = 0x06; /* flags: LE general discoverable, BR/EDR not supported */
    d[3] = 17; d[4] = 0x07; memcpy(&d[5], SERVICE_UUID_LE, 16);
    ad.length = 21;
    le_cmd(OCF_LE_SET_ADVERTISING_DATA, &ad, LE_SET_ADVERTISING_DATA_CP_SIZE);

    le_set_scan_response_data_cp sr; /* the name does not fit next to a 128-bit UUID: scan response */
    memset(&sr, 0, sizeof sr);
    sr.data[0] = (uint8_t)(1 + strlen(NAME)); sr.data[1] = 0x09; memcpy(&sr.data[2], NAME, strlen(NAME));
    sr.length = (uint8_t)(2 + strlen(NAME));
    le_cmd(OCF_LE_SET_SCAN_RESPONSE_DATA, &sr, LE_SET_SCAN_RESPONSE_DATA_CP_SIZE);
  }
  le_set_advertise_enable_cp en;
  memset(&en, 0, sizeof en);
  en.enable = on ? 0x01 : 0x00;
  int r = le_cmd(OCF_LE_SET_ADVERTISE_ENABLE, &en, LE_SET_ADVERTISE_ENABLE_CP_SIZE);
  LOG("gatt: advertising %s (%s)", on ? "on" : "off", r == 0 ? "ok" : "FAILED");
}

static void on_text_write(struct gatt_db_attribute* a, unsigned int id, uint16_t off, const uint8_t* v,
                          size_t len, uint8_t op, struct bt_att* att, void* u) {
  (void)op; (void)att; (void)u;
  if (g_cb.on_text) g_cb.on_text(off, v, len, g_cb.user);
  gatt_db_attribute_write_result(a, id, 0);
}

static void on_attention_write(struct gatt_db_attribute* a, unsigned int id, uint16_t off, const uint8_t* v,
                               size_t len, uint8_t op, struct bt_att* att, void* u) {
  (void)off; (void)op; (void)att; (void)u;
  if (g_cb.on_attention) g_cb.on_attention(v, len, g_cb.user);
  gatt_db_attribute_write_result(a, id, 0);
}

static void build_db(void) {
  bt_uuid_t u;
  g_db = gatt_db_new();
  bt_string_to_uuid(&u, SERVICE_UUID);
  struct gatt_db_attribute* svc = gatt_db_add_service(g_db, &u, true, 8);
  bt_string_to_uuid(&u, TEXT_UUID);
  gatt_db_service_add_characteristic(svc, &u, BT_ATT_PERM_WRITE,
                                     BT_GATT_CHRC_PROP_WRITE | BT_GATT_CHRC_PROP_WRITE_WITHOUT_RESP,
                                     NULL, on_text_write, NULL);
  bt_string_to_uuid(&u, ATTENTION_UUID);
  gatt_db_service_add_characteristic(svc, &u, BT_ATT_PERM_WRITE,
                                     BT_GATT_CHRC_PROP_WRITE | BT_GATT_CHRC_PROP_WRITE_WITHOUT_RESP,
                                     NULL, on_attention_write, NULL);
  gatt_db_service_set_active(svc, true);
}

static void on_disconnect(int err, void* u) {
  (void)err; (void)u;
  LOG("gatt: disconnected");
  if (g_server) { bt_gatt_server_unref(g_server); g_server = NULL; }
  if (g_att) { bt_att_unref(g_att); g_att = NULL; }
  if (g_cb.on_connected) g_cb.on_connected(0, g_cb.user);
  advertise(1);
}

static void on_accept(int fd, uint32_t ev, void* u) {
  (void)ev; (void)u;
  struct sockaddr_l2 addr;
  socklen_t alen = sizeof addr;
  int cfd = accept(fd, (struct sockaddr*)&addr, &alen);
  if (cfd < 0) return;
  if (g_att) { close(cfd); return; } /* one central at a time */
  g_att = bt_att_new(cfd, false);
  bt_att_set_close_on_unref(g_att, true);
  bt_att_register_disconnect(g_att, on_disconnect, NULL, NULL);
  g_server = bt_gatt_server_new(g_db, g_att, SERVER_MTU, 0);
  /* ADV_IND stops by itself on connection; an explicit disable is answered with 0x0c Command Disallowed */
  LOG("gatt: connected");
  if (g_cb.on_connected) g_cb.on_connected(1, g_cb.user);
}

static int listen_att(void) {
  int fd = socket(PF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
  if (fd < 0) return -1;
  struct sockaddr_l2 a;
  memset(&a, 0, sizeof a);
  a.l2_family = AF_BLUETOOTH;
  a.l2_cid = htobs(ATT_CID);
  bacpy(&a.l2_bdaddr, BDADDR_ANY);
  a.l2_bdaddr_type = BDADDR_LE_PUBLIC;
  if (bind(fd, (struct sockaddr*)&a, sizeof a) < 0) { close(fd); return -1; }
  struct bt_security sec = {.level = BT_SECURITY_LOW, .key_size = 0};
  setsockopt(fd, SOL_BLUETOOTH, BT_SECURITY, &sec, sizeof sec);
  if (listen(fd, 1) < 0) { close(fd); return -1; }
  return fd;
}

static void* thread_main(void* arg) {
  (void)arg;
  mainloop_init();
  build_db();
  g_hci = hci_open_dev(0);
  if (g_hci < 0) { LOG("gatt: hci_open_dev failed: %s", strerror(errno)); return NULL; }
  g_listen = listen_att();
  if (g_listen < 0) { LOG("gatt: l2cap listen failed: %s", strerror(errno)); return NULL; }
  mainloop_add_fd(g_listen, EPOLLIN, on_accept, NULL, NULL);
  advertise(1);
  mainloop_run();
  return NULL;
}

int kiep_gatt_start(const kiep_gatt_callbacks* cb) {
  g_cb = *cb;
  g_running = pthread_create(&g_thread, NULL, thread_main, NULL) == 0;
  return g_running ? 0 : -1;
}

void kiep_gatt_stop(void) {
  if (!g_running) return;
  advertise(0);
  mainloop_quit();
  pthread_join(g_thread, NULL);
  g_running = 0;
}
