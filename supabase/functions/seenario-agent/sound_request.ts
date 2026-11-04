// sound: 소리 모드를 켤 때 앱이 보내는 조용한 요청. 묘사 문장 없이 곡만 고른다.
// 사진 → Gemini 1번(tags, existing_sounds) → tools/sound.ts로 곡 고르기 → play_sound(없으면 빈 actions).
// history는 쓰지 않는다.

import { callGemini, extractText, parseJsonText } from "../_shared/gemini.ts";
import type { SoundMode } from "../_shared/sound_pick.ts";
import { SOUND_TAG_KEYS } from "../_shared/sound_tags.ts";
import { soundInstruction } from "./prompts.ts";
import { logPick, playAction } from "./sound_tools.ts";
import { type AgentAction, type AgentContext, AgentError, type AgentResponse, EXISTING_SOUNDS, remainingMs } from "./types.ts";

const SOUND_ONLY_SCHEMA = {
  type: "OBJECT",
  properties: {
    tags: { type: "ARRAY", items: { type: "STRING", enum: SOUND_TAG_KEYS } },
    existing_sounds: { type: "ARRAY", items: { type: "STRING", enum: [...EXISTING_SOUNDS] } },
  },
  required: ["tags", "existing_sounds"],
};

export async function soundOnly(ctx: AgentContext): Promise<AgentResponse> {
  const { request } = ctx;
  // sound_mode가 off인 요청은 index.ts에서 이미 막는다.
  if (!request.image) throw new AgentError(400, "image_required");

  const body = {
    contents: [{
      role: "user",
      parts: [
        { text: soundInstruction() },
        { inline_data: { mime_type: "image/jpeg", data: request.image } },
      ],
    }],
    generationConfig: {
      thinkingConfig: { thinkingLevel: "minimal" },
      responseMimeType: "application/json",
      responseSchema: SOUND_ONLY_SCHEMA,
    },
  };

  const result = await callGemini(body, { deadlineMs: remainingMs(ctx) });
  if (!result.data) throw new AgentError(502, "gemini_failed");

  const parsed = parseJsonText(extractText(result.data));
  if (!parsed) {
    console.error("sound empty response", result.model);
    throw new AgentError(502, "empty_response");
  }
  console.log(`sound ok model=${result.model}`);

  const actions: AgentAction[] = [];
  const pick = await logPick(ctx, request.sound_mode as SoundMode, parsed.tags, parsed.existing_sounds, "sound");
  if (pick.sound) actions.push(playAction(pick.sound));
  return { speech: "", display: "", actions };
}
