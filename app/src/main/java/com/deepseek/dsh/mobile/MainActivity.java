package com.deepseek.dsh.mobile;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * DeepSeek Harness 手机版 - 主界面
 * 
 * 功能：
 * 1. 启动/连接 Node.js 后台服务
 * 2. 显示 DSH Web 界面
 * 3. 处理网络状态变化
 */
public class MainActivity extends AppCompatActivity {
    
    private static final String DSH_URL = "http://localhost:3080";
    private static final int RETRY_DELAY_MS = 2000;
    private static final int MAX_RETRIES = 30;
    
    private WebView webView;
    private ProgressBar progressBar;
    private View loadingView;
    private TextView statusText;
    private Button retryButton;
    private SwipeRefreshLayout swipeRefresh;
    
    private NodeService nodeService;
    private boolean isBound = false;
    private int retryCount = 0;
    private Handler handler;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NodeService.NodeBinder binder = (NodeService.NodeBinder) service;
            nodeService = binder.getService();
            isBound = true;
            
            // 服务连接后，等待服务器启动
            waitForServer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            nodeService = null;
            isBound = false;
        }
    };

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
        
        initViews();
        setupWebView();
        
        // 启动 Node.js 服务
        startNodeService();
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        loadingView = findViewById(R.id.loadingView);
        statusText = findViewById(R.id.statusText);
        retryButton = findViewById(R.id.retryButton);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        
        retryButton.setOnClickListener(v -> {
            retryCount = 0;
            waitForServer();
        });
        
        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
            swipeRefresh.setRefreshing(false);
        });
        
        // 设置状态栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.primary_dark));
        }
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
        
        // 响应式布局
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        
        // 混合内容
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
                    showError("页面加载失败: " + error.getDescription());
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

    private void startNodeService() {
        updateStatus("正在启动服务...");
        
        Intent serviceIntent = new Intent(this, NodeService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // 绑定服务
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void waitForServer() {
        if (retryCount >= MAX_RETRIES) {
            showError("服务启动超时，请重试");
            return;
        }
        
        updateStatus("等待服务就绪... (" + (retryCount + 1) + "/" + MAX_RETRIES + ")");
        
        handler.postDelayed(() -> {
            if (isServerRunning()) {
                loadDSH();
            } else {
                retryCount++;
                waitForServer();
            }
        }, RETRY_DELAY_MS);
    }

    private boolean isServerRunning() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            // 简单检查：尝试连接本地端口
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

    private void showError(String message) {
        loadingView.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.error));
        retryButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideLoading() {
        loadingView.setVisibility(View.GONE);
    }

    private void updateStatus(String message) {
        statusText.setText(message);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        retryButton.setVisibility(View.GONE);
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
        
        if (webView != null) {
            webView.destroy();
        }
        
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
