package com.jellyfinserver.app;

import android.content.Context;

import java.io.File;

/**
 * Constants and on-disk layout of the embedded Jellyfin server.
 *
 * <pre>
 * files/server/
 *   bin/                 proot + ELF loader + libtalloc (from the APK assets)
 *   rootfs/              Debian arm64 userland with Jellyfin and ffmpeg
 *   data/config          Jellyfin data directory (bound to /config)
 *   data/cache           Jellyfin cache directory (bound to /cache)
 *   tmp/                 proot temporary directory
 *   server.log           captured stdout/stderr of the guest
 * </pre>
 */
public final class AppConfig {

    /** Jellyfin HTTP port, the default of the server. */
    public static final int PORT = 8096;

    /** Version of the Jellyfin build embedded in the APK. */
    public static final String SERVER_VERSION = "12.3.0";

    public static final String LOCAL_URL = "http://127.0.0.1:" + PORT;
    public static final String READY_URL = LOCAL_URL + "/web/index.html";

    public static final String ASSET_ROOTFS = "rootfs.bin";
    public static final String ASSET_ROOTFS_SHA256 = "rootfs.sha256";
    public static final String ASSET_PROOT_DIR = "proot";

    /** Guest path where the phone shared storage is mounted. */
    public static final String MEDIA_GUEST_PATH = "/media";
    /** Host path bound to {@link #MEDIA_GUEST_PATH}. */
    public static final String MEDIA_HOST_PATH = "/storage/emulated/0";

    /** Minimum free space required before extracting the rootfs. */
    public static final long INSTALL_SPACE_REQUIRED = 1024L * 1024L * 1024L;

    private AppConfig() {
    }

    public static File serverDir(Context context) {
        return new File(context.getFilesDir(), "server");
    }

    public static File rootfsDir(Context context) {
        return new File(serverDir(context), "rootfs");
    }

    public static File binDir(Context context) {
        return new File(serverDir(context), "bin");
    }

    public static File configDir(Context context) {
        return new File(serverDir(context), "data/config");
    }

    public static File cacheDir(Context context) {
        return new File(serverDir(context), "data/cache");
    }

    public static File tmpDir(Context context) {
        return new File(serverDir(context), "tmp");
    }

    public static File logFile(Context context) {
        return new File(serverDir(context), "server.log");
    }

    public static File installedMarker(Context context) {
        return new File(serverDir(context), "installed-" + SERVER_VERSION);
    }

    public static File prootBinary(Context context) {
        return new File(binDir(context), "proot");
    }

    public static File prootLoader(Context context) {
        return new File(binDir(context), "loader");
    }

    public static File tallocLibrary(Context context) {
        return new File(binDir(context), "libtalloc.so.2");
    }
}
