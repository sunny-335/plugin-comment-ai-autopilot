/**
 * 统一 API 路径常量，避免硬编码散布在多个 Vue 文件中。
 * 修改 API 路径只需在此处更新。
 */
export const API_BASE = '/apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1'

// ===== 日志相关 =====
export const API_REPLIES = `${API_BASE}/replies`
export const apiReply = (name: string) => `${API_REPLIES}/${name}`
export const apiReplyAction = (name: string, action: string) => `${API_REPLIES}/${name}/${action}`
export const API_BATCH_APPROVE = `${API_REPLIES}/batch-approve`
export const API_BATCH_REJECT = `${API_REPLIES}/batch-reject`
export const API_BATCH_DELETE = `${API_REPLIES}/batch-delete`
export const apiConversation = (commentId: string) => `${API_BASE}/conversation/${commentId}`

// ===== 概览相关 =====
export const API_STATS = `${API_BASE}/stats`
export const API_PERSONAS = `${API_BASE}/personas`
export const apiPersona = (name: string) => `${API_PERSONAS}/${name}`
export const API_HEALTH = `${API_BASE}/health`

// ===== 设置相关 =====
export const API_EXPORT = `${API_BASE}/export`
export const API_IMPORT = `${API_BASE}/import`
export const API_COMMENTERS = `${API_BASE}/commenters`
export const API_CLEANUP = `${API_BASE}/cleanup`

// ===== 评论触发 =====
export const apiCommentTrigger = (commentName: string) => `${API_BASE}/comments/${commentName}/trigger`
