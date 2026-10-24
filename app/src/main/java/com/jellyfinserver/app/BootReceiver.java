package com.jellyfinserver.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the server after a reboot when autostart is enabled. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !updated) {
            return;
        }
        if (!Prefs.isAutostartEnabled(context) || !Prefs.isInstalled(context)) {
            return;
        }
        Intent service = new Intent(context, ServerService.class)
                .setAction(ServerService.ACTION_START);
        context.startForegroundService(service);
    }
}
