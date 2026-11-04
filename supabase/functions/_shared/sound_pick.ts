// 분위기 태그로 sounds 테이블에서 음원을 고른다.
// - describe-scene이 쓰는 음원 선택 로직이다.
// - seenario-agent는 팀장님이 만드는 seenario-agent/tools/sound.ts가 오기 전까지 이걸 **임시로** 불러
//   고른 곡을 로그로만 남긴다. sound.ts가 오면 에이전트는 그걸로 바꾼다.

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import type { SoundTag } from "./sound_tags.ts";

export type SoundMode = "ambient" | "music";
export const SOUND_MODES = new Set<string>(["ambient", "music"]);

const SOUND_BUCKET = "sounds";
const SOUND_URL_EXPIRES_SEC = 60 * 60; // 재생 URL 유효 시간(1시간)

type SoundRow = { id: string; title: string; mode: SoundMode; file_path: string; loopable: boolean; tags: string[] };
export type PickedSound = { id: string; title: string; mode: SoundMode; url: string; loopable: boolean; tags: string[] };

/**
 * 모드가 같은 음원 중 태그가 가장 많이 겹치는 것을 고른다(동점이면 무작위).
 * 하나도 겹치지 않으면 엉뚱한 소리가 나지 않도록 고르지 않는다.
 */
export async function pickSound(
  supabase: SupabaseClient,
  mode: SoundMode,
  tags: SoundTag[],
): Promise<PickedSound | null> {
  if (tags.length === 0) return null;
  const { data, error } = await supabase
    .from("sounds")
    .select("id, title, mode, file_path, loopable, tags")
    .eq("mode", mode)
    .overlaps("tags", tags);
  if (error) {
    console.error("sounds read failed", error.message);
    return null;
  }
  const rows = (data ?? []) as SoundRow[];
  if (rows.length === 0) return null;

  const wanted = new Set<string>(tags);
  const scored = rows.map((r) => ({ r, score: r.tags.filter((t) => wanted.has(t)).length }));
  const best = Math.max(...scored.map((s) => s.score));
  const top = scored.filter((s) => s.score === best);
  const chosen = top[Math.floor(Math.random() * top.length)].r;

  const { data: signed, error: urlError } = await supabase.storage
    .from(SOUND_BUCKET)
    .createSignedUrl(chosen.file_path, SOUND_URL_EXPIRES_SEC);
  if (urlError || !signed?.signedUrl) {
    console.error("sound signed url failed", chosen.file_path, urlError?.message);
    return null;
  }
  return {
    id: chosen.id,
    title: chosen.title,
    mode: chosen.mode,
    url: signed.signedUrl,
    loopable: chosen.loopable,
    tags: chosen.tags,
  };
}
