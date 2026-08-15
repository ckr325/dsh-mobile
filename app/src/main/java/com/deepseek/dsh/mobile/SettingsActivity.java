package com.deepseek.dsh.mobile;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页面（占位，实际设置通过 WebView 内的界面完成）
 */
public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置通过 WebView 内的 Web 界面完成，这个 Activity 只是占位
        finish();
    }
}
