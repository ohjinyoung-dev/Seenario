// Gemini 모델 상태(건너뛰기) 기록. gemini.ts가 쓴다.
// - 과부하(503/500/504), 응답 없음(slow), 시간 초과(timeout)는 10분, 한도 초과(429)는 Google이 알려 준 시간만큼 건너뛴다.
// - 기록은 테이블 public.gemini_model_status에 두어 모든 함수 인스턴스가 함께 본다(인스턴스 메모리는 캐시로만 쓴다).
// - 테이블은 RLS만 켜고 정책이 없어서 앱(anon/authenticated)은 접근할 수 없다. Edge Function은 service role로 접근한다.
// - DB를 읽거나 쓰지 못해도 Gemini 호출은 막지 않는다(빈 기록으로 진행).

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

export const SKIP_MS = 10 * 60_000; // 과부하·응답 없음·시간 초과면 건너뛰는 시간
export const SLOW_MS = 8_000; // 시작 후 이 시간이 넘도록 답이 없다가 끝나면 응답 없음으로 본다
const DEFAULT_QUOTA_SKIP_MS = 60_000; // 429인데 대기 시간을 알려 주지 않으면
const LOAD_TIMEOUT_MS = 700; // 테이블 읽기를 기다리는 최대 시간
const CACHE_MS = 10_000; // 이 시간 안에 읽었으면 다시 읽지 않는다
const TABLE = "gemini_model_status";
const OVERLOADED_STATUS = new Set([500, 503, 504]);

export type SkipReason = "overloaded" | "slow" | "timeout" | "quota";
export type SkipEntry = { until: number; reason: SkipReason };
export type SkipRule = { until: number; reason: SkipReason; lastStatus: string };

/** 모델 한 번 시도의 결과. */
export type Outcome =
  | { kind: "ok" }
  | { kind: "http"; status: number; body: string }
  | { kind: "timeout" } // 대기 시간 초과로 끝남
  | { kind: "slow" }; // 다른 이유(다른 모델 성공 등)로 취소됐는데 이미 오래 기다린 경우

// ---------------------------------------------------------------------------
// 순수 함수 (테스트 대상)
// ---------------------------------------------------------------------------

const PACIFIC = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/Los_Angeles",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
});

function pacificClock(ms: number): { h: number; m: number; s: number } {
  const p = Object.fromEntries(PACIFIC.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { h: Number(p.hour), m: Number(p.minute), s: Number(p.second) };
}

/** 다음 미국 태평양 시간 자정(Google 하루 한도가 초기화되는 시각). 서머타임 전환일도 맞춘다. */
export function nextPacificMidnight(now: number): number {
  const { h, m, s } = pacificClock(now);
  let candidate = now - (now % 1000) - (h * 3600 + m * 60 + s) * 1000 + 86_400_000;
  const after = pacificClock(candidate).h; // 서머타임 전환일이면 23시나 1시가 나온다
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
 * 건너뛸 결과면 SkipRule, 정상 응답이면 "clear"(기록 지우기), 모델 상태와 상관없는 결과(400/404 등)면 null.
 */
export function skipRuleFor(outcome: Outcome, now: number): SkipRule | "clear" | null {
  switch (outcome.kind) {
    case "ok":
      return "clear";
    case "timeout":
      return { until: now + SKIP_MS, reason: "timeout", lastStatus: "timeout" };
    case "slow":
      return { until: now + SKIP_MS, reason: "slow", lastStatus: "slow" };
    case "http":
      if (outcome.status === 429) {
        return { until: quotaUntil(outcome.body, now), reason: "quota", lastStatus: "429" };
      }
      if (OVERLOADED_STATUS.has(outcome.status)) {
        return { until: now + SKIP_MS, reason: "overloaded", lastStatus: String(outcome.status) };
      }
      return null;
  }
}

/**
 * 부를 순서. [preferModel]을 맨 앞에 두고, 건너뛰는 중인 모델은 뺀다.
 * 모두 건너뛰는 중이면 가장 먼저 풀리는 모델 하나만 부른다.
 */
export function orderModels(
  models: readonly string[],
  skips: ReadonlyMap<string, SkipEntry>,
  now: number,
  preferModel?: string,
): { order: string[]; skipped: { model: string; reason: SkipReason; leftSec: number }[] } {
  const all = preferModel && models.includes(preferModel)
    ? [preferModel, ...models.filter((m) => m !== preferModel)]
    : [...models];
  const isSkipped = (m: string) => (skips.get(m)?.until ?? 0) > now;
  const ready = all.filter((m) => !isSkipped(m));
  const skipped = all.filter(isSkipped).map((m) => {
    const e = skips.get(m)!;
    return { model: m, reason: e.reason, leftSec: Math.round((e.until - now) / 1000) };
  });
  if (ready.length > 0) return { order: ready, skipped };
  const soonest = [...all].sort((a, b) => skips.get(a)!.until - skips.get(b)!.until)[0];
  return { order: [soonest], skipped };
}

// ---------------------------------------------------------------------------
// DB (service role) + 인스턴스 메모리 캐시
// ---------------------------------------------------------------------------

const cache = new Map<string, SkipEntry>();
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

/** 지금 건너뛰는 중인 모델. 테이블을 최대 700ms만 기다리고, 실패하면 메모리 기록으로 진행한다. */
export async function loadSkips(): Promise<ReadonlyMap<string, SkipEntry>> {
  const now = Date.now();
  if (now - lastLoadedAt < CACHE_MS) return cache;
  lastLoadedAt = now; // 실패해도 매 요청마다 다시 시도하지 않게 먼저 기록한다
  const client = adminClient();
  if (!client) return cache;
  try {
    const { data, error } = await client
      .from(TABLE)
      .select("model, skip_until, reason")
      .gt("skip_until", new Date(now).toISOString())
      .abortSignal(AbortSignal.timeout(LOAD_TIMEOUT_MS));
    if (error) throw new Error(error.message);
    const fromDb = new Map<string, SkipEntry>();
    for (const row of data ?? []) {
      fromDb.set(row.model, { until: Date.parse(row.skip_until), reason: row.reason as SkipReason });
    }
    // 이 인스턴스가 방금 쓴(아직 DB에 안 들어갔을 수 있는) 기록은 남긴다.
    for (const [model, entry] of cache) {
      if (entry.until > now && !fromDb.has(model)) fromDb.set(model, entry);
    }
    cache.clear();
    for (const [model, entry] of fromDb) cache.set(model, entry);
  } catch (e) {
    console.error("model status load failed", e instanceof Error ? e.message : String(e));
  }
  return cache;
}

/** 시도 결과를 기록한다. 메모리는 바로, 테이블은 백그라운드로. */
export function recordOutcome(model: string, outcome: Outcome, ms: number): void {
  const now = Date.now();
  const rule = skipRuleFor(outcome, now);
  if (rule === null) return;
  if (rule === "clear") {
    if (!cache.has(model)) return; // 기록이 없던 모델은 성공해도 DB에 쓰지 않는다
    cache.delete(model);
    console.log("model status clear", model);
    const client = adminClient();
    if (client) {
      background(Promise.resolve(client.from(TABLE).delete().eq("model", model)).then(({ error }) => {
        if (error) console.error("model status clear failed", model, error.message);
      }));
    }
    return;
  }
  cache.set(model, { until: rule.until, reason: rule.reason });
  console.log("model status skip", model, rule.reason, `${Math.round((rule.until - now) / 1000)}s`);
  const client = adminClient();
  if (client) {
    background(
      Promise.resolve(
        client.from(TABLE).upsert({
          model,
          skip_until: new Date(rule.until).toISOString(),
          reason: rule.reason,
          last_status: rule.lastStatus,
          last_ms: Math.round(ms),
          updated_at: new Date(now).toISOString(),
        }),
      ).then(({ error }) => {
        if (error) console.error("model status save failed", model, error.message);
      }),
    );
  }
}
