// 소리 고르기 도구: 장면 태그로 sounds 테이블에서 음원 하나를 고른다.
// - 같은 mode이고 is_active인 음원만 후보로 본다.
// - contains(음원에 실제로 들리는 소리)가 existing_sounds(지금 주변에서 들리는 소리)와 하나라도 겹치면 뺀다.
//   실제 주변 소리와 같은 소리를 겹쳐 틀지 않기 위해서다.
// - 장소가 반대인 음원은 뺀다. 장면 태그에 indoor가 있으면 tags에 outdoor가 있는 음원을,
//   outdoor가 있으면 indoor가 있는 음원을 후보에서 제외한다.
// - 점수 = 겹친 태그 수 ÷ √(그 음원의 태그 수). 태그를 많이 단 음원이 늘 이기지 않으면서도,
//   태그가 적은 음원이 한두 개만 겹쳐서 쉽게 이기지 않도록 완만하게 나눈다.
//   가장 높은 점수끼리 동점이면 무작위로 고른다.
// - 겹친 태그가 없거나 점수가 MIN_SCORE보다 낮으면 고르지 않는다(null).

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { normalizeTags, type SoundTag } from "../../_shared/sound_tags.ts";

/** 이 점수보다 낮으면 고르지 않는다. 0이면 태그가 하나라도 겹치면 된다. */
export const MIN_SCORE = 0;

const SOUND_BUCKET = "sounds";
const SOUND_URL_EXPIRES_SEC = 60 * 60; // 재생 URL 유효 시간(1시간)

export type SoundPickInput = {
  mode: "off" | "ambient" | "music";
  /** 어울리는 상황 태그(SOUND_TAGS 값). */
  tags: readonly string[];
  /** 지금 주변에서 실제로 들리는 소리 종류(sounds.contains와 같은 목록). */
  existing_sounds: readonly string[];
};

export type SoundPick = { sound_id: string; title: string; url: string; loopable: boolean };

/** sounds 테이블에서 읽어 오는 행. */
export type SoundCandidate = {
  id: string;
  title: string;
  file_path: string;
  loopable: boolean;
  tags: string[];
  contains: string[];
};

/** 음원 하나의 점수(겹친 태그 수 ÷ √음원의 태그 수)와 겹친 태그 수. */
function score(row: SoundCandidate, wanted: Set<string>): { overlap: number; score: number } {
  const tags = row.tags ?? [];
  const overlap = tags.filter((t) => wanted.has(t)).length;
  return { overlap, score: tags.length === 0 ? 0 : overlap / Math.sqrt(tags.length) };
}

/** 장면이 indoor면 outdoor 음원을, outdoor면 indoor 음원을 뺄 태그 목록. */
function oppositePlaceTags(wanted: Set<string>): string[] {
  const opposite: string[] = [];
  if (wanted.has("indoor")) opposite.push("outdoor");
  if (wanted.has("outdoor")) opposite.push("indoor");
  return opposite;
}

/**
 * DB 없이 후보 중에서 고른다(pickSound가 쓰고, 테스트도 이걸 직접 부른다).
 * [rows]는 이미 같은 mode, is_active인 음원이라고 본다. [random]은 0 이상 1 미만을 돌려준다.
 */
export function chooseSound(
  rows: readonly SoundCandidate[],
  input: SoundPickInput,
  random: () => number = Math.random,
): SoundCandidate | null {
  if (input.mode === "off") return null;
  const wanted = new Set<string>(normalizeTags(input.tags));
  if (wanted.size === 0) return null;
  const existing = new Set(input.existing_sounds.map((s) => s.trim().toLowerCase()));
  const opposite = oppositePlaceTags(wanted);

  const scored = rows
    .filter((r) => !(r.contains ?? []).some((c) => existing.has(c)))
    .filter((r) => !(r.tags ?? []).some((t) => opposite.includes(t)))
    .map((r) => ({ r, ...score(r, wanted) }))
    .filter((s) => s.overlap > 0 && s.score >= MIN_SCORE);
  if (scored.length === 0) return null;

  const best = Math.max(...scored.map((s) => s.score));
  const top = scored.filter((s) => s.score === best);
  return top[Math.floor(random() * top.length)].r;
}

/** 음원을 골라 1시간짜리 signed URL과 함께 돌려준다. 고르지 못하면 null. */
export async function pickSound(supabase: SupabaseClient, input: SoundPickInput): Promise<SoundPick | null> {
  if (input.mode === "off") return null;
  const tags: SoundTag[] = normalizeTags(input.tags);
  if (tags.length === 0) return null;

  const { data, error } = await supabase
    .from("sounds")
    .select("id, title, file_path, loopable, tags, contains")
    .eq("mode", input.mode)
    .eq("is_active", true)
    .overlaps("tags", tags);
  if (error) {
    console.error("sounds read failed", error.message);
    return null;
  }

  const chosen = chooseSound((data ?? []) as SoundCandidate[], { ...input, tags });
  if (!chosen) return null;

  const { data: signed, error: urlError } = await supabase.storage
    .from(SOUND_BUCKET)
    .createSignedUrl(chosen.file_path, SOUND_URL_EXPIRES_SEC);
  if (urlError || !signed?.signedUrl) {
    console.error("sound signed url failed", chosen.file_path, urlError?.message);
    return null;
  }
  return { sound_id: chosen.id, title: chosen.title, url: signed.signedUrl, loopable: chosen.loopable };
}
