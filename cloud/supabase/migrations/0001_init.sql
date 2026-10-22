-- TAV cloud: accounts, synced settings, presets and the event pipeline.
-- Runs on Supabase (Postgres 15+, auth schema present). Idempotent where cheap.
--
-- Design in one paragraph: a user is an auth.users row, anonymous at first (Supabase anonymous
-- sign-in), later linked to an email or Google identity with the same id, so nothing migrates.
-- Settings are one jsonb per user (last write wins by updated_at). Presets are opaque jsonb
-- documents owned by the "Build richer colour presets" work; this schema only requires `v` and
-- `name`. Events are append-only and only ever written through ingest_events(), which drops them
-- for users who opted out. Everything a client reads back about itself goes through SECURITY
-- DEFINER functions, so no table policy ever exposes another user's rows.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- profiles

create table if not exists public.profiles (
    id               uuid primary key references auth.users (id) on delete cascade,
    handle           text unique check (handle ~ '^[a-z0-9_]{3,24}$'),
    display_name     text check (char_length(display_name) <= 40),
    is_anonymous     boolean not null default true,
    analytics_opt_in boolean not null default true,
    created_at       timestamptz not null default now(),
    last_seen_at     timestamptz
);
comment on table public.profiles is 'One row per auth user, created by trigger. analytics_opt_in gates ingest_events().';

create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    insert into public.profiles (id, is_anonymous)
    values (new.id, coalesce(new.is_anonymous, false))
    on conflict (id) do nothing;
    return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
    for each row execute function public.handle_new_user();

-- An anonymous user who links an email or an OAuth identity keeps the id; mirror the flag.
create or replace function public.handle_user_updated() returns trigger
language plpgsql security definer set search_path = public as $$
begin
    update public.profiles set is_anonymous = coalesce(new.is_anonymous, false) where id = new.id;
    return new;
end $$;

drop trigger if exists on_auth_user_updated on auth.users;
create trigger on_auth_user_updated after update of is_anonymous, email on auth.users
    for each row execute function public.handle_user_updated();

alter table public.profiles enable row level security;
drop policy if exists "profiles: read own" on public.profiles;
create policy "profiles: read own" on public.profiles for select using (id = auth.uid());
drop policy if exists "profiles: update own" on public.profiles;
create policy "profiles: update own" on public.profiles for update
    using (id = auth.uid()) with check (id = auth.uid());

-- ---------------------------------------------------------------- settings

create table if not exists public.user_settings (
    user_id    uuid primary key references auth.users (id) on delete cascade,
    data       jsonb not null default '{}'::jsonb check (pg_column_size(data) < 16384),
    updated_at timestamptz not null default now()
);
comment on table public.user_settings is 'The menu settings (scene, palette, brightness, eq...) as one document. Last write wins.';

alter table public.user_settings enable row level security;
drop policy if exists "settings: own" on public.user_settings;
create policy "settings: own" on public.user_settings for all
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Merge: the client sends its document and the time it last changed. The newer side wins and
-- the winner is returned, so a client that was offline gets the cloud copy back.
create or replace function public.sync_settings(p_data jsonb, p_updated_at timestamptz)
returns table (data jsonb, updated_at timestamptz)
language plpgsql security invoker set search_path = public as $$
declare
    uid uuid := auth.uid();
    cur public.user_settings;
begin
    if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
    select * into cur from public.user_settings s where s.user_id = uid for update;
    if not found or cur.updated_at < p_updated_at then
        insert into public.user_settings (user_id, data, updated_at)
        values (uid, p_data, p_updated_at)
        on conflict (user_id) do update set data = excluded.data, updated_at = excluded.updated_at
        returning * into cur;
    end if;
    return query select cur.data, cur.updated_at;
end $$;

-- ---------------------------------------------------------------- presets

create table if not exists public.presets (
    id          uuid primary key default gen_random_uuid(),
    owner       uuid not null references auth.users (id) on delete cascade,
    data        jsonb not null check (
                    data ? 'v' and data ? 'name'
                    and jsonb_typeof(data -> 'v') = 'number'
                    and jsonb_typeof(data -> 'name') = 'string'
                    and char_length(data ->> 'name') between 1 and 40
                    and pg_column_size(data) < 8192),
    name        text generated always as (data ->> 'name') stored,
    is_public   boolean not null default false,
    code        text unique check (code ~ '^[a-z0-9]{6}$'),   -- share code, set when made public
    forked_from uuid references public.presets (id) on delete set null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz
);
comment on table public.presets is 'Opaque preset documents (see Palettes.java / engine.js for the format). Soft-deleted so offline clients converge.';
create index if not exists presets_owner_idx on public.presets (owner, updated_at desc);
create index if not exists presets_public_idx on public.presets (is_public, updated_at desc) where deleted_at is null;

alter table public.presets enable row level security;
drop policy if exists "presets: read own or public" on public.presets;
create policy "presets: read own or public" on public.presets for select
    using (owner = auth.uid() or (is_public and deleted_at is null));
drop policy if exists "presets: insert own" on public.presets;
create policy "presets: insert own" on public.presets for insert with check (owner = auth.uid());
drop policy if exists "presets: update own" on public.presets;
create policy "presets: update own" on public.presets for update
    using (owner = auth.uid()) with check (owner = auth.uid());
drop policy if exists "presets: delete own" on public.presets;
create policy "presets: delete own" on public.presets for delete using (owner = auth.uid());

-- A public preset gets a six-character share code (tav.app/#l=xxxxxx) the first time it is published.
create or replace function public.presets_before_write() returns trigger
language plpgsql as $$
declare tries int := 0;
begin
    if tg_op = 'UPDATE' then new.updated_at := now(); end if;
    if new.is_public and new.code is null then
        loop
            new.code := lower(substr(translate(encode(gen_random_bytes(6), 'base64'), '+/=OI01l', 'abcdefgh'), 1, 6));
            exit when not exists (select 1 from public.presets p where p.code = new.code);
            tries := tries + 1;
            if tries > 20 then raise exception 'could not allocate a share code'; end if;
        end loop;
    end if;
    return new;
end $$;
drop trigger if exists presets_touch on public.presets;
drop trigger if exists presets_before_write on public.presets;
create trigger presets_before_write before insert or update on public.presets
    for each row execute function public.presets_before_write();



-- Likes are the only social write a stranger can make on a preset.
create table if not exists public.preset_likes (
    preset_id  uuid not null references public.presets (id) on delete cascade,
    user_id    uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (preset_id, user_id)
);
alter table public.preset_likes enable row level security;
drop policy if exists "likes: read all" on public.preset_likes;
create policy "likes: read all" on public.preset_likes for select using (true);
drop policy if exists "likes: own" on public.preset_likes;
create policy "likes: own" on public.preset_likes for insert with check (user_id = auth.uid());
drop policy if exists "likes: unlike" on public.preset_likes;
create policy "likes: unlike" on public.preset_likes for delete using (user_id = auth.uid());

-- Copies a public preset into your own collection. The copy remembers where it came from, which
-- feeds the "N people use your preset" count.
create or replace function public.fork_preset(p_id uuid)
returns public.presets
language plpgsql security definer set search_path = public as $$
declare
    uid uuid := auth.uid();
    src public.presets;
    dst public.presets;
begin
    if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
    select * into src from public.presets where id = p_id and is_public and deleted_at is null;
    if not found then raise exception 'no such public preset' using errcode = 'P0002'; end if;
    insert into public.presets (owner, data, forked_from)
    values (uid, src.data, src.id) returning * into dst;
    return dst;
end $$;

-- ---------------------------------------------------------------- events

create table if not exists public.events (
    id          bigint generated always as identity primary key,
    user_id     uuid not null references auth.users (id) on delete cascade,
    session_id  uuid not null,
    kind        text not null check (kind ~ '^[a-z][a-z0-9_]{2,31}$'),
                -- Known kinds are listed in cloud/README.md (session_start, session_end, scene,
                -- setting_changed, look_saved, look_applied, look_shared, ...). A check on the
                -- spelling, not an enum, so a new client event needs no migration.
    ts          timestamptz not null,
    day         date not null,                     -- the client's local calendar day, for streaks
    app         text not null check (app in ('android', 'web', 'termux')),
    app_version text check (char_length(app_version) <= 32),
    props       jsonb not null default '{}'::jsonb check (pg_column_size(props) < 1024),
    received_at timestamptz not null default now()
);
comment on table public.events is 'Append-only usage events. Written only through ingest_events(); never read by clients directly.';
create index if not exists events_user_day_idx on public.events (user_id, day);
create index if not exists events_kind_ts_idx on public.events (kind, ts desc);

alter table public.events enable row level security;
-- No policies on purpose: with RLS on and no policy, anon/authenticated can neither read nor
-- write the table directly. ingest_events() below is SECURITY DEFINER.
revoke all on public.events from anon, authenticated;

-- Batch ingest. The payload is an array of {kind, ts, day, props, session_id} objects; app and
-- version come once per batch. Returns the number of rows stored (0 after opt-out).
create or replace function public.ingest_events(p_app text, p_version text, p_events jsonb)
returns integer
language plpgsql security definer set search_path = public as $$
declare
    uid uuid := auth.uid();
    n integer;
begin
    if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
    if jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) > 200 then
        raise exception 'events: array of at most 200' using errcode = '22023';
    end if;
    update public.profiles set last_seen_at = now() where id = uid;
    if not exists (select 1 from public.profiles where id = uid and analytics_opt_in) then
        return 0;
    end if;
    insert into public.events (user_id, session_id, kind, ts, day, app, app_version, props)
    select uid,
           (e ->> 'session_id')::uuid,
           e ->> 'kind',
           (e ->> 'ts')::timestamptz,
           coalesce((e ->> 'day')::date, ((e ->> 'ts')::timestamptz at time zone 'UTC')::date),
           p_app, p_version,
           coalesce(e -> 'props', '{}'::jsonb)
    from jsonb_array_elements(p_events) as e;
    get diagnostics n = row_count;
    return n;
end $$;

-- GDPR: forget everything but keep the account. The auth user itself is deleted from the
-- dashboard or the client calls auth's own delete; cascades take care of the rest.
create or replace function public.forget_my_events() returns integer
language plpgsql security definer set search_path = public as $$
declare n integer;
begin
    delete from public.events where user_id = auth.uid();
    get diagnostics n = row_count;
    return n;
end $$;

-- ---------------------------------------------------------------- engagement: derived data

-- Days a user showed up and how long. Cheap because of events_user_day_idx.
create or replace view public.user_days
with (security_invoker = false) as
select user_id, day,
       count(*) filter (where kind = 'session_start')                                 as sessions,
       coalesce(sum((props ->> 'seconds')::numeric) filter (where kind = 'session_end'), 0)::int as seconds
from public.events
group by user_id, day;
revoke all on public.user_days from anon, authenticated;

-- Current and longest streak of consecutive local days, counted up to today or yesterday
-- (so a streak is not broken at midnight before the user had a chance to play).
create or replace function public.user_streak(p_user uuid, p_today date default current_date)
returns table (current_streak int, longest_streak int, active_days int)
language sql stable security definer set search_path = public as $$
    with days as (
        select distinct day from public.events where user_id = p_user and kind = 'session_start'
    ),
    runs as (
        select day, day - (row_number() over (order by day))::int as grp from days
    ),
    streaks as (
        select min(day) as first_day, max(day) as last_day, count(*)::int as len from runs group by grp
    )
    select coalesce((select len from streaks where last_day >= p_today - 1 order by last_day desc limit 1), 0),
           coalesce((select max(len) from streaks), 0),
           (select count(*)::int from days);
$$;

-- How popular a preset is: likes, forks, and distinct people who applied it in the last 30 days.
create or replace view public.preset_stats
with (security_invoker = false) as
select p.id,
       (select count(*) from public.preset_likes l where l.preset_id = p.id)::int as likes,
       (select count(*) from public.presets f where f.forked_from = p.id and f.deleted_at is null)::int as forks,
       (select count(distinct e.user_id) from public.events e
         where e.kind in ('look_applied', 'preset_apply') and e.ts > now() - interval '30 days'
           and (e.props ->> 'preset_id')::uuid = p.id)::int as appliers_30d
from public.presets p
where p.is_public and p.deleted_at is null;
revoke all on public.preset_stats from anon, authenticated;

create or replace function public.preset_score(likes int, forks int, appliers int, age interval)
returns numeric language sql immutable as $$
    -- Hacker-News style: engagement over age^1.2 so new good presets can surface.
    select (likes + 2 * forks + 3 * appliers + 1)
           / power(greatest(extract(epoch from age) / 3600, 2), 1.2)::numeric;
$$;

-- Open a shared preset by its code, no login needed (the recipient may not have the app yet).
create or replace function public.preset_by_code(p_code text)
returns table (id uuid, name text, data jsonb, owner_handle text, likes int, forks int)
language sql stable security definer set search_path = public as $$
    select p.id, p.name, p.data, pr.handle, s.likes, s.forks
    from public.presets p
    join public.preset_stats s on s.id = p.id
    left join public.profiles pr on pr.id = p.owner
    where p.code = lower(p_code);
$$;

-- The public gallery, best first. Also the fallback for recommendations.
create or replace function public.trending_presets(p_limit int default 20)
returns table (id uuid, code text, name text, data jsonb, owner_handle text, likes int, forks int, appliers_30d int,
               created_at timestamptz, liked boolean)
language sql stable security definer set search_path = public as $$
    select p.id, p.code, p.name, p.data, pr.handle, s.likes, s.forks, s.appliers_30d, p.created_at,
           exists (select 1 from public.preset_likes l where l.preset_id = p.id and l.user_id = auth.uid())
    from public.presets p
    join public.preset_stats s on s.id = p.id
    left join public.profiles pr on pr.id = p.owner
    order by public.preset_score(s.likes, s.forks, s.appliers_30d, now() - p.created_at) desc
    limit least(greatest(p_limit, 1), 100);
$$;

-- One public preset per day, the same for everyone: picked among the top 30 by score with a
-- hash of the date, so it rotates but a strong preset comes back. Null while the gallery is empty.
create or replace function public.preset_of_the_day(p_day date default current_date)
returns table (id uuid, name text, data jsonb, owner_handle text, likes int, forks int)
language sql stable security definer set search_path = public as $$
    with top as (
        select p.id, p.name, p.data, pr.handle, s.likes, s.forks
        from public.presets p
        join public.preset_stats s on s.id = p.id
        left join public.profiles pr on pr.id = p.owner
        order by public.preset_score(s.likes, s.forks, s.appliers_30d, now() - p.created_at) desc
        limit 30
    )
    select * from top order by md5(p_day::text || id::text) limit 1;
$$;

-- "People who applied what you applied also applied": item-to-item co-occurrence over the last
-- 90 days, excluding what you own or already applied. Falls back to trending when there is not
-- enough history, which is the case for every new user and for the first months of the app.
create or replace function public.recommend_presets(p_limit int default 10)
returns table (id uuid, name text, data jsonb, owner_handle text, likes int, forks int, reason text)
language plpgsql stable security definer set search_path = public as $$
declare
    uid uuid := auth.uid();
begin
    return query
    with mine as (
        select distinct (e.props ->> 'preset_id')::uuid as pid
        from public.events e
        where e.user_id = uid and e.kind in ('look_applied', 'preset_apply') and e.ts > now() - interval '90 days'
          and e.props ? 'preset_id'
    ),
    peers as (
        select distinct e.user_id
        from public.events e join mine on (e.props ->> 'preset_id')::uuid = mine.pid
        where e.kind in ('look_applied', 'preset_apply') and e.user_id <> uid and e.ts > now() - interval '90 days'
    ),
    cooc as (
        select (e.props ->> 'preset_id')::uuid as pid, count(distinct e.user_id) as n
        from public.events e join peers on peers.user_id = e.user_id
        where e.kind in ('look_applied', 'preset_apply') and e.props ? 'preset_id'
          and (e.props ->> 'preset_id')::uuid not in (select pid from mine)
        group by 1
    )
    select p.id, p.name, p.data, pr.handle, s.likes, s.forks, 'people who liked your presets' as reason
    from cooc
    join public.presets p on p.id = cooc.pid and p.is_public and p.deleted_at is null and p.owner <> uid
    join public.preset_stats s on s.id = p.id
    left join public.profiles pr on pr.id = p.owner
    order by cooc.n desc, s.likes desc
    limit least(greatest(p_limit, 1), 50);

    if not found then
        return query
        select t.id, t.name, t.data, t.owner_handle, t.likes, t.forks, 'trending' as reason
        from public.trending_presets(p_limit) t
        where t.id not in (select p.id from public.presets p where p.owner = uid);
    end if;
end $$;

-- What the client shows on a "your week" card. One round trip.
create or replace function public.me_summary()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
    uid uuid := auth.uid();
    st record;
    fav text;
    secs int;
    week int;
    uses int;
begin
    if uid is null then return null; end if;
    select * into st from public.user_streak(uid);
    select props ->> 'scene' into fav from public.events
     where user_id = uid and kind = 'scene' and ts > now() - interval '30 days'
     group by 1 order by count(*) desc limit 1;
    select coalesce(sum(seconds), 0), count(*) filter (where day > current_date - 7)
      into secs, week from public.user_days where user_id = uid;
    select coalesce(sum(s.forks + s.likes), 0) into uses
      from public.presets p join public.preset_stats s on s.id = p.id where p.owner = uid;
    return jsonb_build_object(
        'current_streak', st.current_streak,
        'longest_streak', st.longest_streak,
        'active_days', st.active_days,
        'days_this_week', week,
        'listened_seconds', secs,
        'favourite_scene', fav,
        'preset_love', uses,
        'is_anonymous', (select is_anonymous from public.profiles where id = uid),
        'analytics_opt_in', (select analytics_opt_in from public.profiles where id = uid));
end $$;

-- ---------------------------------------------------------------- grants

-- Postgres (and Supabase's defaults) let everyone execute a new function; start from nothing.
revoke execute on function public.handle_new_user(), public.handle_user_updated(), public.presets_before_write(),
    public.sync_settings(jsonb, timestamptz), public.fork_preset(uuid), public.ingest_events(text, text, jsonb),
    public.forget_my_events(), public.user_streak(uuid, date), public.preset_score(int, int, int, interval),
    public.preset_by_code(text), public.trending_presets(int), public.preset_of_the_day(date),
    public.recommend_presets(int), public.me_summary()
    from public, anon, authenticated;
grant usage on schema public to anon, authenticated;
grant select, update on public.profiles to authenticated;
grant select, insert, update, delete on public.user_settings to authenticated;
grant select, insert, update, delete on public.presets to authenticated;
grant select, insert, delete on public.preset_likes to authenticated;
grant execute on function public.sync_settings(jsonb, timestamptz) to authenticated;
grant execute on function public.fork_preset(uuid) to authenticated;
grant execute on function public.ingest_events(text, text, jsonb) to authenticated;
grant execute on function public.forget_my_events() to authenticated;
grant execute on function public.me_summary() to authenticated;
grant execute on function public.recommend_presets(int) to authenticated;
grant execute on function public.trending_presets(int) to anon, authenticated;
grant execute on function public.preset_of_the_day(date) to anon, authenticated;
grant execute on function public.preset_by_code(text) to anon, authenticated;
