import { API_BASE } from '@/lib/api'

export interface JdScope {
  keyword: string
  city: string
  jobType: string
  from: string
  to: string
  includeHistory: boolean
  jobIds?: number[]
}

export const emptyJdScope: JdScope = { keyword: '', city: '', jobType: '', from: '', to: '', includeHistory: false }

export interface JdEvidence { jobId: number; versionId: number; quote: string }
export interface JdTerm { term: string; count: number; total: number; evidence?: JdEvidence[] }
export interface JdRequirement extends JdTerm { category: string }
export interface JdJob {
  jobId: number; versionId?: number; jobName: string; companyName: string; city?: string
  salary?: string; experience?: string; degree?: string; jobType?: string; description?: string
  firstSeen?: string; lastSeen?: string; source?: string; matchedAt?: string; jobUrl?: string
  excluded?: boolean; included?: boolean | number; requestedJobType?: string; currentVersionId?: number; analysisStatus?: string
}
export interface JdVersion {
  versionId: number; description: string; createdAt?: string; capturedAt?: string; jobName?: string; companyName?: string
  contentHash?: string; [key: string]: unknown
}
export interface JdMatch {
  keyword?: string; searchCity?: string; jobType?: string; source?: string; matchedAt?: string
  lastSeen?: string; lastMatchedAt?: string; versionId?: number; matchStatus?: string; matched?: boolean; configJson?: string; config?: Record<string, unknown>; requestedJobType?: string
  [key: string]: unknown
}
export interface JdDetail { job: JdJob; versions: JdVersion[]; matches: JdMatch[] }
export interface JdOverview {
  totalJobs: number; totalCompanies: number; duplicateContents: number; terms: JdTerm[]
  available?: boolean; error?: string
}
export interface JdSettings { autoEnabled: boolean; batchSize: number; maxCalls: number }
export interface JdReport {
  id?: string; taskId?: string; status: string; createdAt?: string; scope?: JdScope
  sampleCount?: number; analyzedCount?: number; failedCount?: number; processedCount?: number
  totalCount?: number; model?: string; stale?: boolean; requirements?: JdRequirement[]
  recommendations?: Array<string | { text: string; term?: string; evidence?: JdEvidence[] }> | string; error?: string; message?: string
  [key: string]: unknown
}

export function jdScopeQuery(scope: JdScope): URLSearchParams {
  const query = new URLSearchParams()
  for (const key of ['keyword', 'city', 'jobType', 'from', 'to'] as const) {
    if (scope[key].trim()) query.set(key, scope[key].trim())
  }
  query.set('includeHistory', String(scope.includeHistory))
  if (scope.jobIds?.length) query.set('jobIds', scope.jobIds.join(','))
  return query
}

export function jdScopeIssue(scope: JdScope): string | null {
  if (scope.from && scope.to && scope.from > scope.to) return '开始日期不能晚于结束日期。'
  if (scope.keyword.trim().length > 100 || scope.city.trim().length > 100) return '关键词或城市过长。'
  return null
}

export function jdStatusLabel(status?: string): string {
  const labels: Record<string, string> = {
    queued: '等待处理', pending: '等待处理', running: '分析中', processing: '处理中',
    completed: '已完成', complete: '已完成', succeeded: '已完成', success: '已完成',
    partial: '部分完成', failed: '处理失败', error: '处理失败', cancelled: '已取消', canceled: '已取消',
    paused: '已暂停', matched: '筛选通过', historical: '历史待确认', history: '历史待确认', manual: '人工纳入', unconfirmed: '历史待确认',
  }
  return labels[String(status || '').toLowerCase()] || status || '未分析'
}

export function jdTaskRunning(status?: string): boolean {
  return ['queued', 'pending', 'running', 'processing'].includes(String(status || '').toLowerCase())
}

export function jdJobType(value?: string): string {
  return ({ '0': '不限', '1901': '全职', '1902': '实习', '1903': '兼职', unknown: '未核验' } as Record<string, string>)[value || ''] || value || '未核验'
}

export function jdDate(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

export async function jdRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  init?.signal?.addEventListener('abort', abort, { once: true })
  if (init?.signal?.aborted) controller.abort()
  const timeout = setTimeout(abort, 25_000)
  try {
    const response = await fetch(`${API_BASE}/api/boss/jd${path}`, {
      cache: 'no-store', ...init, signal: controller.signal,
      headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers },
    })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(typeof body?.message === 'string' ? body.message : `请求失败（${response.status}）`)
    if (body === null) throw new Error('服务返回的内容不完整。')
    return body as T
  } catch (error) {
    if (controller.signal.aborted && !init?.signal?.aborted) throw new Error('请求超时，请稍后重试。')
    throw error
  } finally {
    clearTimeout(timeout)
    init?.signal?.removeEventListener('abort', abort)
  }
}

export async function downloadJd(scope: JdScope, format: 'json' | 'markdown', signal?: AbortSignal, reportId?: string) {
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) controller.abort()
  const timeout = setTimeout(abort, 30_000)
  try {
  const query = jdScopeQuery(scope)
  query.set('format', format)
  if (reportId) query.set('reportId', reportId)
  const response = await fetch(`${API_BASE}/api/boss/jd/export?${query}`, { signal: controller.signal, cache: 'no-store' })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new Error(error?.message || `导出失败（${response.status}）`)
  }
  const blob = await response.blob()
  if (controller.signal.aborted) return
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = `mitu-jd-${new Date().toISOString().slice(0, 10)}${reportId ? '-report' : ''}.${format === 'json' ? 'json' : 'md'}`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  } catch (error) {
    if (controller.signal.aborted && !signal?.aborted) throw new Error('导出超时，请缩小范围后重试。')
    throw error
  } finally {
    clearTimeout(timeout)
    signal?.removeEventListener('abort', abort)
  }
}
