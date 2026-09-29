#!/usr/bin/env python3
"""1400 Hz, 120 ms, 16-bit mono 16 kHz tone with 5 ms fades -> res/ui/beep.wav (same pitch as the TC001 buzzer).
Standard library only; output is committed."""
import math
import pathlib
import struct
import wave

out = pathlib.Path(__file__).resolve().parents[1] / "res/ui/beep.wav"
rate, ms, freq = 16000, 120, 1400
n = rate * ms // 1000
fade = rate * 5 // 1000
samples = [int(28000 * math.sin(2 * math.pi * freq * i / rate) * min(1.0, i / fade, (n - i) / fade)) for i in range(n)]
with wave.open(str(out), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(rate)
    w.writeframes(struct.pack(f"<{n}h", *samples))
print("wrote", out, n, "samples")
