package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Small bootstrap compatible with the standard gradlew scripts.
 * It reads gradle-wrapper.properties, downloads the declared distribution,
 * unpacks it under ~/.gradle/wrapper/dists, then delegates to bin/gradle(.bat).
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        File project = new File(System.getProperty("user.dir"));
        File propsFile = new File(project, "gradle/wrapper/gradle-wrapper.properties");
        if (!propsFile.isFile()) throw new FileNotFoundException(propsFile.getAbsolutePath());
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) { props.load(in); }
        String urlText = props.getProperty("distributionUrl");
        if (urlText == null || urlText.trim().isEmpty()) throw new IllegalStateException("distributionUrl missing");
        URL url = new URL(urlText.trim());
        String fileName = new File(url.getPath()).getName();
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        File base = new File(System.getProperty("user.home"), ".gradle/wrapper/dists/" + baseName + "/" + shortHash(urlText));
        File marker = new File(base, ".ready");
        File gradleHome = findGradleHome(base);
        if (!marker.isFile() || gradleHome == null) {
            if (!base.exists() && !base.mkdirs()) throw new IOException("Cannot create " + base);
            File zip = new File(base, fileName);
            if (!zip.isFile()) download(url, zip);
            unzip(zip, base);
            gradleHome = findGradleHome(base);
            if (gradleHome == null) throw new IOException("Gradle distribution did not unpack correctly");
            marker.createNewFile();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        File exe = new File(gradleHome, windows ? "bin/gradle.bat" : "bin/gradle");
        List<String> cmd = new ArrayList<String>();
        if (windows) { cmd.add("cmd.exe"); cmd.add("/d"); cmd.add("/c"); }
        cmd.add(exe.getAbsolutePath());
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(project);
        pb.inheritIO();
        int code = pb.start().waitFor();
        System.exit(code);
    }

    private static void download(URL initial, File out) throws Exception {
        System.out.println("Downloading " + initial);
        URL current = initial;
        for (int redirects = 0; redirects < 8; redirects++) {
            URLConnection raw = current.openConnection();
            raw.setConnectTimeout(15000); raw.setReadTimeout(30000);
            if (raw instanceof HttpURLConnection) {
                HttpURLConnection h = (HttpURLConnection) raw;
                h.setInstanceFollowRedirects(false);
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = h.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    current = new URL(current, location); h.disconnect(); continue;
                }
                if (code >= 400) throw new IOException("HTTP " + code + " downloading Gradle");
            }
            File tmp = new File(out.getParentFile(), out.getName() + ".part");
            try (InputStream in = raw.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536]; int n; long total = 0; long next = 10L * 1024L * 1024L;
                while ((n = in.read(buf)) >= 0) {
                    os.write(buf, 0, n); total += n;
                    if (total >= next) { System.out.println("  " + (total / (1024 * 1024)) + " MB"); next += 10L * 1024L * 1024L; }
                }
            }
            if (!tmp.renameTo(out)) { Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return;
        }
        throw new IOException("Too many redirects downloading Gradle");
    }

    private static void unzip(File zip, File dir) throws Exception {
        System.out.println("Unpacking " + zip.getName());
        String root = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dir, e.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new IOException("Unsafe zip entry: " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile(); if (!parent.exists()) parent.mkdirs();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = zin.read(buf)) >= 0) os.write(buf, 0, n);
                }
                if (e.getName().endsWith("/bin/gradle")) out.setExecutable(true);
            }
        }
    }

    private static File findGradleHome(File base) {
        File[] children = base.listFiles(); if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("gradle-") && new File(child, "bin").isDirectory()) return child;
        }
        return null;
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(value.getBytes("UTF-8"));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) s.append(String.format("%02x", d[i]));
        return s.toString();
    }
}
