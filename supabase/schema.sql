-- Seenario Supabase schema
-- 익명 로그인 사용 시 Dashboard > Authentication > Sign In / Providers 에서
-- "Allow anonymous sign-ins" 를 켜야 합니다.

-- ---------------------------------------------------------------------------
-- user_settings
-- ---------------------------------------------------------------------------
create table if not exists public.user_settings (
    user_id     uuid primary key references auth.users (id) on delete cascade,
    sound_mode  text not null default 'off',
    speech_rate real,
    updated_at  timestamptz not null default now()
);

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists user_settings_set_updated_at on public.user_settings;
create trigger user_settings_set_updated_at
    before update on public.user_settings
    for each row execute function public.set_updated_at();

alter table public.user_settings enable row level security;

create policy "user_settings_select_own" on public.user_settings
    for select to authenticated using (auth.uid() = user_id);
create policy "user_settings_insert_own" on public.user_settings
    for insert to authenticated with check (auth.uid() = user_id);
create policy "user_settings_update_own" on public.user_settings
    for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- memories
-- ---------------------------------------------------------------------------
create table if not exists public.memories (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
    created_at  timestamptz not null default now(),
    description text,
    place_type  text,
    season      text,
    weather     text,
    time_of_day text,
    mood        text,
    sound_tag   text,
    place_name  text,
    latitude    double precision,
    longitude   double precision,
    is_favorite boolean not null default false
);

create index if not exists memories_user_id_created_at_idx
    on public.memories (user_id, created_at desc);

alter table public.memories enable row level security;

create policy "memories_select_own" on public.memories
    for select to authenticated using (auth.uid() = user_id);
create policy "memories_insert_own" on public.memories
    for insert to authenticated with check (auth.uid() = user_id);
create policy "memories_update_own" on public.memories
    for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "memories_delete_own" on public.memories
    for delete to authenticated using (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- sounds (배경음악 / 환경음 목록)
-- 모든 사용자가 같이 쓰는 공용 목록. 앱에서는 읽기만 하고,
-- 추가/수정/삭제는 대시보드(또는 service role)에서만 한다.
-- 음원 파일은 Storage 버킷 'sounds'에 두고 file_path에 버킷 안 경로를 적는다.
-- tags에는 supabase/functions/describe-scene/sound_tags.ts 목록의 영문 단어만 넣는다.
-- ---------------------------------------------------------------------------
create table if not exists public.sounds (
    id           uuid primary key default gen_random_uuid(),
    mode         text not null check (mode in ('ambient', 'music')),
    title        text not null,
    file_path    text not null unique,
    duration_sec integer check (duration_sec > 0),
    loopable     boolean not null default true,
    tags         text[] not null default '{}',
    source       text,
    license      text,
    is_active    boolean not null default true,
    created_at   timestamptz not null default now()
);

create index if not exists sounds_mode_idx on public.sounds (mode) where is_active;
create index if not exists sounds_tags_idx on public.sounds using gin (tags);

alter table public.sounds enable row level security;

drop policy if exists "sounds_select_authenticated" on public.sounds;
create policy "sounds_select_authenticated" on public.sounds
    for select to authenticated using (is_active);

-- 음원 파일 버킷 (비공개, 로그인한 사용자만 읽기)
insert into storage.buckets (id, name, public)
values ('sounds', 'sounds', false)
on conflict (id) do nothing;

drop policy if exists "sounds_bucket_read_authenticated" on storage.objects;
create policy "sounds_bucket_read_authenticated" on storage.objects
    for select to authenticated using (bucket_id = 'sounds');

-- ---------------------------------------------------------------------------
-- sounds.contains (음원에 실제로 들어 있는 소리 종류)
-- - tags는 "어울리는 상황"(SOUND_TAGS), contains는 "실제로 들리는 소리"다.
-- - 소리 고르기 도구는 existing_sounds(지금 주변에서 이미 들리는 소리)와
--   contains가 겹치는 음원을 제외한다. 예: 실제로 비가 오면 rain이 든 음원은 고르지 않는다.
-- - 허용 값: birds, wind, rain, water, traffic, people, music
-- ---------------------------------------------------------------------------
alter table public.sounds
    add column if not exists contains text[] not null default '{}';

alter table public.sounds drop constraint if exists sounds_contains_check;
alter table public.sounds add constraint sounds_contains_check
    check (contains <@ array['birds', 'wind', 'rain', 'water', 'traffic', 'people', 'music']::text[]);

create index if not exists sounds_contains_idx on public.sounds using gin (contains);

-- 음원별 contains 값 (2026-11-03 대시보드에서 적용 완료, 기록용)
-- 새 음원을 올리면 실제로 들리는 소리를 직접 듣고 contains를 채운다.
update public.sounds set contains = array['rain'] where file_path = 'ambient/rain-window.mp3';                         -- 창밖 빗소리
update public.sounds set contains = array['water'] where file_path = 'ambient/ocean-waves.mp3';                       -- 파도 소리
update public.sounds set contains = array['birds', 'wind'] where file_path = 'ambient/forest-evening.mp3';            -- 저녁 숲: 새소리, 바람
update public.sounds set contains = array['rain', 'traffic', 'people'] where file_path = 'ambient/city-street-rain.mp3'; -- 비 오는 거리: 비, 차, 사람
update public.sounds set contains = array['music'] where mode = 'music';                                              -- 배경음악은 모두 music
