export const ANALYSIS_STATUSES = ["未投递", "已投递", "已过滤", "投递失败"]

export type AnalysisFilters = {
  statuses: string[]
  location: string
  experience: string
  degree: string
  minK: string
  maxK: string
  keyword: string
  filterHeadhunter: boolean
}

export const EMPTY_ANALYSIS_FILTERS: AnalysisFilters = {
  statuses: [], location: "", experience: "", degree: "", minK: "", maxK: "", keyword: "", filterHeadhunter: false,
}

export function normalizeAnalysisFilters(filters: AnalysisFilters): AnalysisFilters {
  return {
    statuses: ANALYSIS_STATUSES.filter(status => filters.statuses.includes(status)),
    location: filters.location.trim(),
    experience: filters.experience.trim(),
    degree: filters.degree.trim(),
    minK: filters.minK.trim(),
    maxK: filters.maxK.trim(),
    keyword: filters.keyword.trim(),
    filterHeadhunter: filters.filterHeadhunter,
  }
}

export function validateAnalysisFilters(filters: AnalysisFilters): string | null {
  const { minK, maxK } = normalizeAnalysisFilters(filters)
  if ([minK, maxK].some(value => value !== "" && (!Number.isFinite(Number(value)) || Number(value) < 0))) {
    return "薪资请填写大于或等于 0 的有效数字，留空表示不限。"
  }
  if (minK !== "" && maxK !== "" && Number(minK) > Number(maxK)) {
    return "最低月薪不能高于最高月薪，请调整薪资范围。"
  }
  return null
}

export function analysisFilterParams(filters: AnalysisFilters): URLSearchParams {
  const normalized = normalizeAnalysisFilters(filters)
  const params = new URLSearchParams()
  if (normalized.statuses.length) params.set("statuses", normalized.statuses.join(","))
  for (const key of ["location", "experience", "degree", "keyword"] as const) {
    if (normalized[key]) params.set(key, normalized[key])
  }
  for (const key of ["minK", "maxK"] as const) {
    if (normalized[key] !== "") params.set(key, String(Number(normalized[key])))
  }
  if (normalized.filterHeadhunter) params.set("filterHeadhunter", "true")
  return params
}

export function analysisFiltersEqual(left: AnalysisFilters, right: AnalysisFilters): boolean {
  return analysisFilterParams(left).toString() === analysisFilterParams(right).toString()
}

export function clampAnalysisPage(value: number | string, maxPage: number): number {
  const parsed = Number(value)
  return Math.min(Math.max(1, Math.trunc(maxPage)), Math.max(1, Number.isFinite(parsed) ? Math.trunc(parsed) : 1))
}

export function csvCell(value: unknown): string {
  const text = String(value ?? "")
  return /[,"\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}
