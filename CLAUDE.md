# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

- **앱 이름**: 씨나리오(Seenario, See + Scenario)
- **팀**: 버저비터(2인). AI 경진대회 출품작.
- **목적**: 시각장애인이 머무는 공간의 분위기를 감성적으로 느낄 수 있도록, 카메라에 비친 풍경을 라디오처럼 묘사해 주는 앱.
- **사용 흐름**: 빅스비로 앱 실행 → 카메라 상시 켜짐 → "지금 풍경 묘사해줘" 음성 명령(또는 화면 탭) → 사진 1장 캡처 → AI 묘사 → TTS 낭독.
- **사운드 모드**: 기본값은 `off`(실제 환경 소리와 겹치지 않도록). 사용자가 음성으로 `ambient` / `music` 모드를 선택한다. `user_settings.sound_mode`에 저장.
- **묘사 저장**: 좋았던 묘사와 상황 태그를 `memories` 테이블에 저장해 나중에 다시 듣는다.
- **백엔드**: Supabase(익명 로그인, PostgreSQL, RLS). AI 호출(Gemini 장면 분석, Claude 문장 생성)은 **Supabase Edge Function에서만** 하고, AI API 키는 절대 앱에 넣지 않는다. 앱에는 Supabase URL/anon key만 둔다.

### 전체 사용 흐름 (현재 구현)

1. 앱 실행 → 빠진 권한이 있으면 TTS로 "곧 카메라와 마이크 권한을 묻는 창이 떠요. '앱 사용 중에만 허용'을 선택해 주세요." 안내 후 카메라·마이크 권한을 한 번에 요청한다.
2. 권한이 있으면 카메라 미리보기가 화면 전체에 켜지고, 시작 안내를 TTS로 읽는다.
3. 화면 탭 / 이어폰 버튼 → 한 번 듣기(눌러서 말하기). 상태: "듣는 중". 볼륨 키는 볼륨 조절용으로 남겨 둔다.
   - 화면을 1초 이상 길게 누르면 듣지 않고 바로 묘사(`input_type: "describe"`)를 요청한다(시끄러운 곳용). TalkBack에서는 두 번 탭하고 누르고 있기.
4. 들은 말이 휴대폰 명령이면 앱이 바로 처리한다.
   - "다시": 마지막 답변을 다시 낭독. "설정 열어줘": 시스템 앱 정보 화면. "처음부터": 대화 기록 초기화.
   - "환경음 모드"·"환경음 켜" / "배경음 모드"·"배경음 켜": `sound_mode`를 `ambient` / `music`으로 바꾼다.
   - "소리 꺼", "기본 모드(로 가줘/돌아가)", "소리 없이": 기본 모드(`off`)로 바꾼다. 화면과 안내에서는 `off`를 "기본 모드"라고 부른다.
   - "기본으로", "조용히": 명령처럼 말할 때만 기본 모드로 바꾸고("조용히 해줘", "기본으로 돌아가" 등), 질문이면 에이전트로 보낸다("여기 조용히 해야 하는 곳이야?" 등).
   - "소리 켜": 지난 모드가 `ambient`/`music`이면 그 모드로, `off`이거나 없으면 환경음으로 켠다.
   - 모드를 바꾸면 TTS로 "환경음 모드로 바꿨어요"처럼 알리고 `user_settings.sound_mode`(DB)에 저장한다. 바꾼 모드(`off` 포함)는 "지난 모드"로 휴대폰 DataStore에도 저장한다. 화면 위쪽에 현재 소리 모드를 표시한다.
   - 앱을 켜면 항상 기본 모드로 시작하고 DB `sound_mode`도 `off`로 저장한다(지난 모드에는 기록하지 않음). 지난 모드가 `ambient`/`music`이면 시작 안내 끝에 "지난번에는 환경음 모드를 쓰셨어요. '환경음 켜'라고 말하면 다시 켜져요."(배경음이면 배경음)를 붙인다. 음성 인식을 못 쓰는 기기에는 붙이지 않는다.
5. 휴대폰 명령이 아니면 에이전트(`agent/`)로 보낸다. "묘사해줘"는 `describe`, 나머지 말은 `speech`. 최근 대화 6개(메시지 기준)와 현재 `sound_mode`를 같이 보낸다.
   - TTS "잠시만요, 주변을 살펴볼게요."(describe) 또는 "잠시만요, 생각해 볼게요."(speech) + 상태 "생각하는 중".
   - 지금은 `BuildConfig.USE_FAKE_AGENT`(기본 true)라서 2초 뒤 가짜 응답이 온다.
   - 최신 카메라 사진을 `LatestFrameProvider`에서 꺼내 `image`(base64 JPEG, 긴 변 768px)로 같이 보낸다. 카메라 준비 전이거나 권한이 없으면 `image` 없이 보낸다. describe에 사진이 없으면 서버가 400 `image_required`를 돌려주고, 앱은 아래 대신 묘사로 넘어간다.
   - 디버그 빌드에서는 Logcat `SEENARIO_AGENT`에 사진 포함 여부와 크기(KB)만 남긴다.
6. 응답의 `speech`는 TTS로 낭독(상태 "말하는 중"), `display`는 화면 아래쪽에 큰 글씨로 표시 → 끝나면 "대기 중". `actions`는 소리 재생기가 생길 때까지 Logcat `SEENARIO_AGENT`에만 남긴다.
   - 처리 중 다시 요청: "지금 살펴보는 중이에요" 안내 후 무시.
   - 실패: "주변을 살펴보지 못했어요. 다시 시도해 주세요." 안내. 전체 대기 시간은 20초를 넘지 않는다.
   - describe가 에러로 바로 실패하면 남은 시간 안에서 기존 `describe-scene`(`DescribeTrigger.request()` → 최신 프레임 → Gemini)으로 대신 묘사한다. 20초 시간 초과면 대신 묘사 없이 바로 실패 안내.
   - 음성 인식을 못 쓰는 기기: 탭하면 듣지 않고 바로 묘사를 요청한다.

### 원칙

- 사진은 기본적으로 저장하지 않는다. 캡처한 이미지는 묘사 요청에만 쓰고 버린다.
  - 검토 중(미확정): 사용자가 "이 순간 저장해줘"라고 명시적으로 요청할 때만 묘사와 함께 사진을 저장하는 기능. 확정 전까지는 사진 저장 코드를 넣지 않는다.
- 모든 기능은 TalkBack과 음성만으로 사용 가능해야 한다. 새 UI 요소에는 의미 있는 contentDescription/semantics를 붙이고, 시각적 피드백에만 의존하지 않는다.
- 코드는 Kotlin + Jetpack Compose.

## Tech

Single-module Android app (`:app`, package `com.buzzerbeater.seenario`) built with Jetpack Compose + Material3, backed by Supabase (anonymous auth + Postgrest). minSdk 26, compile/targetSdk 37.

## Commands

Run from the repo root (`gradlew.bat` on Windows cmd/PowerShell, `./gradlew` in Git Bash).

```
./gradlew :app:assembleDebug                 # build debug APK
./gradlew :app:installDebug                  # build + install on connected device/emulator
./gradlew :app:testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew :app:testDebugUnitTest --tests "com.buzzerbeater.seenario.ExampleUnitTest"   # single test class
./gradlew :app:connectedDebugAndroidTest     # instrumented tests (app/src/androidTest), needs a device
./gradlew :app:lintDebug                     # Android lint
```

Runtime logs use the Logcat tag `SEENARIO` (e.g. `adb logcat -s SEENARIO`).

## Build setup

- **AGP 9 with built-in Kotlin**: there is no `org.jetbrains.kotlin.android` plugin; Kotlin compiler version comes from the `kotlin` entry in `gradle/libs.versions.toml` (used by the compose and serialization plugins). All dependencies/plugins go through that version catalog.
- **supabase-kt is pinned to 3.6.0 (Ktor 3.4.3)** because supabase-kt ≥3.7 is compiled with Kotlin 2.4 and won't compile against the project's Kotlin 2.2.x. Upgrade Kotlin first before bumping supabase-kt; keep `ktor` aligned with the version supabase-kt depends on.
- **Secrets**: `SUPABASE_URL` and `SUPABASE_ANON_KEY` are read from `local.properties` (git-ignored) in `app/build.gradle.kts` and exposed as `BuildConfig` fields. A fresh clone must add both to `local.properties` or the client gets empty strings.
- Release builds use the new `optimization {}` DSL with `packageScope` limited to `androidx.**`, `kotlin.**`, `kotlinx.**`; project keep rules live in `app/src/main/keepRules/`.

## Architecture

- `data/SupabaseProvider` — lazily-created singleton `SupabaseClient` with `Auth` and `Postgrest` installed. Auth persists the session on-device, so restarts reuse it.
- `data/UserSettingsRepository` — `ensureSignedIn()` waits for session restore (`awaitInitialization`) and signs in anonymously only if no session exists; `getOrCreate()` selects the user's `user_settings` row and inserts one with only `user_id` (letting DB defaults fill the rest) if missing. Row models are `@Serializable` data classes with `@SerialName` snake_case mapping.
- `MainActivity` runs that startup flow in `lifecycleScope` on first creation, logs `sound_mode` and passes the loaded settings to `SeenarioController.onSettingsLoaded()`, creates `SeenarioController`, shows `SeenarioScreen`, and forwards key events. Nothing else should live there.
- `SeenarioScreen.kt` — integration layer. `SeenarioController` wires `voice/` to `agent/` and `camera/`: phone commands (repeat, settings, reset, sound mode), agent requests with a 20 s total budget, describe fallback to `describe-scene` via `DescribeTrigger` when the agent errors, busy/error handling, and the combined camera+mic permission request with a spoken notice. A process-level `Session` object keeps the conversation history and sound mode across Activity recreation. `SeenarioScreen` composes the full-screen camera preview, the single tap layer (push-to-talk), the top status panel (status + current sound mode), and the bottom result text (liveRegion off when TTS already read it).
- `agent/` — `AgentModels` (request/response classes for the app ↔ agent contract, `InputType`, `SoundMode`, `AgentJson`), `AgentClient` (`create()` picks `FakeAgentClient` or `SupabaseAgentClient` by `BuildConfig.USE_FAKE_AGENT`, default true, overridable in `local.properties`), `FakeAgentClient` (sample responses after 2 s), `SupabaseAgentClient` (calls Edge Function `seenario-agent`), `ConversationHistory` (last 6 messages, in memory only).
- `sound/` — `SoundModeStore` saves `user_settings.sound_mode`. `LastSoundMode` keeps the "last mode" (every user change, `off` included) in DataStore via `LastSoundModeStorage`/`DataStoreLastSoundModeStorage`. `SoundModePolicy` holds the pure rules ("소리 켜" target, startup suggestion line). No player yet; sound actions are only logged with tag `SEENARIO_AGENT`.
- `voice/` — `VoiceController` (push-to-talk, media session, TTS via `speak()`), `SpeechListener`, `TtsSpeaker`, `CommandParser` (phone commands; anything else is `UNKNOWN` and goes to the agent as speech). Commands reach other modules through `VoiceCommandListener`.
- `camera/` — `CameraStreamScreen` (preview + latest-frame capture on `DescribeTrigger` requests, no tap/text of its own), `FrameStreamAnalyzer`, `SceneDescriber` (calls Edge Function `describe-scene`; now used as the describe fallback). `LatestFrameProvider` (pass it to `CameraStreamScreen` as `latestFrames`; `latest()` returns the newest in-memory frame, or null before the camera is ready or after the camera screen is gone). `SeenarioController` owns one (`frames`) and puts the latest frame into agent requests as `image` (base64 JPEG); when it returns null, the request is sent without `image`.

## Database

`supabase/schema.sql` is the reference schema for `user_settings` and `memories` (both keyed to `auth.users`, RLS restricting rows to `auth.uid()`, `updated_at` trigger on `user_settings`), plus the shared read-only `sounds` catalog (ambient/music tracks; files live in the private Storage bucket `sounds`, rows point to them via `file_path`). In `sounds`, `tags` means "which situations it suits" (values from `SOUND_TAGS`), and `contains` means "which sounds are actually audible in the track". `contains` is `text[] not null default '{}'`, limited to `birds`, `wind`, `rain`, `water`, `traffic`, `people`, `music`, and has a GIN index `sounds_contains_idx`. The live tables were created manually in the Supabase dashboard, so this file is not applied automatically — when changing the schema, give the user incremental SQL to run rather than re-running the whole file. Anonymous sign-ins must be enabled in the Supabase project.

## 에이전트 구조 (ReAct)

- 기본 모드(묘사)와 대화 모드를 하나로 합친다. 앱은 사용자 입력과 최신 카메라 사진을 Edge Function `seenario-agent`로 보낸다. 에이전트는 생각 → 도구 사용 → 판단을 최대 3번 반복한 뒤 답한다.
- 사진 촬영과 소리 재생은 앱이 맡는다. 서버는 응답의 `actions`로 무엇을 할지 지시만 한다.
- `seenario-agent`(서버 15초 제한)는 지금 **Gemini만** 쓴다. describe는 반복 없이 사진 → Gemini 1번 → 묘사이고, 사진이 없으면 400 `image_required`라서 앱이 `describe-scene`으로 대신 묘사한다. speech는 Gemini 함수 호출로 최대 3번 반복하며, 소리 모드가 켜져 있으면 `pick_sound`/`stop_sound` 도구를 준다. `tools/sound.ts`가 오기 전까지는 `_shared/sound_pick.ts`로 고른 곡을 **로그로만** 남기고 `play_sound`는 보내지 않는다(`stop_sound`만 보냄).
- Edge Function 공용 코드는 `supabase/functions/_shared/`에 있다: `gemini.ts`(모델 시간차 호출. 후보 순서는 secret `GEMINI_MODELS`로 바꿀 수 있고, 없으면 무료 한도가 넉넉한 Flash Lite부터 부른다. 429가 난 모델은 대기 시간 동안 건너뛰고, 생각 단계를 지원하지 않는 모델은 낮춰서 다시 보낸다), `http.ts`(응답, 사용자 확인), `sound_pick.ts`(describe-scene의 음원 선택), `sound_tags.ts`.
- 기존 `describe-scene`은 대신 묘사용으로 남긴다. 현재 `describe-scene`은 `{ description, model }`을 돌려준다. `user_settings.sound_mode`가 `ambient`/`music`이면 `soundMode`, `tags`(`SOUND_TAGS` 값), `sound`도 함께 돌려준다. `sound`는 `{ id, title, mode, url(1시간 signed URL), loopable, tags }` 형태이고, 고르지 못하면 `null`이다. 앱(`SceneDescriber`)은 아직 이 값들을 로그로만 남긴다.

### 앱 ↔ 에이전트 약속

요청:

```json
{
  "input": "string",
  "input_type": "speech | describe",
  "image": "base64 JPEG",
  "history": [{ "role": "user | agent", "text": "string" }],
  "sound_mode": "off | ambient | music"
}
```

- 소리 모드는 사용자가 말로 고르며 기본값은 `off`다. 에이전트는 고른 모드 안에서만 음원을 고른다.

응답:

```json
{
  "speech": "string",
  "display": "string",
  "actions": [{
    "type": "play_sound | stop_sound",
    "sound_id": "string (선택)",
    "title": "string (선택)",
    "url": "signed URL (선택)",
    "volume": "number (선택)",
    "loopable": "boolean (선택)"
  }]
}
```

#### input_type `"sound"` (소리만 고르는 요청)

- 소리 모드를 켤 때 소리만 고르는 요청이다. 묘사 문장을 만들지 않고 Gemini를 1번만 부른다.
- 요청: `{ "input_type": "sound", "image", "sound_mode": "ambient | music", "history"(선택), "input": "" }`
- 응답: `{ "speech": "", "display": "", "actions": [play_sound] 또는 [] }`
- `sound_mode`가 `off`면 400, 사진이 없으면 400 `image_required`, 어울리는 소리가 없으면 `actions`는 빈 배열이다.
- 서버 처리(서버 담당): 사진에서 `SOUND_TAGS` 태그와 `existing_sounds`를 뽑고 `tools/sound.ts`의 `pickSound`를 부른다.
- 앱 처리(앱 담당): 모드를 켤 때 조용한 소리 요청으로 보내고, `speech`는 읽지 않는다.

### 소리 고르기 도구 (`tools/sound.ts`, 앱 담당)

- 지금 `describe-scene` 안에 있는 음원 선택 로직(`pickSound`)을 에이전트 단계에서 `supabase/functions/seenario-agent/tools/sound.ts`로 옮긴다. 이 로직은 `SOUND_TAGS` 기반으로 같은 mode의 음원 중 태그가 가장 많이 겹치는 것을 고르고, 동점이면 무작위로 고르며, 하나도 겹치지 않으면 고르지 않는다.
- 태그 목록 `SOUND_TAGS`는 현재 `supabase/functions/_shared/sound_tags.ts`에 있다. `sounds.tags`와 Gemini 태그는 이 목록의 영문 소문자 단어만 쓴다.
- `sounds.tags`는 "어울리는 상황"(`SOUND_TAGS`)이고, `sounds.contains`는 "음원에 실제로 들리는 소리"다. 고를 때 점수는 `tags`로 매기고, 제외는 `contains`로 판단한다.
- 입력: `{ "mode": "ambient" | "music", "tags": SOUND_TAGS 안의 값 배열, "existing_sounds": 이미 들리는 소리 종류 배열 }`
  - `existing_sounds` 값은 `contains`와 같은 목록을 쓴다: `birds`, `wind`, `rain`, `water`, `traffic`, `people`, `music`.
- 출력: `{ "sound_id", "title", "url"(1시간 signed URL), "loopable" }` 또는 `null`
- `contains`가 `existing_sounds`와 하나라도 겹치는 음원은 제외한다. 실제 주변 소리와 같은 소리를 겹쳐 틀지 않기 위해서다.

## 파일 담당

- **앱 담당**: `voice/`, `agent/`, `sound/`, `SeenarioScreen.kt`, `supabase/functions/seenario-agent/tools/sound.ts`, Storage 음원
- **서버/AI 담당**: `camera/`, `supabase/functions/seenario-agent/`의 나머지, `describe-scene`, `schema.sql`과 DB 변경
- **공용(수정 전 서로 알리기)**: `MainActivity.kt`, `AndroidManifest.xml`, `libs.versions.toml`, `build.gradle.kts`, `CLAUDE.md`

## 작업 범위와 확인 규칙 (Claude는 반드시 지킬 것)

- 요청받은 범위만 작업한다. 요청에 없는 기능, 명령, 모드, 화면은 삭제하거나 동작을 바꾸지 않는다.
- 요청을 두 가지 이상으로 해석할 수 있으면, 작업을 시작하기 전에 어떻게 이해했는지 설명하고 사용자에게 확인받는다.
- 아래 작업은 요청에 명확히 적혀 있지 않으면 하기 전에 반드시 물어본다.
  - 기존 기능, 명령, 모드, 파일 삭제
  - DB 테이블, 컬럼, 제약 조건 변경
  - 앱-에이전트 약속(요청/응답 형식) 변경
  - 다른 사람 담당 폴더나 공용 파일 수정
- 더 좋은 방법이 떠오르면 바로 적용하지 말고 "제안"으로 먼저 알린다.
- 작업 결과를 보고할 때 "요청과 다르게 한 부분"과 "요청에 없었지만 추가한 부분"을 따로 구분해서 적는다.
- 변경 범위가 큰 작업은 plan mode로 계획을 먼저 보여주고 승인받는다.

## Git 협업 규칙 (Claude는 반드시 지킬 것)

- **언어**: 사용자에게 하는 답변과 PR 제목/본문은 한국어로 쓴다.
- **master**: 최종 배포/경진대회 제출용. 절대 직접 커밋, 푸시, 병합하지 않는다.
- **dev**: 통합 테스트용. 직접 커밋하거나 병합하지 않고, 반드시 PR로만 합친다.
- **작업 시작 전**: `git checkout dev` → `git pull origin dev` → 새 브랜치 생성.
- **브랜치 이름**: `feat/기능명`(새 기능), `fix/내용`(버그), `chore/내용`(설정). 예: `feat/camera`, `feat/vui`, `feat/audio`
- **작업이 끝나면**: 내 브랜치를 푸시하고 dev로 가는 PR을 만든다. 병합은 팀원이 확인한 뒤 사람이 직접 한다.
- 현재 브랜치가 master나 dev인 상태에서 코드 수정을 요청받으면, 먼저 알맞은 브랜치를 만들자고 제안한다.
- **커밋 메시지 태그**: `[Feat]` 새 기능, `[Fix]` 버그 수정, `[UI]` 레이아웃/디자인, `[Refactor]` 기능 변화 없는 구조 개선, `[Chore]` 라이브러리/빌드 설정/.gitignore 등. 메시지는 한국어로 쓴다.
- **코드 겹침 방지**: 위 "파일 담당"에 따라 자기 담당 파일만 수정한다. 공용 파일(`MainActivity.kt`, `AndroidManifest.xml`, `libs.versions.toml`, `build.gradle.kts`, `CLAUDE.md`)은 수정 전에 서로 알리고, 꼭 필요한 최소한만 수정한다.
- **보안**
  - Supabase URL과 publishable key는 `local.properties`에만 둔다.
  - Gemini/Claude API 키 같은 비밀 키는 Supabase Edge Function secrets에만 둔다. 앱 코드, `build.gradle.kts`, `local.properties`에는 절대 넣지 않는다.
  - 커밋 전에는 스테이징된 파일에 키가 없는지 확인한다.
