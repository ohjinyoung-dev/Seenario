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
