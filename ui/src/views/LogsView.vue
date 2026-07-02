<template>
  <div class="logs-container">
    <VPageHeader title="AI回复日志">
      <template #icon><IconPlug class="header-icon" /></template>
      <template #actions>
        <VButton @click="$router.push({ name: 'CommentAiAutopilot' })">返回概览</VButton>
      </template>
    </VPageHeader>

    <!-- 批量操作工具栏 -->
    <div v-if="selectedNames.size > 0" class="toolbar batch-toolbar">
      <span class="batch-text">已选择 {{ selectedNames.size }} 项</span>
      <div class="batch-actions">
        <button class="btn-batch btn-pass" @click="batchApprove">批量通过</button>
        <button class="btn-batch btn-reject" @click="batchReject">批量拒绝</button>
        <button class="btn-batch btn-delete" @click="batchDelete">批量删除</button>
        <button class="btn-batch btn-cancel" @click="selectedNames.clear(); selectAll = false">取消选择</button>
      </div>
    </div>

    <!-- 过滤工具栏 -->
    <div class="toolbar filter-toolbar">
      <select v-model="filterStatus" class="filter-select">
        <option value="">全部状态</option>
        <option value="PASS">通过</option>
        <option value="FAIL">失败</option>
        <option value="PENDING">待审核</option>
        <option value="REJECTED">已拒绝</option>
        <option value="FILTERED">已拦截</option>
        <option value="FALSE_POSITIVE">误报通过</option>
      </select>
      <select v-model="filterSentiment" class="filter-select">
        <option value="">全部情感</option>
        <option value="VERY_POSITIVE">非常正面</option>
        <option value="POSITIVE">正面</option>
        <option value="NEUTRAL">中性</option>
        <option value="NEGATIVE">负面</option>
        <option value="VERY_NEGATIVE">非常负面</option>
      </select>
      <input v-model="filterKeyword" type="text" placeholder="搜索回复内容..." class="filter-input" />
      <button class="btn-reset" @click="resetFilters">重置</button>
      <div class="autorefresh-group">
        <button class="btn-autorefresh" :class="{ 'is-active': autoRefresh }" @click="toggleAutoRefresh" :title="autoRefresh ? '点击关闭实时刷新' : '点击开启实时刷新'">
          <span class="autorefresh-dot" v-if="autoRefresh"></span>
          {{ autoRefresh ? '实时刷新' : '实时刷新' }}
        </button>
        <select v-if="autoRefresh" v-model="autoRefreshSecs" class="autorefresh-interval" :title="`刷新间隔：${autoRefreshSecs}秒`">
          <option :value="5">5s</option>
          <option :value="10">10s</option>
          <option :value="30">30s</option>
          <option :value="60">60s</option>
        </select>
        <span v-if="autoRefresh" class="autorefresh-status">{{ lastRefreshLabel }}</span>
      </div>
    </div>

    <!-- 列表区 -->
    <div class="list-area">
      <VLoading v-if="loading" />
      <div v-else-if="replies.length === 0" class="empty-state">暂无记录</div>
      
      <div v-else class="reply-list">
        <div class="select-all-wrap">
          <input type="checkbox" :checked="selectAll" @change="toggleSelectAll" />
          <span>全选本页</span>
        </div>
        
        <div v-for="reply in replies" :key="reply.metadata.name" class="reply-card">
          <div class="card-main">
            <input type="checkbox" :checked="selectedNames.has(reply.metadata.name)" @change="toggleSelect(reply.metadata.name)" />
            <div class="card-content">
              <div class="card-header">
                <div class="tags-wrap">
                  <span class="custom-tag" :class="'tag-' + reply.spec.status">{{ getStatusLabel(reply.spec.status) }}</span>
                  <span class="custom-tag" :class="reply.spec.published ? 'tag-published' : 'tag-draft'">{{ reply.spec.published ? '已发布' : '未发布' }}</span>
                  <span v-if="reply.spec.isAiConversation" class="custom-tag tag-conv">对话</span>
                  <span v-if="reply.spec.sentiment" class="custom-tag" :class="'tag-' + reply.spec.sentiment">{{ getSentimentLabel(reply.spec.sentiment) }}</span>
                </div>
                <span class="card-time">{{ formatDate(reply.metadata.creationTimestamp) }}</span>
              </div>
              <div class="card-text">{{ stripHtml(reply.spec.reply) || '(空)' }}</div>
              <div v-if="reply.spec.status === 'FILTERED'" class="card-filter-reason">
                <svg class="filter-icon" fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/></svg>
                <span class="filter-category" v-if="reply.spec.filterCategory">{{ reply.spec.filterCategory }}</span>
                <span class="filter-detail">{{ reply.spec.filterReason || '未提供具体原因' }}</span>
                <button class="btn-false-positive" @click="openFalsePositiveDialog(reply)">误报反馈</button>
              </div>
              <div v-if="reply.spec.status === 'FALSE_POSITIVE'" class="card-filter-reason">
                <svg class="filter-icon fp-icon-ok" fill="currentColor" viewBox="0 0 20 20"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"/></svg>
                <span class="filter-category">误报</span>
                <span class="filter-detail">{{ reply.spec.filterReason || '用户确认为误报' }}</span>
                <button class="btn-trigger-ai" :disabled="triggerAiLoadingName === reply.metadata.name" @click="handleTriggerAiReply(reply)">
                  <span v-if="triggerAiLoadingName === reply.metadata.name" class="fp-spinner"></span>
                  触发AI回复
                </button>
              </div>
            </div>
          </div>
          <div class="card-footer">
            <div class="footer-info">
              <span>评分: <strong>{{ reply.spec.score }}</strong></span>
              <span v-if="reply.spec.postSlug">
                关联: <a :href="getPostUrl(reply.spec.postSlug, reply.spec.postKind)" target="_blank" class="post-link">{{ reply.spec.postSlug }}</a>
              </span>
              <span v-if="reply.spec.retryCount > 0" class="retry-text">重试 {{ reply.spec.retryCount }} 次</span>
            </div>
            <div class="footer-actions">
              <template v-if="reply.spec.status === 'PASS' && !reply.spec.published">
                <button class="action-btn pass" @click="handleApprove(reply.metadata.name)">通过</button>
                <button class="action-btn reject" @click="handleReject(reply.metadata.name)">拒绝</button>
              </template>
              <button class="action-btn view" @click="openConversation(reply)">查看对话</button>
              <button class="action-btn delete" @click="handleDelete(reply.metadata.name)">删除</button>
            </div>
          </div>
        </div>
      </div>
      
      <div v-if="totalPages > 1" class="pagination">
        <span>共 {{ total }} 条</span>
        <div class="pagination-btns">
          <VButton size="sm" :disabled="page <= 1 || loading" @click="page--">上一页</VButton>
          <VButton size="sm" :disabled="page >= totalPages || loading" @click="page++">下一页</VButton>
        </div>
      </div>
    </div>

    <!-- 完整对话弹窗 -->
    <teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="showDialog = false">
        <div class="dialog-box">
          <div class="dialog-header">
            <h3>对话上下文</h3>
            <button class="close-btn" @click="showDialog = false"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" width="24" height="24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg></button>
          </div>
          <div class="dialog-body">
            <VLoading v-if="conversationLoading" />
            <div v-else-if="conversationMessages.length === 0" class="empty-state">暂无内容</div>
            <div v-else class="chat-container">
              <div v-for="(msg, idx) in conversationMessages" :key="idx" class="chat-row" :class="msg.isAi ? 'row-ai' : 'row-user'">
                <div class="chat-message">
                  <div class="chat-owner">{{ msg.owner }}</div>
                  <div class="chat-bubble" :class="msg.isAi ? 'bubble-ai' : 'bubble-user'">
                    
                    <!-- 现代化的精美引用框 (无左边框) -->
                    <div v-if="msg.quoteOwner && msg.quoteContent" class="quote-box">
                      <div class="quote-header">
                        <svg class="quote-icon" fill="currentColor" viewBox="0 0 24 24"><path d="M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z"/></svg>
                        <span class="quote-owner">{{ msg.quoteOwner }}</span>
                      </div>
                      <div class="quote-content">{{ truncateQuote(msg.quoteContent) }}</div>
                    </div>

                    <div class="chat-text" v-html="renderContent(msg.content)"></div>
                  </div>
                  <div class="chat-time">{{ formatDate(msg.time) }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </teleport>

    <!-- 误报反馈确认弹窗 -->
    <teleport to="body">
      <div v-if="showFalsePositiveDialog" class="dialog-overlay" @click.self="closeFalsePositiveDialog">
        <div class="dialog-box fp-dialog">
          <div class="dialog-header">
            <h3>确认为误报？</h3>
            <button class="close-btn" @click="showFalsePositiveDialog = false"><svg fill="none" stroke="currentColor" viewBox="0 0 24 24" width="24" height="24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg></button>
          </div>
          <div class="fp-dialog-body">
            <p class="fp-desc">系统检测到该评论可能包含违规内容，但您认为这是正常表达。请选择处理方式：</p>
            <div class="fp-actions">
              <button class="fp-btn fp-btn-primary" :disabled="fpLoading" @click="handleFalsePositive('aiReply')">
                <span v-if="fpLoading" class="fp-spinner"></span>
                AI 回复
              </button>
              <button class="fp-btn fp-btn-secondary" :disabled="fpLoading" @click="handleFalsePositive('approveOnly')">
                仅通过
              </button>
              <button class="fp-btn fp-btn-ghost" :disabled="fpLoading" @click="closeFalsePositiveDialog">
                取消
              </button>
            </div>
          </div>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from "vue"
import { axiosInstance } from "@halo-dev/api-client"
import { VPageHeader, VButton, VLoading, Toast } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

interface AiCommentReplyItem { metadata: { name: string; creationTimestamp: string }; spec: any }
interface ConversationMessage { type: string; owner: string; content: string; time: string; isAi: boolean; quoteOwner?: string; quoteContent?: string }

const replies = ref<AiCommentReplyItem[]>([]); const loading = ref(false); const batchLoading = ref(false); const page = ref(1); const size = ref(20); const total = ref(0); const totalPages = ref(0);
const selectedNames = ref<Set<string>>(new Set()); const selectAll = ref(false);
const filterStatus = ref(""); const filterSentiment = ref(""); const filterKeyword = ref("");
const showDialog = ref(false); const conversationLoading = ref(false); const conversationMessages = ref<ConversationMessage[]>([]);
const showFalsePositiveDialog = ref(false); const falsePositiveTarget = ref<AiCommentReplyItem | null>(null); const fpLoading = ref(false);
const triggerAiLoadingName = ref<string | null>(null);

// 实时刷新：定时轮询新数据。暂停条件：标签页隐藏、loading 中、弹窗打开。
// 优化：轻量变更检测、新记录提示、连续失败自动关闭、间隔可配置、用户操作后重置计时、保留滚动位置、显示相对更新时间
const autoRefresh = ref(false); const autoRefreshSecs = ref(10); let autoRefreshTimer: ReturnType<typeof setInterval> | null = null;
let consecutiveFailures = 0; const MAX_FAILURES = 5;
const lastRefreshTime = ref<number | null>(null); let refreshRelativeTimer: ReturnType<typeof setInterval> | null = null;
const lastRefreshLabel = ref("等待中…");
let autoRefreshing = false; // 防止 autoRefreshTick 与 fetchReplies 竞态
// 轻量签名：total + 首尾 name + 首尾状态，检测记录数量、顺序、状态变化
const dataSignature = (items: any[], totalCount: number) => {
  if (!items.length) return `${totalCount}|`;
  const first = items[0]; const last = items[items.length - 1];
  const firstStatus = first.spec?.status || ""; const lastStatus = last.spec?.status || "";
  const firstPublished = first.spec?.published || "";
  const lastPublished = last.spec?.published || "";
  return `${totalCount}|${first.metadata.name}|${firstStatus}|${firstPublished}|${last.metadata.name}|${lastStatus}|${lastPublished}`;
};
const updateRelativeTime = () => {
  if (lastRefreshTime.value == null) { lastRefreshLabel.value = "等待中…"; return; }
  const diff = Math.floor((Date.now() - lastRefreshTime.value) / 1000);
  if (diff < 5) lastRefreshLabel.value = "刚刚更新";
  else if (diff < 60) lastRefreshLabel.value = `${diff}秒前更新`;
  else lastRefreshLabel.value = `${Math.floor(diff / 60)}分钟前更新`;
};
const isPageVisible = () => !document.hidden;
const autoRefreshTick = async () => {
  // 标签页隐藏、正在加载、或存在打开的弹窗时不轮询，避免干扰用户操作
  if (!autoRefresh.value || loading.value || autoRefreshing || showDialog.value || showFalsePositiveDialog.value) return;
  autoRefreshing = true;
  // 保留滚动位置：刷新前后记录并恢复 list-area 的 scrollTop
  const listArea = document.querySelector(".list-area");
  const savedScroll = listArea ? listArea.scrollTop : 0;
  const prevSignature = dataSignature(replies.value, total.value);
  const prevFirstPage = page.value === 1;
  const prevCount = total.value;
  try {
    const params: any = { page: page.value, size: size.value }
    if (filterStatus.value) params.status = filterStatus.value; if (filterSentiment.value) params.sentiment = filterSentiment.value; if (filterKeyword.value) params.keyword = filterKeyword.value;
    const { data } = await axiosInstance.get("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies", { params })
    const newItems = data.items || []; const newTotal = data.total || 0;
    const newSignature = dataSignature(newItems, newTotal);
    consecutiveFailures = 0; // 成功，重置失败计数
    lastRefreshTime.value = Date.now();
    updateRelativeTime();
    // 仅当数据签名变化时更新，减少不必要的渲染
    if (newSignature !== prevSignature) {
      // 在首页且有新增记录时提示用户（仅在首页轮询能可靠判定"新增"）
      if (prevFirstPage && newTotal > prevCount) {
        Toast.success(`发现 ${newTotal - prevCount} 条新记录`);
      }
      replies.value = newItems; total.value = newTotal; totalPages.value = Math.ceil(newTotal / size.value);
      // 数据删除导致当前页变空时，回退到上一页
      if (replies.value.length === 0 && page.value > 1) { page.value--; }
    }
    // 恢复滚动位置
    if (listArea) listArea.scrollTop = savedScroll;
  } catch (e) {
    consecutiveFailures++;
    if (consecutiveFailures >= MAX_FAILURES) {
      autoRefresh.value = false;
      stopAutoRefresh();
      Toast.warning(`连续 ${MAX_FAILURES} 次刷新失败，已自动关闭实时刷新`);
    }
  } finally {
    autoRefreshing = false;
  }
};
const startAutoRefresh = () => {
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
  consecutiveFailures = 0;
  lastRefreshTime.value = null;
  updateRelativeTime();
  // 相对时间计时器：每秒更新 "N秒前更新" 文案
  if (refreshRelativeTimer) clearInterval(refreshRelativeTimer);
  refreshRelativeTimer = setInterval(updateRelativeTime, 1000);
  autoRefreshTimer = setInterval(() => { if (isPageVisible()) autoRefreshTick(); }, autoRefreshSecs.value * 1000);
};
const stopAutoRefresh = () => {
  if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
  if (refreshRelativeTimer) { clearInterval(refreshRelativeTimer); refreshRelativeTimer = null; }
};
const resetAutoRefreshTimer = () => { if (autoRefresh.value) startAutoRefresh(); };
const toggleAutoRefresh = () => {
  autoRefresh.value = !autoRefresh.value;
  if (autoRefresh.value) { startAutoRefresh(); Toast.success("已开启实时刷新"); }
  else { stopAutoRefresh(); Toast.success("已关闭实时刷新"); }
};

const toggleSelect = (name: string) => { selectedNames.value.has(name) ? selectedNames.value.delete(name) : selectedNames.value.add(name); selectAll.value = replies.value.length > 0 && replies.value.every(r => selectedNames.value.has(r.metadata.name)) }
const toggleSelectAll = () => { if (selectAll.value) { selectedNames.value.clear(); selectAll.value = false } else { selectedNames.value = new Set(replies.value.map(r => r.metadata.name)); selectAll.value = true } }

const fetchReplies = async () => {
  loading.value = true;
  try {
    const params: any = { page: page.value, size: size.value }
    if (filterStatus.value) params.status = filterStatus.value; if (filterSentiment.value) params.sentiment = filterSentiment.value; if (filterKeyword.value) params.keyword = filterKeyword.value;
    const { data } = await axiosInstance.get("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies", { params })
    replies.value = data.items || []; total.value = data.total || 0; totalPages.value = Math.ceil(total.value / size.value)
    // 当前页数据为空且非首页时，回退到上一页（处理删除最后一页最后一条后的越界问题）
    if (replies.value.length === 0 && page.value > 1 && totalPages.value > 0) { page.value = Math.min(page.value, totalPages.value); }
  } catch (e) { Toast.error("获取数据失败"); total.value = 0; totalPages.value = 0; } finally { loading.value = false }
}

const openConversation = async (reply: AiCommentReplyItem) => {
  showDialog.value = true; conversationLoading.value = true; conversationMessages.value = []
  try {
    const { data } = await axiosInstance.get(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/conversation/${reply.spec.commentId}`)
    conversationMessages.value = data.messages || []
  } catch (e) { Toast.error("获取对话失败") } finally { conversationLoading.value = false }
}

const handleDelete = async (name: string) => { try { await axiosInstance.delete(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}`); Toast.success("删除成功"); fetchReplies() } catch (e) { Toast.error("删除失败") } }
const handleApprove = async (name: string) => { try { await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/approve`); Toast.success("审核通过"); fetchReplies() } catch (e) { Toast.error("审核失败") } }
const handleReject = async (name: string) => { try { await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/reject`); Toast.success("已拒绝"); fetchReplies() } catch (e) { Toast.error("拒绝失败") } }
const batchApprove = async () => { if(!selectedNames.value.size||batchLoading.value) return; batchLoading.value=true; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-approve", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } finally { batchLoading.value=false } }
const batchReject = async () => { if(!selectedNames.value.size||batchLoading.value) return; batchLoading.value=true; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-reject", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } finally { batchLoading.value=false } }
const batchDelete = async () => { if(!selectedNames.value.size||batchLoading.value) return; batchLoading.value=true; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-delete", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } finally { batchLoading.value=false } }

const getStatusLabel = (s: string) => { const m:any = { PASS: '通过', FAIL: '失败', PENDING: '待审', REJECTED: '拒绝', FILTERED: '已拦截', FALSE_POSITIVE: '误报通过' }; return m[s] || s }
const getSentimentLabel = (s: string) => { const m:any = { VERY_POSITIVE: '极好', POSITIVE: '正面', NEUTRAL: '中性', NEGATIVE: '负面', VERY_NEGATIVE: '极差' }; return m[s] || s }
const formatDate = (ts: string) => ts ? new Date(ts).toLocaleString("zh-CN") : ""
const getPostUrl = (slug: string, postKind?: string) => {
  if (postKind === "Moment") {
    return `${window.location.origin}/moments/${slug}`
  }
  return `${window.location.origin}/archives/${slug}`
}
const stripHtml = (html: string) => html ? html.replace(/<[^>]+>/g, "").replace(/\n+/g, " ").trim() : ""

const truncateQuote = (content: string, length = 35) => {
  if (!content) return ""
  let plain = stripHtml(content)
  plain = plain.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, '').trim()
  return plain.length > length ? plain.substring(0, length) + "..." : plain
}

const renderContent = (content: string) => {
  if (!content) return "<span style='opacity:0.5'>(空)</span>"
  // XSS 防护：移除所有 on* 事件处理器、javascript: 协议、script/style/iframe/object/embed 标签
  let parsed = content
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
    .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, "")
    .replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, "")
    .replace(/<object[^>]*>[\s\S]*?<\/object>/gi, "")
    .replace(/<embed[^>]*>/gi, "")
    .replace(/\son\w+\s*=\s*"[^"]*"/gi, "")
    .replace(/\son\w+\s*=\s*'[^']*'/gi, "")
    .replace(/\son\w+\s*=\s*[^\s>]+/gi, "")
    .replace(/(href|src)\s*=\s*["']?\s*javascript:/gi, "$1=\"\"")
    .replace(/(href|src)\s*=\s*["']?\s*data:text\/html/gi, "$1=\"\"")
  parsed = parsed.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, "")
  return parsed.replace(/\n/g, "<br/>")
}

const resetFilters = () => { filterStatus.value = ""; filterSentiment.value = ""; filterKeyword.value = ""; page.value = 1; fetchReplies(); resetAutoRefreshTimer(); }

const openFalsePositiveDialog = (reply: AiCommentReplyItem) => { falsePositiveTarget.value = reply; showFalsePositiveDialog.value = true }
const closeFalsePositiveDialog = () => { showFalsePositiveDialog.value = false; falsePositiveTarget.value = null }
const handleFalsePositive = async (action: string) => {
  if (!falsePositiveTarget.value) return
  fpLoading.value = true
  try {
    await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${falsePositiveTarget.value.metadata.name}/false-positive`, { action })
    Toast.success(action === "aiReply" ? "已标记为误报，AI回复正在后台生成" : "已标记为误报并通过")
    closeFalsePositiveDialog()
    fetchReplies()
  } catch (e: any) {
    Toast.error(e?.response?.data?.message || "操作失败")
  } finally { fpLoading.value = false }
}
const handleTriggerAiReply = async (reply: AiCommentReplyItem) => {
  if (triggerAiLoadingName.value) return
  triggerAiLoadingName.value = reply.metadata.name
  try {
    await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${reply.metadata.name}/false-positive`, { action: "aiReply" })
    Toast.success("AI回复正在后台生成")
    fetchReplies()
  } catch (e: any) {
    Toast.error(e?.response?.data?.message || "触发失败")
  } finally { triggerAiLoadingName.value = null }
}
// 状态/情感筛选立即触发；关键词输入防抖 300ms 避免每次按键都请求
watch([filterStatus, filterSentiment], () => { page.value = 1; fetchReplies(); resetAutoRefreshTimer(); })
let keywordDebounceTimer: ReturnType<typeof setTimeout> | null = null
watch(filterKeyword, () => {
  if (keywordDebounceTimer) clearTimeout(keywordDebounceTimer)
  keywordDebounceTimer = setTimeout(() => { page.value = 1; fetchReplies(); resetAutoRefreshTimer(); }, 300)
})
watch(page, () => { selectedNames.value.clear(); selectAll.value = false; fetchReplies(); resetAutoRefreshTimer(); })
// 刷新间隔变化时重启计时器
watch(autoRefreshSecs, () => { if (autoRefresh.value) startAutoRefresh(); })
// 标签页重新可见时，若开启了实时刷新则立即拉取一次，保证回到页面时数据是最新的
const handleVisibilityChange = () => { if (!document.hidden && autoRefresh.value) autoRefreshTick(); };
onMounted(() => { fetchReplies(); document.addEventListener("visibilitychange", handleVisibilityChange); })
onUnmounted(() => {
  if (keywordDebounceTimer) clearTimeout(keywordDebounceTimer);
  stopAutoRefresh();
  document.removeEventListener("visibilitychange", handleVisibilityChange);
})
</script>

<style scoped>
/* 全局基础布局 */
.logs-container { padding-bottom: 20px; }
.header-icon { margin-right: 8px; align-self: center; }

/* 响应式工具栏 */
.toolbar { display: flex; flex-direction: column; gap: 12px; margin: 16px; align-items: stretch; }
@media (min-width: 768px) { .toolbar { flex-direction: row; align-items: center; } }
.batch-toolbar { background: #eff6ff; padding: 12px 16px; border-radius: 8px; border: 1px solid #bfdbfe; }
.batch-text { font-size: 14px; color: #1d4ed8; font-weight: bold; }
.batch-actions { display: flex; flex-wrap: wrap; gap: 8px; width: 100%; }
@media (min-width: 768px) { .batch-actions { width: auto; margin-left: auto; } }
.btn-batch { padding: 6px 12px; border-radius: 6px; border: none; font-size: 12px; cursor: pointer; color: #fff; white-space: nowrap; }
.btn-pass { background: #16a34a; } .btn-reject { background: #f97316; } .btn-delete { background: #dc2626; }
.btn-cancel { background: transparent; color: #6b7280; border: 1px solid #d1d5db; }
.filter-select, .filter-input { width: 100%; padding: 8px 12px; border: 1px solid #e5e7eb; border-radius: 6px; font-size: 14px; outline: none; }
@media (min-width: 768px) { .filter-select { width: auto; min-width: 120px; } .filter-input { flex: 1; } }
.btn-reset { padding: 8px 16px; border: 1px solid #e5e7eb; border-radius: 6px; background: #f9fafb; cursor: pointer; white-space: nowrap; width: 100%; }
@media (min-width: 768px) { .btn-reset { width: auto; } }
.autorefresh-group { display: flex; align-items: center; gap: 6px; width: 100%; flex-wrap: wrap; }
@media (min-width: 768px) { .autorefresh-group { width: auto; flex-wrap: nowrap; } }
.btn-autorefresh { padding: 8px 16px; border: 1px solid #e5e7eb; border-radius: 6px; background: #f9fafb; cursor: pointer; white-space: nowrap; font-size: 13px; color: #6b7280; display: flex; align-items: center; gap: 6px; transition: all 0.15s; }
.btn-autorefresh:hover { background: #f3f4f6; }
.btn-autorefresh.is-active { background: #dcfce7; border-color: #86efac; color: #15803d; }
.autorefresh-dot { width: 8px; height: 8px; border-radius: 50%; background: #16a34a; animation: autorefresh-pulse 1.5s ease-in-out infinite; }
@keyframes autorefresh-pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.5; transform: scale(0.85); } }
.autorefresh-interval { padding: 6px 8px; border: 1px solid #e5e7eb; border-radius: 6px; background: #f9fafb; font-size: 13px; color: #6b7280; cursor: pointer; }
.autorefresh-interval:focus { outline: none; border-color: #86efac; }
.autorefresh-status { font-size: 12px; color: #9ca3af; white-space: nowrap; min-width: 70px; }

/* 列表区 */
.list-area { margin: 16px; }
.empty-state { text-align: center; padding: 60px 0; color: #9ca3af; font-size: 14px; }
.reply-list { display: flex; flex-direction: column; gap: 16px; }
.select-all-wrap { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #6b7280; padding: 0 4px; }
.reply-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 2px rgba(0,0,0,0.02); }
.card-main { display: flex; gap: 12px; padding: 16px; align-items: flex-start; }
.card-main input { margin-top: 4px; }
.card-content { flex: 1; min-width: 0; }
.card-header { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
@media (min-width: 640px) { .card-header { flex-direction: row; justify-content: space-between; align-items: center; } }
.tags-wrap { display: flex; gap: 6px; flex-wrap: wrap; }
.card-time { font-size: 12px; color: #9ca3af; }
.card-text { font-size: 14px; color: #374151; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.card-filter-reason { display: flex; align-items: flex-start; gap: 6px; margin-top: 8px; padding: 6px 10px; background: #fef3c7; border: 1px solid #fde68a; border-radius: 6px; font-size: 12px; color: #92400e; }
.filter-icon { width: 14px; height: 14px; flex-shrink: 0; margin-top: 1px; }
.filter-category { flex-shrink: 0; padding: 1px 6px; background: #b45309; color: #fff; border-radius: 3px; font-weight: 600; font-size: 11px; line-height: 1.5; }
.filter-detail { flex: 1; line-height: 1.5; }
.btn-false-positive { flex-shrink: 0; margin-left: auto; padding: 2px 8px; border: 1px solid #b45309; border-radius: 4px; background: transparent; color: #b45309; font-size: 11px; cursor: pointer; white-space: nowrap; transition: all 0.15s; }
.btn-false-positive:hover { background: #b45309; color: #fff; }
.fp-icon-ok { color: #16a34a; }
.btn-trigger-ai { flex-shrink: 0; margin-left: auto; padding: 2px 8px; border: 1px solid #2563eb; border-radius: 4px; background: transparent; color: #2563eb; font-size: 11px; cursor: pointer; white-space: nowrap; transition: all 0.15s; display: inline-flex; align-items: center; gap: 4px; }
.btn-trigger-ai:hover:not(:disabled) { background: #2563eb; color: #fff; }
.btn-trigger-ai:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-trigger-ai .fp-spinner { width: 11px; height: 11px; border-color: rgba(37,99,235,0.3); border-top-color: #2563eb; }
.card-footer { display: flex; flex-direction: column; gap: 12px; padding: 12px 16px; background: #f9fafb; border-top: 1px solid #f3f4f6; }
@media (min-width: 640px) { .card-footer { flex-direction: row; justify-content: space-between; align-items: center; } }
.footer-info { font-size: 12px; color: #6b7280; display: flex; flex-wrap: wrap; gap: 12px; }
.post-link { color: #3b82f6; text-decoration: none; } .post-link:hover { text-decoration: underline; }
.retry-text { color: #f59e0b; }
.footer-actions { display: flex; flex-wrap: wrap; gap: 8px; width: 100%; justify-content: flex-end; }
@media (min-width: 640px) { .footer-actions { width: auto; } }
.action-btn { padding: 4px 10px; border-radius: 4px; font-size: 12px; border: none; cursor: pointer; white-space: nowrap; }
.action-btn.pass { background: #dcfce7; color: #16a34a; }
.action-btn.reject { background: #fee2e2; color: #dc2626; }
.action-btn.view { background: #dbeafe; color: #2563eb; }
.action-btn.delete { background: #e5e7eb; color: #4b5563; }

/* 标签体系 */
.custom-tag { padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: bold; }
.tag-PASS { background: #dcfce7; color: #15803d; } .tag-FAIL { background: #fee2e2; color: #b91c1c; } .tag-PENDING { background: #fef9c3; color: #a16207; } .tag-REJECTED { background: #ffedd5; color: #c2410c; } .tag-FILTERED { background: #f1f5f9; color: #b45309; border: 1px solid #fde68a; } .tag-FALSE_POSITIVE { background: #dbeafe; color: #1d4ed8; border: 1px solid #93c5fd; }
.tag-published { background: #dbeafe; color: #1d4ed8; } .tag-draft { background: #f3f4f6; color: #4b5563; }
.tag-conv { background: #f3e8ff; color: #7e22ce; }
.tag-VERY_POSITIVE { background: #dcfce7; color: #14532d; } .tag-POSITIVE { background: #ecfdf5; color: #15803d; } .tag-NEUTRAL { background: #f3f4f6; color: #4b5563; } .tag-NEGATIVE { background: #ffe4e6; color: #e11d48; } .tag-VERY_NEGATIVE { background: #fee2e2; color: #991b1b; }

/* 对话弹窗与响应式气泡 */
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 9999; backdrop-filter: blur(2px); padding: 16px; box-sizing: border-box; }
.dialog-box { width: 100%; max-width: 600px; background: #fff; border-radius: 16px; display: flex; flex-direction: column; max-height: 90vh; box-shadow: 0 10px 25px rgba(0,0,0,0.15); }
.dialog-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #f3f4f6; }
.dialog-header h3 { margin: 0; font-size: 16px; font-weight: bold; }
.close-btn { background: none; border: none; cursor: pointer; color: #9ca3af; padding: 0; display: flex; align-items: center; justify-content: center; }
.close-btn:hover { color: #4b5563; }
.dialog-body { padding: 20px; overflow-y: auto; background: #f8fafc; flex: 1; border-bottom-left-radius: 16px; border-bottom-right-radius: 16px; }

.chat-container { display: flex; flex-direction: column; gap: 20px; }
.chat-row { display: flex; width: 100%; }
.row-ai { justify-content: flex-start; }
.row-user { justify-content: flex-end; }
.chat-message { max-width: 90%; display: flex; flex-direction: column; }
@media (min-width: 640px) { .chat-message { max-width: 75%; } }
.chat-owner { font-size: 12px; margin-bottom: 6px; font-weight: bold; }
.row-ai .chat-owner { color: #2563eb; text-align: left; margin-left: 4px; }
.row-user .chat-owner { color: #6b7280; text-align: right; margin-right: 4px; }
.chat-time { font-size: 11px; color: #9ca3af; margin-top: 6px; }
.row-ai .chat-time { text-align: left; margin-left: 4px; }
.row-user .chat-time { text-align: right; margin-right: 4px; }

/* 气泡样式 */
.chat-bubble { padding: 10px 14px; border-radius: 12px; font-size: 14px; line-height: 1.6; word-wrap: break-word; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.bubble-ai { background: #ffffff; color: #1f2937; border: 1px solid #e2e8f0; border-top-left-radius: 2px; }
.bubble-user { background: #2563eb; color: #ffffff; border-top-right-radius: 2px; }

/* 全新精美引用框 (无左黑条，微信风格) */
.quote-box { margin-bottom: 8px; padding: 8px 10px; border-radius: 8px; font-size: 12px; display: block; width: 100%; box-sizing: border-box; }
.bubble-ai .quote-box { background: rgba(0,0,0,0.04); color: #6b7280; }
.bubble-user .quote-box { background: rgba(255,255,255,0.15); color: #d1d5db; }
.quote-header { display: flex; align-items: center; gap: 4px; margin-bottom: 2px; }
.quote-icon { width: 12px; height: 12px; opacity: 0.7; }
.quote-owner { font-weight: 600; font-size: 11px; }
.bubble-ai .quote-owner { color: #374151; }
.bubble-user .quote-owner { color: #ffffff; }
.quote-content { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.4; }

.pagination { display: flex; flex-direction: column; gap: 12px; align-items: center; margin-top: 20px; font-size: 14px; color: #6b7280; }
@media (min-width: 640px) { .pagination { flex-direction: row; justify-content: space-between; } }
.pagination-btns { display: flex; gap: 8px; }

/* 误报反馈弹窗 */
.fp-dialog { max-width: 440px; }
.fp-dialog-body { padding: 24px; }
.fp-desc { margin: 0 0 20px; font-size: 14px; color: #4b5563; line-height: 1.6; }
.fp-actions { display: flex; flex-direction: column; gap: 10px; }
.fp-btn { padding: 10px 16px; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; border: none; transition: all 0.15s; display: flex; align-items: center; justify-content: center; gap: 6px; }
.fp-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.fp-btn-primary { background: #2563eb; color: #fff; }
.fp-btn-primary:hover:not(:disabled) { background: #1d4ed8; }
.fp-btn-secondary { background: #f3f4f6; color: #374151; border: 1px solid #d1d5db; }
.fp-btn-secondary:hover:not(:disabled) { background: #e5e7eb; }
.fp-btn-ghost { background: transparent; color: #9ca3af; }
.fp-btn-ghost:hover:not(:disabled) { color: #6b7280; background: #f9fafb; }
.fp-spinner { width: 14px; height: 14px; border: 2px solid rgba(255,255,255,0.3); border-top-color: #fff; border-radius: 50%; animation: fp-spin 0.6s linear infinite; }
@keyframes fp-spin { to { transform: rotate(360deg); } }
</style>
