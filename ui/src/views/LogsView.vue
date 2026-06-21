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
      <button class="btn-batch btn-pass" @click="batchApprove">批量通过</button>
      <button class="btn-batch btn-reject" @click="batchReject">批量拒绝</button>
      <button class="btn-batch btn-delete" @click="batchDelete">批量删除</button>
      <button class="btn-batch btn-cancel" @click="selectedNames.clear(); selectAll = false">取消选择</button>
    </div>

    <!-- 过滤工具栏 -->
    <div class="toolbar filter-toolbar">
      <select v-model="filterStatus" class="filter-select">
        <option value="">全部状态</option>
        <option value="PASS">通过</option>
        <option value="FAIL">失败</option>
        <option value="PENDING">待审核</option>
        <option value="REJECTED">已拒绝</option>
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
            </div>
          </div>
          <div class="card-footer">
            <div class="footer-info">
              <span>评分: <strong>{{ reply.spec.score }}</strong></span>
              <span v-if="reply.spec.postSlug">
                关联: <a :href="getPostUrl(reply.spec.postSlug)" target="_blank">{{ reply.spec.postSlug }}</a>
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
        <div>
          <VButton size="sm" :disabled="page <= 1" @click="page--">上一页</VButton>
          <VButton size="sm" :disabled="page >= totalPages" @click="page++">下一页</VButton>
        </div>
      </div>
    </div>

    <!-- 完整对话弹窗 -->
    <teleport to="body">
      <div v-if="showDialog" class="dialog-overlay" @click.self="showDialog = false">
        <div class="dialog-box">
          <div class="dialog-header">
            <h3>对话上下文</h3>
            <button class="close-btn" @click="showDialog = false">×</button>
          </div>
          <div class="dialog-body">
            <VLoading v-if="conversationLoading" />
            <div v-else-if="conversationMessages.length === 0" class="empty-state">暂无内容</div>
            <div v-else class="chat-container">
              <div v-for="(msg, idx) in conversationMessages" :key="idx" class="chat-row" :class="msg.isAi ? 'row-ai' : 'row-user'">
                <div class="chat-message">
                  <div class="chat-owner">{{ msg.owner }}</div>
                  <div class="chat-bubble" :class="msg.isAi ? 'bubble-ai' : 'bubble-user'">
                    
                    <!-- 原生 CSS 渲染的引用框 -->
                    <div v-if="msg.quoteOwner && msg.quoteContent" class="quote-box">
                      <span class="quote-owner">@{{ msg.quoteOwner }}:</span>
                      <span class="quote-content">{{ truncateQuote(msg.quoteContent) }}</span>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from "vue"
import { axiosInstance } from "@halo-dev/api-client"
import { VPageHeader, VButton, VLoading, Toast } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

interface AiCommentReplyItem { metadata: { name: string; creationTimestamp: string }; spec: any }
interface ConversationMessage { type: string; owner: string; content: string; time: string; isAi: boolean; quoteOwner?: string; quoteContent?: string }

const replies = ref<AiCommentReplyItem[]>([]); const loading = ref(false); const page = ref(1); const size = ref(20); const total = ref(0); const totalPages = ref(0);
const selectedNames = ref<Set<string>>(new Set()); const selectAll = ref(false);
const filterStatus = ref(""); const filterSentiment = ref(""); const filterKeyword = ref("");
const showDialog = ref(false); const conversationLoading = ref(false); const conversationMessages = ref<ConversationMessage[]>([]);

const toggleSelect = (name: string) => { selectedNames.value.has(name) ? selectedNames.value.delete(name) : selectedNames.value.add(name); selectAll.value = replies.value.length > 0 && replies.value.every(r => selectedNames.value.has(r.metadata.name)) }
const toggleSelectAll = () => { if (selectAll.value) { selectedNames.value.clear(); selectAll.value = false } else { selectedNames.value = new Set(replies.value.map(r => r.metadata.name)); selectAll.value = true } }

const fetchReplies = async () => {
  loading.value = true;
  try {
    const params: any = { page: page.value, size: size.value }
    if (filterStatus.value) params.status = filterStatus.value; if (filterSentiment.value) params.sentiment = filterSentiment.value; if (filterKeyword.value) params.keyword = filterKeyword.value;
    const { data } = await axiosInstance.get("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies", { params })
    replies.value = data.items || []; total.value = data.total || 0; totalPages.value = Math.ceil(total.value / size.value)
  } catch (e) { Toast.error("获取数据失败") } finally { loading.value = false }
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
const batchApprove = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-approve", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } }
const batchReject = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-reject", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } }
const batchDelete = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-delete", { names: Array.from(selectedNames.value) }); Toast.success("成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("失败") } }

const getStatusLabel = (s: string) => { const m:any = { PASS: '通过', FAIL: '失败', PENDING: '待审', REJECTED: '拒绝' }; return m[s] || s }
const getSentimentLabel = (s: string) => { const m:any = { VERY_POSITIVE: '极好', POSITIVE: '正面', NEUTRAL: '中性', NEGATIVE: '负面', VERY_NEGATIVE: '极差' }; return m[s] || s }
const formatDate = (ts: string) => ts ? new Date(ts).toLocaleString("zh-CN") : ""
const getPostUrl = (slug: string) => `${window.location.origin}/archives/${slug}`
const stripHtml = (html: string) => html ? html.replace(/<[^>]+>/g, "").replace(/\n+/g, " ").trim() : ""

// 彻底清除冗余 Markdown 的正则替换
const truncateQuote = (content: string, length = 35) => {
  if (!content) return ""
  let plain = stripHtml(content)
  plain = plain.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, '').trim()
  return plain.length > length ? plain.substring(0, length) + "..." : plain
}

const renderContent = (content: string) => {
  if (!content) return "<span style='opacity:0.5'>(空)</span>"
  let parsed = content.replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "").replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, "")
  parsed = parsed.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, "") // 清理脏数据
  return parsed.replace(/\n/g, "<br/>")
}

const resetFilters = () => { filterStatus.value = ""; filterSentiment.value = ""; filterKeyword.value = ""; page.value = 1; fetchReplies() }
watch([filterStatus, filterSentiment, filterKeyword], () => { page.value = 1; fetchReplies() })
watch(page, () => { selectedNames.value.clear(); selectAll.value = false; fetchReplies() })
onMounted(fetchReplies)
</script>

<style scoped>
/* 全局基础布局 */
.logs-container { padding-bottom: 20px; }
.header-icon { margin-right: 8px; align-self: center; }

/* 工具栏与按钮 */
.toolbar { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px; align-items: center; }
.batch-toolbar { background: #eff6ff; padding: 12px 16px; border-radius: 8px; border: 1px solid #bfdbfe; }
.batch-text { font-size: 14px; color: #1d4ed8; font-weight: bold; }
.btn-batch { padding: 6px 12px; border-radius: 6px; border: none; font-size: 12px; cursor: pointer; color: #fff; }
.btn-pass { background: #16a34a; } .btn-reject { background: #f97316; } .btn-delete { background: #dc2626; }
.btn-cancel { background: transparent; color: #6b7280; margin-left: auto; }
.filter-select, .filter-input { padding: 8px 12px; border: 1px solid #e5e7eb; border-radius: 6px; font-size: 14px; outline: none; }
.btn-reset { padding: 8px 16px; border: 1px solid #e5e7eb; border-radius: 6px; background: #f9fafb; cursor: pointer; }

/* 列表区 */
.list-area { margin: 16px; }
.empty-state { text-align: center; padding: 60px 0; color: #9ca3af; }
.reply-list { display: flex; flex-direction: column; gap: 16px; }
.select-all-wrap { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #6b7280; padding: 0 4px; }
.reply-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden; }
.card-main { display: flex; gap: 12px; padding: 16px; }
.card-content { flex: 1; min-width: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.tags-wrap { display: flex; gap: 6px; flex-wrap: wrap; }
.card-time { font-size: 12px; color: #9ca3af; }
.card-text { font-size: 14px; color: #374151; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.card-footer { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: #f9fafb; border-top: 1px solid #f3f4f6; }
.footer-info { font-size: 12px; color: #6b7280; display: flex; gap: 16px; }
.footer-actions { display: flex; gap: 8px; }
.action-btn { padding: 4px 8px; border-radius: 4px; font-size: 12px; border: none; cursor: pointer; }
.action-btn.pass { background: #dcfce7; color: #16a34a; }
.action-btn.reject { background: #fee2e2; color: #dc2626; }
.action-btn.view { background: #dbeafe; color: #2563eb; }
.action-btn.delete { background: #e5e7eb; color: #4b5563; }

/* 标签配色体系 (纯CSS，抛弃Tailwind限制) */
.custom-tag { padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: bold; }
.tag-PASS { background: #dcfce7; color: #15803d; }
.tag-FAIL { background: #fee2e2; color: #b91c1c; }
.tag-PENDING { background: #fef9c3; color: #a16207; }
.tag-REJECTED { background: #ffedd5; color: #c2410c; }
.tag-published { background: #dbeafe; color: #1d4ed8; }
.tag-draft { background: #f3f4f6; color: #4b5563; }
.tag-conv { background: #f3e8ff; color: #7e22ce; }
.tag-VERY_POSITIVE { background: #dcfce7; color: #14532d; }
.tag-POSITIVE { background: #ecfdf5; color: #15803d; }
.tag-NEGATIVE { background: #ffe4e6; color: #e11d48; }
.tag-VERY_NEGATIVE { background: #fee2e2; color: #991b1b; }

/* 对话弹窗与聊天气泡 */
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.dialog-box { width: 90%; max-width: 600px; background: #fff; border-radius: 16px; display: flex; flex-direction: column; max-height: 85vh; }
.dialog-header { display: flex; justify-content: space-between; padding: 16px 24px; border-bottom: 1px solid #f3f4f6; }
.dialog-header h3 { margin: 0; font-size: 18px; }
.close-btn { background: none; border: none; font-size: 24px; cursor: pointer; color: #9ca3af; }
.dialog-body { padding: 24px; overflow-y: auto; background: #f9fafb; flex: 1; border-bottom-left-radius: 16px; border-bottom-right-radius: 16px; }

.chat-container { display: flex; flex-direction: column; gap: 20px; }
.chat-row { display: flex; width: 100%; }
.row-ai { justify-content: flex-start; }
.row-user { justify-content: flex-end; }
.chat-message { max-width: 80%; display: flex; flex-direction: column; }
.chat-owner { font-size: 12px; margin-bottom: 6px; font-weight: bold; }
.row-ai .chat-owner { color: #2563eb; text-align: left; }
.row-user .chat-owner { color: #6b7280; text-align: right; }
.chat-time { font-size: 11px; color: #9ca3af; margin-top: 6px; }
.row-user .chat-time { text-align: right; }

/* 核心：独立的气泡和引用框 CSS */
.chat-bubble { padding: 12px 16px; border-radius: 12px; font-size: 14px; line-height: 1.6; word-wrap: break-word; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.bubble-ai { background: #ffffff; color: #1f2937; border: 1px solid #e0e7ff; border-top-left-radius: 2px; }
.bubble-user { background: #2563eb; color: #ffffff; border-top-right-radius: 2px; }

/* 绝对可靠的引用框样式 */
.quote-box { margin-bottom: 10px; padding: 8px 12px; border-radius: 6px; font-size: 12px; border-left: 3px solid; }
.quote-owner { font-weight: bold; margin-right: 6px; }
.bubble-ai .quote-box { background: #f3f4f6; border-left-color: #9ca3af; color: #4b5563; }
.bubble-user .quote-box { background: rgba(0,0,0,0.15); border-left-color: rgba(255,255,255,0.4); color: #d1d5db; }

.pagination { display: flex; justify-content: space-between; align-items: center; margin-top: 20px; font-size: 14px; color: #6b7280; }
</style>
