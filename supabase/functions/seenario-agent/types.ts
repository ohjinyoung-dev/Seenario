// 앱 ↔ 에이전트 약속(CLAUDE.md)의 요청/응답 형식.

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

export type InputType = "speech" | "describe" | "sound";
export type SoundModeOrOff = "off" | "ambient" | "music";
export type HistoryItem = { role: "user" | "agent"; text: string };

export type AgentRequest = {
  input: string;
  input_type: InputType;
  /** base64 JPEG. 앱이 아직 보내지 않을 수 있다. */
  image?: string;
  history: HistoryItem[];
  sound_mode: SoundModeOrOff;
};

export type AgentAction = {
  type: "play_sound" | "stop_sound";
  sound_id?: string;
  title?: string;
  url?: string;
  volume?: number;
  loopable?: boolean;
};

export type AgentResponse = { speech: string; display: string; actions: AgentAction[] };

/** 요청 하나를 처리하는 데 필요한 것. */
export type AgentContext = {
  /** 사용자 토큰이 실린 클라이언트(RLS가 이 사용자 기준). */
  supabase: SupabaseClient;
  request: AgentRequest;
  /** 이 시각(Date.now() 기준)까지 응답해야 한다. */
  deadlineAt: number;
};

/** 앱에 그대로 돌려줄 오류(상태 코드 + 짧은 코드). */
export class AgentError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

/** 지금 주변에서 들리는 소리 종류. sounds.contains와 같은 목록. */
export const EXISTING_SOUNDS = ["birds", "wind", "rain", "water", "traffic", "people", "music", "insects", "fire"] as const;
const EXISTING_SET = new Set<string>(EXISTING_SOUNDS);

/** 목록에 있는 소리 종류만 남기고 중복을 없앤다. */
export function normalizeExistingSounds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const out = new Set<string>();
  for (const v of value) {
    if (typeof v === "string" && EXISTING_SET.has(v.trim().toLowerCase())) out.add(v.trim().toLowerCase());
  }
  return [...out];
}

export function remainingMs(ctx: AgentContext): number {
  return ctx.deadlineAt - Date.now();
}
