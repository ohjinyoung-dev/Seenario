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

### 원칙

- 사진은 저장하지 않는다(기기·Storage·DB 어디에도). 캡처한 이미지는 묘사 요청에만 쓰고 버린다.
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
- `MainActivity` runs that startup flow in `lifecycleScope` on first creation and logs `sound_mode`; UI is still the template `Greeting` screen.

## Database

`supabase/schema.sql` is the reference schema for `user_settings` and `memories` (both keyed to `auth.users`, RLS restricting rows to `auth.uid()`, `updated_at` trigger on `user_settings`). The live tables were created manually in the Supabase dashboard, so this file is not applied automatically — when changing the schema, give the user incremental SQL to run rather than re-running the whole file. Anonymous sign-ins must be enabled in the Supabase project.
