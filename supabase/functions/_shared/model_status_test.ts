// model_status.ts의 순수 함수 테스트 (DB 없이).
// 실행: deno test supabase/functions/_shared/model_status_test.ts

import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  ALL,
  KEY_ERROR_SKIP_MS,
  nextPacificMidnight,
  pacificDay,
  planAttempts,
  ruleSlot,
  SKIP_MS,
  type SkipEntry,
  skipRuleFor,
  slotId,
  type Status,
  tokensOf,
} from "./model_status.ts";

const NOW = Date.parse("2026-11-06T05:30:00Z"); // 태평양 시간 11-05 21:30 (PST, UTC-8)
const MODELS = ["lite-a", "lite-b", "flash-a", "flash-b"];
const KEYS = ["k1", "k2", "k3", "k4"];
const NO_STATUS: Status = { skips: new Map(), usage: new Map() };
const first = () => 0; // 동점이면 원래 순서(테스트용 고정 난수)

function status(skips: [string, string, SkipEntry][] = [], usage: [string, string, number][] = []): Status {
  return {
    skips: new Map(skips.map(([k, m, e]) => [slotId(k, m), e])),
    usage: new Map(usage.map(([k, m, n]) => [slotId(k, m), n])),
  };
}

// ---------------------------------------------------------------------------
// skipRuleFor
// ---------------------------------------------------------------------------

Deno.test("503 과부하면 모델 전체를 10분 건너뛴다", () => {
  const rule = skipRuleFor({ kind: "http", status: 503, body: "high demand" }, NOW);
  assertEquals(rule, { scope: "model", until: NOW + SKIP_MS, reason: "overloaded", lastStatus: "503" });
});

Deno.test("500, 504도 과부하로 본다", () => {
  for (const status of [500, 504]) {
    const rule = skipRuleFor({ kind: "http", status, body: "" }, NOW);
    assert(rule !== null && rule !== "clear");
    assertEquals([rule.scope, rule.reason], ["model", "overloaded"]);
  }
});

Deno.test("응답 없음(slow)과 시간 초과(timeout)도 모델 전체 10분", () => {
  assertEquals(skipRuleFor({ kind: "slow" }, NOW), {
    scope: "model",
    until: NOW + SKIP_MS,
    reason: "slow",
    lastStatus: "slow",
  });
  assertEquals(skipRuleFor({ kind: "timeout" }, NOW), {
    scope: "model",
    until: NOW + SKIP_MS,
    reason: "timeout",
    lastStatus: "timeout",
  });
});

Deno.test("429는 그 키×모델만 Google이 알려 준 retryDelay만큼 건너뛴다", () => {
  const body = '{"error":{"code":429,"details":[{"retryDelay": "12.3s"}]}}';
  const rule = skipRuleFor({ kind: "http", status: 429, body }, NOW);
  assertEquals(rule, { scope: "pair", until: NOW + 12_300, reason: "quota", lastStatus: "429" });
});

Deno.test("429인데 retryDelay가 없으면 60초", () => {
  const rule = skipRuleFor({ kind: "http", status: 429, body: "quota" }, NOW);
  assert(rule !== null && rule !== "clear");
  assertEquals(rule.until, NOW + 60_000);
});

Deno.test("하루 한도 429는 다음 태평양 자정까지 건너뛴다", () => {
  const body = '{"error":{"message":"GenerateRequestsPerDayPerProjectPerModel exceeded"}}';
  const rule = skipRuleFor({ kind: "http", status: 429, body }, NOW);
  assert(rule !== null && rule !== "clear");
  assertEquals(rule.scope, "pair");
  assertEquals(new Date(rule.until).toISOString(), "2026-11-06T08:00:00.000Z"); // 11-06 00:00 PST
});

Deno.test("401, 403, 잘못된 키 400은 그 키 전체를 1시간 건너뛴다", () => {
  const cases = [
    { status: 401, body: "" },
    { status: 403, body: "PERMISSION_DENIED" },
    { status: 400, body: '{"error":{"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}' },
  ];
  for (const c of cases) {
    const rule = skipRuleFor({ kind: "http", ...c }, NOW);
    assertEquals(rule, { scope: "key", until: NOW + KEY_ERROR_SKIP_MS, reason: "key_error", lastStatus: String(c.status) });
  }
});

Deno.test("정상 응답이면 기록을 지운다(clear)", () => {
  assertEquals(skipRuleFor({ kind: "ok" }, NOW), "clear");
});

Deno.test("키와 상관없는 400, 404는 기록하지 않는다", () => {
  assertEquals(skipRuleFor({ kind: "http", status: 400, body: "bad" }, NOW), null);
  assertEquals(skipRuleFor({ kind: "http", status: 404, body: "not found" }, NOW), null);
});

Deno.test("규칙 범위에 따라 기록할 줄이 정해진다", () => {
  const base = { until: NOW, reason: "quota" as const, lastStatus: "" };
  assertEquals(ruleSlot({ ...base, scope: "model" }, "k1", "lite-a"), { keyName: ALL, model: "lite-a" });
  assertEquals(ruleSlot({ ...base, scope: "pair" }, "k1", "lite-a"), { keyName: "k1", model: "lite-a" });
  assertEquals(ruleSlot({ ...base, scope: "key" }, "k1", "lite-a"), { keyName: "k1", model: ALL });
});

// ---------------------------------------------------------------------------
// 날짜
// ---------------------------------------------------------------------------

Deno.test("태평양 시간 날짜", () => {
  assertEquals(pacificDay(NOW), "2026-11-05");
  assertEquals(pacificDay(Date.parse("2026-11-06T08:00:00Z")), "2026-11-06");
});

Deno.test("태평양 자정은 서머타임(PDT)에도 맞는다", () => {
  const summer = Date.parse("2026-07-01T20:15:42.500Z"); // 07-01 13:15 PDT (UTC-7)
  assertEquals(new Date(nextPacificMidnight(summer)).toISOString(), "2026-07-02T07:00:00.000Z");
});

Deno.test("서머타임이 끝나는 날에도 다음 자정을 맞게 찾는다", () => {
  const dstEnd = Date.parse("2026-11-01T12:00:00Z"); // 11-01 04:00 PST (이날 02:00에 PDT→PST)
  assertEquals(new Date(nextPacificMidnight(dstEnd)).toISOString(), "2026-11-02T08:00:00.000Z");
});

// ---------------------------------------------------------------------------
// planAttempts
// ---------------------------------------------------------------------------

Deno.test("기록이 없으면 모델 순서대로, 키는 원래 순서(동점)", () => {
  const { plan, skipped } = planAttempts(MODELS, KEYS, NO_STATUS, NOW, undefined, first);
  assertEquals(plan, MODELS.map((model) => ({ model, keys: KEYS })));
  assertEquals(skipped, []);
});

Deno.test("모델마다 오늘 덜 쓴 키부터 쓴다", () => {
  const s = status([], [["k1", "lite-a", 30], ["k2", "lite-a", 5], ["k3", "lite-a", 12], ["k4", "lite-b", 9]]);
  const { plan } = planAttempts(MODELS, KEYS, s, NOW, undefined, first);
  assertEquals(plan[0], { model: "lite-a", keys: ["k4", "k2", "k3", "k1"] });
  assertEquals(plan[1], { model: "lite-b", keys: ["k1", "k2", "k3", "k4"] });
});

Deno.test("사용량이 같으면 무작위로 섞는다", () => {
  const rolls = [0.9, 0.1, 0.5, 0.3];
  let i = 0;
  const { plan } = planAttempts(["lite-a"], KEYS, NO_STATUS, NOW, undefined, () => rolls[i++]);
  assertEquals(plan[0].keys, ["k2", "k4", "k3", "k1"]);
});

Deno.test("한도 초과한 키×모델은 그 모델에서만 빠진다", () => {
  const s = status([["k1", "lite-a", { until: NOW + 60_000, reason: "quota" }]]);
  const { plan, skipped } = planAttempts(MODELS, KEYS, s, NOW, undefined, first);
  assertEquals(plan[0], { model: "lite-a", keys: ["k2", "k3", "k4"] });
  assertEquals(plan[1], { model: "lite-b", keys: KEYS });
  assertEquals(skipped, [{ key: "k1", model: "lite-a", reason: "quota", leftSec: 60 }]);
});

Deno.test("과부하 모델(모든 키)은 통째로 빠진다", () => {
  const s = status([[ALL, "lite-a", { until: NOW + 60_000, reason: "overloaded" }]]);
  const { plan, skipped } = planAttempts(MODELS, KEYS, s, NOW, undefined, first);
  assertEquals(plan.map((p) => p.model), ["lite-b", "flash-a", "flash-b"]);
  assertEquals(skipped.length, 4);
});

Deno.test("키 오류인 키는 모든 모델에서 빠진다", () => {
  const s = status([["k3", ALL, { until: NOW + 60_000, reason: "key_error" }]]);
  const { plan } = planAttempts(MODELS, KEYS, s, NOW, undefined, first);
  for (const p of plan) assertEquals(p.keys, ["k1", "k2", "k4"]);
});

Deno.test("기간이 지난 기록은 무시한다", () => {
  const s = status([[ALL, "lite-a", { until: NOW - 1, reason: "slow" }], ["k1", ALL, { until: NOW, reason: "key_error" }]]);
  assertEquals(planAttempts(MODELS, KEYS, s, NOW, undefined, first).plan, MODELS.map((model) => ({ model, keys: KEYS })));
});

Deno.test("선호 모델을 맨 앞에 둔다(건너뛰는 중이 아니면)", () => {
  const order = (s: Status) => planAttempts(MODELS, KEYS, s, NOW, "flash-a", first).plan.map((p) => p.model);
  assertEquals(order(NO_STATUS), ["flash-a", "lite-a", "lite-b", "flash-b"]);
  assertEquals(order(status([[ALL, "flash-a", { until: NOW + 1000, reason: "overloaded" }]])), [
    "lite-a",
    "lite-b",
    "flash-b",
  ]);
});

Deno.test("모두 건너뛰는 중이면 가장 먼저 풀리는 키×모델 하나만 부른다", () => {
  const skips: [string, string, SkipEntry][] = MODELS.map((m) => [ALL, m, { until: NOW + 600_000, reason: "overloaded" }]);
  skips.push(["k2", "flash-a", { until: NOW + 5_000, reason: "quota" }]);
  // flash-a의 k2는 모델 전체 기록(10분)이 더 늦게 풀리므로, 가장 먼저 풀리는 건 10분짜리 중 첫 번째다.
  const { plan } = planAttempts(MODELS, KEYS, status(skips), NOW, undefined, first);
  assertEquals(plan, [{ model: "lite-a", keys: ["k1"] }]);

  const quotaOnly: [string, string, SkipEntry][] = [];
  for (const m of MODELS) {
    for (const k of KEYS) quotaOnly.push([k, m, { until: NOW + (k === "k3" && m === "flash-b" ? 5_000 : 60_000), reason: "quota" }]);
  }
  assertEquals(planAttempts(MODELS, KEYS, status(quotaOnly), NOW, undefined, first).plan, [{ model: "flash-b", keys: ["k3"] }]);
});

Deno.test("키가 하나도 없으면 계획도 없다", () => {
  assertEquals(planAttempts(MODELS, [], NO_STATUS, NOW).plan, []);
});

// ---------------------------------------------------------------------------
// tokensOf
// ---------------------------------------------------------------------------

Deno.test("토큰 수: 입력, 출력(생각 포함)", () => {
  const data = { usageMetadata: { promptTokenCount: 1200, candidatesTokenCount: 80, thoughtsTokenCount: 40 } };
  assertEquals(tokensOf(data), { prompt: 1200, output: 120 });
  assertEquals(tokensOf({}), { prompt: 0, output: 0 });
});
