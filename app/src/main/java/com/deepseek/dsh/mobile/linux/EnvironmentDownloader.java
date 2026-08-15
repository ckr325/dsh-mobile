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

    // proot 下载地址（arm64）
    private static final String PROOT_URL_AARCH64 =
        "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static";

    // proot 下载地址（arm）
    private static final String PROOT_URL_ARM =
        "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-arm-static";

    // proot 下载地址（x86_64）
    private static final String PROOT_URL_X86_64 =
        "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-x86_64-static";

    // Ubuntu 最小 rootfs（arm64）- 来自 Ubuntu CDIMAGE
    // 使用 ubuntu-base 是最小的 Ubuntu 根文件系统
    private static final String ROOTFS_URL_AARCH64 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz";

    private static final String ROOTFS_URL_X86_64 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-amd64.tar.gz";

    private static final String ROOTFS_URL_ARMHF =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-armhf.tar.gz";

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

        // 设置可执行权限
        Runtime.getRuntime().exec("chmod 755 " + prootFile.getAbsolutePath());
        prootFile.setExecutable(true, false);

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
     * 下载文件并报告进度
     */
    private void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream os = null;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DSH-Mobile/1.0");

            // 处理重定向
            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "DSH-Mobile/1.0");
            }

            long totalSize = conn.getContentLength();
            is = conn.getInputStream();
            os = new FileOutputStream(dest);

            byte[] buffer = new byte[8192];
            int len;
            long downloaded = 0;
            int lastProgress = 0;

            while ((len = is.read(buffer)) > 0) {
                if (cancelled) throw new IOException("下载已取消");

                os.write(buffer, 0, len);
                downloaded += len;

                // 每 5% 报告一次进度
                if (totalSize > 0) {
                    int progress = (int) (downloaded * 100 / totalSize);
                    if (progress - lastProgress >= 5) {
                        lastProgress = progress;
                        notifyProgress(downloaded, totalSize);
                    }
                }
            }

            os.flush();

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
