// Gemini 키·모델 상태(건너뛰기) + 오늘 사용량 기록. gemini.ts가 쓴다.
// - 한 줄 = 키 하나 × 모델 하나. 키 이름만 기록하고 키 값은 절대 기록하지 않는다.
//   ALL('*')은 "모든 키"(key_name) 또는 "모든 모델"(model)이라는 뜻이다.
// - 건너뛰는 범위와 기간:
//   과부하(503/500/504)·응답 없음(slow)·시간 초과(timeout) → 모델 전체(모든 키) 10분. 모델 자체가 바쁜 것이라 키를 바꿔도 같다.
//   한도 초과(429) → 그 키×모델만 Google이 알려 준 시간까지(하루 한도면 태평양 자정까지).
//   키 오류(401/402 결제·크레딧/403/잘못된 키) → 그 키의 모든 모델 1시간.
// - 사용량은 태평양 시간 날짜(Google 하루 한도 기준)의 하루치만 둔다. 성공한 요청만 센다.
//   키는 모델마다 오늘 요청 수가 가장 적은 것부터 쓴다(같으면 무작위).
// - 기록은 테이블 public.gemini_model_status에 두어 모든 함수 인스턴스가 함께 본다(인스턴스 메모리는 캐시로만 쓴다).
// - 테이블은 RLS만 켜고 정책이 없어서 앱(anon/authenticated)은 접근할 수 없다. Edge Function은 service role로 접근한다.
// - DB를 읽거나 쓰지 못해도 Gemini 호출은 막지 않는다(메모리 기록으로 진행).

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

export const ALL = "*";
export const SKIP_MS = 10 * 60_000; // 과부하·응답 없음·시간 초과면 건너뛰는 시간
export const KEY_ERROR_SKIP_MS = 60 * 60_000; // 키 오류면 그 키를 건너뛰는 시간
export const SLOW_MS = 8_000; // 시작 후 이 시간이 넘도록 답이 없다가 끝나면 응답 없음으로 본다
const DEFAULT_QUOTA_SKIP_MS = 60_000; // 429인데 대기 시간을 알려 주지 않으면
const LOAD_TIMEOUT_MS = 700; // 테이블 읽기를 기다리는 최대 시간
const CACHE_MS = 10_000; // 이 시간 안에 읽었으면 다시 읽지 않는다
const TABLE = "gemini_model_status";
const OVERLOADED_STATUS = new Set([500, 503, 504]);
// Gemini는 잘못된 키에 400(API_KEY_INVALID)을 주기도 한다.
const KEY_ERROR_BODY = /API_KEY_INVALID|API key not valid|API key expired|PERMISSION_DENIED/i;

export type SkipReason = "overloaded" | "slow" | "timeout" | "quota" | "key_error";
/** model: 모델 전체(모든 키), pair: 그 키×모델, key: 그 키의 모든 모델. */
export type SkipScope = "model" | "pair" | "key";
export type SkipEntry = { until: number; reason: SkipReason };
export type SkipRule = { scope: SkipScope; until: number; reason: SkipReason; lastStatus: string };

/** 키·모델 상태. skips/usage의 키는 slotId(키 이름, 모델). */
export type Status = {
  skips: ReadonlyMap<string, SkipEntry>;
  /** 오늘(태평양 시간) 성공한 요청 수. 없으면 0. */
  usage: ReadonlyMap<string, number>;
};

/** 모델 하나와, 그 모델에 쓸 키 이름(먼저 쓸 것부터). */
export type ModelPlan = { model: string; keys: string[] };

/** 키 한 번 시도의 결과. */
export type Outcome =
  | { kind: "ok" }
  | { kind: "http"; status: number; body: string }
  | { kind: "timeout" } // 대기 시간 초과로 끝남
  | { kind: "slow" }; // 다른 이유(다른 모델 성공 등)로 취소됐는데 이미 오래 기다린 경우

export function slotId(keyName: string, model: string): string {
  return `${keyName}|${model}`;
}

// ---------------------------------------------------------------------------
// 순수 함수 (테스트 대상)
// ---------------------------------------------------------------------------

const PACIFIC = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/Los_Angeles",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
});

function pacificParts(ms: number): Record<string, string> {
  return Object.fromEntries(PACIFIC.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
}

/** 태평양 시간 날짜(YYYY-MM-DD). Google 하루 한도가 이 날짜 기준으로 초기화된다. */
export function pacificDay(now: number): string {
  const p = pacificParts(now);
  return `${p.year}-${p.month}-${p.day}`;
}

/** 다음 미국 태평양 시간 자정(Google 하루 한도가 초기화되는 시각). 서머타임 전환일도 맞춘다. */
export function nextPacificMidnight(now: number): number {
  const p = pacificParts(now);
  const [h, m, s] = [Number(p.hour), Number(p.minute), Number(p.second)];
  let candidate = now - (now % 1000) - (h * 3600 + m * 60 + s) * 1000 + 86_400_000;
  const after = Number(pacificParts(candidate).hour); // 서머타임 전환일이면 23시나 1시가 나온다
  if (after === 23) candidate += 3_600_000;
  else if (after === 1) candidate -= 3_600_000;
  return candidate;
}

/** 429 응답 본문으로 건너뛸 시각을 정한다. 하루 한도면 태평양 자정까지, 아니면 retryDelay만큼. */
function quotaUntil(body: string, now: number): number {
  if (/PerDay/i.test(body)) return nextPacificMidnight(now);
  const delay = body.match(/"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"/);
  return now + (delay ? Math.ceil(Number(delay[1]) * 1000) : DEFAULT_QUOTA_SKIP_MS);
}

/**
 * 시도 결과를 건너뛰기 규칙으로 바꾼다.
 * 건너뛸 결과면 SkipRule, 정상 응답이면 "clear"(기록 지우기), 상태와 상관없는 결과(지원하지 않는 옵션 400, 404 등)면 null.
 */
export function skipRuleFor(outcome: Outcome, now: number): SkipRule | "clear" | null {
  switch (outcome.kind) {
    case "ok":
      return "clear";
    case "timeout":
      return { scope: "model", until: now + SKIP_MS, reason: "timeout", lastStatus: "timeout" };
    case "slow":
      return { scope: "model", until: now + SKIP_MS, reason: "slow", lastStatus: "slow" };
    case "http": {
      const { status, body } = outcome;
      if (status === 429) {
        return { scope: "pair", until: quotaUntil(body, now), reason: "quota", lastStatus: "429" };
      }
      if (status === 401 || status === 402 || status === 403 || (status === 400 && KEY_ERROR_BODY.test(body))) {
        return { scope: "key", until: now + KEY_ERROR_SKIP_MS, reason: "key_error", lastStatus: String(status) };
      }
      if (OVERLOADED_STATUS.has(status)) {
        return { scope: "model", until: now + SKIP_MS, reason: "overloaded", lastStatus: String(status) };
      }
      return null;
    }
  }
}

/** 규칙이 기록될 줄(키 이름, 모델). */
export function ruleSlot(rule: SkipRule, keyName: string, model: string): { keyName: string; model: string } {
  switch (rule.scope) {
    case "model":
      return { keyName: ALL, model };
    case "pair":
      return { keyName, model };
    case "key":
      return { keyName, model: ALL };
  }
}

/** 이 키×모델이 언제까지 건너뛰는 중인지(모델 전체·키 전체 기록도 함께 본다). 건너뛰지 않으면 null. */
function skipOf(status: Status, keyName: string, model: string, now: number): SkipEntry | null {
  let worst: SkipEntry | null = null;
  for (const id of [slotId(keyName, model), slotId(ALL, model), slotId(keyName, ALL)]) {
    const e = status.skips.get(id);
    if (e && e.until > now && (!worst || e.until > worst.until)) worst = e;
  }
  return worst;
}

/**
 * 부를 순서. 모델은 [preferModel]을 맨 앞에 두고 원래 순서대로, 키는 모델마다 오늘 덜 쓴 것부터(같으면 무작위).
 * 건너뛰는 중인 키×모델은 빼고, 쓸 키가 없는 모델도 뺀다.
 * 모두 건너뛰는 중이면 가장 먼저 풀리는 키×모델 하나만 부른다.
 */
export function planAttempts(
  models: readonly string[],
  keyNames: readonly string[],
  status: Status,
  now: number,
  preferModel?: string,
  random: () => number = Math.random,
): { plan: ModelPlan[]; skipped: { key: string; model: string; reason: SkipReason; leftSec: number }[] } {
  const ordered = preferModel && models.includes(preferModel)
    ? [preferModel, ...models.filter((m) => m !== preferModel)]
    : [...models];
  const plan: ModelPlan[] = [];
  const skipped: { key: string; model: string; reason: SkipReason; leftSec: number }[] = [];
  let soonest: { key: string; model: string; until: number } | null = null;

  for (const model of ordered) {
    const ready: { key: string; used: number; tie: number }[] = [];
    for (const key of keyNames) {
      const skip = skipOf(status, key, model, now);
      if (skip) {
        skipped.push({ key, model, reason: skip.reason, leftSec: Math.round((skip.until - now) / 1000) });
        if (!soonest || skip.until < soonest.until) soonest = { key, model, until: skip.until };
      } else {
        ready.push({ key, used: status.usage.get(slotId(key, model)) ?? 0, tie: random() });
      }
    }
    if (ready.length === 0) continue;
    ready.sort((a, b) => a.used - b.used || a.tie - b.tie);
    plan.push({ model, keys: ready.map((r) => r.key) });
  }

  if (plan.length === 0 && soonest) plan.push({ model: soonest.model, keys: [soonest.key] });
  return { plan, skipped };
}

/** 응답의 토큰 수. 생각(thoughts) 토큰은 출력 토큰에 더한다. */
export function tokensOf(data: any): { prompt: number; output: number } {
  const u = data?.usageMetadata ?? {};
  const n = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);
  return { prompt: n(u.promptTokenCount), output: n(u.candidatesTokenCount) + n(u.thoughtsTokenCount) };
}

// ---------------------------------------------------------------------------
// DB (service role) + 인스턴스 메모리 캐시
// ---------------------------------------------------------------------------

const skipCache = new Map<string, SkipEntry>();
const usageCache = new Map<string, number>();
let usageCacheDay = "";
let lastLoadedAt = 0;
let admin: SupabaseClient | null | undefined;

function adminClient(): SupabaseClient | null {
  if (admin !== undefined) return admin;
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  admin = url && key ? createClient(url, key, { auth: { persistSession: false } }) : null;
  if (!admin) console.error("model status: service role env missing, using memory only");
  return admin;
}

/** 응답을 보낸 뒤에도 끝까지 실행되게 한다(Supabase Edge Runtime). 없으면 기다리지 않고 흘려보낸다. */
function background(task: Promise<unknown>): void {
  const runtime = (globalThis as { EdgeRuntime?: { waitUntil(p: Promise<unknown>): void } }).EdgeRuntime;
  if (runtime) runtime.waitUntil(task);
}

/** 날짜가 바뀌었으면 메모리의 오늘 사용량을 비운다. */
function rollUsageDay(today: string): void {
  if (usageCacheDay === today) return;
  usageCacheDay = today;
  usageCache.clear();
}

/** 지금 키·모델 상태. 테이블을 최대 700ms만 기다리고, 실패하면 메모리 기록으로 진행한다. */
export async function loadStatus(): Promise<Status> {
  const now = Date.now();
  const today = pacificDay(now);
  rollUsageDay(today);
  const status: Status = { skips: skipCache, usage: usageCache };
  if (now - lastLoadedAt < CACHE_MS) return status;
  lastLoadedAt = now; // 실패해도 매 요청마다 다시 시도하지 않게 먼저 기록한다
  const client = adminClient();
  if (!client) return status;
  try {
    const { data, error } = await client
      .from(TABLE)
      .select("key_name, model, skip_until, reason, usage_day, requests")
      .abortSignal(AbortSignal.timeout(LOAD_TIMEOUT_MS));
    if (error) throw new Error(error.message);

    const skips = new Map<string, SkipEntry>();
    const usage = new Map<string, number>();
    for (const row of data ?? []) {
      const id = slotId(row.key_name, row.model);
      const until = row.skip_until ? Date.parse(row.skip_until) : 0;
      if (until > now && row.reason) skips.set(id, { until, reason: row.reason as SkipReason });
      if (row.usage_day === today && row.requests > 0) usage.set(id, row.requests);
    }
    // 이 인스턴스가 방금 쓴(아직 DB에 안 들어갔을 수 있는) 기록은 남긴다.
    for (const [id, entry] of skipCache) {
      if (entry.until > now && !skips.has(id)) skips.set(id, entry);
    }
    for (const [id, count] of usageCache) {
      if (count > (usage.get(id) ?? 0)) usage.set(id, count);
    }
    skipCache.clear();
    for (const [id, entry] of skips) skipCache.set(id, entry);
    usageCache.clear();
    for (const [id, count] of usage) usageCache.set(id, count);
  } catch (e) {
    console.error("model status load failed", e instanceof Error ? e.message : String(e));
  }
  return status;
}

/** 시도 결과를 기록한다. 메모리는 바로, 테이블은 백그라운드로. 성공이면 오늘 사용량도 더한다. */
export function recordOutcome(keyName: string, model: string, outcome: Outcome, ms: number, data?: any): void {
  const now = Date.now();
  const rule = skipRuleFor(outcome, now);
  if (rule === null) return;
  const client = adminClient();

  if (rule === "clear") {
    rollUsageDay(pacificDay(now));
    const id = slotId(keyName, model);
    usageCache.set(id, (usageCache.get(id) ?? 0) + 1);
    skipCache.delete(id);
    skipCache.delete(slotId(ALL, model));
    if (!client) return;
    const tokens = tokensOf(data);
    background(
      Promise.resolve(
        client.rpc("gemini_record_usage", {
          p_key_name: keyName,
          p_model: model,
          p_usage_day: pacificDay(now),
          p_prompt_tokens: tokens.prompt,
          p_output_tokens: tokens.output,
          p_ms: Math.round(ms),
        }),
      ).then(({ error }) => {
        if (error) console.error("model usage save failed", keyName, model, error.message);
      }),
    );
    return;
  }

  const slot = ruleSlot(rule, keyName, model);
  skipCache.set(slotId(slot.keyName, slot.model), { until: rule.until, reason: rule.reason });
  console.log(
    "model status skip",
    `key=${slot.keyName}`,
    `model=${slot.model}`,
    rule.reason,
    `${Math.round((rule.until - now) / 1000)}s`,
  );
  if (!client) return;
  background(
    Promise.resolve(
      client.from(TABLE).upsert({
        key_name: slot.keyName,
        model: slot.model,
        skip_until: new Date(rule.until).toISOString(),
        reason: rule.reason,
        last_status: rule.lastStatus,
        last_ms: Math.round(ms),
        updated_at: new Date(now).toISOString(),
      }, { onConflict: "key_name,model" }),
    ).then(({ error }) => {
      if (error) console.error("model status save failed", slot.keyName, slot.model, error.message);
    }),
  );
}
