// Gemini generateContent 호출 공용 코드 (describe-scene, seenario-agent에서 같이 쓴다).
// - Gemini API 키는 Edge Function secret(GEMINI_API_KEY)에만 둔다. 앱에는 절대 넣지 않는다.
// - 모델 후보는 secret GEMINI_MODELS(쉼표로 구분, 앞에서부터 먼저 부름)로 바꿀 수 있다. 없으면 DEFAULT_MODELS.
//   무료 한도는 모델마다 따로라서, 한도가 넉넉한 모델을 앞에 두고 한도가 작은 모델은 뒤에 둔다.
// - 사용량 초과(429)가 난 모델은 Google이 알려 준 대기 시간 동안 건너뛴다.
// - 모델이 요청한 생각 단계(thinkingLevel)를 지원하지 않으면(400) 같은 모델에 low → 생각 설정 없음 순으로 다시 보낸다.
// - 건너뛰기/생각 단계 기억은 함수 인스턴스 메모리에만 있다(인스턴스가 새로 뜨면 처음부터 다시 배운다).

export const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");

// 2026-11 AI Studio 무료 한도 기준: Flash Lite 2종은 15 RPM / 500 RPD, Flash 2종은 5 RPM / 20 RPD.
const DEFAULT_MODELS = "gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-3.6-flash,gemini-3.8-flash";
const MODELS = (Deno.env.get("GEMINI_MODELS") ?? DEFAULT_MODELS)
  .split(",")
  .map((m) => m.trim())
  .filter((m, i, all) => m && all.indexOf(m) === i);

// 이 상태 코드면 다음 모델로 넘어간다. 그 외(401/403 키 문제)는 전체 즉시 실패.
// 400은 모델마다 지원 옵션이 달라서 날 수 있으므로 다음 모델로 넘긴다.
const NEXT_MODEL_STATUS = new Set([400, 404, 429, 500, 503, 504]);
const HEDGE_DELAY_MS = 5_000; // 이 시간 안에 답이 없으면 다음 모델을 함께 출발
const ATTEMPT_TIMEOUT_MS = 20_000; // 모델 하나의 최대 대기 시간
const DEFAULT_COOLDOWN_MS = 60_000; // 429인데 대기 시간을 알려 주지 않으면
const DAILY_COOLDOWN_MS = 60 * 60_000; // 하루 한도 초과면 길게 쉰다(인스턴스가 그 전에 내려가면 다시 시도)

/** 모델별 사용량 초과 대기 종료 시각(Date.now() 기준). */
const cooldownUntil = new Map<string, number>();

/** 요청한 생각 단계를 그대로 쓸지, low로 낮출지, 생각 설정을 뺄지. */
type ThinkingMode = "as_is" | "low" | "none";
const NEXT_THINKING: Record<ThinkingMode, ThinkingMode | null> = { as_is: "low", low: "none", none: null };
/** 모델별로 통했던 생각 단계(as_is가 안 됐던 모델만 기록). */
const thinkingModeOf = new Map<string, ThinkingMode>();

export type Attempt = { model: string; status: number | "timeout" | "aborted"; ms: number };
export type GeminiResult = { model: string; data: any | null; attempts: Attempt[] };
type TryResult = { data: any | null; fatal: boolean; attempt: Attempt };

/** generateContent 요청 본문. generationConfig.thinkingConfig는 모델에 맞게 바뀔 수 있다. */
export type GeminiBody = Record<string, any>;

export type CallOptions = {
  /** 전체 대기 시간. 이 안에 아무 모델도 성공하지 못하면 data가 null. */
  deadlineMs: number;
  /** 이 모델을 맨 앞에 둔다(반복 대화에서 앞 라운드에 답한 모델을 먼저 부를 때). */
  preferModel?: string;
};

function withThinking(body: GeminiBody, mode: ThinkingMode): string {
  const config = body.generationConfig;
  if (mode === "as_is" || !config?.thinkingConfig) return JSON.stringify(body);
  const { thinkingConfig, ...rest } = config;
  const generationConfig = mode === "low" ? { ...rest, thinkingConfig: { ...thinkingConfig, thinkingLevel: "low" } } : rest;
  return JSON.stringify({ ...body, generationConfig });
}

/** 429 응답에서 쉴 시간을 정한다. 하루 한도면 길게, 아니면 Google이 알려 준 retryDelay만큼. */
function cooldownMs(errorText: string): number {
  if (/PerDay/i.test(errorText)) return DAILY_COOLDOWN_MS;
  const delay = errorText.match(/"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"/);
  return delay ? Math.ceil(Number(delay[1]) * 1000) : DEFAULT_COOLDOWN_MS;
}

async function tryModel(model: string, body: GeminiBody, signal: AbortSignal): Promise<TryResult> {
  const started = Date.now();
  let mode: ThinkingMode = thinkingModeOf.get(model) ?? "as_is";
  try {
    while (true) {
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY! },
          body: withThinking(body, mode),
          signal,
        },
      );
      const attempt: Attempt = { model, status: res.status, ms: Date.now() - started };
      if (res.ok) return { data: await res.json(), fatal: false, attempt };

      const errorText = await res.text();
      const next = NEXT_THINKING[mode];
      if (res.status === 400 && /thinking/i.test(errorText) && next) {
        // 이 모델이 지원하지 않는 생각 단계 → 낮춰서 같은 모델에 바로 다시 보낸다.
        console.log("Gemini thinking fallback", model, `${mode} → ${next}`);
        mode = next;
        thinkingModeOf.set(model, next);
        continue;
      }
      if (res.status === 429) {
        const wait = cooldownMs(errorText);
        cooldownUntil.set(model, Date.now() + wait);
        console.error("Gemini quota exceeded", model, `skip ${Math.round(wait / 1000)}s`);
      } else {
        console.error("Gemini error", model, res.status, `${attempt.ms}ms`, errorText.slice(0, 300));
      }
      return { data: null, fatal: !NEXT_MODEL_STATUS.has(res.status), attempt };
    }
  } catch (_e) {
    const status = signal.reason?.name === "TimeoutError" ? "timeout" : "aborted";
    return { data: null, fatal: false, attempt: { model, status, ms: Date.now() - started } };
  }
}

/** 부를 순서. 사용량 초과로 쉬는 모델은 뺀다. 모두 쉬는 중이면 가장 먼저 풀리는 모델 하나만. */
function modelOrder(preferModel?: string): string[] {
  const all = preferModel && MODELS.includes(preferModel)
    ? [preferModel, ...MODELS.filter((m) => m !== preferModel)]
    : MODELS;
  const now = Date.now();
  const ready = all.filter((m) => (cooldownUntil.get(m) ?? 0) <= now);
  const skipped = all.filter((m) => !ready.includes(m));
  if (skipped.length > 0) console.log("Gemini skip (quota)", JSON.stringify(skipped));
  if (ready.length > 0) return ready;
  return [[...all].sort((a, b) => (cooldownUntil.get(a) ?? 0) - (cooldownUntil.get(b) ?? 0))[0]];
}

/** 모델 후보를 시간차로 출발시켜 가장 먼저 성공한 응답을 돌려준다. 모두 실패하면 data가 null. */
export function callGemini(body: GeminiBody, options: CallOptions): Promise<GeminiResult> {
  const models = modelOrder(options.preferModel);
  const attempts: Attempt[] = [];
  const controllers: AbortController[] = [];
  const deadline = AbortSignal.timeout(Math.max(1, options.deadlineMs));

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

type Part = { text?: string; thought?: boolean; functionCall?: { name: string; args?: unknown } };

/** 첫 후보의 응답 파트들(생각 파트 포함, 그대로). 다음 라운드에 모델 응답으로 다시 보낼 때 쓴다. */
export function responseParts(data: any): Part[] {
  return (data?.candidates?.[0]?.content?.parts ?? []) as Part[];
}

/** 생각(thought) 파트를 빼고 글자만 이어 붙인다. */
export function extractText(data: any): string {
  return responseParts(data)
    .map((p) => (p.thought ? "" : p.text ?? ""))
    .join("")
    .trim();
}

/** JSON 응답 글자를 파싱한다. 일부 모델이 ```json 코드 블록으로 감싸 보내는 경우를 대비한다. 실패하면 null. */
export function parseJsonText(text: string): any | null {
  try {
    return JSON.parse(text.replace(/^```(?:json)?\s*|\s*```$/g, ""));
  } catch {
    return null;
  }
}
