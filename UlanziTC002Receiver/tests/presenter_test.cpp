// Host tests for the presenter: assert-based, no framework. Run via `docker compose run --rm test`.
#include "Presenter.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace kiep;

static int minLit(const Frame& f, int yFrom = 0, int yTo = H - 1) {  // ignores heartbeat column 51
  for (int x = 0; x < W - 1; x++)
    for (int y = yFrom; y <= yTo; y++)
      if (f.lit(x, y)) return x;
  return -1;
}
static int maxLit(const Frame& f) {
  for (int x = W - 2; x >= 0; x--)
    for (int y = 0; y < H; y++)
      if (f.lit(x, y)) return x;
  return -1;
}
static int litCount(const Frame& f) {
  int n = 0;
  for (int x = 0; x < W - 1; x++)
    for (int y = 0; y < H; y++) n += f.lit(x, y);
  return n;
}
static void run(Presenter& p, Frame& f, int64_t& t, int ticks) {
  for (int i = 0; i < ticks; i++) { t += TICK_MS; p.tick(t, f); }
}
static uint8_t* px(Frame& f, int x, int y) { return &f.rgb[(y * W + x) * 3]; }

int main() {
  {  // M that fits is centered: left margin == right margin within 2 px (column 51 is reserved)
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onText('M', "Hi", t); p.tick(t, f);
    assert(!p.scrolling());
    int l = minLit(f), r = maxLit(f);
    assert(l >= 0 && std::abs(l - (W - 2 - r)) <= 2);
  }
  {  // M wider than the panel scrolls in from the right, then holds the tail
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    int w = Presenter::textWidth("Hallo allemaal"); assert(w > W);
    p.onText('M', "Hallo allemaal", t); p.tick(t, f); assert(p.scrolling() && p.scrollX() == W - 1);
    run(p, f, t, 5); assert(p.scrollX() == W - 6);
    run(p, f, t, 500); assert(!p.scrolling() && p.scrollX() == W - w && maxLit(f) >= W - 3);
  }
  {  // W extension never restarts a running scroll; a non-extension does
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onText('M', "Hallo allemaal", t); run(p, f, t, 10); int x = p.scrollX();
    p.onText('W', "Hallo allemaal hier", t); assert(p.scrollX() == x && p.scrolling());
    p.onText('M', "Hallo allemaal hier", t); assert(p.scrollX() == W);
  }
  {  // W extension of a held (fitting) message resumes scrolling from its position
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onText('M', "Hoi", t); p.tick(t, f); assert(!p.scrolling()); int x = p.scrollX();
    p.onText('W', "Hoi allemaal samen", t); assert(p.scrolling() && p.scrollX() == x);
  }
  {  // L: left-aligned when it fits, right-aligned tail when not, never scrolls
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onText('L', "ab", t); p.tick(t, f); assert(minLit(f) <= 2 && !p.scrolling());
    p.onText('L', "abcdefghijklmnop", t); p.tick(t, f); assert(!p.scrolling() && maxLit(f) >= W - 3);
  }
  {  // newline renders as a space
    Presenter a, b; Frame fa, fb; int64_t t = 0; a.onConnected(true, t); b.onConnected(true, t);
    a.onText('L', "a\nb", t); b.onText('L', "a b", t); a.tick(t, fa); b.tick(t, fb);
    assert(std::memcmp(fa.rgb, fb.rgb, sizeof fa.rgb) == 0);
  }
  {  // accents render (ink in rows 1-3 for 'é', none for 'e'); unknown codepoint / bad UTF-8 -> '?'
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onText('L', "e", t); p.tick(t, f); assert(minLit(f, 1, 3) == -1);
    p.onText('L', "\xC3\xA9", t); p.tick(t, f); assert(minLit(f, 1, 3) >= 0);
    assert(Presenter::textWidth("\xF0\x9F\x98\x80") == Presenter::textWidth("?"));  // emoji
    assert(Presenter::textWidth("\xC3") == Presenter::textWidth("?"));              // truncated sequence
    assert(Presenter::textWidth("\xC3" "a") == Presenter::textWidth("?a"));         // resyncs after a bad byte
  }
  {  // rapid updates: only the latest text renders
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    for (int i = 0; i < 100; i++) p.onText('L', std::string(1, char('a' + i % 26)), t);
    p.tick(t, f); assert(p.text() == "v");
  }
  {  // idle clear
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.setIdleMs(1000);
    p.onText('M', "Hoi", t); run(p, f, t, 40); assert(litCount(f) == 0 && p.text().empty());
  }
  {  // attention: white frames, one beep per onset, text restored afterwards
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.onText('M', "Hoi", t);
    p.onAttention(1, 2, t); p.tick(t, f);
    assert(litCount(f) == (W - 1) * H && px(f, 0, 0)[0] == 255 && px(f, 0, 0)[2] == 255);
    run(p, f, t, 20); assert(p.takeBeeps() == 2); assert(litCount(f) > 0 && litCount(f) < (W - 1) * H);
  }
  {  // yes = green, no = red, for ANSWER_MS
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t);
    p.onAttention(2, 1, t); p.tick(t, f); assert(px(f, 0, 0)[1] > 0 && px(f, 0, 0)[0] == 0);
    run(p, f, t, ANSWER_MS / TICK_MS + 2); assert(litCount(f) == 0);
    p.onAttention(3, 1, t); p.tick(t, f); assert(px(f, 0, 0)[0] > 0 && px(f, 0, 0)[1] == 0);
  }
  {  // disconnect clears text and an active flash
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.onText('M', "Hoi", t); p.onAttention(1, 5, t);
    p.onConnected(false, t); p.tick(t, f); assert(p.text().empty() && litCount(f) == 0);
    run(p, f, t, 30); assert(p.takeBeeps() == 0);
  }
  {  // heartbeat: solid when connected, blinking when not, red when battery low; static text sends nothing
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.onText('M', "Hoi", t);
    assert(p.tick(t, f)); assert(px(f, 51, 0)[2] == 229);
    assert(!p.tick(t + TICK_MS, f));
    p.onConnected(false, t); p.tick(t, f); bool on0 = f.lit(51, 0); run(p, f, t, 17); assert(f.lit(51, 0) != on0);
    p.onConnected(true, t); p.setBatteryLow(true); p.tick(t, f); assert(px(f, 51, 0)[0] > 0 && px(f, 51, 0)[2] == 0);
  }
  {  // brightness scales channels
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.onText('M', "H", t); p.setBrightnessLevel(0); p.tick(t, f);
    int x = minLit(f); assert(x >= 0);
    for (int y = 0; y < H; y++)
      if (f.lit(x, y)) { assert(px(f, x, y)[0] == 16); break; }
  }
  {  // replay re-shows the last message from the start
    Presenter p; Frame f; int64_t t = 0; p.onConnected(true, t); p.onText('M', "Hallo allemaal", t); run(p, f, t, 500);
    assert(!p.scrolling()); p.replay(t); assert(p.scrolling() && p.scrollX() == W);
  }
  std::puts("presenter_test: all passed");
  return 0;
}
