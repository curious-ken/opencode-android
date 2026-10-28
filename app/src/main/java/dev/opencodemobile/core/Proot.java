package dev.opencodemobile.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.opencodemobile.Paths;
import dev.opencodemobile.Prefs;

/**
 * Builds PRoot command lines for the Debian container.
 *
 * <p>PRoot gives us a root filesystem and fake uid 0 without requiring a rooted device. The
 * container shares the Android kernel and network, while /dev, /proc, /sys and (optionally) shared
 * storage are bind mounted into the guest.</p>
 */
public final class Proot {

    public static final String ROOT = "/root";
    public static final String STORAGE_TARGET = "/root/storage";
    public static final String ANDROID_STORAGE = "/storage/emulated/0";

    private Proot() {
    }

    public static File binary() {
        return Paths.binFile("proot");
    }

    public static boolean isReady() {
        File root = Paths.rootfs();
        return binary().isFile()
            && (new File(root, "usr/bin/bash").isFile() || new File(root, "bin/bash").isFile())
            && new File(root, "usr/bin/apt-get").exists();
    }

    /** Environment used by the proot process itself (outside the guest). */
    public static Map<String, String> hostEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PROOT_TMP_DIR", Paths.tmp().getAbsolutePath());
        // The Termux build of proot was compiled with PROOT_UNBUNDLE_LOADER, i.e. its ELF loader
        // is a separate file and the built-in path points at /data/data/com.termux/... which does
        // not exist on this device. Without a valid loader proot fails every execve() with ENOENT,
        // so the bundled copy is passed explicitly.
        File loader = Paths.binFile("proot-loader");
        if (loader.isFile()) env.put("PROOT_LOADER", loader.getAbsolutePath());
        File loader32 = Paths.binFile("proot-loader32");
        if (loader32.isFile()) env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
        // Some Android kernels/ROMs have a seccomp filter that upsets proot's acceleration path.
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", Paths.lib().getAbsolutePath());
        env.put("TMPDIR", Paths.tmp().getAbsolutePath());
        env.put("HOME", Paths.files().getAbsolutePath());
        env.put("PATH", Paths.bin().getAbsolutePath() + ":/system/bin:/system/xbin:/usr/bin:/bin");
        return env;
    }

    public static String[] hostEnvironmentArray() {
        Map<String, String> env = hostEnvironment();
        String[] out = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            out[i++] = e.getKey() + "=" + e.getValue();
        }
        return out;
    }

    /** Environment exported inside Debian. */
    public static List<String> guestEnvironment() {
        List<String> env = new ArrayList<>();
        env.add("HOME=/root");
        env.add("USER=root");
        env.add("LOGNAME=root");
        env.add("SHELL=/bin/bash");
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("LANG=C.UTF-8");
        env.add("LC_ALL=C.UTF-8");
        env.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.add("TMPDIR=/tmp");
        return env;
    }

    public static boolean sharedStorageAvailable(Context context) {
        if (!Prefs.sharedStorage()) return false;
        File storage = new File(ANDROID_STORAGE);
        return storage.isDirectory() && storage.canRead();
    }

    /**
     * Wrap a guest command in the proot invocation. The returned list starts with proot options and
     * is suitable for {@code TerminalSession} (which supplies argv[0] separately) or ProcessBuilder.
     */
    public static List<String> wrap(Context context, String workdir, List<String> guestCommand) {
        List<String> args = new ArrayList<>();

        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        args.add("-0");
        args.add("-r");
        args.add(Paths.rootfsPath());

        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        args.add("-b");
        args.add("/sys");

        File shm = new File(Paths.tmp(), "shm");
        Paths.mkdirs(shm);
        args.add("-b");
        args.add(shm.getAbsolutePath() + ":/dev/shm");

        if (sharedStorageAvailable(context)) {
            args.add("-b");
            args.add(ANDROID_STORAGE + ":" + STORAGE_TARGET);
        }

        args.add("-w");
        args.add(workdir == null ? ROOT : workdir);

        args.add("/usr/bin/env");
        args.add("-i");
        args.addAll(guestEnvironment());
        args.addAll(guestCommand);
        return args;
    }

    /** A login shell. */
    public static List<String> loginShell(Context context) {
        List<String> cmd = new ArrayList<>();
        cmd.add("/bin/bash");
        cmd.add("-l");
        return wrap(context, ROOT, cmd);
    }

    /** Run a bash snippet and open a login-ish shell. */
    public static List<String> bashCommand(Context context, String workdir, String script) {
        List<String> cmd = new ArrayList<>();
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add(script);
        return wrap(context, workdir == null ? ROOT : workdir, cmd);
    }

    /** Run a command and keep the terminal open until the user presses Enter. */
    public static List<String> interactiveCommand(Context context, String workdir, String script) {
        String wrapped = script + "\n__oc_status=$?\n"
            + "printf '\\n[exit %s - press Enter to close]\\n' \"$__oc_status\"\n"
            + "read -r __oc_dummy || true\n";
        return bashCommand(context, workdir, wrapped);
    }

    public static ProcessBuilder processBuilder(Context context, List<String> fullArgs) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(binary().getAbsolutePath());
        argv.addAll(fullArgs);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(Paths.files());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(hostEnvironment());
        return pb;
    }
}
