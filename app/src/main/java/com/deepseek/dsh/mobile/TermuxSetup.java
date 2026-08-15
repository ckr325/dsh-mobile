package com.deepseek.dsh.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Termux 集成管理器
 *
 * 功能：
 * 1. 检查 Termux 是否已安装
 * 2. 引导用户安装 Termux
 * 3. 自动在 Termux 中安装 Node.js
 * 4. 启动 DSH 服务器
 */
public class TermuxSetup {

    private static final String TAG = "TermuxSetup";

    // Termux 包名
    public static final String TERMUX_PACKAGE = "com.termux";

    // Termux:Tasker 包名（用于执行脚本）
    public static final String TERMUX_TASKER_PACKAGE = "com.termux.tasker";

    // Termux F-Droid 下载地址
    private static final String TERMUX_FDROID_URL = "https://f-droid.org/repo/com.termux_1000.apk";

    // 回调接口
    public interface SetupCallback {
        void onStatusUpdate(String status);
        void onError(String error);
        void onSuccess();
    }

    private final Context context;
    private final Handler handler;
    private SetupCallback callback;

    public TermuxSetup(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(SetupCallback callback) {
        this.callback = callback;
    }

    /**
     * 检查 Termux 是否已安装
     */
    public boolean isTermuxInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 检查 Termux 中是否已安装 Node.js
     */
    public boolean isNodeInstalled() {
        File nodeReady = new File(context.getFilesDir(), "termux-node-ready");
        return nodeReady.exists();
    }

    /**
     * 标记 Node.js 已安装
     */
    public void markNodeInstalled() {
        try {
            File marker = new File(context.getFilesDir(), "termux-node-ready");
            marker.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "创建标记文件失败", e);
        }
    }

    /**
     * 跳转到 Termux 安装页面
     */
    public void openTermuxInstall(Activity activity) {
        // 优先尝试 F-Droid
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TERMUX_PACKAGE));
            intent.setPackage("org.fdroid.fdroid");
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                // 尝试 Google Play
                Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + TERMUX_PACKAGE));
                activity.startActivity(intent);
            } catch (Exception e2) {
                // 最后打开 F-Droid 网页
                Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.termux/"));
                activity.startActivity(intent);
            }
        }
    }

    /**
     * 准备 DSH 脚本文件到 Termux 可访问的位置
     */
    public void prepareScripts() {
        try {
            // 在 app 内部存储创建脚本目录
            File scriptsDir = new File(context.getFilesDir(), "dsh-scripts");
            if (!scriptsDir.exists()) {
                scriptsDir.mkdirs();
            }

            // 创建 setup-node.sh
            String setupScript = "#!/bin/bash\n" +
                "echo '=== DSH Mobile Setup ==='\n" +
                "echo 'Installing Node.js...'\n" +
                "pkg update -y\n" +
                "pkg install -y nodejs\n" +
                "echo 'Node.js version:'\n" +
                "node --version\n" +
                "echo 'npm version:'\n" +
                "npm --version\n" +
                "echo '=== Setup Complete ==='\n";

            writeFile(new File(scriptsDir, "setup-node.sh"), setupScript);

            // 创建 start-dsh.sh
            String startScript = "#!/bin/bash\n" +
                "echo 'Starting DSH Server...'\n" +
                "cd ~/dsh-server\n" +
                "if [ ! -d 'node_modules' ]; then\n" +
                "  echo 'Installing dependencies...'\n" +
                "  npm install --production\n" +
                "fi\n" +
                "echo 'Server starting on port 3080...'\n" +
                "node index.js\n";

            writeFile(new File(scriptsDir, "start-dsh.sh"), startScript);

            // 创建 setup-and-start.sh（一键脚本）
            String allInOne = "#!/bin/bash\n" +
                "set -e\n" +
                "echo '================================'\n" +
                "echo '  DSH Mobile - Auto Setup'\n" +
                "echo '================================'\n" +
                "echo ''\n" +
                "\n" +
                "# 1. 安装 Node.js\n" +
                "if ! command -v node &> /dev/null; then\n" +
                "  echo '[1/3] Installing Node.js...'\n" +
                "  pkg update -y 2>/dev/null\n" +
                "  pkg install -y nodejs 2>/dev/null\n" +
                "else\n" +
                "  echo '[1/3] Node.js already installed'\n" +
                "fi\n" +
                "\n" +
                "# 2. 准备 DSH 服务器目录\n" +
                "echo '[2/3] Preparing DSH Server...'\n" +
                "mkdir -p ~/dsh-server\n" +
                "\n" +
                "# 检查是否已有服务器文件\n" +
                "if [ ! -f ~/dsh-server/index.js ]; then\n" +
                "  echo 'Server files not found, please transfer them.'\n" +
                "  exit 1\n" +
                "fi\n" +
                "\n" +
                "# 3. 安装依赖并启动\n" +
                "echo '[3/3] Starting DSH Server...'\n" +
                "cd ~/dsh-server\n" +
                "if [ ! -d 'node_modules' ]; then\n" +
                "  npm install --production\n" +
                "fi\n" +
                "\n" +
                "echo ''\n" +
                "echo '================================'\n" +
                "echo '  DSH Server Running!'\n" +
                "echo '  http://localhost:3080'\n" +
                "echo '================================'\n" +
                "node index.js\n";

            writeFile(new File(scriptsDir, "setup-and-start.sh"), allInOne);

            Log.d(TAG, "脚本准备完成: " + scriptsDir.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "准备脚本失败", e);
        }
    }

    /**
     * 获取 Termux 执行命令的 Intent
     * 通过 Termux:RUN_COMMAND 接口执行
     */
    public Intent getTermuxCommandIntent(String command) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        return intent;
    }

    /**
     * 创建安装引导 Intent（通过 Termux 执行）
     */
    public Intent getInstallNodeIntent() {
        String cmd = "pkg update -y && pkg install -y nodejs && echo 'NODE_READY'";
        return getTermuxCommandIntent(cmd);
    }

    /**
     * 创建启动 DSH 的 Intent
     */
    public Intent getStartDSHIntent(String serverDir) {
        String cmd = "cd " + serverDir + " && " +
            "if [ ! -d node_modules ]; then npm install --production; fi && " +
            "node index.js";
        return getTermuxCommandIntent(cmd);
    }

    /**
     * 从 assets 复制服务器文件到可访问的目录
     */
    public void copyServerFiles() throws IOException {
        File serverDir = new File(context.getFilesDir(), "dsh-server");
        if (!serverDir.exists()) {
            serverDir.mkdirs();
        }

        String[] files = {
            "nodejs-project/index.js",
            "nodejs-project/package.json"
        };

        for (String filePath : files) {
            copyAsset(filePath, new File(serverDir, new File(filePath).getName()));
        }
    }

    private void copyAsset(String assetPath, File destFile) throws IOException {
        if (destFile.exists()) return;

        InputStream is = context.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(destFile);

        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) > 0) {
            os.write(buffer, 0, len);
        }

        os.flush();
        os.close();
        is.close();
    }

    private void writeFile(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes());
        fos.flush();
        fos.close();
    }

    /**
     * 通知状态更新
     */
    private void notifyStatus(String status) {
        if (callback != null) {
            handler.post(() -> callback.onStatusUpdate(status));
        }
    }

    /**
     * 通知错误
     */
    private void notifyError(String error) {
        if (callback != null) {
            handler.post(() -> callback.onError(error));
        }
    }

    /**
     * 通知成功
     */
    private void notifySuccess() {
        if (callback != null) {
            handler.post(() -> callback.onSuccess());
        }
    }
}
