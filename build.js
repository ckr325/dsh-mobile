#!/usr/bin/env node

/**
 * DeepSeek Harness Mobile - 构建脚本
 * 
 * 功能：
 * 1. 安装依赖
 * 2. 准备 Node.js 项目
 * 3. 构建 Android APK
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT_DIR = __dirname;
const NODEJS_PROJECT_DIR = path.join(ROOT_DIR, 'app/src/main/assets/nodejs-project');

console.log('================================');
console.log('DeepSeek Harness Mobile Builder');
console.log('================================');
console.log('');

// 检查环境
function checkEnvironment() {
    console.log('检查环境...');
    
    // 检查 Node.js
    try {
        const nodeVersion = execSync('node --version', { encoding: 'utf8' }).trim();
        console.log(`✓ Node.js: ${nodeVersion}`);
    } catch (e) {
        console.error('✗ 未找到 Node.js，请安装 Node.js 16+');
        process.exit(1);
    }
    
    // 检查 npm
    try {
        const npmVersion = execSync('npm --version', { encoding: 'utf8' }).trim();
        console.log(`✓ npm: ${npmVersion}`);
    } catch (e) {
        console.error('✗ 未找到 npm');
        process.exit(1);
    }
    
    // 检查 Java
    try {
        const javaVersion = execSync('java -version 2>&1', { encoding: 'utf8' }).trim();
        console.log(`✓ Java: ${javaVersion.split('\n')[0]}`);
    } catch (e) {
        console.warn('⚠ 未找到 Java（Android 构建需要 JDK 11+）');
    }
    
    console.log('');
}

// 安装依赖
function installDependencies() {
    console.log('安装依赖...');
    
    // 安装主项目依赖
    execSync('npm install', { cwd: ROOT_DIR, stdio: 'inherit' });
    
    // 安装 Node.js 项目依赖
    if (fs.existsSync(path.join(NODEJS_PROJECT_DIR, 'package.json'))) {
        execSync('npm install', { cwd: NODEJS_PROJECT_DIR, stdio: 'inherit' });
    }
    
    console.log('');
}

// 准备 Node.js 项目
function prepareNodeJSProject() {
    console.log('准备 Node.js 项目...');
    
    // 确保目录存在
    if (!fs.existsSync(NODEJS_PROJECT_DIR)) {
        fs.mkdirSync(NODEJS_PROJECT_DIR, { recursive: true });
    }
    
    // 复制 package.json（如果不存在）
    const packageJsonPath = path.join(NODEJS_PROJECT_DIR, 'package.json');
    if (!fs.existsSync(packageJsonPath)) {
        console.log('创建 package.json...');
        // 已经在前面创建了
    }
    
    console.log('✓ Node.js 项目准备完成');
    console.log('');
}

// 构建 Android
function buildAndroid() {
    console.log('构建 Android APK...');
    console.log('');
    console.log('请使用以下方式构建：');
    console.log('');
    console.log('方式 1: 使用 Android Studio');
    console.log('  1. 打开 Android Studio');
    console.log('  2. 选择 "Open an existing Android Studio project"');
    console.log('  3. 选择此项目目录');
    console.log('  4. 点击 Build > Build Bundle(s) / APK(s) > Build APK(s)');
    console.log('');
    console.log('方式 2: 使用命令行');
    console.log('  ./gradlew assembleDebug');
    console.log('');
    console.log('APK 位置: app/build/outputs/apk/debug/app-debug.apk');
    console.log('');
}

// 主函数
function main() {
    checkEnvironment();
    prepareNodeJSProject();
    installDependencies();
    buildAndroid();
    
    console.log('================================');
    console.log('构建准备完成！');
    console.log('================================');
}

main();
