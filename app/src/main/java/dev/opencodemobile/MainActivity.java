package dev.opencodemobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.opencodemobile.core.Installer;
import dev.opencodemobile.core.OpencodeServer;
import dev.opencodemobile.core.Proot;
import dev.opencodemobile.core.SessionManager;
import dev.opencodemobile.core.SessionService;

/**
 * Single-activity UI: a native terminal tab plus the opencode web UI, with an installation
 * wizard shown until the Debian container is ready.
 */
public class MainActivity extends android.app.Activity
    implements SessionManager.Listener, Installer.Listener {

    private static final int REQ_NOTIFICATIONS = 10;
    private static final int REQ_STORAGE = 11;
    private static final int REQ_FILE_CHOOSER = 12;

    private TerminalView mTerminal;
    private WebView mWeb;
    private ValueCallback<Uri[]> mFileCallback;

    private LinearLayout mRoot;
    private LinearLayout mHeader;
    private TextView mTitle;
    private TextView mSubtitle;
    private TextView mTabTerminal;
    private TextView mTabWeb;
    private FrameLayout mContent;
    private HorizontalScrollView mExtraScroll;
    private LinearLayout mExtraRow;
    private TextView mKeyCtrl;
    private TextView mKeyAlt;
    private TextView mKeyShift;

    private ScrollView mSetup;
    private TextView mSetupTitle;
    private TextView mSetupBody;
    private TextView mInstallButton;
    private TextView mRetryButton;
    private TextView mSetupStatus;
    private TextView mSetupError;
    private TextView mSetupLog;
    private ScrollView mSetupLogScroll;
    private ProgressBar mSetupProgress;
    private LinearLayout mSetupLogBox;

    private FrameLayout mWebOverlay;
    private TextView mWebOverlayText;
    private ProgressBar mWebProgress;

    private SessionManager mSessions;
    private Installer mInstaller;
    private OpencodeServer mServer;

    private boolean mWebMode;
    private boolean mWebLoading;
    private AlertDialog mUpdateDialog;
    private AlertDialog mSettingsDialog;
    private TextView mSettingsFontLabel;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Paths.init(this);
        Prefs.init(this);

        mSessions = App.get().sessions();
        mInstaller = App.get().installer();
        mServer = OpencodeServer.get(this);

        buildUi();
        mSessions.setListener(this);
        mInstaller.addListener(this);

        applyKeepScreenOn();
        requestNotificationPermission();
        if (App.get().terminalError() != null) showTerminalError();

        if (mInstaller.isInstalled()) {
            mSetup.setVisibility(View.GONE);
            int tab = Prefs.getTab();
            if (tab == Prefs.TAB_WEB) {
                openWeb();
            } else {
                showTerminal();
            }
        } else {
            showSetup();
            renderInstallerState(mInstaller.getState());
            for (String line : mInstaller.getLogTail()) appendSetupLog(line, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWeb != null) {
            mWeb.onResume();
            mWeb.resumeTimers();
        }
        if (mInstaller.isInstalled() && mSetup.getVisibility() == View.VISIBLE && !mInstaller.isRunning()) {
            if (mInstaller.getState().step == Installer.Step.DONE) {
                mSetup.setVisibility(View.GONE);
                showTerminal();
            }
        }
    }

    @Override
    protected void onPause() {
        if (mWeb != null) {
            mWeb.onPause();
            mWeb.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mWebMode = false;
        mSessions.removeListener(this);
        mInstaller.removeListener(this);
        if (mUpdateDialog != null) {
            mUpdateDialog.dismiss();
            mUpdateDialog = null;
        }
        if (mWeb != null) {
            mContent.removeView(mWeb);
            mWeb.destroy();
            mWeb = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSetup.getVisibility() == View.VISIBLE) {
            if (mInstaller.isRunning()) {
                new AlertDialog.Builder(this)
                    .setTitle("Installation in progress")
                    .setMessage("Keep installing in the background?")
                    .setPositiveButton("Keep installing", (d, w) -> moveTaskToBack(true))
                    .setNegativeButton("Cancel install", (d, w) -> {
                        mInstaller.cancel();
                        moveTaskToBack(true);
                    })
                    .show();
                return;
            }
            moveTaskToBack(true);
            return;
        }
        if (mWebMode && mWeb != null && mWeb.canGoBack()) {
            mWeb.goBack();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && mTerminal != null && imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(mTerminal.getWindowToken(), 0);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Leave OpenCode?")
            .setMessage("Sessions can keep running in the background, so long AI tasks are not interrupted.")
            .setPositiveButton("Keep running", (d, w) -> moveTaskToBack(true))
            .setNeutralButton("Stop sessions", (d, w) -> {
                mServer.stop();
                mSessions.closeAll();
                SessionService.stop(this);
                finish();
            })
            .setNegativeButton("Stay", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            toast(mSessions != null ? "Storage permission updated" : "");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------------------------------

    private void buildUi() {
        getWindow().setStatusBarColor(color(R.color.surface));
        getWindow().setNavigationBarColor(color(R.color.surface));

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(color(R.color.bg));
        mRoot.setFitsSystemWindows(true);

        mHeader = buildHeader();
        mRoot.addView(mHeader, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.border));
        mRoot.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mContent = new FrameLayout(this);
        mRoot.addView(mContent, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mTerminal = new TerminalView(this, null);
        mTerminal.setBackgroundColor(color(R.color.bg));
        mTerminal.setFocusable(true);
        mTerminal.setFocusableInTouchMode(true);
        mContent.addView(mTerminal, match());

        mSetup = buildSetup();
        mContent.addView(mSetup, match());

        mExtraScroll = buildExtraKeys();
        mRoot.addView(mExtraScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(mRoot);
        mSessions.bind(mTerminal);
        updateTabStyles();
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(color(R.color.surface));
        header.setPadding(dp(14), dp(7), dp(8), dp(7));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mTitle = new TextView(this);
        mTitle.setText("OPENCODE");
        mTitle.setTextColor(color(R.color.text));
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setLetterSpacing(0.14f);
        brand.addView(mTitle);

        mSubtitle = new TextView(this);
        mSubtitle.setText("Debian · arm64");
        mSubtitle.setTextColor(color(R.color.text_dim));
        mSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mSubtitle.setSingleLine(true);
        mSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(1);
        brand.addView(mSubtitle, subLp);
        header.addView(brand);

        mTabTerminal = tabButton(">_ TERM");
        mTabTerminal.setOnClickListener(v -> showTerminal());
        header.addView(mTabTerminal, tabLp());

        mTabWeb = tabButton("WEB");
        mTabWeb.setOnClickListener(v -> openWeb());
        header.addView(mTabWeb, tabLp());

        TextView menu = tabButton("⋮");
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menu.setContentDescription("Menu");
        menu.setOnClickListener(v -> showMenu(v));
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(42), dp(38));
        menuLp.leftMargin = dp(4);
        header.addView(menu, menuLp);
        return header;
    }

    private LinearLayout.LayoutParams tabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.leftMargin = dp(4);
        return lp;
    }

    private TextView tabButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color(R.color.text_dim));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), 0, dp(10), 0);
        tv.setBackgroundResource(R.drawable.btn_ghost_bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private ScrollView buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(R.color.bg));
        scroll.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(28), dp(24), dp(28));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(box, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        box.addView(logo, new LinearLayout.LayoutParams(dp(84), dp(84)));

        mSetupTitle = new TextView(this);
        mSetupTitle.setText(R.string.install_title);
        mSetupTitle.setTextColor(color(R.color.text));
        mSetupTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        mSetupTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mSetupTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(18);
        box.addView(mSetupTitle, titleLp);

        mSetupBody = new TextView(this);
        mSetupBody.setText(R.string.install_body);
        mSetupBody.setTextColor(color(R.color.text_dim));
        mSetupBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mSetupBody.setGravity(Gravity.CENTER);
        mSetupBody.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(10);
        box.addView(mSetupBody, bodyLp);

        LinearLayout features = new LinearLayout(this);
        features.setOrientation(LinearLayout.VERTICAL);
        features.setBackgroundResource(R.drawable.panel_bg);
        features.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams featureLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        featureLp.topMargin = dp(20);
        box.addView(features, featureLp);
        feature(features, "Debian 13 (trixie) · arm64 userland");
        feature(features, "Full opencode CLI, TUI and web UI");
        feature(features, "bash, apt, git, ripgrep, curl, ssh");
        feature(features, "Sessions survive app switching");

        mInstallButton = primaryButton(getString(R.string.install_button));
        mInstallButton.setOnClickListener(v -> startInstall());
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        installLp.topMargin = dp(22);
        box.addView(mInstallButton, installLp);

        mSetupProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mSetupProgress.setMax(100);
        mSetupProgress.setIndeterminate(true);
        mSetupProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressLp.topMargin = dp(16);
        box.addView(mSetupProgress, progressLp);

        mSetupStatus = new TextView(this);
        mSetupStatus.setTextColor(color(R.color.text_dim));
        mSetupStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mSetupStatus.setGravity(Gravity.CENTER);
        mSetupStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(8);
        box.addView(mSetupStatus, statusLp);

        mSetupError = new TextView(this);
        mSetupError.setTextColor(color(R.color.danger));
        mSetupError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mSetupError.setGravity(Gravity.CENTER);
        mSetupError.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = dp(8);
        box.addView(mSetupError, errorLp);

        mRetryButton = primaryButton(getString(R.string.install_retry));
        mRetryButton.setOnClickListener(v -> startInstall());
        mRetryButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        retryLp.topMargin = dp(12);
        box.addView(mRetryButton, retryLp);

        mSetupLogBox = new LinearLayout(this);
        mSetupLogBox.setOrientation(LinearLayout.VERTICAL);
        mSetupLogBox.setBackgroundResource(R.drawable.panel_bg);
        mSetupLogBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        mSetupLogBox.setVisibility(View.GONE);

        TextView logTitle = new TextView(this);
        logTitle.setText("INSTALL LOG");
        logTitle.setTextColor(color(R.color.text_dim));
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        logTitle.setLetterSpacing(0.13f);
        mSetupLogBox.addView(logTitle);

        mSetupLogScroll = new ScrollView(this);
        mSetupLog = new TextView(this);
        mSetupLog.setTextColor(color(R.color.text_dim));
        mSetupLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        mSetupLog.setTypeface(Typeface.MONOSPACE);
        mSetupLog.setTextIsSelectable(true);
        mSetupLogScroll.addView(mSetupLog);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        mSetupLogBox.addView(mSetupLogScroll, logLp);

        LinearLayout.LayoutParams logBoxLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logBoxLp.topMargin = dp(16);
        box.addView(mSetupLogBox, logBoxLp);

        TextView note = new TextView(this);
        note.setText(R.string.install_note);
        note.setTextColor(color(R.color.text_dim));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(14);
        box.addView(note, noteLp);
        return scroll;
    }

    private void feature(LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(3);
        rowLp.bottomMargin = dp(3);

        TextView dot = new TextView(this);
        dot.setText("›");
        dot.setTextColor(color(R.color.accent));
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(dot);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color(R.color.text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = dp(8);
        row.addView(label, labelLp);
        parent.addView(row, rowLp);
    }

    private HorizontalScrollView buildExtraKeys() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(color(R.color.surface));
        scroll.setPadding(dp(3), dp(4), dp(3), dp(4));

        mExtraRow = new LinearLayout(this);
        mExtraRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(mExtraRow, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addKey("ESC", v -> {
            mSessions.sendEscape();
            mSessions.clearModifiers();
        });
        addKey("TAB", v -> {
            mSessions.sendTab();
            mSessions.clearModifiers();
        });
        mKeyCtrl = addKey("CTRL", v -> mSessions.toggleCtrl());
        mKeyAlt = addKey("ALT", v -> mSessions.toggleAlt());
        mKeyShift = addKey("SHIFT", v -> mSessions.toggleShift());
        addKey("←", v -> mSessions.sendKey(KeyEvent.KEYCODE_DPAD_LEFT));
        addKey("↑", v -> mSessions.sendKey(KeyEvent.KEYCODE_DPAD_UP));
        addKey("↓", v -> mSessions.sendKey(KeyEvent.KEYCODE_DPAD_DOWN));
        addKey("→", v -> mSessions.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        addKey("HOME", v -> mSessions.sendKey(KeyEvent.KEYCODE_MOVE_HOME));
        addKey("END", v -> mSessions.sendKey(KeyEvent.KEYCODE_MOVE_END));
        addKey("PGUP", v -> mSessions.sendKey(KeyEvent.KEYCODE_PAGE_UP));
        addKey("PGDN", v -> mSessions.sendKey(KeyEvent.KEYCODE_PAGE_DOWN));
        addKey("|", v -> write("|"));
        addKey("~", v -> write("~"));
        addKey("/", v -> write("/"));
        addKey("-", v -> write("-"));
        addKey("PASTE", v -> mSessions.pasteFromClipboard());
        addKey("⌨", v -> toggleKeyboard());
        updateModifierKeys();
        return scroll;
    }

    private TextView addKey(String label, View.OnClickListener listener) {
        TextView key = new TextView(this);
        key.setText(label);
        key.setTextColor(color(R.color.text));
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        key.setGravity(Gravity.CENTER);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        key.setPadding(dp(9), 0, dp(9), 0);
        key.setMinWidth(dp(38));
        key.setHeight(dp(34));
        key.setBackgroundResource(R.drawable.key_bg);
        key.setClickable(true);
        key.setFocusable(true);
        key.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(4);
        mExtraRow.addView(key, lp);
        return key;
    }

    private void write(String text) {
        TerminalSession session = mSessions.current();
        if (session != null) session.write(text);
        mSessions.clearModifiers();
    }

    private TextView primaryButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(color(R.color.on_accent));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.btn_primary_bg);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------------------------------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------------------------------

    private void showTerminal() {
        if (App.get().terminalError() != null) {
            showTerminalError();
            return;
        }
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        mWebMode = false;
        Prefs.setTab(Prefs.TAB_TERMINAL);
        mSetup.setVisibility(View.GONE);
        mTerminal.setVisibility(View.VISIBLE);
        if (mWeb != null) mWeb.setVisibility(View.GONE);
        mExtraScroll.setVisibility(Prefs.extraKeys() ? View.VISIBLE : View.GONE);
        updateTabStyles();
        if (mSessions.current() == null) {
            mSessions.newShell();
        }
        mSessions.bind(mTerminal);
        mTerminal.requestFocus();
        mSubtitle.setText(installedLabel());
    }

    private void openWeb() {
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        mWebMode = true;
        Prefs.setTab(Prefs.TAB_WEB);
        mSetup.setVisibility(View.GONE);
        mTerminal.setVisibility(View.GONE);
        mExtraScroll.setVisibility(View.GONE);
        updateTabStyles();

        if (mWeb == null) createWebView();
        mWeb.setVisibility(View.VISIBLE);
        mSubtitle.setText("opencode web · localhost:" + mServer.port());

        if (mServer.isRunning()) {
            hideWebOverlay();
            mWeb.loadUrl(mServer.url());
            return;
        }
        showWebOverlay("Starting opencode web UI…", true);
        mServer.start(OpencodeServer.DEFAULT_PORT, new OpencodeServer.Callback() {
            @Override
            public void onReady(int port) {
                if (!mWebMode || mWeb == null || isFinishing() || isDestroyed()) return;
                mSubtitle.setText("opencode web · localhost:" + port);
                hideWebOverlay();
                mWeb.loadUrl(mServer.url());
            }

            @Override
            public void onError(String message) {
                if (mWeb == null || isFinishing() || isDestroyed()) return;
                hideWebOverlay();
                if (!mWebMode) return;
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Could not start the web UI")
                    .setMessage(message)
                    .setPositiveButton("Open terminal", (d, w) -> showTerminal())
                    .setNegativeButton("Close", null)
                    .show();
            }
        });
    }

    private void updateTabStyles() {
        boolean terminalActive = !mWebMode;
        mTabTerminal.setTextColor(color(terminalActive ? R.color.accent : R.color.text_dim));
        mTabTerminal.setBackgroundResource(terminalActive
            ? R.drawable.key_active_bg : R.drawable.btn_ghost_bg);
        mTabWeb.setTextColor(color(!terminalActive ? R.color.accent : R.color.text_dim));
        mTabWeb.setBackgroundResource(!terminalActive
            ? R.drawable.key_active_bg : R.drawable.btn_ghost_bg);
    }

    private void showSetup() {
        mWebMode = false;
        mSetup.setVisibility(View.VISIBLE);
        mTerminal.setVisibility(View.GONE);
        if (mWeb != null) mWeb.setVisibility(View.GONE);
        mExtraScroll.setVisibility(View.GONE);
        updateTabStyles();
        mSubtitle.setText("Not installed");
    }

    private void showTerminalError() {
        String error = App.get().terminalError();
        new AlertDialog.Builder(this)
            .setTitle("Terminal not supported on this device")
            .setMessage("The bundled terminal engine (libtermux.so) could not be loaded.\n\n"
                + (error == null ? "Unknown native library error." : error)
                + "\n\nThis is usually caused by a 16 KB-page kernel, which this arm64 build "
                + "does not support yet. A 4 KB-page device is required.")
            .setPositiveButton("Close", null)
            .show();
    }

    private void createWebView() {
        mWeb = new WebView(this);
        mWeb.setBackgroundColor(color(R.color.bg));
        WebSettings settings = mWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocal(uri)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isLocal(Uri.parse(url))) hideWebOverlay();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame() && mWebMode) {
                    showWebOverlay("Could not load " + mServer.url(), false);
                }
            }
        });
        mWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                android.util.Log.d("WebView", consoleMessage.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (mFileCallback != null) mFileCallback.onReceiveValue(null);
                mFileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Choose file"), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    mFileCallback = null;
                    return false;
                }
                return true;
            }
        });
        mContent.addView(mWeb, match());
        mWeb.setVisibility(View.GONE);

        mWebOverlay = new FrameLayout(this);
        mWebOverlay.setBackgroundColor(0xF00B0F14);
        mWebOverlay.setVisibility(View.GONE);
        LinearLayout overlayBox = new LinearLayout(this);
        overlayBox.setOrientation(LinearLayout.VERTICAL);
        overlayBox.setGravity(Gravity.CENTER);
        overlayBox.setPadding(dp(28), dp(28), dp(28), dp(28));

        mWebProgress = new ProgressBar(this);
        overlayBox.addView(mWebProgress, new LinearLayout.LayoutParams(dp(42), dp(42)));

        mWebOverlayText = new TextView(this);
        mWebOverlayText.setTextColor(color(R.color.text));
        mWebOverlayText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mWebOverlayText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = dp(16);
        overlayBox.addView(mWebOverlayText, textLp);

        TextView back = tabButton("Back to terminal");
        back.setPadding(dp(16), 0, dp(16), 0);
        back.setOnClickListener(v -> showTerminal());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        backLp.topMargin = dp(20);
        overlayBox.addView(back, backLp);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        mWebOverlay.addView(overlayBox, boxLp);
        mContent.addView(mWebOverlay, match());
    }

    private void showWebOverlay(String text, boolean spinner) {
        mWebLoading = true;
        mWebOverlayText.setText(text);
        mWebProgress.setVisibility(spinner ? View.VISIBLE : View.GONE);
        mWebOverlay.setVisibility(View.VISIBLE);
    }

    private void hideWebOverlay() {
        mWebLoading = false;
        if (mWebOverlay != null) mWebOverlay.setVisibility(View.GONE);
    }

    private boolean isLocal(Uri uri) {
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            toast("No app can open " + uri);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Installation
    // ---------------------------------------------------------------------------------------------

    private void startInstall() {
        mInstallButton.setEnabled(false);
        mInstallButton.setText("Installing…");
        mSetupError.setVisibility(View.GONE);
        mRetryButton.setVisibility(View.GONE);
        mSetupLogBox.setVisibility(View.VISIBLE);
        if (Prefs.sharedStorage()) requestStoragePermission();
        mInstaller.install();
    }

    @Override
    public void onInstallState(Installer.State state) {
        renderInstallerState(state);
        if (state.step == Installer.Step.DONE) {
            if (mUpdateDialog != null) {
                mUpdateDialog.dismiss();
                mUpdateDialog = null;
                toast("opencode " + Prefs.installedVersion() + " is ready");
                return;
            }
            mSetup.setVisibility(View.GONE);
            mSetupProgress.setVisibility(View.GONE);
            mSetupStatus.setVisibility(View.GONE);
            mSetupError.setVisibility(View.GONE);
            mRetryButton.setVisibility(View.GONE);
            mInstallButton.setEnabled(true);
            mInstallButton.setText(getString(R.string.install_button));
            if (mSetup.getVisibility() != View.VISIBLE) {
                toast("opencode " + Prefs.installedVersion() + " is ready");
                return;
            }
            showTerminal();
            new AlertDialog.Builder(this)
                .setTitle("Your container is ready")
                .setMessage("Run `opencode auth login` to connect an AI provider, then start a chat "
                    + "from the terminal or the web tab.\n\n"
                    + "Files in /root/storage are your Android shared storage.")
                .setPositiveButton("Sign in", (d, w) -> openAuthSession())
                .setNegativeButton("Start exploring", null)
                .show();
        } else if (state.step == Installer.Step.ERROR) {
            if (mUpdateDialog != null) {
                mUpdateDialog.dismiss();
                mUpdateDialog = null;
            }
            mInstallButton.setEnabled(true);
            mInstallButton.setText(getString(R.string.install_button));
            mSetupProgress.setVisibility(View.GONE);
            mSetupStatus.setVisibility(View.GONE);
            mSetupError.setVisibility(View.VISIBLE);
            mSetupError.setText(state.error == null ? "Unknown error" : state.error);
            mRetryButton.setVisibility(View.VISIBLE);
            mSetupLogBox.setVisibility(View.VISIBLE);
        }
    }

    private void renderInstallerState(Installer.State state) {
        if (state == null) return;
        if (state.isBusy()) {
            mSetup.setVisibility(View.VISIBLE);
            mTerminal.setVisibility(View.GONE);
            if (mWeb != null) mWeb.setVisibility(View.GONE);
            mExtraScroll.setVisibility(View.GONE);
            mInstallButton.setEnabled(false);
            mInstallButton.setText("Installing…");
            mSetupProgress.setVisibility(View.VISIBLE);
            if (state.progress > 0) {
                mSetupProgress.setIndeterminate(false);
                mSetupProgress.setProgress(state.progress);
            } else {
                mSetupProgress.setIndeterminate(true);
            }
            mSetupStatus.setVisibility(View.VISIBLE);
            mSetupStatus.setText(state.message);
            mSetupError.setVisibility(View.GONE);
            mRetryButton.setVisibility(View.GONE);
            mSetupLogBox.setVisibility(View.VISIBLE);
            mSubtitle.setText(state.message);
        } else if (state.step == Installer.Step.ERROR) {
            mSetup.setVisibility(View.VISIBLE);
            mSubtitle.setText("Installation failed");
        } else if (state.step == Installer.Step.IDLE) {
            mSetup.setVisibility(View.VISIBLE);
            mSetupProgress.setVisibility(View.GONE);
            mSetupStatus.setVisibility(View.GONE);
            mInstallButton.setEnabled(true);
            mInstallButton.setText(getString(R.string.install_button));
            mSubtitle.setText("Not installed");
        }
    }

    @Override
    public void onInstallLog(String line) {
        appendSetupLog(line, true);
    }

    private void appendSetupLog(String line, boolean scroll) {
        if (mSetupLog == null) return;
        CharSequence current = mSetupLog.getText();
        String text = current == null ? "" : current.toString();
        if (text.length() > 16000) {
            text = text.substring(text.length() - 12000);
        }
        mSetupLog.setText(text + line + "\n");
        if (scroll) {
            mSetupLogScroll.post(() -> mSetupLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Session callbacks
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onSessionsChanged() {
        if (mSetup.getVisibility() == View.VISIBLE) return;
        if (!mWebMode) mSubtitle.setText(installedLabel());
    }

    @Override
    public void onSessionTitle(String title) {
        if (mWebMode) return;
        if (!TextUtils.isEmpty(title)) mSubtitle.setText(title);
    }

    @Override
    public void onSessionFinished(TerminalSession session) {
        if (mSetup.getVisibility() == View.VISIBLE) return;
        if (!mWebMode) mSubtitle.setText(installedLabel());
    }

    @Override
    public void onShowKeyboard() {
        if (mWebMode) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mTerminal, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public void onModifiersChanged() {
        updateModifierKeys();
    }

    private void updateModifierKeys() {
        if (mKeyCtrl == null) return;
        mKeyCtrl.setBackgroundResource(mSessions.isCtrlDown()
            ? R.drawable.key_active_bg : R.drawable.key_bg);
        mKeyAlt.setBackgroundResource(mSessions.isAltDown()
            ? R.drawable.key_active_bg : R.drawable.key_bg);
        mKeyShift.setBackgroundResource(mSessions.isShiftDown()
            ? R.drawable.key_active_bg : R.drawable.key_bg);
    }

    @Override
    public void onCopyModeChanged(boolean selecting) {
        if (!selecting) mTerminal.invalidate();
    }

    @Override
    public void onFontSizeChanged(int sp) {
        if (mSettingsFontLabel != null) mSettingsFontLabel.setText(sp + " sp");
    }

    @Override
    public void onBell() {
        // Handled with a vibration in SessionManager.
    }

    // ---------------------------------------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------------------------------------

    private void showMenu(View anchor) {
        final String[] items = new String[]{
            "New terminal session",
            "Switch session",
            "OpenCode terminal (TUI)",
            "OpenCode mini TUI (small screens)",
            "OpenCode web UI",
            "Sign in / manage providers",
            "List available models",
            "Token usage statistics",
            "Restart OpenCode web server",
            "Update opencode",
            "Settings",
            "View logs",
            "About",
            "Reset Debian container"
        };
        new AlertDialog.Builder(this)
            .setTitle("OpenCode Mobile")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        if (!mInstaller.isInstalled()) {
                            showSetup();
                            break;
                        }
                        mSessions.newShell();
                        showTerminal();
                        break;
                    case 1:
                        showSessionPicker();
                        break;
                    case 2:
                        openOpencodeTui(false);
                        break;
                    case 3:
                        openOpencodeTui(true);
                        break;
                    case 4:
                        openWeb();
                        break;
                    case 5:
                        openAuthSession();
                        break;
                    case 6:
                        runCommand("models", "opencode models");
                        break;
                    case 7:
                        runCommand("stats", "opencode stats");
                        break;
                    case 8:
                        mServer.stop(() -> {
                            if (!isFinishing() && !isDestroyed()) openWeb();
                        });
                        break;
                    case 9:
                        confirmUpdateOpencode();
                        break;
                    case 10:
                        showSettings();
                        break;
                    case 11:
                        showLogs();
                        break;
                    case 12:
                        showAbout();
                        break;
                    case 13:
                        confirmReset();
                        break;
                }
            })
            .show();
    }

    private void showSessionPicker() {
        List<TerminalSession> sessions = new ArrayList<>(mSessions.sessions());
        if (sessions.isEmpty()) {
            toast("No terminal sessions yet");
            return;
        }
        TerminalSession current = mSessions.current();
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i);
            String name = TextUtils.isEmpty(session.mSessionName)
                ? "session " + (i + 1) : session.mSessionName;
            String state = session.getPid() > 0 ? "running" : "exited";
            labels[i] = (session == current ? "● " : "   ") + name + " · " + state;
        }
        new AlertDialog.Builder(this)
            .setTitle("Terminal sessions")
            .setItems(labels, (d, which) -> {
                mSessions.switchTo(sessions.get(which));
                showTerminal();
                mSessions.bind(mTerminal);
            })
            .setNeutralButton("New shell", (d, w) -> {
                if (!mInstaller.isInstalled()) {
                    showSetup();
                    return;
                }
                mSessions.newShell();
                showTerminal();
            })
            .setNegativeButton("Close current", (d, w) -> mSessions.finishCurrent())
            .show();
    }

    private void openOpencodeTui(boolean mini) {
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        // Reuse a running TUI instead of starting a second instance against the same session DB.
        String label = mini ? "opencode mini" : "opencode";
        for (TerminalSession session : mSessions.sessions()) {
            if (label.equals(session.mSessionName) && session.getPid() > 0) {
                mSessions.switchTo(session);
                showTerminal();
                return;
            }
        }
        mSessions.newOpencode(mini);
        showTerminal();
    }

    private void openAuthSession() {
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        runCommand("opencode auth", "opencode auth login");
    }

    private void runCommand(String label, String command) {
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        mSessions.newCommand(label, command);
        showTerminal();
        mSessions.bind(mTerminal);
    }

    private void confirmUpdateOpencode() {
        if (!mInstaller.isInstalled()) {
            showSetup();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Update opencode?")
            .setMessage("The latest linux-arm64 release will be downloaded and replaced. "
                + "Sessions should be restarted afterwards.")
            .setPositiveButton("Update", (d, w) -> {
                mUpdateDialog = new AlertDialog.Builder(this)
                    .setTitle("Updating opencode")
                    .setMessage("Downloading the latest release…")
                    .setCancelable(false)
                    .show();
                mInstaller.updateOpencode();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("Reset Debian container?")
            .setMessage("This permanently deletes the Debian userland, installed packages and all "
                + "opencode sessions/configuration inside it. Files in Android shared storage are "
                + "not touched.")
            .setPositiveButton("Delete everything", (d, w) -> {
                mServer.stop(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mSessions.closeAll();
                    mInstaller.deleteRootfs(() -> {
                        showSetup();
                        renderInstallerState(mInstaller.getState());
                        toast("Container removed");
                    });
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(10));
        scroll.addView(box);

        TextView fontLabel = new TextView(this);
        fontLabel.setText("Terminal font size");
        fontLabel.setTextColor(color(R.color.text));
        fontLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(fontLabel);

        mSettingsFontLabel = new TextView(this);
        mSettingsFontLabel.setText(mSessions.fontSizeSp() + " sp");
        mSettingsFontLabel.setTextColor(color(R.color.text_dim));
        mSettingsFontLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(mSettingsFontLabel);

        SeekBar seek = new SeekBar(this);
        seek.setMax(24);
        seek.setProgress(mSessions.fontSizeSp() - 8);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                mSessions.setFontSizeSp(8 + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(seek);

        CheckBox extra = new CheckBox(this);
        extra.setText("Show extra keys row");
        extra.setTextColor(color(R.color.text));
        extra.setChecked(Prefs.extraKeys());
        extra.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setExtraKeys(checked);
            if (!mWebMode) mExtraScroll.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        box.addView(extra);

        CheckBox keep = new CheckBox(this);
        keep.setText("Keep screen on while using the terminal");
        keep.setTextColor(color(R.color.text));
        keep.setChecked(Prefs.keepScreenOn());
        keep.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setKeepScreenOn(checked);
            applyKeepScreenOn();
        });
        box.addView(keep);

        CheckBox storage = new CheckBox(this);
        storage.setText("Mount Android shared storage at /root/storage");
        storage.setTextColor(color(R.color.text));
        storage.setChecked(Prefs.sharedStorage());
        storage.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setSharedStorage(checked);
            if (checked) requestStoragePermission();
        });
        box.addView(storage);

        TextView note = new TextView(this);
        note.setText("Storage and font changes apply to new terminal sessions.");
        note.setTextColor(color(R.color.text_dim));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(14);
        box.addView(actions, actionsLp);

        TextView update = tabButton("Update opencode");
        update.setOnClickListener(v -> {
            if (mSettingsDialog != null) mSettingsDialog.dismiss();
            confirmUpdateOpencode();
        });
        actions.addView(update, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView repair = tabButton("Repair container (reinstall packages + opencode)");
        repair.setOnClickListener(v -> {
            if (mSettingsDialog != null) mSettingsDialog.dismiss();
            new AlertDialog.Builder(this)
                .setTitle("Repair container?")
                .setMessage("Re-runs apt-get and reinstalls opencode. Existing files and "
                    + "configuration are kept.")
                .setPositiveButton("Repair", (d, w) -> {
                    mUpdateDialog = new AlertDialog.Builder(this)
                        .setTitle("Repairing container")
                        .setMessage("Reinstalling packages and opencode…")
                        .setCancelable(false)
                        .show();
                    mInstaller.repair();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        LinearLayout.LayoutParams repairLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        repairLp.topMargin = dp(8);
        actions.addView(repair, repairLp);

        mSettingsDialog = new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show();
    }

    private void showLogs() {
        StringBuilder log = new StringBuilder();
        log.append("=== INSTALL LOG ===\n");
        for (String line : mInstaller.getLogTail()) log.append(line).append('\n');
        log.append("\n=== SERVER LOG ===\n");
        List<String> server = mServer.logTail();
        if (server.isEmpty()) {
            log.append("(not running)\n");
        } else {
            for (String line : server) log.append(line).append('\n');
        }

        TextView tv = new TextView(this);
        tv.setText(log.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle("Logs")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("logs", log.toString()));
                toast("Logs copied");
            })
            .show();
    }

    private void showAbout() {
        String version = Prefs.installedVersion();
        new AlertDialog.Builder(this)
            .setTitle("OpenCode Mobile")
            .setMessage("A native Android home for the opencode AI coding agent.\n\n"
                + "Container: Debian 13 (trixie), arm64\n"
                + "opencode: " + (version.isEmpty() ? "not installed" : version) + "\n"
                + "Runtime: PRoot (no root required)\n\n"
                + "opencode is built by Anomaly. This is an unofficial client.\n\n"
                + "Terminal emulation is based on the Termux terminal-emulator (GPLv3). "
                + "PRoot, talloc and busybox are used under their respective licenses. "
                + "Source and license notices are included with the project.")
            .setPositiveButton("Close", null)
            .setNeutralButton("opencode.ai", (d, w) -> openExternal(Uri.parse("https://opencode.ai")))
            .show();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void applyKeepScreenOn() {
        if (Prefs.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(mTerminal.getWindowToken(), 0);
        } else {
            mTerminal.requestFocus();
            imm.showSoftInput(mTerminal, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
            && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    private String installedLabel() {
        String v = Prefs.installedVersion();
        return v.isEmpty() ? "Debian 13 · arm64" : "Debian 13 · opencode " + v;
    }

    private int color(int res) {
        return getResources().getColor(res, getTheme());
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void toast(String message) {
        if (!TextUtils.isEmpty(message)) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && mFileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            mFileCallback.onReceiveValue(result);
            mFileCallback = null;
        }
    }
}
