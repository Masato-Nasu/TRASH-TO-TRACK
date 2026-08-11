package dev.masatolab.trashtotrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.CancellationSignal;
import android.net.Uri;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader {
    private final Context context;
    private final LruCache<String, Bitmap> cache = new LruCache<>(80);
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public ThumbnailLoader(Context context) { this.context = context.getApplicationContext(); }

    public void load(SelectedFile file, ImageView view) {
        String key = file.key();
        view.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(iconFor(file.kind));
        if (file.kind != SelectedFile.Kind.PHOTO && file.kind != SelectedFile.Kind.VIDEO) return;
        executor.execute(() -> {
            try {
                Uri uri = file.uri;
                Bitmap bitmap;
                if (file.source == SelectedFile.Source.DEVICE_FS && "file".equalsIgnoreCase(uri.getScheme())) {
                    File raw = new File(uri.getPath());
                    bitmap = file.kind == SelectedFile.Kind.VIDEO
                            ? ThumbnailUtils.createVideoThumbnail(raw, new Size(128, 128), (CancellationSignal) null)
                            : ThumbnailUtils.createImageThumbnail(raw, new Size(128, 128), (CancellationSignal) null);
                } else {
                    bitmap = context.getContentResolver().loadThumbnail(uri, new Size(128, 128), null);
                }
                if (bitmap != null) {
                    cache.put(key, bitmap);
                    view.post(() -> {
                        if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    public void clear() { cache.evictAll(); }
    public void shutdown() { executor.shutdownNow(); }

    public static int iconFor(SelectedFile.Kind kind) {
        if (kind == SelectedFile.Kind.FOLDER) return R.drawable.ic_folder;
        if (kind == SelectedFile.Kind.PHOTO) return R.drawable.ic_photo;
        if (kind == SelectedFile.Kind.VIDEO) return R.drawable.ic_video;
        if (kind == SelectedFile.Kind.AUDIO) return R.drawable.ic_audio;
        if (kind == SelectedFile.Kind.DOCUMENT) return R.drawable.ic_document;
        return R.drawable.ic_file;
    }
}
