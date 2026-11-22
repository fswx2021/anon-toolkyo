package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Recent local outcomes for a specific revision of a source's search rule. */
final class ReaderSourceHealth {
    private static final long HISTORY_LIFETIME = TimeUnit.DAYS.toMillis(14);
    private static final long COOLDOWN = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_ENTRIES = 8000;
    private static final int BROKEN_RULE = -1;
    private final Map<String, Outcome> history = new LinkedHashMap<>();

    ReaderSourceHealth(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray entries = new JSONArray(json);
            for (int i = Math.max(0, entries.length() - MAX_ENTRIES); i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null || !entry.has("id")) continue;
                Outcome outcome = new Outcome(entry.optInt("revision"), entry.optInt("rank"),
                        entry.optInt("hardFailure"), entry.optInt("streak"), entry.optLong("time"));
                history.put(entry.optString("id"), outcome);
            }
        } catch (JSONException invalid) {
            history.clear();
        }
    }

    int priority(ReaderSources.Source source, long now) {
        Outcome outcome = recent(source, now);
        return outcome == null ? 0 : outcome.rank;
    }

    boolean deferred(ReaderSources.Source source, long now) {
        Outcome outcome = recent(source, now);
        return outcome != null && outcome.streak >= 2 && now - outcome.time < COOLDOWN;
    }

    boolean record(ReaderSources.Source source, boolean found, Exception failure, long now) {
        Outcome previous = recent(source, now);
        int rank = failure == null ? (found ? 2 : 1) : -1;
        int hardFailure = failure == null ? 0 : hardFailure(failure);
        int streak = hardFailure == 0 ? 0 : 1;
        if (streak > 0 && previous != null && previous.hardFailure == hardFailure) {
            streak = Math.min(2, previous.streak + 1);
        }
        history.remove(source.id);
        if (history.size() >= MAX_ENTRIES) history.remove(history.keySet().iterator().next());
        history.put(source.id, new Outcome(source.searchRevision, rank, hardFailure, streak, now));
        return true;
    }

    String serialize() {
        JSONArray entries = new JSONArray();
        for (Map.Entry<String, Outcome> item : history.entrySet()) {
            Outcome outcome = item.getValue();
            try {
                entries.put(new JSONObject().put("id", item.getKey()).put("revision", outcome.revision)
                        .put("rank", outcome.rank).put("hardFailure", outcome.hardFailure)
                        .put("streak", outcome.streak).put("time", outcome.time));
            } catch (JSONException invalid) {
                // Only strings, integers and timestamps are stored.
            }
        }
        return entries.toString();
    }

    private Outcome recent(ReaderSources.Source source, long now) {
        Outcome outcome = history.get(source.id);
        if (outcome == null || outcome.revision != source.searchRevision
                || now < outcome.time || now - outcome.time >= HISTORY_LIFETIME) return null;
        return outcome;
    }

    private static int hardFailure(Exception failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ReaderSources.HttpFailure) {
                int status = ((ReaderSources.HttpFailure) cause).status;
                return status == 404 || status == 410 || status == 401 || status == 403 ? status : 0;
            }
            if (cause instanceof ReaderSources.RuleFailure) return BROKEN_RULE;
        }
        return 0;
    }

    private static final class Outcome {
        final int revision;
        final int rank;
        final int hardFailure;
        final int streak;
        final long time;

        Outcome(int revision, int rank, int hardFailure, int streak, long time) {
            this.revision = revision;
            this.rank = rank;
            this.hardFailure = hardFailure;
            this.streak = streak;
            this.time = time;
        }
    }
}
