<template>
  <div class="comment-ai-autopilot-logs">
    <VPageHeader title="AI回复日志">
      <template #icon>
        <IconPlug class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton @click="$router.push({ name: 'CommentAiAutopilot' })"> 返回概览 </VButton>
      </template>
    </VPageHeader>

    <!-- 批量操作工具栏 -->
    <div v-if="selectedNames.size > 0" class="m-4 mb-0 flex flex-wrap items-center gap-3 bg-blue-50 border border-blue-200 rounded-lg px-4 py-3">
      <span class="text-sm text-blue-700 font-medium">已选择 {{ selectedNames.size }} 项</span>
      <button class="px-3 py-1.5 text-xs font-medium rounded-md bg-green-600 text-white hover:bg-green-700 transition" @click="batchApprove">批量通过</button>
      <button class="px-3 py-1.5 text-xs font-medium rounded-md bg-orange-500 text-white hover:bg-orange-600 transition" @click="batchReject">批量拒绝</button>
      <button class="px-3 py-1.5 text-xs font-medium rounded-md bg-red-600 text-white hover:bg-red-700 transition" @click="batchDelete">批量删除</button>
      <button class="px-3 py-1.5 text-xs font-medium rounded-md text-gray-500 hover:bg-gray-200 transition ml-auto" @click="selectedNames.clear(); selectAll = false">取消选择</button>
    </div>

    <!-- 过滤工具栏 -->
    <div class="m-4 mb-0 flex flex-col md:flex-row items-center gap-3">
      <select v-model="filterStatus" class="w-full md:w-auto px-3 py-2 text-sm border border-gray-200 rounded-lg bg-gray-50 focus:bg-white focus:border-blue-500 outline-none transition">
        <option value="">全部状态</option>
        <option value="PASS">通过</option>
        <option value="FAIL">失败</option>
        <option value="PENDING">待审核</option>
        <option value="REJECTED">已拒绝</option>
      </select>
      <select v-model="filterSentiment" class="w-full md:w-auto px-3 py-2 text-sm border border-gray-200 rounded-lg bg-gray-50 focus:bg-white focus:border-blue-500 outline-none transition">
        <option value="">全部情感</option>
        <option value="VERY_POSITIVE">非常正面</option>
        <option value="POSITIVE">正面</option>
        <option value="NEUTRAL">中性</option>
        <option value="NEGATIVE">负面</option>
        <option value="VERY_NEGATIVE">非常负面</option>
      </select>
      <div class="relative w-full md:w-64">
        <svg class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input v-model="filterKeyword" type="text" placeholder="搜索回复内容..." class="w-full pl-9 pr-3 py-2 text-sm border border-gray-200 rounded-lg bg-gray-50 focus:bg-white focus:border-blue-500 outline-none transition" />
      </div>
      <button class="w-full md:w-auto flex items-center justify-center gap-1 px-4 py-2 text-sm text-gray-600 bg-gray-100 hover:bg-gray-200 border border-gray-200 rounded-lg transition" @click="resetFilters">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
        重置
      </button>
    </div>

    <!-- 列表区 -->
    <div class="m-4">
      <VLoading v-if="loading" />

      <div v-else-if="replies.length === 0" class="flex flex-col items-center justify-center py-16 text-gray-400">
        <svg class="w-12 h-12 mb-3 opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg>
        <span class="text-sm">暂无记录</span>
      </div>

      <div v-else class="space-y-4">
        <div class="flex items-center gap-2 px-1">
          <input type="checkbox" :checked="selectAll" @change="toggleSelectAll" class="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
          <span class="text-xs text-gray-500">全选本页</span>
        </div>
        
        <div v-for="reply in replies" :key="reply.metadata.name" class="bg-white rounded-xl border border-gray-200 overflow-hidden hover:shadow-md transition duration-200">
          <div class="p-4 flex items-start gap-3">
            <input type="checkbox" :checked="selectedNames.has(reply.metadata.name)" @change="toggleSelect(reply.metadata.name)" class="mt-1 w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="flex flex-wrap items-center justify-between gap-2 mb-2">
                <div class="flex flex-wrap items-center gap-1.5">
                  <span class="px-2 py-0.5 rounded text-xs font-medium" :class="getStatusClass(reply.spec.status)">{{ getStatusLabel(reply.spec.status) }}</span>
                  <span class="px-2 py-0.5 rounded text-xs font-medium" :class="reply.spec.published ? 'bg-blue-50 text-blue-700' : 'bg-gray-100 text-gray-500'">{{ reply.spec.published ? '已发布' : '未发布' }}</span>
                  <span v-if="reply.spec.isAiConversation" class="px-2 py-0.5 rounded text-xs font-medium bg-purple-50 text-purple-700">对话</span>
                  <span v-if="reply.spec.sentiment" class="px-2 py-0.5 rounded text-xs font-medium" :class="getSentimentClass(reply.spec.sentiment)">{{ getSentimentLabel(reply.spec.sentiment) }}</span>
                </div>
                <span class="text-xs text-gray-400 shrink-0">{{ formatDate(reply.metadata.creationTimestamp) }}</span>
              </div>
              <div class="text-sm text-gray-700 leading-relaxed break-words line-clamp-3">
                {{ stripHtml(reply.spec.reply) || '(空)' }}
              </div>
            </div>
          </div>
          
          <div class="px-4 py-3 bg-gray-50 border-t border-gray-100 flex flex-wrap items-center justify-between gap-3">
            <div class="flex flex-wrap items-center gap-4 text-xs text-gray-500">
              <span class="flex items-center gap-1">
                评分 <span :class="getScoreTextClass(reply.spec.score)" class="font-bold">{{ reply.spec.score }}</span>
                <span class="px-1.5 py-0.5 rounded ml-1" :class="getScoreBgClass(reply.spec.score)">{{ getScoreLabel(reply.spec.score) }}</span>
              </span>
              <span v-if="reply.spec.postSlug" class="flex items-center gap-1">
                {{ reply.spec.postKind === 'SinglePage' ? '页面' : '文章' }}
                <a :href="reply.spec.postKind === 'SinglePage' ? getPageUrl(reply.spec.postSlug) : getPostUrl(reply.spec.postSlug)" target="_blank" class="text-blue-500 hover:underline">{{ reply.spec.postSlug }}</a>
              </span>
              <span v-if="reply.spec.retryCount > 0" class="text-orange-500">重试 {{ reply.spec.retryCount }} 次</span>
            </div>
            <div class="flex items-center gap-2">
              <template v-if="reply.spec.status === 'PASS' && !reply.spec.published">
                <button class="px-2 py-1 text-xs text-green-600 bg-green-50 hover:bg-green-100 rounded transition" @click="handleApprove(reply.metadata.name)">审核通过</button>
                <button class="px-2 py-1 text-xs text-red-600 bg-red-50 hover:bg-red-100 rounded transition" @click="handleReject(reply.metadata.name)">拒绝</button>
              </template>
              <button class="px-2 py-1 flex items-center gap-1 text-xs text-blue-600 bg-blue-50 hover:bg-blue-100 rounded transition" @click="openConversation(reply)">查看对话</button>
              <button class="px-2 py-1 text-xs text-gray-500 bg-gray-200 hover:bg-red-50 hover:text-red-600 rounded transition" @click="handleDelete(reply.metadata.name)">删除</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="totalPages > 1" class="flex items-center justify-between mt-6 px-2">
        <span class="text-sm text-gray-500">共 {{ total }} 条</span>
        <div class="flex gap-2">
          <VButton size="sm" :disabled="page <= 1" @click="page--">上一页</VButton>
          <VButton size="sm" :disabled="page >= totalPages" @click="page++">下一页</VButton>
        </div>
      </div>
    </div>

    <!-- 完整对话弹窗 -->
    <teleport to="body">
      <div v-if="showDialog" class="fixed inset-0 z-[9999] flex items-center justify-center p-4">
        <div class="absolute inset-0 bg-gray-900/50 backdrop-blur-sm" @click="showDialog = false"></div>
        <div class="relative bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[85vh] flex flex-col overflow-hidden">
          <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100">
            <h3 class="text-lg font-bold text-gray-800">对话上下文</h3>
            <button class="p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-700 rounded-lg transition" @click="showDialog = false">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
          <div class="flex-1 overflow-y-auto p-6 space-y-5 bg-gray-50/50">
            <VLoading v-if="conversationLoading" />
            <div v-else-if="conversationMessages.length === 0" class="text-center text-gray-400 py-10 text-sm">暂无内容</div>
            <div v-else>
              <div v-for="(msg, idx) in conversationMessages" :key="idx" class="flex mb-5" :class="msg.isAi ? 'justify-start' : 'justify-end'">
                <div class="max-w-[85%] md:max-w-[75%]">
                  <div class="text-xs mb-1.5 px-1 font-medium" :class="msg.isAi ? 'text-blue-600' : 'text-gray-500 text-right'">
                    {{ msg.owner }}
                  </div>
                  <div class="rounded-2xl px-4 py-3 text-sm leading-relaxed shadow-sm break-words" :class="msg.isAi ? 'bg-white border border-blue-100 text-gray-800 rounded-tl-sm' : 'bg-blue-600 text-white rounded-tr-sm'">
                    <!-- 引用摘要模块（如果该条消息有回复对象，则渲染） -->
                    <div v-if="msg.quoteOwner && msg.quoteContent"
                         class="mb-2.5 px-3 py-2 rounded-lg border-l-[3px] text-xs"
                         :class="msg.isAi ? 'bg-gray-50 border-gray-300 text-gray-500' : 'bg-black/10 border-white/40 text-blue-100'">
                      <span class="font-semibold" :class="msg.isAi ? 'text-gray-700' : 'text-white'">@{{ msg.quoteOwner }}</span>：
                      {{ truncateQuote(msg.quoteContent, 40) }}
                    </div>
                    <!-- 实际发言内容 -->
                    <div v-html="renderContent(msg.content)"></div>
                  </div>
                  <div class="text-[11px] text-gray-400 mt-1.5 px-1" :class="msg.isAi ? 'text-left' : 'text-right'">
                    {{ formatDate(msg.time) }}
                  </div>
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

interface AiCommentReplyItem {
  metadata: { name: string; creationTimestamp: string }
  spec: {
    commentId: string; postId: string; postSlug: string; postKind: string
    reply: string; score: number; status: string; retryCount: number
    replyTo: string; isAiConversation: boolean; published: boolean; sentiment: string | null
  }
}

interface ConversationMessage {
  type: string
  owner: string
  content: string
  time: string
  isAi: boolean
  quoteOwner?: string
  quoteContent?: string
}

const replies = ref<AiCommentReplyItem[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const totalPages = ref(0)

const selectedNames = ref<Set<string>>(new Set())
const selectAll = ref(false)

const filterStatus = ref("")
const filterSentiment = ref("")
const filterKeyword = ref("")

const showDialog = ref(false)
const conversationLoading = ref(false)
const conversationMessages = ref<ConversationMessage[]>([])

const toggleSelect = (name: string) => {
  selectedNames.value.has(name) ? selectedNames.value.delete(name) : selectedNames.value.add(name)
  selectAll.value = replies.value.length > 0 && replies.value.every(r => selectedNames.value.has(r.metadata.name))
}
const toggleSelectAll = () => {
  if (selectAll.value) { selectedNames.value.clear(); selectAll.value = false } 
  else { selectedNames.value = new Set(replies.value.map(r => r.metadata.name)); selectAll.value = true }
}

const fetchReplies = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: page.value, size: size.value }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterSentiment.value) params.sentiment = filterSentiment.value
    if (filterKeyword.value) params.keyword = filterKeyword.value
    const { data } = await axiosInstance.get("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies", { params })
    replies.value = data.items || []; total.value = data.total || 0; totalPages.value = Math.ceil(total.value / size.value)
  } catch (e) { Toast.error("获取数据失败") } finally { loading.value = false }
}

const openConversation = async (reply: AiCommentReplyItem) => {
  showDialog.value = true
  conversationLoading.value = true
  conversationMessages.value = []
  try {
    const { data } = await axiosInstance.get(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/conversation/${reply.spec.commentId}`)
    conversationMessages.value = data.messages || []
  } catch (e) { Toast.error("获取对话失败") } finally { conversationLoading.value = false }
}

const handleDelete = async (name: string) => { try { await axiosInstance.delete(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}`); Toast.success("删除成功"); fetchReplies() } catch (e) { Toast.error("删除失败") } }
const handleApprove = async (name: string) => { try { await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/approve`); Toast.success("审核通过"); fetchReplies() } catch (e) { Toast.error("审核失败") } }
const handleReject = async (name: string) => { try { await axiosInstance.post(`/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/reject`); Toast.success("已拒绝"); fetchReplies() } catch (e) { Toast.error("拒绝失败") } }
const batchApprove = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-approve", { names: Array.from(selectedNames.value) }); Toast.success("批量通过成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("批量通过失败") } }
const batchReject = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-reject", { names: Array.from(selectedNames.value) }); Toast.success("批量拒绝成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("批量拒绝失败") } }
const batchDelete = async () => { if(!selectedNames.value.size) return; try { await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-delete", { names: Array.from(selectedNames.value) }); Toast.success("批量删除成功"); selectedNames.value.clear(); selectAll.value=false; fetchReplies() } catch(e) { Toast.error("批量删除失败") } }

const getScoreTextClass = (score: number) => score >= 85 ? "text-green-600" : score >= 60 ? "text-yellow-600" : "text-red-600"
const getScoreBgClass = (score: number) => score >= 85 ? "bg-green-100 text-green-700" : score >= 70 ? "bg-blue-100 text-blue-700" : score >= 50 ? "bg-yellow-100 text-yellow-800" : "bg-red-100 text-red-700"
const getScoreLabel = (score: number) => score >= 85 ? "优秀" : score >= 70 ? "良好" : score >= 50 ? "一般" : "较差"

const getStatusClass = (status: string) => {
  switch (status) {
    case "PASS": return "bg-green-100 text-green-700"
    case "FAIL": return "bg-red-100 text-red-700"
    case "REJECTED": return "bg-orange-100 text-orange-700"
    default: return "bg-gray-100 text-gray-600"
  }
}
const getStatusLabel = (status: string) => {
  switch (status) {
    case "PASS": return "通过"; case "FAIL": return "失败"; case "PENDING": return "待审核"; case "REJECTED": return "已拒绝"; default: return status
  }
}

const getSentimentClass = (sentiment: string) => {
  switch (sentiment) {
    case "VERY_POSITIVE": return "bg-green-100 text-green-800"
    case "POSITIVE": return "bg-emerald-50 text-emerald-600"
    case "NEGATIVE": return "bg-rose-50 text-rose-600"
    case "VERY_NEGATIVE": return "bg-red-100 text-red-800"
    default: return "bg-gray-100 text-gray-600"
  }
}
const getSentimentLabel = (sentiment: string) => {
  switch (sentiment) {
    case "VERY_POSITIVE": return "非常正面"; case "POSITIVE": return "正面"; case "NEGATIVE": return "负面"; case "VERY_NEGATIVE": return "非常负面"; default: return "中性"
  }
}

const formatDate = (ts: string) => ts ? new Date(ts).toLocaleString("zh-CN") : ""
const getPostUrl = (slug: string) => `${window.location.origin}/archives/${slug}`
const getPageUrl = (slug: string) => `${window.location.origin}/pages/${slug}`

const stripHtml = (html: string) => {
  if (!html) return ""
  return html.replace(/<[^>]+>/g, "").replace(/\n+/g, " ").trim()
}

const truncateQuote = (content: string, length = 40) => {
  if (!content) return ""
  let plain = stripHtml(content)
  // 清理旧版本测试时残留的 Markdown 引用文本，防止摘要里套娃
  plain = plain.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, '').trim()
  return plain.length > length ? plain.substring(0, length) + "..." : plain
}

const renderContent = (content: string) => {
  if (!content) return "<span class='opacity-50'>(空)</span>"
  let parsed = content
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
    .replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, "")

  // 清理之前测试时已经存入数据库的历史遗留 "> 💬 @某人 :" 文本
  parsed = parsed.replace(/^>\s*(?:💬\s*)?\*\*(.*?)\*\*\s*[:：]\s*/gm, "")

  return parsed.replace(/\n/g, "<br/>")
}

const resetFilters = () => { filterStatus.value = ""; filterSentiment.value = ""; filterKeyword.value = ""; page.value = 1; fetchReplies() }

watch([filterStatus, filterSentiment, filterKeyword], () => { page.value = 1; fetchReplies() })
watch(page, () => { selectedNames.value.clear(); selectAll.value = false; fetchReplies() })

onMounted(fetchReplies)
</script>

<style scoped>
.line-clamp-3 {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
