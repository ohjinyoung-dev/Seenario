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
