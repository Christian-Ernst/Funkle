// TAV cloud client: accounts, synced settings, presets and usage events on Supabase, with no SDK.
// Everything is plain fetch against the Auth (GoTrue) and REST (PostgREST) endpoints, so the same
// calls map one to one onto android/.../Cloud.java. Without web/cloud-config.js the page works
// exactly as before: every method is a no-op that resolves to null.
//
//   TavCloud.init({ app: 'web', version: '1.0' })  -> starts (anonymous) session, flushes old events
//   TavCloud.track('scene', { scene: 'halo' })     -> queued, sent in batches
//   TavCloud.settings.sync(doc, updatedAtMs)       -> newer side wins, returns the winner
//   TavCloud.presets.list() / save(doc) / publish(id) / remove(id) / trending() / ofTheDay()
//   TavCloud.auth.sendMagicLink(email) / linkGoogle() / signOut() / user()
//   TavCloud.me()                                  -> streak, listened time, favourite scene
'use strict';

const TavCloud = (() => {
  const cfg = (typeof TAV_CLOUD !== 'undefined' && TAV_CLOUD && TAV_CLOUD.url && TAV_CLOUD.anonKey) ? TAV_CLOUD : null;
  const KEY = { session: 'tav.cloud.session', queue: 'tav.cloud.queue', optOut: 'tav.cloud.optout' };
  const FLUSH_EVERY_MS = 20000, FLUSH_AT = 40, MAX_QUEUE = 500;
  let app = 'web', version = '0', session = null, queue = [], timer = 0, sessionId = uuid(), ready = null;
  const listeners = new Set();

  // ---------- small helpers
  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  }
  function load(k, dflt) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : dflt; } catch (e) { return dflt; } }
  function store(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private mode */ } }
  function localDay(d = new Date()) {
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }
  function emit() { for (const l of listeners) { try { l(state()); } catch (e) { /* listener's problem */ } } }
  function state() {
    const u = session && session.user;
    return { enabled: !!cfg, signedIn: !!u, anonymous: !u || !!u.is_anonymous, email: u && u.email || null, uid: u && u.id || null };
  }

  // ---------- HTTP
  async function call(path, { method = 'GET', body, auth = true, headers = {}, keepalive = false } = {}) {
    if (!cfg) return null;
    if (auth && session && session.expires_at - 60 < Date.now() / 1000) await refresh();
    const h = { apikey: cfg.anonKey, 'Content-Type': 'application/json', ...headers };
    if (auth && session) h.Authorization = 'Bearer ' + session.access_token;
    const r = await fetch(cfg.url + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), keepalive });
    const text = await r.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
    if (!r.ok) {
      const err = new Error((data && (data.msg || data.message || data.error_description || data.error)) || `${r.status} ${path}`);
      err.status = r.status; err.data = data;
      throw err;
    }
    return data;
  }
  const rest = (path, opts) => call('/rest/v1' + path, { ...opts, headers: { Prefer: 'return=representation', ...(opts && opts.headers) } });
  const rpc = (fn, args = {}, opts) => rest('/rpc/' + fn, { method: 'POST', body: args, ...opts });

  // ---------- auth
  function setSession(s) {
    if (s && s.access_token) {
      session = { access_token: s.access_token, refresh_token: s.refresh_token, user: s.user,
        expires_at: s.expires_at || Math.floor(Date.now() / 1000) + (s.expires_in || 3600) };
    } else session = null;
    store(KEY.session, session);
    emit();
  }
  async function refresh() {
    if (!session || !session.refresh_token) return;
    try { setSession(await call('/auth/v1/token?grant_type=refresh_token', { method: 'POST', auth: false, body: { refresh_token: session.refresh_token } })); }
    catch (e) { if (e.status === 400 || e.status === 401) setSession(null); }
  }
  // Tokens arrive in the URL hash after a magic link or an OAuth redirect.
  function takeTokensFromUrl() {
    const hash = location.hash.startsWith('#') ? location.hash.slice(1) : '';
    if (!hash.includes('access_token=')) return false;
    const p = new URLSearchParams(hash);
    setSession({ access_token: p.get('access_token'), refresh_token: p.get('refresh_token'), expires_in: +p.get('expires_in') || 3600 });
    history.replaceState(null, '', location.pathname + location.search);
    return true;
  }
  async function loadUser() {
    try { const u = await call('/auth/v1/user'); if (u) { session.user = u; store(KEY.session, session); emit(); } }
    catch (e) { if (e.status === 401 || e.status === 403) setSession(null); }
  }
  async function signInAnonymously() {
    try { setSession(await call('/auth/v1/signup', { method: 'POST', auth: false, body: {} })); return true; }
    catch (e) { console.warn('TAV cloud: anonymous sign-in failed (enable it under Authentication → Providers):', e.message); return false; }
  }
  const auth = {
    user: () => session && session.user || null,
    state,
    onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    // Guest -> account with the same id (and all of its data): the email gets a confirmation link.
    // Signed out -> a magic link that signs in (and creates the user on first use).
    async sendMagicLink(email) {
      const redirect = encodeURIComponent(location.origin + location.pathname);
      if (session && session.user && session.user.is_anonymous) {
        await call('/auth/v1/user?redirect_to=' + redirect, { method: 'PUT', body: { email } });
        return 'confirm';
      }
      await call('/auth/v1/otp', { method: 'POST', auth: false, body: { email, create_user: true, options: { email_redirect_to: decodeURIComponent(redirect) } } });
      return 'link';
    },
    // Opens Google. A guest keeps the id when "manual linking" is on in the Supabase project; else
    // it is a fresh sign-in (the guest's data stays with the guest id).
    async linkGoogle() {
      const redirect = encodeURIComponent(location.origin + location.pathname);
      if (session && session.user && session.user.is_anonymous) {
        try {
          const r = await call('/auth/v1/user/identities/authorize?skip_http_redirect=true&provider=google&redirect_to=' + redirect);
          if (r && r.url) { location.href = r.url; return; }
        } catch (e) { /* manual linking off: fall through to a plain sign-in */ }
      }
      location.href = `${cfg.url}/auth/v1/authorize?provider=google&redirect_to=${redirect}`;
    },
    async signOut() {
      await flush(true).catch(() => {});
      try { await call('/auth/v1/logout', { method: 'POST' }); } catch (e) { /* already gone */ }
      setSession(null);
      sessionId = uuid();
      await signInAnonymously();
    },
    async setAnalytics(on) {
      store(KEY.optOut, on ? null : true);
      if (session) await rest('/profiles?id=eq.' + session.user.id, { method: 'PATCH', body: { analytics_opt_in: !!on } });
      if (!on) { queue = []; store(KEY.queue, null); }
    },
    analyticsOn: () => !load(KEY.optOut, false),
    forgetMyEvents: () => rpc('forget_my_events'),
  };

  // ---------- events
  function track(kind, props) {
    if (!cfg || load(KEY.optOut, false)) return;
    queue.push({ session_id: sessionId, kind, ts: new Date().toISOString(), day: localDay(), props: props || {} });
    if (queue.length > MAX_QUEUE) queue.splice(0, queue.length - MAX_QUEUE);
    store(KEY.queue, queue);
    if (queue.length >= FLUSH_AT) flush();
    else if (!timer) timer = setTimeout(flush, FLUSH_EVERY_MS);
  }
  async function flush(keepalive = false) {
    clearTimeout(timer); timer = 0;
    if (!cfg || !session || !queue.length) return;
    const batch = queue.splice(0, 200);
    store(KEY.queue, queue);
    try {
      await rpc('ingest_events', { p_app: app, p_version: version, p_events: batch }, { keepalive });
    } catch (e) {
      if (e.status === 401) { queue.unshift(...batch); store(KEY.queue, queue); return; }
      if (e.status >= 500 || !e.status) { queue.unshift(...batch); store(KEY.queue, queue); }   // keep for next time; 4xx: drop, it is our bug
    }
    if (queue.length) timer = setTimeout(flush, FLUSH_EVERY_MS);
  }

  // ---------- settings (one document per user, newest wins)
  const settings = {
    async sync(doc, updatedAtMs) {
      if (!cfg || !session) return null;
      const rows = await rpc('sync_settings', { p_data: doc, p_updated_at: new Date(updatedAtMs || 0).toISOString() });
      const row = Array.isArray(rows) ? rows[0] : rows;
      return row ? { data: row.data, updatedAt: Date.parse(row.updated_at) } : null;
    },
  };

  // ---------- presets
  const presets = {
    list: () => rest('/presets?select=id,data,is_public,code,forked_from,updated_at&deleted_at=is.null&order=updated_at.desc'),
    async save(doc, id) {
      if (id) return (await rest('/presets?id=eq.' + id, { method: 'PATCH', body: { data: doc } }))[0];
      return (await rest('/presets', { method: 'POST', body: { owner: session.user.id, data: doc } }))[0];
    },
    publish: async (id, on = true) => (await rest('/presets?id=eq.' + id, { method: 'PATCH', body: { is_public: !!on } }))[0],
    remove: (id) => rest('/presets?id=eq.' + id, { method: 'PATCH', body: { deleted_at: new Date().toISOString() } }),
    like: (id, on = true) => on
      ? rest('/preset_likes', { method: 'POST', body: { preset_id: id, user_id: session.user.id }, headers: { Prefer: 'resolution=ignore-duplicates' } })
      : rest(`/preset_likes?preset_id=eq.${id}&user_id=eq.${session.user.id}`, { method: 'DELETE' }),
    fork: (id) => rpc('fork_preset', { p_id: id }),
    trending: (limit = 20) => rpc('trending_presets', { p_limit: limit }),
    recommended: (limit = 10) => rpc('recommend_presets', { p_limit: limit }),
    async ofTheDay() { const r = await rpc('preset_of_the_day'); return r && r[0] || null; },
    async byCode(code) { const r = await rpc('preset_by_code', { p_code: code }); return r && r[0] || null; },
    shareUrl: (code) => location.origin + location.pathname + '#l=' + code,
  };

  // ---------- lifecycle
  function init(opts = {}) {
    app = opts.app || app; version = opts.version || version;
    if (!cfg) return (ready = Promise.resolve(state()));
    queue = load(KEY.queue, []);
    const fromUrl = takeTokensFromUrl();
    if (!fromUrl) session = load(KEY.session, null);
    ready = (async () => {
      if (session) { if (session.expires_at - 60 < Date.now() / 1000) await refresh(); if (session) await loadUser(); }
      if (!session) await signInAnonymously();
      if (fromUrl && session) track('login', { provider: session.user && session.user.app_metadata && session.user.app_metadata.provider || 'email' });
      if (queue.length) flush();
      return state();
    })();
    addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flush(true); });
    addEventListener('pagehide', () => flush(true));
    return ready;
  }

  return { enabled: !!cfg, init, whenReady: () => ready || Promise.resolve(state()), track, flush, auth, settings, presets,
    me: () => rpc('me_summary'), sessionId: () => sessionId, newSession() { sessionId = uuid(); return sessionId; } };
})();
