package dev.opencodemobile.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.opencodemobile.App;
import dev.opencodemobile.Paths;
import dev.opencodemobile.Prefs;

/**
 * Downloads and prepares the Debian root filesystem, the PRoot runtime and the opencode binary.
 *
 * <p>The work happens on a single background thread. All listeners are notified on the main
 * thread, and the state object is safe to read from any thread.</p>
 */
public final class Installer {

    private static final String TAG = "Installer";

    public static final String LXC_BASE =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/";
    public static final String ROOTFS_FALLBACK =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261028_05%3A24/rootfs.tar.xz";
    public static final String OPENCODE_API =
        "https://api.github.com/repos/anomalyco/opencode/releases/latest";
    public static final String OPENCODE_FALLBACK_VERSION = "v1.19.5";
    public static final String OPENCODE_FALLBACK_URL =
        "https://github.com/anomalyco/opencode/releases/download/v1.19.5/opencode-linux-arm64.tar.gz";

    private static final Pattern LXC_DATE =
        Pattern.compile("href=\"([0-9]{8}_[0-9]{2}(?:%3A|:)[0-9]{2})/\"");
    private static final Pattern ANSI =
        Pattern.compile("\u001B\\[[;\\d?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007");

    public enum Step {
        IDLE("Not installed"),
        CHECKING("Checking device"),
        DOWNLOADING_ROOTFS("Downloading Debian"),
        EXTRACTING_ROOTFS("Extracting Debian"),
        PREPARING("Preparing container"),
        INSTALLING_PACKAGES("Installing packages"),
        DOWNLOADING_OPENCODE("Downloading opencode"),
        INSTALLING_OPENCODE("Installing opencode"),
        DONE("Ready"),
        ERROR("Installation failed");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    public static final class State {
        public final Step step;
        public final int progress;
        public final String message;
        public final String error;

        State(Step step, int progress, String message, String error) {
            this.step = step;
            this.progress = progress;
            this.message = message;
            this.error = error;
        }

        public boolean isBusy() {
            return step != Step.IDLE && step != Step.DONE && step != Step.ERROR;
        }
    }

    public interface Listener {
        void onInstallState(State state);

        void onInstallLog(String line);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "opencode-installer");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Deque<String> mLogTail = new ArrayDeque<>();
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);

    private volatile State mState = new State(Step.IDLE, 0, Step.IDLE.label, null);
    private volatile boolean mRunning;
    private volatile Process mProcess;
    private File mLogFile;

    public Installer(Context context) {
        mContext = context.getApplicationContext();
    }

    public State getState() {
        return mState;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public List<String> getLogTail() {
        synchronized (mLogTail) {
            return new ArrayList<>(mLogTail);
        }
    }

    public boolean isInstalled() {
        // The marker is informational only. Checking the real files avoids reporting a working
        // container as missing when the marker could not be written.
        return new File(Paths.rootfs(), "usr/local/bin/opencode").isFile()
            && new File(Paths.rootfs(), "usr/bin/apt-get").exists()
            && Proot.binary().isFile();
    }

    private File marker() {
        return new File(Paths.rootfs(), ".opencode-mobile-install");
    }

    /** Start a normal install. No-op if already running or already installed. */
    public synchronized void install() {
        if (mRunning) return;
        if (isInstalled()) {
            setState(Step.DONE, 100, "opencode " + Prefs.installedVersion() + " is installed", null);
            return;
        }
        startForeground("Preparing installation…");
        mRunning = true;
        mCancelled.set(false);
        mExecutor.execute(this::runInstall);
    }

    /** Re-run package provisioning and opencode installation without re-downloading Debian. */
    public synchronized void repair() {
        if (mRunning) return;
        if (!new File(Paths.rootfs(), "usr/bin/apt-get").exists()) {
            install();
            return;
        }
        startForeground("Repairing installation…");
        mRunning = true;
        mCancelled.set(false);
        mExecutor.execute(() -> {
            try {
                logHeader("Repair");
                copyBootstrap();
                prepareRootfs();
                installPackages();
                installOpencode(false);
                finishSuccess();
            } catch (Throwable t) {
                fail(t);
            } finally {
                mRunning = false;
                stopForegroundIfIdle();
            }
        });
    }

    /** Download and install the latest opencode release into the existing container. */
    public synchronized void updateOpencode() {
        if (mRunning) return;
        if (!new File(Paths.rootfs(), "usr/bin/apt-get").exists()) {
            install();
            return;
        }
        startForeground("Updating opencode…");
        mRunning = true;
        mCancelled.set(false);
        mExecutor.execute(() -> {
            try {
                logHeader("Update opencode");
                installOpencode(true);
                finishSuccess();
            } catch (Throwable t) {
                fail(t);
            } finally {
                mRunning = false;
                stopForegroundIfIdle();
            }
        });
    }

    public synchronized void cancel() {
        mCancelled.set(true);
        Process p = mProcess;
        if (p != null) p.destroy();
        setState(mState.step, mState.progress, "Cancelling…", null);
    }

    /** Delete the Debian container on a background thread. The proot runtime and downloads are kept. */
    public synchronized void deleteRootfs(Runnable onDone) {
        if (mRunning) return;
        mRunning = true;
        setState(Step.CHECKING, 0, "Removing Debian container…", null);
        mExecutor.execute(() -> {
            try {
                deleteTree(Paths.rootfs());
                deleteStagingTrees();
                Paths.mkdirs(Paths.rootfs());
                Prefs.setInstalled("");
                logLine("Container removed");
                setState(Step.IDLE, 0, "Container removed", null);
            } catch (Throwable t) {
                fail(t);
            } finally {
                mRunning = false;
                stopForegroundIfIdle();
                if (onDone != null) mMain.post(onDone);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Install pipeline
    // ---------------------------------------------------------------------------------------------

    private void runInstall() {
        try {
            logHeader("Install");
            if (!isArm64()) {
                throw new IOException("This build supports 64-bit ARM (arm64-v8a) devices only.");
            }
            setState(Step.CHECKING, 2, "Checking proot runtime…", null);
            copyBootstrap();
            testProot();

            boolean haveRootfs = new File(Paths.rootfs(), "usr/bin/apt-get").exists()
                && new File(Paths.rootfs(), "usr/bin/bash").exists()
                && new File(Paths.rootfs(), "etc/debian_version").isFile()
                && new File(Paths.rootfs(), "bin").exists();
            if (!haveRootfs) {
                checkSpace();
                String url = resolveRootfsUrl();
                File archive = new File(Paths.downloads(), "rootfs.tar.xz");
                // Reuse the archive from a previous (interrupted) attempt instead of downloading
                // the whole image again.
                if (archive.isFile() && archive.length() > 50L * 1024 * 1024) {
                    logLine("Using cached " + archive.getName() + " (" + mb(archive.length()) + " MB)");
                    setState(Step.DOWNLOADING_ROOTFS, 100, "Debian root filesystem already downloaded", null);
                } else {
                    download(url, archive, Step.DOWNLOADING_ROOTFS, "Debian root filesystem");
                }
                setState(Step.EXTRACTING_ROOTFS, 0, "Extracting Debian (this takes a few minutes)…", null);
                extractRootfs(archive);
                // The compressed archive is not needed after extraction.
                if (!archive.delete()) Log.w(TAG, "Could not delete " + archive);
            } else {
                logLine("Using existing Debian root filesystem");
            }

            prepareRootfs();
            installPackages();
            installOpencode(false);
            finishSuccess();
        } catch (CancelledException e) {
            mRunning = false;
            setState(Step.ERROR, 0, "Installation cancelled", "Cancelled");
            stopForegroundIfIdle();
        } catch (Throwable t) {
            fail(t);
        } finally {
            mRunning = false;
            stopForegroundIfIdle();
        }
    }

    private void finishSuccess() throws IOException {
        String version = Prefs.installedVersion();
        if (version == null || version.isEmpty()) version = readOpencodeVersion();
        Prefs.setInstalled(version);
        try {
            writeMarker(version);
        } catch (IOException e) {
            // The marker is only a hint; a read-only or partially broken rootfs should not turn a
            // working installation into a failure.
            Log.w(TAG, "Could not write install marker", e);
            logLine("Warning: could not write install marker: " + e.getMessage());
        }
        setState(Step.DONE, 100, "opencode " + version + " is ready", null);
        logLine("Installation complete: opencode " + version);
    }

    private void fail(Throwable t) {
        Log.e(TAG, "Install failed", t);
        String message = t.getMessage();
        if (message == null || message.isEmpty()) message = t.getClass().getSimpleName();
        logLine("ERROR: " + message);
        Prefs.setInstalled("");
        setState(Step.ERROR, 0, "Installation failed", message);
    }

    private void writeMarker(String version) throws IOException {
        String body = "version=" + version + "\n"
            + "arch=arm64-v8a\n"
            + "installed=" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()) + "\n";
        writeFile(marker(), body);
    }

    // ---------------------------------------------------------------------------------------------
    // Assets / runtime
    // ---------------------------------------------------------------------------------------------

    private void copyBootstrap() throws IOException {
        setState(Step.CHECKING, 4, "Unpacking proot and busybox…", null);
        AssetManager assets = mContext.getAssets();
        String[] names;
        try {
            names = assets.list("bootstrap");
        } catch (IOException e) {
            throw new IOException("Bundled bootstrap assets are missing", e);
        }
        if (names == null || names.length == 0) {
            throw new IOException("Bundled bootstrap assets are missing");
        }
        for (String name : names) {
            File target;
            boolean executable = false;
            if (name.startsWith("lib")) {
                target = new File(Paths.lib(), name);
            } else {
                target = new File(Paths.bin(), name);
                executable = true;
            }
            File part = new File(target.getPath() + ".part");
            try (InputStream in = assets.open("bootstrap/" + name);
                 FileOutputStream out = new FileOutputStream(part)) {
                copy(in, out);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace " + target);
            }
            if (!part.renameTo(target)) {
                throw new IOException("Cannot install " + target);
            }
            chmod(target.getAbsolutePath(), executable ? 0755 : 0644);
        }
        chmod(Paths.bin().getAbsolutePath(), 0755);
        chmod(Paths.tmp().getAbsolutePath(), 0700);
    }

    private void testProot() throws IOException {
        if (!Proot.binary().isFile()) throw new IOException("proot binary missing");
        File busybox = Paths.binFile("busybox");
        if (!busybox.isFile()) throw new IOException("busybox binary missing");
        String out = runCapture(new String[]{Proot.binary().getAbsolutePath(), "--version"}, 15);
        logLine("proot: " + out.replace('\n', ' ').trim());
        String bb = runCapture(new String[]{busybox.getAbsolutePath(), "true"}, 15);
        if (!bb.isEmpty()) logLine("busybox: " + bb.trim());
        // Every proot invocation goes through proot's ELF loader. Verify that path works, using a
        // binary we know exists: a missing/blocked loader would otherwise surface much later as an
        // opaque "execve(...): No such file or directory" during extraction.
        try {
            runCapture(new String[]{Proot.binary().getAbsolutePath(), "-r", "/",
                busybox.getAbsolutePath(), "true"}, 20);
            logLine("proot tracing: ok");
        } catch (Throwable t) {
            throw new IOException("proot cannot execute programs on this device: "
                + t.getMessage(), t);
        }
    }

    private boolean isArm64() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
            ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        return abi != null && (abi.contains("arm64") || abi.contains("aarch64"));
    }

    private void checkSpace() throws IOException {
        StatFs stat = new StatFs(Paths.files().getAbsolutePath());
        long available = stat.getAvailableBytes();
        long needed = 2L * 1024 * 1024 * 1024;
        logLine("Free space: " + mb(available) + " MB");
        if (available < needed) {
            throw new IOException("Not enough free space. Need about "
                + mb(needed) + " MB, have " + mb(available) + " MB.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Debian rootfs
    // ---------------------------------------------------------------------------------------------

    private String resolveRootfsUrl() {
        String override = System.getenv("OPENCODE_MOBILE_ROOTFS_URL");
        if (override != null && !override.isEmpty()) return override;
        try {
            String listing = fetchText(LXC_BASE, 20);
            Matcher m = LXC_DATE.matcher(listing);
            String newest = null;
            while (m.find()) {
                String candidate = m.group(1);
                if (newest == null || candidate.compareTo(newest) > 0) newest = candidate;
            }
            if (newest != null) {
                String url = LXC_BASE + newest + "/rootfs.tar.xz";
                logLine("Debian image: " + newest.replace("%3A", ":"));
                return url;
            }
        } catch (Throwable t) {
            logLine("Could not query Debian image index: " + t.getMessage());
        }
        logLine("Using pinned Debian image");
        return ROOTFS_FALLBACK;
    }

    private void extractRootfs(File archive) throws IOException {
        if (!archive.isFile() || archive.length() < 1024 * 1024) {
            throw new IOException("Downloaded Debian archive is incomplete");
        }
        File busybox = Paths.binFile("busybox");

        setState(Step.EXTRACTING_ROOTFS, 0, "Extracting Debian (this takes a few minutes)…", null);

        // Debian's rootfs contains hardlinks (for example usr/bin/perl5.40.1 -> usr/bin/perl).
        // App-private storage normally supports them, but on some devices/ROMs link() is refused
        // and busybox tar then aborts the whole extraction. PRoot's --link2symlink extension
        // rewrites those hardlinks as symlinks while tar runs, which is how proot-distro installs
        // a rootfs on Android.
        boolean hardlinks = supportsHardlinks(Paths.files());
        List<String> tail = new ArrayList<>();

        if (!hardlinks) {
            logLine("Filesystem does not support hardlinks; using proot --link2symlink");
            extractInPlace(busybox, archive, tail);
        } else {
            // Unpack into a fresh staging tree first, so an interrupted or failed extraction can
            // never damage a previously working container. tar also never trips over files left
            // behind by an earlier attempt.
            File staging = new File(Paths.files(), "debian.new");
            deleteStagingTrees();
            if (!deleteTreeStrict(staging)) {
                staging = new File(Paths.files(), "debian.new." + System.currentTimeMillis());
            }
            Paths.mkdirs(staging);

            boolean ok = extractWith(busybox, archive, staging, false, tail);
            if (ok) {
                if (!isValidRootfs(staging)) {
                    deleteTree(staging);
                    throw new IOException("Extraction finished but the Debian root filesystem is invalid");
                }
                setState(Step.EXTRACTING_ROOTFS, 95, "Installing Debian root filesystem…", null);
                replaceTree(staging, Paths.rootfs());
            } else {
                // The hardlink probe said links work but tar still failed; fall back to emulation.
                logLine("Extraction failed; retrying with hardlink emulation");
                extractInPlace(busybox, archive, tail);
            }
        }

        if (!isValidRootfs(Paths.rootfs())) {
            throw new IOException("Extraction finished but the Debian root filesystem is invalid");
        }
        setState(Step.EXTRACTING_ROOTFS, 100, "Debian extracted", null);
    }

    /**
     * Unpack straight into the final container directory, using proot's link2symlink extension.
     *
     * <p>The extension stores <em>absolute host paths</em> inside the symlinks it creates, so this
     * tree must never be renamed afterwards. That is why this path is not staged like the normal
     * one.</p>
     */
    private void extractInPlace(File busybox, File archive, List<String> tail) throws IOException {
        deleteStagingTrees();
        if (!deleteTreeStrict(Paths.rootfs())) {
            throw new IOException("Could not clear the previous container at " + Paths.rootfs());
        }
        Paths.mkdirs(Paths.rootfs());
        tail.clear();
        if (!extractWith(busybox, archive, Paths.rootfs(), true, tail)) {
            String detail = lastLine(tail);
            throw new IOException("Could not extract the Debian root filesystem"
                + (detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private boolean isValidRootfs(File root) {
        return new File(root, "etc/debian_version").isFile()
            && new File(root, "usr/bin/bash").exists()
            && new File(root, "bin").exists();
    }

    /** True when {@code dir} (and therefore the container) supports hardlinks. */
    private boolean supportsHardlinks(File dir) {
        File source = new File(dir, ".hardlink-test");
        File link = new File(dir, ".hardlink-test.link");
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(1);
        } catch (IOException e) {
            Log.w(TAG, "Could not create hardlink test file", e);
            return false;
        }
        try {
            Os.link(source.getAbsolutePath(), link.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Hardlinks unavailable: " + t.getMessage());
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            link.delete();
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    /**
     * Unpack the rootfs archive into {@code dest}, optionally through proot's link2symlink
     * extension. Returns false when tar reports a non-zero exit status.
     */
    private boolean extractWith(File busybox, File archive, File dest, boolean link2symlink,
                                List<String> tail) throws IOException {
        List<String> argv = new ArrayList<>();
        if (link2symlink) {
            if (!Proot.binary().isFile()) throw new IOException("proot binary missing");
            argv.add(Proot.binary().getAbsolutePath());
            argv.add("--link2symlink");
            argv.add("--kill-on-exit");
            argv.add("-r");
            argv.add("/");
        }
        argv.add(busybox.getAbsolutePath());
        argv.add("sh");
        argv.add("-c");
        argv.add("set -e; \"$0\" xz -dc \"$1\" | \"$0\" tar -xf - -C \"$2\"");
        argv.add(busybox.getAbsolutePath());
        argv.add(archive.getAbsolutePath());
        argv.add(dest.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.directory(Paths.files());
        Map<String, String> env = pb.environment();
        env.clear();
        if (link2symlink) {
            env.putAll(Proot.hostEnvironment());
        } else {
            env.put("PATH", Paths.bin().getAbsolutePath());
            env.put("TMPDIR", Paths.tmp().getAbsolutePath());
        }
        return runLogged(pb, Step.EXTRACTING_ROOTFS, "Extracting Debian root filesystem", tail) == 0;
    }

    /** Remove leftover staging trees from earlier, interrupted extractions. */
    private static void deleteStagingTrees() {
        File[] entries = Paths.files().listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith("debian.new") || name.equals("debian.old")) deleteTree(entry);
        }
    }

    /** Move a fully extracted tree into place, keeping the old one until the new one is ready. */
    private void replaceTree(File staged, File target) throws IOException {
        File old = new File(Paths.files(), "debian.old");
        deleteTree(old);
        if (target.exists() && !target.renameTo(old)) {
            if (!deleteTreeStrict(target)) {
                throw new IOException("Could not replace the existing container at " + target);
            }
        }
        if (!staged.renameTo(target)) {
            throw new IOException("Could not move the extracted container into place");
        }
        deleteTree(old);
    }

    // ---------------------------------------------------------------------------------------------
    // Container configuration
    // ---------------------------------------------------------------------------------------------

    private void prepareRootfs() throws IOException {
        setState(Step.PREPARING, 10, "Writing container configuration…", null);
        File root = Paths.rootfs();
        Paths.mkdirs(new File(root, "root"));
        Paths.mkdirs(new File(root, "root/.config/opencode"));
        Paths.mkdirs(new File(root, "root/storage"));
        Paths.mkdirs(new File(root, "opt"));
        Paths.mkdirs(new File(root, "usr/local/bin"));
        Paths.mkdirs(new File(root, "etc/profile.d"));
        Paths.mkdirs(new File(root, "etc/apt/apt.conf.d"));
        Paths.mkdirs(new File(root, "tmp"));

        writeFile(new File(root, "etc/resolv.conf"),
            "# Managed by OpenCode Mobile\nnameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n");
        writeFile(new File(root, "etc/hostname"), "opencode\n");
        writeFile(new File(root, "etc/hosts"),
            "127.0.0.1 localhost opencode\n::1 localhost ip6-localhost ip6-loopback\n");
        writeFile(new File(root, "etc/apt/apt.conf.d/99opencode-mobile"),
            "Acquire::Retries \"3\";\n"
                + "Acquire::http::Timeout \"30\";\n"
                + "Acquire::https::Timeout \"30\";\n"
                + "DPkg::Use-Pty \"false\";\n");

        writeFile(new File(root, "etc/profile.d/00-opencode-mobile.sh"),
            "export TERM=${TERM:-xterm-256color}\n"
                + "export COLORTERM=${COLORTERM:-truecolor}\n"
                + "export LANG=${LANG:-C.UTF-8}\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:$PATH\n");

        File bashrc = new File(root, "root/.bashrc");
        writeFile(bashrc,
            "# ~/.bashrc - OpenCode Mobile\n"
                + "export TERM=xterm-256color\n"
                + "export COLORTERM=truecolor\n"
                + "export LANG=C.UTF-8\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:$PATH\n"
                + "export EDITOR=nano\n"
                + "PS1='\\[\\e[38;5;114m\\]\\u@opencode\\[\\e[0m\\]:\\[\\e[38;5;111m\\]\\w\\[\\e[0m\\]\\$ '\n"
                + "alias ll='ls -alF'\n"
                + "alias la='ls -A'\n"
                + "alias l='ls -CF'\n"
                + "alias oc='opencode'\n"
                + "alias ocweb='opencode serve --hostname 127.0.0.1 --port 4096'\n"
                + "if [ -f /root/storage/.bashrc ]; then . /root/storage/.bashrc; fi\n");

        File config = new File(root, "root/.config/opencode/opencode.json");
        if (!config.isFile()) {
            writeFile(config, "{\n  \"$schema\": \"https://opencode.ai/config.json\"\n}\n");
        }

        writeFile(new File(root, "root/README-opencode-mobile.md"),
            "# OpenCode Mobile\n\n"
                + "You are inside a Debian container running on Android.\n\n"
                + "- `opencode` starts the full TUI. `opencode web` is available from the app header.\n"
                + "- `opencode auth login` (or the app menu) configures AI providers.\n"
                + "- Files under `/root/storage` are your Android shared storage.\n"
                + "- Configuration lives in `~/.config/opencode/`.\n"
                + "- Use apt to install anything else: `apt-get install <package>`.\n");
        setState(Step.PREPARING, 100, "Container configuration ready", null);
    }

    private void installPackages() throws IOException {
        setState(Step.INSTALLING_PACKAGES, 2, "Running apt-get (this can take a while)…", null);
        String script =
            "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "export APT_LISTCHANGES_FRONTEND=none\n"
                // Recover from an interrupted earlier run (killed app, no space, …) before touching
                // the package index. Both commands are allowed to fail.
                + "dpkg --configure -a || true\n"
                + "apt-get -f install -y || true\n"
                + "apt-get update -o Acquire::Retries=3\n"
                + "apt-get install -y --no-install-recommends "
                + "ca-certificates curl git ripgrep unzip xz-utils less nano procps jq openssh-client\n"
                + "apt-get clean\n"
                + "rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb\n";
        List<String> args = Proot.bashCommand(mContext, "/root", script);
        ProcessBuilder pb = Proot.processBuilder(mContext, args);
        runLogged(pb, Step.INSTALLING_PACKAGES, "apt-get install");
        setState(Step.INSTALLING_PACKAGES, 100, "Packages installed", null);
    }

    // ---------------------------------------------------------------------------------------------
    // opencode
    // ---------------------------------------------------------------------------------------------

    private void installOpencode(boolean force) throws IOException {
        setState(Step.DOWNLOADING_OPENCODE, 2, "Finding the latest opencode release…", null);
        String version = null;
        String url = null;
        try {
            String json = fetchText(OPENCODE_API, 20);
            JSONObject release = new JSONObject(json);
            version = release.optString("tag_name", null);
            org.json.JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    if ("opencode-linux-arm64.tar.gz".equals(asset.optString("name"))) {
                        url = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            logLine("GitHub release lookup failed: " + t.getMessage());
        }
        if (version == null || version.isEmpty()) version = OPENCODE_FALLBACK_VERSION;
        if (url == null || url.isEmpty()) url = OPENCODE_FALLBACK_URL;
        logLine("opencode release: " + version);

        File archive = new File(Paths.downloads(), "opencode-" + version + ".tar.gz");
        if (force || !archive.isFile() || archive.length() < 20L * 1024 * 1024) {
            download(url, archive, Step.DOWNLOADING_OPENCODE, "opencode " + version);
        } else {
            logLine("Using cached " + archive.getName());
        }

        setState(Step.INSTALLING_OPENCODE, 20, "Unpacking opencode…", null);
        File targetDir = new File(Paths.rootfs(), "opt/opencode");
        File stagingDir = new File(Paths.rootfs(), "opt/opencode.new");
        deleteTree(stagingDir);
        Paths.mkdirs(stagingDir);
        File busybox = Paths.binFile("busybox");
        ProcessBuilder pb = new ProcessBuilder(
            busybox.getAbsolutePath(), "tar", "-xzf", archive.getAbsolutePath(),
            "-C", stagingDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.directory(Paths.files());
        runLogged(pb, Step.INSTALLING_OPENCODE, "Unpacking opencode");

        File binary = new File(stagingDir, "opencode");
        if (!binary.isFile()) {
            throw new IOException("opencode binary not found in archive");
        }
        chmod(binary.getAbsolutePath(), 0755);

        // Swap the new tree in only after it has been unpacked successfully, so a failed update
        // never destroys a working installation.
        deleteTree(targetDir);
        if (!stagingDir.renameTo(targetDir)) {
            throw new IOException("Could not install opencode into " + targetDir);
        }

        File wrapper = new File(Paths.rootfs(), "usr/local/bin/opencode");
        writeFile(wrapper,
            "#!/bin/sh\n"
                + "# Installed by OpenCode Mobile\n"
                + "exec /opt/opencode/opencode \"$@\"\n");
        chmod(wrapper.getAbsolutePath(), 0755);

        File oc = new File(Paths.rootfs(), "usr/local/bin/oc");
        writeFile(oc,
            "#!/bin/sh\n"
                + "exec /opt/opencode/opencode \"$@\"\n");
        chmod(oc.getAbsolutePath(), 0755);

        Prefs.setInstalled(version);
        String reported = readOpencodeVersion();
        if (reported != null && !reported.isEmpty()) version = reported;
        Prefs.setInstalled(version);
        setState(Step.INSTALLING_OPENCODE, 100, "opencode " + version + " installed", null);
        logLine("opencode installed: " + version);
    }

    private String readOpencodeVersion() {
        File bin = new File(Paths.rootfs(), "opt/opencode/opencode");
        if (!bin.isFile()) return "";
        try {
            List<String> argv = new ArrayList<>();
            argv.add(Proot.binary().getAbsolutePath());
            argv.addAll(Proot.bashCommand(mContext, "/root", "opencode --version 2>/dev/null | tail -n 1"));
            String out = runCapture(argv.toArray(new String[0]), 40);
            String[] lines = out.trim().split("\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+.*")) return line;
            }
            return lines.length > 0 ? lines[lines.length - 1].trim() : "";
        } catch (Throwable t) {
            Log.w(TAG, "Could not read opencode version", t);
            return "";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------------

    private String fetchText(String url, int timeoutSeconds) throws IOException {
        HttpURLConnection c = open(url, timeoutSeconds);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private void download(String url, File dest, Step step, String label) throws IOException {
        Paths.mkdirs(dest.getParentFile());
        File part = new File(dest.getPath() + ".part");
        if (part.exists() && !part.delete()) throw new IOException("Cannot delete " + part);
        HttpURLConnection c = open(url, 60);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + url);
            long total = c.getContentLengthLong();
            long done = 0;
            long lastUpdate = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (mCancelled.get()) throw new CancelledException();
                    out.write(buffer, 0, read);
                    done += read;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate > 300) {
                        lastUpdate = now;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        String msg = total > 0
                            ? label + "  " + mb(done) + " / " + mb(total) + " MB"
                            : label + "  " + mb(done) + " MB";
                        setState(step, pct, msg, null);
                        SessionService.update(mContext, msg);
                    }
                }
            }
            if (part.length() < 1024) throw new IOException("Downloaded file is too small: " + label);
            if (dest.exists() && !dest.delete()) throw new IOException("Cannot replace " + dest);
            if (!part.renameTo(dest)) throw new IOException("Cannot rename downloaded file");
            setState(step, 100, label + " downloaded", null);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, int timeoutSeconds) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutSeconds * 1000);
        c.setReadTimeout(timeoutSeconds * 1000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "OpenCodeMobile/1.0 (Android)");
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    // ---------------------------------------------------------------------------------------------
    // Process helpers
    // ---------------------------------------------------------------------------------------------

    private void runLogged(ProcessBuilder pb, Step step, String label) throws IOException {
        int exit = runLogged(pb, step, label, null);
        if (exit != 0) {
            throw new IOException(label + " failed with exit code " + exit + ". See the log for details.");
        }
    }

    /**
     * Run a process, streaming its output to the install log. Returns the exit code; the last
     * output lines are copied into {@code tail} (when non-null) so callers can report failures.
     */
    private int runLogged(ProcessBuilder pb, Step step, String label, List<String> tail) throws IOException {
        setState(step, step == Step.INSTALLING_PACKAGES ? 5 : 0, label + "…", null);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Could not start " + label + ": " + e.getMessage(), e);
        }
        mProcess = process;
        long lastNotify = 0;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (mCancelled.get()) {
                    process.destroy();
                    break;
                }
                logLine(line);
                if (tail != null) {
                    tail.add(line);
                    while (tail.size() > 40) tail.remove(0);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastNotify > 1000) {
                    lastNotify = now;
                    if (step == Step.INSTALLING_PACKAGES) {
                        setState(step, Math.min(90, mState.progress + 1), label + "…", null);
                    }
                }
            }
        } finally {
            mProcess = null;
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException(label + " interrupted", e);
        }
        if (mCancelled.get()) throw new CancelledException();
        return exit;
    }

    private String runCapture(String[] argv, int timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.directory(Paths.files());
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(Proot.hostEnvironment());
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 4096) out.append(line).append('\n');
            }
        }
        try {
            if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("Timed out: " + argv[0]);
            }
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException(argv[0] + " failed (exit " + p.exitValue() + "): " + out.toString().trim());
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // State / logging / files
    // ---------------------------------------------------------------------------------------------

    private void setState(Step step, int progress, String message, String error) {
        State state = new State(step, Math.max(0, Math.min(100, progress)), message, error);
        mState = state;
        mMain.post(() -> {
            for (Listener l : mListeners) {
                try {
                    l.onInstallState(state);
                } catch (Throwable t) {
                    Log.w(TAG, "install listener failed", t);
                }
            }
        });
        if (state.isBusy()) {
            SessionService.update(mContext, message);
        }
    }

    private void logHeader(String title) {
        mLogFile = new File(Paths.logs(), "install.log");
        String line = "\n===== " + title + " "
            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " =====";
        appendFile(line);
        logLine(line);
    }

    private void logLine(String raw) {
        if (raw == null) return;
        String line = ANSI.matcher(raw.replace('\r', ' ')).replaceAll("").trim();
        if (line.isEmpty()) return;
        synchronized (mLogTail) {
            mLogTail.addLast(line);
            while (mLogTail.size() > 500) mLogTail.removeFirst();
        }
        appendFile(line);
        mMain.post(() -> {
            for (Listener l : mListeners) {
                try {
                    l.onInstallLog(line);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void appendFile(String line) {
        if (mLogFile == null) mLogFile = new File(Paths.logs(), "install.log");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(mLogFile, true))) {
            w.write(line);
            w.write('\n');
        } catch (IOException ignored) {
        }
    }

    private void startForeground(String text) {
        SessionService.start(mContext, text);
    }

    private void stopForegroundIfIdle() {
        if (!App.get().sessions().hasRunningSessions()) {
            SessionService.stop(mContext);
        }
    }

    private static void writeFile(File file, String content) throws IOException {
        Paths.mkdirs(file.getParentFile());
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot replace " + file);
        if (!tmp.renameTo(file)) throw new IOException("Cannot write " + file);
        chmod(file.getAbsolutePath(), 0644);
    }

    private static void chmod(String path, int mode) throws IOException {
        try {
            Os.chmod(path, mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Could not chmod " + path, e);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        try {
            Path root = file.toPath();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(p);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.w(TAG, "Could not delete " + file, e);
        }
    }

    /** Delete a tree and report whether it is really gone. */
    private static boolean deleteTreeStrict(File file) {
        deleteTree(file);
        return file == null || !file.exists();
    }

    private static String lastLine(List<String> tail) {
        for (int i = tail.size() - 1; i >= 0; i--) {
            String line = tail.get(i).trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toString("UTF-8");
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.0f", bytes / 1024.0 / 1024.0);
    }

    private static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }
}
