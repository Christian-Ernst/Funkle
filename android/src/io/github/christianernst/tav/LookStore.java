package io.github.christianernst.tav;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The user's saved Looks: a list in SharedPreferences first (so Looks work offline and without an
 * account), mirrored to the cloud's presets table when the cloud is configured. Each entry keeps
 * the cloud row id and the six-character share code once it has them. Everything runs on the
 * main thread; the cloud calls come back there too.
 */
final class LookStore {
    static final int MAX = 60;

    /** One saved Look with its bookkeeping. */
    static final class Entry {
        final String id;          // local id
        Look look;
        String cloudId, code;     // the presets row, and its share code once published
        long updatedAt;           // ms; after a push, the cloud row's updated_at
        String origin = "own";    // own, shared, daily
        boolean pushing;          // a savePreset call is in flight (not stored)
        final List<Done<Entry>> waiting = new ArrayList<>();   // callbacks for when it lands

        Entry(String id, Look look) {
            this.id = id;
            this.look = look;
        }

        String name() {
            return look.name;
        }
    }

    interface Done<T> {
        void on(T value, Exception error);
    }

    private final SharedPreferences store;
    private final List<Entry> entries = new ArrayList<>();

    LookStore(Context ctx) {
        store = ctx.getSharedPreferences("tav_looks", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(store.getString("looks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Look l = Look.fromJson(o.optJSONObject("look"));
                if (l == null) continue;
                Entry e = new Entry(o.optString("id", UUID.randomUUID().toString()), l);
                e.cloudId = o.isNull("cloudId") ? null : o.optString("cloudId", null);
                e.code = o.isNull("code") ? null : o.optString("code", null);
                e.updatedAt = o.optLong("updatedAt", 0);
                e.origin = o.optString("origin", "own");
                entries.add(e);
            }
        } catch (JSONException e) {
            entries.clear();
        }
    }

    List<Entry> all() {
        return entries;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    Entry byId(String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    /** Adds a Look at the top; a Look with the same name replaces the old one. */
    Entry add(Look look, String origin) {
        look.name = Look.cleanName(look.name);
        Entry e = null;
        for (Entry x : entries)
            if (x.look.name.equalsIgnoreCase(look.name)) {
                e = x;
                break;
            }
        if (e == null) {
            e = new Entry(UUID.randomUUID().toString(), look);
            e.origin = origin;
        } else {
            entries.remove(e);
            e.look = look;
            e.code = null;   // the cloud copy is updated below; its code stays valid for the new content
        }
        e.updatedAt = System.currentTimeMillis();
        entries.add(0, e);
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        save();
        return e;
    }

    void remove(Entry e) {
        entries.remove(e);
        save();
    }

    /** A name for the current state that is not taken yet: "Halo · Neon", then "Halo · Neon 2". */
    String suggestName(String base) {
        String name = Look.cleanName(base);
        int n = 2;
        outer:
        while (true) {
            for (Entry e : entries)
                if (e.look.name.equalsIgnoreCase(name)) {
                    name = Look.cleanName(base + " " + n++);
                    continue outer;
                }
            return name;
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) {
                a.put(new JSONObject().put("id", e.id).put("look", e.look.toJson()).put("cloudId", e.cloudId == null ? JSONObject.NULL : e.cloudId)
                        .put("code", e.code == null ? JSONObject.NULL : e.code).put("updatedAt", e.updatedAt).put("origin", e.origin));
            }
        } catch (JSONException ignored) {
        }
        store.edit().putString("looks", a.toString()).apply();
    }

    // ------------------------------------------------------------------ cloud

    /**
     * Pulls the account's Looks (rows whose document has colours) that this phone does not have
     * yet, pushes local Looks that are not in the cloud, and reports whether anything changed.
     */
    void sync(Cloud cloud, Done<Boolean> done) {
        if (cloud == null || !cloud.signedIn()) {
            if (done != null) done.on(false, null);
            return;
        }
        cloud.listPresets((rows, err) -> {
            if (rows == null) {
                if (done != null) done.on(false, err);
                return;
            }
            boolean changed = false;
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", null);
                Look l = Look.fromJson(row.optJSONObject("data"));
                if (id == null || l == null) continue;
                seen.add(id);
                long at = Cloud.parseIso(row.optString("updated_at", ""));
                Entry e = null;
                for (Entry x : entries) if (id.equals(x.cloudId)) e = x;
                if (e != null) {
                    // both sides have it: the newer document wins (a second of slack for the clocks)
                    if (!e.look.sameAs(l)) {
                        if (at > e.updatedAt + 1000) {
                            e.look = l;
                            e.updatedAt = at;
                            changed = true;
                        } else if (e.updatedAt > at + 1000) {
                            pushEntry(cloud, e, null);
                        }
                    }
                } else {
                    for (Entry x : entries) if (x.cloudId == null && x.look.name.equalsIgnoreCase(l.name)) e = x;   // saved offline, then synced from elsewhere
                    if (e == null) {
                        e = new Entry(UUID.randomUUID().toString(), l);
                        e.updatedAt = at;
                        entries.add(e);
                        changed = true;
                    }
                    e.cloudId = id;
                }
                String code = row.isNull("code") ? null : row.optString("code", null);
                if (code != null && !code.equals(e.code)) {
                    e.code = code;
                    changed = true;
                }
            }
            for (Entry e : new ArrayList<>(entries)) {
                if (e.cloudId == null && !e.pushing) pushEntry(cloud, e, null);
                else if (!seen.contains(e.cloudId)) e.cloudId = null;   // deleted on another device: keep it here as a local Look
            }
            if (changed) save();
            if (done != null) done.on(changed, null);
        });
    }

    /**
     * Saves the entry's Look to the cloud (insert or update). One call per entry is in flight at a
     * time: a second call while the first is out waits for it (so Save followed by Share makes one
     * row, not two) and then sends again only if the Look changed meanwhile.
     */
    void pushEntry(Cloud cloud, Entry e, Done<Entry> done) {
        if (cloud == null || !cloud.signedIn()) {
            if (done != null) done.on(e, null);
            return;
        }
        if (e.pushing) {
            if (done != null) e.waiting.add(done);
            return;
        }
        e.pushing = true;
        final JSONObject sent = e.look.toJson();
        cloud.savePreset(sent, e.cloudId, (row, err) -> {
            e.pushing = false;
            if (row != null) {
                e.cloudId = row.optString("id", e.cloudId);
                if (!row.isNull("code")) e.code = row.optString("code", e.code);
                long at = Cloud.parseIso(row.optString("updated_at", ""));
                if (at > 0) e.updatedAt = at;
                save();
            }
            List<Done<Entry>> later = new ArrayList<>(e.waiting);
            e.waiting.clear();
            if (row != null && !Look.canonical(sent).equals(Look.canonical(e.look.toJson()))) {
                // the Look changed while the request was out: send the new one, then tell everyone
                pushEntry(cloud, e, (x, err2) -> {
                    if (done != null) done.on(e, err2);
                    for (Done<Entry> d : later) d.on(e, err2);
                });
                return;
            }
            if (done != null) done.on(e, err);
            for (Done<Entry> d : later) d.on(e, err);
        });
    }

    /** Makes the Look public so it gets a share code; the entry has the code when done returns. */
    void publish(Cloud cloud, Entry e, Done<Entry> done) {
        if (e.code != null || cloud == null || !cloud.signedIn()) {
            done.on(e, null);
            return;
        }
        pushEntry(cloud, e, (x, err) -> {
            if (e.cloudId == null) {
                done.on(e, err);
                return;
            }
            cloud.publishPreset(e.cloudId, true, (row, err2) -> {
                if (row != null && !row.isNull("code")) {
                    e.code = row.optString("code", null);
                    save();
                }
                done.on(e, err2);
            });
        });
    }

    /** Removes the entry here and marks the cloud row deleted. */
    void delete(Cloud cloud, Entry e) {
        remove(e);
        if (cloud != null && cloud.signedIn() && e.cloudId != null) cloud.removePreset(e.cloudId, null);
    }
}
