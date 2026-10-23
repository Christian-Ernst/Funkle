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
 * The user's profiles ({@link Profile}): a list in SharedPreferences first, so profiles work
 * offline and without an account, mirrored to the cloud's presets table when signed in. One of
 * them is active; the activity keeps the active one up to date as settings change. Same shape as
 * {@link LookStore}, minus publishing (a profile is the user's own set-up, not something shared).
 */
final class ProfileStore {
    static final int MAX = 20;
    static final String DEFAULT_NAME = "Default";

    static final class Entry {
        final String id;
        Profile profile;
        String cloudId;
        long updatedAt;
        boolean pushing;
        final List<LookStore.Done<Entry>> waiting = new ArrayList<>();

        Entry(String id, Profile profile) {
            this.id = id;
            this.profile = profile;
        }

        String name() {
            return profile.name;
        }
    }

    private final SharedPreferences store;
    private final List<Entry> entries = new ArrayList<>();
    private String activeId;

    ProfileStore(Context ctx) {
        store = ctx.getSharedPreferences("tav_profiles", Context.MODE_PRIVATE);
        activeId = store.getString("active", null);
        try {
            JSONArray a = new JSONArray(store.getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = Profile.fromJson(o.optJSONObject("profile"));
                if (p == null) continue;
                Entry e = new Entry(o.optString("id", UUID.randomUUID().toString()), p);
                e.cloudId = o.isNull("cloudId") ? null : o.optString("cloudId", null);
                e.updatedAt = o.optLong("updatedAt", 0);
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
        if (id == null) return null;
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    Entry byName(String name) {
        for (Entry e : entries) if (e.profile.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    /** The active profile, or null when there is none yet. */
    Entry active() {
        Entry e = byId(activeId);
        if (e == null && !entries.isEmpty()) e = entries.get(0);
        return e;
    }

    void activate(Entry e) {
        activeId = e == null ? null : e.id;
        save();
    }

    /** Adds a profile; a profile with the same name replaces the old one's content. Returns the entry. */
    Entry add(Profile p) {
        p.name = Look.cleanName(p.name);
        Entry e = byName(p.name);
        if (e == null) {
            e = new Entry(UUID.randomUUID().toString(), p);
            entries.add(e);
            while (entries.size() > MAX) entries.remove(entries.size() - 1);
        } else {
            e.profile = p;
        }
        e.updatedAt = System.currentTimeMillis();
        save();
        return e;
    }

    /** The entry's new content, keeping its name; true when the document changed. */
    boolean update(Entry e, Profile p) {
        p.name = e.profile.name;
        if (e.profile.sameAs(p)) return false;
        e.profile = p;
        e.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    void rename(Entry e, String name) {
        e.profile.name = Look.cleanName(name);
        e.updatedAt = System.currentTimeMillis();
        save();
    }

    void remove(Entry e) {
        entries.remove(e);
        if (e.id.equals(activeId)) activeId = entries.isEmpty() ? null : entries.get(0).id;
        save();
    }

    /** A name that is not taken yet: "Profile 2", "Profile 3". */
    String suggestName(String base) {
        String name = Look.cleanName(base);
        int n = 2;
        while (byName(name) != null) name = Look.cleanName(base + " " + n++);
        return name;
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) {
                a.put(new JSONObject().put("id", e.id).put("profile", e.profile.toJson())
                        .put("cloudId", e.cloudId == null ? JSONObject.NULL : e.cloudId).put("updatedAt", e.updatedAt));
            }
        } catch (JSONException ignored) {
        }
        store.edit().putString("profiles", a.toString()).putString("active", activeId).apply();
    }

    // ------------------------------------------------------------------ cloud

    /**
     * Pulls the account's profiles (presets rows whose document has kind "profile") that this
     * phone does not have, pushes local ones the cloud lacks, lets the newer side win where both
     * have one, and reports whether anything changed.
     */
    void sync(Cloud cloud, LookStore.Done<Boolean> done) {
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
                Profile p = Profile.fromJson(row.optJSONObject("data"));
                if (id == null || p == null) continue;
                seen.add(id);
                long at = Cloud.parseIso(row.optString("updated_at", ""));
                Entry e = null;
                for (Entry x : entries) if (id.equals(x.cloudId)) e = x;
                if (e != null) {
                    if (!e.profile.sameAs(p)) {
                        if (at > e.updatedAt + 1000) {
                            e.profile = p;
                            e.updatedAt = at;
                            changed = true;
                        } else if (e.updatedAt > at + 1000) {
                            pushEntry(cloud, e, null);
                        }
                    }
                } else {
                    for (Entry x : entries) if (x.cloudId == null && x.profile.name.equalsIgnoreCase(p.name)) e = x;
                    if (e == null) {
                        e = new Entry(UUID.randomUUID().toString(), p);
                        e.updatedAt = at;
                        entries.add(e);
                        changed = true;
                    } else if (at > e.updatedAt + 1000) {   // the same name, made on another device: take the newer
                        e.profile = p;
                        e.updatedAt = at;
                        changed = true;
                    }
                    e.cloudId = id;
                }
            }
            for (Entry e : new ArrayList<>(entries)) {
                if (e.cloudId == null && !e.pushing) pushEntry(cloud, e, null);
                else if (!seen.contains(e.cloudId)) e.cloudId = null;   // deleted on another device: keep it here as a local profile
            }
            if (changed || activeId == null) save();
            if (done != null) done.on(changed, null);
        });
    }

    /** Saves the entry to the cloud (insert or update); one call per entry in flight, see LookStore. */
    void pushEntry(Cloud cloud, Entry e, LookStore.Done<Entry> done) {
        if (cloud == null || !cloud.signedIn()) {
            if (done != null) done.on(e, null);
            return;
        }
        if (e.pushing) {
            if (done != null) e.waiting.add(done);
            return;
        }
        e.pushing = true;
        final JSONObject sent = e.profile.toJson();
        cloud.savePreset(sent, e.cloudId, (row, err) -> {
            e.pushing = false;
            if (row != null) {
                e.cloudId = row.optString("id", e.cloudId);
                long at = Cloud.parseIso(row.optString("updated_at", ""));
                if (at > 0) e.updatedAt = at;
                save();
            }
            List<LookStore.Done<Entry>> later = new ArrayList<>(e.waiting);
            e.waiting.clear();
            if (row != null && !Look.canonical(sent).equals(Look.canonical(e.profile.toJson()))) {
                pushEntry(cloud, e, (x, err2) -> {
                    if (done != null) done.on(e, err2);
                    for (LookStore.Done<Entry> d : later) d.on(e, err2);
                });
                return;
            }
            if (done != null) done.on(e, err);
            for (LookStore.Done<Entry> d : later) d.on(e, err);
        });
    }

    /** Removes the entry here and marks the cloud row deleted. */
    void delete(Cloud cloud, Entry e) {
        remove(e);
        if (cloud != null && cloud.signedIn() && e.cloudId != null) cloud.removePreset(e.cloudId, null);
    }
}
