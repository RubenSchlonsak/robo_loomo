package com.example.loomoagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that owns the {@link LoomoHttpServer} (and through it the
 * reverse tunnel). Running as a START_STICKY foreground service lets Android
 * restart it automatically if the process is killed, and keeps it alive when no
 * activity is in the foreground - so the agent + tunnel come back on their own
 * after a reboot or a low-memory kill without any manual intervention.
 */
public class LoomoService extends Service {

    private static final String TAG = "LoomoService";
    private static final String CHANNEL_ID = "loomo_agent";
    private static final int NOTIF_ID = 1;

    private static volatile LoomoHttpServer server;

    /** The running server instance, or null if the service is not up. */
    public static LoomoHttpServer getServer() {
        return server;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        if (server == null) {
            server = new LoomoHttpServer(this, null);
            server.start();
            Log.i(TAG, "LoomoHttpServer started in service");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
            server = null;
        }
        Log.i(TAG, "LoomoService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "LoomoAgent", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = b
                .setContentTitle("LoomoAgent")
                .setContentText("Agent + Tunnel laufen")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }
}
