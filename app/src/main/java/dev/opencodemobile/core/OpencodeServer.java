package dev.opencodemobile.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import dev.opencodemobile.Paths;

/**
 * Runs {@code opencode serve} inside the Debian container and waits until its HTTP server is
 * reachable from the Android side. The web UI is then shown in a WebView.
 */
public final class OpencodeServer {

    private static final String TAG = "OpencodeServer";
    public static final int DEFAULT_PORT = 4096;

    public interface Callback {
        void onReady(int port);

        void onError(String message);
    }

    private static volatile OpencodeServer sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Deque<String> mLog = new ArrayDeque<>();
    private final List<Callback> mCallbacks = new ArrayList<>();

    private Process mProcess;
    private Thread mThread;
    private volatile boolean mRunning;
    private volatile boolean mStarting;
    private volatile boolean mOwned;
    private int mPort = DEFAULT_PORT;

    private OpencodeServer(Context context) {
        mContext = context.getApplicationContext();
    }

    public static OpencodeServer get(Context context) {
        OpencodeServer instance = sInstance;
        if (instance == null) {
            synchronized (OpencodeServer.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new OpencodeServer(context);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public boolean isStarting() {
        return mStarting;
    }

    public int port() {
        return mPort;
    }

    public String url() {
        return "http://127.0.0.1:" + mPort + "/";
    }

    public List<String> logTail() {
        synchronized (mLog) {
            return new ArrayList<>(mLog);
        }
    }

    public synchronized void start(int port, Callback callback) {
        if (mRunning) {
            final int readyPort = mPort;
            mMain.post(() -> callback.onReady(readyPort));
            return;
        }
        mCallbacks.add(callback);
        if (mStarting) return;
        mPort = port;
        mStarting = true;
        synchronized (mLog) {
            mLog.clear();
        }

        if (!Proot.isReady()) {
            mStarting = false;
            deliverError("Debian is not installed yet.");
            return;
        }

        // Everything from here on happens on the server thread: probing the port with a socket is
        // a network operation and would throw NetworkOnMainThreadException if done here.
        SessionService.start(mContext, "Starting opencode web server…");
        mThread = new Thread(() -> runServer(port), "opencode-server");
        mThread.start();
    }

    private void runServer(int port) {
        File logFile = new File(Paths.logs(), "server.log");
        try {
            if (isPortOpen(port)) {
                // Another instance (or a previous run) is already listening; just use it.
                mRunning = true;
                mOwned = false;
                mStarting = false;
                SessionService.update(mContext, "opencode web server running");
                deliverReady(port);
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("/usr/local/bin/opencode");
            cmd.add("serve");
            cmd.add("--hostname");
            cmd.add("127.0.0.1");
            cmd.add("--port");
            cmd.add(String.valueOf(port));
            ProcessBuilder pb = Proot.processBuilder(mContext,
                Proot.wrap(mContext, Proot.ROOT, cmd));
            mProcess = pb.start();
            mOwned = true;

            try (BufferedWriter file = new BufferedWriter(new FileWriter(logFile, false))) {
                file.write("=== opencode serve :" + port + " ===\n");
            } catch (IOException ignored) {
            }

            Thread pump = new Thread(() -> pump(mProcess, logFile), "opencode-server-log");
            pump.setDaemon(true);
            pump.start();

            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (!mProcess.isAlive()) {
                    mStarting = false;
                    deliverError("opencode server exited while starting.\n\n" + lastLines(8));
                    return;
                }
                if (isPortOpen(port)) {
                    mRunning = true;
                    mStarting = false;
                    SessionService.update(mContext, "opencode web server running");
                    deliverReady(port);
                    return;
                }
                try {
                    Thread.sleep(350);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            mStarting = false;
            deliverError("Timed out waiting for opencode to start.\n\n" + lastLines(8));
        } catch (Throwable t) {
            Log.e(TAG, "server start failed", t);
            mStarting = false;
            mRunning = false;
            deliverError("Could not start opencode server: " + t.getMessage());
        }
    }

    private void deliverReady(int port) {
        List<Callback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(mCallbacks);
            mCallbacks.clear();
        }
        mMain.post(() -> {
            for (Callback callback : callbacks) {
                try {
                    callback.onReady(port);
                } catch (Throwable t) {
                    Log.w(TAG, "ready callback failed", t);
                }
            }
        });
    }

    private void deliverError(String message) {
        List<Callback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(mCallbacks);
            mCallbacks.clear();
        }
        mMain.post(() -> {
            for (Callback callback : callbacks) {
                try {
                    callback.onError(message);
                } catch (Throwable t) {
                    Log.w(TAG, "error callback failed", t);
                }
            }
        });
    }

    private void pump(Process process, File logFile) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter file = new BufferedWriter(new FileWriter(logFile, true))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (mLog) {
                    mLog.addLast(line);
                    while (mLog.size() > 300) mLog.removeFirst();
                }
                try {
                    file.write(line);
                    file.write('\n');
                    file.flush();
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        if (mOwned) {
            mRunning = false;
            if (!hasTerminalSessions()) SessionService.stop(mContext);
        }
    }

    private boolean hasTerminalSessions() {
        try {
            return dev.opencodemobile.App.get().sessions().hasRunningSessions();
        } catch (Throwable t) {
            return false;
        }
    }

    public void stop() {
        stop(null);
    }

    /**
     * Stop the server. The process is killed on a background thread so callers on the main thread
     * (menu actions, reset confirmation) never block; {@code onStopped} runs on the main thread.
     */
    public void stop(Runnable onStopped) {
        Process p;
        synchronized (this) {
            mRunning = false;
            mStarting = false;
            mCallbacks.clear();
            p = mProcess;
            mProcess = null;
            mOwned = false;
        }
        if (p == null) {
            if (!hasTerminalSessions()) SessionService.stop(mContext);
            if (onStopped != null) mMain.post(onStopped);
            return;
        }
        final Process victim = p;
        Thread killer = new Thread(() -> {
            victim.destroy();
            try {
                if (!victim.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    victim.destroyForcibly();
                    victim.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                victim.destroyForcibly();
            }
            if (!hasTerminalSessions()) SessionService.stop(mContext);
            if (onStopped != null) mMain.post(onStopped);
        }, "opencode-server-stop");
        killer.setDaemon(true);
        killer.start();
    }

    private String lastLines(int count) {
        synchronized (mLog) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, mLog.size() - count);
            int i = 0;
            for (String line : mLog) {
                if (i++ < skip) continue;
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }

    public static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
