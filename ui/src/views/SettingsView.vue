<template>
  <div class="settings-container">
    <VPageHeader title="插件设置">
      <template #icon><IconPlug class="header-icon" /></template>
      <template #actions>
        <div class="header-actions">
          <VButton size="sm" @click="exportConfig">导出</VButton>
          <label class="btn-import">导入<input type="file" accept=".json" class="hidden" @change="handleImportFile" /></label>
          <VButton size="sm" @click="$router.push({ name: 'CommentAiAutopilot' })">返回概览</VButton>
        </div>
      </template>
    </VPageHeader>

    <div class="settings-main">
      <VLoading v-if="loading" />

      <div v-else class="settings-layout">
        <!-- 左侧主体 -->
        <div class="settings-content">
          <!-- 标签导航 (移动端横向滚动) -->
          <div class="tabs-wrap">
            <button v-for="tab in tabItems" :key="tab.value" 
              class="tab-btn" :class="{ 'active': activeTab === tab.value }"
              @click="activeTab = tab.value">
              {{ tab.label }}
            </button>
          </div>

          <!-- 各个设置面板 -->
          <!-- 1. 基本设置 -->
          <div v-if="activeTab === 'basic'" class="setting-panel">
            <div class="panel-header section-header--blue">
              <div class="section-header__text"><h3>基本设置</h3><p>控制AI回复的基本行为</p></div>
            </div>
            <div class="panel-body">
              <div class="form-row">
                <div class="form-row__label"><span class="form-label">自动回复</span><span class="form-hint">启用后，AI将自动回复新评论</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.basic.autoReply" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
              <div class="form-row">
                <div class="form-row__label"><span class="form-label">自动发布</span><span class="form-hint">关闭后需手动审核发布</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.basic.autoPublish" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
              <div class="form-field">
                <div class="form-field__header"><span class="form-label">最大对话轮次</span><span class="form-badge">{{ settings.basic.maxConversationRounds }}</span></div>
                <input type="range" v-model.number="settings.basic.maxConversationRounds" min="1" max="100" class="slider__input" />
              </div>
              <div class="form-field">
                <label class="form-label">速率限制</label>
                <input type="number" v-model.number="settings.basic.rateLimitPerMinute" class="form-input" placeholder="10" />
              </div>
              <div class="form-field">
                <div class="form-field__header"><span class="form-label">最大重试次数</span><span class="form-badge">{{ settings.basic.maxRetryCount }}</span></div>
                <input type="range" v-model.number="settings.basic.maxRetryCount" min="1" max="10" class="slider__input" />
              </div>
              <div class="form-field">
                <div class="form-field__header"><span class="form-label">评论者黑名单</span><button class="btn-link" @click="openCommenterDialog">添加评论者</button></div>
                <textarea v-model="settings.basic.blockedCommenters" rows="2" class="form-textarea" placeholder="例如：张三, spam@example.com"></textarea>
              </div>
              <div class="form-row">
                <div class="form-row__label"><span class="form-label">启用前置过滤</span><span class="form-hint">AI回复前检测评论合规性，拦截广告/辱骂/敏感内容，节省Token</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.basic.preFilterEnabled" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
              <div class="form-row">
                <div class="form-row__label"><span class="form-label">违规评论设为待审核</span><span class="form-hint">检测到违规评论时自动取消通过，需人工审核</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.basic.preFilterPendingOnViolation" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
              <div v-if="momentsAvailable" class="form-row">
                <div class="form-row__label"><span class="form-label">瞬间评论区适配</span><span class="form-hint">为瞬间插件(Moments)的评论区启用AI自动回复</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.basic.momentsEnabled" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
            </div>
          </div>

          <!-- 2. AI角色设置 -->
          <div v-if="activeTab === 'persona'" class="setting-panel">
            <div class="panel-header section-header--purple">
              <div class="section-header__text"><h3>AI角色设置</h3><p>定义AI虚拟评论者的身份和风格</p></div>
            </div>
            <div class="panel-body">
              <VLoading v-if="personasLoading" />
              <div v-else-if="personas.length === 0" class="persona-empty"><span>请添加至少一个AI角色</span></div>
              <div v-else class="persona-list">
                <div v-for="p in personas" :key="p.metadata.name" class="persona-card">
                  <div class="persona-card__main">
                    <div class="persona-card__avatar">
                      <img v-if="getPersonaAvatar(p)" :src="getPersonaAvatar(p)" alt="头像" />
                      <span v-else class="persona-card__avatar-fallback">{{ (p.spec.displayName || '?').charAt(0) }}</span>
                    </div>
                    <div class="persona-card__info">
                      <div class="persona-card__name">
                        {{ p.spec.displayName || '未命名' }}
                        <span class="persona-card__badge" :class="'badge-' + p.spec.gender">{{ p.spec.gender === 'female' ? '女' : '男' }}</span>
                        <span v-if="p.spec.neutralVoice" class="persona-card__badge">中性语气</span>
                        <span v-if="p.spec.wakeWord" class="persona-card__badge badge-wake">唤醒: {{ p.spec.wakeWord }}</span>
                        <span v-if="p.spec.isDefault" class="persona-card__badge badge-default">默认</span>
                      </div>
                      <div class="persona-card__prompt">{{ p.spec.prompt || '暂无提示词' }}</div>
                    </div>
                  </div>
                  <div class="persona-card__actions">
                    <button class="btn-action btn-edit" @click="openPersonaDialog(p)">编辑</button>
                    <button v-if="!p.spec?.isDefault" class="btn-action btn-del" @click="deletePersona(p)">删除</button>
                    <button v-if="!p.spec?.isDefault" class="btn-action btn-def" @click="setDefaultPersona(p)">设为默认</button>
                  </div>
                </div>
              </div>
              <button class="btn-add-persona" @click="openPersonaDialog(null)">+ 添加角色</button>
            </div>
          </div>

          <!-- 3. 模型设置 -->
          <div v-if="activeTab === 'model'" class="setting-panel">
            <div class="panel-header section-header--green">
              <div class="section-header__text"><h3>模型设置</h3><p>配置AI Foundation提供的模型</p></div>
            </div>
            <div class="panel-body">
              <div class="form-field">
                <label class="form-label">AI模型名称</label>
                <input type="text" v-model="settings.model.modelName" class="form-input" placeholder="留空使用默认模型" />
              </div>
            </div>
          </div>

          <!-- 4. 提示词设置 -->
          <div v-if="activeTab === 'prompt'" class="setting-panel">
            <div class="panel-header section-header--amber">
              <div class="section-header__text"><h3>提示词设置</h3><p>自定义AI回复的提示词模板</p></div>
            </div>
            <div class="panel-body">
              <div class="form-field">
                <label class="form-label">提示词预设</label>
                <div class="preset-grid">
                  <label v-for="p in promptPresets" :key="p.key" class="preset-item" :class="{ 'preset-item--active': isPresetEnabled(p.key) }">
                    <input type="checkbox" :checked="isPresetEnabled(p.key)" @change="togglePreset(p.key)" class="preset-checkbox" />
                    <div class="preset-item__content"><span class="preset-item__label">{{ p.label }}</span><span class="preset-item__desc">{{ p.desc }}</span></div>
                  </label>
                </div>
              </div>
              <div class="form-field">
                <label class="form-label">自定义提示词模板</label>
                <textarea v-model="settings.prompt.customPromptTemplate" rows="10" class="form-textarea form-textarea--mono" placeholder="自定义提示词模板"></textarea>
              </div>
            </div>
          </div>

          <!-- 5. 数据清理 -->
          <div v-if="activeTab === 'cleanup'" class="setting-panel">
            <div class="panel-header section-header--red">
              <div class="section-header__text"><h3>数据清理</h3><p>自动清理过期的AI回复记录</p></div>
            </div>
            <div class="panel-body">
              <div class="form-row">
                <div class="form-row__label"><span class="form-label">启用自动清理</span><span class="form-hint">每天自动清理</span></div>
                <label class="toggle"><input type="checkbox" v-model="settings.cleanup.cleanupEnabled" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label>
              </div>
              <div class="form-field">
                <div class="form-field__header"><span class="form-label">保留天数</span><span class="form-badge">{{ settings.cleanup.retentionDays }} 天</span></div>
                <input type="range" v-model.number="settings.cleanup.retentionDays" min="1" max="365" class="slider__input" />
              </div>
              <div class="form-row form-row--bordered">
                <span class="form-label">手动清理</span>
                <VButton size="sm" type="secondary" @click="performCleanup" :disabled="cleanupLoading">{{ cleanupLoading ? '清理中...' : '立即清理' }}</VButton>
              </div>
              <div v-if="cleanupResult !== null" class="cleanup-result">清理完成，共删除 {{ cleanupResult }} 条记录</div>
            </div>
          </div>
        </div>

        <!-- 右侧侧边栏 -->
        <div class="settings-sidebar">
          <div class="sidebar-card">
            <div class="sidebar-header"><h4>操作控制</h4><span v-if="hasUnsavedChanges" class="unsaved-badge">未保存</span></div>
            <button class="btn-save" @click="saveSettings" :disabled="saving">{{ saving ? '保存中...' : '保存设置' }}</button>
            <button class="btn-reset" @click="fetchSettings" :disabled="saving">还原当前值</button>
          </div>
          <div v-if="activeTab === 'prompt'" class="sidebar-card info-card">
            <h4 class="info-title">可用变量</h4>
            <div class="sidebar-var" v-for="v in promptVariables" :key="v.name"><code>{{ v.name }}</code><span>{{ v.desc }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 弹窗：评论者黑名单 -->
    <div v-if="showCommenterDialog" class="dialog-overlay" @click.self="showCommenterDialog = false">
      <div class="dialog">
        <div class="dialog__header">
          <h3>选择评论者</h3>
          <button class="dialog__close" @click="showCommenterDialog = false">×</button>
        </div>
        <div class="dialog__body p-4">
          <input v-model="commenterSearch" type="text" class="form-input mb-3" placeholder="搜索名称或邮箱..." />
          <VLoading v-if="commenterLoading" />
          <div v-else class="dialog__list">
            <button v-for="c in filteredCommenters" :key="c.displayName + c.email" class="dialog__item" @click="addCommenter(c)">
              <div class="dialog__item-avatar"><img v-if="c.avatarUrl" :src="c.avatarUrl" alt="" /><span v-else>{{ c.displayName?.charAt(0) || '?' }}</span></div>
              <div class="dialog__item-info"><div class="dialog__item-name">{{ c.displayName }}</div><div class="dialog__item-email">{{ c.email }}</div></div>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 弹窗：AI角色编辑 -->
    <div v-if="showPersonaDialog" class="dialog-overlay" @click.self="showPersonaDialog = false">
      <div class="dialog">
        <div class="dialog__header">
          <h3>{{ personaEditing ? '编辑角色' : '添加角色' }}</h3>
          <button class="dialog__close" @click="showPersonaDialog = false">×</button>
        </div>
        <div class="dialog__body p-4">
          <div class="persona-dialog-preview">
            <div class="persona-card__avatar"><img v-if="personaDialogAvatar" :src="personaDialogAvatar" alt="" /><span v-else class="persona-card__avatar-fallback">{{ (personaForm.displayName || '?').charAt(0) }}</span></div>
            <div class="preview-text">{{ personaDialogAvatar ? 'Gravatar预览' : '未设置邮箱' }}</div>
          </div>
          <div class="form-field mt-3"><label class="form-label">昵称</label><input type="text" v-model="personaForm.displayName" class="form-input" /></div>
          <div class="form-field mt-3"><label class="form-label">邮箱</label><input type="email" v-model="personaForm.email" class="form-input" /></div>
          <div class="form-field mt-3">
            <label class="form-label">性别</label>
            <select v-model="personaForm.gender" class="form-input"><option value="female">女</option><option value="male">男</option></select>
          </div>
          <div class="form-row mt-3 px-0 bg-transparent border-0"><label class="form-label">中性语气</label><label class="toggle"><input type="checkbox" v-model="personaForm.neutralVoice" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label></div>
          <div class="form-field mt-3"><label class="form-label">唤醒词</label><input type="text" v-model="personaForm.wakeWord" class="form-input" /></div>
          <div class="form-field mt-3"><label class="form-label">提示词</label><textarea v-model="personaForm.prompt" rows="3" class="form-textarea"></textarea></div>
          <div class="form-row mt-3 px-0 bg-transparent border-0"><label class="form-label">设为默认</label><label class="toggle"><input type="checkbox" v-model="personaForm.isDefault" /><span class="toggle__track"><span class="toggle__thumb"></span></span></label></div>
          <div class="dialog-footer mt-4">
            <VButton @click="showPersonaDialog = false">取消</VButton>
            <VButton type="primary" @click="savePersona" :disabled="personaSaving">{{ personaSaving ? '保存中...' : '保存' }}</VButton>
          </div>
        </div>
      </div>
    </div>

    <!-- 弹窗：导入配置确认 -->
    <VModal v-model:visible="showImportConfirm" title="确认导入配置">
      <p style="font-size:14px;color:#4b5563">导入将覆盖当前配置，此操作不可撤销。确定要继续吗？</p>
      <template #footer><VSpace><VButton @click="showImportConfirm = false">取消</VButton><VButton type="primary" :loading="importLoading" @click="confirmImport">确认</VButton></VSpace></template>
    </VModal>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from "vue"
import { axiosInstance, coreApiClient } from "@halo-dev/api-client"
import { VPageHeader, VButton, VLoading, Toast, VModal, VSpace, IconPlug } from "@halo-dev/components"

const activeTab = ref("basic")
const tabItems = [
  { label: "基本设置", value: "basic" },
  { label: "AI角色", value: "persona" },
  { label: "模型设置", value: "model" },
  { label: "提示词", value: "prompt" },
  { label: "数据清理", value: "cleanup" },
]

const promptVariables = [
  { name: '{{persona_prompt}}', desc: 'AI角色人格提示词（含已启用的预设）' },
  { name: '{{safety_prompt}}', desc: '安全规范提示词' },
  { name: '{{output_guidance}}', desc: '输出规范（回复长度、风格约束等）' },
  { name: '{{sentiment_hint}}', desc: '情感提示（根据评论情绪自动生成，可省略）' },
  { name: '{{post_title}}', desc: '文章标题' },
  { name: '{{post_date}}', desc: '文章发布日期' },
  { name: '{{comment_count}}', desc: '该文章的评论数' },
  { name: '{{article}}', desc: '文章/页面内容' },
  { name: '{{conversation_history}}', desc: '对话历史上下文' },
  { name: '{{comment}}', desc: '评论内容（含评论者名称）' },
]

const promptPresets = [
  { key: 'friendly', label: '友好型', desc: '热情友好，像朋友聊天' },
  { key: 'professional', label: '专业型', desc: '严谨正式，有逻辑性' },
  { key: 'humorous', label: '幽默型', desc: '轻松诙谐，适当幽默' },
  { key: 'concise', label: '简洁型', desc: '一两句话，简洁明了' },
]

const settings = reactive({
  basic: { autoReply: true, autoPublish: true, maxRetryCount: 3, blockedCommenters: "", maxConversationRounds: 8, rateLimitPerMinute: 10, preFilterEnabled: true, preFilterPendingOnViolation: true, momentsEnabled: true },
  model: { modelName: "" },
  prompt: { customPromptTemplate: "", enabledPresets: [] as string[] },
  cleanup: { cleanupEnabled: true, retentionDays: 30 },
})

// 瞬间插件可用性：仅当检测到瞬间插件已安装并启用时才显示对应开关
const momentsAvailable = ref(false)
const fetchMomentsStatus = async () => {
  try {
    const { data } = await axiosInstance.get(`${apiBase}/moments-status`)
    momentsAvailable.value = !!(data?.installed || data?.enabled)
  } catch {
    momentsAvailable.value = false
  }
}

const loading = ref(false)
const saving = ref(false)
const lastSavedSnapshot = ref("")
const hasUnsavedChanges = computed(() => JSON.stringify(settings) !== lastSavedSnapshot.value)
const configMapName = "comment-ai-autopilot-configmap"
const apiBase = "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1"

// Preset Logic
const enabledPresetKeys = computed({
  get: () => Array.isArray(settings.prompt.enabledPresets) ? settings.prompt.enabledPresets : [],
  set: (keys: string[]) => { settings.prompt.enabledPresets = keys }
})
const togglePreset = (key: string) => {
  const keys = [...enabledPresetKeys.value]; const idx = keys.indexOf(key)
  if (idx >= 0) keys.splice(idx, 1); else keys.push(key)
  enabledPresetKeys.value = keys
}
const isPresetEnabled = (key: string) => enabledPresetKeys.value.includes(key)

// Import / Export
const importLoading = ref(false)
const showImportConfirm = ref(false)
const importFileData = ref<any>(null)
const exportConfig = async () => { try { const { data } = await axiosInstance.get(`${apiBase}/export`); const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = `comment-ai-autopilot-config.json`; a.click(); URL.revokeObjectURL(url); Toast.success('配置已导出') } catch(e) { Toast.error('导出配置失败') } }
const handleImportFile = (event: Event) => { const input = event.target as HTMLInputElement; if (!input.files?.length) return; const reader = new FileReader(); reader.onload = (e) => { try { importFileData.value = JSON.parse(e.target?.result as string); showImportConfirm.value = true } catch { Toast.error('解析失败') } }; reader.readAsText(input.files[0]); input.value = '' }
const confirmImport = async () => { importLoading.value = true; try { await axiosInstance.post(`${apiBase}/import`, importFileData.value); Toast.success('导入成功'); showImportConfirm.value = false; await fetchSettings(); await fetchPersonas(); await computePersonaAvatars() } catch(e) { Toast.error('导入失败') } finally { importLoading.value = false } }

// Commenters & Cleanup
const showCommenterDialog = ref(false); const commenterList = ref<any[]>([]); const commenterSearch = ref(""); const commenterLoading = ref(false)
const filteredCommenters = computed(() => { const kw = commenterSearch.value.trim().toLowerCase(); if(!kw) return commenterList.value; return commenterList.value.filter(c => c.displayName.toLowerCase().includes(kw) || (c.email && c.email.toLowerCase().includes(kw))) })
const openCommenterDialog = async () => { showCommenterDialog.value = true; commenterLoading.value = true; try { const { data } = await axiosInstance.get(`${apiBase}/commenters`); commenterList.value = data.items || data } catch(e) { commenterList.value = [] } finally { commenterLoading.value = false } }
const addCommenter = (c: any) => { const v = c.email || c.displayName; const cur = settings.basic.blockedCommenters.split(",").map(s=>s.trim()).filter(Boolean); if(cur.includes(v)) return; cur.push(v); settings.basic.blockedCommenters = cur.join(","); Toast.success("已添加"); showCommenterDialog.value = false }
const cleanupLoading = ref(false); const cleanupResult = ref<number | null>(null)
const performCleanup = async () => { cleanupLoading.value=true; try { const { data } = await axiosInstance.post(`${apiBase}/cleanup`); cleanupResult.value = typeof data === 'number' ? data : (data?.deletedCount ?? 0); Toast.success("清理完成") } catch(e){ Toast.error("清理失败") } finally { cleanupLoading.value=false } }

// Persona
const personasApiBase = `${apiBase}/personas`
const personas = ref<any[]>([]); const personasLoading = ref(false); const showPersonaDialog = ref(false); const personaEditing = ref<any>(null); const personaSaving = ref(false); const personaDialogAvatar = ref('')
const personaForm = reactive({ displayName: '', email: '', gender: 'female', neutralVoice: false, wakeWord: '', prompt: '', isDefault: false })
const computeGravatarHash = async (e: string) => { const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(e.trim().toLowerCase())); return Array.from(new Uint8Array(buf)).map(b=>b.toString(16).padStart(2,'0')).join('') }
const fetchPersonas = async () => { personasLoading.value=true; try { const { data } = await axiosInstance.get(personasApiBase); personas.value = (data.items || data).sort((a:any,b:any) => (a.spec?.isDefault ? -1 : 1)) } catch(e){} finally { personasLoading.value=false } }
const computePersonaAvatars = async () => { for (const p of personas.value) { if(p.spec?.email) { try { p._avatarUrl = `https://cn.cravatar.com/avatar/${await computeGravatarHash(p.spec.email)}` } catch{} } else { p._avatarUrl = '' } } }
const getPersonaAvatar = (p:any) => p._avatarUrl || ''
const openPersonaDialog = async (p: any) => { personaEditing.value = p; if(p){ Object.assign(personaForm, p.spec) } else { Object.assign(personaForm, { displayName:'', email:'', gender:'female', neutralVoice:false, wakeWord:'', prompt:'', isDefault:false }) }; if(personaForm.email) personaDialogAvatar.value = `https://cn.cravatar.com/avatar/${await computeGravatarHash(personaForm.email)}`; else personaDialogAvatar.value = ''; showPersonaDialog.value = true }
const savePersona = async () => { personaSaving.value=true; try { if(personaForm.isDefault) { for(const p of personas.value) { if(p.spec?.isDefault && p.metadata.name !== personaEditing.value?.metadata.name) { const {data:l} = await axiosInstance.get(`${personasApiBase}/${p.metadata.name}`); l.spec.isDefault=false; await axiosInstance.put(`${personasApiBase}/${p.metadata.name}`, l) } } } const payload = { spec: { ...personaForm }, apiVersion: 'comment-ai-autopilot.nxxy335.top/v1alpha1', kind: 'AiPersona', metadata: personaEditing.value ? { name: personaEditing.value.metadata.name } : { generateName: 'persona-' } }; if(personaEditing.value) { const {data:ex} = await axiosInstance.get(`${personasApiBase}/${personaEditing.value.metadata.name}`); await axiosInstance.put(`${personasApiBase}/${personaEditing.value.metadata.name}`, {...ex, spec: payload.spec}); Toast.success('已更新') } else { await axiosInstance.post(personasApiBase, payload); Toast.success('已添加') }; showPersonaDialog.value=false; await fetchPersonas(); await computePersonaAvatars() } catch(e){ Toast.error('保存失败') } finally { personaSaving.value=false } }
const deletePersona = async (p:any) => { if(p.spec?.isDefault) return Toast.warning('默认不可删'); if(confirm('确认删除？')) { await axiosInstance.delete(`${personasApiBase}/${p.metadata.name}`); await fetchPersonas(); await computePersonaAvatars() } }
const setDefaultPersona = async (p:any) => { for(const cp of personas.value){ if(cp.spec?.isDefault) { const {data:l} = await axiosInstance.get(`${personasApiBase}/${cp.metadata.name}`); l.spec.isDefault=false; await axiosInstance.put(`${personasApiBase}/${cp.metadata.name}`, l) } }; const {data:t} = await axiosInstance.get(`${personasApiBase}/${p.metadata.name}`); t.spec.isDefault=true; await axiosInstance.put(`${personasApiBase}/${p.metadata.name}`, t); await fetchPersonas() }

let emailDebounce: any; watch(() => personaForm.email, v => { clearTimeout(emailDebounce); if(!v) personaDialogAvatar.value=''; else emailDebounce = setTimeout(async () => { personaDialogAvatar.value = `https://cn.cravatar.com/avatar/${await computeGravatarHash(v)}` }, 500) })

const parseCfg = (d:any, k:string) => { const v = d[k]; if(!v) return {}; if(typeof v === 'string') { try{ return JSON.parse(v) }catch{ return {} } } return v }
const fetchSettings = async () => { loading.value=true; try { const { data } = await coreApiClient.configMap.getConfigMap({ name: configMapName }); if(data.data) { const d:any = data.data; const b = parseCfg(d,'basic'); const m = parseCfg(d,'model'); const p = parseCfg(d,'prompt'); const c = parseCfg(d,'cleanup'); if(b.autoReply !== undefined) Object.assign(settings.basic, b); if(m.modelName !== undefined) settings.model.modelName = m.modelName; if(p.customPromptTemplate !== undefined) { settings.prompt.customPromptTemplate = p.customPromptTemplate; settings.prompt.enabledPresets = Array.isArray(p.enabledPresets) ? p.enabledPresets : (p.enabledPresets||'').split(',').filter(Boolean) }; if(c.retentionDays !== undefined) Object.assign(settings.cleanup, c) } } catch(e){} finally { loading.value=false; lastSavedSnapshot.value = JSON.stringify(settings) } }
const saveSettings = async () => { saving.value=true; try { const { data:l } = await coreApiClient.configMap.getConfigMap({ name: configMapName }); l.data = { ...l.data, basic: JSON.stringify(settings.basic), model: JSON.stringify(settings.model), prompt: JSON.stringify(settings.prompt), cleanup: JSON.stringify(settings.cleanup) }; await coreApiClient.configMap.updateConfigMap({ name: configMapName, configMap: l }); Toast.success("保存成功"); lastSavedSnapshot.value = JSON.stringify(settings) } catch(e){ Toast.error("保存失败") } finally { saving.value=false } }

onMounted(async () => { await fetchSettings(); await fetchMomentsStatus(); await fetchPersonas(); await computePersonaAvatars() })
onUnmounted(() => { clearTimeout(emailDebounce) })
</script>

<style scoped>
/* 核心重置与基础布局 */
.settings-container { padding-bottom: 30px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
.header-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.btn-import { font-size: 13px; font-weight: 500; background: #fff; border: 1px solid #d1d5db; padding: 4px 10px; border-radius: 6px; cursor: pointer; color: #374151; white-space: nowrap; transition: 0.2s; display: inline-flex; align-items: center; }
.btn-import:hover { background: #f9fafb; border-color: #9ca3af; }
.settings-main { margin: 16px; }

/* 响应式主网格 */
.settings-layout { display: flex; flex-direction: column; gap: 24px; }
@media (min-width: 1024px) {
  .settings-main { margin: 24px; }
  .settings-layout { flex-direction: row; align-items: flex-start; }
}

.settings-content { flex: 1; width: 100%; min-width: 0; }
.settings-sidebar { width: 100%; display: flex; flex-direction: column; gap: 16px; flex-shrink: 0; }
@media (min-width: 1024px) { .settings-sidebar { width: 300px; position: sticky; top: 24px; } }

/* 导航 Tabs (支持横向滚动) */
.tabs-wrap { display: flex; gap: 6px; background: #fff; padding: 6px; border-radius: 10px; border: 1px solid #e5e7eb; margin-bottom: 20px; overflow-x: auto; white-space: nowrap; -webkit-overflow-scrolling: touch; scrollbar-width: none; }
.tabs-wrap::-webkit-scrollbar { display: none; }
.tab-btn { flex: 1; padding: 10px 16px; border: none; background: transparent; border-radius: 6px; cursor: pointer; color: #6b7280; font-weight: 600; font-size: 14px; transition: 0.2s ease; }
.tab-btn:hover { background: #f3f4f6; color: #374151; }
.tab-btn.active { background: #2563eb; color: #fff; box-shadow: 0 2px 4px rgba(37,99,235,0.2); }

/* 面板样式 */
.setting-panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.02); overflow: hidden; }
.panel-header { padding: 16px 20px; border-bottom: 1px solid #e5e7eb; }
.section-header--blue { border-top: 4px solid #3b82f6; background: linear-gradient(to right, #eff6ff, #fff); }
.section-header--purple { border-top: 4px solid #8b5cf6; background: linear-gradient(to right, #f5f3ff, #fff); }
.section-header--green { border-top: 4px solid #10b981; background: linear-gradient(to right, #ecfdf5, #fff); }
.section-header--amber { border-top: 4px solid #f59e0b; background: linear-gradient(to right, #fffbeb, #fff); }
.section-header--red { border-top: 4px solid #ef4444; background: linear-gradient(to right, #fef2f2, #fff); }
.section-header__text h3 { margin: 0 0 4px; font-size: 16px; font-weight: bold; color: #111827; }
.section-header__text p { margin: 0; font-size: 13px; color: #6b7280; }
.panel-body { padding: 20px; display: flex; flex-direction: column; gap: 20px; }

/* 表单行与控件 */
.form-row { display: flex; justify-content: space-between; align-items: center; padding: 14px; background: #f8fafc; border-radius: 8px; flex-wrap: wrap; gap: 12px; border: 1px solid #f1f5f9; }
.form-row--bordered { background: transparent; border: none; border-top: 1px solid #e5e7eb; border-radius: 0; padding: 16px 20px; margin: 0 -20px; }
.form-field { display: flex; flex-direction: column; gap: 8px; }
.form-field__header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.form-label { font-size: 14px; font-weight: 600; color: #374151; }
.form-hint { font-size: 13px; color: #6b7280; margin-top: 2px; display: block; }
.form-badge { background: #eff6ff; color: #2563eb; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }

.form-input, .form-textarea { width: 100%; padding: 10px 14px; border: 1px solid #d1d5db; border-radius: 8px; font-size: 14px; outline: none; transition: 0.2s; background: #fff; box-sizing: border-box; }
.form-input:focus, .form-textarea:focus { border-color: #3b82f6; box-shadow: 0 0 0 3px rgba(59,130,246,0.1); }
.form-textarea--mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 13px; line-height: 1.5; }

/* 按钮与开关 */
.toggle { position: relative; display: inline-block; cursor: pointer; width: 44px; height: 24px; flex-shrink: 0; }
.toggle input { opacity: 0; width: 0; height: 0; position: absolute; }
.toggle__track { position: absolute; inset: 0; background: #e5e7eb; border-radius: 12px; transition: 0.3s; }
.toggle__thumb { position: absolute; top: 2px; left: 2px; width: 20px; height: 20px; background: #fff; border-radius: 50%; transition: 0.3s; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }
.toggle input:checked + .toggle__track { background: #10b981; }
.toggle input:checked + .toggle__track .toggle__thumb { transform: translateX(20px); }

.slider__input { width: 100%; accent-color: #3b82f6; cursor: pointer; }

/* 链接及预设块 */
.btn-link { background: none; border: none; color: #3b82f6; cursor: pointer; font-size: 13px; font-weight: 500; padding: 0; }
.btn-link:hover { color: #2563eb; text-decoration: underline; }

.preset-grid { display: grid; grid-template-columns: 1fr; gap: 10px; }
@media (min-width: 640px) { .preset-grid { grid-template-columns: 1fr 1fr; } }
.preset-item { display: flex; gap: 10px; padding: 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; cursor: pointer; transition: 0.2s; }
.preset-item:hover { border-color: #cbd5e1; }
.preset-item--active { border-color: #3b82f6; background: #eff6ff; }
.preset-checkbox { width: 16px; height: 16px; margin-top: 2px; accent-color: #3b82f6; flex-shrink: 0; cursor: pointer; }
.preset-item__label { font-weight: 600; font-size: 14px; color: #1e293b; display: block; margin-bottom: 2px; }
.preset-item__desc { font-size: 12px; color: #64748b; line-height: 1.4; }

/* 角色卡片 (彻底修复排版，增加优雅感) */
.persona-empty { padding: 40px 20px; text-align: center; color: #9ca3af; font-size: 14px; background: #f8fafc; border-radius: 8px; border: 1px dashed #cbd5e1; }
.persona-list { display: flex; flex-direction: column; gap: 12px; }
.persona-card { display: flex; flex-direction: column; gap: 16px; padding: 16px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,0.02); transition: all 0.2s; }
.persona-card:hover { border-color: #cbd5e1; box-shadow: 0 4px 6px rgba(0,0,0,0.04); transform: translateY(-1px); }
@media (min-width: 640px) { .persona-card { flex-direction: row; align-items: center; justify-content: space-between; } }

.persona-card__main { display: flex; align-items: center; gap: 16px; flex: 1; min-width: 0; }
.persona-card__avatar { width: 44px; height: 44px; border-radius: 50%; overflow: hidden; background: #e5e7eb; flex-shrink: 0; box-shadow: inset 0 0 0 1px rgba(0,0,0,0.1); }
.persona-card__avatar img { width: 100%; height: 100%; object-fit: cover; }
.persona-card__avatar-fallback { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; background: linear-gradient(135deg, #8b5cf6, #6d28d9); color: #fff; font-weight: bold; font-size: 18px; }

.persona-card__info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.persona-card__name { font-size: 15px; font-weight: 700; color: #1e293b; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.persona-card__badge { padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; background: #f1f5f9; color: #475569; }
.badge-female { background: #fce7f3; color: #be185d; }
.badge-male { background: #dbeafe; color: #1d4ed8; }
.badge-wake { background: #fef3c7; color: #b45309; }
.badge-default { background: #10b981; color: #fff; }
.persona-card__prompt { font-size: 13px; color: #64748b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.persona-card__actions { display: flex; gap: 8px; width: 100%; flex-wrap: wrap; }
@media (min-width: 640px) { .persona-card__actions { width: auto; flex-shrink: 0; flex-wrap: nowrap; } }
.btn-action { padding: 6px 12px; font-size: 12px; font-weight: 600; border-radius: 6px; cursor: pointer; border: 1px solid transparent; background: #f1f5f9; color: #475569; transition: 0.2s; flex: 1; text-align: center; }
@media (min-width: 640px) { .btn-action { flex: none; } }
.btn-action:hover { background: #e2e8f0; color: #0f172a; }
.btn-del { color: #ef4444; background: #fef2f2; border-color: #fee2e2; }
.btn-del:hover { background: #fee2e2; color: #dc2626; }
.btn-edit { background: #fff; border-color: #cbd5e1; }
.btn-edit:hover { border-color: #94a3b8; background: #f8fafc; }

.btn-add-persona { width: 100%; padding: 12px; background: #f8fafc; color: #3b82f6; border: 1px dashed #cbd5e1; border-radius: 10px; cursor: pointer; font-weight: 600; font-size: 14px; transition: 0.2s; margin-top: 10px; }
.btn-add-persona:hover { background: #eff6ff; border-color: #93c5fd; }

/* 侧边栏按钮区 */
.sidebar-card { background: #fff; border: 1px solid #e5e7eb; padding: 20px; border-radius: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.02); }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.sidebar-header h4 { margin: 0; font-size: 15px; font-weight: bold; color: #1e293b; }
.unsaved-badge { background: #fef08a; color: #854d0e; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
.btn-save { width: 100%; padding: 12px; background: #2563eb; color: white; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; margin-bottom: 10px; font-size: 14px; transition: 0.2s; box-shadow: 0 2px 4px rgba(37,99,235,0.2); }
.btn-save:hover:not(:disabled) { background: #1d4ed8; }
.btn-save:disabled { opacity: 0.7; cursor: not-allowed; }
.btn-reset { width: 100%; padding: 10px; background: #fff; color: #475569; border: 1px solid #cbd5e1; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 500; transition: 0.2s; }
.btn-reset:hover:not(:disabled) { background: #f8fafc; color: #0f172a; }
.info-title { font-size: 14px; font-weight: 600; margin: 0 0 12px 0; color: #334155; }
.sidebar-var { margin-bottom: 10px; font-size: 13px; color: #64748b; display: flex; flex-direction: column; gap: 4px; }
.sidebar-var code { background: #f1f5f9; color: #8b5cf6; padding: 2px 6px; border-radius: 4px; font-size: 12px; width: max-content; }

/* 弹窗通用 (手机端撑满，电脑端居中) */
.dialog-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.6); display: flex; align-items: center; justify-content: center; z-index: 9999; backdrop-filter: blur(2px); padding: 16px; box-sizing: border-box; }
.dialog { width: 100%; max-width: 480px; background: #fff; border-radius: 16px; display: flex; flex-direction: column; max-height: 90vh; box-shadow: 0 10px 25px rgba(0,0,0,0.1); }
.dialog__header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid #f1f5f9; }
.dialog__header h3 { margin: 0; font-size: 16px; font-weight: bold; color: #1e293b; }
.dialog__close { background: none; border: none; cursor: pointer; color: #94a3b8; font-size: 24px; line-height: 1; padding: 0 4px; border-radius: 4px; }
.dialog__close:hover { color: #475569; background: #f1f5f9; }
.dialog__body { overflow-y: auto; flex: 1; padding: 20px; }
.dialog-footer { display: flex; justify-content: flex-end; gap: 10px; }

/* 弹窗内名单与预览 */
.dialog__list { display: flex; flex-direction: column; gap: 4px; }
.dialog__item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; background: none; border: 1px solid transparent; cursor: pointer; border-radius: 8px; text-align: left; transition: 0.2s; width: 100%; }
.dialog__item:hover { background: #f8fafc; border-color: #e2e8f0; }
.dialog__item-avatar { width: 36px; height: 36px; border-radius: 50%; background: #94a3b8; color: #fff; display: flex; justify-content: center; align-items: center; font-weight: bold; overflow: hidden; flex-shrink: 0; }
.dialog__item-avatar img { width: 100%; height: 100%; object-fit: cover; }
.dialog__item-info { flex: 1; min-width: 0; }
.dialog__item-name { font-size: 14px; font-weight: 600; color: #1e293b; }
.dialog__item-email { font-size: 12px; color: #64748b; margin-top: 2px; }

.persona-dialog-preview { display: flex; align-items: center; gap: 12px; background: #f8fafc; padding: 16px; border-radius: 10px; border: 1px solid #e2e8f0; }
.preview-text { font-size: 13px; color: #64748b; }
.cleanup-result { background: #ecfdf5; color: #059669; padding: 12px; border-radius: 8px; font-size: 14px; font-weight: bold; margin-top: 12px; text-align: center; border: 1px solid #a7f3d0; }

/* 实用工具 */
.p-4 { padding: 16px; } .mt-3 { margin-top: 12px; } .mt-4 { margin-top: 16px; } .mb-3 { margin-bottom: 12px; } .px-0 { padding-left: 0; padding-right: 0; } .border-0 { border: none; } .bg-transparent { background: transparent; }
</style>
