-- The slice of a Supabase project the migrations rely on, for tests on plain Postgres.
do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
create schema if not exists auth;
create table if not exists auth.users (
    id           uuid primary key default gen_random_uuid(),
    email        text,
    is_anonymous boolean not null default false,
    created_at   timestamptz not null default now()
);
-- Supabase reads the JWT; the test sets request.jwt.claim.sub directly.
create or replace function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
