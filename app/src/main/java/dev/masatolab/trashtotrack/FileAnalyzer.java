package dev.masatolab.trashtotrack;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FileAnalyzer {
    private static final int MAX_TEXT_BYTES = 24 * 1024;
    private static final int MAX_OFFICE_XML_BYTES = 128 * 1024;
    private static final int MAX_TRACE_CHARS = 4200;
    private static final int ESSENCE_VISUAL_MAX = 360;

    private FileAnalyzer() {}


    public static EssenceRecord createEssence(Context context, SelectedFile file, boolean creditEligible, boolean richEssenceMode) {
        String id = UUID.randomUUID().toString();
        String trace;
        String visualPath = "";
        try {
            switch (file.kind) {
                case PHOTO: {
                    Bitmap bitmap = decodeScaledBitmap(context, file.uri, ESSENCE_VISUAL_MAX);
                    trace = bitmap == null ? "Image; visual could not be decoded." : imageTrace(bitmap);
                    if (richEssenceMode) {
                        visualPath = saveEssenceVisual(context, id, bitmap);
                    } else if (bitmap != null) {
                        try { bitmap.recycle(); } catch (Exception ignored) {}
                    }
                    break;
                }
                case VIDEO: {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    Bitmap frame = null;
                    long duration = 0L;
                    try {
                        if (file.source == SelectedFile.Source.DEVICE_FS && "file".equalsIgnoreCase(file.uri.getScheme()))
                            retriever.setDataSource(file.uri.getPath());
                        else retriever.setDataSource(context, file.uri);
                        String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (d != null) duration = Long.parseLong(d);
                        frame = retriever.getFrameAtTime(duration > 0 ? duration * 500L : -1L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } finally {
                        try { retriever.release(); } catch (Exception ignored) {}
                    }
                    if (frame != null) frame = scaleToMax(frame, ESSENCE_VISUAL_MAX);
                    trace = "Video duration " + Math.round(duration / 1000.0) + " seconds"
                            + (frame == null ? "." : "; abstract representative-frame features: " + imageTrace(frame));
                    if (richEssenceMode) {
                        visualPath = saveEssenceVisual(context, id, frame);
                    } else if (frame != null) {
                        try { frame.recycle(); } catch (Exception ignored) {}
                    }
                    break;
                }
                case AUDIO: {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    long duration = 0L;
                    String bitrate = null;
                    try {
                        if (file.source == SelectedFile.Source.DEVICE_FS && "file".equalsIgnoreCase(file.uri.getScheme()))
                            retriever.setDataSource(file.uri.getPath());
                        else retriever.setDataSource(context, file.uri);
                        String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (d != null) duration = Long.parseLong(d);
                        bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                    } finally {
                        try { retriever.release(); } catch (Exception ignored) {}
                    }
                    trace = "Audio duration " + Math.round(duration / 1000.0) + " seconds"
                            + (bitrate == null ? "." : "; bitrate about " + bitrate + " bps.");
                    break;
                }
                case DOCUMENT: {
                    String ext = file.extension();
                    if (!richEssenceMode) {
                        trace = "Document metadata only; semantic content intentionally not retained in PRIVATE MODE. Type ." + ext + ".";
                    } else if (file.mimeType.equalsIgnoreCase("application/pdf") || ext.equals("pdf")) {
                        Bitmap page = renderPdfFirstPage(context, file.uri);
                        trace = "PDF document; first page retained as a tiny visual essence when possible.";
                        visualPath = saveEssenceVisual(context, id, page);
                    } else if (ext.equals("docx") || ext.equals("xlsx") || ext.equals("pptx")) {
                        String text = readOfficeText(context, file.uri, ext);
                        trace = text.trim().isEmpty() ? "Office document with no readable text excerpt." : "Document text trace: " + text;
                    } else {
                        String text = readPlainText(context, file.uri);
                        trace = text.trim().isEmpty() ? "Document with no readable text excerpt." : "Document text trace: " + text;
                    }
                    break;
                }
                default:
                    trace = "Other file type " + file.mimeType + ", extension ." + file.extension() + ".";
            }
        } catch (Exception e) {
            trace = "File type " + file.mimeType + "; content trace unavailable.";
        }

        if (trace.length() > MAX_TRACE_CHARS) trace = trace.substring(0, MAX_TRACE_CHARS) + "…";
        long bytes = creditEligible ? file.sizeBytes : 0L;
        return new EssenceRecord(id, bytes, file.kind.name().toLowerCase(Locale.ROOT), trace,
                visualPath, System.currentTimeMillis(), file.locationLabel);
    }

    public static void discardEssence(EssenceRecord essence) {
        if (essence == null || essence.visualPath.trim().isEmpty()) return;
        try { new File(essence.visualPath).delete(); } catch (Exception ignored) {}
    }

    private static String saveEssenceVisual(Context context, String id, Bitmap source) {
        if (source == null) return "";
        Bitmap scaled = scaleToMax(source, ESSENCE_VISUAL_MAX);
        File dir = new File(context.getFilesDir(), "essence");
        if (!dir.exists() && !dir.mkdirs()) return "";
        File out = new File(dir, id + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 52, fos);
            fos.flush();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        } finally {
            if (scaled != source) scaled.recycle();
            try { source.recycle(); } catch (Exception ignored) {}
        }
    }

    private static String imageTrace(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);
        long r = 0, g = 0, b = 0, count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bitmap.getPixel(x, y);
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++;
            }
        }
        if (count == 0) count = 1;
        int rr = (int) (r / count), gg = (int) (g / count), bb = (int) (b / count);
        double brightness = (rr + gg + bb) / 765.0;
        String mood = brightness < .28 ? "dark" : brightness > .72 ? "bright" : "mid-tone";
        return "Visual " + w + "x" + h + ", average RGB(" + rr + "," + gg + "," + bb + "), " + mood + ".";
    }

    private static String readPlainText(Context context, Uri uri) throws Exception {
        try (InputStream in = openInputStream(context, uri)) {
            if (in == null) return "";
            byte[] buffer = new byte[4096];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int total = 0;
            while (total < MAX_TEXT_BYTES) {
                int n = in.read(buffer, 0, Math.min(buffer.length, MAX_TEXT_BYTES - total));
                if (n < 0) break;
                out.write(buffer, 0, n); total += n;
            }
            return cleanText(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static String readOfficeText(Context context, Uri uri, String ext) throws Exception {
        StringBuilder result = new StringBuilder();
        try (InputStream raw = openInputStream(context, uri);
             ZipInputStream zip = raw == null ? null : new ZipInputStream(raw)) {
            if (zip == null) return "";
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && result.length() < MAX_TRACE_CHARS * 2) {
                String n = entry.getName();
                boolean wanted = ext.equals("docx") && n.equals("word/document.xml")
                        || ext.equals("pptx") && n.startsWith("ppt/slides/slide") && n.endsWith(".xml")
                        || ext.equals("xlsx") && (n.equals("xl/sharedStrings.xml") || n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"));
                if (!wanted) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int total = 0;
                while (total < MAX_OFFICE_XML_BYTES) {
                    int count = zip.read(buffer, 0, Math.min(buffer.length, MAX_OFFICE_XML_BYTES - total));
                    if (count < 0) break;
                    out.write(buffer, 0, count); total += count;
                }
                String xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
                result.append(' ').append(xml.replaceAll("<[^>]+>", " ")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'"));
            }
        }
        return cleanText(result.toString());
    }

    private static Bitmap decodeScaledBitmap(Context context, Uri uri, int max) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = openInputStream(context, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        try (InputStream in = openInputStream(context, uri)) {
            Bitmap decoded = BitmapFactory.decodeStream(in, null, opts);
            return decoded == null ? null : scaleToMax(decoded, max);
        }
    }

    private static Bitmap renderPdfFirstPage(Context context, Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = openFileDescriptor(context, uri)) {
            if (pfd == null) return null;
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                if (renderer.getPageCount() < 1) return null;
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    float ratio = page.getHeight() / (float) page.getWidth();
                    int width = ESSENCE_VISUAL_MAX;
                    int height = Math.max(1, Math.round(width * ratio));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return bitmap;
                }
            }
        }
    }

    private static Bitmap scaleToMax(Bitmap bitmap, int max) {
        if (bitmap == null) return null;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (Math.max(w, h) <= max) return bitmap;
        float ratio = max / (float) Math.max(w, h);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * ratio)),
                Math.max(1, Math.round(h * ratio)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private static InputStream openInputStream(Context context, Uri uri) throws Exception {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath()));
        }
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return in;
    }

    private static ParcelFileDescriptor openFileDescriptor(Context context, Uri uri) throws Exception {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            return ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return pfd;
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (cleaned.length() > MAX_TRACE_CHARS) cleaned = cleaned.substring(0, MAX_TRACE_CHARS) + "…";
        return cleaned;
    }

    public static SelectedFile.Kind kindForMime(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return SelectedFile.Kind.PHOTO;
        if (m.startsWith("video/")) return SelectedFile.Kind.VIDEO;
        if (m.startsWith("audio/")) return SelectedFile.Kind.AUDIO;
        if (m.startsWith("text/") || m.contains("pdf") || m.contains("word") || m.contains("sheet")
                || m.contains("presentation") || m.contains("document") || m.contains("office")) return SelectedFile.Kind.DOCUMENT;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(pdf|txt|md|csv|doc|docx|xls|xlsx|ppt|pptx|rtf)$")) return SelectedFile.Kind.DOCUMENT;
        return SelectedFile.Kind.OTHER;
    }

    private static String guessMimeFromName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return "text/plain";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        return "application/octet-stream";
    }
}
