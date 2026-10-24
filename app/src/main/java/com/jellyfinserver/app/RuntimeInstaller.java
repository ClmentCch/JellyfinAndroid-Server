package com.jellyfinserver.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * One-shot installation of the Linux runtime shipped in the APK assets:
 * the proot helpers and the Debian rootfs containing Jellyfin.
 */
public final class RuntimeInstaller {

    public interface Listener {
        void onProgress(int percent, String message);
    }

    private static final String[] PROOT_FILES = {
            "proot", "loader", "libtalloc.so.2", "libandroid-shmem.so"
    };
    private static final int BUFFER_SIZE = 64 * 1024;
    /** Progress reserved for the rootfs extraction (the rest is proot + checks). */
    private static final int ROOTFS_PROGRESS_MAX = 96;

    private RuntimeInstaller() {
    }

    public static void install(Context context, Listener listener) throws IOException {
        File rootfs = AppConfig.rootfsDir(context);
        File bin = AppConfig.binDir(context);
        File config = AppConfig.configDir(context);
        File cache = AppConfig.cacheDir(context);
        File tmp = AppConfig.tmpDir(context);

        ensureDirectory(bin);
        ensureDirectory(config);
        ensureDirectory(cache);
        ensureDirectory(tmp);

        long usable = context.getFilesDir().getUsableSpace();
        if (usable < AppConfig.INSTALL_SPACE_REQUIRED) {
            throw new IOException("Espace libre insuffisant : "
                    + (usable / (1024 * 1024)) + " Mo disponibles, "
                    + (AppConfig.INSTALL_SPACE_REQUIRED / (1024 * 1024)) + " Mo requis.");
        }

        notifyProgress(listener, 1, "Installation de proot…");
        installProotFiles(context);

        notifyProgress(listener, 3, "Vérification de l'archive…");
        String expectedDigest = readAssetText(context, AppConfig.ASSET_ROOTFS_SHA256).trim();
        if (expectedDigest.isEmpty()) {
            throw new IOException("Empreinte de l'archive introuvable.");
        }

        if (rootfs.exists()) {
            deleteRecursively(rootfs);
        }
        ensureDirectory(rootfs);

        MessageDigest digest = newSha256();
        long totalBytes = assetLength(context, AppConfig.ASSET_ROOTFS);
        ProgressThrottle throttle = new ProgressThrottle(listener);
        try (InputStream asset = context.getAssets().open(AppConfig.ASSET_ROOTFS);
             DigestInputStream digestStream = new DigestInputStream(asset, digest)) {
            TarExtractor.extractTarGz(digestStream, totalBytes, rootfs, (bytesRead, total, entry) -> {
                int percent = total > 0
                        ? (int) Math.min(ROOTFS_PROGRESS_MAX, 4 + bytesRead * ROOTFS_PROGRESS_MAX / total)
                        : 4;
                throttle.report(percent, "Extraction du système Linux…");
            });
        } catch (IOException error) {
            deleteRecursively(rootfs);
            throw error;
        }

        notifyProgress(listener, ROOTFS_PROGRESS_MAX + 1, "Vérification de l'intégrité…");
        String actualDigest = toHex(digest.digest());
        if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
            deleteRecursively(rootfs);
            throw new IOException("Archive corrompue (empreinte SHA-256 invalide).");
        }

        File marker = AppConfig.installedMarker(context);
        try (OutputStream output = new FileOutputStream(marker)) {
            output.write(AppConfig.SERVER_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        notifyProgress(listener, 100, "Installation terminée.");
    }

    private static void installProotFiles(Context context) throws IOException {
        File bin = AppConfig.binDir(context);
        ensureDirectory(bin);
        AssetManager assets = context.getAssets();
        for (String name : PROOT_FILES) {
            File target = new File(bin, name);
            try (InputStream input = assets.open(AppConfig.ASSET_PROOT_DIR + "/" + name);
                 OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            if (!target.setExecutable(true, true)) {
                throw new IOException("Impossible de rendre " + name + " exécutable.");
            }
            target.setReadable(true, true);
        }
    }

    private static long assetLength(Context context, String assetName) {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(assetName)) {
            return descriptor.getLength();
        } catch (IOException error) {
            return -1;
        }
    }

    private static String readAssetText(Context context, String assetName) throws IOException {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 indisponible", error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Impossible de créer " + directory.getAbsolutePath());
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void notifyProgress(Listener listener, int percent, String message) {
        if (listener != null) {
            listener.onProgress(percent, message);
        }
    }

    private static final class ProgressThrottle {
        private final Listener listener;
        private int lastPercent = -1;
        private long lastReport;

        ProgressThrottle(Listener listener) {
            this.listener = listener;
        }

        void report(int percent, String message) {
            long now = System.currentTimeMillis();
            if (percent == lastPercent || (now - lastReport < 400 && percent != 100)) {
                return;
            }
            lastPercent = percent;
            lastReport = now;
            notifyProgress(listener, percent, message);
        }
    }
}
