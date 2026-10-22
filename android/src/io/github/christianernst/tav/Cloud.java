package io.github.christianernst.tav;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cloud side on Android: accounts, synced settings, presets and usage events on Supabase, with
 * no SDK (plain HttpsURLConnection and org.json, which the platform ships). The twin of
 * web/cloud.js: same endpoints, same SQL functions. Without assets/cloud.json every call is a no-op,
 * so the app works offline exactly as before.
 *
 * All network work runs on one background thread; callbacks come back on the main thread.
 */
final class Cloud {
    private static final String TAG = "TavCloud";
    private static final int FLUSH_AT = 40, MAX_QUEUE = 500;
    private static final long FLUSH_EVERY_MS = 20000;
    /** Deep link the browser returns to after a magic link or Google sign-in (see AndroidManifest). */
    static final String REDIRECT = "tav://auth";

    interface Callback<T> {
        void on(T value, Exception error);
    }

    interface Listener {
        void onAccountChanged();
    }

    private static Cloud instance;

    static synchronized Cloud get(Context ctx) {
        if (instance == null) instance = new Cloud(ctx.getApplicationContext());
        return instance;
    }

    private final String url, anonKey;
    private final SharedPreferences store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String app = "android", version;
    private String sessionId = UUID.randomUUID().toString();
    private String accessToken, refreshToken, userId, email;
    private long expiresAt;
    private boolean anonymous = true;
    private JSONArray queue = new JSONArray();
    private boolean flushScheduled;
    private Listener listener;

    private Cloud(Context ctx) {
        store = ctx.getSharedPreferences("tav_cloud", Context.MODE_PRIVATE);
        String u = null, k = null;
        try (InputStream in = ctx.getAssets().open("cloud.json")) {
            JSONObject cfg = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            u = cfg.optString("url", null);
            k = cfg.optString("anonKey", null);
        } catch (IOException | JSONException e) {
            // no cloud.json: offline edition
        }
        url = (u != null && u.startsWith("https://")) ? u.replaceAll("/+$", "") : null;
        anonKey = (url != null && k != null && !k.isEmpty()) ? k : null;
        String v = "0";
        try {
            v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        version = v;
        accessToken = store.getString("access", null);
        refreshToken = store.getString("refresh", null);
        userId = store.getString("uid", null);
        email = store.getString("email", null);
        anonymous = store.getBoolean("anon", true);
        expiresAt = store.getLong("expires", 0);
        try {
            queue = new JSONArray(store.getString("queue", "[]"));
        } catch (JSONException e) {
            queue = new JSONArray();
        }
    }

    boolean enabled() {
        return anonKey != null;
    }

    boolean signedIn() {
        return enabled() && accessToken != null;
    }

    boolean anonymous() {
        return anonymous;
    }

    String email() {
        return email;
    }

    String userId() {
        return userId;
    }

    boolean analyticsOn() {
        return store.getBoolean("analytics", true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Restores or starts an (anonymous) session, then sends what is still queued. */
    void start() {
        if (!enabled()) return;
        io.execute(() -> {
            try {
                if (accessToken != null && expiresAt - 60 < now()) refresh();
                if (accessToken != null) loadUser();
                if (accessToken == null) signInAnonymously();
                flushNow();
            } catch (Exception e) {
                Log.w(TAG, "start: " + e.getMessage());
            }
        });
    }

    /** Tokens from the deep link the browser returns to (tav://auth#access_token=...). */
    boolean takeTokens(Uri uri) {
        if (!enabled() || uri == null || uri.getFragment() == null || !uri.getFragment().contains("access_token=")) return false;
        Uri q = Uri.parse("tav://auth?" + uri.getFragment());
        String at = q.getQueryParameter("access_token"), rt = q.getQueryParameter("refresh_token");
        if (at == null) return false;
        long life = 3600;
        try {
            life = Long.parseLong(q.getQueryParameter("expires_in"));
        } catch (Exception ignored) {
        }
        setSession(at, rt, life, null);
        io.execute(() -> {
            try {
                loadUser();
                track("login", new JSONObject().put("provider", email != null ? "email" : "oauth"));
                flushNow();
            } catch (Exception e) {
                Log.w(TAG, "login: " + e.getMessage());
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ events

    void track(String kind, JSONObject props) {
        if (!enabled() || !analyticsOn()) return;
        try {
            JSONObject e = new JSONObject().put("session_id", sessionId).put("kind", kind)
                    .put("ts", iso(now())).put("day", localDay()).put("props", props != null ? props : new JSONObject());
            synchronized (this) {
                queue.put(e);
                while (queue.length() > MAX_QUEUE) queue.remove(0);
                store.edit().putString("queue", queue.toString()).apply();
                if (queue.length() >= FLUSH_AT) flush();
                else if (!flushScheduled) {
                    flushScheduled = true;
                    main.postDelayed(this::flush, FLUSH_EVERY_MS);
                }
            }
        } catch (JSONException ignored) {
        }
    }

    void track(String kind, String key, Object value) {
        try {
            track(kind, new JSONObject().put(key, value));
        } catch (JSONException ignored) {
        }
    }

    /** Sends the queue in the background; safe to call often (onStop, for instance). */
    void flush() {
        flushScheduled = false;
        if (!enabled()) return;
        io.execute(this::flushNow);
    }

    /** Ends the usage session: the next event starts a new one. */
    void newSession() {
        sessionId = UUID.randomUUID().toString();
    }

    private void flushNow() {
        JSONArray batch;
        synchronized (this) {
            if (accessToken == null || queue.length() == 0) return;
            batch = queue;
            queue = new JSONArray();
            store.edit().putString("queue", "[]").apply();
        }
        try {
            rpc("ingest_events", new JSONObject().put("p_app", app).put("p_version", version).put("p_events", batch));
        } catch (Exception e) {
            Log.w(TAG, "flush: " + e.getMessage());
            boolean keep = !(e instanceof HttpException) || ((HttpException) e).status >= 500 || ((HttpException) e).status == 401;
            if (keep) synchronized (this) {   // back in front, for next time; a 4xx is our bug and is dropped
                for (int i = 0; i < queue.length(); i++) batch.put(queue.opt(i));
                queue = batch;
                store.edit().putString("queue", queue.toString()).apply();
            }
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * Sends the settings document and when it last changed; the newer side wins and comes back
     * (null when nothing is newer than what was sent).
     */
    void syncSettings(JSONObject data, long updatedAtMs, Callback<JSONObject> cb) {
        run(cb, () -> {
            Object r = rpc("sync_settings", new JSONObject().put("p_data", data).put("p_updated_at", iso(updatedAtMs / 1000)));
            JSONObject row = r instanceof JSONArray ? ((JSONArray) r).optJSONObject(0) : (JSONObject) r;
            if (row == null) return null;
            long at = parseIso(row.optString("updated_at"));
            return at > updatedAtMs ? row.optJSONObject("data").put("_updated_at", at) : null;
        });
    }

    // ------------------------------------------------------------------ presets

    void listPresets(Callback<JSONArray> cb) {
        run(cb, () -> (JSONArray) rest("GET", "/presets?select=id,data,is_public,code,forked_from,updated_at&deleted_at=is.null&order=updated_at.desc", null));
    }

    void savePreset(JSONObject doc, String id, Callback<JSONObject> cb) {
        run(cb, () -> {
            Object r = id != null ? rest("PATCH", "/presets?id=eq." + id, new JSONObject().put("data", doc))
                    : rest("POST", "/presets", new JSONObject().put("owner", userId).put("data", doc));
            return ((JSONArray) r).optJSONObject(0);
        });
    }

    void publishPreset(String id, boolean on, Callback<JSONObject> cb) {
        run(cb, () -> ((JSONArray) rest("PATCH", "/presets?id=eq." + id, new JSONObject().put("is_public", on))).optJSONObject(0));
    }

    void removePreset(String id, Callback<JSONArray> cb) {
        run(cb, () -> (JSONArray) rest("PATCH", "/presets?id=eq." + id, new JSONObject().put("deleted_at", iso(now()))));
    }

    void trendingPresets(int limit, Callback<JSONArray> cb) {
        run(cb, () -> (JSONArray) rpc("trending_presets", new JSONObject().put("p_limit", limit)));
    }

    void presetOfTheDay(Callback<JSONObject> cb) {
        run(cb, () -> ((JSONArray) rpc("preset_of_the_day", new JSONObject())).optJSONObject(0));
    }

    void presetByCode(String code, Callback<JSONObject> cb) {
        run(cb, () -> ((JSONArray) rpc("preset_by_code", new JSONObject().put("p_code", code))).optJSONObject(0));
    }

    void forkPreset(String id, Callback<JSONObject> cb) {
        run(cb, () -> (JSONObject) rpc("fork_preset", new JSONObject().put("p_id", id)));
    }

    /** Streak, days this week, listened seconds, favourite scene; see me_summary() in the schema. */
    void me(Callback<JSONObject> cb) {
        run(cb, () -> (JSONObject) rpc("me_summary", new JSONObject()));
    }

    // ------------------------------------------------------------------ account

    /**
     * Guest: the email gets a confirmation link and the guest becomes that account, same id.
     * Signed out: a magic link. The link opens tav://auth, which lands in MainActivity.
     */
    void sendMagicLink(String email, Callback<String> cb) {
        run(cb, () -> {
            String redirect = Uri.encode(REDIRECT);
            if (accessToken != null && anonymous) {
                call("PUT", "/auth/v1/user?redirect_to=" + redirect, new JSONObject().put("email", email), true);
                return "confirm";
            }
            call("POST", "/auth/v1/otp", new JSONObject().put("email", email).put("create_user", true)
                    .put("options", new JSONObject().put("email_redirect_to", REDIRECT)), false);
            return "link";
        });
    }

    /** The URL to open in the browser for Google; the browser comes back to tav://auth. */
    void googleUrl(Callback<String> cb) {
        run(cb, () -> {
            String redirect = Uri.encode(REDIRECT);
            if (accessToken != null && anonymous) {
                try {   // keep the guest's id: needs "manual linking" on in the Supabase project
                    Object r = call("GET", "/auth/v1/user/identities/authorize?skip_http_redirect=true&provider=google&redirect_to=" + redirect, null, true);
                    if (r instanceof JSONObject && ((JSONObject) r).has("url")) return ((JSONObject) r).getString("url");
                } catch (Exception ignored) {
                }
            }
            return url + "/auth/v1/authorize?provider=google&redirect_to=" + redirect;
        });
    }

    void signOut(Callback<Boolean> cb) {
        run(cb, () -> {
            flushNow();
            try {
                call("POST", "/auth/v1/logout", new JSONObject(), true);
            } catch (Exception ignored) {
            }
            setSession(null, null, 0, null);
            newSession();
            signInAnonymously();
            return true;
        });
    }

    void setAnalytics(boolean on) {
        store.edit().putBoolean("analytics", on).apply();
        if (!on) synchronized (this) {
            queue = new JSONArray();
            store.edit().putString("queue", "[]").apply();
        }
        if (!enabled()) return;
        io.execute(() -> {
            try {
                if (userId != null) rest("PATCH", "/profiles?id=eq." + userId, new JSONObject().put("analytics_opt_in", on));
            } catch (Exception e) {
                Log.w(TAG, "analytics: " + e.getMessage());
            }
        });
    }

    void forgetMyEvents(Callback<Object> cb) {
        run(cb, () -> rpc("forget_my_events", new JSONObject()));
    }

    // ------------------------------------------------------------------ auth plumbing

    private void signInAnonymously() {
        try {
            JSONObject s = (JSONObject) call("POST", "/auth/v1/signup", new JSONObject(), false);
            setSession(s.getString("access_token"), s.optString("refresh_token", null), s.optLong("expires_in", 3600), s.optJSONObject("user"));
        } catch (Exception e) {
            Log.w(TAG, "anonymous sign-in failed (enable it under Authentication > Providers): " + e.getMessage());
        }
    }

    private void refresh() {
        if (refreshToken == null) return;
        try {
            JSONObject s = (JSONObject) call("POST", "/auth/v1/token?grant_type=refresh_token", new JSONObject().put("refresh_token", refreshToken), false);
            setSession(s.getString("access_token"), s.optString("refresh_token", refreshToken), s.optLong("expires_in", 3600), s.optJSONObject("user"));
        } catch (HttpException e) {
            if (e.status == 400 || e.status == 401) setSession(null, null, 0, null);
        } catch (Exception e) {
            Log.w(TAG, "refresh: " + e.getMessage());
        }
    }

    private void loadUser() {
        try {
            Object u = call("GET", "/auth/v1/user", null, true);
            if (u instanceof JSONObject) setSession(accessToken, refreshToken, expiresAt - now(), (JSONObject) u);
        } catch (HttpException e) {
            if (e.status == 401 || e.status == 403) setSession(null, null, 0, null);
        } catch (Exception e) {
            Log.w(TAG, "user: " + e.getMessage());
        }
    }

    private synchronized void setSession(String access, String refreshTok, long life, JSONObject user) {
        accessToken = access;
        refreshToken = access != null ? refreshTok : null;
        expiresAt = access != null ? now() + life : 0;
        if (access == null) {
            userId = null;
            email = null;
            anonymous = true;
        } else if (user != null) {
            userId = user.optString("id", userId);
            email = user.isNull("email") || user.optString("email").isEmpty() ? null : user.optString("email");
            anonymous = user.optBoolean("is_anonymous", email == null);
        }
        store.edit().putString("access", accessToken).putString("refresh", refreshToken).putString("uid", userId)
                .putString("email", email).putBoolean("anon", anonymous).putLong("expires", expiresAt).apply();
        if (listener != null) main.post(() -> {
            if (listener != null) listener.onAccountChanged();
        });
    }

    // ------------------------------------------------------------------ HTTP

    static final class HttpException extends IOException {
        final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    interface Work<T> {
        T run() throws Exception;
    }

    private <T> void run(Callback<T> cb, Work<T> work) {
        if (!enabled()) {
            if (cb != null) main.post(() -> cb.on(null, new IOException("cloud is not configured")));
            return;
        }
        io.execute(() -> {
            T value = null;
            Exception error = null;
            try {
                value = work.run();
            } catch (Exception e) {
                error = e;
            }
            if (cb != null) {
                final T v = value;
                final Exception err = error;
                main.post(() -> cb.on(v, err));
            }
        });
    }

    private Object rpc(String fn, JSONObject args) throws IOException, JSONException {
        return rest("POST", "/rpc/" + fn, args);
    }

    private Object rest(String method, String path, JSONObject body) throws IOException, JSONException {
        return call(method, "/rest/v1" + path, body, true);
    }

    private Object call(String method, String path, JSONObject body, boolean auth) throws IOException, JSONException {
        if (auth && accessToken != null && expiresAt - 60 < now()) refresh();
        if (auth && accessToken == null) throw new HttpException(401, "not signed in");
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setRequestProperty("apikey", anonKey);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (path.startsWith("/rest/")) c.setRequestProperty("Prefer", "return=representation");
        if (auth) c.setRequestProperty("Authorization", "Bearer " + accessToken);
        if (body != null) {
            c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in != null ? new String(readAll(in), StandardCharsets.UTF_8) : "";
        c.disconnect();
        if (status >= 400) {
            String msg = text;
            try {
                JSONObject j = new JSONObject(text);
                msg = j.optString("msg", j.optString("message", j.optString("error_description", j.optString("error", text))));
            } catch (JSONException ignored) {
            }
            throw new HttpException(status, status + " " + path + ": " + msg);
        }
        if (text.isEmpty()) return null;
        try {
            return text.trim().startsWith("[") ? new JSONArray(text) : text.trim().startsWith("{") ? new JSONObject(text) : text;
        } catch (JSONException e) {
            return text;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String iso(long epochSeconds) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochSeconds * 1000));
    }

    private static long parseIso(String s) {
        // PostgREST sends 2026-01-01T10:00:00.123456+00:00; seconds are plenty here.
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String localDay() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
