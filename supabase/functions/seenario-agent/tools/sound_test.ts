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

// 실제 배경음 기록(schema.sql)과 같은 태그
const vivaldiAutumn: SoundCandidate = {
  id: "vivaldi-autumn",
  title: "비발디 - 사계 가을 3악장",
  file_path: "music/vivaldi-autumn-allegro.mp3",
  loopable: true,
  tags: ["autumn", "park", "forest", "outdoor", "daytime", "lively"],
  contains: ["music"],
};

const vivaldiWinter: SoundCandidate = {
  id: "vivaldi-winter",
  title: "비발디 - 사계 겨울 2악장",
  file_path: "music/vivaldi-winter-largo.mp3",
  loopable: true,
  tags: ["winter", "snow", "indoor", "home", "cozy", "calm", "evening"],
  contains: ["music"],
};

const schumann: SoundCandidate = {
  id: "schumann",
  title: "슈만 - 트로이메라이",
  file_path: "music/schumann-traumerei.mp3",
  loopable: true,
  tags: ["home", "indoor", "evening", "night", "winter", "calm", "cozy", "nostalgic"],
  contains: ["music"],
};

const grieg: SoundCandidate = {
  id: "grieg",
  title: "그리그 - 아침 기분",
  file_path: "music/grieg-morning-mood.mp3",
  loopable: true,
  tags: ["morning", "park", "forest", "outdoor", "sunny", "spring", "refreshing", "bright"],
  contains: ["music"],
};

Deno.test("실내·낮·차분·가을 장면에서는 비발디 가을(outdoor)이 뽑히지 않는다", () => {
  const input = { mode: "music" as const, tags: ["indoor", "daytime", "calm", "autumn"], existing_sounds: [] };
  // 점수만 보면 비발디 가을(2/√6 ≈ 0.82)이 비발디 겨울(2/√7 ≈ 0.76)보다 높지만, outdoor라서 빠진다.
  assertEquals(chooseSound([vivaldiAutumn, vivaldiWinter], input, first)?.id, "vivaldi-winter");
  assertEquals(chooseSound([vivaldiWinter, vivaldiAutumn], input, () => 0.99)?.id, "vivaldi-winter");
  // 비발디 가을만 있으면 아무것도 고르지 않는다.
  assertEquals(chooseSound([vivaldiAutumn], input, first), null);
});

Deno.test("야외 장면에서는 실내 곡이 뽑히지 않는다", () => {
  const input = { mode: "music" as const, tags: ["outdoor", "evening", "calm", "cozy"], existing_sounds: [] };
  // 점수만 보면 슈만(3/√8 ≈ 1.06), 비발디 겨울(3/√7 ≈ 1.13)이 그리그(1/√8 ≈ 0.35)보다 높지만, indoor라서 빠진다.
  assertEquals(chooseSound([schumann, vivaldiWinter, grieg], input, first)?.id, "grieg");
  assertEquals(chooseSound([grieg, schumann, vivaldiWinter], input, () => 0.99)?.id, "grieg");
  // 실내 곡만 있으면 아무것도 고르지 않는다.
  assertEquals(chooseSound([schumann, vivaldiWinter], input, first), null);
});

Deno.test("장면에 indoor와 outdoor가 둘 다 있으면 장소로 빼지 않고 점수만으로 고른다", () => {
  // 테라스처럼 애매한 장면. 두 곡 모두 후보에 남는다.
  const input = { mode: "music" as const, tags: ["indoor", "outdoor", "evening", "calm", "cozy"], existing_sounds: [] };
  // 점수: 비발디 겨울(indoor, evening, calm, cozy = 4/√7 ≈ 1.51) > 그리그(outdoor = 1/√8 ≈ 0.35)
  assertEquals(chooseSound([grieg, vivaldiWinter], input, first)?.id, "vivaldi-winter");
  assertEquals(chooseSound([vivaldiWinter, grieg], input, () => 0.99)?.id, "vivaldi-winter");
  // outdoor 곡만 있어도 빠지지 않고 골라진다.
  assertEquals(chooseSound([grieg], input, first)?.id, "grieg");
  // indoor 곡만 있어도 빠지지 않고 골라진다.
  assertEquals(chooseSound([schumann], input, first)?.id, "schumann");
});

Deno.test("점수는 겹친 태그 수 ÷ √(음원 태그 수)로 완만하게 매긴다", () => {
  // 1개 겹침 / 태그 1개 = 1.0, 3개 겹침 / 태그 4개 = 3/2 = 1.5 → 태그가 더 많아도 더 많이 겹친 쪽이 이긴다.
  // (예전 방식 겹친 수 ÷ 태그 수였다면 1.0 대 0.75로 태그 1개짜리가 이겼다.)
  const single: SoundCandidate = {
    id: "single",
    title: "태그 1개",
    file_path: "music/single.mp3",
    loopable: true,
    tags: ["calm"],
    contains: ["music"],
  };
  const four: SoundCandidate = {
    id: "four",
    title: "태그 4개",
    file_path: "music/four.mp3",
    loopable: true,
    tags: ["calm", "night", "rain", "city"],
    contains: ["music"],
  };
  const input = { mode: "music" as const, tags: ["calm", "night", "rain"], existing_sounds: [] };
  assertEquals(chooseSound([single, four], input, first)?.id, "four");
  assertEquals(chooseSound([four, single], input, () => 0.99)?.id, "four");
});

Deno.test("겹치는 태그가 없으면 null", () => {
  const input = { mode: "ambient" as const, tags: ["city", "busy"], existing_sounds: [] };
  assertEquals(chooseSound([forestEvening, oceanWaves], input, first), null);
});
