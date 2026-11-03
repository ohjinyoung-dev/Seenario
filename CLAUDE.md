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
   - 화면을 1초 이상 길게 누르면 듣지 않고 바로 묘사를 요청한다(시끄러운 곳용). TalkBack에서는 두 번 탭하고 누르고 있기.
4. "묘사해줘" → TTS "잠시만요, 주변을 살펴볼게요." + 상태 "살펴보는 중" → `DescribeTrigger.request()` → 최신 프레임 캡처 → Edge Function `describe-scene`(Gemini) 묘사.
5. 결과를 화면 아래쪽에 큰 글씨로 표시하고 TTS로 낭독(상태 "말하는 중") → 끝나면 "대기 중".
   - 처리 중 다시 묘사 요청: "지금 살펴보는 중이에요" 안내 후 무시.
   - 실패: "주변을 살펴보지 못했어요. 다시 시도해 주세요." 안내.
   - "다시 들려줘"(REPEAT): 마지막 묘사를 다시 낭독.
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
- `MainActivity` runs that startup flow in `lifecycleScope` on first creation and logs `sound_mode`, creates `SeenarioController`, shows `SeenarioScreen`, and forwards key events. Nothing else should live there.
- `SeenarioScreen.kt` — integration layer. `SeenarioController` wires `voice/` to `camera/` (describe/repeat handling, busy/error/watchdog, combined camera+mic permission request with a spoken notice). `SeenarioScreen` composes the full-screen camera preview, the single tap layer (push-to-talk), the top status panel, and the bottom result text (liveRegion off when TTS already read it).
- `voice/` — `VoiceController` (push-to-talk, media session, TTS via `speak()`), `SpeechListener`, `TtsSpeaker`, `CommandParser`. Commands reach other modules through `VoiceCommandListener`.
- `camera/` — `CameraStreamScreen` (preview + latest-frame capture on `DescribeTrigger` requests, no tap/text of its own), `FrameStreamAnalyzer`, `SceneDescriber` (calls Edge Function `describe-scene`).

## Database

`supabase/schema.sql` is the reference schema for `user_settings` and `memories` (both keyed to `auth.users`, RLS restricting rows to `auth.uid()`, `updated_at` trigger on `user_settings`), plus the shared read-only `sounds` catalog (ambient/music tracks; files live in the private Storage bucket `sounds`, rows point to them via `file_path`). The live tables were created manually in the Supabase dashboard, so this file is not applied automatically — when changing the schema, give the user incremental SQL to run rather than re-running the whole file. Anonymous sign-ins must be enabled in the Supabase project.

## 에이전트 구조 (ReAct)

- 기본 모드(묘사)와 대화 모드를 하나로 합친다. 앱은 사용자 입력과 최신 카메라 사진을 Edge Function `seenario-agent`로 보낸다. 에이전트는 생각 → 도구 사용 → 판단을 최대 3번 반복한 뒤 답한다.
- 사진 촬영과 소리 재생은 앱이 맡는다. 서버는 응답의 `actions`로 무엇을 할지 지시만 한다.
- 에이전트가 완성되기 전까지는 기존 `describe-scene`을 쓴다. 현재 `describe-scene`은 `{ description, model }`을 돌려준다. `user_settings.sound_mode`가 `ambient`/`music`이면 `soundMode`, `tags`(`SOUND_TAGS` 값), `sound`도 함께 돌려준다. `sound`는 `{ id, title, mode, url(1시간 signed URL), loopable, tags }` 형태이고, 고르지 못하면 `null`이다. 앱(`SceneDescriber`)은 아직 이 값들을 로그로만 남긴다.

### 앱 ↔ 에이전트 약속

요청:

```json
{
  "input": "string",
  "input_type": "speech | describe",
  "image": "base64 JPEG",
  "history": [{ "role": "user | agent", "text": "string" }]
}
```

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

### 소리 고르기 도구 (`tools/sound.ts`, 앱 담당)

- 지금 `describe-scene` 안에 있는 음원 선택 로직(`pickSound`)을 에이전트 단계에서 `supabase/functions/seenario-agent/tools/sound.ts`로 옮긴다. 이 로직은 `SOUND_TAGS` 기반으로 같은 mode의 음원 중 태그가 가장 많이 겹치는 것을 고르고, 동점이면 무작위로 고르며, 하나도 겹치지 않으면 고르지 않는다.
- 태그 목록 `SOUND_TAGS`는 현재 `supabase/functions/describe-scene/sound_tags.ts`에 있다. `sounds.tags`와 Gemini 태그는 이 목록의 영문 소문자 단어만 쓴다.
- 입력: `{ "mode": "ambient" | "music", "tags": SOUND_TAGS 안의 값 배열, "existing_sounds": 이미 들리는 소리 종류 배열 }`
- 출력: `{ "sound_id", "title", "url"(1시간 signed URL), "loopable" }` 또는 `null`
- `existing_sounds`에 해당하는 종류의 음원은 제외한다.

## 파일 담당

- **앱 담당**: `voice/`, `agent/`, `sound/`, `SeenarioScreen.kt`, `supabase/functions/seenario-agent/tools/sound.ts`, Storage 음원
- **서버/AI 담당**: `camera/`, `supabase/functions/seenario-agent/`의 나머지, `describe-scene`, `schema.sql`과 DB 변경
- **공용(수정 전 서로 알리기)**: `MainActivity.kt`, `AndroidManifest.xml`, `libs.versions.toml`, `build.gradle.kts`, `CLAUDE.md`

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
