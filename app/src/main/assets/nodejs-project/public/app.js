/**
 * DeepSeek Harness Mobile - 前端应用
 */

// 全局状态
const state = {
    conversations: [],
    currentConversation: null,
    settings: {
        apiKey: '',
        model: 'deepseek-chat',
        temperature: 0.7,
        maxTokens: 2048
    },
    isLoading: false,
    ws: null
};

// DOM 元素
const elements = {};

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    initElements();
    initEventListeners();
    loadSettings();
    loadConversations();
    connectWebSocket();
});

// 初始化 DOM 元素
function initElements() {
    elements.sidebar = document.getElementById('sidebar');
    elements.menuBtn = document.getElementById('menuBtn');
    elements.newChatBtn = document.getElementById('newChatBtn');
    elements.conversationList = document.getElementById('conversationList');
    elements.settingsBtn = document.getElementById('settingsBtn');
    elements.settingsPanel = document.getElementById('settingsPanel');
    elements.closeSettings = document.getElementById('closeSettings');
    elements.welcomeScreen = document.getElementById('welcomeScreen');
    elements.chatScreen = document.getElementById('chatScreen');
    elements.startBtn = document.getElementById('startBtn');
    elements.chatTitle = document.getElementById('chatTitle');
    elements.messageList = document.getElementById('messageList');
    elements.messageInput = document.getElementById('messageInput');
    elements.sendBtn = document.getElementById('sendBtn');
    elements.clearBtn = document.getElementById('clearBtn');
    elements.apiKeyInput = document.getElementById('apiKeyInput');
    elements.modelSelect = document.getElementById('modelSelect');
    elements.tempSlider = document.getElementById('tempSlider');
    elements.tempValue = document.getElementById('tempValue');
    elements.tokensSlider = document.getElementById('tokensSlider');
    elements.tokensValue = document.getElementById('tokensValue');
    elements.saveSettings = document.getElementById('saveSettings');
    elements.charCount = document.querySelector('.char-count');
}

// 初始化事件监听
function initEventListeners() {
    // 侧边栏切换
    elements.menuBtn.addEventListener('click', toggleSidebar);
    
    // 新建对话
    elements.newChatBtn.addEventListener('click', createNewConversation);
    elements.startBtn.addEventListener('click', createNewConversation);
    
    // 设置
    elements.settingsBtn.addEventListener('click', openSettings);
    elements.closeSettings.addEventListener('click', closeSettings);
    elements.saveSettings.addEventListener('click', saveSettings);
    
    // 发送消息
    elements.sendBtn.addEventListener('click', sendMessage);
    elements.messageInput.addEventListener('keydown', handleKeyDown);
    elements.messageInput.addEventListener('input', updateCharCount);
    
    // 清空对话
    elements.clearBtn.addEventListener('click', clearCurrentConversation);
    
    // 设置滑块
    elements.tempSlider.addEventListener('input', (e) => {
        elements.tempValue.textContent = e.target.value;
    });
    elements.tokensSlider.addEventListener('input', (e) => {
        elements.tokensValue.textContent = e.target.value;
    });
}

// 切换侧边栏
function toggleSidebar() {
    elements.sidebar.classList.toggle('open');
}

// 创建新对话
async function createNewConversation() {
    try {
        const response = await fetch('/api/conversations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: '新对话' })
        });
        
        const conversation = await response.json();
        state.conversations.unshift(conversation);
        state.currentConversation = conversation;
        
        renderConversationList();
        showChatScreen();
        updateChatTitle();
        
        // 关闭侧边栏（移动端）
        if (window.innerWidth <= 768) {
            elements.sidebar.classList.remove('open');
        }
    } catch (error) {
        console.error('创建对话失败:', error);
        alert('创建对话失败，请重试');
    }
}

// 加载对话列表
async function loadConversations() {
    try {
        const response = await fetch('/api/conversations');
        state.conversations = await response.json();
        renderConversationList();
    } catch (error) {
        console.error('加载对话列表失败:', error);
    }
}

// 渲染对话列表
function renderConversationList() {
    elements.conversationList.innerHTML = '';
    
    state.conversations.forEach(conv => {
        const item = document.createElement('div');
        item.className = `conversation-item ${state.currentConversation?.id === conv.id ? 'active' : ''}`;
        item.innerHTML = `
            <i class="fas fa-comment"></i>
            <span>${escapeHtml(conv.title)}</span>
            <button class="delete-btn" data-id="${conv.id}">
                <i class="fas fa-trash"></i>
            </button>
        `;
        
        item.addEventListener('click', () => loadConversation(conv.id));
        
        const deleteBtn = item.querySelector('.delete-btn');
        deleteBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteConversation(conv.id);
        });
        
        elements.conversationList.appendChild(item);
    });
}

// 加载对话
async function loadConversation(conversationId) {
    try {
        const response = await fetch(`/api/conversations/${conversationId}`);
        const conversation = await response.json();
        
        state.currentConversation = conversation;
        renderConversationList();
        showChatScreen();
        renderMessages();
        updateChatTitle();
        
        // 关闭侧边栏（移动端）
        if (window.innerWidth <= 768) {
            elements.sidebar.classList.remove('open');
        }
    } catch (error) {
        console.error('加载对话失败:', error);
    }
}

// 删除对话
async function deleteConversation(conversationId) {
    if (!confirm('确定要删除这个对话吗？')) return;
    
    try {
        await fetch(`/api/conversations/${conversationId}`, {
            method: 'DELETE'
        });
        
        state.conversations = state.conversations.filter(c => c.id !== conversationId);
        
        if (state.currentConversation?.id === conversationId) {
            state.currentConversation = null;
            showWelcomeScreen();
        }
        
        renderConversationList();
    } catch (error) {
        console.error('删除对话失败:', error);
    }
}

// 显示聊天界面
function showChatScreen() {
    elements.welcomeScreen.style.display = 'none';
    elements.chatScreen.style.display = 'flex';
}

// 显示欢迎界面
function showWelcomeScreen() {
    elements.welcomeScreen.style.display = 'flex';
    elements.chatScreen.style.display = 'none';
}

// 更新聊天标题
function updateChatTitle() {
    if (state.currentConversation) {
        elements.chatTitle.textContent = state.currentConversation.title;
    }
}

// 渲染消息
function renderMessages() {
    elements.messageList.innerHTML = '';
    
    if (!state.currentConversation) return;
    
    state.currentConversation.messages.forEach(msg => {
        appendMessage(msg.role, msg.content);
    });
    
    scrollToBottom();
}

// 添加消息到界面
function appendMessage(role, content) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role} fade-in`;
    
    const avatarIcon = role === 'user' ? 'fa-user' : 'fa-robot';
    
    let formattedContent = content;
    if (role === 'assistant') {
        // 使用 marked 渲染 markdown
        formattedContent = marked.parse(content, {
            highlight: function(code, lang) {
                if (lang && hljs.getLanguage(lang)) {
                    return hljs.highlight(code, { language: lang }).value;
                }
                return hljs.highlightAuto(code).value;
            }
        });
    } else {
        formattedContent = escapeHtml(content).replace(/\n/g, '<br>');
    }
    
    messageDiv.innerHTML = `
        <div class="message-avatar">
            <i class="fas ${avatarIcon}"></i>
        </div>
        <div class="message-content">
            ${formattedContent}
        </div>
    `;
    
    elements.messageList.appendChild(messageDiv);
    scrollToBottom();
}

// 添加加载动画
function appendTypingIndicator() {
    const typingDiv = document.createElement('div');
    typingDiv.className = 'message assistant fade-in';
    typingDiv.id = 'typingIndicator';
    typingDiv.innerHTML = `
        <div class="message-avatar">
            <i class="fas fa-robot"></i>
        </div>
        <div class="message-content">
            <div class="typing-indicator">
                <span></span>
                <span></span>
                <span></span>
            </div>
        </div>
    `;
    
    elements.messageList.appendChild(typingDiv);
    scrollToBottom();
}

// 移除加载动画
function removeTypingIndicator() {
    const indicator = document.getElementById('typingIndicator');
    if (indicator) {
        indicator.remove();
    }
}

// 发送消息
async function sendMessage() {
    const content = elements.messageInput.value.trim();
    if (!content || state.isLoading) return;
    
    // 检查 API Key
    if (!state.settings.apiKey) {
        alert('请先在设置中配置 API Key');
        openSettings();
        return;
    }
    
    // 确保有当前对话
    if (!state.currentConversation) {
        await createNewConversation();
    }
    
    // 清空输入框
    elements.messageInput.value = '';
    updateCharCount();
    
    // 添加用户消息
    appendMessage('user', content);
    
    // 显示加载动画
    state.isLoading = true;
    elements.sendBtn.disabled = true;
    appendTypingIndicator();
    
    try {
        // 使用 WebSocket 流式发送
        if (state.ws && state.ws.readyState === WebSocket.OPEN) {
            state.ws.send(JSON.stringify({
                conversationId: state.currentConversation.id,
                message: content
            }));
        } else {
            // 回退到 HTTP 请求
            const response = await fetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    conversationId: state.currentConversation.id,
                    message: content
                })
            });
            
            const data = await response.json();
            
            if (data.error) {
                throw new Error(data.error);
            }
            
            removeTypingIndicator();
            appendMessage('assistant', data.response);
            
            // 更新对话列表
            loadConversations();
        }
    } catch (error) {
        console.error('发送消息失败:', error);
        removeTypingIndicator();
        appendMessage('assistant', `错误: ${error.message}`);
    } finally {
        state.isLoading = false;
        elements.sendBtn.disabled = false;
    }
}

// 处理键盘事件
function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
}

// 更新字符计数
function updateCharCount() {
    const count = elements.messageInput.value.length;
    elements.charCount.textContent = `${count}/2000`;
}

// 清空当前对话
async function clearCurrentConversation() {
    if (!state.currentConversation) return;
    if (!confirm('确定要清空这个对话吗？')) return;
    
    await deleteConversation(state.currentConversation.id);
}

// 滚动到底部
function scrollToBottom() {
    elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

// 打开设置
function openSettings() {
    elements.apiKeyInput.value = state.settings.apiKey;
    elements.modelSelect.value = state.settings.model;
    elements.tempSlider.value = state.settings.temperature;
    elements.tempValue.textContent = state.settings.temperature;
    elements.tokensSlider.value = state.settings.maxTokens;
    elements.tokensValue.textContent = state.settings.maxTokens;
    
    elements.settingsPanel.style.display = 'flex';
}

// 关闭设置
function closeSettings() {
    elements.settingsPanel.style.display = 'none';
}

// 保存设置
async function saveSettings() {
    state.settings = {
        apiKey: elements.apiKeyInput.value.trim(),
        model: elements.modelSelect.value,
        temperature: parseFloat(elements.tempSlider.value),
        maxTokens: parseInt(elements.tokensSlider.value)
    };
    
    try {
        await fetch('/api/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(state.settings)
        });
        
        alert('设置已保存');
        closeSettings();
    } catch (error) {
        console.error('保存设置失败:', error);
        alert('保存设置失败，请重试');
    }
}

// 加载设置
async function loadSettings() {
    try {
        const response = await fetch('/api/settings');
        const settings = await response.json();
        state.settings = { ...state.settings, ...settings };
    } catch (error) {
        console.error('加载设置失败:', error);
    }
}

// 连接 WebSocket
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}`;
    
    state.ws = new WebSocket(wsUrl);
    
    state.ws.onopen = () => {
        console.log('WebSocket 连接已建立');
    };
    
    state.ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        
        if (data.type === 'chunk') {
            // 流式接收内容
            handleStreamChunk(data.content);
        } else if (data.type === 'done') {
            // 流式接收完成
            handleStreamDone();
        } else if (data.type === 'error') {
            // 错误
            handleStreamError(data.message);
        }
    };
    
    state.ws.onclose = () => {
        console.log('WebSocket 连接关闭，尝试重连...');
        setTimeout(connectWebSocket, 3000);
    };
    
    state.ws.onerror = (error) => {
        console.error('WebSocket 错误:', error);
    };
}

// 处理流式数据块
let streamContent = '';
let currentStreamMessage = null;

function handleStreamChunk(content) {
    if (!currentStreamMessage) {
        removeTypingIndicator();
        
        // 创建新的消息元素
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant fade-in';
        messageDiv.innerHTML = `
            <div class="message-avatar">
                <i class="fas fa-robot"></i>
            </div>
            <div class="message-content"></div>
        `;
        
        elements.messageList.appendChild(messageDiv);
        currentStreamMessage = messageDiv.querySelector('.message-content');
        streamContent = '';
    }
    
    streamContent += content;
    
    // 渲染 markdown
    currentStreamMessage.innerHTML = marked.parse(streamContent, {
        highlight: function(code, lang) {
            if (lang && hljs.getLanguage(lang)) {
                return hljs.highlight(code, { language: lang }).value;
            }
            return hljs.highlightAuto(code).value;
        }
    });
    
    scrollToBottom();
}

// 流式接收完成
function handleStreamDone() {
    currentStreamMessage = null;
    streamContent = '';
    
    state.isLoading = false;
    elements.sendBtn.disabled = false;
    
    // 更新对话列表
    loadConversations();
}

// 流式接收错误
function handleStreamError(message) {
    removeTypingIndicator();
    appendMessage('assistant', `错误: ${message}`);
    
    currentStreamMessage = null;
    streamContent = '';
    
    state.isLoading = false;
    elements.sendBtn.disabled = false;
}

// HTML 转义
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
