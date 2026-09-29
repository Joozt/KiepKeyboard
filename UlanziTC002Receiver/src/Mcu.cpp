// Pixel MCU protocol on /dev/ttyS1 @ 1.5 Mbaud (reimplemented from the documented frame format):
//   FF 55 <cmd> <len> <payload...> <ck_hi> <ck_lo>   checksum = 16-bit sum of all preceding bytes
// Commands used: 0x11 version query, 0x03 battery query.
#include "Mcu.h"

#include <fcntl.h>
#include <poll.h>
#include <termios.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#include "Log.h"

namespace kiep {

bool Mcu::open() {
  fd_ = ::open("/dev/ttyS1", O_RDWR | O_NOCTTY | O_NONBLOCK);
  if (fd_ < 0) { KLOGW("mcu: open ttyS1 failed: %s", strerror(errno)); return false; }
  termios t{};
  tcgetattr(fd_, &t);
  cfmakeraw(&t);
  cfsetispeed(&t, B1500000);
  cfsetospeed(&t, B1500000);
  t.c_cflag |= CLOCAL | CREAD;
  tcsetattr(fd_, TCSANOW, &t);
  tcflush(fd_, TCIOFLUSH);
  return true;
}

bool Mcu::transact(uint8_t cmd, const uint8_t* payload, int plen, std::vector<uint8_t>& resp) {
  if (fd_ < 0 || plen > 32) return false;
  uint8_t tx[40] = {0xFF, 0x55, cmd, (uint8_t)plen};
  memcpy(tx + 4, payload, plen);
  uint16_t ck = 0;
  for (int i = 0; i < 4 + plen; i++) ck += tx[i];
  tx[4 + plen] = ck >> 8;
  tx[5 + plen] = ck & 0xFF;
  std::lock_guard<std::mutex> l(mu_);
  tcflush(fd_, TCIFLUSH);
  if (::write(fd_, tx, 6 + plen) != 6 + plen) return false;
  uint8_t rx[128];
  int n = 0;
  pollfd p{fd_, POLLIN, 0};
  for (int tries = 0; tries < 20; tries++) {  // up to ~1 s
    if (poll(&p, 1, 50) > 0) {
      int r = ::read(fd_, rx + n, sizeof rx - n);
      if (r > 0) n += r;
    }
    while (n >= 6) {
      if (rx[0] != 0xFF || rx[1] != 0x55) { memmove(rx, rx + 1, --n); continue; }
      int len = rx[3], total = len + 6;
      if (n < total) break;  // wait for the rest
      uint16_t sum = 0;
      for (int i = 0; i < total - 2; i++) sum += rx[i];
      bool ok = sum == ((rx[total - 2] << 8) | rx[total - 1]);
      if (ok && rx[2] == cmd) { resp.assign(rx + 4, rx + 4 + len); return true; }
      memmove(rx, rx + total, n - total);  // unsolicited report (mic, keys) or bad checksum: skip it
      n -= total;
    }
  }
  return false;
}

bool Mcu::queryVersion(std::string& out) {
  std::vector<uint8_t> r;
  uint8_t z = 0;
  if (!transact(0x11, &z, 1, r)) return false;
  out.assign(r.begin(), r.end());
  return true;
}

bool Mcu::queryBattery(int& percent, bool& charging) {
  std::vector<uint8_t> r;
  uint8_t z = 0;
  if (!transact(0x03, &z, 1, r) || r.empty()) return false;
  // Payload seen on hardware: 3 bytes, e.g. 55 0c 36 = 85 %, then two bytes that are not a 0/1 charging
  // flag (voltage per the protocol notes). No charging indication is exposed, so `charging` stays false.
  percent = r[0];
  charging = false;
  return true;
}

}  // namespace kiep
