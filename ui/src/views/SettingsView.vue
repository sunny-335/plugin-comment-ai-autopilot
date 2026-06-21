<template>
  <div class="settings-container">
    <VPageHeader title="插件设置">
      <template #icon><IconPlug class="header-icon" /></template>
      <template #actions>
        <VSpace spacing="sm">
          <VButton size="sm" @click="exportConfig">导出配置</VButton>
          <VButton size="sm" @click="$router.push({ name: 'CommentAiAutopilot' })">返回概览</VButton>
        </VSpace>
      </template>
    </VPageHeader>

    <div class="settings-main">
      <VLoading v-if="loading" />

      <div v-else class="settings-layout">
        <!-- 左侧主体 -->
        <div class="settings-content">
          <!-- 标签导航 -->
          <div class="tabs-wrap">
            <button v-for="tab in tabItems" :key="tab.value" 
              @click="activeTab = tab.value"
              class="tab-btn"
              :class="{ 'active': activeTab === tab.value }">
              {{ tab.label }}
            </button>
          </div>

          <!-- 各个设置面板 -->
          <!-- 基本设置 -->
          <div v-if="activeTab === 'basic'" class="setting-panel">
            <div class="panel-header"><h3>基本设置</h3><p>控制AI回复的基本行为</p></div>
            <div class="panel-body">
              <div class="form-row"><label>自动回复</label><input type="checkbox" v-model="settings.basic.autoReply" /></div>
              <div class="form-row"><label>自动发布</label><input type="checkbox" v-model="settings.basic.autoPublish" /></div>
              <div class="form-row-col">
                <label>最大对话轮次 (当前: {{ settings.basic.maxConversationRounds }})</label>
                <input type="range" v-model.number="settings.basic.maxConversationRounds" min="1" max="100" />
              </div>
              <div class="form-row-col">
                <label>速率限制 (每分钟回复数量)</label>
                <input type="number" v-model.number="settings.basic.rateLimitPerMinute" class="custom-input" />
              </div>
              <div class="form-row-col">
                <label>评论者黑名单 (用逗号分隔)</label>
                <textarea v-model="settings.basic.blockedCommenters" class="custom-textarea" rows="3"></textarea>
              </div>
            </div>
          </div>

          <!-- 模型设置 -->
          <div v-if="activeTab === 'model'" class="setting-panel">
            <div class="panel-header"><h3>模型设置</h3><p>配置 AI Foundation</p></div>
            <div class="panel-body">
              <div class="form-row-col">
                <label>AI模型名称 (留空使用默认)</label>
                <input type="text" v-model="settings.model.modelName" class="custom-input" />
              </div>
            </div>
          </div>

          <!-- 提示词设置 -->
          <div v-if="activeTab === 'prompt'" class="setting-panel">
            <div class="panel-header"><h3>Prompt 提示词</h3><p>自定义 AI 回复逻辑</p></div>
            <div class="panel-body">
              <div class="form-row-col">
                <label>自定义Prompt模板</label>
                <textarea v-model="settings.prompt.customPromptTemplate" class="custom-textarea prompt-box" rows="12"></textarea>
              </div>
            </div>
          </div>

        </div>

        <!-- 右侧侧边栏 (保存操作区) -->
        <div class="settings-sidebar">
          <div class="sidebar-card">
            <div class="sidebar-header">
              <h4>操作控制</h4>
              <span v-if="hasUnsavedChanges" class="unsaved-badge">未保存</span>
            </div>
            <button class="btn-save" @click="saveSettings" :disabled="saving">
              {{ saving ? '保存中...' : '保存设置' }}
            </button>
            <button class="btn-reset" @click="fetchSettings" :disabled="saving">还原当前值</button>
          </div>

          <div v-if="activeTab === 'prompt'" class="sidebar-card info-card">
            <h4>可用变量</h4>
            <ul>
              <li><code v-pre>{{persona_prompt}}</code> 角色设定</li>
              <li><code v-pre>{{comment}}</code> 评论内容</li>
              <li><code v-pre>{{article}}</code> 文章内容</li>
              <li><code v-pre>{{conversation_history}}</code> 历史上下文</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue"
import { coreApiClient } from "@halo-dev/api-client"
import { VPageHeader, VButton, VLoading, Toast, VSpace, IconPlug } from "@halo-dev/components"

const activeTab = ref("basic")
const tabItems = [
  { label: "基本设置", value: "basic" },
  { label: "模型设置", value: "model" },
  { label: "Prompt", value: "prompt" },
]

const loading = ref(false)
const saving = ref(false)

const settings = reactive({
  basic: { autoReply: true, autoPublish: true, maxConversationRounds: 8, rateLimitPerMinute: 10, blockedCommenters: "" },
  model: { modelName: "" },
  prompt: { customPromptTemplate: "" }
})

const lastSavedSnapshot = ref("")
const hasUnsavedChanges = computed(() => JSON.stringify(settings) !== lastSavedSnapshot.value)
const configMapName = "comment-ai-autopilot-configmap"

const exportConfig = () => { Toast.info("导出功能开发中") }

const parseConfig = (data: any, key: string) => {
  if (!data[key]) return {}
  if (typeof data[key] === 'string') { try { return JSON.parse(data[key]) } catch { return {} } }
  return data[key]
}

const fetchSettings = async () => {
  loading.value = true
  try {
    const { data } = await coreApiClient.configMap.getConfigMap({ name: configMapName })
    if (data.data) {
      const d: any = data.data
      const basic = parseConfig(d, 'basic')
      const model = parseConfig(d, 'model')
      const prompt = parseConfig(d, 'prompt')
      
      if (Object.keys(basic).length) {
        settings.basic.autoReply = basic.autoReply !== false
        settings.basic.autoPublish = basic.autoPublish !== false
        settings.basic.maxConversationRounds = basic.maxConversationRounds || 8
        settings.basic.rateLimitPerMinute = basic.rateLimitPerMinute || 10
        settings.basic.blockedCommenters = basic.blockedCommenters || ""
      }
      if (Object.keys(model).length) settings.model.modelName = model.modelName || ""
      if (Object.keys(prompt).length) settings.prompt.customPromptTemplate = prompt.customPromptTemplate || ""
    }
  } catch (e) { Toast.error("加载设置失败") }
  finally {
    loading.value = false
    lastSavedSnapshot.value = JSON.stringify(settings)
  }
}

const saveSettings = async () => {
  saving.value = true
  try {
    const { data: latest } = await coreApiClient.configMap.getConfigMap({ name: configMapName })
    const updated = { ...latest }
    updated.data = {
      ...updated.data,
      basic: JSON.stringify(settings.basic),
      model: JSON.stringify(settings.model),
      prompt: JSON.stringify(settings.prompt)
    }
    await coreApiClient.configMap.updateConfigMap({ name: configMapName, configMap: updated })
    Toast.success("设置已保存")
    lastSavedSnapshot.value = JSON.stringify(settings)
  } catch (e) { Toast.error("保存失败") }
  finally { saving.value = false }
}

onMounted(fetchSettings)
</script>

<style scoped>
.settings-container { padding-bottom: 30px; }
.header-icon { margin-right: 8px; align-self: center; }
.settings-main { margin: 20px; }

/* 稳定的布局结构：PC端左右分栏，手机端上下堆叠 */
.settings-layout { display: flex; flex-direction: column; gap: 24px; }
@media (min-width: 1024px) {
  .settings-layout { flex-direction: row; align-items: flex-start; }
}

.settings-content { flex: 1; width: 100%; }
.settings-sidebar { width: 100%; display: flex; flex-direction: column; gap: 16px; }
@media (min-width: 1024px) {
  .settings-sidebar { width: 280px; position: sticky; top: 20px; flex-shrink: 0; }
}

/* 选项卡样式 */
.tabs-wrap { display: flex; gap: 8px; background: #fff; padding: 6px; border-radius: 10px; border: 1px solid #e5e7eb; margin-bottom: 24px; overflow-x: auto; }
.tab-btn { flex: 1; padding: 10px 16px; border: none; background: transparent; border-radius: 6px; cursor: pointer; color: #4b5563; font-weight: bold; font-size: 14px; white-space: nowrap; }
.tab-btn:hover { background: #f3f4f6; }
.tab-btn.active { background: #2563eb; color: #fff; }

/* 面板通用样式 */
.setting-panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden; }
.panel-header { padding: 16px 20px; background: #f9fafb; border-bottom: 1px solid #e5e7eb; }
.panel-header h3 { margin: 0 0 4px 0; font-size: 16px; color: #1f2937; }
.panel-header p { margin: 0; font-size: 12px; color: #6b7280; }
.panel-body { padding: 20px; display: flex; flex-direction: column; gap: 20px; }

/* 表单行 */
.form-row { display: flex; justify-content: space-between; align-items: center; padding: 12px; background: #f9fafb; border-radius: 8px; }
.form-row label { font-weight: bold; font-size: 14px; color: #374151; }
.form-row-col { display: flex; flex-direction: column; gap: 8px; }
.form-row-col label { font-weight: bold; font-size: 14px; color: #374151; }

.custom-input, .custom-textarea { width: 100%; padding: 10px 12px; border: 1px solid #d1d5db; border-radius: 8px; font-size: 14px; outline: none; box-sizing: border-box; }
.custom-input:focus, .custom-textarea:focus { border-color: #3b82f6; box-shadow: 0 0 0 2px rgba(59,130,246,0.2); }
.prompt-box { font-family: monospace; font-size: 13px; background: #f9fafb; }

/* 右侧侧边栏卡片 */
.sidebar-card { background: #f0fdf4; border: 1px solid #bbf7d0; padding: 20px; border-radius: 12px; }
.info-card { background: #fff; border-color: #e5e7eb; }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.sidebar-header h4 { margin: 0; font-size: 15px; color: #1f2937; }
.unsaved-badge { background: #fef08a; color: #854d0e; padding: 2px 6px; border-radius: 4px; font-size: 12px; font-weight: bold; }

.btn-save { width: 100%; padding: 12px; background: #16a34a; color: white; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; margin-bottom: 10px; font-size: 14px; }
.btn-save:disabled { opacity: 0.7; cursor: not-allowed; }
.btn-reset { width: 100%; padding: 10px; background: #fff; color: #374151; border: 1px solid #d1d5db; border-radius: 8px; cursor: pointer; font-size: 14px; }

.info-card ul { padding-left: 20px; margin: 0; color: #4b5563; font-size: 13px; line-height: 1.8; }
.info-card code { background: #f3f4f6; color: #9333ea; padding: 2px 4px; border-radius: 4px; font-size: 12px; }
</style>
