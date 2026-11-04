// Gemini generateContent 호출 공용 코드 (describe-scene, seenario-agent에서 같이 쓴다).
// - Gemini API 키는 Edge Function secret(GEMINI_API_KEY)에만 둔다. 앱에는 절대 넣지 않는다.

export const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY");
export const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-flash-latest";

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

export type Attempt = { model: string; status: number | "timeout" | "aborted"; ms: number };
export type GeminiResult = { model: string; data: any | null; attempts: Attempt[] };
type TryResult = { data: any | null; fatal: boolean; attempt: Attempt };

export type CallOptions = {
  /** 전체 대기 시간. 이 안에 아무 모델도 성공하지 못하면 data가 null. */
  deadlineMs: number;
  /** 이 모델을 맨 앞에 둔다(반복 대화에서 앞 라운드에 답한 모델을 먼저 부를 때). */
  preferModel?: string;
};

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
  } catch (_e) {
    const status = signal.reason?.name === "TimeoutError" ? "timeout" : "aborted";
    return { data: null, fatal: false, attempt: { model, status, ms: Date.now() - started } };
  }
}

/** 모델 후보를 시간차로 출발시켜 가장 먼저 성공한 응답을 돌려준다. 모두 실패하면 data가 null. */
export function callGemini(body: string, options: CallOptions): Promise<GeminiResult> {
  const first = options.preferModel ?? GEMINI_MODEL;
  const models = [first, ...[GEMINI_MODEL, ...FALLBACK_MODELS].filter((m) => m !== first)]
    .filter((m, i, all) => all.indexOf(m) === i);
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
