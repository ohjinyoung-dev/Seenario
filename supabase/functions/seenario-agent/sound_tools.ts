// 에이전트의 소리 도구(pick_sound, stop_sound).
// - 곡 고르기는 tools/sound.ts(contains 제외, 비율 점수)를 쓴다.
// - 고른 곡이 있으면 응답 actions에 play_sound를 붙인다. url은 로그에 남기지 않는다.

import type { SoundMode } from "../_shared/sound_pick.ts";
import { normalizeTags, SOUND_TAG_KEYS } from "../_shared/sound_tags.ts";
import { MAX_TAGS } from "./prompts.ts";
import { pickSound as pickAgentSound, type SoundPick } from "./tools/sound.ts";
import { type AgentAction, type AgentContext, EXISTING_SOUNDS, normalizeExistingSounds } from "./types.ts";

/** play_sound의 기본 음량(0~1). 묘사 낭독을 가리지 않도록 작게. */
export const PLAY_VOLUME = 0.4;

export type PickLog = { found: boolean; title?: string; sound?: SoundPick };

/** 곡을 고르고 로그를 남긴다. url은 로그에 남기지 않는다. */
export async function logPick(
  ctx: AgentContext,
  mode: SoundMode,
  rawTags: unknown,
  rawExisting: unknown,
  from: string,
): Promise<PickLog> {
  const tags = normalizeTags(rawTags).slice(0, MAX_TAGS);
  const existing = normalizeExistingSounds(rawExisting);
  const picked = await pickAgentSound(ctx.supabase, { mode, tags, existing_sounds: existing });
  console.log(
    `sound pick from=${from} mode=${mode} tags=${JSON.stringify(tags)} existing=${JSON.stringify(existing)}`,
    picked ? `→ ${picked.sound_id} "${picked.title}"` : "→ none",
  );
  return picked ? { found: true, title: picked.title, sound: picked } : { found: false };
}

/** 고른 곡으로 play_sound action을 만든다. */
export function playAction(sound: SoundPick): AgentAction {
  return {
    type: "play_sound",
    sound_id: sound.sound_id,
    title: sound.title,
    url: sound.url,
    volume: PLAY_VOLUME,
    loopable: sound.loopable,
  };
}

/** 소리 모드가 켜져 있을 때 Gemini에 주는 도구 선언. */
export const SOUND_TOOL_DECLARATIONS = [
  {
    name: "pick_sound",
    description: "지금 장면과 사용자의 요청에 어울리는 소리를 지금 소리 모드 안에서 고른다.",
    parameters: {
      type: "OBJECT",
      properties: {
        tags: {
          type: "ARRAY",
          description: `어울리는 상황 태그 1~${MAX_TAGS}개.`,
          items: { type: "STRING", enum: SOUND_TAG_KEYS },
        },
        existing_sounds: {
          type: "ARRAY",
          description: "지금 주변에서 실제로 들리는 소리 종류. 모르면 빈 배열.",
          items: { type: "STRING", enum: [...EXISTING_SOUNDS] },
        },
      },
      required: ["tags"],
    },
  },
  {
    name: "stop_sound",
    description: "지금 틀고 있는 소리를 멈춘다.",
  },
];

/** 도구를 실행하고 모델에게 돌려줄 결과를 만든다. 앱이 할 일은 [actions]에 쌓는다. */
export async function runSoundTool(
  ctx: AgentContext,
  mode: SoundMode,
  name: string,
  args: Record<string, unknown>,
  actions: AgentAction[],
): Promise<Record<string, unknown>> {
  switch (name) {
    case "pick_sound": {
      const pick = await logPick(ctx, mode, args.tags, args.existing_sounds, "speech");
      if (!pick.sound) return { found: false, playing: false, note: "어울리는 곡을 찾지 못함" };
      // 한 번에 곡 하나만 틀도록, 이번 응답에서 먼저 고른 곡이 있으면 새 곡으로 바꾼다.
      const kept = actions.filter((a) => a.type !== "play_sound");
      actions.length = 0;
      actions.push(...kept, playAction(pick.sound));
      return { found: true, title: pick.title, playing: true };
    }
    case "stop_sound":
      if (!actions.some((a) => a.type === "stop_sound")) actions.push({ type: "stop_sound" });
      return { stopped: true };
    default:
      return { error: "unknown_tool" };
  }
}
