<template>
  <div class="comment-ai-autopilot-home">
    <VPageHeader title="AI回评">
      <template #icon>
        <IconPlug class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton type="primary" @click="openSettings"> 插件设置 </VButton>
        <VButton @click="$router.push({ name: 'CommentAiAutopilotLogs' })">查看日志</VButton>
      </template>
    </VPageHeader>

    <div class="m-4">
      <!-- Comment Next 插件冲突提示卡 -->
      <div v-if="commentNextConflict" class="mb-4">
        <VCard :body-class="['!p-5']" class="conflict-card">
          <div class="flex items-start gap-4">
            <div class="flex-shrink-0 h-10 w-10 rounded-full bg-red-50 flex items-center justify-center">
              <svg class="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-semibold text-red-700">检测到评论组件 Next 插件冲突</div>
              <p class="text-xs text-gray-600 mt-1 leading-relaxed">
                您使用的评论组件 Next 插件已集成AI回复、AI拦截功能，请前往该插件的
                <a class="conflict-link" :href="commentNextAiReplyUrl" target="_blank">AI回复</a>
                或
                <a class="conflict-link" :href="commentNextAiReviewUrl" target="_blank">AI拦截</a>
                开启并配置对应功能
              </p>
            </div>
          </div>
        </VCard>
      </div>

      <!-- AI Foundation 状态提示卡 -->
      <div v-if="showAiFoundationWarning" class="mb-4">
        <VCard :body-class="['!p-5']" class="warning-card" :class="aiFoundationWarningType === 'no-model' || aiFoundationWarningType === 'degraded' ? 'border-l-amber-500' : 'border-l-red-500'">
          <div class="flex items-start gap-4">
            <div class="flex-shrink-0 h-10 w-10 rounded-full flex items-center justify-center"
                 :class="aiFoundationWarningType === 'no-model' || aiFoundationWarningType === 'degraded' ? 'bg-amber-50' : 'bg-red-50'">
              <svg class="w-5 h-5" :class="aiFoundationWarningType === 'no-model' || aiFoundationWarningType === 'degraded' ? 'text-amber-500' : 'text-red-500'" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            </div>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-semibold"
                   :class="aiFoundationWarningType === 'no-model' || aiFoundationWarningType === 'degraded' ? 'text-amber-700' : 'text-red-700'">
                {{ aiFoundationWarningTitle }}
              </div>
              <p class="text-xs text-gray-500 mt-1">{{ aiFoundationWarningMessage }}</p>
              <div class="mt-3 flex flex-wrap gap-2">
                <VButton v-if="aiFoundationWarningType === 'not-installed'" size="sm" type="secondary" @click="openPluginsPage">前往插件页面</VButton>
                <VButton v-if="aiFoundationWarningType === 'not-installed'" size="sm" type="primary" @click="openAiFoundationStore">应用商店下载</VButton>
                <VButton v-if="aiFoundationWarningType === 'not-enabled'" size="sm" type="secondary" @click="openAiFoundationPlugin">前往启用</VButton>
                <VButton v-if="aiFoundationWarningType === 'no-model'" size="sm" type="secondary" @click="openAiFoundationModels">添加模型</VButton>
                <VButton v-if="aiFoundationWarningType === 'no-model'" size="sm" type="primary" @click="openAiFoundationDefaults">配置默认模型</VButton>
                <VButton v-if="aiFoundationWarningType === 'unhealthy'" size="sm" type="secondary" @click="openPluginsPage">检查插件</VButton>
              </div>
            </div>
          </div>
        </VCard>
      </div>

      <!-- 顶部：AI 角色 + 概览 -->
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <!-- AI 角色卡片 -->
        <VCard :body-class="['!p-5']">
          <div class="flex items-center gap-4">
            <div class="relative">
              <img
                v-if="persona?.avatar"
                :src="persona.avatar"
                :alt="persona?.name"
                class="h-14 w-14 rounded-full object-cover ring-2 ring-blue-100"
              />
              <div
                v-else
                class="h-14 w-14 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white text-xl font-bold shadow-sm"
              >
                {{ persona?.name?.charAt(0) || '?' }}
              </div>
              <span class="absolute -bottom-0.5 -right-0.5 h-4 w-4 rounded-full bg-green-400 ring-2 ring-white"></span>
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2 flex-wrap">
                <div class="text-base font-semibold text-gray-900 truncate">{{ persona?.name || '加载中...' }}</div>
                <span v-if="persona?.gender" class="gender-badge" :class="genderBadgeClass">{{ genderText }}</span>
              </div>
              <div class="text-xs text-gray-400 mt-0.5">AI虚拟评论者 · 在线</div>
            </div>
          </div>
          <!-- 提示词预览 -->
          <div v-if="persona?.prompt" class="mt-3 bg-gray-50 rounded-md px-3 py-2">
            <p class="text-xs text-gray-500 line-clamp-2 leading-relaxed">{{ persona.prompt }}</p>
          </div>
          <!-- 唤醒词 -->
          <div v-if="persona?.wakeWord" class="mt-2 flex items-center gap-1.5">
            <span class="text-xs text-gray-400">唤醒词：</span>
            <span class="wake-word-tag">{{ persona.wakeWord }}</span>
          </div>
        </VCard>

        <!-- 概览卡片 -->
        <VCard :body-class="['!p-5']" class="lg:col-span-2">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-sm font-medium text-gray-500">概览</h3>
            <!-- AI Foundation 连接状态指示器 -->
            <div class="flex items-center gap-1.5 text-xs" :class="statusTextClass">
              <span class="status-dot" :class="statusDotClass"></span>
              <span>{{ statusText }}</span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-4 sm:grid-cols-5">
            <div class="text-center">
              <div class="text-3xl font-bold text-gray-900">{{ stats?.total || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">总回复</div>
            </div>
            <div class="text-center">
              <div class="text-3xl font-bold text-green-600">{{ stats?.passCount || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">已通过</div>
            </div>
            <div class="text-center">
              <div class="text-3xl font-bold text-red-500">{{ stats?.failCount || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">已失败</div>
            </div>
            <div class="text-center">
              <div class="text-3xl font-bold text-orange-500">{{ stats?.filteredCount || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">已拦截</div>
            </div>
            <div class="text-center">
              <div class="text-3xl font-bold text-amber-500">{{ stats?.reviewingCount || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">待审核</div>
            </div>
          </div>
        </VCard>
      </div>

      <!-- 待审核独立卡片（仅在自动发布关闭时显示） -->
      <div v-if="showPendingCard" class="mt-4">
        <VCard :body-class="['!p-5']">
          <div class="flex items-center gap-4">
            <div class="flex-shrink-0 h-12 w-12 rounded-full bg-amber-50 flex items-center justify-center">
              <svg class="w-6 h-6 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-baseline gap-2">
                <span class="text-3xl font-bold text-amber-500">{{ stats?.reviewingCount || 0 }}</span>
                <span class="text-sm text-gray-500">条 AI 回复待审核</span>
              </div>
              <p class="text-xs text-gray-400 mt-1">请前往回复日志完成审核处理</p>
              <!-- 违规待审核数（仅在启用前置过滤且违规评论设为待审核时显示） -->
              <p v-if="showViolationPending" class="text-xs text-gray-400 mt-1">
                其中
                <span class="font-semibold text-red-500">{{ violationPendingCount }}</span>
                条违规评论待审核
              </p>
            </div>
            <VButton size="sm" type="secondary" @click="$router.push({ name: 'CommentAiAutopilotLogs' })">前往审核</VButton>
          </div>
        </VCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { axiosInstance, coreApiClient } from "@halo-dev/api-client"
import { VPageHeader, VButton, VCard } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

// 接口路径与配置项名称
const apiBase = "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1"
const configMapName = "comment-ai-autopilot-configmap"
// Comment Next 插件 AI 回复 / AI 拦截配置页链接
const commentNextAiReplyUrl = "/console/plugins/PluginCommentNext?tab=aiAutoReply"
const commentNextAiReviewUrl = "/console/plugins/PluginCommentNext?tab=aiReview"
// AI Foundation 应用市场链接
const aiFoundationStoreUrl = "https://www.halo.run/store/apps/app-acslk9nu"

interface StatsResponse {
  total: number
  passCount: number
  failCount: number
  filteredCount: number
  reviewingCount: number
}

// 角色信息：名称、头像、提示词预览、唤醒词、性别
interface PersonaResponse {
  name: string
  prompt: string
  avatar: string
  wakeWord: string
  gender: string
}

interface HealthResponse {
  aiFoundationInstalled: boolean
  aiFoundationEnabled: boolean
  modelConfigured: boolean
  modelName: string
  status: string
  message: string
}

// 基本设置：自动发布、前置过滤相关
interface BasicSettings {
  autoPublish?: boolean
  preFilterEnabled?: boolean
  preFilterPendingOnViolation?: boolean
}

// Comment Next 插件状态
interface CommentNextStatus {
  installed: boolean
  enabled: boolean
}

const stats = ref<StatsResponse | null>(null)
const persona = ref<PersonaResponse | null>(null)
const health = ref<HealthResponse | null>(null)
const settings = ref<BasicSettings>({})
const commentNextStatus = ref<CommentNextStatus | null>(null)
// 违规待审核数（FILTERED 状态记录数）
const violationPendingCount = ref(0)

const showAiFoundationWarning = computed(() => {
  const s = health.value?.status
  return s && s !== 'healthy'
})

const aiFoundationWarningType = computed(() => {
  return health.value?.status || 'not-installed'
})

const aiFoundationWarningTitle = computed(() => {
  switch (health.value?.status) {
    case 'not-installed': return '未安装插件依赖 AI Foundation'
    case 'not-enabled': return 'AI Foundation 插件未启用'
    case 'no-model': return 'AI Foundation 未配置默认模型'
    case 'degraded': return '模型配置异常'
    case 'unhealthy': return 'AI Foundation 服务异常'
    default: return 'AI Foundation 异常'
  }
})

const aiFoundationWarningMessage = computed(() => {
  if (health.value?.message) return health.value.message
  switch (health.value?.status) {
    case 'not-installed': return 'AI 回复功能依赖 AI Foundation 插件，请先安装并启用该插件。'
    case 'not-enabled': return 'AI Foundation 插件已安装但未启用，请前往插件页面启用该插件。'
    case 'no-model': return 'AI Foundation 已安装启用，但未配置默认模型，请先添加模型并设为默认。'
    case 'degraded': return '当前指定的模型不可用，将使用默认模型。'
    case 'unhealthy': return 'AI Foundation 服务当前不可用，请检查插件状态。'
    default: return ''
  }
})

const statusDotClass = computed(() => {
  const status = health.value?.status
  if (status === 'healthy') return 'status-dot-green'
  if (status === 'no-model' || status === 'degraded') return 'status-dot-yellow'
  return 'status-dot-red'
})

const statusTextClass = computed(() => {
  const status = health.value?.status
  if (status === 'healthy') return 'text-green-600'
  if (status === 'no-model' || status === 'degraded') return 'text-yellow-600'
  return 'text-red-600'
})

const statusText = computed(() => {
  const status = health.value?.status
  if (status === 'healthy') return 'AI Foundation 连接正常'
  if (status === 'not-installed') return 'AI Foundation 未安装'
  if (status === 'not-enabled') return 'AI Foundation 未启用'
  if (status === 'no-model') return 'AI Foundation 未配置模型'
  if (status === 'degraded') return 'AI Foundation 部分可用'
  return 'AI Foundation 不可用'
})

// 仅在自动发布关闭时显示待审核卡片
const showPendingCard = computed(() => settings.value.autoPublish === false)

// 仅在启用前置过滤且违规评论设为待审核时显示违规待审核数
const showViolationPending = computed(() =>
  settings.value.preFilterEnabled === true && settings.value.preFilterPendingOnViolation === true,
)

// Comment Next 插件已安装且已启用时显示冲突提示
const commentNextConflict = computed(() =>
  commentNextStatus.value?.installed === true && commentNextStatus.value?.enabled === true,
)

// 性别显示文本
const genderText = computed(() => {
  const g = persona.value?.gender
  if (g === 'female') return '女'
  if (g === 'male') return '男'
  return ''
})

// 性别徽章样式
const genderBadgeClass = computed(() => {
  const g = persona.value?.gender
  if (g === 'female') return 'gender-badge-female'
  if (g === 'male') return 'gender-badge-male'
  return ''
})

// 解析 configmap 中的 JSON 配置值
const parseConfigValue = (data: Record<string, string>, key: string): Record<string, unknown> => {
  const v = data[key]
  if (!v) return {}
  if (typeof v === 'string') {
    try {
      return JSON.parse(v) as Record<string, unknown>
    } catch {
      return {}
    }
  }
  return v as Record<string, unknown>
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get(
      `${apiBase}/stats?range=7`,
    )
    stats.value = data
  } catch (e) {
    console.error("Failed to fetch stats", e)
  }
}

const computeGravatarHash = async (email: string): Promise<string> => {
  const normalized = email.trim().toLowerCase()
  const encoder = new TextEncoder()
  const data = encoder.encode(normalized)
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

// 获取默认角色完整信息（名称、提示词、唤醒词、性别）
const fetchPersona = async () => {
  try {
    const { data } = await axiosInstance.get(
      `${apiBase}/personas`,
    )
    const personas = Array.isArray(data) ? data : (data.items || [])
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const defaultPersona = personas.find((p: any) => p.spec?.isDefault) || personas[0]
    if (defaultPersona) {
      persona.value = {
        name: defaultPersona.spec?.displayName || '未命名',
        prompt: defaultPersona.spec?.prompt || '',
        avatar: '',
        wakeWord: defaultPersona.spec?.wakeWord || '',
        gender: defaultPersona.spec?.gender || '',
      }
      // 使用Gravatar邮箱头像
      const email = defaultPersona.spec?.email
      if (email) {
        const hash = await computeGravatarHash(email)
        persona.value.avatar = `https://cn.cravatar.com/avatar/${hash}`
      }
    }
  } catch (e) {
    console.error("Failed to fetch persona", e)
  }
}

const fetchHealth = async () => {
  try {
    const { data } = await axiosInstance.get(
      `${apiBase}/health`,
    )
    health.value = data
  } catch (e) {
    console.error("Failed to fetch health", e)
  }
}

// 获取插件基本设置（从 configmap 读取 autoPublish / preFilter 等配置）
const fetchSettings = async () => {
  try {
    const { data } = await coreApiClient.configMap.getConfigMap({ name: configMapName })
    if (data.data) {
      const basic = parseConfigValue(data.data, 'basic')
      settings.value = {
        autoPublish: basic.autoPublish as boolean | undefined,
        preFilterEnabled: basic.preFilterEnabled as boolean | undefined,
        preFilterPendingOnViolation: basic.preFilterPendingOnViolation as boolean | undefined,
      }
    }
  } catch (e) {
    console.error("Failed to fetch settings", e)
  }
}

// 检测 Comment Next 插件是否已安装并启用
const fetchCommentNextStatus = async () => {
  try {
    const { data } = await axiosInstance.get(
      `${apiBase}/comment-next-status`,
    )
    commentNextStatus.value = data
  } catch {
    // 端点不存在或调用失败时不显示冲突提示
    commentNextStatus.value = null
  }
}

// 获取违规待审核数（统计 FILTERED 状态记录总数）
const fetchViolationPendingCount = async () => {
  try {
    const { data } = await axiosInstance.get(
      `${apiBase}/replies?status=FILTERED&size=1&page=1`,
    )
    violationPendingCount.value = data.total || 0
  } catch {
    violationPendingCount.value = 0
  }
}

const openSettings = () => {
  window.location.href = "/console/comment-ai-autopilot/settings"
}

// 打开 AI Foundation 应用市场页面
const openAiFoundationStore = () => {
  window.open(aiFoundationStoreUrl, "_blank")
}

const openAiFoundationModels = () => {
  window.location.href = '/console/ai-foundation/models'
}

const openPluginsPage = () => {
  window.location.href = '/console/plugins'
}

const openAiFoundationPlugin = () => {
  window.location.href = '/console/plugins/ai-foundation'
}

const openAiFoundationDefaults = () => {
  window.location.href = '/console/ai-foundation/defaults'
}

onMounted(() => {
  fetchStats()
  fetchPersona()
  fetchHealth()
  fetchSettings()
  fetchCommentNextStatus()
  // 违规待审核数（用于条件展示，调用开销较小）
  fetchViolationPendingCount()
})
</script>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* AI Foundation 连接状态圆点 */
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot-green {
  background-color: #22c55e;
}

.status-dot-yellow {
  background-color: #eab308;
}

.status-dot-red {
  background-color: #ef4444;
}

/* 性别徽章 */
.gender-badge {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 500;
  padding: 1px 6px;
  border-radius: 9999px;
  line-height: 1.4;
}

.gender-badge-female {
  background-color: #fce7f3;
  color: #db2777;
}

.gender-badge-male {
  background-color: #dbeafe;
  color: #2563eb;
}

/* 唤醒词标签 */
.wake-word-tag {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 9999px;
  background-color: #f3f4f6;
  color: #6b7280;
  border: 1px solid #e5e7eb;
}

/* 冲突提示卡 */
.conflict-card {
  border-left: 4px solid #ef4444 !important;
}

.conflict-card :deep(.conflict-link) {
  color: #dc2626;
  font-weight: 500;
  text-decoration: underline;
}

.conflict-card :deep(.conflict-link:hover) {
  color: #b91c1c;
}

/* 警告提示卡 */
.warning-card {
  border-left-width: 4px !important;
  border-left-style: solid !important;
}

.warning-card.border-l-amber-500 {
  border-left-color: #f59e0b !important;
}

.warning-card.border-l-red-500 {
  border-left-color: #ef4444 !important;
}

/* Mobile responsive */
@media (max-width: 640px) {
  .comment-ai-autopilot-home :deep(.page-header) {
    flex-wrap: wrap;
    gap: 8px;
  }
  .comment-ai-autopilot-home :deep(.page-header-actions) {
    width: 100%;
  }
  .comment-ai-autopilot-home :deep(.page-header-actions .space-y-2) {
    flex-direction: row;
    width: 100%;
  }
}
</style>
