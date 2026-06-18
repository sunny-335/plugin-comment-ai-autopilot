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

    <!-- Batch Operation Toolbar -->
    <div v-if="selectedNames.size > 0" class="m-4 mb-0 flex items-center gap-3 bg-blue-50 border border-blue-200 rounded-lg px-4 py-2.5">
      <span class="text-sm text-blue-700 font-medium">已选择 {{ selectedNames.size }} 项</span>
      <button
        class="batch-btn batch-btn--approve"
        @click="batchApprove"
      >
        批量通过
      </button>
      <button
        class="batch-btn batch-btn--reject"
        @click="batchReject"
      >
        批量拒绝
      </button>
      <button
        class="batch-btn batch-btn--delete"
        @click="batchDelete"
      >
        批量删除
      </button>
      <button
        class="batch-btn batch-btn--cancel"
        @click="selectedNames.clear(); selectAll = false"
      >
        取消选择
      </button>
    </div>

    <!-- Filter Bar -->
    <div class="m-4 mb-0 flex items-center gap-3">
      <select
        v-model="filterStatus"
        class="filter-select"
      >
        <option value="">全部状态</option>
        <option value="PASS">通过</option>
        <option value="FAIL">失败</option>
        <option value="PENDING">待审核</option>
        <option value="REJECTED">已拒绝</option>
      </select>
      <select
        v-model="filterSentiment"
        class="filter-select"
      >
        <option value="">全部情感</option>
        <option value="VERY_POSITIVE">非常正面</option>
        <option value="POSITIVE">正面</option>
        <option value="NEUTRAL">中性</option>
        <option value="NEGATIVE">负面</option>
        <option value="VERY_NEGATIVE">非常负面</option>
      </select>
      <div class="relative flex-1 max-w-xs">
        <svg class="filter-search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <input
          v-model="filterKeyword"
          type="text"
          placeholder="搜索回复内容..."
          class="filter-input"
        />
      </div>
      <button
        class="filter-reset-btn"
        @click="resetFilters"
      >
        <svg style="width:14px;height:14px" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
        </svg>
        重置
      </button>
    </div>

    <div class="m-4">
      <VLoading v-if="loading" />

      <div v-else-if="replies.length === 0" class="flex flex-col items-center justify-center py-16 text-gray-400">
        <svg class="w-12 h-12 mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <span>暂无AI回复记录</span>
      </div>

      <div v-else class="space-y-3">
        <div v-if="replies.length > 0" class="flex items-center gap-2 mb-2 px-1">
          <input
            type="checkbox"
            :checked="selectAll"
            @change="toggleSelectAll"
            class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span class="text-xs text-gray-500">全选</span>
        </div>
        <div
          v-for="reply in replies"
          :key="reply.metadata.name"
          class="bg-white rounded-lg border border-gray-200 overflow-hidden hover:shadow-sm transition-all"
        >
          <!-- Card body -->
          <div class="p-4">
            <div class="flex items-start gap-3">
              <input
                type="checkbox"
                :checked="selectedNames.has(reply.metadata.name)"
                @change="toggleSelect(reply.metadata.name)"
                class="mt-1 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 shrink-0"
              />
              <div class="flex-1 min-w-0">
                <!-- Row 1: Status tags + time -->
                <div class="flex items-center justify-between mb-3">
                  <div class="flex items-center gap-1.5 flex-wrap">
                    <span
                      class="status-tag"
                      :class="getStatusClass(reply.spec.status)"
                    >
                      {{ getStatusLabel(reply.spec.status) }}
                    </span>
                    <span
                      class="status-tag"
                      :class="reply.spec.published ? 'status-tag--published' : 'status-tag--unpublished'"
                    >
                      {{ reply.spec.published ? '已发布' : '未发布' }}
                    </span>
                    <span
                      v-if="reply.spec.isAiConversation"
                      class="status-tag status-tag--conversation"
                    >
                      对话
                    </span>
                    <span
                      v-if="reply.spec.sentiment"
                      class="status-tag"
                      :class="getSentimentClass(reply.spec.sentiment)"
                    >
                      {{ getSentimentLabel(reply.spec.sentiment) }}
                    </span>
                  </div>
                  <span class="text-xs text-gray-400 flex-shrink-0 ml-2">{{ formatDate(reply.metadata.creationTimestamp) }}</span>
                </div>

                <!-- Row 2: AI reply content (truncated) -->
                <div class="text-sm text-gray-800 leading-relaxed break-words line-clamp-3 mt-2">
                  {{ stripHtml(reply.spec.reply) || '(空)' }}
                </div>
              </div>
            </div>
          </div>

          <!-- Card footer: meta info + actions -->
          <div class="px-4 py-2.5 bg-gray-50 border-t border-gray-100 flex items-center justify-between">
            <div class="flex items-center gap-4 text-xs text-gray-400">
              <span class="flex items-center gap-1.5">
                评分 <span :class="getScoreClass(reply.spec.score)" class="font-semibold">{{ reply.spec.score }}</span>
                <span class="score-label" :class="getScoreLabelClass(reply.spec.score)">{{ getScoreLabel(reply.spec.score) }}</span>
              </span>
              <span v-if="reply.spec.postSlug && reply.spec.postKind === 'Post'" class="flex items-center gap-1">
                文章
                <a
                  :href="getPostUrl(reply.spec.postSlug)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-blue-500 hover:text-blue-700 hover:underline"
                >{{ reply.spec.postSlug }}</a>
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                </svg>
              </span>
              <span v-else-if="reply.spec.postSlug && reply.spec.postKind === 'SinglePage'" class="flex items-center gap-1">
                页面
                <a
                  :href="getPageUrl(reply.spec.postSlug)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-blue-500 hover:text-blue-700 hover:underline"
                >{{ reply.spec.postSlug }}</a>
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                </svg>
              </span>
              <span v-if="reply.spec.retryCount > 0">
                重试 <span class="text-gray-600">{{ reply.spec.retryCount }}</span> 次
              </span>
            </div>
            <div class="flex items-center gap-2">
              <template v-if="reply.spec.status === 'PASS' && !reply.spec.published">
                <button
                  class="inline-flex items-center gap-1 text-xs text-green-600 hover:text-green-800 transition-colors px-2 py-1 rounded hover:bg-green-50"
                  @click="handleApprove(reply.metadata.name)"
                >
                  审核通过
                </button>
                <button
                  class="inline-flex items-center gap-1 text-xs text-red-500 hover:text-red-700 transition-colors px-2 py-1 rounded hover:bg-red-50"
                  @click="handleReject(reply.metadata.name)"
                >
                  拒绝
                </button>
              </template>
              <button
                class="inline-flex items-center gap-1 text-xs text-blue-500 hover:text-blue-700 transition-colors px-2 py-1 rounded hover:bg-blue-50"
                @click="openConversation(reply)"
              >
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
                查看
              </button>
              <button
                class="text-xs text-red-400 hover:text-red-600 transition-colors px-2 py-1 rounded hover:bg-red-50"
                @click="handleDelete(reply.metadata.name)"
              >
                删除
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Pagination -->
      <div v-if="totalPages > 1" class="flex items-center justify-between mt-4 px-1">
        <span class="text-xs text-gray-400">共 {{ total }} 条</span>
        <div class="flex gap-2">
          <VButton size="sm" :disabled="page <= 1" @click="page--">上一页</VButton>
          <VButton size="sm" :disabled="page >= totalPages" @click="page++">下一页</VButton>
        </div>
      </div>
    </div>

    <!-- Conversation Dialog -->
    <teleport to="body">
      <div
        v-if="showDialog"
        class="fixed inset-0 z-[9999] flex items-center justify-center"
      >
        <!-- Overlay -->
        <div
          class="absolute inset-0 bg-black/40"
          @click="showDialog = false"
        ></div>

        <!-- Dialog -->
        <div class="relative bg-white rounded-xl shadow-2xl w-full max-w-2xl max-h-[80vh] mx-4 flex flex-col overflow-hidden">
          <!-- Dialog header -->
          <div class="flex items-center justify-between px-5 py-4 border-b border-gray-100">
            <div class="flex items-center gap-2">
              <svg class="w-5 h-5 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
              <h3 class="text-base font-semibold text-gray-800">完整对话</h3>
            </div>
            <button
              class="text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100"
              @click="showDialog = false"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Dialog body: conversation -->
          <div class="flex-1 overflow-y-auto px-5 py-4 space-y-4">
            <VLoading v-if="conversationLoading" />

            <div v-else-if="conversationMessages.length === 0" class="text-center text-gray-400 py-8">
              暂无对话内容
            </div>

            <div v-else>
              <div
                v-for="(msg, idx) in conversationMessages"
                :key="idx"
                class="flex"
                :class="msg.isAi ? 'justify-start' : 'justify-end'"
              >
                <div class="max-w-[80%]">
                  <!-- Owner name -->
                  <div
                    class="text-xs mb-1"
                    :class="msg.isAi ? 'text-blue-500' : 'text-gray-500'"
                  >
                    <span class="font-medium">{{ msg.owner }}</span>
                  </div>
                  <!-- Bubble -->
                  <div
                    class="rounded-2xl px-4 py-2.5 text-sm leading-relaxed break-words"
                    :class="msg.isAi
                      ? 'bg-blue-50 text-gray-800 rounded-tl-md'
                      : 'bg-gray-100 text-gray-800 rounded-tr-md'"
                    v-html="renderContent(msg.content)"
                  ></div>
                  <!-- Time -->
                  <div class="text-[10px] text-gray-300 mt-1" :class="msg.isAi ? 'text-left' : 'text-right'">
                    {{ formatDate(msg.time) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Dialog footer -->
          <div class="px-5 py-3 border-t border-gray-100 flex justify-end">
            <button
              class="px-4 py-1.5 text-sm text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors"
              @click="showDialog = false"
            >
              关闭
            </button>
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
  metadata: {
    name: string
    creationTimestamp: string
  }
  spec: {
    commentId: string
    postId: string
    postSlug: string
    postKind: string
    reply: string
    score: number
    status: string
    retryCount: number
    replyTo: string
    isAiConversation: boolean
    published: boolean
    sentiment: string | null
  }
}

interface ConversationMessage {
  type: string
  owner: string
  content: string
  time: string
  isAi: boolean
}

const replies = ref<AiCommentReplyItem[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const totalPages = ref(0)

// Selection state
const selectedNames = ref<Set<string>>(new Set())
const selectAll = ref(false)

// Filter state
const filterStatus = ref("")
const filterSentiment = ref("")
const filterKeyword = ref("")

const toggleSelect = (name: string) => {
  if (selectedNames.value.has(name)) {
    selectedNames.value.delete(name)
  } else {
    selectedNames.value.add(name)
  }
  // Update selectAll state
  selectAll.value = replies.value.length > 0 && replies.value.every(r => selectedNames.value.has(r.metadata.name))
}

const toggleSelectAll = () => {
  if (selectAll.value) {
    selectedNames.value.clear()
    selectAll.value = false
  } else {
    selectedNames.value = new Set(replies.value.map(r => r.metadata.name))
    selectAll.value = true
  }
}

// Conversation dialog state
const showDialog = ref(false)
const conversationLoading = ref(false)
const conversationMessages = ref<ConversationMessage[]>([])

const fetchReplies = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: page.value, size: size.value }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterSentiment.value) params.sentiment = filterSentiment.value
    if (filterKeyword.value) params.keyword = filterKeyword.value
    const { data } = await axiosInstance.get(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies",
      { params },
    )
    replies.value = data.items || []
    total.value = data.total || 0
    totalPages.value = Math.ceil(total.value / size.value)
  } catch (e) {
    console.error("Failed to fetch replies", e)
  } finally {
    loading.value = false
  }
}

const openConversation = async (reply: AiCommentReplyItem) => {
  showDialog.value = true
  conversationLoading.value = true
  conversationMessages.value = []
  try {
    const { data } = await axiosInstance.get(
      `/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/conversation/${reply.spec.commentId}`,
    )
    conversationMessages.value = data.messages || []
  } catch (e) {
    console.error("Failed to fetch conversation", e)
    Toast.error("获取对话失败")
  } finally {
    conversationLoading.value = false
  }
}

const handleDelete = async (name: string) => {
  try {
    await axiosInstance.delete(
      `/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}`,
    )
    Toast.success("删除成功")
    fetchReplies()
  } catch (e) {
    console.error("Failed to delete reply", e)
    Toast.error("删除失败")
  }
}

const handleApprove = async (name: string) => {
  try {
    await axiosInstance.post(
      `/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/approve`,
    )
    Toast.success("审核通过")
    fetchReplies()
  } catch (e) {
    console.error("Failed to approve reply", e)
    Toast.error("审核操作失败")
  }
}

const handleReject = async (name: string) => {
  try {
    await axiosInstance.post(
      `/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/${name}/reject`,
    )
    Toast.success("已拒绝")
    fetchReplies()
  } catch (e) {
    console.error("Failed to reject reply", e)
    Toast.error("拒绝操作失败")
  }
}

const batchApprove = async () => {
  if (selectedNames.value.size === 0) return
  try {
    await axiosInstance.post(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-approve",
      { names: Array.from(selectedNames.value) }
    )
    Toast.success("批量审核通过成功")
    selectedNames.value.clear()
    selectAll.value = false
    fetchReplies()
  } catch (e) {
    Toast.error("批量审核操作失败")
  }
}

const batchReject = async () => {
  if (selectedNames.value.size === 0) return
  try {
    await axiosInstance.post(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-reject",
      { names: Array.from(selectedNames.value) }
    )
    Toast.success("批量拒绝成功")
    selectedNames.value.clear()
    selectAll.value = false
    fetchReplies()
  } catch (e) {
    Toast.error("批量拒绝操作失败")
  }
}

const batchDelete = async () => {
  if (selectedNames.value.size === 0) return
  try {
    await axiosInstance.post(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/batch-delete",
      { names: Array.from(selectedNames.value) }
    )
    Toast.success("批量删除成功")
    selectedNames.value.clear()
    selectAll.value = false
    fetchReplies()
  } catch (e) {
    Toast.error("批量删除操作失败")
  }
}

const getScoreClass = (score: number) => {
  if (score >= 85) return "text-green-600"
  if (score >= 60) return "text-yellow-600"
  return "text-red-600"
}

const getScoreLabelClass = (score: number) => {
  if (score >= 85) return "score-label--excellent"
  if (score >= 70) return "score-label--good"
  if (score >= 50) return "score-label--average"
  if (score > 0) return "score-label--poor"
  return ""
}

const getScoreLabel = (score: number) => {
  if (score >= 85) return "优秀"
  if (score >= 70) return "良好"
  if (score >= 50) return "一般"
  if (score > 0) return "较差"
  return ""
}

const getStatusClass = (status: string) => {
  switch (status) {
    case "PASS":
      return "status-tag--pass"
    case "FAIL":
      return "status-tag--fail"
    case "PENDING":
      return "status-tag--pending"
    case "REJECTED":
      return "status-tag--rejected"
    default:
      return "status-tag--pending"
  }
}

const getStatusLabel = (status: string) => {
  switch (status) {
    case "PASS":
      return "通过"
    case "FAIL":
      return "失败"
    case "PENDING":
      return "待处理"
    case "REJECTED":
      return "已拒绝"
    default:
      return status
  }
}

const getSentimentClass = (sentiment: string) => {
  switch (sentiment) {
    case "VERY_POSITIVE":
      return "status-tag--very-positive"
    case "POSITIVE":
      return "status-tag--positive"
    case "NEGATIVE":
      return "status-tag--negative"
    case "VERY_NEGATIVE":
      return "status-tag--very-negative"
    case "NEUTRAL":
      return "status-tag--neutral-sentiment"
    default:
      return "status-tag--neutral-sentiment"
  }
}

const getSentimentLabel = (sentiment: string) => {
  switch (sentiment) {
    case "VERY_POSITIVE":
      return "非常正面"
    case "POSITIVE":
      return "正面"
    case "NEGATIVE":
      return "负面"
    case "VERY_NEGATIVE":
      return "非常负面"
    case "NEUTRAL":
      return "中性"
    default:
      return sentiment
  }
}

const formatDate = (timestamp: string) => {
  if (!timestamp) return ""
  return new Date(timestamp).toLocaleString("zh-CN")
}

const getPostUrl = (slug: string) => {
  return `${window.location.origin}/archives/${slug}`
}

const getPageUrl = (slug: string) => {
  return `${window.location.origin}/pages/${slug}`
}

/**
 * Strip HTML tags for plain text display (card preview)
 */
const stripHtml = (html: string) => {
  if (!html) return ""
  return html
    .replace(/<p[^>]*>/gi, "")
    .replace(/<\/p>/gi, "\n")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim()
}

/**
 * Sanitize and render HTML content for conversation bubbles.
 * Only allows safe inline tags, strips dangerous elements.
 */
const renderContent = (content: string) => {
  if (!content) return "<span class='text-gray-400'>(空)</span>"
  return content
    .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
    .replace(/<iframe[^>]*>[\s\S]*?<\/iframe>/gi, "")
    .replace(/<object[^>]*>[\s\S]*?<\/object>/gi, "")
    .replace(/<embed[^>]*>/gi, "")
    .replace(/<form[^>]*>[\s\S]*?<\/form>/gi, "")
    .replace(/on\w+\s*=\s*["'][^"']*["']/gi, "")
    .replace(/on\w+\s*=\s*[^\s>]*/gi, "")
    .replace(/<p[^>]*>/gi, "<p style='margin:0 0 0.5em 0'>")
    .replace(/<a /gi, "<a target='_blank' rel='noopener noreferrer' ")
}

const resetFilters = () => {
  filterStatus.value = ""
  filterSentiment.value = ""
  filterKeyword.value = ""
  page.value = 1
  fetchReplies()
}

watch([filterStatus, filterSentiment, filterKeyword], () => {
  page.value = 1
  fetchReplies()
})

watch(page, () => {
  selectedNames.value.clear()
  selectAll.value = false
  fetchReplies()
})

onMounted(fetchReplies)
</script>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.line-clamp-3 {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ===== Batch Operation Buttons ===== */
.batch-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
  line-height: 1.5;
}
.batch-btn--approve {
  background-color: #16a34a;
  color: #fff;
}
.batch-btn--approve:hover {
  background-color: #15803d;
}
.batch-btn--reject {
  background-color: #f97316;
  color: #fff;
}
.batch-btn--reject:hover {
  background-color: #ea580c;
}
.batch-btn--delete {
  background-color: #dc2626;
  color: #fff;
}
.batch-btn--delete:hover {
  background-color: #b91c1c;
}
.batch-btn--cancel {
  background: none;
  color: #6b7280;
  margin-left: auto;
}
.batch-btn--cancel:hover {
  color: #374151;
  background: #f3f4f6;
}

/* ===== Filter Bar ===== */
.filter-select {
  padding: 6px 32px 6px 12px;
  font-size: 13px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  color: #374151;
  outline: none;
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 24 24' stroke='%239ca3af'%3E%3Cpath stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M19 9l-7 7-7-7'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
  background-size: 16px;
  transition: all 0.15s ease;
}
.filter-select:focus {
  border-color: #3b82f6;
  background-color: #fff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.filter-search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  width: 16px;
  height: 16px;
  color: #9ca3af;
  pointer-events: none;
}
.filter-input {
  width: 100%;
  padding: 6px 12px 6px 34px;
  font-size: 13px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  color: #374151;
  outline: none;
  transition: all 0.15s ease;
}
.filter-input:focus {
  border-color: #3b82f6;
  background-color: #fff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.filter-input::placeholder {
  color: #9ca3af;
}
.filter-reset-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  font-size: 13px;
  color: #6b7280;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.filter-reset-btn:hover {
  color: #374151;
  background: #e5e7eb;
}

/* ===== Status Tags ===== */
.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.5;
}
.status-tag--pass {
  background: #dcfce7;
  color: #166534;
}
.status-tag--fail {
  background: #fee2e2;
  color: #991b1b;
}
.status-tag--pending {
  background: #f3f4f6;
  color: #374151;
}
.status-tag--rejected {
  background: #fee2e2;
  color: #991b1b;
}
.status-tag--published {
  background: #dbeafe;
  color: #1e40af;
}
.status-tag--unpublished {
  background: #f3f4f6;
  color: #9ca3af;
}
.status-tag--conversation {
  background: #ede9fe;
  color: #5b21b6;
}

/* Sentiment tags */
.status-tag--very-positive {
  background: #dcfce7;
  color: #166534;
}
.status-tag--positive {
  background: #bbf7d0;
  color: #15803d;
}
.status-tag--neutral-sentiment {
  background: #f3f4f6;
  color: #4b5563;
}
.status-tag--negative {
  background: #fecaca;
  color: #b91c1c;
}
.status-tag--very-negative {
  background: #fee2e2;
  color: #991b1b;
}

/* ===== Score Label ===== */
.score-label {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  margin-left: 4px;
}
.score-label--excellent {
  background: #dcfce7;
  color: #166534;
}
.score-label--good {
  background: #dbeafe;
  color: #1e40af;
}
.score-label--average {
  background: #fef3c7;
  color: #92400e;
}
.score-label--poor {
  background: #fee2e2;
  color: #991b1b;
}
</style>
