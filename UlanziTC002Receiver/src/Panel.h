// The 52x16 LED panel: a pixel MCU behind /dev/spidev0.0, latched by GPIO35.
#pragma once
#include "Presenter.h"

namespace kiep {

class Panel {
 public:
  bool open();                 // spidev + gpio35; logs and returns false on failure
  void send(const Frame& f);   // latch low, 1 ms, 3072-byte frame, 1 ms, latch high
  void close();                // sends black twice (the panel shows a frame when the next one arrives)

 private:
  bool writeGpio(int v);
  int spi_ = -1, gpio_ = -1;
};

}  // namespace kiep
