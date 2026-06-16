<template>
  <div class="comment-ai-autopilot-home">
    <VPageHeader title="AI回评">
      <template #icon>
        <IconPlug class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton type="primary" @click="openSettings"> 插件设置 </VButton>
      </template>
    </VPageHeader>

    <!-- Health Banner -->
    <div v-if="health && healthVisible" class="mx-4 mt-2 flex items-center gap-2 rounded-lg px-4 py-2.5"
      :class="{
        'bg-green-50 border border-green-200 text-green-700': health.status === 'healthy',
        'bg-yellow-50 border border-yellow-200 text-yellow-700': health.status === 'degraded',
        'bg-red-50 border border-red-200 text-red-700': health.status === 'unhealthy',
      }"
    >
      <svg v-if="health.status === 'healthy'" class="w-5 h-5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      <svg v-else-if="health.status === 'degraded'" class="w-5 h-5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
      </svg>
      <svg v-else class="w-5 h-5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      <span class="text-sm flex-1">
        {{ health.status === 'healthy' ? 'AI Foundation 连接正常' : health.status === 'degraded' ? 'AI Foundation 部分功能不可用' : 'AI Foundation 不可用，请检查插件和模型配置' }}
      </span>
      <button class="shrink-0 hover:opacity-70 transition-opacity" @click="healthVisible = false">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>

    <div class="m-4">
      <!-- Top: AI Persona + Stats Overview -->
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <!-- AI Persona Card -->
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
              <div class="text-base font-semibold text-gray-900 truncate">{{ persona?.name || '加载中...' }}</div>
              <div class="text-xs text-gray-400 mt-0.5">AI虚拟评论者 · 在线</div>
            </div>
          </div>
          <div v-if="persona?.prompt" class="mt-3 bg-gray-50 rounded-md px-3 py-2">
            <p class="text-xs text-gray-500 line-clamp-2 leading-relaxed">{{ persona.prompt }}</p>
          </div>
          <div class="mt-3 flex gap-2">
            <VButton size="sm" type="secondary" @click="openSettings">修改配置</VButton>
            <VButton size="sm" @click="$router.push({ name: 'CommentAiAutopilotLogs' })">查看日志</VButton>
          </div>
        </VCard>

        <!-- Stats Overview -->
        <VCard :body-class="['!p-5']" class="lg:col-span-2">
          <h3 class="text-sm font-medium text-gray-500 mb-4">回复概览</h3>
          <div class="grid grid-cols-2 gap-4 sm:grid-cols-4">
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
              <div class="text-3xl font-bold text-amber-500">{{ stats?.reviewingCount || 0 }}</div>
              <div class="text-xs text-gray-400 mt-1">待审核</div>
            </div>
          </div>
          <!-- Pass Rate Bar -->
          <div class="mt-4">
            <div class="flex items-center justify-between text-xs text-gray-400 mb-1">
              <span>通过率</span>
              <span>{{ passRate }}%</span>
            </div>
            <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
              <div
                class="h-full bg-gradient-to-r from-green-400 to-green-500 rounded-full transition-all duration-500"
                :style="{ width: passRate + '%' }"
              ></div>
            </div>
          </div>
        </VCard>
      </div>

      <!-- Middle: Sentiment + Trend -->
      <div class="grid grid-cols-1 gap-4 mt-4 lg:grid-cols-2">
        <!-- Sentiment Distribution -->
        <VCard :body-class="['!p-5']">
          <h3 class="text-sm font-medium text-gray-500 mb-4">情感分布</h3>
          <div class="space-y-3">
            <div class="flex items-center gap-3">
              <div class="w-2 h-2 rounded-full bg-green-500 shrink-0"></div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-700">正面</span>
                  <span class="font-medium text-green-600">{{ stats?.sentimentDistribution?.POSITIVE || 0 }}</span>
                </div>
                <div class="mt-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    class="h-full bg-green-400 rounded-full transition-all duration-500"
                    :style="{ width: getSentimentPercent('POSITIVE') + '%' }"
                  ></div>
                </div>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <div class="w-2 h-2 rounded-full bg-gray-400 shrink-0"></div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-700">中性</span>
                  <span class="font-medium text-gray-600">{{ stats?.sentimentDistribution?.NEUTRAL || 0 }}</span>
                </div>
                <div class="mt-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    class="h-full bg-gray-400 rounded-full transition-all duration-500"
                    :style="{ width: getSentimentPercent('NEUTRAL') + '%' }"
                  ></div>
                </div>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <div class="w-2 h-2 rounded-full bg-red-500 shrink-0"></div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-700">负面</span>
                  <span class="font-medium text-red-500">{{ stats?.sentimentDistribution?.NEGATIVE || 0 }}</span>
                </div>
                <div class="mt-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    class="h-full bg-red-400 rounded-full transition-all duration-500"
                    :style="{ width: getSentimentPercent('NEGATIVE') + '%' }"
                  ></div>
                </div>
              </div>
            </div>
            <div class="flex items-center gap-3">
              <div class="w-2 h-2 rounded-full bg-gray-300 shrink-0"></div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center justify-between text-sm">
                  <span class="text-gray-700">未知</span>
                  <span class="font-medium text-gray-400">{{ stats?.sentimentDistribution?.UNKNOWN || 0 }}</span>
                </div>
                <div class="mt-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    class="h-full bg-gray-300 rounded-full transition-all duration-500"
                    :style="{ width: getSentimentPercent('UNKNOWN') + '%' }"
                  ></div>
                </div>
              </div>
            </div>
          </div>
        </VCard>

      </div>

      <!-- Bottom: Score + Quick Actions -->
      <div class="grid grid-cols-1 gap-4 mt-4 sm:grid-cols-2">
        <!-- Avg Score -->
        <VCard :body-class="['!p-5']">
          <h3 class="text-sm font-medium text-gray-500 mb-3">平均审核评分</h3>
          <div class="flex items-center gap-4">
            <div class="text-4xl font-bold" :class="scoreColor">{{ stats?.avgScore?.toFixed(1) || '0.0' }}</div>
            <div class="flex-1">
              <div class="h-3 bg-gray-100 rounded-full overflow-hidden">
                <div
                  class="h-full rounded-full transition-all duration-500"
                  :class="scoreBarColor"
                  :style="{ width: (stats?.avgScore || 0) * 10 + '%' }"
                ></div>
              </div>
              <div class="flex justify-between text-[10px] text-gray-300 mt-1">
                <span>0</span>
                <span>5</span>
                <span>10</span>
              </div>
            </div>
          </div>
        </VCard>

        <!-- Quick Actions -->
        <VCard :body-class="['!p-5']">
          <h3 class="text-sm font-medium text-gray-500 mb-3">快捷操作</h3>
          <div class="grid grid-cols-2 gap-2">
            <button
              class="flex items-center gap-2 px-3 py-2.5 rounded-md bg-gray-50 hover:bg-gray-100 transition-colors text-sm text-gray-700"
              @click="$router.push({ name: 'CommentAiAutopilotLogs' })"
            >
              <svg class="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
              </svg>
              回复日志
            </button>
            <button
              class="flex items-center gap-2 px-3 py-2.5 rounded-md bg-gray-50 hover:bg-gray-100 transition-colors text-sm text-gray-700"
              @click="openSettings"
            >
              <svg class="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              插件设置
            </button>
            <button
              class="flex items-center gap-2 px-3 py-2.5 rounded-md bg-gray-50 hover:bg-gray-100 transition-colors text-sm text-gray-700"
              @click="$router.push({ name: 'CommentAiAutopilotSettings' })"
            >
              <svg class="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
              </svg>
              AI角色
            </button>
            <button
              class="flex items-center gap-2 px-3 py-2.5 rounded-md bg-gray-50 hover:bg-gray-100 transition-colors text-sm text-gray-700"
              @click="refreshData"
            >
              <svg class="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
              刷新数据
            </button>
          </div>
        </VCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { axiosInstance } from "@halo-dev/api-client"
import { VPageHeader, VButton, VCard, Toast } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

interface StatsResponse {
  total: number
  passCount: number
  failCount: number
  reviewingCount: number
  avgScore: number
  sentimentDistribution: Record<string, number>
}

interface PersonaResponse {
  name: string
  prompt: string
  avatar: string
}

interface HealthResponse {
  status: string
}

const stats = ref<StatsResponse | null>(null)
const persona = ref<PersonaResponse | null>(null)
const health = ref<HealthResponse | null>(null)
const healthVisible = ref(true)

const passRate = computed(() => {
  if (!stats.value || stats.value.total === 0) return 0
  return Math.round((stats.value.passCount / stats.value.total) * 100)
})

const scoreColor = computed(() => {
  const score = stats.value?.avgScore || 0
  if (score >= 7) return "text-green-600"
  if (score >= 4) return "text-amber-500"
  return "text-red-500"
})

const scoreBarColor = computed(() => {
  const score = stats.value?.avgScore || 0
  if (score >= 7) return "bg-gradient-to-r from-green-400 to-green-500"
  if (score >= 4) return "bg-gradient-to-r from-amber-400 to-amber-500"
  return "bg-gradient-to-r from-red-400 to-red-500"
})

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get(
      `/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/stats?range=7`,
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

const fetchPersona = async () => {
  try {
    const { data } = await axiosInstance.get(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/personas",
    )
    const personas = Array.isArray(data) ? data : (data.items || [])
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const defaultPersona = personas.find((p: any) => p.spec?.isDefault) || personas[0]
    if (defaultPersona) {
      persona.value = {
        name: defaultPersona.spec?.displayName || '未命名',
        prompt: defaultPersona.spec?.prompt || '',
        avatar: '',
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
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/health",
    )
    health.value = data
  } catch (e) {
    console.error("Failed to fetch health", e)
  }
}

const refreshData = () => {
  fetchStats()
  fetchPersona()
  Toast.success("数据已刷新")
}

const openSettings = () => {
  window.location.href = "/console/comment-ai-autopilot/settings"
}

const getSentimentPercent = (sentiment: string): number => {
  const dist = stats.value?.sentimentDistribution
  if (!dist) return 0
  const total = Object.values(dist).reduce((a, b) => a + b, 0)
  if (total === 0) return 0
  return Math.round(((dist[sentiment] || 0) / total) * 100)
}

onMounted(() => {
  fetchStats()
  fetchPersona()
  fetchHealth()
})
</script>
