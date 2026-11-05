// model_status.ts의 순수 함수 테스트 (DB 없이).
// 실행: deno test supabase/functions/_shared/model_status_test.ts

import { assert, assertEquals } from "jsr:@std/assert@1";
import { nextPacificMidnight, orderModels, SKIP_MS, type SkipEntry, skipRuleFor } from "./model_status.ts";

const NOW = Date.parse("2026-11-06T05:30:00Z"); // 태평양 시간 11-05 21:30 (PST, UTC-8)
const MODELS = ["lite-a", "lite-b", "flash-a", "flash-b"];

Deno.test("503 과부하면 10분 건너뛴다", () => {
  const rule = skipRuleFor({ kind: "http", status: 503, body: "high demand" }, NOW);
  assertEquals(rule, { until: NOW + SKIP_MS, reason: "overloaded", lastStatus: "503" });
});

Deno.test("500, 504도 과부하로 본다", () => {
  for (const status of [500, 504]) {
    const rule = skipRuleFor({ kind: "http", status, body: "" }, NOW);
    assert(rule !== null && rule !== "clear");
    assertEquals(rule.reason, "overloaded");
  }
});

Deno.test("응답 없음(slow)과 시간 초과(timeout)도 10분 건너뛴다", () => {
  assertEquals(skipRuleFor({ kind: "slow" }, NOW), { until: NOW + SKIP_MS, reason: "slow", lastStatus: "slow" });
  assertEquals(skipRuleFor({ kind: "timeout" }, NOW), { until: NOW + SKIP_MS, reason: "timeout", lastStatus: "timeout" });
});

Deno.test("429는 Google이 알려 준 retryDelay만큼 건너뛴다", () => {
  const body = '{"error":{"code":429,"details":[{"retryDelay": "12.3s"}]}}';
  const rule = skipRuleFor({ kind: "http", status: 429, body }, NOW);
  assertEquals(rule, { until: NOW + 12_300, reason: "quota", lastStatus: "429" });
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
  assertEquals(new Date(rule.until).toISOString(), "2026-11-06T08:00:00.000Z"); // 11-06 00:00 PST
});

Deno.test("태평양 자정은 서머타임(PDT)에도 맞는다", () => {
  const summer = Date.parse("2026-07-01T20:15:42.500Z"); // 07-01 13:15 PDT (UTC-7)
  assertEquals(new Date(nextPacificMidnight(summer)).toISOString(), "2026-07-02T07:00:00.000Z");
});

Deno.test("서머타임이 끝나는 날에도 다음 자정을 맞게 찾는다", () => {
  const dstEnd = Date.parse("2026-11-01T12:00:00Z"); // 11-01 04:00 PST (이날 02:00에 PDT→PST)
  assertEquals(new Date(nextPacificMidnight(dstEnd)).toISOString(), "2026-11-02T08:00:00.000Z");
});

Deno.test("정상 응답이면 기록을 지운다(clear)", () => {
  assertEquals(skipRuleFor({ kind: "ok" }, NOW), "clear");
});

Deno.test("400, 404는 모델 상태가 아니라서 기록하지 않는다", () => {
  assertEquals(skipRuleFor({ kind: "http", status: 400, body: "bad" }, NOW), null);
  assertEquals(skipRuleFor({ kind: "http", status: 404, body: "not found" }, NOW), null);
});

Deno.test("건너뛰는 중인 모델은 순서에서 빠진다", () => {
  const skips = new Map<string, SkipEntry>([["lite-a", { until: NOW + 60_000, reason: "overloaded" }]]);
  const { order, skipped } = orderModels(MODELS, skips, NOW);
  assertEquals(order, ["lite-b", "flash-a", "flash-b"]);
  assertEquals(skipped, [{ model: "lite-a", reason: "overloaded", leftSec: 60 }]);
});

Deno.test("기간이 지난 기록은 무시한다", () => {
  const skips = new Map<string, SkipEntry>([["lite-a", { until: NOW - 1, reason: "slow" }]]);
  assertEquals(orderModels(MODELS, skips, NOW).order, MODELS);
});

Deno.test("선호 모델을 맨 앞에 둔다(건너뛰는 중이 아니면)", () => {
  assertEquals(orderModels(MODELS, new Map(), NOW, "flash-a").order, ["flash-a", "lite-a", "lite-b", "flash-b"]);
  const skips = new Map<string, SkipEntry>([["flash-a", { until: NOW + 1000, reason: "quota" }]]);
  assertEquals(orderModels(MODELS, skips, NOW, "flash-a").order, ["lite-a", "lite-b", "flash-b"]);
});

Deno.test("모두 건너뛰는 중이면 가장 먼저 풀리는 모델 하나만 부른다", () => {
  const skips = new Map<string, SkipEntry>(
    MODELS.map((m, i) => [m, { until: NOW + (i === 2 ? 5_000 : 600_000), reason: "overloaded" }]),
  );
  assertEquals(orderModels(MODELS, skips, NOW).order, ["flash-a"]);
});
