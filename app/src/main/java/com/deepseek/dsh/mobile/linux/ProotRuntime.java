package com.deepseek.dsh.mobile.linux;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PRoot Ubuntu 运行时
 *
 * 在 Android 上通过 proot 运行完整的 Ubuntu 环境
 * 不需要 root 权限，不需要 Termux
 *
 * 原理：
 * proot 是一个用户空间的 chroot 实现，通过 ptrace 系统调用
 * 拦截程序的系统调用，将文件路径重定向到我们的 rootfs 目录。
 * 这样我们可以在 Android 的 app 私有目录中运行完整的 Linux 环境。
 */
public class ProotRuntime {

    private static final String TAG = "ProotRuntime";

    private final Context context;
    private final File dataDir;       // App 内部存储根目录
    private final File rootfsDir;     // Ubuntu rootfs 目录
    private final File prootBinary;   // proot 可执行文件
    private final File scriptsDir;    // 脚本目录

    // 回调接口
    public interface LogCallback {
        void onLog(String message);
    }

    private LogCallback logCallback;

    public ProotRuntime(Context context) {
        this.context = context;
        this.dataDir = context.getFilesDir();
        this.rootfsDir = new File(dataDir, "ubuntu-rootfs");
        this.prootBinary = new File(dataDir, "bin/proot");
        this.scriptsDir = new File(dataDir, "scripts");
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    // ==================== 环境检查 ====================

    /**
     * 检查 Ubuntu rootfs 是否已安装
     */
    public boolean isRootfsInstalled() {
        File testFile = new File(rootfsDir, "bin/bash");
        File etcDir = new File(rootfsDir, "etc");
        return testFile.exists() && etcDir.exists();
    }

    /**
     * 检查 proot 是否就绪
     */
    public boolean isProotReady() {
        return prootBinary.exists() && prootBinary.canExecute();
    }

    /**
     * 检查 Node.js 是否已安装在 rootfs 中
     */
    public boolean isNodeInstalled() {
        File nodeBin = new File(rootfsDir, "usr/bin/node");
        return nodeBin.exists();
    }

    // ==================== 环境初始化 ====================

    /**
     * 初始化 proot 环境
     * 从 assets 复制 proot 二进制文件
     */
    public void initProot() throws IOException {
        log("初始化 proot...");

        // 创建目录
        File binDir = new File(dataDir, "bin");
        if (!binDir.exists()) binDir.mkdirs();

        // 根据 CPU 架构选择 proot
        String arch = getArch();
        String prootAsset = "proot/" + arch + "/proot";

        log("CPU 架构: " + arch);
        log("复制 proot 二进制文件...");

        copyAsset(prootAsset, prootBinary);

        // 设置可执行权限
        Runtime.getRuntime().exec("chmod 755 " + prootBinary.getAbsolutePath());
        prootBinary.setExecutable(true, false);

        log("proot 初始化完成 ✓");
    }

    /**
     * 安装 Ubuntu rootfs
     * 从 assets 复制预打包的最小 rootfs
     */
    public void installRootfs(AssetProgressCallback progress) throws IOException {
        log("开始安装 Ubuntu rootfs...");

        if (!rootfsDir.exists()) rootfsDir.mkdirs();

        // 方式1：从 assets 复制预打包的 rootfs tar
        String rootfsAsset = "rootfs/ubuntu-rootfs.tar.gz";
        File tarFile = new File(dataDir, "ubuntu-rootfs.tar.gz");

        log("解压 Ubuntu rootfs（这可能需要几分钟）...");
        copyAsset(rootfsAsset, tarFile, progress);

        // 解压
        log("正在解压...");
        extractTar(tarFile, rootfsDir);

        // 清理 tar 文件
        tarFile.delete();

        log("Ubuntu rootfs 安装完成 ✓");
    }

    // ==================== 命令执行 ====================

    /**
     * 在 proot Ubuntu 环境中执行命令
     *
     * @param command 要执行的 bash 命令
     * @return 命令输出
     */
    public ExecResult exec(String command) throws IOException {
        return exec(command, null);
    }

    /**
     * 在 proot Ubuntu 环境中执行命令（带实时输出回调）
     */
    public ExecResult exec(String command, LineCallback callback) throws IOException {
        List<String> cmd = buildProotCommand(command);
        log("执行: " + command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootfsDir);
        pb.redirectErrorStream(true);

        // 设置环境变量
        Map<String, String> env = pb.environment();
        env.put("HOME", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm");
        env.put("TMPDIR", "/tmp");

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
            if (callback != null) {
                callback.onLine(line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            exitCode = -1;
        }

        ExecResult result = new ExecResult(exitCode, output.toString().trim());
        log("命令退出码: " + exitCode);

        return result;
    }

    /**
     * 后台执行命令（不等待完成）
     */
    public Process execBackground(String command) throws IOException {
        List<String> cmd = buildProotCommand(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootfsDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("HOME", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LANG", "en_US.UTF-8");
        env.put("TMPDIR", "/tmp");

        return pb.start();
    }

    /**
     * 构建 proot 命令行
     */
    private List<String> buildProotCommand(String shellCommand) {
        List<String> cmd = new ArrayList<>();

        cmd.add(prootBinary.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("--kill-on-exit");

        // rootfs 挂载点
        cmd.add("--rootfs=" + rootfsDir.getAbsolutePath());

        // 绑定 /dev, /proc, /sys 等
        cmd.add("--bind=/dev");
        cmd.add("--bind=/dev/urandom:/dev/random");
        cmd.add("--bind=/proc");
        cmd.add("--bind=/sys");

        // 绑定 Android 的 tmp 目录
        File tmpDir = new File(context.getCacheDir(), "proot-tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        cmd.add("--bind=" + tmpDir.getAbsolutePath() + ":/tmp");

        // 设置 cwd
        cmd.add("--cwd=/root");

        // 设置用户
        cmd.add("--root-id");

        // 设置内核信息（伪装成标准 Linux）
        cmd.add("--kernel-release=5.4.0-fake-android");

        // 执行 bash -c "command"
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("LANG=en_US.UTF-8");
        cmd.add("TERM=xterm");
        cmd.add("/bin/bash");
        cmd.add("-c");
        cmd.add(shellCommand);

        return cmd;
    }

    // ==================== DSH 专用方法 ====================

    /**
     * 在 Ubuntu 中安装 Node.js
     */
    public boolean installNode(LineCallback callback) throws IOException {
        log("在 Ubuntu 中安装 Node.js...");

        String script =
            "set -e\n" +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "echo '[1/4] 更新软件源...'\n" +
            "apt-get update -y 2>&1 | tail -1\n" +
            "echo '[2/4] 安装 Node.js 和 npm...'\n" +
            "apt-get install -y nodejs npm curl 2>&1 | tail -3\n" +
            "echo '[3/4] 验证安装...'\n" +
            "node --version\n" +
            "npm --version\n" +
            "echo '[4/4] 配置 npm...'\n" +
            "npm config set unsafe-perm true\n" +
            "echo '===NODE_INSTALLED==='\n";

        ExecResult result = exec(script, callback);
        return result.exitCode == 0 || result.output.contains("NODE_INSTALLED");
    }

    /**
     * 部署 DSH 服务器文件到 Ubuntu
     */
    public void deployServer() throws IOException {
        log("部署 DSH 服务器...");

        // 先创建目录
        exec("mkdir -p /root/dsh-server");

        // 从 Android assets 复制文件到 rootfs 中
        File serverDir = new File(rootfsDir, "root/dsh-server");
        if (!serverDir.exists()) serverDir.mkdirs();

        String[] files = {
            "nodejs-project/index.js",
            "nodejs-project/package.json"
        };

        for (String asset : files) {
            String name = new File(asset).getName();
            File dest = new File(serverDir, name);
            copyAsset(asset, dest);
            log("  已部署: " + name);
        }

        log("服务器文件部署完成 ✓");
    }

    /**
     * 安装 DSH 服务器依赖
     */
    public boolean installDependencies(LineCallback callback) throws IOException {
        log("安装 npm 依赖...");
        ExecResult result = exec("cd /root/dsh-server && npm install --production 2>&1", callback);
        return result.exitCode == 0;
    }

    /**
     * 启动 DSH 服务器（后台运行）
     */
    public Process startServer() throws IOException {
        log("启动 DSH 服务器...");
        return execBackground("cd /root/dsh-server && node index.js");
    }

    // ==================== 工具方法 ====================

    private String getArch() {
        String arch = Build.SUPPORTED_ABIS[0];
        if (arch.contains("arm64") || arch.contains("aarch64")) {
            return "aarch64";
        } else if (arch.contains("arm")) {
            return "arm";
        } else if (arch.contains("x86_64")) {
            return "x86_64";
        } else {
            return "i686";
        }
    }

    private void copyAsset(String assetPath, File dest) throws IOException {
        copyAsset(assetPath, dest, null);
    }

    private void copyAsset(String assetPath, File dest, AssetProgressCallback progress) throws IOException {
        InputStream is = context.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(dest);

        byte[] buffer = new byte[8192];
        int len;
        long total = 0;

        while ((len = is.read(buffer)) > 0) {
            os.write(buffer, 0, len);
            total += len;
            if (progress != null) {
                progress.onProgress(total);
            }
        }

        os.flush();
        os.close();
        is.close();
    }

    private void extractTar(File tarFile, File destDir) throws IOException {
        // 使用 proot 自身来解压（它自带 busybox 功能）
        // 或者用 Android 自带的工具
        String cmd = String.format(
            "cd %s && tar xzf %s",
            destDir.getAbsolutePath(),
            tarFile.getAbsolutePath()
        );

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        while (reader.readLine() != null) { /* 消费输出 */ }

        try {
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("解压失败，退出码: " + exit);
            }
        } catch (InterruptedException e) {
            throw new IOException("解压被中断");
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (logCallback != null) {
            logCallback.onLog(msg);
        }
    }

    // ==================== 内部类 ====================

    public static class ExecResult {
        public final int exitCode;
        public final String output;

        public ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public interface LineCallback {
        void onLine(String line);
    }

    public interface AssetProgressCallback {
        void onProgress(long bytesWritten);
    }
}
