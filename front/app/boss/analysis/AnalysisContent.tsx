"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import Chart from "chart.js/auto"
import { useTheme } from "next-themes"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Label } from "@/components/ui/label"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import PageHeader from "@/app/components/PageHeader"
import { API_BASE } from "@/lib/api"
import { useModalFocus } from "@/lib/use-modal-focus"
import { ANALYSIS_STATUSES, EMPTY_ANALYSIS_FILTERS, analysisFilterParams, analysisFiltersEqual, clampAnalysisPage, csvCell, normalizeAnalysisFilters, validateAnalysisFilters, type AnalysisFilters } from "./filters"
import { BiRefresh, BiDownload, BiBarChart, BiLineChart, BiPieChart, BiBriefcase, BiSearch, BiX, BiLinkExternal, BiChevronRight, BiReset } from "react-icons/bi"

type NameValue = { name: string; value: number }
type BucketValue = { bucket: string; value: number }

type StatsResponse = {
  kpi: {
    total: number
    delivered: number
    pending: number
    filtered: number
    failed: number
    avgMonthlyK?: number | null
  }
  charts: {
    byStatus: NameValue[]
    byCity: NameValue[]
    byIndustry: NameValue[]
    byCompany: NameValue[]
    byExperience: NameValue[]
    byDegree: NameValue[]
    salaryBuckets: BucketValue[]
    dailyTrend: NameValue[]
    hrActivity: NameValue[]
  }
}

type BossJob = {
  id: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  hrPosition?: string
  hrActiveStatus?: string
  deliveryStatus?: string
  jobUrl?: string
  recruitmentStatus?: string
  companyAddress?: string
  industry?: string
  introduce?: string
  financingStage?: string
  companyScale?: string
  jobDescription?: string
  createdAt?: string
}

type PagedResult = {
  items: BossJob[]
  total: number
  page: number
  size: number
}

// 图表随应用打包，不再依赖外部 CDN。
const CATEGORY_COLORS = ["#5b4df5", "#26b9eb", "#ff7a59", "#f5b23d", "#db5dca", "#4187f4", "#9d6cf8", "#45cde8", "#f36c9a", "#dda01e"]
const DARK_CATEGORY_COLORS = ["#a99bff", "#66d4f7", "#ffac94", "#ffd078", "#ec94dd", "#85b4ff", "#c4a2ff", "#85e5f8", "#ffa0c2", "#f7d57f"]

function ChartCanvas({
  type,
  labels,
  data,
  title,
  horizontal = false,
}: {
  type: "pie" | "bar" | "line"
  labels: string[]
  data: number[]
  title: string
  horizontal?: boolean
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const { resolvedTheme } = useTheme()

  useEffect(() => {
    if (!canvasRef.current) return
    const theme = getComputedStyle(document.documentElement)
    const token = (name: string) => theme.getPropertyValue(`--${name}`).trim()
    const primary = `hsl(${token("primary")})`
    const primaryForeground = `hsl(${token("primary-foreground")})`
    const mutedForeground = `hsl(${token("muted-foreground")})`
    const border = `hsl(${token("border")})`
    const card = `hsl(${token("card")})`
    const categoryColors = resolvedTheme === "dark" ? DARK_CATEGORY_COLORS : CATEGORY_COLORS
    const chart = new Chart(canvasRef.current, {
      type,
      data: {
        labels,
        datasets: [{
          label: title,
          data,
          backgroundColor: type === "line" ? `hsla(${token("primary").split(/\s+/).join(", ")}, 0.08)` : labels.map((_, index) => categoryColors[index % categoryColors.length]),
          borderColor: type === "pie" ? card : primary,
          borderWidth: type === "line" ? 2 : type === "pie" ? 3 : 0,
          borderRadius: type === "bar" ? 5 : 0,
          maxBarThickness: 32,
          pointRadius: 3,
          pointHoverRadius: 5,
          pointBackgroundColor: primary,
          fill: type === "line",
          tension: 0.25,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: horizontal && type === "bar" ? "y" : "x",
        plugins: {
          legend: {
            display: type === "pie",
            position: "bottom",
            labels: { color: mutedForeground, usePointStyle: true, pointStyle: "circle", boxWidth: 8, padding: 18, font: { size: 11 } },
          },
          tooltip: { backgroundColor: primary, titleColor: primaryForeground, bodyColor: primaryForeground, padding: 12, cornerRadius: 8 },
        },
        scales: type === "pie" ? undefined : {
          x: {
            beginAtZero: horizontal,
            grid: { display: horizontal, color: border },
            border: { display: false },
            ticks: { color: mutedForeground, maxTicksLimit: 8, autoSkip: true, precision: 0, font: { size: 11 } },
          },
          y: {
            beginAtZero: true,
            grid: { display: !horizontal, color: border },
            border: { display: false },
            ticks: { color: mutedForeground, precision: 0, font: { size: 11 } },
          },
        },
      },
    })
    return () => chart.destroy()
  }, [type, labels, data, title, horizontal, resolvedTheme])

  return (
    <div className="relative h-64 min-w-0">
      <canvas ref={canvasRef} role="img" aria-label={title} />
    </div>
  )
}

function DistributionCard({
  title,
  description,
  rows,
  type = "bar",
  horizontal = false,
  loading,
  unavailable,
  wide = false,
}: {
  title: string
  description: string
  rows?: NameValue[]
  type?: "pie" | "bar" | "line"
  horizontal?: boolean
  loading: boolean
  unavailable: boolean
  wide?: boolean
}) {
  const hasData = rows?.some(row => row.value > 0) ?? false
  return (
    <Card className={"min-w-0 " + (wide ? "xl:col-span-2" : "")}>
      <CardHeader className="pb-3">
        <CardTitle className="text-base text-foreground">{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        {hasData && rows ? (
          <ChartCanvas type={type} title={title} labels={rows.map(row => row.name)} data={rows.map(row => row.value)} horizontal={horizontal} />
        ) : (
          <div className="flex h-64 flex-col items-center justify-center gap-3 rounded-xl bg-background px-5 text-center">
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-card text-primary">
              {type === "line" ? <BiLineChart size={21} /> : type === "pie" ? <BiPieChart size={21} /> : <BiBarChart size={21} />}
            </span>
            <p className="text-sm font-medium text-muted-foreground">{loading ? "正在整理数据…" : unavailable ? "数据暂未加载" : "暂无可展示的数据"}</p>
            <p className="max-w-[240px] text-xs leading-5 text-muted-foreground">
              {loading ? "统计将根据当前筛选条件更新。" : unavailable ? "请检查本地服务并刷新页面。" : "调整筛选条件，或完成一次岗位搜索后查看。"}
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

export default function AnalysisContent({ showHeader = false }: { showHeader?: boolean }) {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [statsError, setStatsError] = useState<string | null>(null)

  const [items, setItems] = useState<BossJob[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  // 分页输入（页码与每页条数），便于自定义跳转与控制
  const [inputPage, setInputPage] = useState<number | string>(1)

  const [draftFilters, setDraftFilters] = useState<AnalysisFilters>(EMPTY_ANALYSIS_FILTERS)
  const [appliedFilters, setAppliedFilters] = useState<AnalysisFilters>(EMPTY_ANALYSIS_FILTERS)
  const appliedFiltersRef = useRef<AnalysisFilters>(EMPTY_ANALYSIS_FILTERS)
  const [filterError, setFilterError] = useState<string | null>(null)
  const [loadingList, setLoadingList] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  const [reloading, setReloading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)
  const listRequest = useRef({ id: 0, controller: null as AbortController | null })
  const statsRequest = useRef({ id: 0, controller: null as AbortController | null })
  const exportController = useRef<AbortController | null>(null)
  const { statuses, location, experience, degree, minK, maxK, keyword, filterHeadhunter } = draftFilters
  const updateDraft = <K extends keyof AnalysisFilters>(key: K, value: AnalysisFilters[K]) => {
    setDraftFilters(current => ({ ...current, [key]: value }))
    setFilterError(null)
  }

  // 查看全文弹窗
  const [showTextDialog, setShowTextDialog] = useState(false)
  const [textDialogTitle, setTextDialogTitle] = useState<string>("")
  const [textDialogContent, setTextDialogContent] = useState<string>("")
  const textAreaRef = useRef<HTMLTextAreaElement | null>(null)
  const [selectedJob, setSelectedJob] = useState<BossJob | null>(null)

  useModalFocus(showTextDialog ? "mitu-text-dialog" : selectedJob ? "mitu-job-details" : null, () => {
    if (showTextDialog) setShowTextDialog(false)
    else setSelectedJob(null)
  })

  const openTextDialog = (title: string, content?: string) => {
    setTextDialogTitle(title)
    setTextDialogContent(content || "")
    setShowTextDialog(true)
  }

  const selectDialogText = () => {
    const el = textAreaRef.current
    if (el) el.select()
  }

  const copyDialogText = async () => {
    try {
      await navigator.clipboard.writeText(textDialogContent || "")
      alert("已复制到剪贴板")
    } catch (e) {
      try {
        const ta = document.createElement("textarea")
        ta.value = textDialogContent || ""
        document.body.appendChild(ta)
        ta.select()
        document.execCommand("copy")
        document.body.removeChild(ta)
        alert("已复制到剪贴板")
      } catch (e2) {
        alert("复制失败，请手动选中复制")
      }
    }
  }

  useEffect(() => {
    loadList(1, 20, EMPTY_ANALYSIS_FILTERS)
    loadStats(EMPTY_ANALYSIS_FILTERS)
    return () => {
      listRequest.current.id += 1
      statsRequest.current.id += 1
      listRequest.current.controller?.abort()
      statsRequest.current.controller?.abort()
      exportController.current?.abort()
    }
  }, [])

  // 当实际页码/每页条数变化时，同步到输入框
  useEffect(() => {
    setInputPage(page)
  }, [page])

  // 仅显示日期（YYYY-MM-DD）
  const formatDateOnly = (s?: string) => {
    if (!s) return ""
    const d = new Date(s)
    if (!isNaN(d.getTime())) {
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, "0")
      const day = String(d.getDate()).padStart(2, "0")
      return `${y}-${m}-${day}`
    }
    // 非标准时间串，兜底截取前10位
    return s.slice(0, 10)
  }

  const loadList = async (toPage = page, toSize = size, filters = appliedFiltersRef.current) => {
    listRequest.current.controller?.abort()
    const controller = new AbortController()
    const requestId = ++listRequest.current.id
    listRequest.current.controller = controller
    const params = analysisFilterParams(filters)
    params.set("page", String(toPage))
    params.set("size", String(toSize))

    try {
      setLoadingList(true)
      setListError(null)
      const res = await fetch(`${API_BASE}/api/boss/list?${params.toString()}`, { signal: controller.signal })
      if (!res.ok) throw new Error("岗位记录加载失败")
      const data: PagedResult = await res.json()
      if (!Array.isArray(data.items) || !Number.isFinite(data.total)) throw new Error("岗位记录响应无效")
      if (controller.signal.aborted || requestId !== listRequest.current.id) return
      const lastPage = Math.max(1, Math.ceil(data.total / toSize))
      if (toPage > lastPage) return await loadList(lastPage, toSize, filters)
      setItems(data.items)
      setTotal(data.total || 0)
      setPage(data.page || toPage)
      setSize(data.size || toSize)
    } catch {
      if (controller.signal.aborted || requestId !== listRequest.current.id) return
      setListError("岗位记录暂时无法加载，请检查本地服务后重试。")
    } finally {
      if (requestId === listRequest.current.id) setLoadingList(false)
    }
  }

  // 统计图加载：与列表共享相同筛选条件
  const loadStats = async (filters = appliedFiltersRef.current) => {
    statsRequest.current.controller?.abort()
    const controller = new AbortController()
    const requestId = ++statsRequest.current.id
    statsRequest.current.controller = controller
    const params = analysisFilterParams(filters)

    try {
      setLoadingStats(true)
      setStatsError(null)
      const res = await fetch(`${API_BASE}/api/boss/stats?${params.toString()}`, { signal: controller.signal })
      if (!res.ok) throw new Error("统计加载失败")
      const data: StatsResponse = await res.json()
      if (!data.kpi || !data.charts) throw new Error("统计响应无效")
      if (controller.signal.aborted || requestId !== statsRequest.current.id) return
      setStats(data)
    } catch {
      if (controller.signal.aborted || requestId !== statsRequest.current.id) return
      setStatsError("统计暂时无法加载，请检查本地服务后刷新。")
    } finally {
      if (requestId === statsRequest.current.id) setLoadingStats(false)
    }
  }

  const onReload = async () => {
    try {
      setReloading(true)
      await Promise.all([loadList(page, size), loadStats()])
    } finally {
      setReloading(false)
    }
  }

  const applyFilters = (filters = draftFilters) => {
    const error = validateAnalysisFilters(filters)
    if (error) {
      setFilterError(error)
      return
    }
    const normalized = normalizeAnalysisFilters(filters)
    setFilterError(null)
    setExportError(null)
    setDraftFilters(normalized)
    setAppliedFilters(normalized)
    appliedFiltersRef.current = normalized
    setStats(null)
    setItems([])
    setTotal(0)
    setPage(1)
    loadList(1, size, normalized)
    loadStats(normalized)
  }

  const exportCSV = async () => {
    if (exportController.current) return
    const controller = new AbortController()
    exportController.current = controller
    try {
      setExporting(true)
      setExportError(null)
      // 导出开始时固定已应用条件，不使用尚未提交的表单草稿。
      const baseParams = analysisFilterParams(appliedFiltersRef.current)

      // 分页抓取，直到获取全部数据
      const pageSize = 1000
      let currentPage = 1
      let all: BossJob[] = []
      let totalCount = 0

      while (true) {
        const params = new URLSearchParams(baseParams)
        params.set("page", String(currentPage))
        params.set("size", String(pageSize))
        const res = await fetch(`${API_BASE}/api/boss/list?${params.toString()}`, { signal: controller.signal })
        if (!res.ok) throw new Error("导出记录加载失败")
        const data: PagedResult = await res.json()
        if (!Array.isArray(data.items) || !Number.isFinite(data.total)) throw new Error("导出记录响应无效")
        const chunk = data.items
        if (currentPage === 1) totalCount = data.total
        all = all.concat(chunk)
        if (all.length >= totalCount) break
        if (chunk.length === 0) throw new Error("导出分页数据不完整")
        currentPage += 1
      }

      const header = [
        "公司名称",
        "岗位名称",
        "薪资",
        "工作地点",
        "经验",
        "学历",
        "HR",
        "投递状态",
        "链接",
        "创建时间",
      ]
      const rows = all.map((it) => [
        it.companyName || "",
        it.jobName || "",
        it.salary || "",
        it.location || "",
        it.experience || "",
        it.degree || "",
        it.hrName || "",
        it.deliveryStatus || "",
        it.jobUrl || "",
        it.createdAt || "",
      ])
      if (controller.signal.aborted) return
      const csv = [header, ...rows].map(row => row.map(csvCell).join(",")).join("\r\n")
      const blob = new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8;" })
      const url = URL.createObjectURL(blob)
      const a = document.createElement("a")
      a.href = url
      a.download = `boss_jobs_${new Date().toISOString().slice(0, 10)}.csv`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      if (!controller.signal.aborted) setExportError("CSV 导出失败，请检查本地服务后重试；未生成不完整文件。")
    } finally {
      exportController.current = null
      if (!controller.signal.aborted) setExporting(false)
    }
  }

  const badgeClass = (kind: "delivery" | "hr" | "recruitment", value?: string) => {
    const base = "inline-flex rounded-md px-2 py-1 text-xs font-medium whitespace-nowrap focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    const v = (value || "").trim()
    if (kind === "delivery") {
      if (v.includes("已投递")) return base + " bg-secondary text-foreground"
      if (v.includes("失败")) return base + " bg-destructive/10 text-destructive"
      if (v.includes("已过滤")) return base + " bg-muted text-muted-foreground"
      return base + " bg-muted text-muted-foreground"
    }
    if (kind === "hr" && /刚|在线|今日|小时|近/.test(v)) return base + " bg-secondary text-secondary-foreground"
    if (kind === "recruitment" && /招|招聘|中/.test(v)) return base + " bg-secondary text-foreground"
    return base + " bg-muted text-muted-foreground"
  }

  const kpiCards = useMemo(() => {
    const k = stats?.kpi
    return [
      { title: "记录岗位", value: k?.total, caption: "当前筛选下的岗位总数", tone: "dark" },
      { title: "已投递", value: k?.delivered, caption: "已保存的成功投递记录", tone: "lime" },
      { title: "待投递", value: k?.pending, caption: "状态为未投递的记录", tone: "white" },
      { title: "已过滤", value: k?.filtered, caption: "不符合条件的岗位", tone: "white" },
      { title: "投递失败", value: k?.failed, caption: "需要留意的失败记录", tone: "white" },
      { title: "平均月薪", value: k?.avgMonthlyK, caption: "可解析薪资中位数的均值", tone: "white", unit: "K" },
    ]
  }, [stats])

  const pageCount = Math.max(1, Math.ceil(total / size))
  const chartLoading = loadingStats && !stats
  const chartUnavailable = !stats && !!statsError
  const chartRows = stats?.charts
  const fieldButtonClass = "max-w-full truncate text-left transition-colors hover:text-primary focus-visible:outline-none focus-visible:underline"
  const filtersActive = !analysisFiltersEqual(appliedFilters, EMPTY_ANALYSIS_FILTERS)
  const hasDraftChanges = !analysisFiltersEqual(draftFilters, appliedFilters)
  const dataBusy = loadingList || loadingStats || reloading
  const actions = (
    <div className="flex flex-wrap gap-2">
      <Button variant="outline" onClick={onReload} disabled={dataBusy || exporting} aria-label="刷新岗位统计与记录">
        <BiRefresh className={reloading ? "animate-spin" : ""} /> {reloading ? "刷新中…" : "刷新数据"}
      </Button>
      <Button variant="outline" onClick={exportCSV} disabled={exporting || dataBusy || !!listError} title="导出已应用筛选条件下的全部记录">
        <BiDownload /> {exporting ? "导出中…" : "导出 CSV"}
      </Button>
    </div>
  )

  return (
    <div className="min-w-0 space-y-6 pb-6 text-foreground">
      {showHeader && (
        <PageHeader
          title="岗位统计"
          subtitle="让每一次搜索与投递，都有迹可循。"
          icon={<BiBarChart size={26} />}
          actions={actions}
        />
      )}

      {(statsError || listError || exportError) && (
        <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 dark:border-amber-900/60 dark:bg-amber-900/20 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
          <span>{[listError, statsError, exportError].filter(Boolean).join(" ")}</span>
          <Button variant="outline" size="sm" disabled={dataBusy || exporting} onClick={() => {
            if (exportError) exportCSV()
            else { loadStats(); loadList(page, size) }
          }}>重试</Button>
        </div>
      )}

      <Card className="min-w-0">
        <CardHeader className="pb-4">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <CardTitle className="text-base">筛选岗位记录</CardTitle>
              <CardDescription className="mt-1.5">设置条件后点击“应用筛选”，同步更新记录、统计与导出范围。</CardDescription>
            </div>
            {!showHeader && actions}
            {showHeader && <span className="rounded-full bg-muted px-3 py-1.5 text-xs text-muted-foreground">{filtersActive ? "当前展示：已筛选记录" : "当前展示：全部记录"}</span>}
          </div>
        </CardHeader>
        <CardContent>
          <form noValidate onSubmit={event => { event.preventDefault(); if (!dataBusy && !exporting) applyFilters() }}>
          <div className="mb-5 flex flex-wrap items-center gap-2">
            <span className="mr-1 text-xs font-medium text-muted-foreground">投递状态</span>
            {ANALYSIS_STATUSES.map(status => (
              <label key={status} className={"inline-flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-xs transition-colors " + (
                statuses.includes(status) ? "border-primary/30 bg-secondary text-foreground" : "border-border bg-card text-muted-foreground hover:bg-muted"
              )}>
                <input
                  type="checkbox"
                  checked={statuses.includes(status)}
                  onChange={event => updateDraft("statuses", event.target.checked ? [...statuses, status] : statuses.filter(value => value !== status))}
                  className="h-3.5 w-3.5 accent-primary"
                />
                {status}
              </label>
            ))}
            <label className="inline-flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-xs text-muted-foreground sm:ml-auto">
              <input type="checkbox" checked={filterHeadhunter} onChange={event => updateDraft("filterHeadhunter", event.target.checked)} className="h-3.5 w-3.5 accent-primary" />
              过滤猎头岗位
            </label>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
            <div className="space-y-2"><Label htmlFor="analysis-keyword">关键词</Label><Input id="analysis-keyword" value={keyword} onChange={event => updateDraft("keyword", event.target.value)} placeholder="公司 / 岗位 / HR，支持模糊匹配" /></div>
            <div className="space-y-2"><Label htmlFor="analysis-city">城市</Label><Input id="analysis-city" value={location} onChange={event => updateDraft("location", event.target.value)} placeholder="完整地点，例如：深圳" /></div>
            <div className="space-y-2"><Label htmlFor="analysis-experience">经验要求</Label><Input id="analysis-experience" value={experience} onChange={event => updateDraft("experience", event.target.value)} placeholder="例如：3-5年" /></div>
            <div className="space-y-2"><Label htmlFor="analysis-degree">学历要求</Label><Input id="analysis-degree" value={degree} onChange={event => updateDraft("degree", event.target.value)} placeholder="例如：本科" /></div>
            <div className="space-y-2"><Label htmlFor="analysis-min-salary">最低月薪 · K</Label><Input id="analysis-min-salary" type="number" min={0} step="any" value={minK} onChange={event => updateDraft("minK", event.target.value)} placeholder="不限" aria-describedby={filterError ? "analysis-filter-error" : undefined} aria-invalid={!!filterError} /></div>
            <div className="space-y-2"><Label htmlFor="analysis-max-salary">最高月薪 · K</Label><Input id="analysis-max-salary" type="number" min={0} step="any" value={maxK} onChange={event => updateDraft("maxK", event.target.value)} placeholder="不限" aria-describedby={filterError ? "analysis-filter-error" : undefined} aria-invalid={!!filterError} /></div>
          </div>
          {filterError && <p id="analysis-filter-error" role="alert" className="mt-4 text-sm text-destructive">{filterError}</p>}
          <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
            <div className="min-w-0 flex-1 text-xs leading-5 text-muted-foreground">
              <p>薪资按记录的可解析月薪中位数筛选；城市、经验、学历按完整记录匹配。</p>
              <p aria-live="polite" className={hasDraftChanges ? "mt-1 font-medium text-primary" : "mt-1"}>{hasDraftChanges ? "有未应用的条件；列表、分页与导出仍沿用当前已应用条件。" : "筛选已同步；不选择状态表示全部状态。"}</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="outline" onClick={() => applyFilters(EMPTY_ANALYSIS_FILTERS)} disabled={dataBusy || exporting || (!filtersActive && !hasDraftChanges)}><BiReset /> 重置</Button>
              <Button type="submit" disabled={dataBusy || exporting}><BiSearch /> {dataBusy ? "更新中…" : "应用筛选"}</Button>
            </div>
          </div>
          </form>
        </CardContent>
      </Card>

      <div className="grid min-w-0 grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6" aria-busy={loadingStats}>
        {kpiCards.map(card => (
          <div
            key={card.title}
            className={"min-w-0 rounded-2xl border px-4 py-5 " + (
              card.tone === "dark" ? "border-primary bg-primary text-primary-foreground"
                : card.tone === "lime" ? "border-primary/20 bg-secondary"
                : "border-border bg-card"
            )}
          >
            <p className={"text-xs font-medium " + (card.tone === "dark" ? "text-primary-foreground/90" : "text-muted-foreground")}>{card.title}</p>
            <p className="mt-3 flex items-baseline gap-1.5 text-3xl font-semibold tabular-nums tracking-tight">
              {card.value == null ? "—" : card.unit ? card.value.toFixed(1) : card.value.toLocaleString()}
              {card.unit && card.value != null && <span className="text-sm font-normal">{card.unit}</span>}
            </p>
            <p className={"mt-3 text-[11px] leading-4 " + (card.tone === "dark" ? "text-primary-foreground" : "text-muted-foreground")}>{card.caption}</p>
          </div>
        ))}
      </div>

      <Card id="analysis-records" className="min-w-0 scroll-mt-6 overflow-hidden">
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle className="flex items-center gap-2 text-base"><BiBriefcase className="text-primary" /> 岗位记录</CardTitle>
              <CardDescription className="mt-1.5">按记录时间倒序展示；打开详情查看全部字段，或前往原岗位。</CardDescription>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs">
              <a href="#analysis-distributions" className="font-medium text-primary hover:underline">查看分布分析</a>
              <span className="rounded-full bg-muted px-3 py-1.5 text-muted-foreground">{loadingList ? "记录更新中…" : listError ? "记录暂未加载" : `共 ${total.toLocaleString()} 条`}</span>
            </div>
          </div>
        </CardHeader>
        <CardContent className="min-w-0">
          <div className="max-w-full overflow-x-auto rounded-xl border border-border" aria-busy={loadingList}>
            <table className="w-full min-w-[920px] table-fixed bg-card text-left text-sm">
              <thead className="border-b border-border bg-background text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="w-56 px-4 py-3.5 font-medium">岗位 / 公司</th>
                  <th scope="col" className="w-24 px-4 py-3.5 font-medium">薪资</th>
                  <th scope="col" className="w-36 px-4 py-3.5 font-medium">城市 / 任职要求</th>
                  <th scope="col" className="w-32 px-4 py-3.5 font-medium">招聘者</th>
                  <th scope="col" className="w-28 px-4 py-3.5 font-medium">投递 / 招聘状态</th>
                  <th scope="col" className="w-28 px-4 py-3.5 font-medium">记录日期</th>
                  <th scope="col" className="w-24 px-4 py-3.5 font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="px-6 py-16 text-center">
                      <div className="mx-auto flex max-w-sm flex-col items-center">
                        <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-muted text-primary"><BiBriefcase size={26} /></div>
                        <p className="font-medium">{loadingList ? "正在加载岗位记录…" : listError ? "记录暂未加载" : filtersActive ? "没有符合筛选条件的岗位" : "你的求职记录从这里开始"}</p>
                        <p className="mt-2 text-xs leading-6 text-muted-foreground">{loadingList ? "请稍候，正在读取本地记录。" : listError ? "检查本地服务后点击重试。" : filtersActive ? "可以尝试放宽城市、薪资或投递状态条件。" : "完成岗位搜索后，记录会出现在这里；你也可以刷新已有数据。"}</p>
                      </div>
                    </td>
                  </tr>
                ) : items.map(job => (
                  <tr key={job.id} className="transition-colors hover:bg-muted/60">
                    <td className="px-4 py-4 align-top">
                      <button className={fieldButtonClass + " block font-semibold text-foreground"} title={job.jobName} onClick={() => openTextDialog("岗位名称", job.jobName)}>{job.jobName || "未命名岗位"}</button>
                      <button className={fieldButtonClass + " mt-1.5 block text-xs text-muted-foreground"} title={job.companyName} onClick={() => openTextDialog("公司名称", job.companyName)}>{job.companyName || "—"}</button>
                    </td>
                    <td className="px-4 py-4 align-top"><button className={fieldButtonClass + " font-semibold text-primary"} title={job.salary} onClick={() => openTextDialog("薪资", job.salary)}>{job.salary || "—"}</button></td>
                    <td className="px-4 py-4 align-top">
                      <button className={fieldButtonClass + " block"} title={job.location} onClick={() => openTextDialog("地点", job.location)}>{job.location || "—"}</button>
                      <div className="mt-1.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                        <button className={fieldButtonClass} title={job.experience} onClick={() => openTextDialog("经验", job.experience)}>{job.experience || "—"}</button><span>·</span>
                        <button className={fieldButtonClass} title={job.degree} onClick={() => openTextDialog("学历", job.degree)}>{job.degree || "—"}</button>
                      </div>
                    </td>
                    <td className="px-4 py-4 align-top">
                      <button className={fieldButtonClass + " block text-xs"} title={job.hrName} onClick={() => openTextDialog("HR", job.hrName)}>{job.hrName || "—"}</button>
                      <button className={fieldButtonClass + " mt-1 block text-[11px] text-muted-foreground"} title={job.hrPosition} onClick={() => openTextDialog("HR 职位", job.hrPosition)}>{job.hrPosition || "—"}</button>
                      <button className={badgeClass("hr", job.hrActiveStatus) + " mt-2"} onClick={() => openTextDialog("HR 活跃", job.hrActiveStatus)}>{job.hrActiveStatus || "活跃状态未知"}</button>
                    </td>
                    <td className="px-4 py-4 align-top">
                      <button className={badgeClass("delivery", job.deliveryStatus)} onClick={() => openTextDialog("投递状态", job.deliveryStatus)}>{job.deliveryStatus || "状态未知"}</button>
                      <button className={badgeClass("recruitment", job.recruitmentStatus) + " mt-2"} onClick={() => openTextDialog("招聘状态", job.recruitmentStatus)}>{job.recruitmentStatus || "招聘状态未知"}</button>
                    </td>
                    <td className="px-4 py-4 align-top text-xs text-muted-foreground"><button className={fieldButtonClass} onClick={() => openTextDialog("创建时间", formatDateOnly(job.createdAt))}>{formatDateOnly(job.createdAt) || "—"}</button></td>
                    <td className="px-4 py-4 align-top">
                      <button className="inline-flex items-center gap-1 text-xs font-medium text-foreground hover:underline" onClick={() => setSelectedJob(job)}>详情 <BiChevronRight /></button>
                      {job.jobUrl && <a href={job.jobUrl} target="_blank" rel="noreferrer" className="mt-3 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-primary">原岗位 <BiLinkExternal /></a>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-5 flex flex-wrap items-center justify-between gap-x-5 gap-y-3 text-xs text-muted-foreground">
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => loadList(Math.max(1, page - 1), size)} disabled={loadingList || page <= 1}>上一页</Button>
              <span className="px-1 tabular-nums">第 {page} / {pageCount} 页</span>
              <Button variant="outline" size="sm" onClick={() => loadList(page + 1, size)} disabled={loadingList || page >= pageCount}>下一页</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Label htmlFor="analysis-page" className="text-xs">跳至</Label>
              <Input id="analysis-page" type="number" min={1} value={inputPage} onChange={event => setInputPage(event.target.value)} onKeyDown={event => {
                if (event.key === "Enter" && !loadingList) loadList(clampAnalysisPage(inputPage, pageCount), size)
              }} className="h-8 w-16 text-xs" />
              <span>页</span>
              <Button variant="ghost" size="sm" disabled={loadingList} onClick={() => loadList(clampAnalysisPage(inputPage, pageCount), size)}>跳转</Button>
              <Label htmlFor="analysis-page-size" className="ml-2 text-xs">每页</Label>
              <Select id="analysis-page-size" value={String(size)} disabled={loadingList} onChange={event => {
                const value = Number(event.target.value)
                loadList(1, Math.max(1, value))
              }} className="h-8 w-20 text-xs">
                <option value="20">20</option><option value="50">50</option><option value="100">100</option><option value="200">200</option>
              </Select>
              <span>条</span>
            </div>
          </div>
        </CardContent>
      </Card>

      <section id="analysis-distributions" aria-labelledby="analysis-charts-title" className="min-w-0 scroll-mt-6 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="analysis-charts-title" className="text-lg font-semibold tracking-tight">岗位分布分析</h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">按已应用条件汇总，切换分类查看不同维度。</p>
          </div>
          {loadingStats ? <span role="status" className="text-xs text-muted-foreground">统计更新中…</span> : <a href="#analysis-records" className="text-xs font-medium text-primary hover:underline">返回岗位记录</a>}
        </div>
        <Tabs defaultValue="delivery" className="min-w-0">
          <TabsList aria-label="统计图表分类" className="grid h-auto w-full grid-cols-3 sm:w-auto sm:inline-flex">
            <TabsTrigger value="delivery" className="px-3 text-xs sm:px-5 sm:text-sm">投递与趋势</TabsTrigger>
            <TabsTrigger value="requirements" className="px-3 text-xs sm:px-5 sm:text-sm">岗位要求</TabsTrigger>
            <TabsTrigger value="companies" className="px-3 text-xs sm:px-5 sm:text-sm">公司与招聘者</TabsTrigger>
          </TabsList>
          <TabsContent value="delivery" className="mt-4">
            <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              <DistributionCard title="每日记录趋势" description="每天新增的岗位记录数" rows={chartRows?.dailyTrend} type="line" loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="投递状态" description="当前岗位的投递结果构成" rows={chartRows?.byStatus} type="pie" loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="城市分布" description="岗位所在地的数量分布" rows={chartRows?.byCity} horizontal loading={chartLoading} unavailable={chartUnavailable} />
            </div>
          </TabsContent>
          <TabsContent value="requirements" className="mt-4">
            <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              <DistributionCard title="经验要求" description="不同经验要求的岗位数量" rows={chartRows?.byExperience} loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="学历要求" description="不同学历要求的岗位数量" rows={chartRows?.byDegree} loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="薪资区间" description="按可解析月薪的中位数分组 · K" rows={chartRows?.salaryBuckets.map(row => ({ name: row.bucket, value: row.value }))} loading={chartLoading} unavailable={chartUnavailable} />
            </div>
          </TabsContent>
          <TabsContent value="companies" className="mt-4">
            <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              <DistributionCard title="行业 TOP 10" description="岗位记录最多的行业" rows={chartRows?.byIndustry} horizontal loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="公司 TOP 10" description="岗位记录最多的公司" rows={chartRows?.byCompany} horizontal loading={chartLoading} unavailable={chartUnavailable} />
              <DistributionCard title="招聘者 TOP 10" description="按有活跃状态记录的招聘者汇总" rows={chartRows?.hrActivity.slice().sort((a, b) => b.value - a.value).slice(0, 10)} horizontal loading={chartLoading} unavailable={chartUnavailable} />
            </div>
          </TabsContent>
        </Tabs>
      </section>

      {selectedJob && (
        <div id="mitu-job-details" tabIndex={-1} className="fixed inset-0 z-40 flex justify-end bg-foreground/[0.35]" role="dialog" aria-modal={!showTextDialog} aria-hidden={showTextDialog || undefined} aria-labelledby="job-details-title" onClick={event => { if (event.target === event.currentTarget) setSelectedJob(null) }}>
          <section className="flex h-full w-full max-w-xl flex-col bg-background shadow-xl">
            <div className="flex items-start justify-between gap-4 border-b border-border bg-card p-6">
              <div className="min-w-0">
                <p className="text-[10px] font-semibold tracking-[0.18em] text-muted-foreground">MITU / 岗位档案</p>
                <h2 id="job-details-title" className="mt-2 break-words text-xl font-semibold">{selectedJob.jobName || "岗位详情"}</h2>
                <p className="mt-1.5 break-words text-sm text-muted-foreground">{selectedJob.companyName || "公司名称未记录"}</p>
              </div>
              <Button variant="ghost" size="icon" onClick={() => setSelectedJob(null)} aria-label="关闭岗位详情"><BiX size={22} /></Button>
            </div>
            <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4 sm:p-6">
              {[
                ["公司名称", selectedJob.companyName], ["岗位名称", selectedJob.jobName],
                ["薪资", selectedJob.salary], ["地点", selectedJob.location], ["经验", selectedJob.experience],
                ["学历", selectedJob.degree], ["HR", selectedJob.hrName], ["HR 职位", selectedJob.hrPosition],
                ["HR 活跃", selectedJob.hrActiveStatus], ["投递状态", selectedJob.deliveryStatus],
                ["招聘状态", selectedJob.recruitmentStatus], ["公司地址", selectedJob.companyAddress],
                ["行业", selectedJob.industry], ["公司规模", selectedJob.companyScale],
                ["融资阶段", selectedJob.financingStage], ["公司介绍", selectedJob.introduce],
                ["岗位描述", selectedJob.jobDescription], ["创建时间", formatDateOnly(selectedJob.createdAt)],
              ].map(([label, value]) => (
                <div key={label} className="rounded-xl border border-border bg-card px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-xs text-muted-foreground">{label}</p>
                    {value && <button className="text-[11px] font-medium text-primary hover:underline" onClick={() => openTextDialog(label || "", value)}>查看全文</button>}
                  </div>
                  <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-foreground">{value || "未记录"}</p>
                </div>
              ))}
            </div>
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border bg-card p-4">
              <Button variant="outline" onClick={() => setSelectedJob(null)}>关闭</Button>
              {selectedJob.jobUrl && <Button asChild><a href={selectedJob.jobUrl} target="_blank" rel="noreferrer">前往原岗位 <BiLinkExternal /></a></Button>}
            </div>
          </section>
        </div>
      )}

      {showTextDialog && (
        <div id="mitu-text-dialog" tabIndex={-1} className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/40 p-4" role="dialog" aria-modal="true" aria-labelledby="text-dialog-title" onClick={event => { if (event.target === event.currentTarget) setShowTextDialog(false) }}>
          <div className="w-full max-w-2xl overflow-hidden rounded-2xl border border-border bg-card shadow-xl">
            <div className="flex items-center justify-between border-b border-border px-5 py-4">
              <h2 id="text-dialog-title" className="font-semibold">{textDialogTitle}</h2>
              <Button variant="ghost" size="icon" onClick={() => setShowTextDialog(false)} aria-label="关闭全文"><BiX size={22} /></Button>
            </div>
            <div className="p-5">
              <textarea data-modal-initial-focus ref={textAreaRef} readOnly value={textDialogContent || ""} aria-label={textDialogTitle} className="h-[45vh] w-full resize-none rounded-xl border border-border bg-background p-4 text-sm leading-7 text-foreground outline-none focus:border-primary" />
              <div className="mt-4 flex flex-wrap justify-end gap-2">
                <Button variant="outline" onClick={selectDialogText}>全选</Button>
                <Button variant="outline" onClick={copyDialogText}>复制全文</Button>
                <Button onClick={() => setShowTextDialog(false)}>完成</Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
