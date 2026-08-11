package dev.masatolab.trashtotrack;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Direct in-app filesystem move used with MANAGE_EXTERNAL_STORAGE. */
public final class FileMover {
    private FileMover() {}

    public static Uri moveDirect(Context context, SelectedFile source, String destinationRelativePath) throws Exception {
        if (source == null || source.source != SelectedFile.Source.DEVICE_FS
                || !"file".equalsIgnoreCase(source.uri.getScheme())) {
            throw new IllegalStateException("This item is not available to the in-app file manager.");
        }

        File destinationDir = FilesystemRepository.resolve(destinationRelativePath);
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            throw new IllegalStateException("Destination folder could not be created: " + destinationRelativePath);
        }

        if (source.isFolder() && FilesystemRepository.isProtectedRootFolder(source.folderPath)) {
            throw new IllegalStateException("Protected top-level folder cannot be moved: " + source.name);
        }

        File original = new File(source.uri.getPath());
        if (!original.exists()) throw new IllegalStateException("Source not found: " + source.name);

        String originalCanonical = original.getCanonicalPath();
        String destinationCanonical = destinationDir.getCanonicalPath();
        File originalParent = original.getParentFile();
        if (originalParent != null && destinationCanonical.equals(originalParent.getCanonicalPath())) {
            throw new IllegalStateException("Already in this folder: " + source.name);
        }
        if (original.isDirectory() && (destinationCanonical.equals(originalCanonical)
                || destinationCanonical.startsWith(originalCanonical + File.separator))) {
            throw new IllegalStateException("A folder cannot be moved inside itself: " + source.name);
        }

        File destination = original.isDirectory()
                ? uniqueDirectory(destinationDir, source.name)
                : uniqueFile(destinationDir, source.name);

        try {
            Files.move(original.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailed) {
            try {
                Files.move(original.toPath(), destination.toPath());
            } catch (Exception moveFailed) {
                if (original.isDirectory()) {
                    copyDirectory(original, destination);
                    if (!deleteRecursively(original)) {
                        deleteRecursively(destination);
                        throw new IllegalStateException("Source folder could not be removed after copy: " + source.name);
                    }
                } else {
                    copyFile(original, destination);
                    if (!original.delete()) {
                        destination.delete();
                        throw new IllegalStateException("Source could not be removed after copy: " + source.name);
                    }
                }
            }
        }

        scanTree(context, destination);
        MediaScannerConnection.scanFile(context, new String[]{originalCanonical}, null, null);
        return Uri.fromFile(destination);
    }

    private static void copyFile(File source, File destination) throws Exception {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
            out.flush();
        }
        destination.setLastModified(source.lastModified());
    }

    private static void copyDirectory(File source, File destination) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IllegalStateException("Could not create folder: " + destination.getName());
        }
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File out = new File(destination, child.getName());
            if (child.isDirectory()) copyDirectory(child, out); else copyFile(child, out);
        }
    }

    private static boolean deleteRecursively(File file) {
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) ok &= deleteRecursively(child);
        }
        return file.delete() && ok;
    }

    private static void scanTree(Context context, File root) {
        List<String> paths = new ArrayList<>();
        collectFiles(root, paths);
        if (!paths.isEmpty()) MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null, null);
    }

    private static void collectFiles(File file, List<String> paths) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            paths.add(file.getAbsolutePath());
            return;
        }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) collectFiles(child, paths);
    }

    private static File uniqueFile(File dir, String originalName) {
        File candidate = new File(dir, originalName);
        if (!candidate.exists()) return candidate;
        String base = originalName;
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            base = originalName.substring(0, dot);
            ext = originalName.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "_" + originalName);
    }

    private static File uniqueDirectory(File dir, String originalName) {
        File candidate = new File(dir, originalName);
        if (!candidate.exists()) return candidate;
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, originalName + " (" + i + ")");
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "_" + originalName);
    }
}
