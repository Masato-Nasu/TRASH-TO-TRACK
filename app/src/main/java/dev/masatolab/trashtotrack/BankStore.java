package dev.masatolab.trashtotrack;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent TRASH BANK.
 *
 * One important invariant is enforced from v0.6.0 onward:
 * remaining ESSENCE bytes track remaining credit bytes. A successfully saved
 * TRACK atomically consumes both 25 MB of credit and the exact ESSENCE slice
 * that created it. Failed/cancelled generation consumes neither.
 */
public final class BankStore {
    public static final long TRACK_BYTES = 25L * 1024L * 1024L;
    private static final String FILE_NAME = "trash_bank.json";

    private BankStore() {}

    public static synchronized State load(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new State(0L, 0L, new ArrayList<>());
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            List<EssenceRecord> records = new ArrayList<>();
            JSONArray arr = root.optJSONArray("records");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) {
                        EssenceRecord record = EssenceRecord.fromJson(item);
                        if (record.sourceBytes > 0L) records.add(record);
                    }
                }
            }
            List<PendingDelete> pendingDeletes = new ArrayList<>();
            JSONArray pendingArr = root.optJSONArray("pendingDeletes");
            if (pendingArr != null) {
                for (int i = 0; i < pendingArr.length(); i++) {
                    JSONObject item = pendingArr.optJSONObject(i);
                    PendingDelete pending = PendingDelete.fromJson(item);
                    if (pending != null && pending.essence != null && pending.essence.sourceBytes > 0L) {
                        pendingDeletes.add(pending);
                    }
                }
            }
            return new State(root.optLong("creditBytes", 0L),
                    root.optLong("generatedCount", 0L), records, pendingDeletes);
        } catch (Exception e) {
            return new State(0L, 0L, new ArrayList<>());
        }
    }

    /**
     * Journal one deletion BEFORE touching the source file. No credit is granted
     * yet. If the app dies after the source is deleted but before commit, the
     * journal lets the next launch recover the missing credit safely.
     */
    public static synchronized String preparePendingDelete(Context context, String sourcePath,
                                                           EssenceRecord essence) {
        if (sourcePath == null || sourcePath.trim().isEmpty() || essence == null
                || essence.sourceBytes <= 0L || essence.id == null || essence.id.trim().isEmpty()) {
            return null;
        }
        State state = load(context);
        String txId = essence.id;
        for (PendingDelete pending : state.pendingDeletes) {
            if (txId.equals(pending.txId)) return null;
        }
        state.pendingDeletes.add(new PendingDelete(txId, sourcePath, essence,
                System.currentTimeMillis()));
        return save(context, state) ? txId : null;
    }

    /** Commit a journaled delete only after the source file is actually gone. */
    public static synchronized boolean commitPendingDelete(Context context, String txId) {
        if (txId == null || txId.trim().isEmpty()) return false;
        State state = load(context);
        PendingDelete found = null;
        for (PendingDelete pending : state.pendingDeletes) {
            if (txId.equals(pending.txId)) { found = pending; break; }
        }
        if (found == null || found.essence == null || found.essence.sourceBytes <= 0L) return false;
        File source = new File(found.sourcePath);
        if (source.exists()) return false;

        State next = copyState(state);
        PendingDelete target = null;
        for (PendingDelete pending : next.pendingDeletes) {
            if (txId.equals(pending.txId)) { target = pending; break; }
        }
        if (target == null) return false;
        next.pendingDeletes.remove(target);
        next.records.add(target.essence);
        next.creditBytes += target.essence.sourceBytes;
        return save(context, next);
    }

    /** Cancel a journal if the source was not deleted. */
    public static synchronized boolean cancelPendingDelete(Context context, String txId) {
        if (txId == null || txId.trim().isEmpty()) return false;
        State state = load(context);
        PendingDelete found = null;
        for (PendingDelete pending : state.pendingDeletes) {
            if (txId.equals(pending.txId)) { found = pending; break; }
        }
        if (found == null) return true;

        State next = copyState(state);
        PendingDelete target = null;
        for (PendingDelete pending : next.pendingDeletes) {
            if (txId.equals(pending.txId)) { target = pending; break; }
        }
        if (target == null) return true;
        next.pendingDeletes.remove(target);
        if (!save(context, next)) return false;
        FileAnalyzer.discardEssence(target.essence);
        return true;
    }

    /**
     * Crash recovery for journaled deletes. Existing source = deletion never
     * completed, so cancel. Missing source = deletion completed, so grant its
     * credit/ESSENCE exactly once.
     *
     * @return number of deleted items whose credit was recovered.
     */
    public static synchronized int recoverPendingDeletes(Context context) {
        State state = load(context);
        if (state.pendingDeletes.isEmpty()) return 0;

        State next = new State(state.creditBytes, state.generatedCount,
                new ArrayList<>(state.records), new ArrayList<>());
        List<EssenceRecord> toDiscard = new ArrayList<>();
        int recovered = 0;
        for (PendingDelete pending : state.pendingDeletes) {
            if (pending == null || pending.essence == null || pending.sourcePath == null) continue;
            File source = new File(pending.sourcePath);
            if (source.exists()) {
                // Source survived: do not grant credit.
                toDiscard.add(pending.essence);
            } else {
                // Source is gone: complete the interrupted commit.
                next.records.add(pending.essence);
                next.creditBytes += pending.essence.sourceBytes;
                recovered++;
            }
        }
        if (!save(context, next)) return 0;
        for (EssenceRecord record : toDiscard) FileAnalyzer.discardEssence(record);
        return recovered;
    }

    /**
     * v0.6.0 migration/repair. Older builds deducted TRACK credit but left the
     * corresponding ESSENCE behind. Trim those already-spent ESSENCE bytes so
     * old deleted material cannot keep leaking into future TRACKS.
     */
    public static synchronized void reconcileLegacyState(Context context) {
        State state = load(context);
        state.records.sort(Comparator
                .comparingLong((EssenceRecord r) -> r.createdAt)
                .thenComparing(r -> r.id == null ? "" : r.id));

        long target = Math.max(0L, state.creditBytes);
        long total = sumBytes(state.records);
        boolean changed = false;

        if (total > target) {
            long excess = total - target;
            List<EssenceRecord> kept = new ArrayList<>();
            for (EssenceRecord record : state.records) {
                if (excess <= 0L) {
                    kept.add(record);
                    continue;
                }
                if (record.sourceBytes <= excess) {
                    excess -= record.sourceBytes;
                    FileAnalyzer.discardEssence(record);
                    changed = true;
                } else {
                    long remainingBytes = record.sourceBytes - excess;
                    kept.add(copyWithBytes(record, remainingBytes));
                    excess = 0L;
                    changed = true;
                }
            }
            state.records.clear();
            state.records.addAll(kept);
        } else if (total < target) {
            // Preserve valid legacy credit even if an older build lost its record.
            long missing = target - total;
            state.records.add(new EssenceRecord(
                    "legacy-credit-" + System.currentTimeMillis(),
                    missing,
                    "other",
                    "Legacy TRASH BANK credit; source content is unavailable. Use only data volume as an abstract musical cue.",
                    "",
                    System.currentTimeMillis(),
                    "LOCAL"));
            changed = true;
        }

        if (changed) save(context, state);
    }

    /**
     * Permanently removes locally retained rich ESSENCE from an existing bank.
     * Credit is preserved. PRIVATE MODE keeps only non-semantic measurements.
     */
    public static synchronized void sanitizeForPrivateMode(Context context) {
        State state = load(context);
        List<EssenceRecord> safe = new ArrayList<>();
        List<PendingDelete> safePending = new ArrayList<>();
        List<EssenceRecord> visualsToDiscard = new ArrayList<>();
        boolean changed = false;

        for (EssenceRecord record : state.records) {
            EssenceRecord sanitized = privateSafeCopy(record);
            if (!samePrivacyPayload(record, sanitized)) changed = true;
            if (record.visualPath != null && !record.visualPath.trim().isEmpty()) visualsToDiscard.add(record);
            safe.add(sanitized);
        }
        for (PendingDelete pending : state.pendingDeletes) {
            if (pending == null || pending.essence == null) continue;
            EssenceRecord sanitized = privateSafeCopy(pending.essence);
            if (!samePrivacyPayload(pending.essence, sanitized)) changed = true;
            if (pending.essence.visualPath != null && !pending.essence.visualPath.trim().isEmpty()) {
                visualsToDiscard.add(pending.essence);
            }
            safePending.add(new PendingDelete(pending.txId, pending.sourcePath,
                    sanitized, pending.preparedAt));
        }

        if (!changed) return;
        if (!save(context, new State(state.creditBytes, state.generatedCount, safe, safePending))) return;
        // Remove rich local visuals only after the sanitized BANK state is durable.
        for (EssenceRecord record : visualsToDiscard) FileAnalyzer.discardEssence(record);
    }

    private static EssenceRecord privateSafeCopy(EssenceRecord record) {
        String kind = record.kind == null ? "other" : record.kind;
        String trace = record.trace == null ? "" : record.trace;
        String safeTrace;
        if ("photo".equals(kind) || "video".equals(kind) || "audio".equals(kind)) {
            safeTrace = trace;
        } else if ("document".equals(kind)) {
            safeTrace = "Document metadata only; semantic content removed for PRIVATE MODE.";
        } else {
            safeTrace = "Non-semantic file metadata retained for PRIVATE MODE.";
        }
        return new EssenceRecord(record.id, record.sourceBytes, kind, safeTrace,
                "", record.createdAt, "LOCAL");
    }

    private static boolean samePrivacyPayload(EssenceRecord a, EssenceRecord b) {
        if (a == null || b == null) return false;
        return safeEquals(a.trace, b.trace)
                && safeEquals(a.visualPath, b.visualPath)
                && safeEquals(a.sourceTag, b.sourceTag)
                && safeEquals(a.kind, b.kind);
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }


    /** Build exactly one 25 MB material slice without consuming it yet. */
    public static synchronized MaterialPack materialForNextTrack(Context context, int sequenceOffset) {
        reconcileLegacyState(context);
        State state = load(context);
        if (state.creditBytes < TRACK_BYTES) {
            return new MaterialPack(new ArrayList<>(), new ArrayList<>(),
                    state.generatedCount + sequenceOffset, 0L);
        }

        List<EssenceRecord> ordered = new ArrayList<>(state.records);
        ordered.sort(Comparator
                .comparingLong((EssenceRecord r) -> r.createdAt)
                .thenComparing(r -> r.id == null ? "" : r.id));

        List<EssenceRecord> slices = new ArrayList<>();
        List<Allocation> allocations = new ArrayList<>();
        long remaining = TRACK_BYTES;
        for (EssenceRecord record : ordered) {
            if (remaining <= 0L) break;
            if (record.sourceBytes <= 0L) continue;
            long take = Math.min(record.sourceBytes, remaining);
            slices.add(copyWithBytes(record, take));
            allocations.add(new Allocation(record.id, take));
            remaining -= take;
        }

        if (remaining > 0L) {
            // Should only happen with damaged legacy state; do not spend credit.
            return new MaterialPack(new ArrayList<>(), new ArrayList<>(),
                    state.generatedCount + sequenceOffset, 0L);
        }
        return new MaterialPack(slices, allocations,
                state.generatedCount + sequenceOffset, TRACK_BYTES);
    }

    /**
     * Commit only after the MP3 has been written successfully. This is the
     * single point where a TRACK consumes both credit and its ESSENCE.
     */
    public static synchronized boolean commitGeneratedTrack(Context context, MaterialPack pack) {
        if (pack == null || pack.consumeBytes != TRACK_BYTES || pack.allocations.isEmpty()) return false;
        State state = load(context);
        if (state.creditBytes < TRACK_BYTES) return false;

        Map<String, Long> remainingById = new LinkedHashMap<>();
        for (Allocation allocation : pack.allocations) {
            if (allocation.id == null || allocation.bytes <= 0L) continue;
            remainingById.put(allocation.id,
                    remainingById.getOrDefault(allocation.id, 0L) + allocation.bytes);
        }

        List<EssenceRecord> next = new ArrayList<>();
        List<EssenceRecord> fullyConsumed = new ArrayList<>();
        long actuallyConsumed = 0L;
        for (EssenceRecord record : state.records) {
            long requested = remainingById.getOrDefault(record.id, 0L);
            if (requested <= 0L) {
                next.add(record);
                continue;
            }
            long use = Math.min(record.sourceBytes, requested);
            actuallyConsumed += use;
            long left = record.sourceBytes - use;
            long stillRequested = requested - use;
            if (stillRequested > 0L) remainingById.put(record.id, stillRequested);
            else remainingById.remove(record.id);

            if (left > 0L) next.add(copyWithBytes(record, left));
            else fullyConsumed.add(record);
        }

        if (actuallyConsumed != TRACK_BYTES || !remainingById.isEmpty()) return false;

        State committed = new State(state.creditBytes - TRACK_BYTES,
                state.generatedCount + 1L, next, new ArrayList<>(state.pendingDeletes));
        if (!save(context, committed)) return false;

        // Delete retained tiny visuals only after the new BANK state is durable.
        for (EssenceRecord record : fullyConsumed) FileAnalyzer.discardEssence(record);
        return true;
    }


    private static EssenceRecord copyWithBytes(EssenceRecord record, long bytes) {
        return new EssenceRecord(record.id, bytes, record.kind, record.trace,
                record.visualPath, record.createdAt, record.sourceTag);
    }

    private static long sumBytes(List<EssenceRecord> records) {
        long sum = 0L;
        for (EssenceRecord record : records) {
            if (record != null && record.sourceBytes > 0L) {
                long next = sum + record.sourceBytes;
                if (next < sum) return Long.MAX_VALUE;
                sum = next;
            }
        }
        return sum;
    }

    private static boolean save(Context context, State state) {
        File target = new File(context.getFilesDir(), FILE_NAME);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("creditBytes", Math.max(0L, state.creditBytes));
            root.put("generatedCount", Math.max(0L, state.generatedCount));
            JSONArray arr = new JSONArray();
            for (EssenceRecord record : state.records) {
                if (record != null && record.sourceBytes > 0L) arr.put(record.toJson());
            }
            root.put("records", arr);
            JSONArray pendingArr = new JSONArray();
            for (PendingDelete pending : state.pendingDeletes) {
                if (pending != null && pending.essence != null && pending.essence.sourceBytes > 0L) {
                    pendingArr.put(pending.toJson());
                }
            }
            root.put("pendingDeletes", pendingArr);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnavailable) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ignored) {
            try { temp.delete(); } catch (Exception ignoredAgain) {}
            return false;
        }
    }


    private static State copyState(State state) {
        return new State(state.creditBytes, state.generatedCount,
                new ArrayList<>(state.records), new ArrayList<>(state.pendingDeletes));
    }

    public static final class PendingDelete {
        public final String txId;
        public final String sourcePath;
        public final EssenceRecord essence;
        public final long preparedAt;

        PendingDelete(String txId, String sourcePath, EssenceRecord essence, long preparedAt) {
            this.txId = txId == null ? "" : txId;
            this.sourcePath = sourcePath == null ? "" : sourcePath;
            this.essence = essence;
            this.preparedAt = preparedAt;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("txId", txId);
                o.put("sourcePath", sourcePath);
                o.put("preparedAt", preparedAt);
                if (essence != null) o.put("essence", essence.toJson());
            } catch (Exception ignored) {}
            return o;
        }

        static PendingDelete fromJson(JSONObject o) {
            if (o == null) return null;
            JSONObject essenceJson = o.optJSONObject("essence");
            EssenceRecord essence = essenceJson == null ? null : EssenceRecord.fromJson(essenceJson);
            String txId = o.optString("txId", essence == null ? "" : essence.id);
            String sourcePath = o.optString("sourcePath", "");
            if (txId.isEmpty() || sourcePath.isEmpty() || essence == null) return null;
            return new PendingDelete(txId, sourcePath, essence,
                    o.optLong("preparedAt", System.currentTimeMillis()));
        }
    }

    public static final class State {
        public long creditBytes;
        public long generatedCount;
        public final List<EssenceRecord> records;
        public final List<PendingDelete> pendingDeletes;

        State(long creditBytes, long generatedCount, List<EssenceRecord> records) {
            this(creditBytes, generatedCount, records, new ArrayList<>());
        }

        State(long creditBytes, long generatedCount, List<EssenceRecord> records,
              List<PendingDelete> pendingDeletes) {
            this.creditBytes = Math.max(0L, creditBytes);
            this.generatedCount = Math.max(0L, generatedCount);
            this.records = records == null ? new ArrayList<>() : records;
            this.pendingDeletes = pendingDeletes == null ? new ArrayList<>() : pendingDeletes;
        }

        public int availableTracks() {
            return (int) Math.min(9999L, creditBytes / TRACK_BYTES);
        }
    }

    public static final class Allocation {
        public final String id;
        public final long bytes;
        Allocation(String id, long bytes) {
            this.id = id;
            this.bytes = bytes;
        }
    }

    public static final class MaterialPack {
        public final List<EssenceRecord> records;
        public final List<Allocation> allocations;
        public final long sequence;
        public final long consumeBytes;

        MaterialPack(List<EssenceRecord> records, List<Allocation> allocations,
                     long sequence, long consumeBytes) {
            this.records = records;
            this.allocations = allocations;
            this.sequence = sequence;
            this.consumeBytes = consumeBytes;
        }
    }
}
