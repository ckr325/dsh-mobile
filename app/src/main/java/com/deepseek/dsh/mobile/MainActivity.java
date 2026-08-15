package com.deepseek.dsh.mobile;

import android.annotation.SuppressLint;
import android.content.Intent;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DSH";
    private static final String DSH_URL = "http://localhost:3080";
    private static final int RETRY_DELAY_MS = 3000;
    private static final int MAX_RETRIES = 60; // 最多等 3 分钟

    private WebView webView;
    private ProgressBar progressBar;
    private View loadingView;
    private TextView statusText;
    private Button actionButton;

    // 步骤指示器
    private View stepIndicator;
    private TextView step1, step2, step3, step4;

    // 日志
    private ScrollView logScrollView;
    private TextView logText;
    private StringBuilder logBuffer = new StringBuilder();

    private TermuxSetup termuxSetup;
    private Handler handler;
    private int retryCount = 0;
    private boolean isServerStarting = false;
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
        termuxSetup = new TermuxSetup(this);

        initViews();
        setupWebView();

        // 开始启动流程
        startSetupFlow();
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

        logScrollView = findViewById(R.id.logScrollView);
        logText = findViewById(R.id.logText);

        actionButton.setOnClickListener(v -> startSetupFlow());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                showWebView();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    appendLog("页面加载失败: " + error.getDescription());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }
        });
    }

    // ===================== 启动流程 =====================

    private void startSetupFlow() {
        retryCount = 0;
        isServerStarting = false;
        actionButton.setVisibility(View.GONE);
        logBuffer.setLength(0);
        logText.setText("");

        stepIndicator.setVisibility(View.VISIBLE);
        logScrollView.setVisibility(View.VISIBLE);

        resetSteps();
        appendLog("=== DSH Mobile 启动 ===");

        checkTermux();
    }

    /**
     * 步骤 1：检查 Termux
     */
    private void checkTermux() {
        updateStep(step1, "⏳", "检查 Termux 运行环境...");
        updateStatus("正在检查运行环境...");
        appendLog("检查 Termux 是否已安装...");

        if (!termuxSetup.isTermuxInstalled()) {
            updateStep(step1, "❌", "Termux 未安装");
            appendLog("Termux 未安装，需要用户手动安装");
            showInstallTermuxButton();
            return;
        }

        updateStep(step1, "✅", "Termux 已安装");
        appendLog("Termux 已安装 ✓");

        // 继续下一步
        checkNode();
    }

    /**
     * 步骤 2：检查 Node.js
     */
    private void checkNode() {
        updateStep(step2, "⏳", "检查 Node.js...");
        updateStatus("正在检查 Node.js...");
        appendLog("检查 Node.js 是否可用...");

        if (!termuxSetup.isNodeInstalled()) {
            updateStep(step2, "⬇️", "首次运行，需要安装 Node.js");
            appendLog("Node.js 未安装，启动 Termux 安装...");
            installNode();
            return;
        }

        updateStep(step2, "✅", "Node.js 已安装");
        appendLog("Node.js 已安装 ✓");
        prepareAndStart();
    }

    /**
     * 步骤 2 执行：安装 Node.js
     */
    private void installNode() {
        try {
            // 准备服务器文件到外部存储（Termux 可访问）
            updateStatus("正在准备服务器文件...");
            appendLog("复制 DSH 服务器文件...");
            copyServerToTermux();
            appendLog("服务器文件准备完成 ✓");

            // 构建 Termux 命令
            String extPath = getExternalFilesDir(null) + "/dsh-server";
            String cmd = String.format(
                "echo '[DSH] 开始安装环境...' && " +
                "pkg update -y && " +
                "echo '[DSH] 安装 Node.js...' && " +
                "pkg install -y nodejs && " +
                "echo '[DSH] Node.js 版本:' && node --version && " +
                "mkdir -p ~/dsh-server && " +
                "cp %s/* ~/dsh-server/ && " +
                "echo '[DSH] 安装依赖...' && " +
                "cd ~/dsh-server && npm install --production && " +
                "echo '[DSH] ===SETUP_COMPLETE==='",
                extPath
            );

            appendLog("启动 Termux 执行安装命令...");
            appendLog("（请在 Termux 中等待安装完成）");
            updateStatus("正在 Termux 中安装 Node.js，请稍候...");

            Intent intent = termuxSetup.getTermuxCommandIntent(cmd);
            startService(intent);

            // 标记已安装（避免重复安装）
            termuxSetup.markNodeInstalled();

            // 等待足够时间让安装完成（首次安装 Node.js 约 2-5 分钟）
            appendLog("等待 Node.js 安装完成（约2-5分钟）...");
            updateStep(step2, "⚙️", "Node.js 安装中（请查看 Termux）...");

            // 定时检查
            handler.postDelayed(() -> {
                appendLog("尝试启动服务器...");
                prepareAndStart();
            }, 30000); // 30 秒后尝试

        } catch (Exception e) {
            appendLog("安装失败: " + e.getMessage());
            updateStep(step2, "❌", "安装失败");
            showError("安装失败: " + e.getMessage());
        }
    }

    /**
     * 步骤 3 + 4：准备文件并启动服务器
     */
    private void prepareAndStart() {
        // 准备文件
        updateStep(step3, "⏳", "准备服务器文件...");
        updateStatus("准备服务器文件...");
        appendLog("准备 DSH 服务器文件...");

        try {
            termuxSetup.prepareScripts();
            termuxSetup.copyServerFiles();
            copyServerToTermux();
            updateStep(step3, "✅", "服务器文件就绪");
            appendLog("服务器文件准备完成 ✓");
        } catch (IOException e) {
            updateStep(step3, "❌", "文件准备失败");
            appendLog("文件准备失败: " + e.getMessage());
            showError("文件准备失败: " + e.getMessage());
            return;
        }

        // 启动服务器
        startDSHServer();
    }

    /**
     * 步骤 4：启动 DSH 服务器
     */
    private void startDSHServer() {
        if (isServerStarting) return;
        isServerStarting = true;

        updateStep(step4, "⏳", "启动 DSH 服务器...");
        updateStatus("正在启动 DSH 服务器...");
        appendLog("通过 Termux 启动 DSH 服务器...");

        try {
            String cmd = "cd ~/dsh-server && node index.js &";
            Intent intent = termuxSetup.getTermuxCommandIntent(cmd);
            startService(intent);

            appendLog("启动命令已发送，等待服务就绪...");
            appendLog("检测端口 localhost:3080 ...");

            // 等 5 秒后开始检测
            handler.postDelayed(() -> waitForServer(), 5000);

        } catch (Exception e) {
            isServerStarting = false;
            appendLog("启动失败: " + e.getMessage());
            updateStep(step4, "❌", "启动失败");
            showError("启动服务器失败: " + e.getMessage());
        }
    }

    /**
     * 等待服务器就绪
     */
    private void waitForServer() {
        if (retryCount >= MAX_RETRIES) {
            isServerStarting = false;
            appendLog("等待超时！服务器未能在端口 3080 上启动");
            appendLog("可能原因：");
            appendLog("  1. Termux 中 Node.js 未安装完成");
            appendLog("  2. DSH 服务器启动报错");
            appendLog("  3. 端口 3080 被占用");
            appendLog("请打开 Termux 查看日志，或点击重试");
            updateStep(step4, "❌", "启动超时");
            showError("服务器启动超时，请检查 Termux 中的输出日志");
            return;
        }

        retryCount++;
        updateStatus(String.format("等待服务就绪... (%d/%d)", retryCount, MAX_RETRIES));

        if (retryCount % 5 == 0) {
            appendLog(String.format("等待中... 第 %d 次尝试 (%ds)", retryCount, retryCount * 3));
        }

        handler.postDelayed(() -> {
            if (isServerRunning()) {
                appendLog("✅ 端口 3080 已就绪！");
                updateStep(step4, "✅", "DSH 服务器运行中");
                isServerStarting = false;
                loadDSH();
            } else {
                waitForServer();
            }
        }, RETRY_DELAY_MS);
    }

    // ===================== 辅助方法 =====================

    private boolean isServerRunning() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("localhost", 3080), 2000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadDSH() {
        updateStatus("正在加载界面...");
        appendLog("加载 WebView 界面...");
        webView.loadUrl(DSH_URL);
    }

    private void showWebView() {
        loadingView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        appendLog("✅ 界面加载完成！");
    }

    /**
     * 复制服务器文件到 Termux 可访问的位置
     */
    private void copyServerToTermux() throws IOException {
        File extDir = new File(getExternalFilesDir(null), "dsh-server");
        if (!extDir.exists()) extDir.mkdirs();

        String[] assetFiles = {"nodejs-project/index.js", "nodejs-project/package.json"};
        for (String asset : assetFiles) {
            String fileName = new File(asset).getName();
            File dest = new File(extDir, fileName);
            if (!dest.exists()) {
                InputStream is = getAssets().open(asset);
                OutputStream os = new FileOutputStream(dest);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                os.flush();
                os.close();
                is.close();
                appendLog("  已复制: " + fileName);
            }
        }
    }

    // ===================== UI 辅助 =====================

    private void showInstallTermuxButton() {
        actionButton.setText("📦 安装 Termux");
        actionButton.setVisibility(View.VISIBLE);
        actionButton.setOnClickListener(v -> {
            termuxSetup.openTermuxInstall(this);
            appendLog("已跳转到 Termux 安装页面...");
            updateStatus("请完成 Termux 安装后返回");
            actionButton.setText("✅ 已安装，继续");
            actionButton.setOnClickListener(v2 -> startSetupFlow());
        });
    }

    private void showError(String message) {
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error));
        actionButton.setText("🔄 重试");
        actionButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void updateStatus(String message) {
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
    }

    private void resetSteps() {
        step1.setText("○ 检查运行环境");
        step1.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        step2.setText("○ 安装 Node.js");
        step2.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        step3.setText("○ 准备服务器文件");
        step3.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        step4.setText("○ 启动 DSH 服务器");
        step4.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
    }

    private void updateStep(TextView stepView, String icon, String text) {
        stepView.setText(icon + " " + text);
        if (icon.equals("✅")) {
            stepView.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if (icon.equals("❌")) {
            stepView.setTextColor(ContextCompat.getColor(this, R.color.error));
        } else {
            stepView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }
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
    protected void onResume() {
        super.onResume();
        if (termuxSetup.isTermuxInstalled() && !isServerStarting && webView.getVisibility() != View.VISIBLE) {
            appendLog("从 Termux 返回，重新检查...");
            startSetupFlow();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }
}
