package com.deepseek.dsh.mobile;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.deepseek.dsh.mobile.linux.EnvironmentDownloader;
import com.deepseek.dsh.mobile.linux.ProotRuntime;
import com.deepseek.dsh.mobile.linux.SetupManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DeepSeek Harness 手机版
 *
 * 基于 proot Ubuntu 的完全自包含方案
 * 不需要 root，不需要 Termux
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DSH";
    private static final String DSH_URL = "http://localhost:3080";
    private static final int RETRY_DELAY_MS = 2000;
    private static final int MAX_RETRIES = 90; // 最多等 3 分钟

    // UI
    private WebView webView;
    private ProgressBar progressBar;
    private View loadingView;
    private TextView statusText;
    private Button actionButton;

    // 步骤指示
    private View stepIndicator;
    private TextView step1, step2, step3, step4, step5;

    // 日志
    private ScrollView logScrollView;
    private TextView logText;
    private StringBuilder logBuffer = new StringBuilder();

    // 核心
    private SetupManager setupManager;
    private EnvironmentDownloader downloader;
    private Handler handler;
    private int retryCount = 0;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(ContextCompat.getColor(this, R.color.primary_dark));
        }

        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        setupManager = new SetupManager(this);
        downloader = new EnvironmentDownloader(this);

        initViews();
        setupWebView();
        startFlow();
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        loadingView = findViewById(R.id.loadingView);
        statusText = findViewById(R.id.statusText);
        actionButton = findViewById(R.id.retryButton);

        stepIndicator = findViewById(R.id.stepIndicator);
        step1 = findViewById(R.id.step1);
        step2 = findViewById(R.id.step2);
        step3 = findViewById(R.id.step3);
        step4 = findViewById(R.id.step4);
        step5 = findViewById(R.id.step5);

        logScrollView = findViewById(R.id.logScrollView);
        logText = findViewById(R.id.logText);

        actionButton.setOnClickListener(v -> startFlow());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap f) {
                progressBar.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView v, String url) {
                progressBar.setVisibility(View.GONE);
                loadingView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) appendLog("页面加载失败");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                if (p == 100) progressBar.setVisibility(View.GONE);
            }
        });
    }

    // ===================== 主流程 =====================

    private void startFlow() {
        retryCount = 0;
        actionButton.setVisibility(View.GONE);
        logBuffer.setLength(0);
        logText.setText("");
        stepIndicator.setVisibility(View.VISIBLE);
        logScrollView.setVisibility(View.VISIBLE);
        resetSteps();

        appendLog("=== DSH Mobile 启动 ===");
        appendLog("架构: " + Build.SUPPORTED_ABIS[0]);

        if (setupManager.isReady()) {
            // 已经装好，直接启动服务器
            appendLog("环境已就绪，直接启动服务器");
            startServer();
        } else {
            // 需要完整安装
            runSetup();
        }
    }

    /**
     * 完整安装流程
     */
    private void runSetup() {
        setupManager.setCallback(new SetupManager.SetupCallback() {
            @Override
            public void onStepChanged(int step, String title, String detail) {
                updateStatus(detail);
                switch (step) {
                    case 1: updateStep(step1, "⏳", title); break;
                    case 2: updateStep(step2, "⏳", title); break;
                    case 3: updateStep(step3, "⏳", title); break;
                    case 4: updateStep(step4, "⏳", title); break;
                    case 5: updateStep(step5, "⏳", title); break;
                }
                // 标记之前的步骤为完成
                if (step > 1) updateStep(step1, "✅", getStepText(step1));
                if (step > 2) updateStep(step2, "✅", getStepText(step2));
                if (step > 3) updateStep(step3, "✅", getStepText(step3));
                if (step > 4) updateStep(step4, "✅", getStepText(step4));
            }

            @Override
            public void onLog(String message) {
                appendLog(message);
            }

            @Override
            public void onProgress(int percent) {
                progressBar.setProgress(percent);
            }

            @Override
            public void onSuccess() {
                updateStep(step5, "✅", "DSH 服务器运行中");
                appendLog("✅ 安装完成！服务器已启动");
                // 开始等待服务器就绪
                waitForServer();
            }

            @Override
            public void onError(String error) {
                appendLog("❌ 错误: " + error);
                showError("安装失败: " + error);
            }
        });

        // 先下载 proot 和 rootfs，然后执行安装
        new Thread(() -> {
            try {
                // 下载 proot
                updateStep(step1, "⬇️", "下载 PRoot 运行时...");
                updateStatus("正在下载 PRoot...");
                downloader.setCallback(new EnvironmentDownloader.DownloadCallback() {
                    @Override public void onStatusUpdate(String s) { appendLog(s); }
                    @Override public void onProgress(long d, long t) {
                        int pct = t > 0 ? (int)(d * 100 / t) : 0;
                        appendLog(String.format("  下载进度: %d%% (%.1fMB/%.1fMB)", pct, d/1048576.0, t/1048576.0));
                    }
                    @Override public void onComplete() {}
                    @Override public void onError(String e) { appendLog("下载错误: " + e); }
                });

                downloader.downloadProot();
                updateStep(step1, "✅", "PRoot 就绪");
                appendLog("PRoot 准备完成 ✓");

                // 下载 Ubuntu rootfs
                updateStep(step2, "⬇️", "下载 Ubuntu 系统...");
                updateStatus("正在下载 Ubuntu（约30MB）...");
                downloader.downloadRootfs();
                updateStep(step2, "✅", "Ubuntu 已安装");
                appendLog("Ubuntu rootfs 安装完成 ✓");

                // 继续执行安装（Node.js + DSH）
                handler.post(() -> setupManager.runFullSetup());

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                handler.post(() -> showError("下载失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 已安装的情况，仅启动服务器
     */
    private void startServer() {
        updateStep(step5, "⏳", "启动 DSH 服务器...");
        updateStatus("正在启动服务器...");
        appendLog("启动 DSH 服务器...");

        setupManager.setCallback(new SetupManager.SetupCallback() {
            @Override public void onStepChanged(int s, String t, String d) { updateStatus(d); }
            @Override public void onLog(String m) { appendLog(m); }
            @Override public void onProgress(int p) { progressBar.setProgress(p); }
            @Override public void onSuccess() {
                updateStep(step5, "✅", "DSH 服务器运行中");
                appendLog("✅ 服务器已启动");
                waitForServer();
            }
            @Override public void onError(String e) {
                appendLog("❌ " + e);
                showError("启动失败: " + e);
            }
        });

        setupManager.startServerOnly();
    }

    /**
     * 等待服务器端口就绪
     */
    private void waitForServer() {
        appendLog("检测端口 localhost:3080 ...");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPortOpen()) {
                    appendLog("✅ 端口 3080 就绪！");
                    appendLog("加载 DSH 界面...");
                    webView.loadUrl(DSH_URL);
                    return;
                }

                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    appendLog("⚠️ 超时，请检查服务器日志");
                    showError("服务器启动超时，请查看日志排查");
                    return;
                }

                if (retryCount % 10 == 0) {
                    appendLog("等待中... (" + retryCount + "/" + MAX_RETRIES + ")");
                }

                handler.postDelayed(this, RETRY_DELAY_MS);
            }
        }, 3000);
    }

    private boolean isPortOpen() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("localhost", 3080), 2000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== UI 辅助 =====================

    private void showError(String msg) {
        statusText.setText(msg);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error));
        actionButton.setText("🔄 重试");
        actionButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void updateStatus(String msg) {
        statusText.setText(msg);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
    }

    private void resetSteps() {
        TextView[] steps = {step1, step2, step3, step4, step5};
        String[] labels = {"初始化 PRoot", "安装 Ubuntu", "安装 Node.js", "部署服务器", "启动服务"};
        for (int i = 0; i < steps.length; i++) {
            steps[i].setText("○ " + labels[i]);
            steps[i].setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        }
    }

    private void updateStep(TextView v, String icon, String text) {
        // 只更新 icon 部分，保留步骤名
        String current = v.getText().toString();
        String label = current.contains(" ") ? current.substring(current.indexOf(" ")) : text;
        v.setText(icon + " " + text);
        if ("✅".equals(icon)) {
            v.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if ("❌".equals(icon)) {
            v.setTextColor(ContextCompat.getColor(this, R.color.error));
        } else {
            v.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }
    }

    private String getStepText(TextView v) {
        String t = v.getText().toString();
        return t.contains(" ") ? t.substring(t.indexOf(" ") + 1) : "";
    }

    private void appendLog(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        Log.d(TAG, msg);
        logBuffer.append(line);
        handler.post(() -> {
            logText.setText(logBuffer.toString());
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
        if (setupManager != null) setupManager.stopServer();
        if (downloader != null) downloader.cancel();
    }
}
