# DeepSeek Harness - 手机版 (Android)

🎉 **完全自包含** —— 服务器和客户端都在手机上运行！

## ✨ 功能特点

- 📱 **一键安装** - 安装 APK 即可使用，无需电脑
- 🚀 **本地运行** - 服务器跑在手机上，无需联网
- 🤖 **AI 助手** - 完整的 DeepSeek Harness 功能
- 💾 **离线可用** - 不依赖外部服务器
- 🔋 **后台运行** - 支持后台持续运行

## 📋 系统要求

- Android 7.0 (API 24) 或更高版本
- ARM64 或 x86_64 架构
- 至少 2GB 可用内存
- 500MB 存储空间

## 🛠️ 技术架构

```
┌─────────────────────────────────────┐
│         Android App (Java)          │
│  ┌───────────────────────────────┐  │
│  │         WebView UI            │  │
│  │   (连接到 localhost:3080)      │  │
│  └───────────────────────────────┘  │
│              │                      │
│  ┌───────────────────────────────┐  │
│  │    Node.js Service (后台)      │  │
│  │    (nodejs-mobile 嵌入)        │  │
│  │         │                      │  │
│  │    ┌────┴────┐                │  │
│  │    │  DSH    │                │  │
│  │    │ Server  │                │  │
│  │    └─────────┘                │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

## 📦 项目结构

```
dsh-mobile/
├── app/
│   ├── src/main/
│   │   ├── java/com/deepseek/dsh/mobile/
│   │   │   ├── MainActivity.java      # 主界面
│   │   │   ├── NodeService.java       # Node.js 后台服务
│   │   │   └── SettingsActivity.java  # 设置页面
│   │   ├── assets/
│   │   │   └── nodejs-project/        # DSH 服务器文件
│   │   │       ├── index.js           # 服务器入口
│   │   │       ├── package.json
│   │   │       └── ...
│   │   └── res/
│   │       ├── layout/
│   │       └── values/
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## 🚀 构建步骤

### 1. 环境准备

安装：
- [Android Studio](https://developer.android.com/studio)
- [Node.js 18+](https://nodejs.org/)

### 2. 克隆并构建

```bash
# 进入项目目录
cd dsh-mobile

# 安装依赖
npm install

# 构建 DSH 服务器（精简版）
npm run build:server

# 用 Android Studio 打开项目
# 或使用命令行构建
./gradlew assembleDebug
```

### 3. 安装到手机

```bash
# USB 调试安装
adb install app/build/outputs/apk/debug/app-debug.apk

# 或者直接传输 APK 到手机安装
```

## 📖 使用说明

1. **首次启动**
   - 打开 App
   - 等待 Node.js 服务启动（约 5-10 秒）
   - 自动加载 DSH 界面

2. **配置 AI**
   - 在设置中配置 DeepSeek API Key
   - 选择模型和参数

3. **开始使用**
   - 在对话框输入问题
   - DSH 会自动处理并回复

## ⚠️ 注意事项

- 首次启动需要加载 Node.js，可能需要几秒钟
- 后台运行会消耗电量，建议在设置中调整
- 如果 App 被系统杀死，重新打开即可
- 建议在 WiFi 环境下使用 AI 功能（需要网络调用模型 API）

## 🔧 高级配置

### 修改服务器端口
编辑 `app/src/main/assets/nodejs-project/index.js`：
```javascript
const PORT = 3080; // 修改为其他端口
```

### 添加更多功能
将 DSH 插件放入 `nodejs-project/plugins/` 目录

## 📄 许可证

MIT License
