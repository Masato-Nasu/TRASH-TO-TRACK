package dev.masatolab.trashtotrack;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.media.MediaScannerConnection;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class GenerationService extends Service {
    public static final String ACTION_START = "dev.masatolab.trashtotrack.action.START_GENERATION";
    public static final String ACTION_CANCEL = "dev.masatolab.trashtotrack.action.CANCEL_GENERATION";
    public static final String ACTION_STATUS = "dev.masatolab.trashtotrack.action.GENERATION_STATUS";

    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_GENRE = "genre";
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_FOLDER_NAME = "folder_name";
    public static final String EXTRA_RICH_ESSENCE = "rich_essence";

    private static final String STATE_PREFS = "generation_service_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_STATUS = "status";
    private static final String KEY_MADE = "made";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_LAST_TRACK_URI = "last_track_uri";
    private static final String KEY_LAST_FOLDER_URI = "last_folder_uri";
    private static final String KEY_ERROR = "error";
    private static final String KEY_HEARTBEAT_MS = "heartbeat_ms";
    private static final long STALE_AFTER_MS = 35_000L;

    private static final String CHANNEL_ID = "track_generation";
    private static final int NOTIFICATION_ID = 4101;
    private static final int COMPLETION_NOTIFICATION_ID = 4102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final LyriaClient lyriaClient = new LyriaClient();
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private ScheduledFuture<?> heartbeatFuture;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelRequested = true;
            lyriaClient.cancelAll();
            State state = readState(this);
            if (state.running) updateRunningState("STOPPING…", state.made, state.total,
                    state.lastTrackUri, state.lastFolderUri, null);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running || readState(this).running) {
            broadcastState();
            return START_NOT_STICKY;
        }
        cancelRequested = false;

        int count = Math.max(1, intent.getIntExtra(EXTRA_COUNT, 1));
        String genre = safe(intent.getStringExtra(EXTRA_GENRE), "AUTO");
        String outputPath = SelectedFile.normalizeFolderPath(intent.getStringExtra(EXTRA_OUTPUT_PATH));
        String folderName = safe(intent.getStringExtra(EXTRA_FOLDER_NAME), outputPath.isEmpty() ? "STORAGE" : outputPath);
        boolean richEssence = intent.getBooleanExtra(EXTRA_RICH_ESSENCE, false);

        File outputDir = FilesystemRepository.resolve(outputPath);
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            writeState(false, "SAVE LOCATION ERROR", 0, count, null, null, "Save folder could not be created.");
            broadcastState();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        running = true;
        writeState(true, "PREPARING 1 / " + count + "…", 0, count, null, null, null);
        ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildProgressNotification("Preparing TRACK generation…", 0, count),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        acquireWakeLock();
        startHeartbeat();

        executor.execute(() -> runGeneration(startId, count, genre, outputPath, folderName, richEssence));
        return START_NOT_STICKY;
    }

    private void runGeneration(int startId, int count, String genre, String outputRelativePath,
                               String folderName, boolean richEssenceMode) {
        int made = 0;
        String failure = null;
        Uri newest = null;
        Uri outputFolder = null;
        try {
            String apiKey = SecureKeyStore.load(this);
            if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalStateException("Gemini API key is missing.");

            File outputDir = FilesystemRepository.resolve(outputRelativePath);
            if (!outputDir.isDirectory() && !outputDir.mkdirs()) throw new IllegalStateException("保存フォルダを作成できませんでした。");
            outputFolder = Uri.fromFile(outputDir);

            for (int i = 0; i < count; i++) {
                if (cancelRequested || Thread.currentThread().isInterrupted()) throw new IOException("Generation stopped by user. TRACK credit was not consumed for the unfinished TRACK.");
                if (BankStore.load(this).availableTracks() < 1) break;
                int displayIndex = i + 1;
                updateRunningState((richEssenceMode ? "RICH" : "PRIVATE") + " · GENERATING "
                        + displayIndex + " / " + count + "…", made, count, newest, outputFolder, null);

                BankStore.MaterialPack pack = BankStore.materialForNextTrack(this, 0);
                if (pack.records.isEmpty() || pack.consumeBytes != BankStore.TRACK_BYTES) {
                    throw new IllegalStateException("TRASH BANK material is out of sync. Credit was not consumed.");
                }
                LyriaClient.GenerationResult result = generateWithDnsRetry(apiKey, genre, pack,
                        richEssenceMode, displayIndex, count, made, newest, outputFolder);
                if (cancelRequested || Thread.currentThread().isInterrupted()) throw new IOException("Generation stopped by user. TRACK credit was not consumed for the unfinished TRACK.");

                if (result.safeEssenceFallback) {
                    updateRunningState("POLICY-SAFE ESSENCE RETRY SUCCEEDED — SAVING…",
                            made, count, newest, outputFolder, null);
                }

                Uri saved = saveTrack(result.mp3Bytes, genre, BankStore.load(this).generatedCount + 1, outputFolder);
                if (saved == null) throw new IllegalStateException("Generated MP3 could not be saved.");

                if (!BankStore.commitGeneratedTrack(this, pack)) {
                    try { if ("file".equalsIgnoreCase(saved.getScheme())) new File(saved.getPath()).delete(); } catch (Exception ignored) {}
                    throw new IllegalStateException("TRACK was generated but TRASH BANK could not commit its ESSENCE safely. The MP3 was removed and credit was not consumed.");
                }
                newest = saved;
                made++;
                updateRunningState("GENERATED " + made + " / " + count
                                + (result.safeEssenceFallback ? " · SAFE ESSENCE" : ""),
                        made, count, newest, outputFolder, null);
            }
        } catch (Exception e) {
            failure = e.getMessage();
            if (failure == null || failure.trim().isEmpty()) failure = e.getClass().getSimpleName();
        } finally {
            running = false;
            stopHeartbeat();
            releaseWakeLock();

            String finalStatus = failure == null
                    ? (made > 0 ? "REFINED " + made + " TRACK" + (made == 1 ? "" : "S") : "READY")
                    : (made > 0 ? "REFINED " + made + " · STOPPED" : "GENERATION FAILED");
            writeState(false, finalStatus, made, count, newest, outputFolder, failure);
            broadcastState();

            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            showCompletionNotification(made, count, folderName, failure);
            stopSelf(startId);
        }
    }

    private LyriaClient.GenerationResult generateWithDnsRetry(String apiKey, String genre,
                                                                BankStore.MaterialPack pack,
                                                                boolean richEssenceMode,
                                                                int trackIndex, int trackCount,
                                                                int made, Uri newest, Uri outputFolder) throws Exception {
        final int[] retrySeconds = {3, 8, 15};
        int retry = 0;
        while (true) {
            if (!hasValidatedNetwork()) {
                if (retry >= retrySeconds.length) {
                    throw new IOException("Internet connection is unavailable. The TRACK credit was not consumed. Please reconnect and try again.");
                }
                int wait = retrySeconds[retry++];
                updateRunningState("NETWORK WAIT " + retry + "/" + retrySeconds.length
                                + " · TRACK " + trackIndex + "/" + trackCount + " · " + wait + "s",
                        made, trackCount, newest, outputFolder, null);
                Thread.sleep(wait * 1000L);
                continue;
            }

            try {
                return lyriaClient.generate(apiKey, genre, pack, richEssenceMode);
            } catch (Exception e) {
                if (!isDnsResolutionFailure(e) || retry >= retrySeconds.length) throw e;
                int wait = retrySeconds[retry++];
                updateRunningState("DNS RECONNECT " + retry + "/" + retrySeconds.length
                                + " · TRACK " + trackIndex + "/" + trackCount + " · " + wait + "s",
                        made, trackCount, newest, outputFolder, null);
                Thread.sleep(wait * 1000L);
            }
        }
    }

    private void updateRunningState(String status, int made, int total, Uri lastTrack, Uri folder, String error) {
        writeState(true, status, made, total, lastTrack, folder, error);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildProgressNotification(status, made, total));
        broadcastState();
    }

    private void writeState(boolean isRunning, String status, int made, int total,
                            Uri lastTrack, Uri lastFolder, String error) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, isRunning)
                .putLong(KEY_HEARTBEAT_MS, isRunning ? System.currentTimeMillis() : 0L)
                .putString(KEY_STATUS, status == null ? "READY" : status)
                .putInt(KEY_MADE, made)
                .putInt(KEY_TOTAL, total)
                .putString(KEY_LAST_TRACK_URI, lastTrack == null ? "" : lastTrack.toString())
                .putString(KEY_LAST_FOLDER_URI, lastFolder == null ? "" : lastFolder.toString())
                .putString(KEY_ERROR, error == null ? "" : error)
                .apply();
    }

    private void broadcastState() {
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }

    public static State readState(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        boolean isRunning = p.getBoolean(KEY_RUNNING, false);
        long heartbeat = p.getLong(KEY_HEARTBEAT_MS, 0L);
        String status = p.getString(KEY_STATUS, "READY");
        String error = p.getString(KEY_ERROR, "");
        if (isRunning && (heartbeat <= 0L || System.currentTimeMillis() - heartbeat > STALE_AFTER_MS)) {
            isRunning = false;
            status = "PREVIOUS GENERATION INTERRUPTED";
            error = "A stale generation state was cleared automatically. Unfinished TRACK credit remains in TRASH BANK.";
            p.edit().putBoolean(KEY_RUNNING, false)
                    .putLong(KEY_HEARTBEAT_MS, 0L)
                    .putString(KEY_STATUS, status)
                    .putString(KEY_ERROR, error)
                    .apply();
        }
        return new State(
                isRunning,
                status,
                p.getInt(KEY_MADE, 0),
                p.getInt(KEY_TOTAL, 0),
                parseUri(p.getString(KEY_LAST_TRACK_URI, "")),
                parseUri(p.getString(KEY_LAST_FOLDER_URI, "")),
                error
        );
    }

    private static Uri parseUri(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception ignored) { return null; }
    }

    public static final class State {
        public final boolean running;
        public final String status;
        public final int made;
        public final int total;
        public final Uri lastTrackUri;
        public final Uri lastFolderUri;
        public final String error;

        State(boolean running, String status, int made, int total, Uri lastTrackUri, Uri lastFolderUri, String error) {
            this.running = running;
            this.status = status == null ? "READY" : status;
            this.made = made;
            this.total = total;
            this.lastTrackUri = lastTrackUri;
            this.lastFolderUri = lastFolderUri;
            this.error = error == null ? "" : error;
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            try {
                getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                        .putLong(KEY_HEARTBEAT_MS, System.currentTimeMillis())
                        .apply();
            } catch (Exception ignored) {}
        }, 10L, 10L, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        try { if (heartbeatFuture != null) heartbeatFuture.cancel(true); } catch (Exception ignored) {}
        heartbeatFuture = null;
    }

    @Override
    public void onDestroy() {
        cancelRequested = true;
        lyriaClient.cancelAll();
        stopHeartbeat();
        releaseWakeLock();
        if (running) {
            State state = readState(this);
            writeState(false, "GENERATION INTERRUPTED", state.made, state.total,
                    state.lastTrackUri, state.lastFolderUri,
                    "Generation service stopped. Unfinished TRACK credit remains in TRASH BANK.");
            broadcastState();
        }
        running = false;
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TRACK generation", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows TRASH TO TRACK generation progress while the screen is off.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private android.app.Notification buildProgressNotification(String status, int made, int total) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("TRASH TO TRACK")
                .setContentText(status)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (total > 0) b.setProgress(total, Math.min(made, total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void showCompletionNotification(int made, int total, String folderName, String failure) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = failure == null ? "TRACK READY" : "TRACK GENERATION STOPPED";
        String text = failure == null
                ? made + " track(s) saved to " + folderName
                : made + "/" + total + " saved · tap to check";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(COMPLETION_NOTIFICATION_ID, b.build());
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":TrackGeneration");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private boolean hasValidatedNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isDnsResolutionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnknownHostException) return true;
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("unable to resolve host") || lower.contains("no address associated with hostname")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Uri saveTrack(byte[] mp3, String genre, long sequence, Uri outputFolderUri) throws Exception {
        if (outputFolderUri == null || !"file".equalsIgnoreCase(outputFolderUri.getScheme())) {
            throw new IllegalStateException("Invalid in-app save folder.");
        }
        File dir = new File(outputFolderUri.getPath());
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("保存先フォルダを作成できませんでした。");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safeGenre = genre.replaceAll("[^A-Za-z0-9_-]", "_");
        File out = new File(dir, "TRASH_TO_TRACK_" + timestamp + "_" + sequence + "_" + safeGenre + ".mp3");
        int suffix = 1;
        while (out.exists()) {
            out = new File(dir, "TRASH_TO_TRACK_" + timestamp + "_" + sequence + "_" + safeGenre + "_" + suffix++ + ".mp3");
        }
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(mp3);
            stream.flush();
        } catch (Exception e) {
            try { out.delete(); } catch (Exception ignored) {}
            throw e;
        }
        MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
        return Uri.fromFile(out);
    }


    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

}
