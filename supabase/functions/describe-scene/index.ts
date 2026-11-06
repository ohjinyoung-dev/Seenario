// 카메라 프레임 1장을 받아 Gemini로 풍경을 라디오처럼 묘사한 한국어 문장을 돌려준다.
// - Gemini API 키는 Edge Function secret(GEMINI_API_KEY_<이름>, _shared/gemini.ts 참고)에만 둔다. 앱에는 절대 넣지 않는다.
// - 이미지는 묘사에만 쓰고 저장하지 않는다.
// - 로그인한(익명 포함) 사용자만 호출할 수 있다.
// - 사용자의 sound_mode가 ambient(환경음)/music(배경음악)이면 분위기 태그도 함께 받아
//   sounds 테이블에서 가장 잘 맞는 음원을 골라 재생 URL을 같이 돌려준다. off(기본)면 묘사만 돌려준다.

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { callGemini, extractText, HAS_GEMINI_KEY, parseJsonText } from "../_shared/gemini.ts";
import { isSignedIn, json, userClient } from "../_shared/http.ts";
import { pickSound, SOUND_MODES, type SoundMode } from "../_shared/sound_pick.ts";
import { normalizeTags, SOUND_TAG_KEYS, SOUND_TAGS, type SoundTag } from "../_shared/sound_tags.ts";

const MAX_IMAGE_BASE64_LENGTH = 2_000_000; // 약 1.5MB JPEG

const PROMPT = `너는 시각장애인을 위해 지금 눈앞의 풍경을 들려주는 라디오 진행자야.
사진에 담긴 공간의 분위기를 따뜻하고 감성적인 한국어로 묘사해 줘.

- 3~4문장, 소리 내어 읽기 좋은 자연스러운 구어체로 말해.
- 빛, 색감, 공간의 크기, 사물의 배치, 계절감이나 시간대처럼 분위기를 만드는 요소를 중심으로.
- 사람이 있다면 외모를 평가하지 말고 무엇을 하고 있는지만 부드럽게 전해.
- 발밑의 장애물처럼 안전에 중요한 것이 보이면 마지막에 짧게 알려 줘.
- 사진이 너무 어둡거나 흐려서 알아보기 어렵다면 솔직하게 그렇다고 말해.
- 목록, 기호, 이모지 없이 문장만 출력해.`;

const MAX_TAGS = 6;

const TAG_GUIDE = SOUND_TAG_KEYS.map((k) => `${k}(${SOUND_TAGS[k]})`).join(", ");

// 배경음/환경음 모드에서만 붙는 추가 지시. 응답은 JSON(description, tags)으로 받는다.
const TAG_PROMPT = `

추가로, 이 장면에 어울리는 소리를 고르기 위한 분위기 태그를 3~${MAX_TAGS}개 골라 줘.
- 반드시 아래 목록의 영문 단어 중에서만 골라.
- 장소, 날씨, 계절, 시간대, 분위기 중 사진에서 알 수 있는 것만 골라. 모르는 건 고르지 마.
- 목록: ${TAG_GUIDE}
- description에는 위 규칙대로 쓴 묘사 문장을, tags에는 고른 태그를 넣어.`;

const TAG_RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    description: { type: "STRING" },
    tags: { type: "ARRAY", items: { type: "STRING", enum: SOUND_TAG_KEYS } },
  },
  required: ["description", "tags"],
};

/** 사용자 설정의 sound_mode. 행이 없거나 읽기에 실패하면 기본값(off)으로 본다. */
async function loadSoundMode(supabase: SupabaseClient): Promise<SoundMode | null> {
  const { data, error } = await supabase.from("user_settings").select("sound_mode").maybeSingle();
  if (error) {
    console.error("user_settings read failed", error.message);
    return null;
  }
  const mode = data?.sound_mode;
  return typeof mode === "string" && SOUND_MODES.has(mode) ? (mode as SoundMode) : null;
}

const TOTAL_DEADLINE_MS = 34_000; // 앱의 요청 제한(40초)보다 짧게

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!HAS_GEMINI_KEY) return json({ error: "server_not_configured" }, 500);

  // 호출자 확인 (익명 로그인 사용자도 허용)
  // 사용자 토큰을 실어 보내 RLS가 이 사용자 기준으로 적용되게 한다.
  const client = userClient(req);
  if (!client) return json({ error: "unauthorized" }, 401);
  const { supabase, token } = client;
  const [signedIn, soundMode] = await Promise.all([
    isSignedIn(supabase, token),
    loadSoundMode(supabase),
  ]);
  if (!signedIn) return json({ error: "unauthorized" }, 401);

  let image: unknown;
  let mimeType: unknown;
  try {
    ({ image, mimeType } = await req.json());
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  if (typeof image !== "string" || image.length === 0) {
    return json({ error: "image_required" }, 400);
  }
  if (image.length > MAX_IMAGE_BASE64_LENGTH) {
    return json({ error: "image_too_large" }, 413);
  }
  const mime = mimeType === "image/png" ? "image/png" : "image/jpeg";

  const requestBody = {
    contents: [{
      role: "user",
      parts: [
        { text: soundMode ? PROMPT + TAG_PROMPT : PROMPT },
        { inline_data: { mime_type: mime, data: image } },
      ],
    }],
    // 풍경 묘사는 깊은 추론이 필요 없으므로 생각 단계를 최소로 줄여 응답 속도를 높인다.
    generationConfig: {
      thinkingConfig: { thinkingLevel: "minimal" },
      ...(soundMode ? { responseMimeType: "application/json", responseSchema: TAG_RESPONSE_SCHEMA } : {}),
    },
  };

  const result = await callGemini(requestBody, { deadlineMs: TOTAL_DEADLINE_MS });
  // 실패 시 모델별 상태 코드만 돌려준다(진단용, 민감 정보 없음).
  if (!result.data) return json({ error: "gemini_failed", attempts: result.attempts }, 502);

  const text = extractText(result.data);

  let description = text;
  let tags: SoundTag[] = [];
  if (soundMode) {
    // 일부 모델이 ```json 코드 블록으로 감싸 보내는 경우를 parseJsonText가 처리한다.
    const parsed = parseJsonText(text);
    if (parsed) {
      description = typeof parsed?.description === "string" ? parsed.description.trim() : "";
      tags = normalizeTags(parsed?.tags).slice(0, MAX_TAGS);
    } else {
      // JSON이 아니면 문장 그대로를 묘사로 쓰고 음원은 고르지 않는다.
      console.error("Gemini tag response not JSON", result.model, text.slice(0, 200));
    }
  }

  if (!description) {
    console.error("Gemini empty response", result.model, JSON.stringify(result.data).slice(0, 500));
    return json({ error: "empty_description" }, 502);
  }

  if (!soundMode) return json({ description, model: result.model });

  // 음원을 못 골라도 묘사는 그대로 돌려준다(sound: null).
  const sound = await pickSound(supabase, soundMode, tags);
  console.log("sound pick", soundMode, JSON.stringify(tags), sound?.id ?? "none");
  return json({ description, model: result.model, soundMode, tags, sound });
});
