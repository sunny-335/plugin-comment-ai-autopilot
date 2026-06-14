<template>
  <div class="comment-ai-autopilot-settings">
    <VPageHeader title="插件设置">
      <template #icon>
        <IconPlug class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton @click="$router.push({ name: 'CommentAiAutopilot' })"> 返回概览 </VButton>
      </template>
    </VPageHeader>

    <div class="m-4">
      <VCard :body-class="['!p-0']">
        <!-- Tab Navigation -->
        <div class="flex border-b">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="px-6 py-3 text-sm font-medium border-b-2 transition-colors"
            :class="activeTab === tab.key
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700'"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="p-6">
          <VLoading v-if="loading" />

          <!-- Basic Settings -->
          <div v-if="activeTab === 'basic' && !loading" class="space-y-6">
            <div class="flex items-center justify-between">
              <div>
                <div class="font-medium">自动回复</div>
                <div class="text-sm text-gray-500">启用后，AI将自动回复新评论</div>
              </div>
              <label class="relative inline-flex cursor-pointer items-center">
                <input type="checkbox" v-model="settings.basic.autoReply" class="peer sr-only" />
                <div class="peer h-6 w-11 rounded-full bg-gray-200 after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all peer-checked:bg-blue-600 peer-checked:after:translate-x-full peer-checked:after:border-white"></div>
              </label>
            </div>
            <div class="flex items-center justify-between">
              <div>
                <div class="font-medium">自动发布</div>
                <div class="text-sm text-gray-500">审核通过后自动发布AI回复</div>
              </div>
              <label class="relative inline-flex cursor-pointer items-center">
                <input type="checkbox" v-model="settings.basic.autoPublish" class="peer sr-only" />
                <div class="peer h-6 w-11 rounded-full bg-gray-200 after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all peer-checked:bg-blue-600 peer-checked:after:translate-x-full peer-checked:after:border-white"></div>
              </label>
            </div>
            <div>
              <label class="font-medium">最大重试次数</label>
              <input
                type="number"
                v-model.number="settings.basic.maxRetryCount"
                min="1"
                max="10"
                class="mt-1 block w-full max-w-xs rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div>
              <div class="flex items-center justify-between">
                <label class="font-medium">评论者黑名单</label>
                <VButton size="sm" @click="openCommenterDialog">
                  添加评论者
                </VButton>
              </div>
              <div class="mt-1 text-sm text-gray-500">输入评论者显示名称或邮箱，多个用逗号分隔。这些评论者的评论不会触发AI回复</div>
              <textarea
                v-model="settings.basic.blockedCommenters"
                rows="3"
                class="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="例如: 张三,李四"
              ></textarea>
            </div>
          </div>

          <!-- Persona Settings -->
          <div v-if="activeTab === 'persona' && !loading" class="space-y-6">
            <div>
              <label class="font-medium">AI角色昵称</label>
              <input
                type="text"
                v-model="settings.persona.personaName"
                class="mt-1 block w-full max-w-md rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="小回"
              />
            </div>
            <div>
              <label class="font-medium">AI角色邮箱</label>
              <div class="mt-1 text-sm text-gray-500">用于Gravatar头像服务展示头像，留空则使用默认头像</div>
              <div class="mt-1 flex items-start gap-4">
                <input
                  type="email"
                  v-model="settings.persona.personaEmail"
                  class="block w-full max-w-md rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  placeholder="ai@example.com"
                />
                <div class="flex-shrink-0">
                  <div
                    v-if="avatarUrl"
                    class="h-12 w-12 overflow-hidden rounded-full border border-gray-200"
                  >
                    <img :src="avatarUrl" alt="头像预览" class="h-full w-full object-cover" />
                  </div>
                  <div
                    v-else
                    class="flex h-12 w-12 items-center justify-center rounded-full border border-gray-200 bg-gray-100"
                  >
                    <svg class="h-6 w-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                    </svg>
                  </div>
                </div>
              </div>
            </div>
            <div>
              <label class="font-medium">AI角色人格提示词</label>
              <textarea
                v-model="settings.persona.personaPrompt"
                rows="4"
                class="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="定义AI角色的性格和回复风格"
              ></textarea>
            </div>
          </div>

          <!-- Model Settings -->
          <div v-if="activeTab === 'model' && !loading" class="space-y-6">
            <div>
              <label class="font-medium">AI模型名称</label>
              <div class="mt-1 text-sm text-gray-500">留空使用AI Foundation默认模型，填写AiModel资源名称可指定模型</div>
              <input
                type="text"
                v-model="settings.model.modelName"
                class="mt-2 block w-full max-w-md rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="留空使用默认模型"
              />
            </div>
          </div>

          <!-- Prompt Settings -->
          <div v-if="activeTab === 'prompt' && !loading" class="space-y-6">
            <div>
              <label class="font-medium">自定义Prompt模板</label>
              <div class="mt-1 text-sm text-gray-500">
                可用变量: <code class="text-xs bg-gray-100 px-1">{'{{'}persona_prompt{{'}}'}}</code>,
                <code class="text-xs bg-gray-100 px-1">{'{{'}safety_prompt{{'}}'}}</code>,
                <code class="text-xs bg-gray-100 px-1">{'{{'}article{{'}}'}}</code>,
                <code class="text-xs bg-gray-100 px-1">{'{{'}comment{{'}}'}}</code>
              </div>
              <textarea
                v-model="settings.prompt.customPromptTemplate"
                rows="10"
                class="mt-2 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="自定义Prompt模板"
              ></textarea>
            </div>
          </div>

          <!-- Cleanup Settings -->
          <div v-if="activeTab === 'cleanup' && !loading" class="space-y-6">
            <div class="flex items-center justify-between">
              <div>
                <div class="font-medium">启用自动清理</div>
                <div class="text-sm text-gray-500">启用后，将自动清理过期的AI回复记录</div>
              </div>
              <label class="relative inline-flex cursor-pointer items-center">
                <input type="checkbox" v-model="settings.cleanup.cleanupEnabled" class="peer sr-only" />
                <div class="peer h-6 w-11 rounded-full bg-gray-200 after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all peer-checked:bg-blue-600 peer-checked:after:translate-x-full peer-checked:after:border-white"></div>
              </label>
            </div>
            <div>
              <label class="font-medium">保留天数</label>
              <div class="mt-1 text-sm text-gray-500">超过保留天数的AI回复记录将被自动清理</div>
              <input
                type="number"
                v-model.number="settings.cleanup.retentionDays"
                min="1"
                max="365"
                class="mt-1 block w-full max-w-xs rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div>
              <VButton @click="performCleanup" :disabled="cleanupLoading">
                {{ cleanupLoading ? '清理中...' : '立即清理' }}
              </VButton>
              <div v-if="cleanupResult !== null" class="mt-2 text-sm text-green-600">
                清理完成，共删除 {{ cleanupResult }} 条记录
              </div>
            </div>
          </div>

          <!-- Save Button -->
          <div v-if="!loading" class="mt-6 flex justify-end">
            <VButton type="primary" @click="saveSettings" :disabled="saving">
              {{ saving ? '保存中...' : '保存设置' }}
            </VButton>
          </div>
        </div>
      </VCard>
    </div>

    <!-- Commenter Selection Dialog -->
    <div
      v-if="showCommenterDialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="showCommenterDialog = false"
    >
      <div class="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div class="border-b px-6 py-4">
          <div class="flex items-center justify-between">
            <h3 class="text-lg font-medium">选择评论者</h3>
            <button
              class="text-gray-400 hover:text-gray-600"
              @click="showCommenterDialog = false"
            >
              <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <input
            v-model="commenterSearch"
            type="text"
            class="mt-3 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            placeholder="搜索评论者名称或邮箱..."
          />
        </div>
        <div class="max-h-80 overflow-y-auto px-6 py-3">
          <VLoading v-if="commenterLoading" />
          <div v-else-if="filteredCommenters.length === 0" class="py-8 text-center text-sm text-gray-500">
            暂无评论者数据
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="commenter in filteredCommenters"
              :key="commenter.name + commenter.email"
              class="flex items-center justify-between rounded-md border border-gray-100 px-4 py-3 hover:bg-gray-50"
            >
              <div class="min-w-0 flex-1">
                <div class="truncate font-medium text-sm">{{ commenter.name }}</div>
                <div v-if="commenter.email" class="truncate text-xs text-gray-500">{{ commenter.email }}</div>
              </div>
              <VButton size="sm" @click="addCommenter(commenter)">
                添加
              </VButton>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from "vue"
import { axiosInstance } from "@halo-dev/api-client"
import { VPageHeader, VButton, VCard, VLoading, Toast } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

const tabs = [
  { key: "basic", label: "基本设置" },
  { key: "persona", label: "AI角色设置" },
  { key: "model", label: "模型设置" },
  { key: "prompt", label: "Prompt设置" },
  { key: "cleanup", label: "数据清理" },
]

const activeTab = ref("basic")
const loading = ref(false)
const saving = ref(false)

// Commenter dialog state
const showCommenterDialog = ref(false)
const commenterList = ref<{ name: string; email: string }[]>([])
const commenterSearch = ref("")
const commenterLoading = ref(false)

// Avatar preview state
const avatarUrl = ref("")
let avatarDebounceTimer: ReturnType<typeof setTimeout> | null = null

// Cleanup state
const cleanupLoading = ref(false)
const cleanupResult = ref<number | null>(null)

const settings = reactive({
  basic: {
    autoReply: true,
    autoPublish: true,
    maxRetryCount: 3,
    blockedCommenters: "",
  },
  persona: {
    personaName: "小回",
    personaPrompt: "",
    personaEmail: "",
  },
  model: {
    modelName: "",
  },
  prompt: {
    customPromptTemplate: "",
  },
  cleanup: {
    cleanupEnabled: true,
    retentionDays: 30,
  },
})

const configMapName = "comment-ai-autopilot-configmap"

// --- Task 4: Commenter blacklist enhancement ---

const openCommenterDialog = async () => {
  showCommenterDialog.value = true
  commenterSearch.value = ""
  commenterLoading.value = true
  try {
    const { data } = await axiosInstance.get(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/commenters",
    )
    commenterList.value = Array.isArray(data) ? data : (data.items || [])
  } catch (e) {
    console.error("Failed to fetch commenters", e)
    Toast.error("获取评论者列表失败")
    commenterList.value = []
  } finally {
    commenterLoading.value = false
  }
}

const filteredCommenters = computed(() => {
  const keyword = commenterSearch.value.trim().toLowerCase()
  if (!keyword) return commenterList.value
  return commenterList.value.filter(
    (c) =>
      c.name.toLowerCase().includes(keyword) ||
      (c.email && c.email.toLowerCase().includes(keyword)),
  )
})

const addCommenter = (commenter: { name: string; email: string }) => {
  const value = commenter.email || commenter.name
  if (!value) return
  const current = settings.basic.blockedCommenters
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
  if (current.includes(value)) {
    Toast.info("该评论者已在黑名单中")
    return
  }
  current.push(value)
  settings.basic.blockedCommenters = current.join(",")
  Toast.success("已添加到黑名单")
}

// --- Task 7: Avatar preview ---

const computeGravatarHash = async (email: string): Promise<string> => {
  const normalized = email.trim().toLowerCase()
  const encoder = new TextEncoder()
  const data = encoder.encode(normalized)
  const hashBuffer = await crypto.subtle.digest("SHA-256", data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("")
}

watch(
  () => settings.persona.personaEmail,
  (newEmail) => {
    if (avatarDebounceTimer) {
      clearTimeout(avatarDebounceTimer)
    }
    if (!newEmail || !newEmail.trim()) {
      avatarUrl.value = ""
      return
    }
    avatarDebounceTimer = setTimeout(async () => {
      try {
        const hash = await computeGravatarHash(newEmail)
        avatarUrl.value = `https://cn.cravatar.com/avatar/${hash}`
      } catch (e) {
        console.error("Failed to compute Gravatar hash", e)
        avatarUrl.value = ""
      }
    }, 500)
  },
)

// --- Task 10: Cleanup ---

const performCleanup = async () => {
  cleanupLoading.value = true
  cleanupResult.value = null
  try {
    const { data } = await axiosInstance.post(
      "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/cleanup",
    )
    cleanupResult.value = data.deletedCount ?? data ?? 0
    Toast.success(`清理完成，共删除 ${cleanupResult.value} 条记录`)
  } catch (e) {
    console.error("Failed to perform cleanup", e)
    Toast.error("清理失败")
  } finally {
    cleanupLoading.value = false
  }
}

// --- Settings fetch & save ---

const fetchSettings = async () => {
  loading.value = true
  try {
    const { data } = await axiosInstance.get(
      `/api/v1alpha1/configmaps/${configMapName}`,
    )
    if (data.data) {
      const d = data.data
      if (d.basic) {
        settings.basic.autoReply = d.basic.autoReply !== false
        settings.basic.autoPublish = d.basic.autoPublish !== false
        settings.basic.maxRetryCount = d.basic.maxRetryCount || 3
        settings.basic.blockedCommenters = d.basic.blockedCommenters || ""
      }
      if (d.persona) {
        settings.persona.personaName = d.persona.personaName || "小回"
        settings.persona.personaPrompt = d.persona.personaPrompt || ""
        settings.persona.personaEmail = d.persona.personaEmail || ""
      }
      if (d.model) {
        settings.model.modelName = d.model.modelName || ""
      }
      if (d.prompt) {
        settings.prompt.customPromptTemplate = d.prompt.customPromptTemplate || ""
      }
      if (d.cleanup) {
        settings.cleanup.cleanupEnabled = d.cleanup.cleanupEnabled !== false
        settings.cleanup.retentionDays = d.cleanup.retentionDays || 30
      }
    }
  } catch (e) {
    console.error("Failed to fetch settings", e)
  } finally {
    loading.value = false
  }
}

const saveSettings = async () => {
  saving.value = true
  try {
    // Fetch latest version first
    const { data: latest } = await axiosInstance.get(
      `/api/v1alpha1/configmaps/${configMapName}`,
    )

    const updated = { ...latest }
    updated.data = {
      ...updated.data,
      basic: {
        autoReply: settings.basic.autoReply,
        autoPublish: settings.basic.autoPublish,
        maxRetryCount: settings.basic.maxRetryCount,
        blockedCommenters: settings.basic.blockedCommenters,
      },
      persona: {
        personaName: settings.persona.personaName,
        personaPrompt: settings.persona.personaPrompt,
        personaEmail: settings.persona.personaEmail,
      },
      model: {
        modelName: settings.model.modelName,
      },
      prompt: {
        customPromptTemplate: settings.prompt.customPromptTemplate,
      },
      cleanup: {
        cleanupEnabled: settings.cleanup.cleanupEnabled,
        retentionDays: settings.cleanup.retentionDays,
      },
    }

    await axiosInstance.put(
      `/api/v1alpha1/configmaps/${configMapName}`,
      updated,
    )
    Toast.success("设置已保存")
  } catch (e) {
    console.error("Failed to save settings", e)
    Toast.error("保存设置失败")
  } finally {
    saving.value = false
  }
}

onMounted(fetchSettings)
</script>
