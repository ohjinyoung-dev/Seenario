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
-- - 허용 값: birds, wind, rain, water, traffic, people, music, insects, fire
-- ---------------------------------------------------------------------------
alter table public.sounds
    add column if not exists contains text[] not null default '{}';

alter table public.sounds drop constraint if exists sounds_contains_check;
alter table public.sounds add constraint sounds_contains_check
    check (contains <@ array['birds', 'wind', 'rain', 'water', 'traffic', 'people', 'music', 'insects', 'fire']::text[]);

create index if not exists sounds_contains_idx on public.sounds using gin (contains);

-- 음원별 contains 값 (2026-11-03 대시보드에서 적용 완료, 기록용)
-- 새 음원을 올리면 실제로 들리는 소리를 직접 듣고 contains를 채운다.
update public.sounds set contains = array['rain'] where file_path = 'ambient/rain-window.mp3';                         -- 창밖 빗소리
update public.sounds set contains = array['water'] where file_path = 'ambient/ocean-waves.mp3';                       -- 파도 소리
update public.sounds set contains = array['birds', 'wind'] where file_path = 'ambient/forest-morning.mp3';            -- 아침 숲: 새소리, 바람 (2026-11-05 forest-evening에서 이름 변경)
update public.sounds set contains = array['rain', 'traffic', 'people'] where file_path = 'ambient/city-street-rain.mp3'; -- 비 오는 거리: 비, 차, 사람
update public.sounds set contains = array['fire'] where file_path = 'ambient/campfire-crackling.mp3';                -- 장작 타는 소리: 불
update public.sounds set contains = array['insects'] where file_path = 'ambient/crickets-night.mp3';                 -- 여름밤 풀벌레: 벌레
update public.sounds set contains = array['insects'] where file_path = 'ambient/cicada-chorus.mp3';                  -- 여름 저녁 매미: 벌레
update public.sounds set contains = array['birds'] where file_path = 'ambient/forest-evening.mp3';                   -- 저녁 숲: 새소리 (2026-11-05 새 음원)
update public.sounds set contains = array['wind'] where file_path = 'ambient/wind-trees.mp3';                       -- 바람에 살랑이는 나뭇잎: 바람
update public.sounds set contains = array['birds'] where file_path = 'ambient/owl-night.mp3';                       -- 밤 숲 부엉이: 새소리
update public.sounds set contains = array['music'] where mode = 'music';                                              -- 배경음악은 모두 music

-- ---------------------------------------------------------------------------
-- 배경음(music) 9곡 추가 (2026-11-05, 기록용(대시보드에서 실행 완료))
-- - 파일은 Storage 버킷 'sounds'의 music/ 폴더. 128kbps mp3, 음량 -16 LUFS로 맞춤.
-- - duration_sec은 변환한 mp3 길이를 반올림한 값.
-- ---------------------------------------------------------------------------
insert into public.sounds (mode, title, file_path, source, license, tags, contains, duration_sec) values
    -- 그리그 - 아침 기분
    ('music', '그리그 - 아침 기분', 'music/grieg-morning-mood.mp3',
     'https://commons.wikimedia.org/wiki/File:Henrik_Ibsen,_Edvard_Grieg_-_Morgenstemning_(Morning_Mood).ogg',
     'Public domain',
     array['morning','park','forest','outdoor','sunny','spring','refreshing','bright'],
     array['music'], 224),
    -- 비발디 - 사계 봄 3악장
    -- 메모: Musopen 출처, PDM, 음높이 -20센트 조정, 메타데이터 Orpheus 표기, 출시 전 교체 검토
    ('music', '비발디 - 사계 봄 3악장', 'music/vivaldi-spring-allegro.mp3',
     'https://commons.wikimedia.org/wiki/File:The_Modena_Chamber_Orchestra_-_Vivaldi%27s_Spring,_RV_269_-_III._Allegro.ogg',
     'PDM-owner',
     array['spring','park','outdoor','daytime','sunny','lively','bright'],
     array['music'], 258),
    -- 비발디 - 사계 가을 3악장
    -- 메모: Musopen 출처, PDM, 음높이 -20센트 조정, 메타데이터 Orpheus 표기, 출시 전 교체 검토
    ('music', '비발디 - 사계 가을 3악장', 'music/vivaldi-autumn-allegro.mp3',
     'https://commons.wikimedia.org/wiki/File:The_Modena_Chamber_Orchestra_-_Vivaldi%27s_Autumn,_RV_293_-_III._Allegro.ogg',
     'PDM-owner',
     array['autumn','park','forest','outdoor','daytime','lively'],
     array['music'], 202),
    -- 비발디 - 사계 겨울 2악장
    -- 메모: Musopen 출처, PDM, 음높이 -20센트 조정, 메타데이터 Orpheus 표기, 출시 전 교체 검토
    ('music', '비발디 - 사계 겨울 2악장', 'music/vivaldi-winter-largo.mp3',
     'https://commons.wikimedia.org/wiki/File:The_Modena_Chamber_Orchestra_-_Vivaldi%27s_Winter,_RV_297_-_II._Largo.ogg',
     'PDM-owner',
     array['winter','snow','indoor','home','cozy','calm','evening'],
     array['music'], 164),
    -- 슈만 - 트로이메라이
    ('music', '슈만 - 트로이메라이', 'music/schumann-traumerei.mp3',
     'https://commons.wikimedia.org/wiki/File:Robert_Schumann_-_scenes_from_childhood,_op._15_-_vii._dreaming.ogg',
     'Public domain',
     array['home','indoor','evening','night','winter','calm','cozy','nostalgic'],
     array['music'], 203),
    -- 조플린 - 엔터테이너 (아래 update로 indoor, busy 태그 추가)
    ('music', '조플린 - 엔터테이너', 'music/joplin-entertainer.mp3',
     'https://commons.wikimedia.org/wiki/File:The_Entertainer_-_Scott_Joplin.ogg',
     'Public domain',
     array['cafe','restaurant','street','city','daytime','lively','bright'],
     array['music'], 234),
    -- 헨델 - 수상 음악 혼파이프
    ('music', '헨델 - 수상 음악 혼파이프', 'music/handel-water-music-hornpipe.mp3',
     'https://commons.wikimedia.org/wiki/File:Handel%27s_Water_Music_-_12._Alla_hornpipe_-_Chamber_Orchestra_-_United_States_Marine_Band.opus',
     'Public domain',
     array['water','beach','outdoor','sunny','summer','daytime','lively','bright'],
     array['music'], 229),
    -- 베토벤 - 전원 교향곡 3악장
    ('music', '베토벤 - 전원 교향곡 3악장', 'music/beethoven-pastoral-allegro.mp3',
     'https://commons.wikimedia.org/wiki/File:Ludwig_van_Beethoven_-_symphony_no._6_in_f_major_%27pastoral%27,_op._68_-_iii._allegro.ogg',
     'Public domain',
     array['forest','mountain','park','outdoor','spring','summer','lively','refreshing'],
     array['music'], 201),
    -- 쇼팽 - 녹턴 2번
    ('music', '쇼팽 - 녹턴 2번', 'music/chopin-nocturne-op9-2.mp3',
     'https://commons.wikimedia.org/wiki/File:Chopin_-_Nocturne_No._2_in_E-flat_major,_Op._9_No._2_(Frank_Levy).flac',
     'Public domain',
     array['restaurant','cafe','indoor','evening','night','romantic','cozy','calm'],
     array['music'], 272);

-- 조플린 - 엔터테이너 태그 추가: 실내의 활기찬 장면용 (2026-11-05, 기록용(대시보드에서 실행 완료))
update public.sounds
set tags = array_cat(tags, array['indoor','busy'])
where file_path = 'music/joplin-entertainer.mp3';

-- ---------------------------------------------------------------------------
-- gemini_model_status (Gemini 키·모델별 건너뛰기 기록 + 오늘 사용량, _shared/model_status.ts가 읽고 쓴다)
-- - 한 줄 = 키 하나 × 모델 하나. key_name은 secret 이름(GEMINI_KEY_<이름>)에서 온 이름이고, 키 값은 절대 넣지 않는다.
-- - key_name '*'은 "모든 키"(모델 자체가 과부하·느림), model '*'은 "모든 모델"(키가 잘못됨)이다.
-- - 건너뛰기: 과부하(503/500/504)·응답 없음(slow)·시간 초과(timeout)는 모델 전체('*') 10분,
--   한도 초과(quota, 429)는 그 키×모델만 Google이 알려 준 시간까지, 키 오류(key_error, 401/402/403/잘못된 키)는 그 키 전체 1시간.
--   건너뛰지 않을 때는 skip_until/reason이 null이다.
-- - 사용량: usage_day(태평양 시간 날짜, Google 하루 한도 기준) 하루치 성공 요청 수와 토큰 수만 둔다.
--   날짜가 바뀐 뒤 처음 성공하면 0부터 다시 센다. 더하기는 gemini_record_usage()로만 한다(동시 요청에도 정확하게).
-- - 모든 Edge Function 인스턴스가 함께 보도록 DB에 둔다.
-- - RLS만 켜고 정책은 만들지 않는다: 앱(anon/authenticated)은 접근 불가, Edge Function은 service role로 접근.
-- ---------------------------------------------------------------------------
create table if not exists public.gemini_model_status (
    key_name      text not null default '*',
    model         text not null,
    skip_until    timestamptz,
    reason        text check (reason in ('overloaded', 'slow', 'timeout', 'quota', 'key_error')),
    last_status   text,
    last_ms       integer,
    usage_day     date not null default (now() at time zone 'America/Los_Angeles')::date,
    requests      integer not null default 0,
    prompt_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    updated_at    timestamptz not null default now(),
    primary key (key_name, model)
);
alter table public.gemini_model_status enable row level security;

-- 성공한 요청 1번을 기록한다: 오늘 사용량을 더하고(날짜가 바뀌었으면 새로 시작), 그 키×모델과 모델 전체('*')의 건너뛰기를 푼다.
create or replace function public.gemini_record_usage(
    p_key_name text,
    p_model text,
    p_usage_day date,
    p_prompt_tokens bigint,
    p_output_tokens bigint,
    p_ms integer
) returns void
language sql
set search_path = ''
as $$
    insert into public.gemini_model_status as s
        (key_name, model, usage_day, requests, prompt_tokens, output_tokens, last_status, last_ms, updated_at)
    values (p_key_name, p_model, p_usage_day, 1, p_prompt_tokens, p_output_tokens, '200', p_ms, now())
    on conflict (key_name, model) do update set
        requests      = case when s.usage_day = excluded.usage_day then s.requests + 1 else 1 end,
        prompt_tokens = case when s.usage_day = excluded.usage_day then s.prompt_tokens + excluded.prompt_tokens
                             else excluded.prompt_tokens end,
        output_tokens = case when s.usage_day = excluded.usage_day then s.output_tokens + excluded.output_tokens
                             else excluded.output_tokens end,
        usage_day     = excluded.usage_day,
        skip_until    = null,
        reason        = null,
        last_status   = '200',
        last_ms       = excluded.last_ms,
        updated_at    = now();
    update public.gemini_model_status
    set skip_until = null, reason = null, updated_at = now()
    where key_name = '*' and model = p_model and skip_until is not null;
$$;
revoke execute on function public.gemini_record_usage(text, text, date, bigint, bigint, integer) from public, anon, authenticated;
grant execute on function public.gemini_record_usage(text, text, date, bigint, bigint, integer) to service_role;
