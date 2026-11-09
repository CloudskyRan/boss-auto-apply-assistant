import { API_BASE } from './api'

export type ExamSettings = {
  processName: string
  excludeFromCapture: boolean
}

export type ExamDesktopStatus = {
  supported: boolean
  running: boolean
  processName: string | null
  pid: number | null
  excludeFromCapture: boolean
  captureProtectionApplied: boolean
  message: string | null
  question: string
  answer: string
  busy: boolean
}

export const EXAM_QUESTION_LIMIT = 20_000
export const EXAM_IMAGE_LIMIT = 5 * 1024 * 1024

export function validateExamProcessName(name: string): string | null {
  if (name.length < 1 || name.length > 48 || !/^[A-Za-z]/.test(name) || /[^A-Za-z0-9_-]/.test(name)) {
    return '填写 1–48 位字母、数字、下划线或短横线，以英文字母开头，不含 .exe。'
  }
  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(name)) {
    return '这个名称是 Windows 保留名称，请换一个名称。'
  }
  return null
}

export function validateExamImage(file: Pick<File, 'size' | 'type'>): string | null {
  if (!['image/png', 'image/jpeg'].includes(file.type)) return '请选择 PNG 或 JPEG 图片。'
  if (file.size === 0) return '图片为空，请重新选择。'
  if (file.size > EXAM_IMAGE_LIMIT) return '图片大小不能超过 5 MB。'
  return null
}

export function readExamSettings(value: unknown): ExamSettings {
  const settings = value as Partial<ExamSettings> | null
  if (!settings || typeof settings.processName !== 'string' || typeof settings.excludeFromCapture !== 'boolean') {
    throw new Error('设置返回格式不正确，请刷新后重试。')
  }
  return { processName: settings.processName, excludeFromCapture: settings.excludeFromCapture }
}

export function readExamDesktopStatus(value: unknown): ExamDesktopStatus {
  const status = value as Partial<ExamDesktopStatus> | null
  if (!status || typeof status.supported !== 'boolean' || typeof status.running !== 'boolean'
    || typeof status.excludeFromCapture !== 'boolean' || typeof status.captureProtectionApplied !== 'boolean'
    || typeof status.question !== 'string' || typeof status.answer !== 'string' || typeof status.busy !== 'boolean'
    || (status.processName !== null && typeof status.processName !== 'string')
    || (status.pid !== null && (typeof status.pid !== 'number' || !Number.isInteger(status.pid)))
    || (status.message !== null && typeof status.message !== 'string')) {
    throw new Error('桌面状态返回格式不正确，请刷新后重试。')
  }
  return status as ExamDesktopStatus
}

export function readExamText(value: unknown, key: 'text' | 'answer'): string {
  const result = value as Record<string, unknown> | null
  const text = result?.[key]
  if (typeof text !== 'string' || !text.trim()) {
    throw new Error(key === 'text' ? '没有识别到文字，请使用更清晰的图片或手动输入。' : 'AI 没有返回解析，请重试。')
  }
  if (key === 'text' && text.length > EXAM_QUESTION_LIMIT) {
    throw new Error(`识别结果超过 ${EXAM_QUESTION_LIMIT.toLocaleString()} 字符，请缩小图片范围后重试。`)
  }
  return text
}

export async function requestExamJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}/api/exam${path}`, init)
  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new Error(response.ok ? '服务返回内容无法读取，请重试。' : `请求未完成（HTTP ${response.status}），请检查本地服务。`)
  }
  if (!response.ok) {
    const message = (body as { message?: unknown } | null)?.message
    throw new Error(typeof message === 'string' && message.trim() ? message : `请求未完成（HTTP ${response.status}）。`)
  }
  return body as T
}
