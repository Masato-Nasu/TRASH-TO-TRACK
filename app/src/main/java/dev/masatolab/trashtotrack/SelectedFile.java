package dev.masatolab.trashtotrack;

import android.net.Uri;

import java.util.Locale;

public final class SelectedFile {
    public enum Kind { FOLDER, PHOTO, VIDEO, AUDIO, DOCUMENT, OTHER }
    public enum Source { DEVICE, DEVICE_FS, DOCUMENT_PROVIDER }

    public final Uri uri;
    public final String name;
    public final String mimeType;
    public final long sizeBytes;
    public final long dateModifiedSeconds;
    public final Kind kind;
    public final Source source;
    public final boolean deletable;
    public final String locationLabel;
    public final String folderPath;
    private final boolean folderEntry;

    public SelectedFile(Uri uri, String name, String mimeType, long sizeBytes,
                        long dateModifiedSeconds, Kind kind, Source source,
                        boolean deletable, String locationLabel) {
        this(uri, name, mimeType, sizeBytes, dateModifiedSeconds, kind, source,
                deletable, locationLabel, locationLabel, false);
    }

    public SelectedFile(Uri uri, String name, String mimeType, long sizeBytes,
                        long dateModifiedSeconds, Kind kind, Source source,
                        boolean deletable, String locationLabel, String folderPath,
                        boolean folderEntry) {
        this.uri = uri;
        this.name = name == null || name.trim().isEmpty() ? "unnamed" : name;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.dateModifiedSeconds = Math.max(0L, dateModifiedSeconds);
        this.kind = kind == null ? Kind.OTHER : kind;
        this.source = source == null ? Source.DOCUMENT_PROVIDER : source;
        this.deletable = deletable;
        this.locationLabel = locationLabel == null || locationLabel.trim().isEmpty()
                ? sourceLabel(this.source) : locationLabel;
        this.folderPath = normalizeFolderPath(folderPath);
        this.folderEntry = folderEntry;
    }

    public static SelectedFile folder(String fullPath) {
        String normalized = normalizeFolderPath(fullPath);
        String name = normalized;
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash < normalized.length() - 1) name = normalized.substring(slash + 1);
        Uri uri = Uri.parse("trashtotrack-folder:///" + Uri.encode(normalized));
        return new SelectedFile(uri, name, "inode/directory", 0L, 0L,
                Kind.FOLDER, Source.DEVICE, false, "FOLDER", normalized, true);
    }

    public boolean isFolder() { return folderEntry || kind == Kind.FOLDER; }

    public String key() { return uri.toString(); }

    public String extension() {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "unknown";
    }

    public boolean earnsCredit() { return !isFolder() && deletable; }

    public static String sourceLabel(Source source) {
        if (source == Source.DEVICE || source == Source.DEVICE_FS) return "DEVICE";
        return "DOCUMENT";
    }

    public static String normalizeFolderPath(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        while (value.contains("//")) value = value.replace("//", "/");
        return value;
    }
}
