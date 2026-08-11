package dev.masatolab.trashtotrack;

import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class FilesystemRepository {
    private FilesystemRepository() {}

    public static File root() {
        return Environment.getExternalStorageDirectory();
    }

    public static File resolve(String relativePath) {
        File root = root();
        String rel = SelectedFile.normalizeFolderPath(relativePath);
        File out = rel.isEmpty() ? root : new File(root, rel);
        try {
            String rootPath = root.getCanonicalPath();
            String outPath = out.getCanonicalPath();
            if (!outPath.equals(rootPath) && !outPath.startsWith(rootPath + File.separator)) return root;
        } catch (Exception ignored) {}
        return out;
    }

    public static String toRelative(File file) {
        if (file == null) return "";
        try {
            String rootPath = root().getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(rootPath)) return "";
            if (path.startsWith(rootPath + File.separator)) {
                return SelectedFile.normalizeFolderPath(path.substring(rootPath.length() + 1));
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static List<SelectedFile> listDirectory(String relativePath) {
        List<SelectedFile> out = new ArrayList<>();
        File dir = resolve(relativePath);
        File[] children;
        try { children = dir.listFiles(); } catch (Exception e) { children = null; }
        if (children == null) return out;
        Arrays.sort(children, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        String parentRel = toRelative(dir);
        for (File child : children) {
            try {
                if (!child.exists() || !child.canRead()) continue;
                String childRel = toRelative(child);
                if (child.isDirectory()) {
                    out.add(new SelectedFile(Uri.fromFile(child), child.getName(), "inode/directory",
                            0L, Math.max(0L, child.lastModified() / 1000L),
                            SelectedFile.Kind.FOLDER, SelectedFile.Source.DEVICE_FS, child.canWrite(),
                            parentRel.isEmpty() ? "STORAGE" : parentRel,
                            childRel, true));
                    continue;
                }
                String mime = mimeFor(child.getName());
                SelectedFile.Kind kind = FileAnalyzer.kindForMime(mime, child.getName());
                out.add(new SelectedFile(Uri.fromFile(child), child.getName(), mime,
                        Math.max(0L, child.length()), Math.max(0L, child.lastModified() / 1000L),
                        kind, SelectedFile.Source.DEVICE_FS, child.canWrite(),
                        parentRel.isEmpty() ? "STORAGE" : parentRel,
                        parentRel, false));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static boolean isProtectedRootFolder(String relativePath) {
        String rel = SelectedFile.normalizeFolderPath(relativePath);
        if (rel.contains("/")) return false;
        switch (rel.toLowerCase(Locale.ROOT)) {
            case "android":
            case "dcim":
            case "download":
            case "documents":
            case "movies":
            case "music":
            case "pictures":
            case "podcasts":
            case "ringtones":
            case "alarms":
            case "notifications":
                return true;
            default:
                return false;
        }
    }

    public static boolean createFolder(String parentRelativePath, String folderName) {
        String safe = folderName == null ? "" : folderName.trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..") || safe.contains("/") || safe.contains("\\")) return false;
        File parent = resolve(parentRelativePath);
        File dir = new File(parent, safe);
        return dir.isDirectory() || dir.mkdirs();
    }

    public static boolean isEmptyFolder(String relativePath) {
        String rel = SelectedFile.normalizeFolderPath(relativePath);
        if (rel.isEmpty() || isProtectedRootFolder(rel)) return false;
        File dir = resolve(rel);
        try {
            if (!dir.isDirectory()) return false;
            File[] children = dir.listFiles();
            return children != null && children.length == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean deleteEmptyFolder(String relativePath) {
        String rel = SelectedFile.normalizeFolderPath(relativePath);
        if (rel.isEmpty() || isProtectedRootFolder(rel)) return false;
        File dir = resolve(rel);
        try {
            if (!dir.isDirectory()) return false;
            File[] children = dir.listFiles();
            if (children == null || children.length != 0) return false;
            return dir.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String mimeFor(String name) {
        String n = name == null ? "" : name;
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) {
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
