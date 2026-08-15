package com.deepseek.dsh.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.deepseek.dsh.mobile.nodejs.NodeJS;

/**
 * Node.js 后台服务
 * 
 * 功能：
 * 1. 在后台运行 Node.js 运行时
 * 2. 启动 DSH Web 服务器
 * 3. 保持服务存活
 */
public class NodeService extends Service {
    
    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = "dsh_node_service";
    private static final int NOTIFICATION_ID = 1001;
    
    private final IBinder binder = new NodeBinder();
    private NodeJS nodeJS;
    private boolean isRunning = false;
    private Handler handler;
    
    public class NodeBinder extends Binder {
        NodeService getService() {
            return NodeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "服务启动");
        
        // 前台服务通知
        startForeground(NOTIFICATION_ID, createNotification("正在启动 Node.js 服务..."));
        
        // 启动 Node.js
        if (!isRunning) {
            startNodeJS();
        }
        
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startNodeJS() {
        new Thread(() -> {
            try {
                Log.d(TAG, "初始化 Node.js...");
                updateNotification("正在初始化 Node.js...");
                
                // 初始化 Node.js
                nodeJS = new NodeJS(this);
                
                // 设置工作目录
                String workingDir = getFilesDir().getAbsolutePath() + "/dsh-data";
                nodeJS.setWorkingDirectory(workingDir);
                
                Log.d(TAG, "启动 DSH 服务器...");
                updateNotification("正在启动 DSH 服务器...");
                
                // 启动 DSH 服务器
                nodeJS.start("nodejs-project/index.js");
                
                isRunning = true;
                
                handler.post(() -> {
                    Toast.makeText(NodeService.this, "DSH 服务已启动", Toast.LENGTH_SHORT).show();
                    updateNotification("DSH 服务运行中");
                });
                
                Log.d(TAG, "Node.js 服务启动成功");
                
            } catch (Exception e) {
                Log.e(TAG, "启动失败", e);
                handler.post(() -> {
                    Toast.makeText(NodeService.this, "服务启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                stopSelf();
            }
        }).start();
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void restart() {
        if (nodeJS != null) {
            nodeJS.destroy();
        }
        isRunning = false;
        startNodeJS();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DSH 服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("DeepSeek Harness 后台服务");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepSeek Harness")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        Log.d(TAG, "服务停止");
        
        if (nodeJS != null) {
            nodeJS.destroy();
            nodeJS = null;
        }
        
        isRunning = false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        
        // 任务被移除时重启服务（可选）
        // 如果需要持续运行，取消下面的注释
        /*
        Intent restartServiceIntent = new Intent(getApplicationContext(), NodeService.class);
        restartServiceIntent.setPackage(getPackageName());
        startService(restartServiceIntent);
        */
    }
}
