#include "Panel.h"

#include <fcntl.h>
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#include "Log.h"

namespace kiep {

static const char* SPI_DEV = "/dev/spidev0.0";
static const int ROW_BYTES = 192;  // 52 pixels * 3 bytes + 36 zero bytes per row; 16 rows = 3072 bytes

bool Panel::open() {
  spi_ = ::open(SPI_DEV, O_WRONLY);
  if (spi_ < 0) { KLOGW("panel: open %s failed: %s", SPI_DEV, strerror(errno)); return false; }
  uint8_t mode = SPI_MODE_0, bits = 8;
  uint32_t speed = 10000000;
  ioctl(spi_, SPI_IOC_WR_MODE, &mode);
  ioctl(spi_, SPI_IOC_WR_BITS_PER_WORD, &bits);
  ioctl(spi_, SPI_IOC_WR_MAX_SPEED_HZ, &speed);
  int ex = ::open("/sys/class/gpio/export", O_WRONLY);
  if (ex >= 0) { ::write(ex, "35", 2); ::close(ex); }  // EBUSY when already exported is fine
  int dir = ::open("/sys/class/gpio/gpio35/direction", O_WRONLY);
  if (dir >= 0) { ::write(dir, "out", 3); ::close(dir); }
  gpio_ = ::open("/sys/class/gpio/gpio35/value", O_WRONLY);
  if (gpio_ < 0) { KLOGW("panel: gpio35 value failed: %s", strerror(errno)); return false; }
  writeGpio(1);
  KLOG("panel: open");
  return true;
}

bool Panel::writeGpio(int v) {
  const char c = v ? '1' : '0';
  return pwrite(gpio_, &c, 1, 0) == 1;
}

void Panel::send(const Frame& f) {
  if (spi_ < 0 || gpio_ < 0) return;
  uint8_t buf[H * ROW_BYTES];
  std::memset(buf, 0, sizeof buf);
  for (int y = 0; y < H; y++) std::memcpy(&buf[y * ROW_BYTES], &f.rgb[y * W * 3], W * 3);
  writeGpio(0);
  usleep(1000);
  ::write(spi_, buf, sizeof buf);
  usleep(1000);
  writeGpio(1);
}

void Panel::close() {
  Frame black;
  black.clear();
  send(black);
  usleep(20000);
  send(black);
  if (spi_ >= 0) ::close(spi_);
  if (gpio_ >= 0) ::close(gpio_);
  spi_ = gpio_ = -1;
}

}  // namespace kiep
