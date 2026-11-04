// 씨나리오 에이전트. 앱 ↔ 에이전트 약속(CLAUDE.md) 형식으로 요청을 받아 { speech, display, actions }를 돌려준다.
// - AI는 Gemini만 쓴다. API 키는 Edge Function secret(GEMINI_API_KEY)에만 둔다.
// - describe("묘사해줘"): 반복 없이 사진 → Gemini 1번 → 묘사. 사진이 없으면 400(앱이 describe-scene으로 대신 묘사).
// - speech(그 밖의 말): 생각 → 도구 사용 → 판단을 최대 3번 반복한 뒤 답한다.
// - sound(소리 모드를 켤 때): 묘사 없이 사진 → Gemini 1번 → 곡 고르기. speech/display는 빈 문자열.
// - 곡 고르기는 tools/sound.ts를 쓰고, 고른 곡은 actions의 play_sound로 보낸다.
// - 사진은 요청 처리에만 쓰고 저장하지 않는다. 로그에도 사진과 사용자 말 전문은 남기지 않는다.
// - 로그인한(익명 포함) 사용자만 호출할 수 있다.

import { GEMINI_API_KEY } from "../_shared/gemini.ts";
import { isSignedIn, json, userClient } from "../_shared/http.ts";
import { describe } from "./describe.ts";
import { soundOnly } from "./sound_request.ts";
import { converse } from "./speech.ts";
import { type AgentContext, AgentError, type AgentRequest, type HistoryItem, type SoundModeOrOff } from "./types.ts";

/** 서버 전체 응답 시간. 앱의 20초 안에 describe-scene 대신 묘사 시간이 남도록 짧게 둔다. */
const TOTAL_DEADLINE_MS = 15_000;
const MAX_IMAGE_BASE64_LENGTH = 2_000_000; // 약 1.5MB JPEG (describe-scene과 같음)
const MAX_INPUT_LENGTH = 1_000;
const MAX_HISTORY = 6;
const MAX_HISTORY_TEXT_LENGTH = 2_000;
const SOUND_MODES = new Set<string>(["off", "ambient", "music"]);

/** 요청 본문을 검사해 약속 형식으로 맞춘다. 잘못되면 AgentError. */
function parseRequest(body: any): AgentRequest {
  if (typeof body !== "object" || body === null) throw new AgentError(400, "invalid_json");

  const inputType = body.input_type;
  if (inputType !== "speech" && inputType !== "describe" && inputType !== "sound") {
    throw new AgentError(400, "invalid_input_type");
  }

  const input = typeof body.input === "string" ? body.input.trim() : "";
  // sound는 사용자 말이 없는 요청이라 빈 input을 허용한다.
  if (!input && inputType !== "sound") throw new AgentError(400, "input_required");
  if (input.length > MAX_INPUT_LENGTH) throw new AgentError(400, "input_too_long");

  let image: string | undefined;
  if (body.image != null) {
    if (typeof body.image !== "string") throw new AgentError(400, "invalid_image");
    if (body.image.length > MAX_IMAGE_BASE64_LENGTH) throw new AgentError(413, "image_too_large");
    image = body.image || undefined;
  }

  const history: HistoryItem[] = (Array.isArray(body.history) ? body.history : [])
    .filter((h: any) => (h?.role === "user" || h?.role === "agent") && typeof h?.text === "string" && h.text.trim())
    .slice(-MAX_HISTORY)
    .map((h: any) => ({ role: h.role, text: h.text.trim().slice(0, MAX_HISTORY_TEXT_LENGTH) }));

  // 모르는 값이나 빈 값이면 기본 모드(off)로 본다.
  const soundMode: SoundModeOrOff = SOUND_MODES.has(body.sound_mode) ? body.sound_mode : "off";
  // sound는 소리 모드(ambient/music) 안에서만 곡을 고른다.
  if (inputType === "sound" && soundMode === "off") throw new AgentError(400, "sound_mode_off");

  return { input, input_type: inputType, image, history, sound_mode: soundMode };
}

Deno.serve(async (req) => {
  const started = Date.now();
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!GEMINI_API_KEY) return json({ error: "server_not_configured" }, 500);

  const client = userClient(req);
  if (!client) return json({ error: "unauthorized" }, 401);

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  try {
    const request = parseRequest(body);
    if (!(await isSignedIn(client.supabase, client.token))) return json({ error: "unauthorized" }, 401);

    console.log(
      `agent request input_type=${request.input_type} sound_mode=${request.sound_mode} ` +
        `image=${request.image ? "yes" : "no"} history=${request.history.length}`,
    );
    const ctx: AgentContext = { supabase: client.supabase, request, deadlineAt: started + TOTAL_DEADLINE_MS };
    const response = request.input_type === "describe"
      ? await describe(ctx)
      : request.input_type === "sound"
      ? await soundOnly(ctx)
      : await converse(ctx);
    console.log(`agent ok in ${Date.now() - started}ms actions=${JSON.stringify(response.actions.map((a) => a.type))}`);
    return json(response);
  } catch (e) {
    if (e instanceof AgentError) {
      console.error(`agent error ${e.status} ${e.code} in ${Date.now() - started}ms`);
      return json({ error: e.code }, e.status);
    }
    console.error("agent unexpected error", e);
    return json({ error: "internal_error" }, 500);
  }
});
