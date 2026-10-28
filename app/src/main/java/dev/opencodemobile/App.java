package dev.opencodemobile;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dev.opencodemobile.core.Installer;
import dev.opencodemobile.core.SessionManager;

public class App extends Application {

    public static final String CHANNEL_SESSIONS = "sessions";
    public static final String CHANNEL_INSTALL = "install";

    private static App sInstance;

    private Installer mInstaller;
    private SessionManager mSessions;
    private volatile String mTerminalError;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Paths.init(this);
        Prefs.init(this);
        createNotificationChannels();
        installCrashHandler();
        loadTerminalLibrary();
    }

    /**
     * Load the Termux PTY bridge eagerly so an incompatible device (for example a 16 KB-page
     * kernel rejecting the 4 KB-aligned library) produces a clear message instead of a crash
     * when the first shell is opened.
     */
    private void loadTerminalLibrary() {
        try {
            System.loadLibrary("termux");
        } catch (Throwable t) {
            mTerminalError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e("App", "Could not load libtermux.so", t);
        }
    }

    /** Non-null when the native terminal library could not be loaded. */
    public String terminalError() {
        return mTerminalError;
    }

    public static App get() {
        return sInstance;
    }

    public synchronized Installer installer() {
        if (mInstaller == null) mInstaller = new Installer(this);
        return mInstaller;
    }

    public synchronized SessionManager sessions() {
        if (mSessions == null) mSessions = new SessionManager(this);
        return mSessions;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel sessions = new NotificationChannel(
            CHANNEL_SESSIONS,
            getString(R.string.notif_channel_sessions),
            NotificationManager.IMPORTANCE_LOW);
        sessions.setDescription("Keeps terminal and AI sessions alive in the background.");
        sessions.setShowBadge(false);
        nm.createNotificationChannel(sessions);

        NotificationChannel install = new NotificationChannel(
            CHANNEL_INSTALL,
            getString(R.string.notif_channel_install),
            NotificationManager.IMPORTANCE_LOW);
        install.setDescription("Progress while Debian and opencode are being installed.");
        install.setShowBadge(false);
        nm.createNotificationChannel(install);
    }

    /** Write a crash report to the app log directory before letting the default handler run. */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File log = new File(Paths.logs(), "crash.log");
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                try (FileWriter fw = new FileWriter(log, true)) {
                    fw.write("\n===== " + stamp + " " + thread.getName() + " =====\n");
                    fw.write(sw.toString());
                }
            } catch (Throwable ignored) {
                Log.e("App", "Unable to write crash log", ignored);
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
