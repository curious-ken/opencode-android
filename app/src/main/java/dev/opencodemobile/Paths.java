package dev.opencodemobile;

import android.content.Context;

import java.io.File;

/** Central locations used by the app. Everything lives in app-private storage. */
public final class Paths {

    private static File sFiles;

    private Paths() {
    }

    public static synchronized void init(Context context) {
        if (sFiles != null) return;
        sFiles = context.getApplicationContext().getFilesDir();
        mkdirs(files());
        mkdirs(usr());
        mkdirs(bin());
        mkdirs(lib());
        mkdirs(tmp());
        mkdirs(downloads());
        mkdirs(logs());
        mkdirs(cache());
    }

    public static File files() {
        return sFiles;
    }

    public static File usr() {
        return new File(files(), "usr");
    }

    public static File bin() {
        return new File(usr(), "bin");
    }

    public static File lib() {
        return new File(usr(), "lib");
    }

    public static File rootfs() {
        return new File(files(), "debian");
    }

    public static File tmp() {
        return new File(files(), "tmp");
    }

    public static File downloads() {
        return new File(files(), "downloads");
    }

    public static File logs() {
        return new File(files(), "logs");
    }

    public static File cache() {
        return new File(files(), "cache");
    }

    public static File binFile(String name) {
        return new File(bin(), name);
    }

    public static File guest(String guestPath) {
        return new File(rootfs(), guestPath.startsWith("/") ? guestPath.substring(1) : guestPath);
    }

    public static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            // Best effort. Callers verify before use.
        }
    }

    public static String rootfsPath() {
        return rootfs().getAbsolutePath();
    }
}
