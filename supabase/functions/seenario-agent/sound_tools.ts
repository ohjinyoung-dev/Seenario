// 에이전트의 소리 도구(pick_sound, stop_sound).
// - 곡 고르기는 팀장님이 만드는 tools/sound.ts가 오기 전까지 **임시로** _shared/sound_pick.ts를 쓰고,
//   고른 곡은 로그로만 남긴다(응답 actions에 play_sound를 넣지 않음).
//   sound.ts가 오면 logPick()의 pickSound 호출을 그걸로 바꾸고 play_sound action을 붙인다.

import { pickSound, type SoundMode } from "../_shared/sound_pick.ts";
import { normalizeTags, SOUND_TAG_KEYS } from "../_shared/sound_tags.ts";
import { MAX_TAGS } from "./prompts.ts";
import { type AgentAction, type AgentContext, EXISTING_SOUNDS, normalizeExistingSounds } from "./types.ts";

export type PickLog = { found: boolean; title?: string };

/** 임시: 곡을 골라 로그만 남긴다. url은 로그에도 남기지 않는다. */
export async function logPick(
  ctx: AgentContext,
  mode: SoundMode,
  rawTags: unknown,
  rawExisting: unknown,
  from: string,
): Promise<PickLog> {
  const tags = normalizeTags(rawTags).slice(0, MAX_TAGS);
  const existing = normalizeExistingSounds(rawExisting);
  const picked = await pickSound(ctx.supabase, mode, tags);
  console.log(
    `sound pick (임시, 재생 안 함) from=${from} mode=${mode} tags=${JSON.stringify(tags)} existing=${JSON.stringify(existing)}`,
    picked ? `→ ${picked.id} "${picked.title}"` : "→ none",
  );
  return picked ? { found: true, title: picked.title } : { found: false };
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
      return pick.found
        ? { found: true, title: pick.title, playing: false, note: "아직 재생 기능이 없어 실제로 틀지는 않음" }
        : { found: false, playing: false, note: "어울리는 곡을 찾지 못함" };
    }
    case "stop_sound":
      if (!actions.some((a) => a.type === "stop_sound")) actions.push({ type: "stop_sound" });
      return { stopped: true };
    default:
      return { error: "unknown_tool" };
  }
}
