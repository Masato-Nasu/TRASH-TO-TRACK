package dev.masatolab.trashtotrack;

import org.json.JSONObject;

public final class EssenceRecord {
    public final String id;
    public final long sourceBytes;
    public final String kind;
    public final String trace;
    public final String visualPath;
    public final long createdAt;
    public final String sourceTag;

    public EssenceRecord(String id, long sourceBytes, String kind, String trace,
                         String visualPath, long createdAt, String sourceTag) {
        this.id = id;
        this.sourceBytes = Math.max(0L, sourceBytes);
        this.kind = kind == null ? "other" : kind;
        this.trace = trace == null ? "" : trace;
        this.visualPath = visualPath == null ? "" : visualPath;
        this.createdAt = createdAt;
        this.sourceTag = sourceTag == null ? "DEVICE" : sourceTag;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("sourceBytes", sourceBytes);
            o.put("kind", kind);
            o.put("trace", trace);
            o.put("visualPath", visualPath);
            o.put("createdAt", createdAt);
            o.put("sourceTag", sourceTag);
        } catch (Exception ignored) {}
        return o;
    }

    public static EssenceRecord fromJson(JSONObject o) {
        return new EssenceRecord(
                o.optString("id"),
                o.optLong("sourceBytes"),
                o.optString("kind", "other"),
                o.optString("trace", ""),
                o.optString("visualPath", ""),
                o.optLong("createdAt", System.currentTimeMillis()),
                o.optString("sourceTag", "DEVICE")
        );
    }
}
