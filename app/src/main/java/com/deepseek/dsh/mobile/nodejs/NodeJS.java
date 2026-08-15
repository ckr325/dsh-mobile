package com.deepseek.dsh.mobile.nodejs;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Node.js 运行时封装
 * 
 * 使用 nodejs-mobile 库在 Android 上运行 Node.js
 */
public class NodeJS {
    
    private static final String TAG = "NodeJS";
    
    private final Context context;
    private String workingDirectory;
    private Process nodeProcess;
    private boolean isStarted = false;
    
    public NodeJS(Context context) {
        this.context = context;
        this.workingDirectory = context.getFilesDir().getAbsolutePath();
        
        // 初始化 nodejs-mobile
        initNodeJS();
    }
    
    private void initNodeJS() {
        try {
            // 复制 Node.js 项目文件到应用目录
            copyAssetsToInternalStorage();
            
            // 初始化 nodejs-mobile native 库
            // 注意：实际使用时需要添加 nodejs-mobile 依赖
            // 这里使用 Process 方式模拟
            Log.d(TAG, "Node.js 初始化完成");
            
        } catch (Exception e) {
            Log.e(TAG, "初始化失败", e);
            throw new RuntimeException("Node.js 初始化失败", e);
        }
    }
    
    /**
     * 设置工作目录
     */
    public void setWorkingDirectory(String directory) {
        this.workingDirectory = directory;
        new File(directory).mkdirs();
    }
    
    /**
     * 启动 Node.js 脚本
     */
    public void start(String scriptPath) throws IOException {
        if (isStarted) {
            Log.w(TAG, "Node.js 已经启动");
            return;
        }
        
        String scriptFullPath = workingDirectory + "/" + scriptPath;
        
        // 检查脚本是否存在
        File scriptFile = new File(scriptFullPath);
        if (!scriptFile.exists()) {
            throw new IOException("脚本文件不存在: " + scriptFullPath);
        }
        
        Log.d(TAG, "启动脚本: " + scriptFullPath);
        
        // 方式1: 使用 nodejs-mobile 库（推荐）
        // NodeJSRuntime.startEngine(scriptFullPath, workingDirectory);
        
        // 方式2: 使用 Process（备选方案）
        try {
            // 查找 node 可执行文件
            String nodePath = findNodeExecutable();
            
            ProcessBuilder pb = new ProcessBuilder(
                nodePath,
                scriptFullPath
            );
            pb.directory(new File(workingDirectory));
            pb.redirectErrorStream(true);
            
            // 设置环境变量
            pb.environment().put("NODE_ENV", "production");
            pb.environment().put("PORT", "3080");
            pb.environment().put("HOME", workingDirectory);
            
            nodeProcess = pb.start();
            isStarted = true;
            
            // 读取输出流（可选，用于调试）
            startOutputReader(nodeProcess);
            
            Log.d(TAG, "Node.js 进程启动成功");
            
        } catch (Exception e) {
            Log.e(TAG, "启动失败", e);
            throw new IOException("启动 Node.js 失败", e);
        }
    }
    
    /**
     * 停止 Node.js
     */
    public void stop() {
        if (nodeProcess != null) {
            nodeProcess.destroy();
            try {
                nodeProcess.waitFor();
            } catch (InterruptedException e) {
                Log.e(TAG, "等待进程结束被中断", e);
            }
            nodeProcess = null;
        }
        isStarted = false;
    }
    
    /**
     * 销毁并释放资源
     */
    public void destroy() {
        stop();
    }
    
    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        if (nodeProcess == null) return false;
        try {
            nodeProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
    
    /**
     * 查找 Node.js 可执行文件
     */
    private String findNodeExecutable() throws IOException {
        // 尝试常见的 Node.js 路径
        String[] possiblePaths = {
            "/usr/bin/node",
            "/usr/local/bin/node",
            "/data/data/com.termux/files/usr/bin/node", // Termux
            workingDirectory + "/node"
        };
        
        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists() && file.canExecute()) {
                return path;
            }
        }
        
        // 如果找不到，使用 PATH 环境变量
        return "node";
    }
    
    /**
     * 从 assets 复制文件到内部存储
     */
    private void copyAssetsToInternalStorage() throws IOException {
        String[] assetFiles = {
            "nodejs-project/index.js",
            "nodejs-project/package.json",
            "nodejs-project/server.js",
            "nodejs-project/dsh-core.js"
        };
        
        for (String assetFile : assetFiles) {
            copyAssetFile(assetFile);
        }
    }
    
    /**
     * 复制单个 asset 文件
     */
    private void copyAssetFile(String assetPath) throws IOException {
        File destFile = new File(workingDirectory, assetPath);
        
        // 如果目标文件已存在，跳过
        if (destFile.exists()) {
            return;
        }
        
        // 创建目录
        destFile.getParentFile().mkdirs();
        
        // 复制文件
        InputStream is = context.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(destFile);
        
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length);
        }
        
        os.flush();
        os.close();
        is.close();
        
        Log.d(TAG, "复制文件: " + assetPath);
    }
    
    /**
     * 读取进程输出（用于调试）
     */
    private void startOutputReader(Process process) {
        new Thread(() -> {
            try {
                InputStream is = process.getInputStream();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    String output = new String(buffer, 0, length);
                    Log.d(TAG, "Node输出: " + output);
                }
            } catch (IOException e) {
                Log.e(TAG, "读取输出失败", e);
            }
        }).start();
    }
}
