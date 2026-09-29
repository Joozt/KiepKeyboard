// Attention beep through the SDK media player. Implemented in Task 8.
#pragma once
#include <atomic>
#include <string>

namespace kiep {

class Sound {
 public:
  void init(const std::string& resDir);  // no-op when audio is unavailable
  void beep(int count);                  // queues `count` plays; a worker thread plays them back to back

 private:
  std::string path_;
  bool ready_ = false;
  std::atomic<int> pending_{0};
  std::atomic<bool> busy_{false};
};

}  // namespace kiep
