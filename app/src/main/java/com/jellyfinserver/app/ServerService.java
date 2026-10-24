package com.jellyfinserver.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/**
 * Keeps the Jellyfin server process alive while the app is in the background and
 * exposes it through an ongoing notification.
 */
public class ServerService extends Service implements ServerManager.Listener {

    public static final String ACTION_START = "com.jellyfinserver.app.action.START";
    public static final String ACTION_STOP = "com.jellyfinserver.app.action.STOP";

    private static final String CHANNEL_ID = "server";
    private static final int NOTIFICATION_ID = 8096;

    private ServerManager manager;

    @Override
    public void onCreate() {
        super.onCreate();
        manager = ServerManager.get(this);
        manager.addListener(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            manager.stop();
        } else if (manager.isInstalled()) {
            manager.start();
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        manager.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------
    // ServerManager.Listener
    // ---------------------------------------------------------------------

    @Override
    public void onStateChanged(ServerManager.State state, String detail) {
        switch (state) {
            case STARTING:
            case RUNNING:
            case STOPPING:
                startForeground(NOTIFICATION_ID, buildNotification());
                break;
            case STOPPED:
            case ERROR:
            case NOT_INSTALLED:
                stopForeground(true);
                stopSelf();
                break;
            default:
                break;
        }
    }

    @Override
    public void onLog(String line) {
        // The notification does not show logs.
    }

    @Override
    public void onInstallProgress(int percent, String message) {
        // Installation is driven from the activity.
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(notificationText())
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent());

        if (manager.getState() != ServerManager.State.STOPPING) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.notification_action_stop), stopIntent()).build());
        }
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification),
                getString(R.string.notification_action_open), openIntent()).build());
        return builder.build();
    }

    private String notificationText() {
        switch (manager.getState()) {
            case STARTING:
                return getString(R.string.notification_starting);
            case STOPPING:
                return getString(R.string.notification_stopping);
            case RUNNING:
                return getString(R.string.notification_running, AppConfig.LOCAL_URL);
            default:
                return getString(R.string.status_stopped);
        }
    }

    private PendingIntent openIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags());
    }

    private PendingIntent stopIntent() {
        Intent intent = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, 1, intent, pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
