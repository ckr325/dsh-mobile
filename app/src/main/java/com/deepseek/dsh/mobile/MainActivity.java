package com.deepseek.dsh.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * DeepSeek Harness 手机版 - 主界面
 *
 * 启动流程：
 * 1. 检查 Termux 是否安装
 * 2. 引导用户安装 Termux（如果未安装）
 * 3. 检查 Node.js 是否安装
 * 4. 自动安装 Node.js（如果未安装）
 * 5. 复制 DSH 服务器文件
 * 6. 通过 Termux 启动 DSH 服务器
 * 7. WebView 连接本地 DSH 服务
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String DSH_URL = "http://localhost:3080";
    private static final int RETRY_DELAY_MS = 2000;
    private static final int MAX_RETRIES = 30;
    private static final int REQUEST_TERMUX_INSTALL = 1001;

    private WebView webView;
    private ProgressBar progressBar;
    private View loadingView;
    private TextView statusText;
    private Button actionButton;

    private TermuxSetup termuxSetup;
    private Handler handler;
    private int retryCount = 0;
    private boolean isServerStarting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式
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
        settings.setMediaPlaybackRequiresUserGesture(false);
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
                hideLoading();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showError("页面加载失败，请稍后重试");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * 启动设置流程
     */
    private void startSetupFlow() {
        retryCount = 0;
        actionButton.setVisibility(View.GONE);

        // 步骤 1：检查 Termux
        if (!termuxSetup.isTermuxInstalled()) {
            showStep("步骤 1/3：需要安装 Termux 运行环境", true);
            showInstallTermuxButton();
            return;
        }

        // 步骤 2：准备脚本和服务器文件
        showStep("步骤 2/3：准备服务器文件...", false);
        try {
            termuxSetup.prepareScripts();
            termuxSetup.copyServerFiles();
        } catch (IOException e) {
            showError("准备服务器文件失败: " + e.getMessage());
            return;
        }

        // 步骤 3：启动 DSH 服务
        if (!termuxSetup.isNodeInstalled()) {
            showStep("步骤 3/3：首次运行，正在安装 Node.js（约2-5分钟）...", false);
            startTermuxSetup();
        } else {
            showStep("正在启动 DSH 服务器...", false);
            startDSHServer();
        }
    }

    /**
     * 显示安装 Termux 按钮
     */
    private void showInstallTermuxButton() {
        actionButton.setText("安装 Termux");
        actionButton.setVisibility(View.VISIBLE);
        actionButton.setOnClickListener(v -> {
            termuxSetup.openTermuxInstall(this);
            // 等待用户安装后回来
            showStep("请完成 Termux 安装，安装后点击下方按钮继续", false);
            actionButton.setText("已安装，继续");
            actionButton.setOnClickListener(v2 -> startSetupFlow());
        });
    }

    /**
     * 通过 Termux 安装 Node.js
     */
    private void startTermuxSetup() {
        try {
            // 复制服务器文件到 Termux 可访问的位置
            copyServerToTermux();

            // 启动 Termux 执行安装脚本
            String cmd = "pkg update -y && pkg install -y nodejs && " +
                "mkdir -p ~/dsh-server && " +
                "cp /sdcard/Android/data/" + getPackageName() + "/files/dsh-server/* ~/dsh-server/ 2>/dev/null; " +
                "cd ~/dsh-server && npm install --production 2>/dev/null; " +
                "echo '===DSH_NODE_READY==='";

            Intent intent = termuxSetup.getTermuxCommandIntent(cmd);
            startService(intent);

            termuxSetup.markNodeInstalled();

            // 等待一会儿再启动服务器
            showStep("Node.js 安装中，请稍候...", false);
            handler.postDelayed(() -> {
                startDSHServer();
            }, 15000); // 等 15 秒

        } catch (Exception e) {
            Log.e(TAG, "启动 Termux 安装失败", e);
            showError("启动安装失败: " + e.getMessage());
        }
    }

    /**
     * 启动 DSH 服务器
     */
    private void startDSHServer() {
        if (isServerStarting) return;
        isServerStarting = true;

        try {
            // 通过 Termux 启动 DSH 服务器
            String cmd = "cd ~/dsh-server && node index.js &";
            Intent intent = termuxSetup.getTermuxCommandIntent(cmd);
            startService(intent);

            showStep("服务器启动中...", false);

            // 等待服务器就绪
            handler.postDelayed(() -> waitForServer(), 5000);

        } catch (Exception e) {
            isServerStarting = false;
            showError("启动服务器失败: " + e.getMessage());
        }
    }

    /**
     * 复制服务器文件到 Termux 可访问的位置
     */
    private void copyServerToTermux() throws IOException {
        // 外部存储目录，Termux 可以访问
        File extDir = new File(getExternalFilesDir(null), "dsh-server");
        if (!extDir.exists()) {
            extDir.mkdirs();
        }

        String[] assetFiles = {
            "nodejs-project/index.js",
            "nodejs-project/package.json"
        };

        for (String asset : assetFiles) {
            String fileName = new File(asset).getName();
            File dest = new File(extDir, fileName);
            if (!dest.exists()) {
                InputStream is = getAssets().open(asset);
                OutputStream os = new FileOutputStream(dest);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) {
                    os.write(buf, 0, len);
                }
                os.flush();
                os.close();
                is.close();
            }
        }
    }

    /**
     * 等待服务器就绪
     */
    private void waitForServer() {
        if (retryCount >= MAX_RETRIES) {
            isServerStarting = false;
            showError("服务器启动超时，请重试");
            return;
        }

        updateStatus("等待服务就绪... (" + (retryCount + 1) + "/" + MAX_RETRIES + ")");

        handler.postDelayed(() -> {
            if (isServerRunning()) {
                isServerStarting = false;
                loadDSH();
            } else {
                retryCount++;
                waitForServer();
            }
        }, RETRY_DELAY_MS);
    }

    private boolean isServerRunning() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("localhost", 3080), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadDSH() {
        updateStatus("正在加载界面...");
        webView.loadUrl(DSH_URL);
    }

    private void showStep(String message, boolean showButton) {
        loadingView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (!showButton) {
            actionButton.setVisibility(View.GONE);
        }
        progressBar.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        loadingView.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error));
        actionButton.setText("重试");
        actionButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideLoading() {
        loadingView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void updateStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 Termux 返回后，检查状态
        if (termuxSetup.isTermuxInstalled() && !isServerStarting && webView.getVisibility() != View.VISIBLE) {
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
