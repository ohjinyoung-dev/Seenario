// Gemini generateContent 호출 공용 코드 (describe-scene, seenario-agent에서 같이 쓴다).
// - Gemini API 키는 Edge Function secret에만 둔다. 앱에는 절대 넣지 않는다.
//   키마다 secret을 따로 둔다: GEMINI_API_KEY_<이름> 또는 GEMINI_KEY_<이름>(예: GEMINI_API_KEY_OJY1).
//   이름(ojy1)만 DB와 로그에 남는다.
//   처음 등록한 secret GEMINI_API_KEY도 이름 default로 함께 돌려쓴다.
// - 키는 모델마다 오늘(태평양 시간) 덜 쓴 것부터 쓴다. 한도 초과(429)나 키 오류면 같은 모델을 다른 키로 바로 다시 부른다.
// - 모델 후보는 secret GEMINI_MODELS(쉼표로 구분, 앞에서부터 먼저 부름)로 바꿀 수 있다. 없으면 DEFAULT_MODELS.
//   무료 한도는 모델마다 따로라서, 한도가 넉넉한 모델을 앞에 두고 한도가 작은 모델은 뒤에 둔다.
// - 과부하(503/500/504)·응답 없음·시간 초과는 그 모델을 10분(모든 키), 사용량 초과(429)는 그 키×모델을
//   Google이 알려 준 시간만큼, 키 오류(401/402 결제·크레딧/403/잘못된 키)는 그 키를 1시간 건너뛴다.
//   건너뛰기와 오늘 사용량은 model_status.ts가 테이블 gemini_model_status에 두어 모든 인스턴스가 함께 본다.
// - 모델이 요청한 생각 단계(thinkingLevel)를 지원하지 않으면(400) 같은 모델에 low → 생각 설정 없음 순으로 다시 보낸다.
//   생각 단계 기억은 함수 인스턴스 메모리에만 있다(인스턴스가 새로 뜨면 처음부터 다시 배운다).

import { loadStatus, type ModelPlan, planAttempts, recordOutcome, skipRuleFor, SLOW_MS } from "./model_status.ts";

type GeminiKey = { name: string; key: string };
const KEY_PREFIXES = ["GEMINI_API_KEY_", "GEMINI_KEY_"];

/** secret GEMINI_API_KEY_<이름>, GEMINI_KEY_<이름>, GEMINI_API_KEY(이름 default)를 모두 읽는다. */
function loadKeys(): GeminiKey[] {
  let env: Record<string, string> = {};
  try {
    env = Deno.env.toObject();
  } catch (e) {
    console.error("Gemini keys: env read failed", e instanceof Error ? e.message : String(e));
  }
  const byName = new Map<string, string>();
  for (const [name, value] of Object.entries(env)) {
    const prefix = KEY_PREFIXES.find((p) => name.startsWith(p) && name.length > p.length);
    if (prefix && value.trim()) byName.set(name.slice(prefix.length).toLowerCase(), value.trim());
  }
  // 처음 등록한 GEMINI_API_KEY도 이름 default로 함께 쓴다(같은 키 값이 다른 이름으로 이미 있으면 한 번만).
  const legacy = Deno.env.get("GEMINI_API_KEY")?.trim();
  if (legacy && !byName.has("default") && ![...byName.values()].includes(legacy)) byName.set("default", legacy);
  return [...byName].map(([name, key]) => ({ name, key })).sort((a, b) => a.name.localeCompare(b.name));
}

const KEYS = loadKeys();
const KEY_BY_NAME = new Map(KEYS.map((k) => [k.name, k.key]));
console.log("Gemini keys", JSON.stringify(KEYS.map((k) => k.name)));

/** 쓸 수 있는 Gemini 키가 하나라도 있는지. 없으면 함수는 server_not_configured로 답한다. */
export const HAS_GEMINI_KEY = KEYS.length > 0;

// 2026-11 AI Studio 무료 한도 기준: Flash Lite 2종은 15 RPM / 500 RPD, Flash 2종은 5 RPM / 20 RPD.
const DEFAULT_MODELS = "gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-3.6-flash,gemini-3.8-flash";
const MODELS = (Deno.env.get("GEMINI_MODELS") ?? DEFAULT_MODELS)
  .split(",")
  .map((m) => m.trim())
  .filter((m, i, all) => m && all.indexOf(m) === i);

// 이 상태 코드면 다음 키나 다음 모델로 넘어간다. 그 외(413 등 요청 자체 문제)는 전체 즉시 실패.
// 400은 모델마다 지원 옵션이 달라서 날 수 있으므로 다음 모델로 넘긴다(잘못된 키 400은 다음 키로).
const NEXT_STATUS = new Set([400, 401, 402, 403, 404, 429, 500, 503, 504]);
const HEDGE_DELAY_MS = 2_500; // 이 시간 안에 답이 없으면 다음 모델을 함께 출발
const ATTEMPT_TIMEOUT_MS = 20_000; // 시도 하나의 최대 대기 시간

/** 요청한 생각 단계를 그대로 쓸지, low로 낮출지, 생각 설정을 뺄지. */
type ThinkingMode = "as_is" | "low" | "none";
const NEXT_THINKING: Record<ThinkingMode, ThinkingMode | null> = { as_is: "low", low: "none", none: null };
/** 모델별로 통했던 생각 단계(as_is가 안 됐던 모델만 기록). */
const thinkingModeOf = new Map<string, ThinkingMode>();

export type Attempt = { model: string; key: string; status: number | "timeout" | "aborted"; ms: number };
export type GeminiResult = { model: string; key: string; data: any | null; attempts: Attempt[] };
/** next: 실패했을 때 같은 모델을 다음 키로 부를지("key"), 다음 모델로 넘어갈지("model"). */
type TryResult = { data: any | null; fatal: boolean; next: "key" | "model"; attempt: Attempt };

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

async function tryModel(model: string, keyName: string, body: GeminiBody, signal: AbortSignal): Promise<TryResult> {
  const started = Date.now();
  let mode: ThinkingMode = thinkingModeOf.get(model) ?? "as_is";
  try {
    while (true) {
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": KEY_BY_NAME.get(keyName)! },
          body: withThinking(body, mode),
          signal,
        },
      );
      const attempt: Attempt = { model, key: keyName, status: res.status, ms: Date.now() - started };
      if (res.ok) {
        const data = await res.json();
        recordOutcome(keyName, model, { kind: "ok" }, attempt.ms, data);
        return { data, fatal: false, next: "model", attempt };
      }

      const errorText = await res.text();
      const nextThinking = NEXT_THINKING[mode];
      if (res.status === 400 && /thinking/i.test(errorText) && nextThinking) {
        // 이 모델이 지원하지 않는 생각 단계 → 낮춰서 같은 모델에 바로 다시 보낸다.
        console.log("Gemini thinking fallback", model, `${mode} → ${nextThinking}`);
        mode = nextThinking;
        thinkingModeOf.set(model, nextThinking);
        continue;
      }
      console.error("Gemini error", model, `key=${keyName}`, res.status, `${attempt.ms}ms`, errorText.slice(0, 300));
      const outcome = { kind: "http", status: res.status, body: errorText } as const;
      recordOutcome(keyName, model, outcome, attempt.ms);
      // 한도 초과·키 오류는 키 탓이라 같은 모델을 다른 키로 부른다. 나머지는 모델 탓이라 다음 모델로.
      const rule = skipRuleFor(outcome, Date.now());
      const next = rule && rule !== "clear" && rule.scope !== "model" ? "key" : "model";
      return { data: null, fatal: !NEXT_STATUS.has(res.status), next, attempt };
    }
  } catch (_e) {
    const ms = Date.now() - started;
    const timedOut = signal.reason?.name === "TimeoutError";
    // 오래 기다린 끝에 끝난 시도만 기록한다. 짧게 취소된 건(다른 모델이 금방 성공) 모델 탓이 아니다.
    if (ms >= SLOW_MS) recordOutcome(keyName, model, { kind: timedOut ? "timeout" : "slow" }, ms);
    const attempt: Attempt = { model, key: keyName, status: timedOut ? "timeout" : "aborted", ms };
    return { data: null, fatal: false, next: "model", attempt };
  }
}

/**
 * 모델 후보를 시간차로 출발시켜 가장 먼저 성공한 응답을 돌려준다. 모두 실패하면 data가 null.
 * - 시간차(2.5초)로 함께 출발시키는 건 다음 "모델"이다(느린 건 모델 탓이라 같은 모델의 다른 키는 도움이 안 된다).
 * - 한도 초과·키 오류로 끝나면 같은 모델을 다음 키로 바로 부르고, 남은 키가 없으면 다음 모델로 넘어간다.
 */
export async function callGemini(body: GeminiBody, options: CallOptions): Promise<GeminiResult> {
  const started = Date.now();
  const status = await loadStatus(); // 최대 700ms. 실패하면 메모리 기록으로 진행
  const { plan, skipped } = planAttempts(MODELS, KEYS.map((k) => k.name), status, Date.now(), options.preferModel);
  if (skipped.length > 0) console.log("Gemini skip", JSON.stringify(skipped));
  const attempts: Attempt[] = [];
  const controllers: AbortController[] = [];
  const inflight = new Map<string, { model: string; key: string; at: number }>(); // 아직 끝나지 않은 시도
  const deadline = AbortSignal.timeout(Math.max(1, options.deadlineMs - (Date.now() - started)));

  return await new Promise((resolve) => {
    let nextModel = 0;
    let running = 0;
    let done = false;
    let hedgeTimer: ReturnType<typeof setTimeout> | undefined;

    const finish = (result: GeminiResult) => {
      if (done) return;
      done = true;
      clearTimeout(hedgeTimer);
      // 끝나지 않은 시도도 로그에 남긴다(취소되면 tryModel의 catch에서 오래 걸린 시도는 상태 기록).
      const now = Date.now();
      for (const t of inflight.values()) attempts.push({ model: t.model, key: t.key, status: "aborted", ms: now - t.at });
      controllers.forEach((c) => c.abort());
      console.log("Gemini attempts", JSON.stringify(result.attempts));
      resolve(result);
    };
    const fail = () => finish({ model: "", key: "", data: null, attempts });

    /** 다음 모델을 그 모델의 첫 키로 출발시킨다. 남은 모델이 없으면 진행 중인 시도가 없을 때 실패. */
    const launchNextModel = () => {
      if (done) return;
      if (nextModel >= plan.length) {
        clearTimeout(hedgeTimer);
        if (running === 0) fail();
        return;
      }
      launch(plan[nextModel++], 0);
    };

    const launch = (entry: ModelPlan, keyIndex: number) => {
      const { model } = entry;
      const key = entry.keys[keyIndex];
      const id = `${key}|${model}`;
      const controller = new AbortController();
      controllers.push(controller);
      running++;
      inflight.set(id, { model, key, at: Date.now() });
      clearTimeout(hedgeTimer);
      hedgeTimer = setTimeout(launchNextModel, HEDGE_DELAY_MS);

      const signal = AbortSignal.any([controller.signal, deadline, AbortSignal.timeout(ATTEMPT_TIMEOUT_MS)]);
      tryModel(model, key, body, signal).then((r) => {
        running--;
        inflight.delete(id);
        if (done) return;
        attempts.push(r.attempt);
        if (r.data) finish({ model, key, data: r.data, attempts });
        else if (r.fatal) fail();
        else if (r.next === "key" && keyIndex + 1 < entry.keys.length) launch(entry, keyIndex + 1);
        else launchNextModel(); // 오류로 끝났으면 기다리지 말고 바로 다음으로
      });
    };

    deadline.addEventListener("abort", fail);
    launchNextModel();
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
