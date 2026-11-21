"""짠 테이블·의자 전용 텍스처 생성 (16x16, 밝은 회색조). 렌더러가 원작의 색을 곱해 입힌다.

원작(웹)은 텍스처 없이 상자마다 단색 + 약간의 밝기 차이만 쓴다. 여기서는 그 색을 그대로 두고,
Minecraft 블록처럼 보이도록 결·짜임만 얹는다. 평균 밝기를 1 에 가깝게 잡아 색이 원작에서 벗어나지 않게 한다.

    python tools/jjan/textures.py        (mod 폴더에서)
"""
import os
import random
from PIL import Image

OUT = 'src/main/resources/assets/newyearcountdown/textures/block'
N = 16


def save(name, px):
    img = Image.new('RGBA', (N, N))
    for y in range(N):
        for x in range(N):
            v = max(0, min(255, int(round(px[y][x] * 255))))
            img.putpixel((x, y), (v, v, v, 255))
    img.save(os.path.join(OUT, name + '.png'))


def grain(seed, streaks=9, knots=1):
    """결이 가로(U)로 흐르는 나무: 줄마다 밝기가 조금 다르고, 어두운 결이 몇 가닥 지나간다. 사방으로 이어 붙는다."""
    r = random.Random(seed)
    rows = [0.955 + r.uniform(-0.03, 0.03) for _ in range(N)]
    px = [[rows[y] for _ in range(N)] for y in range(N)]
    for _ in range(streaks):
        y, x, n = r.randrange(N), r.randrange(N), r.randint(4, 11)
        d = r.uniform(0.05, 0.1)
        for i in range(n):
            px[y][(x + i) % N] -= d
            if i in (0, n - 1):
                px[y][(x + i) % N] += d * 0.5   # 결 끝은 옅게
    for _ in range(knots):
        y, x = r.randrange(1, N - 1), r.randrange(N)
        px[y][x] -= 0.13
        for dx, dy in ((1, 0), (-1, 0)):
            px[(y + dy) % N][(x + dx) % N] -= 0.06
    for _ in range(7):
        px[r.randrange(N)][r.randrange(N)] += 0.035
    return px


def transpose(px):
    return [[px[x][y] for x in range(N)] for y in range(N)]


def fabric(seed):
    """방석 천: 2픽셀 짜임."""
    r = random.Random(seed)
    px = [[0.0] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            weave = 1.0 if (x // 2 + y // 2) % 2 == 0 else 0.93
            edge = -0.03 if (x % 2 == 1 and y % 2 == 1) else 0
            px[y][x] = weave + edge + r.uniform(-0.012, 0.012)
    return px


def metal(seed):
    """솔질한 금속: 가로 줄무늬와 밝은 띠 하나."""
    r = random.Random(seed)
    px = []
    for y in range(N):
        base = 0.9 + 0.05 * (1 if y in (3, 4) else 0) + r.uniform(-0.02, 0.02)
        px.append([base + (r.uniform(-0.015, 0.015)) for _ in range(N)])
    for _ in range(5):
        y, x = r.randrange(N), r.randrange(N)
        for i in range(r.randint(3, 6)):
            px[y][(x + i) % N] += 0.05
    return px


def plain(seed):
    """병·라벨·잔 받침: 거의 단색, 아주 옅은 얼룩."""
    r = random.Random(seed)
    return [[0.975 + r.choice((-0.025, 0, 0, 0.02)) for _ in range(N)] for _ in range(N)]


def nuts(seed):
    """그릇에 담긴 땅콩: 알알이 밝고 사이는 어둡다."""
    r = random.Random(seed)
    px = [[0.72 + r.uniform(-0.04, 0.04) for _ in range(N)] for _ in range(N)]
    for _ in range(26):
        y, x = r.randrange(N), r.randrange(N)
        v = r.uniform(0.92, 1.0)
        for dx, dy in ((0, 0), (1, 0), (0, 1)) if r.random() < 0.5 else ((0, 0), (1, 0)):
            px[(y + dy) % N][(x + dx) % N] = v - 0.04 * (dx + dy)
    return px


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    wood = grain(11)
    save('jjan_plank', wood)                 # 결이 U(면의 가로) 방향
    save('jjan_plank_z', transpose(wood))    # 윗면에서 z 방향으로 긴 널
    save('jjan_wood', transpose(grain(23, streaks=12, knots=0)))   # 다리·기둥: 세로결
    save('jjan_fabric', fabric(5))
    save('jjan_metal', metal(7))
    save('jjan_plain', plain(3))
    save('jjan_nuts', nuts(9))
    print('ok')
