package com.jellyfinserver.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Jellyfin server process for the whole application.
 *
 * <p>The server itself is a Linux arm64 binary executed through proot. All the
 * state lives in this singleton so that the activity and the foreground service
 * always agree on what is running.</p>
 */
public final class ServerManager {

    public enum State {
        NOT_INSTALLED,
        INSTALLING,
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state, String detail);

        void onLog(String line);

        void onInstallProgress(int percent, String message);
    }

    private static final int MAX_LOG_LINES = 4000;
    private static final long MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L;
    private static final int STARTUP_TIMEOUT_MS = 240_000;

    private static ServerManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Object logLock = new Object();

    private volatile State state;
    private volatile String stateDetail = "";
    private volatile Process process;
    private volatile boolean stopRequested;
    private PowerManager.WakeLock wakeLock;

    public static synchronized ServerManager get(Context context) {
        if (instance == null) {
            instance = new ServerManager(context.getApplicationContext());
        }
        return instance;
    }

    private ServerManager(Context context) {
        this.context = context;
        this.state = Prefs.isInstalled(context) ? State.STOPPED : State.NOT_INSTALLED;
        loadPreviousLog();
    }

    // ---------------------------------------------------------------------
    // Observers
    // ---------------------------------------------------------------------

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
        State current = state;
        String detail = stateDetail;
        mainHandler.post(() -> listener.onStateChanged(current, detail));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        return state;
    }

    public String getStateDetail() {
        return stateDetail;
    }

    public boolean isInstalled() {
        return Prefs.isInstalled(context);
    }

    public String getLogText() {
        synchronized (logLock) {
            StringBuilder builder = new StringBuilder();
            for (String line : logLines) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    // ---------------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------------

    public synchronized void install() {
        if (state == State.INSTALLING || state == State.STARTING || state == State.RUNNING) {
            return;
        }
        setState(State.INSTALLING, "");
        new Thread(() -> {
            try {
                RuntimeInstaller.install(context, (percent, message) ->
                        mainHandler.post(() -> {
                            for (Listener listener : listeners) {
                                listener.onInstallProgress(percent, message);
                            }
                        }));
                Prefs.setInstalled(context, true);
                appendLog("[app] Jellyfin " + AppConfig.SERVER_VERSION
                        + " installé dans " + AppConfig.serverDir(context).getAbsolutePath());
                setState(State.STOPPED, "");
            } catch (Throwable error) {
                String message = error.getMessage() != null
                        ? error.getMessage() : error.getClass().getSimpleName();
                appendLog("[app] Installation échouée : " + message);
                Prefs.setInstalled(context, false);
                setState(State.ERROR, message);
            }
        }, "jellyfin-installer").start();
    }

    // ---------------------------------------------------------------------
    // Start / stop
    // ---------------------------------------------------------------------

    public void start() {
        State current = state;
        if (current == State.INSTALLING || current == State.STARTING
                || current == State.RUNNING || current == State.STOPPING) {
            return;
        }
        if (!Prefs.isInstalled(context)) {
            setState(State.NOT_INSTALLED, "");
            return;
        }

        stopRequested = false;
        setState(State.STARTING, "");
        new Thread(this::launchProcess, "jellyfin-start").start();
    }

    private void launchProcess() {
        appendLog("[app] Démarrage de Jellyfin " + AppConfig.SERVER_VERSION);
        Process started;
        try {
            started = buildProcessBuilder().start();
        } catch (Throwable error) {
            String message = error.getMessage() != null
                    ? error.getMessage() : error.getClass().getSimpleName();
            appendLog("[app] Impossible de lancer le serveur : " + message);
            setState(State.ERROR, message);
            return;
        }

        if (stopRequested) {
            started.destroy();
            return;
        }
        process = started;
        acquireWakeLock();
        pumpProcessOutput(started);
        watchStartup(started);
    }

    public void stop() {
        // Set before looking at the process: a launch may still be in flight.
        stopRequested = true;
        final Process running = process;
        if (running == null) {
            if (state != State.NOT_INSTALLED) {
                setState(State.STOPPED, "");
            }
            return;
        }
        if (state == State.STOPPING) {
            return;
        }
        setState(State.STOPPING, "");
        appendLog("[app] Arrêt du serveur…");

        new Thread(() -> {
            // Ask the Jellyfin process itself to shut down cleanly so that SQLite
            // and the configuration files are flushed. The guest shares the host
            // PID namespace, so a plain SIGTERM does the job.
            signalJellyfinProcess();
            try {
                if (!running.waitFor(15, TimeUnit.SECONDS)) {
                    running.destroy();
                }
                if (!running.waitFor(5, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                    running.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "jellyfin-stop").start();
    }

    /** Stops the server, deletes its installation and returns to a clean state. */
    public void reset() {
        stop();
        new Thread(() -> {
            File serverDir = AppConfig.serverDir(context);
            long deadline = System.currentTimeMillis() + 20_000;
            while (process != null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            RuntimeInstaller.deleteRecursively(serverDir);
            Prefs.setInstalled(context, false);
            synchronized (logLock) {
                logLines.clear();
            }
            stateDetail = "";
            setState(State.NOT_INSTALLED, "");
            appendLog("[app] Serveur supprimé.");
        }, "jellyfin-reset").start();
    }

    // ---------------------------------------------------------------------
    // Process plumbing
    // ---------------------------------------------------------------------

    private ProcessBuilder buildProcessBuilder() throws IOException {
        File proot = AppConfig.prootBinary(context);
        File loader = AppConfig.prootLoader(context);
        File rootfs = AppConfig.rootfsDir(context);
        if (!proot.isFile() || !loader.isFile()) {
            throw new IOException("Installation incomplète : proot est absent.");
        }
        if (!rootfs.isDirectory()) {
            throw new IOException("Installation incomplète : le système Linux est absent.");
        }
        setExecutable(proot);
        setExecutable(loader);
        setExecutable(AppConfig.tallocLibrary(context));
        setExecutable(new File(AppConfig.binDir(context), "libandroid-shmem.so"));

        List<String> command = new ArrayList<>();
        command.add(proot.getAbsolutePath());
        command.add("--kill-on-exit");
        command.add("--link2symlink");
        command.add("-0");
        command.add("-r");
        command.add(rootfs.getAbsolutePath());
        command.add("-w");
        command.add("/root");
        command.add("-b");
        command.add("/dev");
        command.add("-b");
        command.add("/proc");
        command.add("-b");
        command.add("/sys");
        command.add("-b");
        command.add("/dev/urandom:/dev/random");
        command.add("-b");
        command.add(AppConfig.configDir(context).getAbsolutePath() + ":/config");
        command.add("-b");
        command.add(AppConfig.cacheDir(context).getAbsolutePath() + ":/cache");
        command.add("-b");
        command.add(AppConfig.tmpDir(context).getAbsolutePath() + ":/tmp");
        if (hasStorageAccess()) {
            command.add("-b");
            command.add(AppConfig.MEDIA_HOST_PATH + ":" + AppConfig.MEDIA_GUEST_PATH);
        }
        command.add("/bin/sh");
        command.add("-c");
        // Jellyfin 12 has no --ffprobe switch: it looks for ffprobe next to the
        // ffmpeg binary given by --ffmpeg.
        command.add("exec /jellyfin/jellyfin --datadir /config --cachedir /cache"
                + " --webdir /jellyfin/jellyfin-web"
                + " --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(AppConfig.serverDir(context));
        builder.redirectErrorStream(true);

        Map<String, String> environment = builder.environment();
        environment.put("PROOT_LOADER", loader.getAbsolutePath());
        environment.put("PROOT_TMP_DIR", AppConfig.tmpDir(context).getAbsolutePath());
        // proot links against libtalloc and libandroid-shmem, shipped next to it.
        environment.put("LD_LIBRARY_PATH", AppConfig.binDir(context).getAbsolutePath());
        environment.put("HOME", "/root");
        environment.put("TMPDIR", "/tmp");
        environment.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("TERM", "xterm-256color");
        environment.put("JELLYFIN_DATA_DIR", "/config");
        environment.put("JELLYFIN_CONFIG_DIR", "/config/config");
        environment.put("JELLYFIN_CACHE_DIR", "/cache");
        environment.put("JELLYFIN_LOG_DIR", "/config/log");
        environment.put("JELLYFIN_WEB_DIR", "/jellyfin/jellyfin-web");
        environment.put("JELLYFIN_FFMPEG", "/usr/lib/jellyfin-ffmpeg/ffmpeg");
        environment.put("XDG_CACHE_HOME", "/cache");
        // .NET needs the W^X double mapping which proot cannot emulate.
        environment.put("DOTNET_EnableWriteXorExecute", "0");
        environment.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        environment.put("DOTNET_NOLOGO", "1");
        environment.remove("LD_PRELOAD");
        return builder;
    }

    private void setExecutable(File file) throws IOException {
        if (file.isFile() && !file.canExecute() && !file.setExecutable(true, true)) {
            throw new IOException("Impossible de rendre " + file.getName() + " exécutable.");
        }
    }

    private void pumpProcessOutput(final Process running) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ignored) {
                // Process gone, the exit code below is what matters.
            }
            int exitCode = -1;
            try {
                exitCode = running.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            final int code = exitCode;
            mainHandler.post(() -> handleProcessExit(code));
        }, "jellyfin-log").start();
    }

    private synchronized void handleProcessExit(int exitCode) {
        if (process == null) {
            return;
        }
        process = null;
        releaseWakeLock();
        if (state == State.STOPPING) {
            appendLog("[app] Serveur arrêté.");
            setState(State.STOPPED, "");
        } else if (state == State.STARTING || state == State.RUNNING) {
            appendLog("[app] Le serveur s'est arrêté (code " + exitCode + ").");
            String message = context.getString(R.string.server_stopped_unexpectedly, exitCode);
            setState(State.ERROR, message);
        }
    }

    private void watchStartup(final Process running) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (state != State.STARTING || process != running) {
                    return;
                }
                if (isServerResponding()) {
                    if (state == State.STARTING) {
                        appendLog("[app] Le serveur est en ligne sur " + AppConfig.LOCAL_URL);
                        setState(State.RUNNING, "");
                    }
                    return;
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (state == State.STARTING && process == running) {
                appendLog("[app] Le serveur ne répond pas après "
                        + (STARTUP_TIMEOUT_MS / 1000) + " secondes.");
                String message = context.getString(R.string.start_failed);
                setState(State.ERROR, message);
                running.destroy();
            }
        }, "jellyfin-watchdog").start();
    }

    private boolean isServerResponding() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(AppConfig.READY_URL).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(2000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Sends SIGTERM to the Jellyfin process inside the guest. Both the host and
     * the guest share the same PID namespace, so the process is visible in
     * /proc and can be signalled by our own UID.
     */
    private void signalJellyfinProcess() {
        for (File entry : listProcEntries()) {
            String name = entry.getName();
            if (!name.matches("\\d+")) {
                continue;
            }
            String commandLine = readCommandLine(entry);
            if (commandLine != null
                    && commandLine.contains("/jellyfin/jellyfin")
                    && !commandLine.contains("proot")) {
                try {
                    Runtime.getRuntime().exec(new String[]{
                            "/system/bin/kill", "-TERM", name
                    }).waitFor();
                    appendLog("[app] Signal d'arrêt envoyé au serveur (pid " + name + ").");
                } catch (Exception error) {
                    appendLog("[app] Impossible d'envoyer le signal d'arrêt : " + error.getMessage());
                }
                return;
            }
        }
    }

    private File[] listProcEntries() {
        File[] entries = new File("/proc").listFiles();
        return entries != null ? entries : new File[0];
    }

    private String readCommandLine(File processDirectory) {
        File commandLine = new File(processDirectory, "cmdline");
        if (!commandLine.isFile()) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(commandLine, "r")) {
            byte[] buffer = new byte[4096];
            int read = file.read(buffer);
            if (read <= 0) {
                return null;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException error) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Storage permission / wake lock
    // ---------------------------------------------------------------------

    private boolean hasStorageAccess() {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "JellyfinServer::server");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire();
        } catch (RuntimeException ignored) {
            // A wake lock is a nice to have, the service notification keeps the
            // process alive anyway.
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
            // Nothing to do.
        }
    }

    // ---------------------------------------------------------------------
    // State and logging
    // ---------------------------------------------------------------------

    private void setState(State newState, String detail) {
        state = newState;
        stateDetail = detail == null ? "" : detail;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onStateChanged(newState, stateDetail);
            }
        });
    }

    private void appendLog(String line) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String formatted = timestamp + "  " + line;
        synchronized (logLock) {
            logLines.addLast(formatted);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
        writeLogToFile(formatted);
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onLog(formatted);
            }
        });
    }

    private void writeLogToFile(String line) {
        File logFile = AppConfig.logFile(context);
        try {
            if (logFile.length() > MAX_LOG_FILE_BYTES) {
                File rotated = new File(logFile.getParentFile(), "server.log.1");
                //noinspection ResultOfMethodCallIgnored
                rotated.delete();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(rotated);
            }
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Logging must never break the server.
        }
    }

    private void loadPreviousLog() {
        File logFile = AppConfig.logFile(context);
        if (!logFile.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(logFile), StandardCharsets.UTF_8))) {
            ArrayDeque<String> tail = new ArrayDeque<>();
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                while (tail.size() > 200) {
                    tail.removeFirst();
                }
            }
            synchronized (logLock) {
                logLines.clear();
                logLines.addAll(tail);
            }
        } catch (IOException ignored) {
            // A broken log file is not fatal.
        }
    }
}
