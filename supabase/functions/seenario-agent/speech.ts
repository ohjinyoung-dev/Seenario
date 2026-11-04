// speech: 생각 → 도구 사용 → 판단을 최대 MAX_ROUNDS번 반복한 뒤 문장으로 답한다(Gemini 함수 호출).

import { callGemini, extractText, responseParts } from "../_shared/gemini.ts";
import type { SoundMode } from "../_shared/sound_pick.ts";
import { speechInstruction, systemPrompt } from "./prompts.ts";
import { runSoundTool, SOUND_TOOL_DECLARATIONS } from "./sound_tools.ts";
import { type AgentAction, type AgentContext, AgentError, type AgentResponse, remainingMs } from "./types.ts";

/** Gemini 호출 최대 횟수. 마지막 호출은 도구 없이 반드시 문장으로 끝낸다. */
const MAX_ROUNDS = 3;
/** 남은 시간이 이보다 적으면 다음 라운드를 시작하지 않는다. */
const MIN_ROUND_MS = 1_500;

type Content = { role: "user" | "model"; parts: unknown[] };
type FunctionCall = { id?: string; name: string; args?: Record<string, unknown> };

export async function converse(ctx: AgentContext): Promise<AgentResponse> {
  const { request } = ctx;
  const hasImage = !!request.image;
  const soundOn = request.sound_mode !== "off";

  const contents: Content[] = request.history.map((h) => ({
    role: h.role === "agent" ? "model" : "user",
    parts: [{ text: h.text }],
  }));
  const userParts: unknown[] = [{ text: request.input }];
  if (hasImage) userParts.push({ inline_data: { mime_type: "image/jpeg", data: request.image } });
  contents.push({ role: "user", parts: userParts });

  const system = systemPrompt(request.sound_mode, hasImage) + "\n\n" + speechInstruction(soundOn);
  const actions: AgentAction[] = [];
  let preferModel: string | undefined;

  for (let round = 1; round <= MAX_ROUNDS; round++) {
    const left = remainingMs(ctx);
    if (left < MIN_ROUND_MS) throw new AgentError(504, "timeout");
    const last = round === MAX_ROUNDS;

    const body = {
      systemInstruction: { parts: [{ text: system }] },
      contents,
      ...(soundOn
        ? {
          tools: [{ functionDeclarations: SOUND_TOOL_DECLARATIONS }],
          // 마지막 라운드는 도구를 못 쓰게 해서 반드시 문장으로 답하게 한다.
          toolConfig: { functionCallingConfig: { mode: last ? "NONE" : "AUTO" } },
        }
        : {}),
      generationConfig: { thinkingConfig: { thinkingLevel: "minimal" } },
    };

    const started = Date.now();
    const result = await callGemini(body, { deadlineMs: left, preferModel });
    if (!result.data) throw new AgentError(502, "gemini_failed");
    preferModel = result.model;

    const parts = responseParts(result.data);
    const calls = parts
      .map((p) => p.functionCall as FunctionCall | undefined)
      .filter((c): c is FunctionCall => !!c?.name);
    console.log(
      `round ${round} model=${result.model} ms=${Date.now() - started} calls=${JSON.stringify(calls.map((c) => c.name))}`,
    );

    // 마지막 라운드에서 모델이 규칙을 어기고 도구를 불러도 더 돌지 않고 글자만 쓴다.
    if (calls.length === 0 || !soundOn || last) {
      const speech = extractText(result.data);
      if (!speech) throw new AgentError(502, "empty_response");
      return { speech, display: speech, actions };
    }

    // 모델 응답은 생각 파트(thought signature)까지 그대로 다시 보내야 다음 라운드가 이어진다.
    contents.push({ role: "model", parts });
    const responses: unknown[] = [];
    for (const call of calls) {
      const output = await runSoundTool(ctx, request.sound_mode as SoundMode, call.name, call.args ?? {}, actions);
      responses.push({
        functionResponse: { ...(call.id ? { id: call.id } : {}), name: call.name, response: output },
      });
    }
    contents.push({ role: "user", parts: responses });
  }
  // 마지막 라운드에서 항상 return하므로 여기까지 오지 않는다.
  throw new AgentError(502, "too_many_rounds");
}
