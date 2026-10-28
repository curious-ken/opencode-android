package dev.opencodemobile.core;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import dev.opencodemobile.App;
import dev.opencodemobile.MainActivity;
import dev.opencodemobile.R;

/**
 * Keeps the process alive while terminal sessions or an installation are running. The actual
 * sessions are owned by {@link SessionManager}; this service only supplies a foreground
 * notification so Android does not reclaim the process.
 */
public class SessionService extends Service {

    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_STOP = "dev.opencodemobile.STOP_SESSIONS";
    private static final String EXTRA_TEXT = "text";

    private static volatile SessionService sInstance;
    private String mText = "Running";

    public static void start(Context context, String text) {
        SessionService instance = sInstance;
        if (instance != null) {
            instance.setNotificationText(text);
            return;
        }
        Intent intent = new Intent(context, SessionService.class).putExtra(EXTRA_TEXT, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void update(Context context, String text) {
        SessionService instance = sInstance;
        if (instance != null) instance.setNotificationText(text);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SessionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForeground(NOTIFICATION_ID, buildNotification(mText));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) {
                App app = App.get();
                if (app != null) app.sessions().closeAll();
                stopSelf();
                return START_NOT_STICKY;
            }
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null) setNotificationText(text);
        } else if (!App.get().sessions().hasRunningSessions()
            && !OpencodeServer.get(this).isRunning()
            && !App.get().installer().isRunning()) {
            // The system recreated the service after a process death, but all work is gone.
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void setNotificationText(String text) {
        mText = text == null || text.isEmpty() ? "Running" : text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(mText));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentImmutable());
        Intent stop = new Intent(this, SessionService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentImmutable());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, App.CHANNEL_SESSIONS)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                "Stop sessions", stopIntent).build())
            .build();
    }

    private static int pendingIntentImmutable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
