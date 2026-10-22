// Copy to web/cloud-config.js and fill in your Supabase project (Settings → API). The anon key is
// public by design: row level security in cloud/supabase/migrations decides what it may touch.
// Without this file the web edition runs offline, exactly as before.
const TAV_CLOUD = {
  url: 'https://YOUR-PROJECT.supabase.co',
  anonKey: 'YOUR-ANON-KEY',
};
