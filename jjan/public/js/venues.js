// 가게 목록. 서버(server.js)와 브라우저가 같이 쓴다 (three.js 없이).
// device: 노래를 틀고 끄는 기물 (누르면 신청곡 창이 열린다)

export const VENUES = [
  {
    id: 'bar', name: '단골 바', short: '바', desc: '벽난로 옆, 오래된 나무 바',
    host: '바텐더',
    device: { kind: 'piano', name: '피아니스트', ask: '피아니스트에게 신청곡', verb: '연주' },
    hello: ['어서 오세요. 오늘 하루 어땠어요?', '어서 오세요! 한 잔 하고 가요.', '오셨어요? 자리 비워 뒀어요.'],
  },
  {
    id: 'pocha', name: '포장마차', short: '포차', desc: '주황 천막 아래, 어묵 국물에 소주',
    host: '사장님',
    device: { kind: 'radio', name: '카세트 라디오', ask: '라디오로 노래 틀기', verb: '재생' },
    hello: ['어서 와요! 추운데 국물부터 줄까?', '왔어? 앉아, 앉아. 오늘 꼼장어 좋아.', '비 오는 날엔 포차지. 어서 와요.'],
  },
  {
    id: 'hof', name: '호프집', short: '호프', desc: '시끌벅적, 500cc 부딪히는 소리',
    host: '사장님',
    device: { kind: 'band', name: '라이브 밴드', ask: '밴드에게 신청곡', verb: '연주' },
    hello: ['어서 오세요! 시원한 거 바로 드릴게요.', '오늘 밴드 좋아요. 한 잔 하면서 들어요.', '퇴근하셨어요? 고생하셨어요!'],
  },
  {
    id: 'wine', name: '와인바', short: '와인', desc: '촛불, 벽 가득한 와인 병',
    host: '소믈리에',
    device: { kind: 'lp', name: 'LP 플레이어', ask: 'LP 올리기', verb: '재생' },
    hello: ['어서 오세요. 오늘은 어떤 기분이세요?', '편히 앉으세요. 천천히 골라 보세요.', '좋은 밤이에요. 한 잔 따라 드릴게요.'],
  },
  {
    id: 'xmas', name: '크리스마스 산장', short: '산장', desc: '눈 오는 밤, 트리 옆 벽난로',
    host: '산장지기',
    device: { kind: 'orchestrion', name: '오케스트리온', ask: '오케스트리온에 곡 넣기', verb: '연주' },
    hello: ['메리 크리스마스! 눈 털고 들어와요.', '어서 와요, 불 쬐면서 한 잔 해요.', '추웠죠? 따뜻한 것부터 드릴까요?'],
  },
  {
    id: 'attic', name: '다락방 아지트', short: '다락방', desc: '경사진 지붕 아래, 우리끼리',
    host: '친구',
    device: { kind: 'pc', name: '컴퓨터', ask: '컴퓨터로 노래 틀기', verb: '재생' },
    hello: ['왔어? 신발 벗고 편하게 앉아.', '오늘 뭐 마실래? 냉장고에 다 있어.', '어서 와. 노래 하나 틀어 봐.'],
  },
  {
    id: 'izakaya', name: '이자카야', short: '이자카야', desc: '숯불 앞 카운터, 꼬치 굽는 연기',
    host: '대장',
    device: { kind: 'shamisen', name: '샤미센 연주자', ask: '샤미센 연주자에게 신청곡', verb: '연주' },
    hello: ['어서 오세요! 이랏샤이마세!', '오늘 닭꼬치 잘 구워져요. 앉으세요!', '하이볼 한 잔 먼저 드릴까요?'],
  },
  {
    id: 'rooftop', name: '루프탑 바', short: '루프탑', desc: '도시 야경 위, 바람 부는 옥상',
    host: '바텐더',
    device: { kind: 'dj', name: 'DJ 부스', ask: 'DJ에게 신청곡', verb: '재생' },
    hello: ['어서 오세요. 오늘 야경 좋죠?', '바람 불면 히터 옆으로 앉으세요.', '도시 불빛 보면서 한 잔 해요.'],
  },
  {
    id: 'conv', name: '편의점 앞 파라솔', short: '편의점', desc: '24시 불빛 아래 캔맥주와 컵라면',
    host: '점원',
    device: { kind: 'speaker', name: '블루투스 스피커', ask: '스피커로 노래 틀기', verb: '재생' },
    hello: ['어서 오세요~ 컵라면 물은 저기 있어요.', '4캔 만 원이에요! 골라 보세요.', '밤 늦게 고생 많으셨어요.'],
  },
];

export const venueById = Object.fromEntries(VENUES.map((v) => [v.id, v]));
export const isVenue = (v) => !!venueById[v];
export const DEFAULT_VENUE = 'bar';
