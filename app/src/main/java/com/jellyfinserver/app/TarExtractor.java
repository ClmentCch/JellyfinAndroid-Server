package com.jellyfinserver.app;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Extracts the gzipped tar archive that holds the Linux rootfs.
 *
 * <p>Symlinks, hardlinks and POSIX permissions are recreated, which the server
 * needs because the Debian userland relies on merged-usr symlinks and on the
 * executable bits of Jellyfin and ffmpeg. The archive is streamed, so the app
 * never keeps a second copy of the 190 MiB asset.</p>
 */
public final class TarExtractor {

    public interface ProgressCallback {
        void onProgress(long bytesRead, long totalBytes, String entryName);
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private TarExtractor() {
    }

    public static void extractTarGz(InputStream compressed, long totalBytes, File destination,
                                    ProgressCallback callback) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination.getAbsolutePath());
        }

        List<File> directories = new ArrayList<>();
        List<Integer> directoryModes = new ArrayList<>();
        CountingInputStream counting = new CountingInputStream(compressed);

        try (TarArchiveInputStream tar =
                     new TarArchiveInputStream(new GZIPInputStream(counting, BUFFER_SIZE), BUFFER_SIZE)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = normalize(entry.getName());
                if (name == null) {
                    continue;
                }
                File target = new File(destination, name);
                if (!isInside(destination, target)) {
                    throw new IOException("Archive entry escapes the destination: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Cannot create " + target.getAbsolutePath());
                    }
                    directories.add(target);
                    directoryModes.add(entry.getMode());
                } else if (entry.isSymbolicLink()) {
                    ensureParent(target);
                    deleteIfExists(target);
                    Files.createSymbolicLink(target.toPath(), Paths.get(entry.getLinkName()));
                } else if (entry.isLink()) {
                    ensureParent(target);
                    deleteIfExists(target);
                    File linkTarget = new File(destination, normalize(entry.getLinkName()));
                    try {
                        Files.createLink(target.toPath(), linkTarget.toPath());
                    } catch (IOException | UnsupportedOperationException error) {
                        copyFile(linkTarget, target);
                    }
                } else if (entry.isFile()) {
                    ensureParent(target);
                    copyEntry(tar, target);
                    applyMode(target, entry.getMode());
                }

                if (callback != null) {
                    callback.onProgress(counting.getCount(), totalBytes, name);
                }
            }
        }

        // Directory permissions are applied last so that restrictive modes cannot
        // prevent the extraction of their own children.
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < directories.size(); index++) {
            order.add(index);
        }
        Collections.sort(order, (left, right) -> Integer.compare(
                directories.get(right).getAbsolutePath().length(),
                directories.get(left).getAbsolutePath().length()));
        for (Integer index : order) {
            applyMode(directories.get(index), directoryModes.get(index));
        }
    }

    private static String normalize(String entryName) throws IOException {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || ".".equals(name)) {
            return null;
        }
        if (name.startsWith("/")) {
            throw new IOException("Unexpected absolute archive entry: " + entryName);
        }
        Path normalized = Paths.get(name).normalize();
        String result = normalized.toString().replace('\\', '/');
        if ("..".equals(result) || result.startsWith("../")) {
            throw new IOException("Unexpected parent archive entry: " + entryName);
        }
        return result;
    }

    private static boolean isInside(File destination, File target) throws IOException {
        String root = destination.getCanonicalPath();
        String path = target.getCanonicalPath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    private static void ensureParent(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create " + parent.getAbsolutePath());
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void copyEntry(InputStream input, File target) throws IOException {
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        if (!source.isFile()) {
            throw new IOException("Hard link target missing: " + source.getAbsolutePath());
        }
        try (InputStream input = new FileInputStream(source)) {
            copyEntry(input, target);
        }
    }

    private static void applyMode(File file, int mode) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(file.toPath(), permissions);
        } catch (IOException | UnsupportedOperationException error) {
            file.setReadable(true, true);
            file.setWritable(true, true);
            boolean executable = (mode & 0111) != 0;
            file.setExecutable(executable, true);
            file.setExecutable(executable, false);
        }
    }

    /** Counts the compressed bytes consumed from the asset, used for progress. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream input) {
            super(input instanceof BufferedInputStream ? input : new BufferedInputStream(input, BUFFER_SIZE));
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long amount) throws IOException {
            long skipped = super.skip(amount);
            count += skipped;
            return skipped;
        }

        long getCount() {
            return count;
        }
    }
}
