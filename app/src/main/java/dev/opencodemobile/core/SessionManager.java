package dev.opencodemobile.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.opencodemobile.App;
import dev.opencodemobile.Paths;
import dev.opencodemobile.Prefs;
import dev.opencodemobile.R;

/**
 * Owns all terminal sessions and implements the two Termux client interfaces. A single instance
 * lives in the Application so sessions survive activity recreation.
 */
public final class SessionManager implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "SessionManager";

    public interface Listener {
        void onSessionsChanged();

        void onSessionTitle(String title);

        void onSessionFinished(TerminalSession session);

        void onShowKeyboard();

        void onModifiersChanged();

        void onCopyModeChanged(boolean selecting);

        void onFontSizeChanged(int sp);

        void onBell();
    }

    private final Context mContext;
    // Iterated from background threads (installer/server lifecycle checks) and mutated on the UI
    // thread, so use a copy-on-write list.
    private final List<TerminalSession> mSessions = new CopyOnWriteArrayList<>();

    private TerminalView mView;
    private Listener mListener;
    private int mCurrent = -1;

    private boolean mCtrl;
    private boolean mAlt;
    private boolean mShift;

    public SessionManager(Context context) {
        mContext = context.getApplicationContext();
    }

    // ---------------------------------------------------------------------------------------------
    // View / listener wiring
    // ---------------------------------------------------------------------------------------------

    public void bind(TerminalView view) {
        mView = view;
        view.setTerminalViewClient(this);
        view.setTextSize(fontSizePx());
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        TerminalSession session = current();
        if (session != null) {
            view.attachSession(session);
        }
    }

    public void unbind(TerminalView view) {
        if (mView == view) mView = null;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void removeListener(Listener listener) {
        if (mListener == listener) mListener = null;
    }

    public TerminalSession current() {
        if (mCurrent < 0 || mCurrent >= mSessions.size()) return null;
        return mSessions.get(mCurrent);
    }

    public List<TerminalSession> sessions() {
        return Collections.unmodifiableList(mSessions);
    }

    public int indexOf(TerminalSession session) {
        return mSessions.indexOf(session);
    }

    public boolean hasRunningSessions() {
        for (TerminalSession s : mSessions) {
            if (s.getPid() > 0) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Session creation / switching
    // ---------------------------------------------------------------------------------------------

    public TerminalSession newShell() {
        return newSession("shell", Proot.loginShell(mContext));
    }

    public TerminalSession newOpencode() {
        return newOpencode(false);
    }

    /** The full TUI, or {@code --mini} for narrow screens and small keyboards. */
    public TerminalSession newOpencode(boolean mini) {
        List<String> cmd = new ArrayList<>();
        cmd.add("/usr/local/bin/opencode");
        if (mini) cmd.add("--mini");
        return newSession(mini ? "opencode mini" : "opencode", Proot.wrap(mContext, Proot.ROOT, cmd));
    }

    public TerminalSession newCommand(String label, String script) {
        return newSession(label, Proot.interactiveCommand(mContext, Proot.ROOT, script));
    }

    private TerminalSession newSession(String label, List<String> prootArgs) {
        if (App.get() != null && App.get().terminalError() != null) return null;
        if (!Proot.isReady()) {
            return newSessionRaw(label, "/system/bin/sh",
                new String[]{"-c", "echo 'The Debian container is not installed yet.'; sleep 2"});
        }
        String[] args = prootArgs.toArray(new String[0]);
        TerminalSession session = new TerminalSession(
            Proot.binary().getAbsolutePath(),
            Paths.files().getAbsolutePath(),
            args,
            Proot.hostEnvironmentArray(),
            10000,
            this);
        session.mSessionName = label;
        mSessions.add(session);
        mCurrent = mSessions.size() - 1;
        attachCurrent();
        SessionService.start(mContext, label + " session running");
        notifySessions();
        return session;
    }

    private TerminalSession newSessionRaw(String label, String path, String[] args) {
        if (App.get() != null && App.get().terminalError() != null) return null;
        TerminalSession session = new TerminalSession(
            path,
            Paths.files().getAbsolutePath(),
            args,
            new String[]{"PATH=/system/bin:/system/xbin"},
            2000,
            this);
        session.mSessionName = label;
        mSessions.add(session);
        mCurrent = mSessions.size() - 1;
        attachCurrent();
        notifySessions();
        return session;
    }

    private void attachCurrent() {
        TerminalSession session = current();
        if (mView == null || session == null) return;
        mView.setTextSize(fontSizePx());
        mView.attachSession(session);
        mView.requestFocus();
    }

    public void switchTo(TerminalSession session) {
        int index = mSessions.indexOf(session);
        if (index < 0) return;
        mCurrent = index;
        attachCurrent();
        notifySessions();
    }

    public void switchTo(int index) {
        if (index < 0 || index >= mSessions.size()) return;
        mCurrent = index;
        attachCurrent();
        notifySessions();
    }

    public void finishCurrent() {
        TerminalSession session = current();
        if (session != null) session.finishIfRunning();
    }

    public void closeAll() {
        for (TerminalSession session : new ArrayList<>(mSessions)) {
            try {
                session.finishIfRunning();
            } catch (Throwable ignored) {
            }
        }
        mSessions.clear();
        mCurrent = -1;
        if (mView != null) {
            mView.attachSession(null);
            mView.invalidate();
        }
        notifySessions();
    }

    public void removeFinished() {
        List<TerminalSession> dead = new ArrayList<>();
        for (TerminalSession s : mSessions) {
            if (s.getPid() <= 0) dead.add(s);
        }
        if (dead.isEmpty()) return;
        TerminalSession current = current();
        int oldIndex = mCurrent;
        mSessions.removeAll(dead);
        if (mSessions.isEmpty()) {
            mCurrent = -1;
        } else if (current != null && mSessions.contains(current)) {
            mCurrent = mSessions.indexOf(current);
        } else {
            mCurrent = Math.max(0, Math.min(oldIndex, mSessions.size() - 1));
        }
        if (mView != null) mView.attachSession(current());
        notifySessions();
    }

    // ---------------------------------------------------------------------------------------------
    // Input helpers
    // ---------------------------------------------------------------------------------------------

    public void paste(String text) {
        TerminalSession session = current();
        if (session == null || text == null) return;
        if (session.getEmulator() != null) {
            session.getEmulator().paste(text);
        } else {
            session.write(text);
        }
    }

    public void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(mContext);
        if (!TextUtils.isEmpty(text)) paste(text.toString());
    }

    public void sendKey(int keyCode) {
        if (mView == null) return;
        int mod = 0;
        if (mCtrl) mod |= com.termux.terminal.KeyHandler.KEYMOD_CTRL;
        if (mAlt) mod |= com.termux.terminal.KeyHandler.KEYMOD_ALT;
        if (mShift) mod |= com.termux.terminal.KeyHandler.KEYMOD_SHIFT;
        mView.handleKeyCode(keyCode, mod);
        if (mod != 0) clearModifiers();
    }

    public void sendEscape() {
        TerminalSession session = current();
        if (session != null) session.write("\u001b");
    }

    public void sendTab() {
        TerminalSession session = current();
        if (session != null) session.write("\t");
    }

    public void toggleCtrl() {
        mCtrl = !mCtrl;
        notifyModifiers();
    }

    public void toggleAlt() {
        mAlt = !mAlt;
        notifyModifiers();
    }

    public void toggleShift() {
        mShift = !mShift;
        notifyModifiers();
    }

    public void clearModifiers() {
        if (!mCtrl && !mAlt && !mShift) return;
        mCtrl = mAlt = mShift = false;
        notifyModifiers();
    }

    public boolean isCtrlDown() {
        return mCtrl;
    }

    public boolean isAltDown() {
        return mAlt;
    }

    public boolean isShiftDown() {
        return mShift;
    }

    public void setFontSizeSp(int sp) {
        Prefs.setFontSize(sp);
        if (mView != null) mView.setTextSize(fontSizePx());
        if (mListener != null) mListener.onFontSizeChanged(Prefs.getFontSize());
    }

    public int fontSizeSp() {
        return Prefs.getFontSize();
    }

    private int fontSizePx() {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, Prefs.getFontSize(),
            mContext.getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------------------------------------
    // TerminalSessionClient
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mView != null && changedSession == current()) mView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (changedSession == current() && mListener != null) {
            String title = changedSession.getTitle();
            if (TextUtils.isEmpty(title)) title = changedSession.mSessionName;
            mListener.onSessionTitle(title);
        }
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (mListener != null) mListener.onSessionFinished(finishedSession);
        if (!hasRunningSessions() && !OpencodeServer.get(mContext).isRunning()) {
            SessionService.stop(mContext);
        } else {
            SessionService.update(mContext, runningSummary());
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        pasteFromClipboard();
    }

    @Override
    public void onBell(TerminalSession session) {
        Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(40);
            }
        }
        if (mListener != null) mListener.onBell();
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (mView != null) mView.invalidate();
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {
        Log.d(TAG, "session " + session.mSessionName + " pid=" + pid);
        SessionService.update(mContext, runningSummary());
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return 0;
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message == null ? "" : message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message == null ? "" : message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message == null ? "" : message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message == null ? "" : message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message == null ? "" : message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message == null ? "" : message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "stack trace", e);
    }

    // ---------------------------------------------------------------------------------------------
    // TerminalViewClient
    // ---------------------------------------------------------------------------------------------

    @Override
    public float onScale(float scale) {
        int current = Prefs.getFontSize();
        int next = Math.round(current * scale);
        next = Math.max(8, Math.min(32, next));
        if (next != current) setFontSizeSp(next);
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (mListener != null) mListener.onShowKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return mView != null;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
        if (mListener != null) mListener.onCopyModeChanged(copyMode);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            setFontSizeSp(Prefs.getFontSize() + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1));
            return true;
        }
        boolean modifierKey = keyCode == KeyEvent.KEYCODE_CTRL_LEFT
            || keyCode == KeyEvent.KEYCODE_ALT_LEFT
            || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
            || keyCode == KeyEvent.KEYCODE_BACK;
        if (!modifierKey && (mCtrl || mAlt || mShift)) {
            // TerminalView reads the modifiers during this same event dispatch, so clear them
            // afterwards to get Termux-style one-shot behaviour without breaking Ctrl/Alt combos.
            if (mView != null) {
                mView.post(this::clearModifiers);
            } else {
                clearModifiers();
            }
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return mCtrl;
    }

    @Override
    public boolean readAltKey() {
        return mAlt;
    }

    @Override
    public boolean readShiftKey() {
        return mShift;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        if (mCtrl || mAlt || mShift) {
            mCtrl = mAlt = mShift = false;
            notifyModifiers();
        }
        return false;
    }

    @Override
    public void onEmulatorSet() {
        if (mView != null) mView.invalidate();
    }

    // ---------------------------------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------------------------------

    private void notifySessions() {
        if (mListener != null) mListener.onSessionsChanged();
        TerminalSession session = current();
        String title = session == null ? "" : (TextUtils.isEmpty(session.getTitle())
            ? session.mSessionName : session.getTitle());
        if (mListener != null) mListener.onSessionTitle(title);
    }

    private void notifyModifiers() {
        if (mListener != null) mListener.onModifiersChanged();
    }

    private String runningSummary() {
        int running = 0;
        for (TerminalSession s : mSessions) {
            if (s.getPid() > 0) running++;
        }
        if (running == 0) return "Running";
        return running + (running == 1 ? " session running" : " sessions running");
    }

    public boolean isInstalled() {
        return new File(Paths.rootfs(), "usr/local/bin/opencode").isFile();
    }
}
