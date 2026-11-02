// 카메라 프레임 1장을 받아 Gemini로 풍경을 라디오처럼 묘사한 한국어 문장을 돌려준다.
// - Gemini API 키는 Edge Function secret(GEMINI_API_KEY)에만 둔다. 앱에는 절대 넣지 않는다.
// - 이미지는 묘사에만 쓰고 저장하지 않는다.
// - 로그인한(익명 포함) 사용자만 호출할 수 있다.

import { createClient } from "jsr:@supabase/supabase-js@2";

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-flash-latest";
const MAX_IMAGE_BASE64_LENGTH = 2_000_000; // 약 1.5MB JPEG

const PROMPT = `너는 시각장애인을 위해 지금 눈앞의 풍경을 들려주는 라디오 진행자야.
사진에 담긴 공간의 분위기를 따뜻하고 감성적인 한국어로 묘사해 줘.

- 3~4문장, 소리 내어 읽기 좋은 자연스러운 구어체로 말해.
- 빛, 색감, 공간의 크기, 사물의 배치, 계절감이나 시간대처럼 분위기를 만드는 요소를 중심으로.
- 사람이 있다면 외모를 평가하지 말고 무엇을 하고 있는지만 부드럽게 전해.
- 발밑의 장애물처럼 안전에 중요한 것이 보이면 마지막에 짧게 알려 줘.
- 사진이 너무 어둡거나 흐려서 알아보기 어렵다면 솔직하게 그렇다고 말해.
- 목록, 기호, 이모지 없이 문장만 출력해.`;

// 모델 후보. 앞 모델이 [HEDGE_DELAY_MS] 안에 답하지 않으면 기존 요청은 둔 채 다음 모델을 함께 출발시키고,
// 가장 먼저 성공한 응답을 쓴다(나머지는 취소). 앞 모델이 오류로 끝나면 즉시 다음 모델을 출발시킨다.
// 계열(최신 별칭 vs 고정 버전, Flash vs Flash-Lite)을 섞어 동시에 막힐 가능성을 줄였다.
const FALLBACK_MODELS = (
  Deno.env.get("GEMINI_FALLBACK_MODELS") ??
    "gemini-3.6-flash,gemini-flash-lite-latest,gemini-3.5-flash-lite"
)
  .split(",")
  .map((m) => m.trim())
  .filter(Boolean);
// 이 상태 코드면 다음 모델로 넘어간다. 그 외(401/403 키 문제)는 전체 즉시 실패.
// 400은 모델마다 지원 옵션(thinking 수준 등)이 달라서 날 수 있으므로 다음 모델로 넘긴다.
const NEXT_MODEL_STATUS = new Set([400, 404, 429, 500, 503, 504]);
const HEDGE_DELAY_MS = 5_000; // 이 시간 안에 답이 없으면 다음 모델을 함께 출발
const ATTEMPT_TIMEOUT_MS = 20_000; // 모델 하나의 최대 대기 시간
const TOTAL_DEADLINE_MS = 34_000; // 앱의 요청 제한(40초)보다 짧게

type Attempt = { model: string; status: number | "timeout" | "aborted"; ms: number };
type GeminiResult = { model: string; data: any | null; attempts: Attempt[] };
type TryResult = { data: any | null; fatal: boolean; attempt: Attempt };

async function tryModel(model: string, body: string, signal: AbortSignal): Promise<TryResult> {
  const started = Date.now();
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY! },
        body,
        signal,
      },
    );
    const attempt: Attempt = { model, status: res.status, ms: Date.now() - started };
    if (res.ok) return { data: await res.json(), fatal: false, attempt };
    const detail = (await res.text()).slice(0, 300);
    console.error("Gemini error", model, res.status, `${attempt.ms}ms`, detail);
    return { data: null, fatal: !NEXT_MODEL_STATUS.has(res.status), attempt };
  } catch (e) {
    const status = signal.reason?.name === "TimeoutError" ? "timeout" : "aborted";
    return { data: null, fatal: false, attempt: { model, status, ms: Date.now() - started } };
  }
}

/** 모델 후보를 시간차로 출발시켜 가장 먼저 성공한 응답을 돌려준다. 모두 실패하면 data가 null. */
function callGemini(body: string): Promise<GeminiResult> {
  const models = [GEMINI_MODEL, ...FALLBACK_MODELS.filter((m) => m !== GEMINI_MODEL)];
  const attempts: Attempt[] = [];
  const controllers: AbortController[] = [];
  const deadline = AbortSignal.timeout(TOTAL_DEADLINE_MS);

  return new Promise((resolve) => {
    let next = 0;
    let running = 0;
    let done = false;
    let hedgeTimer: number | undefined;

    const finish = (result: GeminiResult) => {
      if (done) return;
      done = true;
      clearTimeout(hedgeTimer);
      controllers.forEach((c) => c.abort());
      console.log("Gemini attempts", JSON.stringify(result.attempts));
      resolve(result);
    };
    const fail = () => finish({ model: "", data: null, attempts });

    const launch = () => {
      if (done) return;
      clearTimeout(hedgeTimer);
      if (next >= models.length) {
        if (running === 0) fail();
        return;
      }
      const model = models[next++];
      const controller = new AbortController();
      controllers.push(controller);
      running++;
      hedgeTimer = setTimeout(launch, HEDGE_DELAY_MS);

      const signal = AbortSignal.any([controller.signal, deadline, AbortSignal.timeout(ATTEMPT_TIMEOUT_MS)]);
      tryModel(model, body, signal).then((r) => {
        running--;
        if (done) return;
        attempts.push(r.attempt);
        if (r.data) finish({ model, data: r.data, attempts });
        else if (r.fatal) fail();
        else launch(); // 오류로 끝났으면 기다리지 말고 다음 모델을 바로 출발
      });
    };

    deadline.addEventListener("abort", fail);
    launch();
  });
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!GEMINI_API_KEY) return json({ error: "server_not_configured" }, 500);

  // 호출자 확인 (익명 로그인 사용자도 허용)
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "unauthorized" }, 401);
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
  );
  const { data: userData, error: authError } = await supabase.auth.getUser(token);
  if (authError || !userData.user) return json({ error: "unauthorized" }, 401);

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

  const requestBody = JSON.stringify({
    contents: [{
      role: "user",
      parts: [
        { text: PROMPT },
        { inline_data: { mime_type: mime, data: image } },
      ],
    }],
    // 풍경 묘사는 깊은 추론이 필요 없으므로 생각 단계를 최소로 줄여 응답 속도를 높인다.
    generationConfig: { thinkingConfig: { thinkingLevel: "minimal" } },
  });

  const result = await callGemini(requestBody);
  // 실패 시 모델별 상태 코드만 돌려준다(진단용, 민감 정보 없음).
  if (!result.data) return json({ error: "gemini_failed", attempts: result.attempts }, 502);

  const description = (result.data?.candidates?.[0]?.content?.parts ?? [])
    .map((p: { text?: string; thought?: boolean }) => (p.thought ? "" : p.text ?? ""))
    .join("")
    .trim();

  if (!description) {
    console.error("Gemini empty response", result.model, JSON.stringify(result.data).slice(0, 500));
    return json({ error: "empty_description" }, 502);
  }

  return json({ description, model: result.model });
});
