-- Engagement metrics, for the Supabase SQL editor (run as the project owner, who bypasses RLS).
-- They read public.events, so each number is only as complete as the share of users who leave
-- analytics on. Nothing here is personal: no emails, no song titles.

-- 1. Retention by install week: of the users whose first session fell in a week, the share seen
--    again 1, 7 and 30 days later (classic D1/D7/D30).
with first_seen as (
    select user_id, min(day) as d0 from public.events where kind = 'session_start' group by user_id
),
seen as (
    select distinct user_id, day from public.events where kind = 'session_start'
)
select date_trunc('week', f.d0)::date                                                as install_week,
       count(*)                                                                      as installs,
       round(100.0 * count(*) filter (where exists (select 1 from seen s where s.user_id = f.user_id and s.day = f.d0 + 1)) / count(*), 1)  as d1_pct,
       round(100.0 * count(*) filter (where exists (select 1 from seen s where s.user_id = f.user_id and s.day between f.d0 + 7 and f.d0 + 13)) / count(*), 1) as d7_pct,
       round(100.0 * count(*) filter (where exists (select 1 from seen s where s.user_id = f.user_id and s.day between f.d0 + 30 and f.d0 + 36)) / count(*), 1) as d30_pct
from first_seen f
where f.d0 < current_date - 1
group by 1 order by 1 desc;

-- 2. Sessions per active week and median session length, per app.
select date_trunc('week', day)::date as week, app,
       count(distinct user_id)                                             as weekly_actives,
       round(count(*) filter (where kind = 'session_start')::numeric / greatest(count(distinct user_id), 1), 2) as sessions_per_user,
       percentile_cont(0.5) within group (order by (props ->> 'seconds')::numeric) filter (where kind = 'session_end') as median_seconds
from public.events
group by 1, 2 order by 1 desc, 2;

-- 3. Where people spend their time: scene dwell, from the scene the session ended on and the
--    explicit scene changes (by = user beats autopilot as a signal of preference).
select props ->> 'scene' as scene,
       count(*) filter (where props ->> 'by' = 'user')      as chosen_by_hand,
       count(*) filter (where props ->> 'by' = 'autopilot') as by_autopilot,
       count(distinct user_id)                               as users
from public.events where kind = 'scene' and ts > now() - interval '30 days'
group by 1 order by chosen_by_hand desc;

-- 4. Looks: creation, publishing, reapply and share rates over the last 30 days.
select count(*) filter (where kind in ('look_saved', 'preset_save'))         as saved,
       count(*) filter (where kind in ('look_applied', 'preset_apply'))      as applied,
       count(*) filter (where kind in ('look_shared', 'preset_share'))       as shared,
       count(distinct user_id) filter (where kind in ('look_saved', 'preset_save')) as creators,
       (select count(*) from public.presets where is_public and deleted_at is null)    as public_looks
from public.events where ts > now() - interval '30 days';

-- 5. The settings people change, and what they change them to (what the defaults should be).
select props ->> 'key' as setting, props ->> 'to' as value, count(distinct user_id) as users
from public.events where kind = 'setting_changed' and ts > now() - interval '30 days'
group by 1, 2 order by 1, 3 desc;

-- 6. Streak distribution today: how many users are on a streak of each length.
select s.current_streak, count(*) as users
from (select distinct user_id from public.events where day > current_date - 2) u,
     lateral public.user_streak(u.user_id) s
group by 1 order by 1;

-- 7. Analytics opt-out share, so the numbers above can be read with the right caveat.
select round(100.0 * count(*) filter (where not analytics_opt_in) / greatest(count(*), 1), 1) as opt_out_pct,
       count(*) as users, count(*) filter (where not is_anonymous) as with_account
from public.profiles;
