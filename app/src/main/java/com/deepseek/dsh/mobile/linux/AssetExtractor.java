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
import java.io.OutputStream;

/**
 * 资源解压器
 * 从 APK assets 解压 proot 和 rootfs 到应用数据目录
 */
public class AssetExtractor {

    private static final String TAG = "AssetExtractor";

    public interface ExtractCallback {
        void onStatusUpdate(String status);
        void onComplete();
        void onError(String error);
    }

    private final Context context;
    private final Handler handler;
    private ExtractCallback callback;

    public AssetExtractor(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(ExtractCallback callback) {
        this.callback = callback;
    }

    /**
     * 检查是否已解压
     */
    public boolean isExtracted() {
        File proot = new File(context.getFilesDir(), "bin/proot");
        File rootfs = new File(context.getFilesDir(), "ubuntu-rootfs/bin/bash");
        return proot.exists() && proot.canExecute() && rootfs.exists();
    }

    /**
     * 从 assets 解压 proot 和 rootfs
     */
    public void extractAll() throws IOException {
        // 解压 proot
        extractProot();

        // 解压 rootfs
        extractRootfs();
    }

    private void extractProot() throws IOException {
        File binDir = new File(context.getFilesDir(), "bin");
        if (!binDir.exists()) binDir.mkdirs();

        File prootFile = new File(binDir, "proot");
        if (prootFile.exists() && prootFile.canExecute()) {
            notifyStatus("proot 已存在，跳过");
            return;
        }

        notifyStatus("解压 proot...");

        String arch = getArch();
        String assetPath = "bin/proot-" + arch;

        try {
            InputStream is = context.getAssets().open(assetPath);
            OutputStream os = new FileOutputStream(prootFile);

            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            os.flush();
            os.close();
            is.close();

            // 设置权限
            try {
                Process chmod = Runtime.getRuntime().exec(new String[]{"chmod", "755", prootFile.getAbsolutePath()});
                chmod.waitFor();
            } catch (InterruptedException ignored) {}

            prootFile.setExecutable(true, false);
            prootFile.setReadable(true, false);

            Log.d(TAG, "proot 解压完成: " + prootFile.length() + " bytes, canExecute=" + prootFile.canExecute());
            notifyStatus("proot 解压完成 ✓");

        } catch (IOException e) {
            throw new IOException("解压 proot 失败: " + e.getMessage(), e);
        }
    }

    private void extractRootfs() throws IOException {
        File rootfsDir = new File(context.getFilesDir(), "ubuntu-rootfs");
        File tarFile = new File(context.getFilesDir(), "rootfs-tmp.tar.gz");

        if (rootfsDir.exists() && new File(rootfsDir, "bin").exists()) {
            notifyStatus("Ubuntu rootfs 已存在，跳过");
            return;
        }

        notifyStatus("解压 Ubuntu rootfs（首次约需2-5分钟）...");

        // 从 assets 复制 tar.gz
        try {
            InputStream is = context.getAssets().open("rootfs/ubuntu-rootfs.tar.gz");
            OutputStream os = new FileOutputStream(tarFile);

            byte[] buf = new byte[16384];
            int len;
            long total = 0;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
                total += len;
                if (total % (1024 * 1024) == 0) {
                    notifyStatus("复制中: " + (total / 1024 / 1024) + "MB...");
                }
            }
            os.flush();
            os.close();
            is.close();

            Log.d(TAG, "rootfs tar 大小: " + (total / 1024 / 1024) + "MB");
            notifyStatus("tar 文件就绪，开始解压...");

        } catch (IOException e) {
            throw new IOException("读取 rootfs assets 失败: " + e.getMessage() + 
                "\n\n可能原因：APK 构建时 rootfs 未正确打包。请重新构建 APK。", e);
        }

        // 解压
        if (!rootfsDir.exists()) rootfsDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            String.format("cd %s && tar xzf %s", rootfsDir.getAbsolutePath(), tarFile.getAbsolutePath())
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // 读取输出
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

        // 清理 tar 文件
        tarFile.delete();

        // 配置 DNS
        File resolvConf = new File(rootfsDir, "etc/resolv.conf");
        if (!resolvConf.getParentFile().exists()) {
            resolvConf.getParentFile().mkdirs();
        }
        FileOutputStream dnsOs = new FileOutputStream(resolvConf);
        dnsOs.write("nameserver 8.8.8.8\nnameserver 8.8.4.4\n".getBytes());
        dnsOs.flush();
        dnsOs.close();

        Log.d(TAG, "rootfs 解压完成");
        notifyStatus("Ubuntu rootfs 解压完成 ✓");
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
}
