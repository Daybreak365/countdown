"""짠(술자리 건배) 소리 합성: 재질별 잔 부딪히는 소리, 따르는 소리, 탄산, 꿀꺽, 딸꾹.

잔 소리는 재질마다 다른 배음 구조의 감쇠 사인파에 짧은 접촉 잡음을 얹는다.
  glass   얇은 유리: 맑고 높게, 여운 중간      thick   두꺼운 유리: 낮고 짧게
  crystal 크리스털: 높고 긴 여운, 살짝 떨림    metal   금속(양은·구리): 쨍하고 비조화 배음
  ceramic 도자기: 둔탁하고 짧게               wood    나무: 툭, 여운 거의 없음
게임에서 잔 크기·세기에 따라 음높이·크기를 바꿔 재생한다.

    python3 tools/sounds/jjan.py   # src/main/resources/assets/newyearcountdown/sounds/jjan_*.ogg (ffmpeg 필요)
"""
import os
import numpy as np
from clock import SR, OUT, bandnoise, write_ogg


def norm(y, peak=0.85):
    return peak * y / max(1e-9, np.abs(y).max())


def clink(seed, dur, modes, noise_band, noise_decay, noise_amp, beat=0.0):
    """modes: (주파수, 감쇠 시간, 세기). 두 잔이 부딪히므로 조금 어긋난 두 번째 잔을 함께 울린다."""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    y = np.zeros(n)
    for detune, amp in ((1.0, 1.0), (1.07, 0.7)):
        for f, d, a in modes:
            wob = 1.0 + beat * np.sin(2 * np.pi * 6.5 * t)
            y += amp * a * np.sin(2 * np.pi * f * detune * t + rng.uniform(0, 6.28)) * np.exp(-t / d) * wob
    y += noise_amp * bandnoise(n, noise_band[0], noise_band[1], rng) * np.exp(-t / noise_decay)
    y *= np.clip(t / 0.0008, 0, 1)
    return norm(y)


CLINKS = {
    'glass': dict(seed=11, dur=0.7, modes=[(2350, 0.11, 1.0), (4900, 0.07, 0.55), (7600, 0.04, 0.3), (10400, 0.025, 0.15)],
                  noise_band=(3000, 12000), noise_decay=0.004, noise_amp=0.5),
    'thick': dict(seed=12, dur=0.5, modes=[(1450, 0.07, 1.0), (3100, 0.045, 0.5), (5200, 0.025, 0.25)],
                  noise_band=(1500, 8000), noise_decay=0.005, noise_amp=0.6),
    'crystal': dict(seed=13, dur=1.5, modes=[(2900, 0.34, 1.0), (5950, 0.22, 0.6), (9100, 0.12, 0.35), (12300, 0.06, 0.18)],
                    noise_band=(4000, 14000), noise_decay=0.003, noise_amp=0.35, beat=0.12),
    'metal': dict(seed=14, dur=0.9, modes=[(1180, 0.16, 1.0), (2710, 0.12, 0.7), (4230, 0.09, 0.5), (6890, 0.05, 0.35), (9320, 0.03, 0.2)],
                  noise_band=(2000, 11000), noise_decay=0.006, noise_amp=0.7),
    'ceramic': dict(seed=15, dur=0.35, modes=[(980, 0.035, 1.0), (2250, 0.022, 0.55), (3900, 0.012, 0.3)],
                    noise_band=(900, 6000), noise_decay=0.006, noise_amp=0.8),
    'wood': dict(seed=16, dur=0.25, modes=[(420, 0.03, 1.0), (910, 0.018, 0.5), (1650, 0.01, 0.25)],
                 noise_band=(300, 3000), noise_decay=0.008, noise_amp=0.9),
}


def pour():
    """따르는 소리: 물줄기 잡음 + 잔이 차면서 올라가는 공명."""
    rng = np.random.default_rng(21)
    n = int(1.5 * SR)
    t = np.arange(n) / SR
    env = np.clip(t / 0.08, 0, 1) * np.clip((1.5 - t) / 0.25, 0, 1)
    y = 0.6 * bandnoise(n, 500, 5000, rng) * (0.7 + 0.3 * np.sin(2 * np.pi * 9 * t + 1.0))
    f = 380 + 520 * (t / 1.5) ** 1.3
    phase = 2 * np.pi * np.cumsum(f) / SR
    y += 0.35 * np.sin(phase) * (0.5 + 0.5 * bandnoise(n, 5, 40, rng))
    # 방울
    for _ in range(40):
        i = int(rng.uniform(0.05, 1.35) * SR)
        m = int(0.02 * SR)
        tt = np.arange(m) / SR
        fb = rng.uniform(900, 2600)
        y[i:i + m] += 0.25 * np.sin(2 * np.pi * (fb + 9000 * tt) * tt) * np.exp(-tt / 0.006)
    return norm(y * env, 0.7)


def fizz():
    rng = np.random.default_rng(22)
    n = int(1.2 * SR)
    t = np.arange(n) / SR
    env = np.clip(t / 0.03, 0, 1) * np.exp(-t / 0.45)
    y = bandnoise(n, 5000, 15000, rng) * (0.6 + 0.4 * bandnoise(n, 20, 120, rng))
    return norm(y * env, 0.45)


def gulp():
    """꿀꺽: 목구멍이 닫혔다 열리는 낮은 울림 두 번."""
    rng = np.random.default_rng(23)
    n = int(0.3 * SR)
    t = np.arange(n) / SR
    y = np.zeros(n)
    for start, f0, f1 in ((0.01, 150, 95), (0.12, 210, 320)):
        i0 = int(start * SR)
        tt = np.arange(n - i0) / SR
        f = f0 + (f1 - f0) * np.clip(tt / 0.07, 0, 1)
        y[i0:] += np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.035) * np.clip(tt / 0.004, 0, 1)
    y += 0.12 * bandnoise(n, 200, 1200, rng) * np.exp(-t / 0.08)
    return norm(y, 0.7)


def hiccup():
    """딸꾹: 급하게 올라가다 끊기는 짧은 소리."""
    rng = np.random.default_rng(24)
    n = int(0.22 * SR)
    t = np.arange(n) / SR
    f = 320 + 900 * np.clip(t / 0.06, 0, 1) ** 0.7
    phase = 2 * np.pi * np.cumsum(f) / SR
    env = np.clip(t / 0.008, 0, 1) * np.clip((0.075 - t) / 0.01, 0, 1)
    y = (np.sin(phase) + 0.4 * np.sin(2 * phase) + 0.2 * np.sin(3 * phase)) * env
    y += 0.15 * bandnoise(n, 800, 4000, rng) * env
    return norm(y, 0.7)


if __name__ == '__main__':
    for name, spec in CLINKS.items():
        write_ogg(clink(**spec), os.path.join(OUT, 'jjan_clink_%s.ogg' % name))
    write_ogg(pour(), os.path.join(OUT, 'jjan_pour.ogg'))
    write_ogg(fizz(), os.path.join(OUT, 'jjan_fizz.ogg'))
    write_ogg(gulp(), os.path.join(OUT, 'jjan_gulp.ogg'))
    write_ogg(hiccup(), os.path.join(OUT, 'jjan_hiccup.ogg'))
    print('jjan_*.ogg ->', OUT)
