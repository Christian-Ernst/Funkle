# TAV cloud

Accounts, settings that follow the user, presets that can be shared, and the usage events that the
engagement work runs on. It is a Supabase project (Postgres, Auth, row level security and a REST
API), used from the web page and the Android app with plain HTTPS and JSON, no SDK, so the
repository's "no libraries" rule holds. Without a config file both editions run offline, as before.

## Set up (about ten minutes, once)

1. Create a project at https://supabase.com (free tier). Note the **Project URL** and the
   **anon public key** under *Settings → API*.
2. Apply the schema: open *SQL editor*, paste `supabase/migrations/0001_init.sql`, run. (Or
   `psql "$DATABASE_URL" -f supabase/migrations/0001_init.sql` with the connection string from
   *Settings → Database*.) Later migrations go in the same folder, numbered.
3. *Authentication → Providers*: turn on **Anonymous sign-ins** (the app starts as a guest) and
   keep **Email** on. For Google, add the provider with an OAuth client from Google Cloud.
   Under *Authentication → Settings* turn on **manual linking** so a guest keeps their id and data
   when they add Google.
4. *Authentication → URL configuration*: add the redirect URLs `tav://auth` (the Android app) and
   the web page's address, for instance `https://christian-ernst.github.io/TAV/web/`.
5. Configure the clients:
   - web: copy `web/cloud-config.example.js` to `web/cloud-config.js` and fill in URL and key.
   - Android: copy `android/assets/cloud.example.json` to `android/assets/cloud.json` and fill it
     in, then build. Both files are gitignored; the anon key is public by design (row level
     security decides what it may touch), but keeping it out of the repo keeps forks from
     writing into your project.
6. Check: open the web page, the *Account* button appears; `cloud/tools/check.sh` runs the schema
   and a scenario test on any Postgres (CI does this on every push).

## What is where

- `supabase/migrations/0001_init.sql`: tables (`profiles`, `user_settings`, `presets`,
  `preset_likes`, `events`), policies, and the SQL functions the clients call:
  `sync_settings`, `ingest_events`, `forget_my_events`, `fork_preset`, `trending_presets`,
  `preset_of_the_day`, `preset_by_code`, `recommend_presets`, `me_summary`.
- `supabase/queries/metrics.sql`: retention by install week (D1/D7/D30), sessions and median
  length, scene dwell, look creation and share rates, which settings people change, streaks,
  opt-out share. Paste into the SQL editor.
- `tools/check.sh`, `tools/scenario.sql`, `tools/supabase-stub.sql`: the test.
- `web/cloud.js` and `android/.../Cloud.java`: the two clients, same calls.

## How it behaves

- **Guest first.** The app signs in anonymously on first start and works fully. Adding an email
  (magic link) or Google turns that guest into an account with the same id, so settings, presets
  and the streak carry over. There is no login wall.
- **Settings** are one JSON document per user, the whole of the Android prefs (scene, palette,
  brightness, motion, equalizer, custom colours) or the web page's five settings. The newer side
  wins, by the time of the last local change. Android applies a newer cloud copy at start-up;
  the menu chips show the new choice after a restart.
- **Presets** are opaque JSON with at least `v` (integer) and `name`; the colour work owns the
  rest of the format. A preset made public gets a six-character share code, and
  `#l=abc123` on the web page opens it for anyone, signed in or not.
- **Events** are batched in the client (forty events or twenty seconds, and on going to the
  background) and sent through `ingest_events`. Clients can never read the events table. The
  "Help improve TAV" switch in the account card turns collection off, and "Delete my usage
  history" removes it. Never log audio or song titles.

### Event catalogue

Common: `session_id`, `ts`, `day` (the client's local day, for streaks), `app`, `app_version`.

| kind | props |
|---|---|
| `session_start` | `source` (visualizer, mic, file, demo), `install_id` |
| `session_end` | `seconds`, `end_scene`, `autopilot`, `mode` |
| `scene` | `scene`, `by` (user, autopilot, restore) |
| `palette` | `palette` |
| `setting_changed` | `key`, `to` |
| `look_saved`, `look_applied`, `look_shared` | `preset_id`, `origin` (own, daily, shared, gallery) |
| `login` | `provider` |

A kind is any `snake_case` word, so a new client event needs no migration; add it to this table.

## Costs and limits

Free tier: 50 000 monthly active users, 500 MB database, 5 GB egress. An event row is about 150
bytes, so a million events is 150 MB; `events` should be trimmed to the last 180 days once the
app has traction (a `pg_cron` job or a monthly `delete`). A project that gets no requests for a
week is paused on the free tier; the first user after that waits a minute.
