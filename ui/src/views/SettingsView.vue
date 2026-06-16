<template>
  <div class="settings-page">
    <VPageHeader title="插件设置">
      <template #icon>
        <IconPlug class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton type="secondary" size="sm" @click="exportConfig">导出配置</VButton>
        <label class="inline-flex items-center px-3 py-1.5 text-sm font-medium rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 cursor-pointer transition-colors">
          导入配置
          <input type="file" accept=".json" class="hidden" @change="handleImportFile" />
        </label>
        <VButton @click="$router.push({ name: 'CommentAiAutopilot' })">返回概览</VButton>
      </template>
    </VPageHeader>

    <div class="m-4">
      <VLoading v-if="loading" />

      <div v-if="!loading" class="settings-container">
        <!-- Left: Settings Sections -->
        <div class="settings-sections space-y-5">
          <!-- ========== 基本设置 ========== -->
          <section class="settings-section">
            <div class="section-header section-header--blue">
              <div class="section-header__icon">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/></svg>
              </div>
              <div class="section-header__text">
                <h3>基本设置</h3>
                <p>控制AI回复的基本行为</p>
              </div>
            </div>
            <div class="section-body">
              <!-- Switch Row: 自动回复 -->
              <div class="form-row">
                <div class="form-row__label">
                  <span class="form-label">自动回复</span>
                  <span class="form-hint">启用后，AI将自动回复新评论</span>
                </div>
                <label class="toggle">
                  <input type="checkbox" v-model="settings.basic.autoReply" />
                  <span class="toggle__track"><span class="toggle__thumb"></span></span>
                </label>
              </div>
              <!-- Switch Row: 自动发布 -->
              <div class="form-row">
                <div class="form-row__label">
                  <span class="form-label">自动发布</span>
                  <span class="form-hint">关闭后AI回复将存为草稿，需手动审核发布</span>
                </div>
                <label class="toggle">
                  <input type="checkbox" v-model="settings.basic.autoPublish" />
                  <span class="toggle__track"><span class="toggle__thumb"></span></span>
                </label>
              </div>
              <!-- Slider: 最大对话轮次 -->
              <div class="form-field">
                <div class="form-field__header">
                  <span class="form-label">最大对话轮次</span>
                  <span class="form-badge">{{ settings.basic.maxConversationRounds }}</span>
                </div>
                <span class="form-hint">同一评论线程中AI最多自动回复的轮次</span>
                <div class="slider">
                  <input type="range" v-model.number="settings.basic.maxConversationRounds" min="1" max="100" class="slider__input" />
                  <div class="slider__marks">
                    <span>1</span><span>50</span><span>100</span>
                  </div>
                </div>
              </div>
              <!-- Input: 速率限制 -->
              <div class="form-field">
                <label class="form-label">速率限制</label>
                <span class="form-hint">每分钟最大AI回复数量</span>
                <input type="number" v-model.number="settings.basic.rateLimitPerMinute" min="1" max="100" class="form-input" placeholder="10" />
              </div>
              <!-- Slider: 最大重试次数 -->
              <div class="form-field">
                <div class="form-field__header">
                  <span class="form-label">最大重试次数</span>
                  <span class="form-badge">{{ settings.basic.maxRetryCount }}</span>
                </div>
                <span class="form-hint">AI生成失败时的最大重试次数，采用指数退避策略</span>
                <div class="slider">
                  <input type="range" v-model.number="settings.basic.maxRetryCount" min="1" max="10" class="slider__input" />
                  <div class="slider__marks">
                    <span>1</span><span>5</span><span>10</span>
                  </div>
                </div>
              </div>
              <!-- Textarea: 评论者黑名单 -->
              <div class="form-field">
                <div class="form-field__header">
                  <span class="form-label">评论者黑名单</span>
                  <button class="btn-link" @click="openCommenterDialog">
                    <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"/></svg>
                    添加评论者
                  </button>
                </div>
                <span class="form-hint">支持名称、邮箱和正则表达式。正则以 regex: 开头，如 regex:^spam.*</span>
                <textarea v-model="settings.basic.blockedCommenters" rows="2" class="form-textarea" placeholder="例如：张三, spam@example.com, 李四"></textarea>
              </div>
            </div>
          </section>

          <!-- ========== AI角色设置 ========== -->
          <section class="settings-section">
            <div class="section-header section-header--purple">
              <div class="section-header__icon">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"/></svg>
              </div>
              <div class="section-header__text">
                <h3>AI角色设置</h3>
                <p>定义AI虚拟评论者的身份和风格</p>
              </div>
            </div>
            <div class="section-body">
              <!-- Persona List -->
              <VLoading v-if="personasLoading" />
              <div v-else-if="personas.length === 0" class="persona-empty">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z"/></svg>
                <span>请添加至少一个AI角色</span>
              </div>
              <div v-else class="persona-list">
                <div v-for="p in personas" :key="p.metadata.name" class="persona-card">
                  <div class="persona-card__avatar">
                    <img v-if="getPersonaAvatar(p)" :src="getPersonaAvatar(p)" alt="头像" />
                    <span v-else class="persona-card__avatar-fallback">{{ (p.spec.displayName || '?').charAt(0) }}</span>
                  </div>
                  <div class="persona-card__info">
                    <div class="persona-card__name">
                      {{ p.spec.displayName || '未命名' }}
                      <span v-if="p.spec.isDefault" class="persona-card__badge">默认</span>
                    </div>
                    <div class="persona-card__prompt">{{ p.spec.prompt || '暂无提示词' }}</div>
                  </div>
                  <div class="persona-card__actions">
                    <button class="btn-icon" title="编辑" @click="openPersonaDialog(p)">
                      <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/></svg>
                    </button>
                    <button v-if="!p.spec?.isDefault" class="btn-icon btn-icon--danger" title="删除" @click="deletePersona(p)">
                      <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                    </button>
                    <button v-if="!p.spec?.isDefault" class="btn-icon" title="设为默认" @click="setDefaultPersona(p)">
                      <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/></svg>
                    </button>
                  </div>
                </div>
              </div>
              <VButton type="secondary" @click="openPersonaDialog(null)">
                <svg style="width:16px;height:16px;margin-right:4px" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>
                添加角色
              </VButton>
            </div>
          </section>

          <!-- ========== 模型设置 ========== -->
          <section class="settings-section">
            <div class="section-header section-header--green">
              <div class="section-header__icon">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/></svg>
              </div>
              <div class="section-header__text">
                <h3>模型设置</h3>
                <p>配置AI Foundation提供的模型</p>
              </div>
            </div>
            <div class="section-body">
              <div class="form-field">
                <label class="form-label">AI模型名称</label>
                <span class="form-hint">留空使用AI Foundation默认模型，填写AiModel资源名称可指定模型</span>
                <input type="text" v-model="settings.model.modelName" class="form-input" placeholder="留空使用默认模型" />
              </div>
            </div>
          </section>

          <!-- ========== Prompt设置 ========== -->
          <section class="settings-section">
            <div class="section-header section-header--amber">
              <div class="section-header__icon">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/></svg>
              </div>
              <div class="section-header__text">
                <h3>Prompt设置</h3>
                <p>自定义AI回复的提示词模板</p>
              </div>
            </div>
            <div class="section-body">
              <!-- Preset Selection -->
              <div class="form-field">
                <label class="form-label">Prompt预设</label>
                <span class="form-hint">选择预设风格，可多选</span>
                <div class="preset-grid">
                  <label v-for="p in promptPresets" :key="p.key" class="preset-item" :class="{ 'preset-item--active': isPresetEnabled(p.key) }">
                    <input type="checkbox" :checked="isPresetEnabled(p.key)" @change="togglePreset(p.key)" class="preset-checkbox" />
                    <div class="preset-item__content">
                      <span class="preset-item__label">{{ p.label }}</span>
                      <span class="preset-item__desc">{{ p.desc }}</span>
                    </div>
                  </label>
                </div>
              </div>
              <div class="form-field">
                <div class="form-field__header">
                  <label class="form-label">自定义Prompt模板</label>
                  <span class="form-hint--inline">留空使用默认模板</span>
                </div>
                <div class="var-tags">
                  <span v-for="v in promptVariables" :key="v.name" class="var-tag">
                    <code>{{ v.name }}</code> {{ v.desc }}
                  </span>
                </div>
                <textarea v-model="settings.prompt.customPromptTemplate" rows="10" class="form-textarea form-textarea--mono" placeholder="自定义Prompt模板"></textarea>
              </div>
            </div>
          </section>

          <!-- ========== 数据清理 ========== -->
          <section class="settings-section">
            <div class="section-header section-header--red">
              <div class="section-header__icon">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
              </div>
              <div class="section-header__text">
                <h3>数据清理</h3>
                <p>自动清理过期的AI回复记录</p>
              </div>
            </div>
            <div class="section-body">
              <!-- Switch: 启用自动清理 -->
              <div class="form-row">
                <div class="form-row__label">
                  <span class="form-label">启用自动清理</span>
                  <span class="form-hint">每天自动清理超过保留天数的记录</span>
                </div>
                <label class="toggle">
                  <input type="checkbox" v-model="settings.cleanup.cleanupEnabled" />
                  <span class="toggle__track"><span class="toggle__thumb"></span></span>
                </label>
              </div>
              <!-- Slider: 保留天数 -->
              <div class="form-field">
                <div class="form-field__header">
                  <span class="form-label">保留天数</span>
                  <span class="form-badge">{{ settings.cleanup.retentionDays }} 天</span>
                </div>
                <span class="form-hint">超过此天数的AI回复记录将被自动清理</span>
                <div class="slider">
                  <input type="range" v-model.number="settings.cleanup.retentionDays" min="1" max="365" class="slider__input" />
                  <div class="slider__marks">
                    <span>1天</span><span>180天</span><span>365天</span>
                  </div>
                </div>
              </div>
              <!-- Manual Cleanup -->
              <div class="form-row form-row--bordered">
                <div class="form-row__label">
                  <span class="form-label">手动清理</span>
                  <span class="form-hint">立即执行一次清理操作</span>
                </div>
                <div class="form-row__action">
                  <VButton size="sm" type="secondary" @click="performCleanup" :disabled="cleanupLoading">
                    {{ cleanupLoading ? '清理中...' : '立即清理' }}
                  </VButton>
                </div>
              </div>
              <div v-if="cleanupResult !== null" class="cleanup-result">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
                清理完成，共删除 {{ cleanupResult }} 条记录
              </div>
            </div>
          </section>
        </div>

        <!-- Right: Sticky Save Bar -->
        <div class="settings-sidebar">
          <div class="settings-sidebar__inner">
            <div class="sidebar-card">
              <h4 class="sidebar-card__title">操作</h4>
              <div class="sidebar-card__actions">
                <VButton block type="primary" @click="saveSettings" :disabled="saving">
                  {{ saving ? '保存中...' : '保存设置' }}
                </VButton>
                <VButton block @click="fetchSettings">重置为当前值</VButton>
              </div>
            </div>
            <div class="sidebar-card">
              <h4 class="sidebar-card__title">可用模板变量</h4>
              <div class="sidebar-card__vars">
                <div v-for="v in promptVariables" :key="v.name" class="sidebar-var">
                  <code>{{ v.name }}</code>
                  <span>{{ v.desc }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Commenter Selection Dialog -->
    <div v-if="showCommenterDialog" class="dialog-overlay" @click.self="showCommenterDialog = false">
      <div class="dialog">
        <div class="dialog__header">
          <h3>选择评论者</h3>
          <button class="dialog__close" @click="showCommenterDialog = false">
            <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="dialog__search">
          <svg class="dialog__search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>
          <input v-model="commenterSearch" type="text" class="dialog__search-input" placeholder="搜索评论者名称或邮箱..." />
        </div>
        <div class="dialog__body">
          <VLoading v-if="commenterLoading" />
          <div v-else-if="filteredCommenters.length === 0" class="dialog__empty">
            <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z"/></svg>
            <span>暂无评论者数据</span>
          </div>
          <div v-else class="dialog__list">
            <button v-for="c in filteredCommenters" :key="c.displayName + c.email" class="dialog__item" @click="addCommenter(c)">
              <div class="dialog__item-avatar">
                <img v-if="c.avatarUrl" :src="c.avatarUrl" alt="" class="w-full h-full object-cover rounded-full" />
                <span v-else>{{ c.displayName?.charAt(0) || '?' }}</span>
              </div>
              <div class="dialog__item-info">
                <div class="dialog__item-name">{{ c.displayName }}</div>
                <div v-if="c.email" class="dialog__item-email">{{ c.email }}</div>
              </div>
              <svg class="dialog__item-add" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Persona Edit Dialog -->
    <div v-if="showPersonaDialog" class="dialog-overlay" @click.self="showPersonaDialog = false">
      <div class="dialog" style="max-width:520px">
        <div class="dialog__header">
          <h3>{{ personaEditing ? '编辑角色' : '添加角色' }}</h3>
          <button class="dialog__close" @click="showPersonaDialog = false">
            <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="dialog__body" style="max-height:none;padding:20px 24px;display:flex;flex-direction:column;gap:16px">
          <!-- Avatar Preview -->
          <div class="persona-dialog-preview">
            <div class="persona-card__avatar">
              <img v-if="personaDialogAvatar" :src="personaDialogAvatar" alt="Gravatar头像" />
              <span v-else class="persona-card__avatar-fallback">{{ (personaForm.displayName || '?').charAt(0) }}</span>
            </div>
            <div style="font-size:13px;color:#6b7280">{{ personaDialogAvatar ? 'Gravatar头像预览' : '未设置邮箱，将使用默认头像' }}</div>
          </div>
          <!-- Nickname -->
          <div class="form-field">
            <label class="form-label">昵称</label>
            <input type="text" v-model="personaForm.displayName" class="form-input" placeholder="小回" />
          </div>
          <!-- Email -->
          <div class="form-field">
            <label class="form-label">邮箱</label>
            <span class="form-hint">用于Gravatar头像服务，留空使用默认头像</span>
            <input type="email" v-model="personaForm.email" class="form-input" placeholder="ai@example.com" />
          </div>
          <!-- Prompt -->
          <div class="form-field">
            <label class="form-label">人格提示词</label>
            <span class="form-hint">定义AI角色的性格、语气和回复风格</span>
            <textarea v-model="personaForm.prompt" rows="3" class="form-textarea" placeholder="例如：你是「小回」，一个友善的评论者。你的回复简洁自然，像朋友聊天一样。"></textarea>
          </div>
          <!-- Is Default -->
          <div class="form-row">
            <div class="form-row__label">
              <span class="form-label">设为默认角色</span>
              <span class="form-hint">默认角色将用于没有指定角色的场景</span>
            </div>
            <label class="toggle">
              <input type="checkbox" v-model="personaForm.isDefault" />
              <span class="toggle__track"><span class="toggle__thumb"></span></span>
            </label>
          </div>
          <!-- Actions -->
          <div style="display:flex;justify-content:flex-end;gap:8px;padding-top:4px">
            <VButton @click="showPersonaDialog = false">取消</VButton>
            <VButton type="primary" @click="savePersona" :disabled="personaSaving">
              {{ personaSaving ? '保存中...' : '保存' }}
            </VButton>
          </div>
        </div>
      </div>
    </div>

    <!-- 导入确认对话框 -->
    <VModal v-model:visible="showImportConfirm" title="确认导入配置">
      <div class="space-y-3">
        <p class="text-sm text-gray-600">导入将覆盖当前配置，此操作不可撤销。确定要继续吗？</p>
        <div v-if="importFileData" class="text-xs text-gray-500">
          <p v-if="importFileData.configMap">- 包含插件设置</p>
          <p v-if="importFileData.personas">- 包含 {{ importFileData.personas.length }} 个AI角色</p>
        </div>
      </div>
      <template #footer>
        <VSpace>
          <VButton @click="showImportConfirm = false">取消</VButton>
          <VButton type="primary" :loading="importLoading" @click="confirmImport">确认导入</VButton>
        </VSpace>
      </template>
    </VModal>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from "vue"
import { axiosInstance, coreApiClient } from "@halo-dev/api-client"
import { VPageHeader, VButton, VLoading, Toast, VModal, VSpace } from "@halo-dev/components"
import { IconPlug } from "@halo-dev/components"

const promptVariables = [
  { name: '{{persona_prompt}}', desc: '人格提示词' },
  { name: '{{safety_prompt}}', desc: '安全规范' },
  { name: '{{post_title}}', desc: '文章标题' },
  { name: '{{post_date}}', desc: '发布日期' },
  { name: '{{comment_count}}', desc: '评论数' },
  { name: '{{article}}', desc: '文章内容' },
  { name: '{{comment}}', desc: '评论内容' },
]

const promptPresets = [
  { key: 'friendly', label: '友好型', desc: '热情友好，像朋友聊天' },
  { key: 'professional', label: '专业型', desc: '严谨正式，有逻辑性' },
  { key: 'humorous', label: '幽默型', desc: '轻松诙谐，适当幽默' },
  { key: 'concise', label: '简洁型', desc: '一两句话，简洁明了' },
]

const enabledPresetKeys = computed({
  get: () => {
    const val: string | string[] = settings.prompt.enabledPresets as string | string[]
    return Array.isArray(val) ? val : (val || '').split(',').map((s: string) => s.trim()).filter(Boolean)
  },
  set: (keys: string[]) => {
    settings.prompt.enabledPresets = keys
  }
})

const togglePreset = (key: string) => {
  const keys = enabledPresetKeys.value.slice()
  const idx = keys.indexOf(key)
  if (idx >= 0) {
    keys.splice(idx, 1)
  } else {
    keys.push(key)
  }
  enabledPresetKeys.value = keys
}

const isPresetEnabled = (key: string) => enabledPresetKeys.value.includes(key)

const loading = ref(false)
const saving = ref(false)
const showCommenterDialog = ref(false)
const commenterList = ref<{ displayName: string; email: string; avatarUrl: string }[]>([])
const commenterSearch = ref("")
const commenterLoading = ref(false)
const cleanupLoading = ref(false)
const cleanupResult = ref<number | null>(null)

// Persona management
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const personas = ref<any[]>([])
const personasLoading = ref(false)
const showPersonaDialog = ref(false)
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const personaEditing = ref<any | null>(null)
const personaSaving = ref(false)
const personaForm = reactive({
  displayName: '',
  email: '',
  prompt: '',
  isDefault: false,
})
const personaDialogAvatar = ref('')

const settings = reactive({
  basic: { autoReply: true, autoPublish: true, maxRetryCount: 3, blockedCommenters: "", maxConversationRounds: 8, rateLimitPerMinute: 10 },
  model: { modelName: "" },
  prompt: { customPromptTemplate: "", enabledPresets: [] as string[] },
  cleanup: { cleanupEnabled: true, retentionDays: 30 },
})

const configMapName = "comment-ai-autopilot-configmap"
const apiBase = "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1"

// ===== Export / Import =====
const exportConfig = async () => {
  try {
    const { data } = await axiosInstance.get(`${apiBase}/export`)
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `comment-ai-autopilot-config-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
    Toast.success('配置已导出')
  } catch (e) {
    console.error('Failed to export config', e)
    Toast.error('导出配置失败')
  }
}

const importLoading = ref(false)
const showImportConfirm = ref(false)
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const importFileData = ref<any>(null)

const handleImportFile = (event: Event) => {
  const input = event.target as HTMLInputElement
  if (!input.files || input.files.length === 0) return
  const file = input.files[0]
  const reader = new FileReader()
  reader.onload = (e) => {
    try {
      const data = JSON.parse(e.target?.result as string)
      if (!data.configMap && !data.personas) {
        Toast.error('无效的配置文件格式')
        return
      }
      importFileData.value = data
      showImportConfirm.value = true
    } catch {
      Toast.error('无法解析配置文件')
    }
  }
  reader.readAsText(file)
  // 重置 input 以允许重复选择同一文件
  input.value = ''
}

const confirmImport = async () => {
  if (!importFileData.value) return
  importLoading.value = true
  try {
    await axiosInstance.post(`${apiBase}/import`, importFileData.value)
    Toast.success('配置已导入')
    showImportConfirm.value = false
    importFileData.value = null
    // 刷新数据
    await fetchPersonas()
    await computePersonaAvatars()
  } catch (e) {
    console.error('Failed to import config', e)
    Toast.error('导入配置失败')
  } finally {
    importLoading.value = false
  }
}

const openCommenterDialog = async () => {
  showCommenterDialog.value = true
  commenterSearch.value = ""
  commenterLoading.value = true
  try {
    const { data } = await axiosInstance.get("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/commenters")
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
  const kw = commenterSearch.value.trim().toLowerCase()
  if (!kw) return commenterList.value
  return commenterList.value.filter(c => c.displayName.toLowerCase().includes(kw) || (c.email && c.email.toLowerCase().includes(kw)))
})

const addCommenter = (commenter: { displayName: string; email: string }) => {
  const value = commenter.email || commenter.displayName
  if (!value) return
  const current = settings.basic.blockedCommenters.split(",").map(s => s.trim()).filter(Boolean)
  if (current.includes(value)) { Toast.info("该评论者已在黑名单中"); return }
  current.push(value)
  settings.basic.blockedCommenters = current.join(",")
  Toast.success("已添加到黑名单")
  showCommenterDialog.value = false
}

const computeGravatarHash = async (email: string): Promise<string> => {
  const normalized = email.trim().toLowerCase()
  const encoder = new TextEncoder()
  const data = encoder.encode(normalized)
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

const performCleanup = async () => {
  cleanupLoading.value = true
  cleanupResult.value = null
  try {
    const { data } = await axiosInstance.post("/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/cleanup")
    cleanupResult.value = data.deletedCount ?? data ?? 0
    Toast.success(`清理完成，共删除 ${cleanupResult.value} 条记录`)
  } catch { Toast.error("清理失败") }
  finally { cleanupLoading.value = false }
}

// ===== Persona CRUD =====
const personasApiBase = "/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/personas"

const fetchPersonas = async () => {
  personasLoading.value = true
  try {
    const { data } = await axiosInstance.get(personasApiBase)
    const list = Array.isArray(data) ? data : (data.items || [])
    // 默认角色排到最上方
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    personas.value = list.sort((a: any, b: any) => {
      if (a.spec?.isDefault && !b.spec?.isDefault) return -1
      if (!a.spec?.isDefault && b.spec?.isDefault) return 1
      return 0
    })
  } catch (e) {
    console.error("Failed to fetch personas", e)
    personas.value = []
  } finally {
    personasLoading.value = false
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const getPersonaAvatar = (persona: any) => {
  if (persona.spec?.email) {
    return persona._avatarUrl || ''
  }
  return ''
}

// Compute avatar for each persona on list load
const computePersonaAvatars = async () => {
  for (const p of personas.value) {
    if (p.spec?.email) {
      try {
        const hash = await computeGravatarHash(p.spec.email)
        p._avatarUrl = `https://cn.cravatar.com/avatar/${hash}`
      } catch { p._avatarUrl = '' }
    } else {
      p._avatarUrl = ''
    }
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const openPersonaDialog = async (persona: any | null) => {
  personaEditing.value = persona
  if (persona) {
    personaForm.displayName = persona.spec.displayName || ''
    personaForm.email = persona.spec.email || ''
    personaForm.prompt = persona.spec.prompt || ''
    personaForm.isDefault = persona.spec.isDefault || false
  } else {
    personaForm.displayName = ''
    personaForm.email = ''
    personaForm.prompt = ''
    personaForm.isDefault = false
  }
  personaDialogAvatar.value = ''
  if (personaForm.email) {
    try {
      const hash = await computeGravatarHash(personaForm.email)
      personaDialogAvatar.value = `https://cn.cravatar.com/avatar/${hash}`
    } catch { personaDialogAvatar.value = '' }
  }
  showPersonaDialog.value = true
}

const savePersona = async () => {
  if (!personaForm.displayName.trim()) {
    Toast.warning('请输入角色昵称')
    return
  }
  // 防止取消唯一默认角色的默认标记
  if (!personaForm.isDefault && personaEditing.value?.spec?.isDefault) {
    Toast.warning('必须保留至少一个默认角色，请先将其他角色设为默认')
    return
  }
  personaSaving.value = true
  try {
    // 如果设为默认角色，先取消当前默认角色的标记
    if (personaForm.isDefault) {
      for (const p of personas.value) {
        if (p.spec?.isDefault) {
          // 编辑时跳过自身
          if (personaEditing.value && p.metadata.name === personaEditing.value.metadata.name) continue
          const { data: latest } = await axiosInstance.get(`${personasApiBase}/${p.metadata.name}`)
          latest.spec.isDefault = false
          await axiosInstance.put(`${personasApiBase}/${p.metadata.name}`, latest)
        }
      }
    }
    const payload = {
      spec: {
        displayName: personaForm.displayName,
        email: personaForm.email,
        prompt: personaForm.prompt,
        isDefault: personaForm.isDefault,
      },
      apiVersion: 'comment-ai-autopilot.nxxy335.top/v1alpha1',
      kind: 'AiPersona',
      metadata: personaEditing.value
        ? { name: personaEditing.value.metadata.name }
        : { generateName: 'persona-' },
    }
    if (personaEditing.value) {
      // Update
      const { data: existing } = await axiosInstance.get(`${personasApiBase}/${personaEditing.value.metadata.name}`)
      const updated = { ...existing, spec: { ...existing.spec, ...payload.spec } }
      await axiosInstance.put(`${personasApiBase}/${personaEditing.value.metadata.name}`, updated)
      Toast.success('角色已更新')
    } else {
      // Create
      await axiosInstance.post(personasApiBase, payload)
      Toast.success('角色已添加')
    }
    showPersonaDialog.value = false
    await fetchPersonas()
    await computePersonaAvatars()
  } catch (e) {
    console.error('Failed to save persona', e)
    Toast.error('保存角色失败')
  } finally {
    personaSaving.value = false
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const deletePersona = async (persona: any) => {
  if (persona.spec?.isDefault) {
    Toast.warning('默认角色不可删除，请先将其他角色设为默认')
    return
  }
  if (!confirm(`确定要删除角色「${persona.spec?.displayName || '未命名'}」吗？`)) return
  try {
    await axiosInstance.delete(`${personasApiBase}/${persona.metadata.name}`)
    Toast.success('角色已删除')
    await fetchPersonas()
    await computePersonaAvatars()
  } catch (e) {
    console.error('Failed to delete persona', e)
    Toast.error('删除角色失败')
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const setDefaultPersona = async (persona: any) => {
  try {
    // 先取消当前默认角色：获取最新数据后更新
    for (const p of personas.value) {
      if (p.spec?.isDefault && p.metadata.name !== persona.metadata.name) {
        const { data: latest } = await axiosInstance.get(`${personasApiBase}/${p.metadata.name}`)
        latest.spec.isDefault = false
        await axiosInstance.put(`${personasApiBase}/${p.metadata.name}`, latest)
      }
    }
    // 设置新默认角色：获取最新数据后更新
    const { data: latestTarget } = await axiosInstance.get(`${personasApiBase}/${persona.metadata.name}`)
    latestTarget.spec.isDefault = true
    await axiosInstance.put(`${personasApiBase}/${persona.metadata.name}`, latestTarget)
    Toast.success('已设为默认角色')
    await fetchPersonas()
  } catch (e) {
    console.error('Failed to set default persona', e)
    Toast.error('设置默认角色失败')
  }
}

// Watch persona dialog email for Gravatar preview
let emailDebounceTimer: ReturnType<typeof setTimeout> | null = null
watch(() => personaForm.email, (newEmail) => {
  if (emailDebounceTimer) clearTimeout(emailDebounceTimer)
  if (!newEmail || !newEmail.trim()) { personaDialogAvatar.value = ''; return }
  emailDebounceTimer = setTimeout(async () => {
    try {
      const hash = await computeGravatarHash(newEmail)
      personaDialogAvatar.value = `https://cn.cravatar.com/avatar/${hash}`
    } catch { personaDialogAvatar.value = '' }
  }, 500)
})

const parseConfigSection = (data: Record<string, unknown>, key: string): Record<string, unknown> => {
  const val = data[key]
  if (!val) return {}
  if (typeof val === 'string') {
    try { return JSON.parse(val) } catch { return {} }
  }
  return val as Record<string, unknown>
}

const fetchSettings = async () => {
  loading.value = true
  try {
    const { data } = await coreApiClient.configMap.getConfigMap({ name: configMapName })
    if (data.data) {
      const d = data.data as Record<string, unknown>
      const basic = parseConfigSection(d, 'basic') as Record<string, unknown>
      const model = parseConfigSection(d, 'model') as Record<string, unknown>
      const prompt = parseConfigSection(d, 'prompt') as Record<string, unknown>
      const cleanup = parseConfigSection(d, 'cleanup') as Record<string, unknown>
      if (Object.keys(basic).length) { settings.basic.autoReply = basic.autoReply !== false; settings.basic.autoPublish = basic.autoPublish !== false; settings.basic.maxRetryCount = (basic.maxRetryCount as number) || 3; settings.basic.blockedCommenters = (basic.blockedCommenters as string) || ""; settings.basic.maxConversationRounds = (basic.maxConversationRounds as number) || 8; settings.basic.rateLimitPerMinute = (basic.rateLimitPerMinute as number) || 10 }
      if (Object.keys(model).length) { settings.model.modelName = (model.modelName as string) || "" }
      if (Object.keys(prompt).length) { settings.prompt.customPromptTemplate = (prompt.customPromptTemplate as string) || ""; const ep = prompt.enabledPresets; settings.prompt.enabledPresets = Array.isArray(ep) ? ep : (typeof ep === 'string' ? (ep as string).split(",").map((s: string) => s.trim()).filter(Boolean) : []) }
      if (Object.keys(cleanup).length) { settings.cleanup.cleanupEnabled = cleanup.cleanupEnabled !== false; settings.cleanup.retentionDays = (cleanup.retentionDays as number) || 30 }
    }
  } catch (e) { console.error("Failed to fetch settings", e) }
  finally { loading.value = false }
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
      prompt: JSON.stringify(settings.prompt),
      cleanup: JSON.stringify(settings.cleanup),
    }
    await coreApiClient.configMap.updateConfigMap({ name: configMapName, configMap: updated })
    Toast.success("设置已保存")
  } catch (e) { console.error("Failed to save settings", e); Toast.error("保存设置失败") }
  finally { saving.value = false }
}

onMounted(async () => {
  await fetchSettings()
  await fetchPersonas()
  await computePersonaAvatars()
})
</script>

<style scoped>
/* ===== Layout ===== */
.settings-container {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 24px;
  align-items: start;
}
@media (max-width: 1024px) {
  .settings-container { grid-template-columns: 1fr; }
}

/* ===== Section ===== */
.settings-section {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: hidden;
}
.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-left: 4px solid transparent;
}
.section-header--blue  { border-left-color: #3b82f6; background: linear-gradient(90deg, #eff6ff 0%, #fff 100%); }
.section-header--purple { border-left-color: #8b5cf6; background: linear-gradient(90deg, #f5f3ff 0%, #fff 100%); }
.section-header--green  { border-left-color: #10b981; background: linear-gradient(90deg, #ecfdf5 0%, #fff 100%); }
.section-header--amber  { border-left-color: #f59e0b; background: linear-gradient(90deg, #fffbeb 0%, #fff 100%); }
.section-header--red    { border-left-color: #ef4444; background: linear-gradient(90deg, #fef2f2 0%, #fff 100%); }

.section-header__icon {
  width: 36px; height: 36px;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.section-header--blue  .section-header__icon { background: #dbeafe; color: #2563eb; }
.section-header--purple .section-header__icon { background: #ede9fe; color: #7c3aed; }
.section-header--green  .section-header__icon { background: #d1fae5; color: #059669; }
.section-header--amber  .section-header__icon { background: #fef3c7; color: #d97706; }
.section-header--red    .section-header__icon { background: #fee2e2; color: #dc2626; }
.section-header__icon svg { width: 18px; height: 18px; }

.section-header__text h3 {
  font-size: 15px; font-weight: 600; color: #111827; margin: 0; line-height: 1.3;
}
.section-header__text p {
  font-size: 13px; color: #6b7280; margin: 2px 0 0; line-height: 1.4;
}

.section-body {
  padding: 20px;
  display: flex; flex-direction: column; gap: 20px;
}

/* ===== Form Row (Switch) ===== */
.form-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  background: #f9fafb;
  border-radius: 10px;
  border: 1px solid #f3f4f6;
}
.form-row--bordered {
  border-top: 1px solid #e5e7eb;
  border-radius: 0;
  background: transparent;
  margin: 0 -20px;
  padding: 16px 20px;
}
.form-row__label { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.form-row__action { flex-shrink: 0; margin-left: 16px; }

/* ===== Form Field ===== */
.form-field {
  display: flex; flex-direction: column; gap: 6px;
}
.form-field__header {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}

/* ===== Form Typography ===== */
.form-label {
  font-size: 14px; font-weight: 500; color: #1f2937; line-height: 1.4;
}
.form-hint {
  font-size: 13px; color: #6b7280; line-height: 1.5;
}
.form-hint--inline {
  font-size: 12px; color: #9ca3af;
}
.form-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 32px; padding: 2px 8px;
  background: #eff6ff; color: #2563eb;
  border-radius: 6px; font-size: 13px; font-weight: 600;
}

/* ===== Form Inputs ===== */
.form-input, .form-textarea {
  width: 100%;
  padding: 10px 14px;
  font-size: 14px; line-height: 1.5;
  color: #1f2937;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  outline: none;
  transition: all 0.15s ease;
}
.form-input:focus, .form-textarea:focus {
  background: #fff;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.form-input::placeholder, .form-textarea::placeholder {
  color: #9ca3af;
}
.form-textarea--mono {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}

/* ===== Toggle Switch ===== */
.toggle {
  position: relative; display: inline-block; cursor: pointer;
  flex-shrink: 0;
}
.toggle input { position: absolute; opacity: 0; width: 0; height: 0; }
.toggle__track {
  display: block; width: 48px; height: 28px;
  background: #d1d5db; border-radius: 14px;
  transition: background 0.2s ease;
  position: relative;
}
.toggle__thumb {
  position: absolute; top: 3px; left: 3px;
  width: 22px; height: 22px;
  background: #fff; border-radius: 50%;
  box-shadow: 0 1px 3px rgba(0,0,0,0.15);
  transition: transform 0.2s ease;
}
.toggle input:checked + .toggle__track { background: #3b82f6; }
.toggle input:checked + .toggle__track .toggle__thumb { transform: translateX(20px); }

/* ===== Slider ===== */
.slider { padding: 4px 0; }
.slider__input {
  width: 100%; height: 6px;
  -webkit-appearance: none; appearance: none;
  background: #e5e7eb; border-radius: 3px;
  outline: none; cursor: pointer;
}
.slider__input::-webkit-slider-thumb {
  -webkit-appearance: none; appearance: none;
  width: 20px; height: 20px;
  background: #3b82f6; border-radius: 50%;
  border: 3px solid #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.2);
  cursor: pointer;
}
.slider__marks {
  display: flex; justify-content: space-between;
  font-size: 11px; color: #9ca3af;
  margin-top: 4px; padding: 0 2px;
}

/* ===== Variable Tags ===== */
.var-tags {
  display: flex; flex-wrap: wrap; gap: 6px;
}
.var-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 10px;
  background: #f3f4f6; border-radius: 6px;
  font-size: 12px; color: #4b5563;
}
.var-tag code {
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 11px;
  background: #e5e7eb; padding: 1px 4px; border-radius: 3px;
  color: #7c3aed;
}

/* ===== Preset Grid ===== */
.preset-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 4px;
}
.preset-item {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 12px 14px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.preset-item:hover {
  border-color: #d1d5db;
  background: #f3f4f6;
}
.preset-item--active {
  border-color: #3b82f6;
  background: #eff6ff;
}
.preset-checkbox {
  width: 18px; height: 18px;
  margin-top: 2px; flex-shrink: 0;
  accent-color: #3b82f6;
  cursor: pointer;
}
.preset-item__content {
  display: flex; flex-direction: column; gap: 2px; min-width: 0;
}
.preset-item__label {
  font-size: 14px; font-weight: 500; color: #1f2937;
}
.preset-item__desc {
  font-size: 12px; color: #6b7280; line-height: 1.4;
}

/* ===== Persona Preview ===== */
.persona-preview {
  display: flex; align-items: center; gap: 16px;
  padding: 16px 20px;
  background: linear-gradient(135deg, #faf5ff 0%, #f9fafb 100%);
  border: 1px solid #ede9fe;
  border-radius: 12px;
}
.persona-preview__avatar {
  position: relative; width: 56px; height: 56px; flex-shrink: 0;
}
.persona-preview__avatar img {
  width: 56px; height: 56px; border-radius: 50%;
  object-fit: cover; border: 2px solid #e9d5ff;
}
.persona-preview__avatar-fallback {
  width: 56px; height: 56px; border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #6d28d9);
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 20px; font-weight: 700;
}
.persona-preview__status {
  position: absolute; bottom: 0; right: 0;
  width: 14px; height: 14px;
  background: #22c55e; border-radius: 50%;
  border: 2.5px solid #fff;
}
.persona-preview__name {
  font-size: 15px; font-weight: 600; color: #1f2937;
}
.persona-preview__role {
  font-size: 13px; color: #6b7280; margin-top: 2px;
}

/* ===== Persona List & Card ===== */
.persona-empty {
  padding: 32px 0; text-align: center; color: #9ca3af;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.persona-empty svg { width: 32px; height: 32px; }
.persona-empty span { font-size: 14px; }

.persona-list {
  display: flex; flex-direction: column; gap: 10px;
}

.persona-card {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 16px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  transition: all 0.15s ease;
}
.persona-card:hover {
  border-color: #d1d5db;
  background: #f3f4f6;
}

.persona-card__avatar {
  width: 44px; height: 44px; flex-shrink: 0;
  position: relative;
}
.persona-card__avatar img {
  width: 44px; height: 44px; border-radius: 50%;
  object-fit: cover; border: 2px solid #e9d5ff;
}
.persona-card__avatar-fallback {
  width: 44px; height: 44px; border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #6d28d9);
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 16px; font-weight: 700;
}

.persona-card__info {
  flex: 1; min-width: 0;
}
.persona-card__name {
  font-size: 14px; font-weight: 600; color: #1f2937;
  display: flex; align-items: center; gap: 6px;
}
.persona-card__badge {
  display: inline-flex; align-items: center;
  padding: 1px 8px; font-size: 11px; font-weight: 500;
  background: #dbeafe; color: #2563eb; border-radius: 4px;
}
.persona-card__prompt {
  font-size: 12px; color: #6b7280; margin-top: 2px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 400px;
}

.persona-card__actions {
  display: flex; gap: 4px; flex-shrink: 0;
}

.btn-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  background: none; border: 1px solid transparent;
  cursor: pointer; color: #6b7280; transition: all 0.15s;
}
.btn-icon:hover {
  background: #f3f4f6; border-color: #e5e7eb; color: #374151;
}
.btn-icon svg { width: 16px; height: 16px; }
.btn-icon--danger:hover {
  background: #fef2f2; border-color: #fecaca; color: #dc2626;
}

/* ===== Persona Dialog Preview ===== */
.persona-dialog-preview {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #faf5ff 0%, #f9fafb 100%);
  border: 1px solid #ede9fe;
  border-radius: 10px;
}

/* ===== Button Link ===== */
.btn-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 13px; font-weight: 500; color: #3b82f6;
  background: none; border: none; cursor: pointer;
  padding: 0; transition: color 0.15s;
}
.btn-link:hover { color: #2563eb; }
.btn-link svg { width: 16px; height: 16px; }

/* ===== Cleanup Result ===== */
.cleanup-result {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px;
  background: #ecfdf5; color: #059669;
  border-radius: 8px; font-size: 13px; font-weight: 500;
}
.cleanup-result svg { width: 16px; height: 16px; }

/* ===== Sidebar ===== */
.settings-sidebar {
  position: sticky; top: 80px;
}
.settings-sidebar__inner {
  display: flex; flex-direction: column; gap: 16px;
}
.sidebar-card {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
  padding: 20px;
}
.sidebar-card__title {
  font-size: 14px; font-weight: 600; color: #1f2937;
  margin: 0 0 14px; padding-bottom: 10px;
  border-bottom: 1px solid #f3f4f6;
}
.sidebar-card__actions {
  display: flex; flex-direction: column; gap: 8px;
}
.sidebar-card__vars {
  display: flex; flex-direction: column; gap: 8px;
}
.sidebar-var {
  display: flex; flex-direction: column; gap: 1px;
}
.sidebar-var code {
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 11px; background: #f3f4f6;
  padding: 2px 6px; border-radius: 4px;
  color: #7c3aed; display: inline-block; width: fit-content;
}
.sidebar-var span {
  font-size: 12px; color: #6b7280;
}

/* ===== Dialog ===== */
.dialog-overlay {
  position: fixed; inset: 0; z-index: 50;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,0.4); backdrop-filter: blur(2px);
}
.dialog {
  width: 100%; max-width: 480px;
  background: #fff; border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
  overflow: hidden;
}
.dialog__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px 24px 0;
}
.dialog__header h3 { font-size: 16px; font-weight: 600; color: #111827; margin: 0; }
.dialog__close {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  background: none; border: none; cursor: pointer;
  color: #9ca3af; transition: all 0.15s;
}
.dialog__close:hover { background: #f3f4f6; color: #374151; }
.dialog__close svg { width: 20px; height: 20px; }

.dialog__search {
  padding: 16px 24px; position: relative;
}
.dialog__search-icon {
  position: absolute; left: 38px; top: 50%; transform: translateY(-50%);
  width: 16px; height: 16px; color: #9ca3af;
}
.dialog__search-input {
  width: 100%; padding: 10px 14px 10px 38px;
  font-size: 14px; border: 1px solid #e5e7eb;
  border-radius: 10px; outline: none; background: #f9fafb;
  transition: all 0.15s;
}
.dialog__search-input:focus {
  background: #fff; border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59,130,246,0.1);
}

.dialog__body {
  max-height: 320px; overflow-y: auto; padding: 0 16px 16px;
}
.dialog__empty {
  padding: 40px 0; text-align: center; color: #9ca3af;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.dialog__empty svg { width: 32px; height: 32px; }
.dialog__empty span { font-size: 14px; }

.dialog__list { display: flex; flex-direction: column; gap: 2px; }
.dialog__item {
  display: flex; align-items: center; gap: 12px;
  width: 100%; padding: 10px 12px;
  background: none; border: none; border-radius: 10px;
  cursor: pointer; text-align: left; transition: background 0.15s;
}
.dialog__item:hover { background: #eff6ff; }
.dialog__item-avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: #f3f4f6; display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600; color: #6b7280; flex-shrink: 0;
}
.dialog__item-info { min-width: 0; flex: 1; }
.dialog__item-name { font-size: 14px; font-weight: 500; color: #1f2937; }
.dialog__item-email { font-size: 12px; color: #9ca3af; margin-top: 1px; }
.dialog__item-add { width: 18px; height: 18px; color: #3b82f6; flex-shrink: 0; }
</style>
