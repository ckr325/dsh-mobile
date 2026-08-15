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

import com.deepseek.dsh.mobile.linux.AssetExtractor;
import com.deepseek.dsh.mobile.linux.ProotRuntime;
import com.deepseek.dsh.mobile.linux.SetupManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DeepSeek Harness 手机版
 * 基于 proot Ubuntu，全部打包在 APK 中
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DSH";
    private static final String DSH_URL = "http://localhost:3080";
    private static final int RETRY_DELAY_MS = 2000;
    private static final int MAX_RETRIES = 90;

    private WebView webView;
    private ProgressBar progressBar;
    private View loadingView;
    private TextView statusText;
    private Button actionButton;
    private View stepIndicator;
    private TextView step1, step2, step3, step4, step5;
    private ScrollView logScrollView;
    private TextView logText;
    private StringBuilder logBuffer = new StringBuilder();

    private SetupManager setupManager;
    private AssetExtractor assetExtractor;
    private Handler handler;
    private int retryCount = 0;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(ContextCompat.getColor(this, R.color.primary_dark));
        }
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        setupManager = new SetupManager(this);
        assetExtractor = new AssetExtractor(this);

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
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { progressBar.setVisibility(View.VISIBLE); }
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
            appendLog("环境已就绪，直接启动服务器");
            startServer();
        } else {
            runSetup();
        }
    }

    /**
     * 完整安装流程：从 assets 解压 → 安装 Node.js → 启动服务器
     */
    private void runSetup() {
        new Thread(() -> {
            try {
                // 步骤 1：解压 proot
                handler.post(() -> {
                    updateStep(step1, "⏳", "解压 PRoot...");
                    updateStatus("正在解压 PRoot...");
                });
                appendLog("从 APK assets 解压 proot...");

                assetExtractor.setCallback(new AssetExtractor.ExtractCallback() {
                    @Override public void onStatusUpdate(String s) { appendLog(s); }
                    @Override public void onComplete() {}
                    @Override public void onError(String e) { appendLog("错误: " + e); }
                });

                assetExtractor.extractAll();

                handler.post(() -> updateStep(step1, "✅", "PRoot 就绪"));
                appendLog("PRoot + Ubuntu 解压完成 ✓");

                // 步骤 2：标记完成
                handler.post(() -> updateStep(step2, "✅", "Ubuntu 已安装"));

                // 步骤 3-5：安装 Node.js + 部署 + 启动
                handler.post(() -> setupManager.runFullSetup());

            } catch (Exception e) {
                Log.e(TAG, "解压失败", e);
                handler.post(() -> showError("解压失败: " + e.getMessage()));
            }
        }).start();

        // 设置 SetupManager 回调
        setupManager.setCallback(new SetupManager.SetupCallback() {
            @Override public void onStepChanged(int step, String title, String detail) {
                updateStatus(detail);
                if (step >= 3) updateStep(step3, "⏳", title);
                if (step >= 4) updateStep(step4, "⏳", title);
                if (step >= 5) updateStep(step5, "⏳", title);
                if (step > 3) updateStep(step3, "✅", getStepText(step3));
                if (step > 4) updateStep(step4, "✅", getStepText(step4));
            }

            @Override public void onLog(String m) { appendLog(m); }
            @Override public void onProgress(int p) { progressBar.setProgress(p); }

            @Override public void onSuccess() {
                updateStep(step5, "✅", "DSH 服务器运行中");
                appendLog("✅ 安装完成！服务器已启动");
                waitForServer();
            }

            @Override public void onError(String error) {
                appendLog("❌ 错误: " + error);
                showError("安装失败: " + error);
            }
        });
    }

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

    private void waitForServer() {
        appendLog("检测端口 localhost:3080 ...");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isPortOpen()) {
                    appendLog("✅ 端口 3080 就绪！");
                    webView.loadUrl(DSH_URL);
                    return;
                }
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    appendLog("⚠️ 超时");
                    showError("服务器启动超时");
                    return;
                }
                if (retryCount % 10 == 0) appendLog("等待中... (" + retryCount + "/" + MAX_RETRIES + ")");
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
        } catch (Exception e) { return false; }
    }

    // ===================== UI =====================

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
        String[] labels = {"解压 PRoot", "解压 Ubuntu", "安装 Node.js", "部署服务器", "启动服务"};
        for (int i = 0; i < steps.length; i++) {
            steps[i].setText("○ " + labels[i]);
            steps[i].setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        }
    }

    private void updateStep(TextView v, String icon, String text) {
        v.setText(icon + " " + text);
        if ("✅".equals(icon)) v.setTextColor(ContextCompat.getColor(this, R.color.success));
        else if ("❌".equals(icon)) v.setTextColor(ContextCompat.getColor(this, R.color.error));
        else v.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
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

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
        if (setupManager != null) setupManager.stopServer();
    }
}
