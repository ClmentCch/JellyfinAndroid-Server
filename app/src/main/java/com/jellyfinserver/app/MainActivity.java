package com.jellyfinserver.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Single screen application: install, start and monitor the embedded server. */
public class MainActivity extends Activity implements ServerManager.Listener {

    private static final int REQUEST_STORAGE = 1001;

    private ServerManager manager;

    private WebView webView;
    private View panelScroll;
    private View statusDot;
    private TextView statusText;
    private TextView panelTitle;
    private TextView panelText;
    private TextView installProgressText;
    private TextView urlLocal;
    private TextView urlLanLabel;
    private TextView urlLan;
    private TextView btnPrimary;
    private TextView btnToggle;
    private TextView btnLogs;
    private TextView btnSettings;
    private TextView btnBrowser;
    private TextView mediaHint;
    private View urlsCard;
    private ProgressBar installProgress;

    private boolean webLoaded;
    private int lastInstallPercent;
    private String lastInstallMessage = "";
    private TextView logDialogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = ServerManager.get(this);
        bindViews();
        configureWebView();
        configureActions();
        requestStoragePermissionIfNeeded();
        refreshUi();
        maybeShowBatteryPrompt();
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.addListener(this);
        refreshUi();
    }

    @Override
    protected void onStop() {
        manager.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private void bindViews() {
        webView = findViewById(R.id.web_view);
        panelScroll = findViewById(R.id.panel_scroll);
        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        panelTitle = findViewById(R.id.panel_title);
        panelText = findViewById(R.id.panel_text);
        installProgressText = findViewById(R.id.install_progress_text);
        installProgress = findViewById(R.id.install_progress);
        urlsCard = findViewById(R.id.urls_card);
        urlLocal = findViewById(R.id.url_local);
        urlLanLabel = findViewById(R.id.url_lan_label);
        urlLan = findViewById(R.id.url_lan);
        mediaHint = findViewById(R.id.media_hint);
        btnPrimary = findViewById(R.id.btn_primary);
        btnToggle = findViewById(R.id.btn_toggle);
        btnLogs = findViewById(R.id.btn_logs);
        btnSettings = findViewById(R.id.btn_settings);
        btnBrowser = findViewById(R.id.btn_browser);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setBackgroundColor(0xFF0B0E13);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                if (failingUrl != null && failingUrl.equals(AppConfig.LOCAL_URL)) {
                    Toast.makeText(MainActivity.this, R.string.web_error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void configureActions() {
        btnPrimary.setOnClickListener(view -> onPrimaryAction());
        btnToggle.setOnClickListener(view -> {
            ServerManager.State state = manager.getState();
            if (state == ServerManager.State.RUNNING
                    || state == ServerManager.State.STARTING
                    || state == ServerManager.State.STOPPING) {
                stopServer();
            } else if (!manager.isInstalled()) {
                manager.install();
            } else {
                startServer();
            }
        });
        btnLogs.setOnClickListener(view -> showLogs());
        btnSettings.setOnClickListener(view -> showSettings());
        btnBrowser.setOnClickListener(view -> openInBrowser());
    }

    private void onPrimaryAction() {
        ServerManager.State state = manager.getState();
        if (state == ServerManager.State.RUNNING) {
            openInBrowser();
        } else if (!manager.isInstalled()) {
            manager.install();
        } else {
            startServer();
        }
    }

    private void startServer() {
        Intent intent = new Intent(this, ServerService.class)
                .setAction(ServerService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopServer() {
        Intent intent = new Intent(this, ServerService.class)
                .setAction(ServerService.ACTION_STOP);
        startService(intent);
    }

    // ---------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------

    private void refreshUi() {
        ServerManager.State state = manager.getState();
        boolean installed = manager.isInstalled();
        webView.setVisibility(state == ServerManager.State.RUNNING ? View.VISIBLE : View.GONE);
        panelScroll.setVisibility(state == ServerManager.State.RUNNING ? View.GONE : View.VISIBLE);
        statusText.setText(statusLabel(state));
        statusDot.setBackgroundResource(statusDot(state));

        switch (state) {
            case NOT_INSTALLED:
                panelTitle.setText(R.string.install_title);
                panelText.setText(R.string.install_text);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setText(R.string.btn_install);
                installProgress.setVisibility(View.GONE);
                installProgressText.setVisibility(View.GONE);
                urlsCard.setVisibility(View.GONE);
                break;
            case INSTALLING:
                panelTitle.setText(R.string.status_installing);
                panelText.setText(R.string.install_wait);
                btnPrimary.setVisibility(View.GONE);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.setIndeterminate(false);
                installProgress.setProgress(lastInstallPercent);
                installProgressText.setVisibility(View.VISIBLE);
                installProgressText.setText(getString(
                        R.string.install_progress, lastInstallPercent,
                        lastInstallMessage.isEmpty()
                                ? getString(R.string.install_wait) : lastInstallMessage));
                urlsCard.setVisibility(View.GONE);
                break;
            case STOPPED:
                panelTitle.setText(installed
                        ? R.string.panel_ready_title : R.string.install_title);
                panelText.setText(installed ? R.string.panel_ready_text : R.string.install_text);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setText(installed ? R.string.btn_start : R.string.btn_install);
                installProgress.setVisibility(View.GONE);
                installProgressText.setVisibility(View.GONE);
                urlsCard.setVisibility(installed ? View.VISIBLE : View.GONE);
                break;
            case STARTING:
                panelTitle.setText(R.string.panel_starting_title);
                panelText.setText(R.string.panel_starting_text);
                btnPrimary.setVisibility(View.GONE);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.setIndeterminate(true);
                installProgressText.setVisibility(View.GONE);
                urlsCard.setVisibility(View.VISIBLE);
                break;
            case STOPPING:
                panelTitle.setText(R.string.status_stopping);
                panelText.setText(R.string.panel_ready_text);
                btnPrimary.setVisibility(View.GONE);
                installProgress.setVisibility(View.VISIBLE);
                installProgress.setIndeterminate(true);
                installProgressText.setVisibility(View.GONE);
                urlsCard.setVisibility(View.VISIBLE);
                break;
            case RUNNING:
                if (!webLoaded) {
                    webLoaded = true;
                    webView.loadUrl(AppConfig.LOCAL_URL);
                }
                break;
            case ERROR:
            default:
                panelTitle.setText(R.string.panel_error_title);
                String detail = manager.getStateDetail();
                panelText.setText(detail.isEmpty() ? getString(R.string.panel_error_text) : detail);
                btnPrimary.setVisibility(View.VISIBLE);
                btnPrimary.setText(installed ? R.string.btn_start : R.string.btn_install);
                installProgress.setVisibility(View.GONE);
                installProgressText.setVisibility(View.GONE);
                urlsCard.setVisibility(installed ? View.VISIBLE : View.GONE);
                break;
        }

        if (state != ServerManager.State.RUNNING) {
            webLoaded = false;
        }
        updateUrls(installed);
        updateFooter(state);
    }

    private void updateUrls(boolean installed) {
        urlLocal.setText(AppConfig.LOCAL_URL);
        String lanUrl = NetUtils.getLanUrl();
        if (lanUrl == null) {
            urlLanLabel.setVisibility(View.GONE);
            urlLan.setVisibility(View.GONE);
            btnBrowser.setVisibility(View.GONE);
        } else {
            urlLanLabel.setVisibility(View.VISIBLE);
            urlLan.setVisibility(View.VISIBLE);
            urlLan.setText(lanUrl);
            btnBrowser.setVisibility(View.VISIBLE);
        }
        mediaHint.setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    private void updateFooter(ServerManager.State state) {
        switch (state) {
            case NOT_INSTALLED:
                btnToggle.setText(R.string.btn_install);
                btnToggle.setEnabled(true);
                btnToggle.setAlpha(1f);
                break;
            case INSTALLING:
                btnToggle.setText(R.string.status_installing);
                btnToggle.setEnabled(false);
                btnToggle.setAlpha(0.5f);
                break;
            case RUNNING:
            case STARTING:
                btnToggle.setText(R.string.btn_stop);
                btnToggle.setEnabled(true);
                btnToggle.setAlpha(1f);
                break;
            case STOPPING:
                btnToggle.setText(R.string.status_stopping);
                btnToggle.setEnabled(false);
                btnToggle.setAlpha(0.5f);
                break;
            case ERROR:
                btnToggle.setText(R.string.btn_start);
                btnToggle.setEnabled(true);
                btnToggle.setAlpha(1f);
                break;
            case STOPPED:
            default:
                btnToggle.setText(R.string.btn_start);
                btnToggle.setEnabled(true);
                btnToggle.setAlpha(1f);
                break;
        }
    }

    private CharSequence statusLabel(ServerManager.State state) {
        switch (state) {
            case NOT_INSTALLED:
                return getString(R.string.status_not_installed);
            case INSTALLING:
                return getString(R.string.status_installing);
            case STARTING:
                return getString(R.string.status_starting);
            case RUNNING:
                return getString(R.string.status_running);
            case STOPPING:
                return getString(R.string.status_stopping);
            case ERROR:
                return getString(R.string.status_error);
            case STOPPED:
            default:
                return getString(R.string.status_stopped);
        }
    }

    private int statusDot(ServerManager.State state) {
        switch (state) {
            case RUNNING:
                return R.drawable.dot_on;
            case STARTING:
            case INSTALLING:
            case STOPPING:
                return R.drawable.dot_busy;
            case ERROR:
                return R.drawable.dot_error;
            default:
                return R.drawable.dot_off;
        }
    }

    // ---------------------------------------------------------------------
    // ServerManager.Listener
    // ---------------------------------------------------------------------

    @Override
    public void onStateChanged(ServerManager.State state, String detail) {
        refreshUi();
        if (state == ServerManager.State.RUNNING) {
            maybeShowBatteryPrompt();
        }
    }

    @Override
    public void onLog(String line) {
        if (logDialogView != null) {
            logDialogView.append(line);
            logDialogView.append("\n");
        }
        if (manager.getState() == ServerManager.State.ERROR) {
            // Keep the error panel up to date with the last lines.
            refreshUi();
        }
    }

    @Override
    public void onInstallProgress(int percent, String message) {
        lastInstallPercent = percent;
        lastInstallMessage = message == null ? "" : message;
        if (manager.getState() == ServerManager.State.INSTALLING) {
            installProgress.setIndeterminate(false);
            installProgress.setProgress(percent);
            installProgressText.setVisibility(View.VISIBLE);
            installProgressText.setText(getString(R.string.install_progress, percent, lastInstallMessage));
        }
    }

    // ---------------------------------------------------------------------
    // Dialogs and intents
    // ---------------------------------------------------------------------

    private void showLogs() {
        TextView view = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        view.setTextSize(10f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextColor(getColor(R.color.text));
        view.setText(manager.getLogText());
        view.setVerticalScrollBarEnabled(true);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logDialogView = view;

        new AlertDialog.Builder(this)
                .setTitle(R.string.log_title)
                .setView(scrollView)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText("JellyfinServer", manager.getLogText()));
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setOnDismissListener(dialog -> logDialogView = null)
                .show();
    }

    private void showSettings() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        CheckBox autostart = new CheckBox(this);
        autostart.setText(R.string.settings_autostart);
        autostart.setChecked(Prefs.isAutostartEnabled(this));
        autostart.setOnCheckedChangeListener((button, checked) ->
                Prefs.setAutostartEnabled(this, checked));
        container.addView(autostart);

        TextView version = new TextView(this);
        version.setText(getString(R.string.settings_data_usage, AppConfig.SERVER_VERSION));
        version.setPadding(0, padding / 2, 0, 0);
        version.setTextColor(getColor(R.color.text_muted));
        container.addView(version);

        Button reset = new Button(this);
        reset.setText(R.string.settings_reset);
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> confirmReset());
        container.addView(reset);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(container)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_text)
                .setPositiveButton(R.string.reset_action, (dialog, which) -> manager.reset())
                .setNegativeButton(R.string.later, null)
                .show();
    }

    private void openInBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.LOCAL_URL));
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, R.string.open_in_browser_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != REQUEST_STORAGE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
            return;
        }
        if (manager.getState() == ServerManager.State.RUNNING) {
            Toast.makeText(this, "Redémarrez le serveur pour accéder aux médias.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void maybeShowBatteryPrompt() {
        if (Prefs.isBatteryPromptShown(this) || !manager.isInstalled()) {
            return;
        }
        Prefs.setBatteryPromptShown(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_text)
                .setPositiveButton(R.string.battery_action, (dialog, which) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception error) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) {
                            // The device does not expose those settings.
                        }
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }
}
