package com.deepseek.dsh.mobile.linux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * DSH 环境安装管理器
 *
 * 负责完整的安装流程：
 * 1. 初始化 proot
 * 2. 安装 Ubuntu rootfs
 * 3. 安装 Node.js
 * 4. 部署 DSH 服务器
 * 5. 启动服务器
 */
public class SetupManager {

    private static final String TAG = "SetupManager";

    public interface SetupCallback {
        /** 步骤变更 */
        void onStepChanged(int step, String title, String detail);
        /** 日志输出 */
        void onLog(String message);
        /** 进度更新 (0-100) */
        void onProgress(int percent);
        /** 安装完成 */
        void onSuccess();
        /** 安装失败 */
        void onError(String error);
    }

    private final Context context;
    private final ProotRuntime proot;
    private final Handler handler;
    private SetupCallback callback;
    private Process serverProcess;
    private boolean isRunning = false;

    public SetupManager(Context context) {
        this.context = context;
        this.proot = new ProotRuntime(context);
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(SetupCallback callback) {
        this.callback = callback;
        proot.setLogCallback(msg -> notifyLog(msg));
    }

    /**
     * 检查是否已经完全安装好（可以直接启动服务器）
     */
    public boolean isReady() {
        return proot.isProotReady() &&
               proot.isRootfsInstalled() &&
               proot.isNodeInstalled();
    }

    /**
     * 执行完整的安装流程
     */
    public void runFullSetup() {
        new Thread(() -> {
            try {
                // 步骤 1：初始化 proot
                notifyStep(1, "初始化 PRoot", "准备 Linux 运行环境...");
                notifyProgress(5);

                if (!proot.isProotReady()) {
                    proot.initProot();
                } else {
                    notifyLog("proot 已就绪，跳过");
                }
                notifyProgress(15);

                // 步骤 2：安装 Ubuntu rootfs
                notifyStep(2, "安装 Ubuntu", "解压 Linux 根文件系统...");
                notifyProgress(20);

                if (!proot.isRootfsInstalled()) {
                    proot.installRootfs(bytes -> {
                        // 可以根据字节数更新进度
                        notifyProgress(20 + (int)(bytes / 1024 / 1024)); // 每MB +1
                    });
                } else {
                    notifyLog("Ubuntu rootfs 已安装，跳过");
                }
                notifyProgress(50);

                // 步骤 3：安装 Node.js
                notifyStep(3, "安装 Node.js", "在 Ubuntu 中安装 Node.js...");
                notifyProgress(55);

                if (!proot.isNodeInstalled()) {
                    boolean ok = proot.installNode(line -> {
                        notifyLog("  " + line);
                    });
                    if (!ok) {
                        notifyError("Node.js 安装失败");
                        return;
                    }
                } else {
                    notifyLog("Node.js 已安装，跳过");
                }
                notifyProgress(75);

                // 步骤 4：部署 DSH 服务器
                notifyStep(4, "部署 DSH 服务器", "准备服务器文件和依赖...");
                notifyProgress(80);

                proot.deployServer();

                // 安装依赖
                notifyLog("安装 npm 依赖...");
                boolean depsOk = proot.installDependencies(line -> {
                    notifyLog("  " + line);
                });
                if (!depsOk) {
                    notifyLog("⚠️ npm install 有警告，继续尝试...");
                }
                notifyProgress(90);

                // 步骤 5：启动服务器
                notifyStep(5, "启动服务器", "DSH 服务器启动中...");
                notifyProgress(95);

                startServer();

                notifyProgress(100);
                notifySuccess();

            } catch (Exception e) {
                Log.e(TAG, "安装失败", e);
                notifyError(e.getMessage());
            }
        }).start();
    }

    /**
     * 仅启动服务器（已安装的情况）
     */
    public void startServerOnly() {
        new Thread(() -> {
            try {
                notifyStep(5, "启动服务器", "DSH 服务器启动中...");
                notifyLog("启动 DSH 服务器...");
                startServer();
                notifySuccess();
            } catch (Exception e) {
                notifyError("启动失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 启动 DSH 服务器
     */
    private void startServer() throws IOException {
        if (serverProcess != null) {
            try { serverProcess.destroy(); } catch (Exception ignored) {}
        }

        serverProcess = proot.startServer();
        isRunning = true;

        // 后台读取服务器输出
        new Thread(() -> {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(serverProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    notifyLog("[SERVER] " + line);
                }
            } catch (Exception e) {
                notifyLog("[SERVER] 输出流关闭");
            }
        }).start();
    }

    /**
     * 停止服务器
     */
    public void stopServer() {
        if (serverProcess != null) {
            serverProcess.destroy();
            serverProcess = null;
        }
        isRunning = false;
    }

    public boolean isServerRunning() {
        return isRunning;
    }

    // ==================== 通知方法 ====================

    private void notifyStep(int step, String title, String detail) {
        if (callback != null) {
            handler.post(() -> callback.onStepChanged(step, title, detail));
        }
    }

    private void notifyLog(String msg) {
        if (callback != null) {
            handler.post(() -> callback.onLog(msg));
        }
    }

    private void notifyProgress(int percent) {
        if (callback != null) {
            handler.post(() -> callback.onProgress(Math.min(percent, 100)));
        }
    }

    private void notifySuccess() {
        if (callback != null) {
            handler.post(() -> callback.onSuccess());
        }
    }

    private void notifyError(String error) {
        if (callback != null) {
            handler.post(() -> callback.onError(error));
        }
    }
}
