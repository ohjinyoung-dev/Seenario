// 음원 분위기 태그 단어 목록.
// - Gemini는 배경음악/환경음 모드에서 사진을 보고 이 목록 안에서만 태그를 고른다.
// - sounds 테이블의 tags 칸에도 반드시 이 목록에 있는 단어만 넣는다(영문 소문자 그대로).
// - 단어를 추가/삭제하면 이미 등록된 sounds 행의 tags도 함께 맞춰 준다.

export const SOUND_TAGS = {
  // 장소
  indoor: "실내",
  outdoor: "야외",
  home: "집",
  cafe: "카페",
  restaurant: "식당",
  shop: "가게·상점",
  street: "거리",
  city: "도시",
  transit: "대중교통·역",
  park: "공원",
  forest: "숲",
  mountain: "산",
  beach: "바다·해변",
  water: "강·호수·물가",

  // 날씨
  sunny: "맑음",
  cloudy: "흐림",
  rain: "비",
  snow: "눈",
  wind: "바람",
  fog: "안개",

  // 계절
  spring: "봄",
  summer: "여름",
  autumn: "가을",
  winter: "겨울",

  // 시간대
  morning: "아침",
  daytime: "낮",
  evening: "저녁·노을",
  night: "밤",

  // 분위기
  calm: "차분함",
  cozy: "아늑함",
  lively: "활기참",
  busy: "북적임",
  bright: "밝고 경쾌함",
  gloomy: "쓸쓸함·가라앉음",
  nostalgic: "그리움·추억",
  refreshing: "상쾌함",
  romantic: "낭만적",
} as const;

export type SoundTag = keyof typeof SOUND_TAGS;

export const SOUND_TAG_KEYS = Object.keys(SOUND_TAGS) as SoundTag[];

const TAG_SET = new Set<string>(SOUND_TAG_KEYS);

/** 목록에 있는 태그만 남기고 중복을 없앤다. */
export function normalizeTags(value: unknown): SoundTag[] {
  if (!Array.isArray(value)) return [];
  const out = new Set<SoundTag>();
  for (const v of value) {
    if (typeof v === "string" && TAG_SET.has(v.trim().toLowerCase())) {
      out.add(v.trim().toLowerCase() as SoundTag);
    }
  }
  return [...out];
}
