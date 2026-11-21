"""새해 네컷 포토부스 아이템 모델: 안으로 걸어 들어가는 커다란 레트로 카메라.
크림색 몸체 + 가죽 아래 띠 + 무지개 줄무늬, 정면 렌즈 링(안쪽 붉은 커튼), 지붕의 플래시·셔터 버튼·뷰파인더."""
from core import Box, Model, Mat
from lib import *
from shapes import prism, prism_z

RAINBOW = ['#e8453c', '#f5a623', '#f8d347', '#5cc96b', '#4da8e8']


def build(name='photo_booth'):
    B = []
    cream = plastic('#fff4e2', bevel=0.04)
    leather = Mat('#4a4650', base=b_cloth('#4a4650', 0.22), bevel=0.03, spec=0.2)
    plinth = Mat('#2c2a30', bevel=0.03)
    chrome = Mat('#e6e6ec', base=b_brushed('#e6e6ec', 0.05), spec=0.9, shin=24, bevel=0.04)
    gold_ring = gold('#f0c860')
    black = Mat('#24232a', bevel=0.03, spec=0.3)
    lamp = Mat('#fff6da', emit=0.4, spec=0.0, bevel=0.0, noise=0.0)
    velvet = Mat('#d7263d', base=b_cloth('#d7263d', 0.3, vgrad=0.2), bevel=0.0, spec=0.1)

    x0, x1, z0, z1 = 1.5, 14.5, 2.5, 14.5
    # 옆면 포스터: 사진 띠 두 장
    strips = d_many(*[d_rect(1.2 + s * 2.6, 0.6, 3.2 + s * 2.6, 5.0, rgba('#ffffff'), r=0.15) for s in range(2)],
                    *[d_rect(1.45 + s * 2.6, 0.85 + k * 1.05, 2.95 + s * 2.6, 1.7 + k * 1.05, rgba(c), r=0.05)
                      for s in range(2) for k, c in enumerate(['#ff5a6e', '#ffd25a', '#7ce08a', '#6ec3ff'])])
    poster = Mat('#4a4650', base=b_cloth('#4a4650', 0.22), bevel=0.03).decal(strips)
    B.append(Box((x0, 0, z0), (x1, 0.6, z1), plinth))
    B.append(Box((x0, 0.6, z0), (x1, 6.2, z1), {'east': poster, 'west': poster, '*': leather}))
    for i, c in enumerate(RAINBOW):
        B.append(Box((x0, 6.2 + i * 0.3, z0), (x1, 6.5 + i * 0.3, z1), plastic(c, bevel=0.0, spec=0.3)))
    B.append(Box((x0, 7.7, z0), (x1, 11.0, z1), cream))
    # 지붕 덮개 + 크롬 테
    B.append(Box((1.3, 11.0, 2.3), (14.7, 11.8, 14.7), cream))
    B.append(Box((1.2, 11.8, 2.2), (14.8, 12.0, 14.8), chrome))
    # 뷰파인더 (2027 글씨)
    vf = Mat('#4a4650', bevel=0.03).decal(d_text('2027', 2.2, 0.8, 1.15, rgba('#f6c945')))
    B.append(Box((5.8, 12.0, 5.5), (10.2, 13.6, 9.0), {'south': vf, '*': leather}))
    B.append(Box((5.7, 13.6, 5.4), (10.3, 13.85, 9.1), chrome))
    # 플래시
    B.append(Box((9.2, 12.0, 10.6), (14.2, 14.0, 14.2), chrome))
    B.append(Box((9.6, 12.35, 14.2), (13.8, 13.65, 14.45), lamp))
    # 셔터 버튼
    B.extend(prism(3.8, 12.6, 12.0, 12.45, 1.25, chrome, sides=8))
    B.extend(prism(3.8, 12.6, 12.45, 13.2, 0.85, plastic('#e8453c'), sides=8))
    # 렌즈 링 입구 (정면): 크롬 > 검은 초점 링 > 금 테 > 커튼
    cy = 5.3
    B.extend(prism_z(8.0, cy, 14.4, 14.85, 5.1, chrome, sides=16))
    B.extend(prism_z(8.0, cy, 14.85, 15.35, 4.5, black, sides=16))
    B.extend(prism_z(8.0, cy, 15.35, 15.5, 3.85, gold_ring, sides=16))
    B.extend(prism_z(8.0, cy, 15.5, 15.55, 3.55, velvet, sides=16))
    return Model(name, B, density=5, gui=(20, 30, 0), ground_scale=0.34)
