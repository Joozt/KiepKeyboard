// Attention beep: beep.wav through the SDK media player (audio-utility), on its own thread.
#include "Sound.h"

#include <unistd.h>

#include <thread>

#include "Log.h"
#include "Presenter.h"
#ifdef KIEP_SOUND
#include <audio_manager.h>
#include <media_player.h>
#endif

namespace kiep {

static const int VOLUME = 25;  // SDK volume for stock level 6 of 6 (mapping 0,15,17,19,21,23,25); level 3 was too soft

static bool exists(const std::string& p) { return access(p.c_str(), R_OK) == 0; }

void Sound::init(const std::string& resDir) {
  // A user-supplied beep.mp3 (any short sound) wins over the generated beep.wav
  path_ = exists(resDir + "/beep.mp3") ? resDir + "/beep.mp3" : resDir + "/beep.wav";
#ifdef KIEP_SOUND
  base::AudioManager::instance().setVolume(VOLUME);
  base::AudioManager::instance().setMute(false);
  ready_ = true;
  KLOG("sound: ready (%s)", path_.c_str());
#else
  KLOG("sound: built without audio");
#endif
}

void Sound::beep(int count) {
  if (!ready_ || count <= 0) return;
  // Flash onsets arrive one per render tick; queue them and let one worker drain the queue, so no beep
  // is dropped while the previous one still plays (3 requested came out as 2 before).
  pending_ += count;
  if (busy_.exchange(true)) return;
  std::thread([this] {
    for (;;) {
      int n = pending_.exchange(0);
      if (n == 0) {
        busy_ = false;
        if (pending_.load() == 0 || busy_.exchange(true)) return;  // nothing queued, or another worker took over
        continue;
      }
#ifdef KIEP_SOUND
      // play() on a busy player is ignored, so wait for each completion. Works for the 120 ms wav and
      // for a user's longer beep.mp3 alike (a long sound trails the flashes; keep custom sounds short).
      base::MediaPlayer player;
      std::atomic<bool> done{false};
      player.onCompletion([&done] { done = true; });
      int played = 0;
      for (int i = 0; i < n; i++) {
        done = false;
        if (player.play(path_) != 0) break;
        for (int t = 0; t < 300 && !done; t++) usleep(10 * 1000);  // up to 3 s per sound
        played++;
        usleep(ATTENTION_OFF_MS * 1000);
      }
      player.stop();
      KLOG("sound: played %d/%d", played, n);
#endif
    }
  }).detach();
}

}  // namespace kiep
