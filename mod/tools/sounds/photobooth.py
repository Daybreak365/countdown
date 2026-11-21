"""포토부스 소리 합성: 카메라 셔터(찰칵 + 짧은 필름 감기)와 카운트다운 삐 소리.

셔터는 막이 열리고 닫히는 두 번의 금속 충돌(감쇠 사인파 + 대역 잡음)을 수십 ms 간격으로 겹치고,
뒤이어 작은 모터가 필름을 감는 소리(톱니파 + 잡음, 진폭이 떨림)를 붙인다.
삐 소리는 디지털 카메라 셀프타이머처럼 맑은 사인파에 배음을 조금 섞는다.

    python3 tools/sounds/photobooth.py   # src/main/resources/assets/newyearcountdown/sounds/photo_{shutter,beep}.ogg (ffmpeg 필요)
"""
import os
import numpy as np
from clock import SR, OUT, bandnoise, write_ogg


def hit(n, start, amp, modes, band, decay, rng):
    out = np.zeros(n)
    i0 = int(start * SR)
    t = np.arange(n - i0) / SR
    for f, d, a in modes:
        out[i0:] += a * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / d)
    out[i0:] += 0.9 * bandnoise(n - i0, band[0], band[1], rng) * np.exp(-t / decay)
    return amp * out


def shutter():
    rng = np.random.default_rng(2027)
    n = int(0.62 * SR)
    y = np.zeros(n)
    y += hit(n, 0.004, 1.0, [(3100, 0.006, 0.6), (5200, 0.004, 0.4), (1700, 0.012, 0.3)], (1800, 9000), 0.006, rng)
    y += hit(n, 0.052, 0.8, [(2800, 0.007, 0.6), (4700, 0.004, 0.4), (1500, 0.014, 0.3)], (1500, 8000), 0.008, rng)
    # 필름 감기: 0.11s 부터 0.3s 동안
    t = np.arange(n) / SR
    m0, m1 = 0.11, 0.41
    env = np.clip((t - m0) / 0.02, 0, 1) * np.clip((m1 - t) / 0.05, 0, 1)
    f = 190 + 40 * np.clip((t - m0) / (m1 - m0), 0, 1)
    phase = np.cumsum(f) / SR
    saw = 2 * (phase % 1.0) - 1
    tick = 0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 38 * t))
    motor = (0.35 * saw + 0.5 * bandnoise(n, 900, 4500, rng)) * (0.55 + 0.45 * tick)
    y += 0.22 * motor * env
    y += hit(n, 0.42, 0.35, [(2400, 0.005, 0.5)], (1500, 7000), 0.004, rng)   # 감기 멈춤 딸깍
    return 0.85 * y / np.abs(y).max()


def beep():
    n = int(0.16 * SR)
    t = np.arange(n) / SR
    env = np.clip(t / 0.004, 0, 1) * np.exp(-np.clip(t - 0.09, 0, None) / 0.02)
    y = (np.sin(2 * np.pi * 1320 * t) + 0.25 * np.sin(2 * np.pi * 2640 * t) + 0.08 * np.sin(2 * np.pi * 3960 * t)) * env
    return 0.6 * y / np.abs(y).max()


if __name__ == '__main__':
    write_ogg(shutter(), os.path.join(OUT, 'photo_shutter.ogg'))
    write_ogg(beep(), os.path.join(OUT, 'photo_beep.ogg'))
    print('photo_shutter.ogg, photo_beep.ogg ->', OUT)
