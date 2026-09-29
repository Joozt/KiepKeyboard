// Knob + buttons via evdev: /dev/input/event67 = gpio buttons (EV_KEY), /dev/input/event68 = knob (EV_ABS).
#include "Input.h"

#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <unistd.h>

#include "Log.h"

namespace kiep {

static const char* NODES[] = {"/dev/input/event67", "/dev/input/event68"};

bool Input::start(Fn onKey, std::function<void(int)> onKnob) {
  for (const char* n : NODES) {
    int fd = ::open(n, O_RDONLY | O_NONBLOCK);
    if (fd >= 0) fds_.push_back(fd); else KLOGW("input: %s missing", n);
  }
  if (fds_.empty()) return false;
  running_ = true;
  thread_ = std::thread([this, onKey, onKnob] {
    int lastAbs = -1;
    std::vector<pollfd> p;
    for (int fd : fds_) p.push_back({fd, POLLIN, 0});
    while (running_) {
      if (poll(p.data(), p.size(), 200) <= 0) continue;
      for (auto& e : p) {
        if (!(e.revents & POLLIN)) continue;
        input_event ev;
        while (::read(e.fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
          if (ev.type == EV_KEY) {
            onKey(ev.code, ev.value);
          } else if (ev.type == EV_ABS) {
            // knob rotation arrives as ABS value pairs (verified on hardware 2026-09-29):
            // 0x8 -> 0x1 clockwise, 0xD -> 0xB counter-clockwise
            if (ev.value == 0x1 && lastAbs == 0x8) onKnob(+1);
            else if (ev.value == 0xB && lastAbs == 0xD) onKnob(-1);
            lastAbs = ev.value;
          }
        }
      }
    }
  });
  return true;
}

void Input::stop() {
  running_ = false;
  if (thread_.joinable()) thread_.join();
  for (int fd : fds_) ::close(fd);
  fds_.clear();
}

}  // namespace kiep
