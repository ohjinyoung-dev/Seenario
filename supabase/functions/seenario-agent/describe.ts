// describe: 반복 없이 고정 순서로 처리한다. 사진 → Gemini 1번 → 묘사(+소리 모드면 태그로 곡 고르기, 지금은 로그만).

import { callGemini, extractText, parseJsonText } from "../_shared/gemini.ts";
import type { SoundMode } from "../_shared/sound_pick.ts";
import { SOUND_TAG_KEYS } from "../_shared/sound_tags.ts";
import { describeInstruction, systemPrompt } from "./prompts.ts";
import { logPick } from "./sound_tools.ts";
import { type AgentContext, AgentError, type AgentResponse, EXISTING_SOUNDS, remainingMs } from "./types.ts";

const SPEECH_ONLY_SCHEMA = {
  type: "OBJECT",
  properties: { speech: { type: "STRING" } },
  required: ["speech"],
};

const WITH_SOUND_SCHEMA = {
  type: "OBJECT",
  properties: {
    speech: { type: "STRING" },
    tags: { type: "ARRAY", items: { type: "STRING", enum: SOUND_TAG_KEYS } },
    existing_sounds: { type: "ARRAY", items: { type: "STRING", enum: [...EXISTING_SOUNDS] } },
  },
  required: ["speech", "tags", "existing_sounds"],
};

export async function describe(ctx: AgentContext): Promise<AgentResponse> {
  const { request } = ctx;
  // 사진이 없으면 앱이 기존 describe-scene으로 대신 묘사한다.
  if (!request.image) throw new AgentError(400, "image_required");
  const soundOn = request.sound_mode !== "off";

  const body = {
    systemInstruction: { parts: [{ text: systemPrompt(request.sound_mode, true) }] },
    contents: [{
      role: "user",
      parts: [
        { text: describeInstruction(soundOn) },
        { inline_data: { mime_type: "image/jpeg", data: request.image } },
      ],
    }],
    // 풍경 묘사는 깊은 추론이 필요 없으므로 생각 단계를 최소로 줄여 응답 속도를 높인다.
    generationConfig: {
      thinkingConfig: { thinkingLevel: "minimal" },
      responseMimeType: "application/json",
      responseSchema: soundOn ? WITH_SOUND_SCHEMA : SPEECH_ONLY_SCHEMA,
    },
  };

  const result = await callGemini(body, { deadlineMs: remainingMs(ctx) });
  if (!result.data) throw new AgentError(502, "gemini_failed");

  const text = extractText(result.data);
  const parsed = parseJsonText(text);
  // JSON이 아니면 글자 그대로를 묘사로 쓴다.
  const speech = (typeof parsed?.speech === "string" ? parsed.speech : parsed ? "" : text).trim();
  if (!speech) {
    console.error("describe empty response", result.model, text.slice(0, 200));
    throw new AgentError(502, "empty_response");
  }
  console.log(`describe ok model=${result.model}`);

  if (soundOn) {
    await logPick(ctx, request.sound_mode as SoundMode, parsed?.tags, parsed?.existing_sounds, "describe");
  }
  return { speech, display: speech, actions: [] };
}
