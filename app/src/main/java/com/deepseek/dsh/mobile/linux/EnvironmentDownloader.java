package com.deepseek.dsh.mobile.linux;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 环境下载器
 *
 * 首次启动时下载：
 * 1. proot 二进制文件
 * 2. Ubuntu 最小 rootfs
 *
 * 下载源：
 * - proot: 来自 Termux/PRoot 项目
 * - Ubuntu rootfs: 来自 Ubuntu 官方 CDIMAGE
 */
public class EnvironmentDownloader {

    private static final String TAG = "EnvDownloader";

    // proot 下载地址 - 来自 proot-me 官方 releases
    private static final String PROOT_URL_AARCH64 =
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static";

    private static final String PROOT_URL_ARM =
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-arm-static";

    private static final String PROOT_URL_X86_64 =
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static";

    // Ubuntu 最小 rootfs（arm64）- 来自 Ubuntu CDIMAGE
    private static final String ROOTFS_URL_AARCH64 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz";

    private static final String ROOTFS_URL_X86_64 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz";

    private static final String ROOTFS_URL_ARMHF =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-armhf.tar.gz";

    public interface DownloadCallback {
        void onStatusUpdate(String status);
        void onProgress(long downloaded, long total);
        void onComplete();
        void onError(String error);
    }

    private final Context context;
    private final Handler handler;
    private DownloadCallback callback;
    private boolean cancelled = false;

    public EnvironmentDownloader(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * 下载 proot 二进制
     */
    public File downloadProot() throws IOException {
        String arch = getArch();
        String url;

        switch (arch) {
            case "aarch64":
                url = PROOT_URL_AARCH64;
                break;
            case "arm":
                url = PROOT_URL_ARM;
                break;
            case "x86_64":
                url = PROOT_URL_X86_64;
                break;
            default:
                throw new IOException("不支持的 CPU 架构: " + arch);
        }

        File binDir = new File(context.getFilesDir(), "bin");
        if (!binDir.exists()) binDir.mkdirs();

        File prootFile = new File(binDir, "proot");

        if (prootFile.exists() && prootFile.length() > 0) {
            notifyStatus("proot 已存在，跳过下载");
            return prootFile;
        }

        notifyStatus("下载 proot (" + arch + ")...");
        Log.d(TAG, "下载 proot: " + url);

        downloadFile(url, prootFile);

        // 设置可执行权限（同步等待完成）
        try {
            Process chmod = Runtime.getRuntime().exec(new String[]{"chmod", "755", prootFile.getAbsolutePath()});
            chmod.waitFor();
            Log.d(TAG, "chmod 退出码: " + chmod.exitValue());
        } catch (Exception e) {
            Log.e(TAG, "chmod 失败", e);
        }

        // 双重保障
        prootFile.setExecutable(true, false);
        prootFile.setReadable(true, false);

        // 验证权限
        Log.d(TAG, "proot 可执行: " + prootFile.canExecute());
        Log.d(TAG, "proot 大小: " + prootFile.length());

        notifyStatus("proot 下载完成 ✓");
        return prootFile;
    }

    /**
     * 下载 Ubuntu rootfs
     */
    public File downloadRootfs() throws IOException {
        String arch = getArch();
        String url;

        switch (arch) {
            case "aarch64":
                url = ROOTFS_URL_AARCH64;
                break;
            case "x86_64":
                url = ROOTFS_URL_X86_64;
                break;
            case "arm":
                url = ROOTFS_URL_ARMHF;
                break;
            default:
                throw new IOException("不支持的架构: " + arch);
        }

        File rootfsDir = new File(context.getFilesDir(), "ubuntu-rootfs");
        File tarFile = new File(context.getCacheDir(), "ubuntu-rootfs.tar.gz");

        if (rootfsDir.exists() && new File(rootfsDir, "bin").exists()) {
            notifyStatus("Ubuntu rootfs 已存在，跳过下载");
            return rootfsDir;
        }

        notifyStatus("下载 Ubuntu rootfs（约 30MB）...");
        Log.d(TAG, "下载 rootfs: " + url);

        downloadFile(url, tarFile);

        // 解压
        notifyStatus("解压 Ubuntu rootfs...");
        extractTarGz(tarFile, rootfsDir);

        // 清理
        tarFile.delete();

        notifyStatus("Ubuntu rootfs 安装完成 ✓");
        return rootfsDir;
    }

    /**
     * 下载文件并报告进度（带重试）
     */
    private void downloadFile(String urlStr, File dest) throws IOException {
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                notifyStatus("尝试下载 (第" + attempt + "次)...");
                Log.d(TAG, "下载: " + urlStr + " (尝试 " + attempt + ")");

                downloadFileOnce(urlStr, dest);
                notifyStatus("下载完成 ✓ (" + (dest.length() / 1024) + "KB)");
                return;

            } catch (IOException e) {
                Log.e(TAG, "下载失败 (尝试 " + attempt + "): " + e.getMessage());
                notifyStatus("下载失败: " + e.getMessage());

                if (attempt >= maxRetries) {
                    throw new IOException("下载失败（已重试" + maxRetries + "次）: " + e.getMessage(), e);
                }

                // 等待后重试
                try {
                    notifyStatus("等待 3 秒后重试...");
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void downloadFileOnce(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream os = null;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000); // 读取超时 2 分钟
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36");

            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP 响应码: " + code);
            notifyStatus("HTTP " + code + ", 开始下载...");

            // 处理重定向
            if (code == 301 || code == 302 || code == 307) {
                String newUrl = conn.getHeaderField("Location");
                Log.d(TAG, "重定向到: " + newUrl);
                notifyStatus("跟随重定向...");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36");
                code = conn.getResponseCode();
            }

            if (code != 200) {
                throw new IOException("HTTP 错误: " + code);
            }

            long totalSize = conn.getContentLength();
            Log.d(TAG, "文件大小: " + (totalSize > 0 ? (totalSize / 1024 / 1024) + "MB" : "未知"));
            notifyStatus("文件大小: " + (totalSize > 0 ? String.format("%.1fMB", totalSize / 1048576.0) : "未知"));

            is = conn.getInputStream();
            os = new FileOutputStream(dest);

            byte[] buffer = new byte[16384]; // 16KB 缓冲
            int len;
            long downloaded = 0;
            long lastLogTime = System.currentTimeMillis();

            while ((len = is.read(buffer)) > 0) {
                if (cancelled) throw new IOException("下载已取消");

                os.write(buffer, 0, len);
                downloaded += len;

                // 每 2 秒输出一次日志
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 2000) {
                    lastLogTime = now;
                    if (totalSize > 0) {
                        int pct = (int) (downloaded * 100 / totalSize);
                        String msg = String.format("下载中: %d%% (%.1fMB / %.1fMB)",
                            pct, downloaded / 1048576.0, totalSize / 1048576.0);
                        notifyStatus(msg);
                        Log.d(TAG, msg);
                    } else {
                        String msg = String.format("下载中: %.1fMB", downloaded / 1048576.0);
                        notifyStatus(msg);
                        Log.d(TAG, msg);
                    }
                }
            }

            os.flush();
            Log.d(TAG, "下载完成: " + downloaded + " bytes");

        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (is != null) try { is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 解压 tar.gz
     */
    private void extractTarGz(File tarFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            String.format("cd %s && tar xzf %s 2>&1", destDir.getAbsolutePath(), tarFile.getAbsolutePath())
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // 消费输出
        InputStream is = p.getInputStream();
        byte[] buf = new byte[1024];
        while (is.read(buf) > 0) {}

        try {
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("解压失败，退出码: " + exit);
            }
        } catch (InterruptedException e) {
            throw new IOException("解压被中断");
        }
    }

    private String getArch() {
        String arch = Build.SUPPORTED_ABIS[0];
        if (arch.contains("arm64") || arch.contains("aarch64")) return "aarch64";
        if (arch.contains("arm")) return "arm";
        if (arch.contains("x86_64")) return "x86_64";
        return "i686";
    }

    private void notifyStatus(String status) {
        Log.d(TAG, status);
        if (callback != null) handler.post(() -> callback.onStatusUpdate(status));
    }

    private void notifyProgress(long downloaded, long total) {
        if (callback != null) handler.post(() -> callback.onProgress(downloaded, total));
    }
}
