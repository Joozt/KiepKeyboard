#include "Presenter.h"

#include <algorithm>
#include <cstring>

#include "Font.h"

namespace kiep {

void Frame::clear() { std::memset(rgb, 0, sizeof rgb); }
void Frame::set(int x, int y, uint8_t r, uint8_t g, uint8_t b) {
  if (x < 0 || x >= W || y < 0 || y >= H) return;
  uint8_t* p = &rgb[(y * W + x) * 3];
  p[0] = r; p[1] = g; p[2] = b;
}
bool Frame::lit(int x, int y) const {
  const uint8_t* p = &rgb[(y * W + x) * 3];
  return (p[0] | p[1] | p[2]) != 0;
}

static const KiepGlyph* findGlyph(uint32_t cp) {
  int lo = 0, hi = KIEP_GLYPH_COUNT - 1;
  while (lo <= hi) {
    int mid = (lo + hi) / 2;
    uint32_t c = KIEP_GLYPHS[mid].codepoint;
    if (c == cp) return &KIEP_GLYPHS[mid];
    if (c < cp) lo = mid + 1; else hi = mid - 1;
  }
  return nullptr;
}
static const KiepGlyph* glyph(uint32_t cp) {
  const KiepGlyph* g = findGlyph(cp);
  return g ? g : findGlyph('?');
}

std::vector<uint32_t> Presenter::decode(const std::string& s) {
  std::vector<uint32_t> out;
  size_t i = 0;
  while (i < s.size()) {
    unsigned char c = s[i];
    uint32_t cp; int n;
    if (c < 0x80) { cp = c; n = 1; }
    else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; n = 2; }
    else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; n = 3; }
    else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; n = 4; }
    else { out.push_back('?'); i++; continue; }
    if (i + n > s.size()) { out.push_back('?'); break; }
    bool ok = true;
    for (int k = 1; k < n; k++) {
      unsigned char cc = s[i + k];
      if ((cc & 0xC0) != 0x80) { ok = false; break; }
      cp = (cp << 6) | (cc & 0x3F);
    }
    if (!ok) { out.push_back('?'); i++; continue; }  // resync on the next byte
    if (cp == '\n' || cp == '\r') cp = ' ';
    out.push_back(findGlyph(cp) ? cp : '?');
    i += n;
  }
  return out;
}

static int widthOf(const std::vector<uint32_t>& cps) {
  if (cps.empty()) return 0;
  int w = 0;
  for (uint32_t cp : cps) w += glyph(cp)->width + KIEP_GLYPH_GAP;
  return w - KIEP_GLYPH_GAP;
}
int Presenter::textWidth(const std::string& utf8) { return widthOf(decode(utf8)); }

void Presenter::onText(char type, const std::string& utf8, int64_t now) {
  bool extension = type == 'W' && !text_.empty() && utf8.size() > text_.size() &&
                   utf8.compare(0, text_.size(), text_) == 0;
  text_ = utf8; type_ = type; cps_ = decode(utf8); width_ = widthOf(cps_); lastTextMs_ = now;
  if (type != 'L') lastMessage_ = utf8;
  layout(extension);
}

void Presenter::layout(bool extension) {
  bool overflow = width_ > W;
  if (type_ == 'L') { scrolling_ = false; x_ = overflow ? W - width_ : 0; return; }
  if (extension) {
    if (scrolling_) return;                       // a running scroll continues into the appended words
    if (overflow) { scrolling_ = true; return; }  // held text stopped fitting: resume from the current x_
    x_ = (W - width_) / 2; return;
  }
  if (overflow) { scrolling_ = true; x_ = W; } else { scrolling_ = false; x_ = (W - width_) / 2; }
}

// A clear (disconnect, idle) is a privacy event: it also forgets the replay buffer, like the TC001, so the
// knob cannot bring a cleared message back.
void Presenter::clearText() { text_.clear(); lastMessage_.clear(); cps_.clear(); width_ = 0; scrolling_ = false; }

void Presenter::onAttention(uint8_t effect, uint8_t count, int64_t now) {
  flash_ = effect == 1 ? ATTENTION : effect == 2 ? YES : effect == 3 ? NO : NONE;
  flashCount_ = std::clamp<int>(count, 1, 5);
  flashStartMs_ = now; beepedOnsets_ = 0; attentionOn_ = false;
}

void Presenter::onConnected(bool c, int64_t now) {
  connected_ = c; lastHeartbeatMs_ = now;
  if (!c) { clearText(); flash_ = NONE; attentionOn_ = false; }
}

void Presenter::replay(int64_t now) { if (!lastMessage_.empty()) onText('M', lastMessage_, now); }
void Presenter::setBrightnessLevel(int level) { brightness_ = std::clamp(level, 0, 4); }
int Presenter::takeBeeps() { int b = beeps_; beeps_ = 0; return b; }

bool Presenter::tick(int64_t now, Frame& out) {
  if (!text_.empty() && now - lastTextMs_ > idleMs_) clearText();
  if (scrolling_) {
    x_--;
    if (x_ <= W - width_) { x_ = W - width_; scrolling_ = false; }
  }
  if (!connected_ && now - lastHeartbeatMs_ >= HEARTBEAT_TOGGLE_MS) { lastHeartbeatMs_ = now; heartbeatOn_ = !heartbeatOn_; }
  if (flash_ == ATTENTION) {
    int64_t el = now - flashStartMs_, period = ATTENTION_ON_MS + ATTENTION_OFF_MS;
    int idx = int(el / period);
    if (idx >= flashCount_) { flash_ = NONE; attentionOn_ = false; }
    else {
      attentionOn_ = el % period < ATTENTION_ON_MS;
      if (attentionOn_ && idx + 1 > beepedOnsets_) { beepedOnsets_ = idx + 1; beeps_++; }
    }
  } else if (flash_ != NONE && now - flashStartMs_ >= ANSWER_MS) {
    flash_ = NONE;
  }
  render(out);
  bool changed = !haveLast_ || std::memcmp(out.rgb, last_.rgb, sizeof out.rgb) != 0;
  last_ = out; haveLast_ = true;
  return changed;
}

void Presenter::render(Frame& f) const {
  f.clear();
  if (flash_ != NONE) {
    uint8_t r = 0, g = 0, b = 0; bool paint = true;
    if (flash_ == ATTENTION) { paint = attentionOn_; r = g = b = 255; }
    else if (flash_ == YES) g = 152;
    else r = 190;
    if (paint) for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) f.set(x, y, r, g, b);
  } else {
    int x = x_;
    for (uint32_t cp : cps_) {
      const KiepGlyph* gl = glyph(cp);
      if (x + gl->width > 0 && x < W)
        for (int y = 0; y < H; y++)
          for (int cx = 0; cx < gl->width; cx++)
            if (gl->rows[y] & (1u << cx)) f.set(x + cx, y, 255, 255, 255);
      x += gl->width + KIEP_GLYPH_GAP;
    }
  }
  if (connected_ || heartbeatOn_) {
    if (batteryLow_) f.set(W - 1, 0, 200, 0, 0); else f.set(W - 1, 0, 51, 181, 229);
  }
  uint8_t s = BRIGHTNESS_LEVELS[brightness_];
  for (uint8_t& v : f.rgb) v = uint8_t(v * s / 255);
}

}  // namespace kiep
