-- Scenario: two users, one anonymous, through settings sync, presets, likes, forks, events,
-- streaks, trending, preset of the day, recommendations and the opt-out. Fails loudly on any
-- wrong answer. Runs as the superuser but switches role to `authenticated` for every client
-- call, so the row level security policies and grants are what is being tested.
\set ON_ERROR_STOP on
\o /dev/null
create or replace function pg_temp.check(cond boolean, what text) returns void language plpgsql as $$
begin if not cond then raise exception 'FAILED: %', what; end if; end $$;

-- two auth users; the trigger must create their profiles
insert into auth.users (id, is_anonymous) values ('00000000-0000-0000-0000-000000000001', true);
insert into auth.users (id, email)        values ('00000000-0000-0000-0000-000000000002', 'b@example.com');
select pg_temp.check((select count(*) from public.profiles) = 2, 'profiles created by trigger');
select pg_temp.check((select is_anonymous from public.profiles where id = '00000000-0000-0000-0000-000000000001'), 'anon flag');

-- ---- user A (anonymous)
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

-- settings: first push stores, an older push loses, a newer push wins
select pg_temp.check((select (data ->> 'scene')::int from public.sync_settings('{"scene": 3}', '2026-01-01T10:00Z')) = 3, 'settings first write');
select pg_temp.check((select (data ->> 'scene')::int from public.sync_settings('{"scene": 1}', '2026-01-01T09:00Z')) = 3, 'older write loses');
select pg_temp.check((select (data ->> 'scene')::int from public.sync_settings('{"scene": 5}', '2026-01-01T11:00Z')) = 5, 'newer write wins');

-- presets: shape is enforced, publishing allocates a share code
do $$ begin
    insert into public.presets (owner, data) values ('00000000-0000-0000-0000-000000000001', '{"name": "no version"}');
    raise exception 'FAILED: preset without v accepted';
exception when check_violation then null; end $$;
insert into public.presets (id, owner, data, is_public)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '{"v": 1, "name": "Neon dusk", "colors": ["#ff2e8c", "#26bfff", "#8c40ff"]}', true);
select pg_temp.check((select code ~ '^[a-z0-9]{6}$' from public.presets where id = '10000000-0000-0000-0000-000000000001'), 'share code allocated');
insert into public.presets (id, owner, data)
values ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '{"v": 1, "name": "Private one"}');
select pg_temp.check((select code is null from public.presets where id = '10000000-0000-0000-0000-000000000002'), 'private preset has no code');

-- you cannot write a preset as someone else
do $$ begin
    insert into public.presets (owner, data) values ('00000000-0000-0000-0000-000000000002', '{"v": 1, "name": "forged"}');
    raise exception 'FAILED: insert for another owner accepted';
exception when insufficient_privilege then null; end $$;

-- events: a batch of four over three days, plus an apply of the public preset
select pg_temp.check(public.ingest_events('web', 'test', jsonb_build_array(
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000001', 'kind', 'session_start', 'ts', now() - interval '2 days', 'day', current_date - 2),
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000001', 'kind', 'session_end',   'ts', now() - interval '2 days', 'day', current_date - 2, 'props', '{"seconds": 300}'::jsonb),
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000002', 'kind', 'session_start', 'ts', now() - interval '1 day',  'day', current_date - 1),
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000003', 'kind', 'session_start', 'ts', now(),                     'day', current_date),
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000003', 'kind', 'scene',         'ts', now(),                     'day', current_date, 'props', '{"scene": "halo"}'::jsonb),
    jsonb_build_object('session_id', '20000000-0000-0000-0000-000000000003', 'kind', 'look_applied',  'ts', now(),                     'day', current_date, 'props', '{"preset_id": "10000000-0000-0000-0000-000000000001"}'::jsonb)
)) = 6, 'ingest stored six events');
do $$ begin
    perform public.ingest_events('web', 'test', '[{"kind": "Bad Kind!", "ts": "2026-01-01T00:00Z", "session_id": "20000000-0000-0000-0000-000000000001"}]');
    raise exception 'FAILED: bad kind accepted';
exception when check_violation then null; end $$;
do $$ begin
    perform count(*) from public.events;
    raise exception 'FAILED: client can read the events table';
exception when insufficient_privilege then null; end $$;

-- the summary: three-day streak, 300 seconds, favourite scene halo
select pg_temp.check((public.me_summary() ->> 'current_streak')::int = 3, 'streak of 3: ' || public.me_summary()::text);
select pg_temp.check((public.me_summary() ->> 'listened_seconds')::int = 300, 'listened seconds');
select pg_temp.check(public.me_summary() ->> 'favourite_scene' = 'halo', 'favourite scene');
select pg_temp.check((public.me_summary() ->> 'is_anonymous')::boolean, 'summary says anonymous');

-- ---- user B
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select pg_temp.check((select count(*) from public.presets) = 1, 'B sees only the public preset');
select pg_temp.check((select name from public.presets) = 'Neon dusk', 'generated name column');
insert into public.preset_likes (preset_id, user_id) values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002');
select pg_temp.check((select forked_from = '10000000-0000-0000-0000-000000000001' from public.fork_preset('10000000-0000-0000-0000-000000000001')), 'fork remembers its source');
select pg_temp.check((select count(*) from public.presets where owner = '00000000-0000-0000-0000-000000000002') = 1, 'fork landed in B''s collection');
do $$ begin
    perform public.fork_preset('10000000-0000-0000-0000-000000000002');
    raise exception 'FAILED: forked a private preset';
exception when no_data_found then null; end $$;
select pg_temp.check((select likes = 1 and forks = 1 and appliers_30d = 1 from public.trending_presets(5) where id = '10000000-0000-0000-0000-000000000001'), 'trending stats');
select pg_temp.check((select liked from public.trending_presets(5) where id = '10000000-0000-0000-0000-000000000001'), 'liked flag for B');
select pg_temp.check((select id from public.preset_of_the_day()) = '10000000-0000-0000-0000-000000000001', 'preset of the day');
select pg_temp.check((select id from public.preset_by_code((select code from public.presets where id = '10000000-0000-0000-0000-000000000001'))) = '10000000-0000-0000-0000-000000000001', 'open by share code');
-- B has no apply history: recommendations fall back to trending, and never include B's own
select pg_temp.check((select reason from public.recommend_presets(5) limit 1) = 'trending', 'recommendation fallback');
select pg_temp.check((select count(*) from public.recommend_presets(5)) = 1, 'own presets are not recommended back');
select pg_temp.check((public.me_summary() ->> 'current_streak')::int = 0, 'B has no streak');

-- opt-out: events are dropped, and forgetting removes history
update public.profiles set analytics_opt_in = false where id = '00000000-0000-0000-0000-000000000002';
select pg_temp.check(public.ingest_events('android', 'test', '[{"kind": "session_start", "ts": "2026-01-01T00:00Z", "day": "2026-01-01", "session_id": "20000000-0000-0000-0000-000000000009"}]') = 0, 'opt-out drops events');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select pg_temp.check(public.forget_my_events() = 6, 'forget removes own events');
select pg_temp.check((public.me_summary() ->> 'active_days')::int = 0, 'nothing left after forget');

-- the public gallery works signed out
set role anon;
select set_config('request.jwt.claim.sub', '', false);
select pg_temp.check((select count(*) from public.trending_presets(5)) = 1, 'anon can browse the gallery');
do $$ begin
    perform public.me_summary();
    raise exception 'FAILED: anon could call me_summary';
exception when insufficient_privilege then null; end $$;
reset role;

-- user_streak counts a streak as alive through yesterday
select pg_temp.check((select current_streak from public.user_streak('00000000-0000-0000-0000-000000000002')) = 0, 'empty streak');
