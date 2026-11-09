export type BossSelections = Record<'industry' | 'experience' | 'jobType' | 'degree' | 'scale' | 'stage' | 'salary', string[]>

export type BossFormConfig = {
  debugger?: number
  keywords?: string
  jobType?: string
  sayHi?: string
  maxSearchJobs?: number | null
  maxDeliveryJobs?: number | null
}

export function toggleBossChoice(selected: string[], code: string) {
  if (code === '0') return selected.includes('0') ? [] : ['0']
  const specific = selected.filter(value => value !== '0')
  return specific.includes(code) ? specific.filter(value => value !== code) : [...specific, code]
}

export function buildBossPayload<T extends BossFormConfig>(config: T, keywords: string, selections: BossSelections) {
  const lists = Object.fromEntries(Object.entries(selections).map(([key, values]) => [key, values.length ? `[${values.join(',')}]` : '']))
  return { ...config, ...lists, keywords, maxSearchJobs: config.maxSearchJobs ?? 0, maxDeliveryJobs: config.maxDeliveryJobs ?? 0 }
}

export function bossPayloadKey(payload: object) {
  return JSON.stringify(Object.fromEntries(Object.entries(payload).sort(([a], [b]) => a.localeCompare(b))))
}

export function bossFormIssues(payload: BossFormConfig, forDelivery = false) {
  const issues: Record<string, string> = {}
  if (payload.jobType !== undefined) {
    const jobTypes = payload.jobType.replace(/^\[|\]$/g, '').split(',').map(value => value.trim()).filter(Boolean)
    if (jobTypes.length !== 1) issues.jobType = '职位类型有且仅能选择一个。'
  }
  for (const [field, label] of [['maxSearchJobs', '搜索岗位上限'], ['maxDeliveryJobs', '本次投递上限']] as const) {
    const value = payload[field] ?? 0
    if (!Number.isInteger(value) || value < 0 || value > 2147483647) issues[field] = `${label}需为 0 或正整数。`
  }
  if (forDelivery) {
    const keywords = (payload.keywords || '').replace(/，/g, ',').replace(/^\[|\]$/g, '').split(',').map(value => value.trim().replace(/^"|"$/g, '').trim())
    if (!keywords.some(Boolean)) issues.keywords = '请填写至少一个搜索关键词。'
    if (Number(payload.debugger) !== 1 && !payload.sayHi?.trim()) issues.sayHi = '请填写固定招呼语，AI 模式也需要备用内容。'
  }
  return issues
}
