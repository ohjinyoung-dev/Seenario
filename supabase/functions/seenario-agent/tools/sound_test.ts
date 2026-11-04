// DB 없이 도는 단위 테스트: deno test supabase/functions/seenario-agent/tools/sound_test.ts

import { assertEquals } from "jsr:@std/assert@1";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { chooseSound, pickSound, type SoundCandidate } from "./sound.ts";

const forestEvening: SoundCandidate = {
  id: "forest",
  title: "저녁 숲",
  file_path: "ambient/forest-evening.mp3",
  loopable: true,
  tags: ["forest", "evening", "calm"],
  contains: ["birds", "wind"],
};

const oceanWaves: SoundCandidate = {
  id: "ocean",
  title: "파도 소리",
  file_path: "ambient/ocean-waves.mp3",
  loopable: true,
  tags: ["beach", "calm", "summer"],
  contains: ["water"],
};

const satie: SoundCandidate = {
  id: "satie",
  title: "짐노페디 1번 (사티)",
  file_path: "music/satie-gymnopedie-1.mp3",
  loopable: false,
  tags: ["rain", "calm", "night"],
  contains: ["music"],
};

/** 무작위 값을 고정해 결과가 늘 같게 한다. */
const first = () => 0;

Deno.test("mode가 off면 null (DB를 건드리지 않음)", async () => {
  // DB에 접근하면 바로 터지는 가짜 클라이언트
  const noDb = new Proxy({}, {
    get() {
      throw new Error("DB에 접근하면 안 됨");
    },
  }) as unknown as SupabaseClient;
  assertEquals(await pickSound(noDb, { mode: "off", tags: ["forest", "calm"], existing_sounds: [] }), null);
  assertEquals(chooseSound([forestEvening], { mode: "off", tags: ["forest", "calm"], existing_sounds: [] }, first), null);
});

Deno.test("새소리가 들리면(existing_sounds: birds) 숲 새소리 음원은 빠진다", () => {
  const input = { mode: "ambient" as const, tags: ["forest", "evening", "calm"], existing_sounds: ["birds"] };
  // 숲 음원이 점수는 가장 높지만 birds가 겹쳐서 빠지고, 파도 소리(calm)가 골라진다.
  assertEquals(chooseSound([forestEvening, oceanWaves], input, first)?.id, "ocean");
  // 숲 음원만 있으면 아무것도 고르지 않는다.
  assertEquals(chooseSound([forestEvening], input, first), null);
});

Deno.test("빗소리가 들려도(existing_sounds: rain) 사티(music)는 남는다", () => {
  const input = { mode: "music" as const, tags: ["rain", "calm"], existing_sounds: ["rain"] };
  assertEquals(chooseSound([satie], input, first)?.id, "satie");
});

Deno.test("태그가 많은 음원이 항상 이기지는 않는다(비율 점수)", () => {
  const manyTags: SoundCandidate = {
    id: "many",
    title: "태그 많은 곡",
    file_path: "music/many.mp3",
    loopable: true,
    // 겹치는 태그 3개 / 전체 10개 = 0.3
    tags: ["rain", "calm", "night", "city", "street", "busy", "lively", "winter", "cozy", "romantic"],
    contains: ["music"],
  };
  const fewTags: SoundCandidate = {
    id: "few",
    title: "태그 적은 곡",
    file_path: "music/few.mp3",
    loopable: true,
    // 겹치는 태그 2개 / 전체 2개 = 1.0
    tags: ["rain", "calm"],
    contains: ["music"],
  };
  const input = { mode: "music" as const, tags: ["rain", "calm", "night"], existing_sounds: [] };
  // 겹친 개수로만 보면 manyTags(3개)가 이기지만, 비율로는 fewTags가 이긴다. 순서와 무작위 값에 상관없이 같아야 한다.
  assertEquals(chooseSound([manyTags, fewTags], input, first)?.id, "few");
  assertEquals(chooseSound([fewTags, manyTags], input, () => 0.99)?.id, "few");
});

Deno.test("겹치는 태그가 없으면 null", () => {
  const input = { mode: "ambient" as const, tags: ["city", "busy"], existing_sounds: [] };
  assertEquals(chooseSound([forestEvening, oceanWaves], input, first), null);
});
