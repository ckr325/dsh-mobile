#!/usr/bin/env node

/**
 * DeepSeek Harness - 手机版服务器
 * 
 * 精简版 DSH 服务器，专为 Android 优化
 * 提供核心 AI 对话功能
 */

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');
const fs = require('fs');
const cors = require('cors');
const bodyParser = require('body-parser');

// 配置
const PORT = process.env.PORT || 3080;
const HOST = '0.0.0.0';
const DATA_DIR = process.env.HOME || process.cwd();

// 创建 Express 应用
const app = express();
const server = http.createServer(app);

// WebSocket 服务器（用于实时对话）
const wss = new WebSocket.Server({ server });

// 中间件
app.use(cors());
app.use(bodyParser.json({ limit: '10mb' }));
app.use(bodyParser.urlencoded({ extended: true }));

// 静态文件服务
app.use(express.static(path.join(__dirname, 'public')));

// 数据存储
const dataFile = path.join(DATA_DIR, 'dsh-data.json');
let appData = {
  conversations: [],
  settings: {
    apiKey: '',
    model: 'deepseek-chat',
    temperature: 0.7,
    maxTokens: 2048
  }
};

// 加载数据
function loadData() {
  try {
    if (fs.existsSync(dataFile)) {
      const data = fs.readFileSync(dataFile, 'utf8');
      appData = JSON.parse(data);
      console.log('数据加载成功');
    }
  } catch (e) {
    console.error('加载数据失败:', e);
  }
}

// 保存数据
function saveData() {
  try {
    fs.writeFileSync(dataFile, JSON.stringify(appData, null, 2));
  } catch (e) {
    console.error('保存数据失败:', e);
  }
}

// 初始化
loadData();

// API 路由

// 获取设置
app.get('/api/settings', (req, res) => {
  res.json(appData.settings);
});

// 更新设置
app.post('/api/settings', (req, res) => {
  appData.settings = { ...appData.settings, ...req.body };
  saveData();
  res.json({ success: true });
});

// 获取对话列表
app.get('/api/conversations', (req, res) => {
  res.json(appData.conversations);
});

// 创建新对话
app.post('/api/conversations', (req, res) => {
  const conversation = {
    id: Date.now().toString(),
    title: req.body.title || '新对话',
    messages: [],
    createdAt: new Date().toISOString()
  };
  appData.conversations.unshift(conversation);
  saveData();
  res.json(conversation);
});

// 获取对话详情
app.get('/api/conversations/:id', (req, res) => {
  const conversation = appData.conversations.find(c => c.id === req.params.id);
  if (conversation) {
    res.json(conversation);
  } else {
    res.status(404).json({ error: '对话不存在' });
  }
});

// 删除对话
app.delete('/api/conversations/:id', (req, res) => {
  appData.conversations = appData.conversations.filter(c => c.id !== req.params.id);
  saveData();
  res.json({ success: true });
});

// 发送消息（AI 对话）
app.post('/api/chat', async (req, res) => {
  const { conversationId, message } = req.body;
  
  if (!appData.settings.apiKey) {
    return res.status(400).json({ error: '请先配置 API Key' });
  }
  
  // 找到对话
  let conversation = appData.conversations.find(c => c.id === conversationId);
  if (!conversation) {
    conversation = {
      id: conversationId || Date.now().toString(),
      title: message.substring(0, 30) + '...',
      messages: [],
      createdAt: new Date().toISOString()
    };
    appData.conversations.unshift(conversation);
  }
  
  // 添加用户消息
  conversation.messages.push({
    role: 'user',
    content: message,
    timestamp: new Date().toISOString()
  });
  
  try {
    // 调用 DeepSeek API
    const response = await callDeepSeekAPI(conversation.messages);
    
    // 添加 AI 回复
    conversation.messages.push({
      role: 'assistant',
      content: response,
      timestamp: new Date().toISOString()
    });
    
    saveData();
    res.json({ success: true, response });
    
  } catch (error) {
    console.error('AI 调用失败:', error);
    res.status(500).json({ error: 'AI 服务调用失败: ' + error.message });
  }
});

// 流式对话（WebSocket）
wss.on('connection', (ws) => {
  console.log('WebSocket 连接建立');
  
  ws.on('message', async (data) => {
    try {
      const { conversationId, message } = JSON.parse(data);
      
      if (!appData.settings.apiKey) {
        ws.send(JSON.stringify({ error: '请先配置 API Key' }));
        return;
      }
      
      // 找到或创建对话
      let conversation = appData.conversations.find(c => c.id === conversationId);
      if (!conversation) {
        conversation = {
          id: conversationId || Date.now().toString(),
          title: message.substring(0, 30) + '...',
          messages: [],
          createdAt: new Date().toISOString()
        };
        appData.conversations.unshift(conversation);
      }
      
      // 添加用户消息
      conversation.messages.push({
        role: 'user',
        content: message,
        timestamp: new Date().toISOString()
      });
      
      // 流式调用 DeepSeek API
      await streamDeepSeekAPI(ws, conversation.messages, (chunk) => {
        ws.send(JSON.stringify({ type: 'chunk', content: chunk }));
      });
      
      // 完成
      ws.send(JSON.stringify({ type: 'done' }));
      saveData();
      
    } catch (error) {
      console.error('WebSocket 处理错误:', error);
      ws.send(JSON.stringify({ type: 'error', message: error.message }));
    }
  });
  
  ws.on('close', () => {
    console.log('WebSocket 连接关闭');
  });
});

// 调用 DeepSeek API（非流式）
async function callDeepSeekAPI(messages) {
  const https = require('https');
  
  const requestData = JSON.stringify({
    model: appData.settings.model,
    messages: messages.map(m => ({
      role: m.role,
      content: m.content
    })),
    temperature: appData.settings.temperature,
    max_tokens: appData.settings.maxTokens
  });
  
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'api.deepseek.com',
      port: 443,
      path: '/v1/chat/completions',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${appData.settings.apiKey}`,
        'Content-Length': Buffer.byteLength(requestData)
      }
    };
    
    const req = https.request(options, (res) => {
      let data = '';
      
      res.on('data', (chunk) => {
        data += chunk;
      });
      
      res.on('end', () => {
        try {
          const response = JSON.parse(data);
          if (response.choices && response.choices[0]) {
            resolve(response.choices[0].message.content);
          } else {
            reject(new Error('无效的 API 响应'));
          }
        } catch (e) {
          reject(e);
        }
      });
    });
    
    req.on('error', reject);
    req.write(requestData);
    req.end();
  });
}

// 流式调用 DeepSeek API
async function streamDeepSeekAPI(ws, messages, onChunk) {
  const https = require('https');
  
  const requestData = JSON.stringify({
    model: appData.settings.model,
    messages: messages.map(m => ({
      role: m.role,
      content: m.content
    })),
    temperature: appData.settings.temperature,
    max_tokens: appData.settings.maxTokens,
    stream: true
  });
  
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'api.deepseek.com',
      port: 443,
      path: '/v1/chat/completions',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${appData.settings.apiKey}`,
        'Content-Length': Buffer.byteLength(requestData)
      }
    };
    
    const req = https.request(options, (res) => {
      let buffer = '';
      let fullContent = '';
      
      res.on('data', (chunk) => {
        buffer += chunk.toString();
        
        // 处理 SSE 数据
        const lines = buffer.split('\n');
        buffer = lines.pop(); // 保留不完整的行
        
        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6);
            
            if (data === '[DONE]') {
              // 保存完整的 AI 回复
              messages.push({
                role: 'assistant',
                content: fullContent,
                timestamp: new Date().toISOString()
              });
              resolve();
              return;
            }
            
            try {
              const parsed = JSON.parse(data);
              const content = parsed.choices?.[0]?.delta?.content || '';
              if (content) {
                fullContent += content;
                onChunk(content);
              }
            } catch (e) {
              // 忽略解析错误
            }
          }
        }
      });
      
      res.on('end', () => {
        // 如果没有收到 [DONE]，也要保存
        if (fullContent) {
          messages.push({
            role: 'assistant',
            content: fullContent,
            timestamp: new Date().toISOString()
          });
        }
        resolve();
      });
    });
    
    req.on('error', reject);
    req.write(requestData);
    req.end();
  });
}

// 健康检查
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    version: '1.0.0',
    uptime: process.uptime(),
    memory: process.memoryUsage()
  });
});

// 主页路由
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// 启动服务器
server.listen(PORT, HOST, () => {
  console.log(`================================`);
  console.log(`DeepSeek Harness Mobile Server`);
  console.log(`================================`);
  console.log(`服务器运行在: http://${HOST}:${PORT}`);
  console.log(`数据目录: ${DATA_DIR}`);
  console.log(`================================`);
});

// 优雅退出
process.on('SIGINT', () => {
  console.log('\n正在关闭服务器...');
  saveData();
  server.close(() => {
    console.log('服务器已关闭');
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  console.log('\n收到终止信号，正在关闭...');
  saveData();
  server.close(() => {
    process.exit(0);
  });
});
