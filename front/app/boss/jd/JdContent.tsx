'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { Archive, ArrowDownToLine, ArrowUpRight, BookOpen, Check, ChevronLeft, ChevronRight, Database, FileText, Filter, History, Loader2, RefreshCw, Search, Settings2, Sparkles, Square, X } from 'lucide-react'
import PageHeader from '@/app/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useModalFocus } from '@/lib/use-modal-focus'
import { downloadJd, emptyJdScope, jdDate, jdJobType, jdRequest, jdScopeIssue, jdScopeQuery, jdStatusLabel, jdTaskRunning, type JdDetail, type JdEvidence, type JdJob, type JdOverview, type JdReport, type JdScope, type JdSettings, type JdTerm } from '@/lib/boss-jd'

const selectClass = 'h-10 w-full min-w-0 rounded-xl border border-input bg-card px-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring'
const categoryLabels: Record<string, string> = { required: '明确要求', preferred: '加分项', unclear: '待明确', responsibility: '工作职责' }
const errorText = (error: unknown) => error instanceof Error ? error.message : '操作失败，请稍后重试。'
const reportId = (report: JdReport) => String(report.id || report.taskId || '')
const scopeKey = (scope: JdScope) => jdScopeQuery(scope).toString()
const reportScopeLabel = (scope?: JdScope) => !scope ? '范围未记录' : [
  scope.keyword || '全部关键词', scope.city || '全部城市',
  scope.jobType ? `搜索类型：${jdJobType(scope.jobType)}` : '全部搜索类型',
  scope.from || scope.to ? `${scope.from || '不限开始'} 至 ${scope.to || '不限结束'}` : '全部日期',
  scope.jobIds?.length ? `选定 ${scope.jobIds.length} 个岗位` : '范围内全部岗位',
].join(' · ')

function Notice({ children, error = false }: { children: React.ReactNode; error?: boolean }) {
  return <p role={error ? 'alert' : 'status'} className={`rounded-xl px-4 py-3 text-sm leading-6 ${error ? 'bg-destructive/10 text-destructive' : 'bg-secondary/70 text-primary'}`}>{children}</p>
}

function Evidence({ items = [], onOpen }: { items?: JdEvidence[]; onOpen: (id: number, versionId?: number) => void }) {
  if (!items.length) return <p className="mt-2 text-xs text-muted-foreground">暂无可展示的原文引用。</p>
  return <details className="mt-2 text-xs"><summary className="cursor-pointer font-medium text-primary">查看原文依据（{items.length}）</summary><div className="mt-2 space-y-2">{items.slice(0, 12).map((item, i) => <div key={`${item.jobId}-${item.versionId}-${i}`} className="rounded-xl border bg-muted/30 p-3"><p className="whitespace-pre-wrap break-words leading-6">“{item.quote}”</p><button className="mt-1 font-medium text-primary hover:underline" onClick={() => onOpen(item.jobId, item.versionId)}>岗位 #{item.jobId} · JD 版本 #{item.versionId} ↗</button></div>)}{items.length > 12 && <p className="text-muted-foreground">页面展示前 12 条依据，导出可查看完整来源。</p>}</div></details>
}

function TermList({ terms, onOpen, empty }: { terms: JdTerm[]; onOpen: (id: number, versionId?: number) => void; empty: string }) {
  if (!terms.length) return <p className="py-7 text-center text-sm text-muted-foreground">{empty}</p>
  return <div className="grid gap-3 md:grid-cols-2">{terms.map((term, i) => <div key={`${term.term}-${i}`} className="min-w-0 rounded-xl border p-4"><div className="flex items-center justify-between gap-3"><p className="break-words text-sm font-semibold">{term.term}</p><span className="shrink-0 text-xs font-medium text-primary">{term.count} / {term.total} 个岗位</span></div><div className="mt-2 h-1.5 overflow-hidden rounded-full bg-secondary"><div className="h-full rounded-full bg-primary" style={{ width: `${term.total ? Math.min(100, term.count / term.total * 100) : 0}%` }} /></div><Evidence items={term.evidence} onOpen={onOpen} /></div>)}</div>
}

export default function JdContent() {
  const [draft, setDraft] = useState<JdScope>({ ...emptyJdScope })
  const [applied, setApplied] = useState<JdScope>({ ...emptyJdScope })
  const [page, setPage] = useState(1)
  const [selectedJobs, setSelectedJobs] = useState<number[]>([])
  const [list, setList] = useState<{ items: JdJob[]; total: number }>({ items: [], total: 0 })
  const [overview, setOverview] = useState<JdOverview | null>(null)
  const [reports, setReports] = useState<JdReport[]>([])
  const [report, setReport] = useState<JdReport | null>(null)
  const [reportLoading, setReportLoading] = useState(false)
  const [selectedReportId, setSelectedReportId] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [message, setMessage] = useState('')
  const [actionError, setActionError] = useState('')
  const [action, setAction] = useState('')
  const [revision, setRevision] = useState(0)
  const [settings, setSettings] = useState<JdSettings | null>(null)
  const [settingsDraft, setSettingsDraft] = useState<JdSettings>({ autoEnabled: false, batchSize: 20, maxCalls: 100 })
  const [settingsError, setSettingsError] = useState('')
  const [apiConfigured, setApiConfigured] = useState(false)
  const [settingsRevision, setSettingsRevision] = useState(0)
  const [analysisTask, setAnalysisTask] = useState<JdReport | null>(null)
  const [importTask, setImportTask] = useState<JdReport | null>(null)
  const [importConfirm, setImportConfirm] = useState(false)
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null)
  const [detail, setDetail] = useState<JdDetail | null>(null)
  const [detailError, setDetailError] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailRevision, setDetailRevision] = useState(0)
  const [versionId, setVersionId] = useState<number | null>(null)
  const requestedVersion = useRef<number | null>(null)
  const mounted = useRef(true)
  const actionRef = useRef(false)
  const activeControllers = useRef(new Set<AbortController>())
  const analysisScope = useRef('')
  const appliedKey = scopeKey(applied)
  const latestScopeKey = useRef(appliedKey)
  latestScopeKey.current = appliedKey
  const dirty = scopeKey(draft) !== appliedKey
  const scopeIssue = jdScopeIssue(draft)
  const settingsDirty = settings !== null && JSON.stringify(settingsDraft) !== JSON.stringify(settings)
  const settingsInvalid = !Number.isInteger(settingsDraft.batchSize) || settingsDraft.batchSize < 1 || settingsDraft.batchSize > 100 || !Number.isInteger(settingsDraft.maxCalls) || settingsDraft.maxCalls < 1 || settingsDraft.maxCalls > 2000
  const modalId = importConfirm ? 'jd-import-confirm' : selectedJobId !== null ? 'jd-detail' : null
  useModalFocus(modalId, () => importConfirm ? setImportConfirm(false) : setSelectedJobId(null))

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; activeControllers.current.forEach(controller => controller.abort()); activeControllers.current.clear() }
  }, [])

  useEffect(() => {
    if (!settingsDirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [settingsDirty])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setLoadError('')
    const query = jdScopeQuery(applied)
    const listQuery = new URLSearchParams(query)
    listQuery.set('page', String(page)); listQuery.set('size', '20')
    Promise.all([
      jdRequest<{ items: JdJob[]; total: number }>(`/jobs?${listQuery}`, { signal: controller.signal }),
      jdRequest<JdOverview>(`/overview?${query}`, { signal: controller.signal }),
      jdRequest<JdReport[]>(`/reports?${query}`, { signal: controller.signal }),
    ]).then(([nextList, nextOverview, nextReports]) => {
      if (controller.signal.aborted) return
      if (!Array.isArray(nextList.items) || !Number.isFinite(nextList.total) || !Array.isArray(nextReports)) throw new Error('岗位库响应格式异常。')
      if (nextOverview.available === false) throw new Error(nextOverview.error || 'JD 岗位库暂不可用。')
      setList(nextList); setOverview(nextOverview); setReports(nextReports)
      if (page > Math.max(1, Math.ceil(nextList.total / 20))) setPage(Math.max(1, Math.ceil(nextList.total / 20)))
      const runningReport = nextReports.find(item => jdTaskRunning(item.status) && reportId(item))
      if (runningReport) {
        analysisScope.current = scopeKey(applied)
        setAnalysisTask(previous => jdTaskRunning(previous?.status) ? previous : runningReport)
      }
      setSelectedReportId(previous => nextReports.some(item => reportId(item) === previous) ? previous : nextReports[0] ? reportId(nextReports[0]) : '')
    }).catch(error => { if (!controller.signal.aborted) setLoadError(errorText(error)) }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [applied, page, revision])

  useEffect(() => {
    setReport(null); setSelectedReportId('')
  }, [appliedKey])

  useEffect(() => {
    if (!selectedReportId) { setReport(null); setReportLoading(false); return }
    const controller = new AbortController()
    setReport(null); setReportLoading(true)
    jdRequest<JdReport>(`/reports/${encodeURIComponent(selectedReportId)}`, { signal: controller.signal }).then(data => { if (!controller.signal.aborted) setReport(data) }).catch(error => { if (!controller.signal.aborted) setActionError(errorText(error)) }).finally(() => { if (!controller.signal.aborted) setReportLoading(false) })
    return () => controller.abort()
  }, [selectedReportId, revision])

  useEffect(() => {
    const controller = new AbortController()
    setSettingsError('')
    jdRequest<JdSettings & { available?: boolean; error?: string; apiConfigured?: boolean }>('/settings', { signal: controller.signal }).then(data => {
      if (controller.signal.aborted) return
      if (typeof data.autoEnabled !== 'boolean' || !Number.isInteger(data.batchSize) || !Number.isInteger(data.maxCalls)) throw new Error('总结设置响应格式异常。')
      if (data.available === false) throw new Error(data.error || 'JD 分析模块暂不可用。')
      setApiConfigured(data.apiConfigured === true)
      const clean = { autoEnabled: data.autoEnabled, batchSize: data.batchSize, maxCalls: data.maxCalls }
      setSettings(clean); setSettingsDraft(clean)
    }).catch(error => { if (!controller.signal.aborted) { setSettings(null); setApiConfigured(false); setSettingsError(errorText(error)) } })
    return () => controller.abort()
  }, [settingsRevision])

  useEffect(() => {
    if (selectedJobId === null) { setDetail(null); return }
    const controller = new AbortController()
    setDetail(null); setDetailError(''); setDetailLoading(true); setVersionId(null)
    jdRequest<JdDetail>(`/jobs/${selectedJobId}`, { signal: controller.signal }).then(data => {
      if (controller.signal.aborted) return
      if (!data.job || !Array.isArray(data.versions) || !Array.isArray(data.matches)) throw new Error('岗位详情响应不完整。')
      const current = data.versions.find(item => item.versionId === data.job.currentVersionId)
      data.job = { ...current, ...data.job, source: data.matches.some(item => item.matched) ? 'matched' : 'history' }
      setDetail(data); setVersionId(requestedVersion.current || data.job.currentVersionId || data.job.versionId || data.versions[0]?.versionId || null)
    }).catch(error => { if (!controller.signal.aborted) setDetailError(errorText(error)) }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false) })
    return () => controller.abort()
  }, [selectedJobId, detailRevision])

  const analysisId = analysisTask ? reportId(analysisTask) : ''
  const analysisRunning = jdTaskRunning(analysisTask?.status)
  useEffect(() => {
    if (!analysisId || !analysisRunning) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await jdRequest<JdReport>(`/analyses/${encodeURIComponent(analysisId)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        setAnalysisTask({ ...next, taskId: analysisId })
        if (!jdTaskRunning(next.status)) {
          setMessage(`需求分析${jdStatusLabel(next.status)}。`)
          if (analysisScope.current === latestScopeKey.current) setRevision(value => value + 1)
          return
        }
      } catch (error) { if (controller.signal.aborted) return; setActionError(`分析进度同步失败：${errorText(error)} 正在重试。`) }
      timer = setTimeout(poll, 2500)
    }
    timer = setTimeout(poll, 1200)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [analysisId, analysisRunning])

  const importId = importTask ? reportId(importTask) : ''
  const importRunning = jdTaskRunning(importTask?.status)
  useEffect(() => {
    if (!importId || !importRunning) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await jdRequest<JdReport>(`/history-imports/${encodeURIComponent(importId)}`, { signal: controller.signal })
        if (controller.signal.aborted) return
        setImportTask({ ...next, taskId: importId })
        if (!jdTaskRunning(next.status)) { setMessage(`历史 JD 导入${jdStatusLabel(next.status)}，导入记录默认待确认。`); setRevision(value => value + 1); return }
      } catch (error) { if (controller.signal.aborted) return; setActionError(`历史导入进度同步失败：${errorText(error)} 正在重试。`) }
      timer = setTimeout(poll, 2500)
    }
    timer = setTimeout(poll, 1200)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [importId, importRunning])

  const runAction = useCallback(async (name: string, work: (signal: AbortSignal) => Promise<void>) => {
    if (actionRef.current) return
    actionRef.current = true; setAction(name); setActionError(''); setMessage('')
    const controller = new AbortController()
    activeControllers.current.add(controller)
    try { await work(controller.signal) } catch (error) { if (mounted.current && !controller.signal.aborted) setActionError(errorText(error)) }
    finally { activeControllers.current.delete(controller); actionRef.current = false; if (mounted.current) setAction('') }
  }, [])

  const startAnalysis = () => {
    const snapshot = { ...applied }
    runAction('analysis', async signal => {
      const task = await jdRequest<JdReport>('/analyses', { method: 'POST', body: JSON.stringify(snapshot), signal })
      if (!reportId(task) || !task.status) throw new Error('分析任务响应不完整，请刷新后确认状态。')
      if (!signal.aborted) { analysisScope.current = scopeKey(snapshot); setAnalysisTask(task); setMessage('分析任务已提交。JD 将发送到系统设置中的 AI 服务。'); setRevision(value => value + 1) }
    })
  }

  const saveSettings = () => {
    if (!settings || settingsInvalid || (settingsDraft.autoEnabled && !apiConfigured)) return
    const snapshot = { ...settingsDraft }
    runAction('settings', async signal => {
      const saved = await jdRequest<JdSettings>('/settings', { method: 'PUT', body: JSON.stringify(snapshot), signal })
      if (!signal.aborted) { const clean = { autoEnabled: saved.autoEnabled, batchSize: saved.batchSize, maxCalls: saved.maxCalls }; setSettings(clean); setSettingsDraft(clean); setMessage('总结设置已保存。') }
    })
  }

  const exportData = (format: 'json' | 'markdown', summary = false) => runAction('export', async signal => {
    await downloadJd({ ...applied }, format, signal, summary && report ? reportId(report) : undefined)
    if (!signal.aborted) setMessage('导出已生成。')
  })

  const openJob = (id: number, targetVersion?: number) => { if (Number.isInteger(id) && id > 0) { requestedVersion.current = targetVersion || null; if (selectedJobId === id && targetVersion) setVersionId(targetVersion); else setSelectedJobId(id) } }
  const currentVersion = detail?.versions.find(item => item.versionId === versionId)
  const currentJob = detail?.job
  const currentExcluded = Boolean(currentJob?.excluded || currentJob?.included === false || currentJob?.included === 0)
  const currentHistorical = (currentJob?.source === 'history' || currentJob?.source === 'historical') && currentJob?.included !== 1 && currentJob?.included !== true
  const pageCount = Math.max(1, Math.ceil(list.total / 20))
  const requirements = Array.isArray(report?.requirements) ? report.requirements : []
  const reportTotal = Number(report?.sampleCount ?? report?.totalCount ?? 0)
  const reportAnalyzed = Number(report?.analyzedCount ?? 0)

  return <>
    <PageHeader icon={<BookOpen size={21} />} title="JD 岗位库" subtitle="留住每一次符合条件的机会，从岗位原文中积累简历调整的依据。" actions={<><Button variant="outline" disabled={Boolean(action) || importRunning} onClick={() => setImportConfirm(true)}><History />{importRunning ? '历史导入中' : '导入历史 JD'}</Button><Button variant="outline" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw className={loading ? 'animate-spin' : ''} />刷新</Button></>} />
    <div className="space-y-4">
      <div className="flex flex-col gap-3 rounded-2xl border border-primary/15 bg-secondary/55 px-5 py-4 sm:flex-row sm:items-center sm:justify-between"><div><p className="text-sm font-semibold text-primary">搜索时自动留存，需求逐步清晰</p><p className="mt-1 text-xs leading-5 text-muted-foreground">岗位通过当前筛选后保存 JD；沟通是否成功不影响留存。同一岗位重复出现只算一个样本。</p></div><Link href="/boss" className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-primary">前往投递工作台<ArrowUpRight size={15} /></Link></div>
      {message && <Notice>{message}</Notice>}
      {actionError && <Notice error>{actionError}</Notice>}
      <Card>
        <CardHeader className="pb-4"><CardTitle className="flex items-center gap-2"><Filter size={17} className="text-primary" />查看范围</CardTitle><CardDescription>按搜索命中条件筛选；日期按匹配命中时间计算，历史待确认记录默认不参与总结。</CardDescription></CardHeader>
        <CardContent>
          <form onSubmit={event => { event.preventDefault(); if (!scopeIssue) { setApplied({ ...draft }); setPage(1); setActionError('') } }}>
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
              <label className="space-y-2 text-xs font-medium" htmlFor="jd-keyword"><span>搜索关键词（精确）</span><Input id="jd-keyword" value={draft.keyword} maxLength={100} placeholder="例如 AI应用" onChange={event => setDraft({ ...draft, keyword: event.target.value })} /></label>
              <label className="space-y-2 text-xs font-medium" htmlFor="jd-city"><span>岗位城市</span><Input id="jd-city" value={draft.city} maxLength={100} placeholder="全部城市" onChange={event => setDraft({ ...draft, city: event.target.value })} /></label>
              <label className="space-y-2 text-xs font-medium" htmlFor="jd-type"><span>搜索职位类型</span><select id="jd-type" className={selectClass} value={draft.jobType} onChange={event => setDraft({ ...draft, jobType: event.target.value })}><option value="">全部搜索类型</option><option value="0">不限类型搜索</option><option value="1901">全职</option><option value="1902">实习</option><option value="1903">兼职</option></select></label>
              <label className="space-y-2 text-xs font-medium" htmlFor="jd-from"><span>开始日期</span><Input id="jd-from" type="date" value={draft.from} onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
              <label className="space-y-2 text-xs font-medium" htmlFor="jd-to"><span>结束日期</span><Input id="jd-to" type="date" value={draft.to} onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3"><label className="flex items-center gap-2 text-xs text-muted-foreground"><input type="checkbox" className="h-4 w-4 accent-primary" checked={draft.includeHistory} onChange={event => setDraft({ ...draft, includeHistory: event.target.checked })} />显示历史待确认记录</label><div className="flex gap-2"><Button type="button" variant="ghost" disabled={loading} onClick={() => { const empty = { ...emptyJdScope }; setDraft(empty); setApplied(empty); setSelectedJobs([]); setPage(1) }}>重置</Button><Button type="submit" disabled={Boolean(scopeIssue) || loading}><Search />应用筛选</Button></div></div>
            <p className={`mt-3 text-xs ${scopeIssue ? 'text-destructive' : dirty ? 'text-primary' : 'text-muted-foreground'}`} role="status">{scopeIssue || (dirty ? '有未应用的条件；岗位列表与原文导出仍沿用当前已应用范围。' : '岗位列表与原文导出使用当前范围；AI 报告以各自标注的范围为准。')}</p>
            {!!applied.jobIds?.length && <div className="mt-3 flex flex-wrap items-center gap-3 rounded-xl bg-secondary/60 px-3 py-2 text-xs text-primary"><span>当前范围限定为所选 {applied.jobIds.length} 个岗位</span><button type="button" className="font-semibold underline" disabled={loading} onClick={() => { setApplied(previous => ({ ...previous, jobIds: undefined })); setDraft(previous => ({ ...previous, jobIds: undefined })); setSelectedJobs([]); setPage(1) }}>取消岗位限定</button></div>}
          </form>
        </CardContent>
      </Card>
      {loadError && <Notice error>岗位库加载失败：{loadError} <button className="ml-2 font-semibold underline" onClick={() => setRevision(value => value + 1)}>重试</button></Notice>}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{[
        { label: '有效岗位', value: overview?.totalJobs, note: '按岗位去重', icon: Archive },
        { label: '目标公司', value: overview?.totalCompanies, note: '当前范围内', icon: Database },
        { label: '相同正文', value: overview?.duplicateContents, note: '关注重复发布', icon: FileText },
        { label: 'AI 已分析', value: report ? `${reportAnalyzed} / ${reportTotal}` : '—', note: report?.stale ? '报告有待更新' : '显示最近选定报告', icon: Sparkles },
      ].map(({ label, value, note, icon: Icon }) => <Card key={label}><CardContent className="p-4"><div className="flex items-center justify-between gap-2"><span className="text-xs text-muted-foreground">{label}</span><Icon size={16} className="text-primary" /></div><p className="mt-3 text-2xl font-semibold tracking-tight">{loading || loadError ? '—' : value ?? 0}</p><p className="mt-1 text-[11px] text-muted-foreground">{note}</p></CardContent></Card>)}</div>
      <Tabs defaultValue="originals">
        <TabsList className="mb-4"><TabsTrigger value="originals"><FileText size={15} className="mr-2" />岗位原文</TabsTrigger><TabsTrigger value="summary"><Sparkles size={15} className="mr-2" />需求总结</TabsTrigger></TabsList>
        <TabsContent value="originals" className="mt-0">
          <Card>
            <CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><div><CardTitle>目标岗位与 JD</CardTitle><CardDescription className="mt-1.5">{loading ? '正在加载岗位…' : loadError ? '请先恢复连接后查看。' : `共 ${list.total} 个岗位 · 每页 20 条`}</CardDescription></div><div className="flex flex-wrap gap-2"><Button variant="outline" size="sm" disabled={loading || !!loadError || !list.total || !!action} onClick={() => exportData('markdown')}><ArrowDownToLine />Markdown</Button><Button variant="outline" size="sm" disabled={loading || !!loadError || !list.total || !!action} onClick={() => exportData('json')}><ArrowDownToLine />JSON</Button></div></div></CardHeader>
            <CardContent>
              {!loading && !loadError && list.items.length > 0 && <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl bg-muted/35 p-3"><div className="flex flex-wrap items-center gap-3"><label className="flex items-center gap-2 text-xs text-muted-foreground"><input type="checkbox" className="h-4 w-4 accent-primary" checked={list.items.every(job => selectedJobs.includes(job.jobId))} onChange={event => setSelectedJobs(previous => event.target.checked ? [...new Set([...previous, ...list.items.map(job => job.jobId)])] : previous.filter(id => !list.items.some(job => job.jobId === id)))} />选择当前页</label><span className="text-xs text-muted-foreground">已选 {selectedJobs.length} 个岗位</span></div><div className="flex gap-2"><Button variant="ghost" size="sm" disabled={!selectedJobs.length} onClick={() => setSelectedJobs([])}>清除选择</Button><Button variant="outline" size="sm" disabled={!selectedJobs.length} onClick={() => { const next = { ...applied, jobIds: [...selectedJobs] }; setApplied(next); setDraft(next); setPage(1) }}>应用所选岗位</Button></div></div>}
              {loading ? <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground"><Loader2 size={18} className="animate-spin" />加载岗位库</div> : loadError ? <p className="py-10 text-center text-sm text-muted-foreground">连接恢复后显示岗位记录。</p> : !list.items.length ? <div className="rounded-2xl border border-dashed px-5 py-12 text-center"><BookOpen size={30} className="mx-auto text-primary/50" /><h3 className="mt-4 text-sm font-semibold">当前范围还没有 JD</h3><p className="mt-2 text-xs leading-6 text-muted-foreground">后续搜索到符合条件的岗位会自动留存。也可以导入已有历史 JD，再确认需要纳入的岗位。</p></div> : <div className="space-y-3">{list.items.map(job => <article key={job.jobId} className="flex min-w-0 flex-col gap-3 rounded-xl border p-4 transition-colors hover:border-primary/35 sm:flex-row sm:items-center"><label className="flex shrink-0 items-center gap-2 text-xs text-muted-foreground"><input type="checkbox" className="h-4 w-4 accent-primary" checked={selectedJobs.includes(job.jobId)} onChange={event => setSelectedJobs(previous => event.target.checked ? [...new Set([...previous, job.jobId])] : previous.filter(id => id !== job.jobId))} aria-label={`选择岗位 ${job.jobName}`} /><span className="sm:sr-only">选择岗位</span></label><div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><button className="break-words text-left text-sm font-semibold hover:text-primary" onClick={() => openJob(job.jobId)}>{job.jobName || '未命名岗位'}</button>{((job.source === 'history' || job.source === 'historical') && job.included !== 1 && job.included !== true) && <span className="rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-700 dark:text-amber-300">历史待确认</span>}{(job.excluded || job.included === 0 || job.included === false) && <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] text-muted-foreground">已排除</span>}</div><p className="mt-1.5 break-words text-xs text-muted-foreground">{job.companyName || '公司未核验'} · {job.city || '城市未核验'}{job.salary ? ` · ${job.salary}` : ''}</p><p className="mt-2 text-[11px] text-muted-foreground">{[job.experience, job.degree, jdJobType(job.jobType)].filter(Boolean).join(' · ')}<span className="ml-3 inline-block">最近发现 {jdDate(job.lastSeen || job.matchedAt)}</span></p></div><Button variant="ghost" size="sm" className="self-start text-primary sm:self-auto" onClick={() => openJob(job.jobId)}>查看 JD<ChevronRight /></Button></article>)}</div>}
              <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t pt-4"><p className="text-xs text-muted-foreground">第 {page} / {pageCount} 页</p><div className="flex gap-2"><Button variant="outline" size="sm" disabled={loading || !!loadError || page <= 1} onClick={() => setPage(value => value - 1)}><ChevronLeft />上一页</Button><Button variant="outline" size="sm" disabled={loading || !!loadError || page >= pageCount} onClick={() => setPage(value => value + 1)}>下一页<ChevronRight /></Button></div></div>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="summary" className="mt-0 space-y-4">
          <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_300px]">
            <div className="space-y-4">
              <Card><CardHeader><CardTitle>原文关键词统计</CardTitle><CardDescription>按词典在原文中明确命中，每个岗位同一词只计一次。未提及不等于岗位不要求。</CardDescription></CardHeader><CardContent>{loading || loadError ? <p className="py-6 text-sm text-muted-foreground">{loading ? '正在统计…' : '当前统计不可用。'}</p> : <TermList terms={overview?.terms || []} onOpen={openJob} empty="当前范围尚无可统计的技能关键词。" />}</CardContent></Card>
              <Card><CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><div><CardTitle className="flex items-center gap-2"><Sparkles size={17} className="text-primary" />AI 需求总结</CardTitle><CardDescription className="mt-1.5">选定范围中的 JD 将发送到系统配置的 AI 服务。</CardDescription></div><Button disabled={loading || !!loadError || !overview?.totalJobs || !!action || analysisRunning || !settings || !apiConfigured} onClick={startAnalysis}>{analysisRunning || action === 'analysis' ? <Loader2 className="animate-spin" /> : <Sparkles />}{analysisRunning ? '正在分析' : '生成总结'}</Button></div></CardHeader><CardContent className="space-y-4">
                {settings && !apiConfigured && <Notice>AI 服务尚未配置完整，先在系统设置中填写服务地址、模型和密钥；原文留存和本地统计可继续使用。</Notice>}
                {analysisTask && <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-secondary/60 p-3"><p className="text-xs leading-6 text-primary">{jdStatusLabel(analysisTask.status)} · 已处理 {Number(analysisTask.processedCount || 0)} / {Number(analysisTask.totalCount ?? analysisTask.sampleCount ?? 0)}{analysisTask.error ? ` · ${analysisTask.error}` : ''}</p>{analysisRunning && <Button size="sm" variant="outline" disabled={!!action} onClick={() => runAction('cancel', async signal => { const next = await jdRequest<JdReport>(`/analyses/${encodeURIComponent(analysisId)}/cancel`, { method: 'POST', signal }); if (!signal.aborted) { setAnalysisTask({ ...next, taskId: analysisId }); setMessage('已请求停止后续分析，已完成结果会保留。'); setRevision(value => value + 1) } })}><Square />停止分析</Button>}</div>}
                {reports.length > 0 && <label htmlFor="jd-report" className="block space-y-2 text-xs font-medium"><span>报告版本与范围</span><select id="jd-report" className={selectClass} value={selectedReportId} onChange={event => setSelectedReportId(event.target.value)}><option value="">选择报告</option>{reports.map(item => <option key={reportId(item)} value={reportId(item)}>{jdDate(item.createdAt)} · {jdStatusLabel(item.status)} · {item.sampleCount ?? item.totalCount ?? 0} 个样本 · {reportScopeLabel(item.scope)}</option>)}</select></label>}
                {reportLoading ? <p className="py-6 text-sm text-muted-foreground">正在加载报告…</p> : report ? <>
                  <div className="rounded-xl border border-primary/15 bg-secondary/35 p-3"><p className="text-xs font-semibold text-primary">这份报告的范围</p><p className="mt-1 break-words text-xs leading-6 text-muted-foreground">{reportScopeLabel(report.scope)}</p><p className="mt-1 text-[11px] leading-5 text-muted-foreground">报告保留生成时的范围与岗位版本，可能与页面当前岗位统计不同。</p>{report.scope && <Button className="mt-2" size="sm" variant="outline" disabled={loading} onClick={() => { const next = { ...emptyJdScope, ...report.scope, jobIds: report.scope?.jobIds?.length ? [...report.scope.jobIds] : undefined }; setDraft(next); setApplied(next); setPage(1); setSelectedJobs([]) }}>应用这份报告的范围</Button>}</div>
                  <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-muted/40 p-3"><p className="text-xs leading-6 text-muted-foreground">{jdStatusLabel(report.status)} · 分析覆盖 {reportAnalyzed} / {reportTotal} 个岗位{report.failedCount ? ` · ${report.failedCount} 个失败` : ''}<br />{jdDate(report.createdAt)}{report.model ? ` · ${report.model}` : ''}</p><Button size="sm" variant="outline" disabled={!!action || jdTaskRunning(report.status)} onClick={() => exportData('markdown', true)}><ArrowDownToLine />导出总结</Button></div>
                  {report.stale && <Notice>所选范围已有新增、更新或排除的样本，这份报告有待更新。原报告保留生成时的岗位版本。</Notice>}
                  {report.error && <Notice error>{report.error}</Notice>}
                  <p className="text-xs leading-6 text-muted-foreground">频率以已成功分析的岗位为分母；请结合覆盖率查看。结论仅代表当前样本，可展开核对原文。</p>
                  {Object.entries(categoryLabels).map(([category, label]) => {
                    const rows = requirements.filter(item => item.category === category)
                    return rows.length > 0 ? <section key={category}><h3 className="mb-3 text-sm font-semibold">{label}</h3><TermList terms={rows} onOpen={openJob} empty="" /></section> : null
                  })}
                  {requirements.some(item => !categoryLabels[item.category]) && <section><h3 className="mb-3 text-sm font-semibold">其他提取要求</h3><TermList terms={requirements.filter(item => !categoryLabels[item.category])} onOpen={openJob} empty="" /></section>}
                  {!requirements.length && <p className="py-6 text-center text-sm text-muted-foreground">这份报告暂无带有效证据的提取要求。查看分析状态或重试。</p>}
                  {report.recommendations && <section className="rounded-xl border border-primary/15 bg-secondary/40 p-4"><h3 className="text-sm font-semibold text-primary">简历准备建议</h3><p className="mt-1 text-xs text-muted-foreground">以下是准备建议，需结合自己的真实经历判断。</p>{Array.isArray(report.recommendations) ? <ul className="mt-3 list-disc space-y-2 pl-4 text-sm leading-6">{report.recommendations.map((item, index) => <li key={index}>{typeof item === 'string' ? item : <>{item.text}<Evidence items={item.evidence} onOpen={openJob} /></>}</li>)}</ul> : <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{report.recommendations}</p>}</section>}
                </> : <div className="rounded-xl border border-dashed p-6 text-center"><Sparkles className="mx-auto text-primary/50" size={25} /><p className="mt-3 text-sm font-medium">让要求从原文中浮现</p><p className="mt-2 text-xs leading-6 text-muted-foreground">生成总结后，查看明确要求、加分项和工作职责。每一条结论保留岗位原文依据，后续可用于调整简历。清空筛选可浏览最近的自动总结报告，再按报告范围查看对应岗位。</p></div>}
              </CardContent></Card>
            </div>
            <Card><CardHeader><CardTitle className="flex items-center gap-2"><Settings2 size={17} className="text-primary" />自动总结</CardTitle><CardDescription>留存和本地统计持续更新，AI 调用单独控制。</CardDescription></CardHeader><CardContent className="space-y-4">
              {settingsError && <><Notice error>{settingsError}</Notice><Button variant="outline" size="sm" onClick={() => setSettingsRevision(value => value + 1)}>重新加载设置</Button></>}
              <fieldset disabled={!settings || !!action} className="space-y-4 disabled:opacity-60"><label className="flex items-center justify-between gap-3 text-sm font-medium"><span>自动更新 AI 总结</span><input type="checkbox" className="h-4 w-4 accent-primary" disabled={!apiConfigured && !settingsDraft.autoEnabled} checked={settingsDraft.autoEnabled} onChange={event => setSettingsDraft({ ...settingsDraft, autoEnabled: event.target.checked })} /></label><p className="text-xs leading-6 text-muted-foreground">开启后，新增或变更 JD 达到批次，或搜索任务结束时，会自动发送对应 JD 到已配置的 AI 服务。</p><label className="block space-y-2 text-xs font-medium" htmlFor="jd-batch"><span>每批新增 / 变更 JD 数量</span><Input id="jd-batch" type="number" min={1} max={100} step={1} value={Number.isNaN(settingsDraft.batchSize) ? '' : settingsDraft.batchSize} onChange={event => setSettingsDraft({ ...settingsDraft, batchSize: event.target.valueAsNumber })} /></label><label className="block space-y-2 text-xs font-medium" htmlFor="jd-budget"><span>单次分析调用上限</span><Input id="jd-budget" type="number" min={1} max={2000} step={1} value={Number.isNaN(settingsDraft.maxCalls) ? '' : settingsDraft.maxCalls} onChange={event => setSettingsDraft({ ...settingsDraft, maxCalls: event.target.valueAsNumber })} /></label></fieldset>
              <p className={`text-xs leading-5 ${settingsInvalid ? 'text-destructive' : 'text-muted-foreground'}`}>{settingsInvalid ? '批次需为 1–100 的整数，调用上限需为 1–2000 的整数。' : settingsDirty ? '有未保存的总结设置。' : settings ? '总结设置已同步。' : '正在读取总结设置。'}</p><Button className="w-full" disabled={!settings || !settingsDirty || settingsInvalid || !!action || (settingsDraft.autoEnabled && !apiConfigured)} onClick={saveSettings}><Check />{action === 'settings' ? '保存中' : '保存总结设置'}</Button><Link href="/env-config" className="inline-flex items-center gap-1 text-xs font-medium text-primary">配置 AI 服务<ArrowUpRight size={14} /></Link><p className="border-t pt-4 text-xs leading-6 text-muted-foreground">当前先积累岗位证据。简历匹配与改写属于后续功能；岗位入库不代表简历已经满足要求。</p>
            </CardContent></Card>
          </div>
        </TabsContent>
      </Tabs>
    </div>
    {selectedJobId !== null && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-3 sm:p-6" onMouseDown={event => { if (event.target === event.currentTarget) setSelectedJobId(null) }}><div id="jd-detail" role="dialog" aria-modal="true" aria-labelledby="jd-detail-title" tabIndex={-1} className="flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border bg-card shadow-xl"><div className="flex items-start justify-between gap-4 border-b p-5"><div className="min-w-0"><p className="text-[10px] font-semibold tracking-widest text-primary">ORIGINAL JOB DESCRIPTION</p><h2 id="jd-detail-title" className="mt-2 break-words text-xl font-semibold">{currentJob?.jobName || `岗位 #${selectedJobId}`}</h2><p className="mt-1 break-words text-xs text-muted-foreground">{currentJob?.companyName || '岗位详情'}</p></div><Button variant="ghost" size="icon" aria-label="关闭 JD 详情" onClick={() => setSelectedJobId(null)} data-modal-initial-focus><X size={20} /></Button></div><div className="min-h-0 space-y-5 overflow-y-auto p-5">
      {detailLoading ? <p className="flex items-center gap-2 py-10 text-sm text-muted-foreground"><Loader2 className="animate-spin" size={17} />正在读取 JD 原文…</p> : detailError ? <Notice error>{detailError} <button className="ml-2 underline" onClick={() => setDetailRevision(value => value + 1)}>重试</button></Notice> : detail && currentJob && <>
        <div className="flex flex-wrap items-center justify-between gap-3"><p className="text-xs leading-6 text-muted-foreground">{[currentJob.city, currentJob.salary, currentJob.experience, currentJob.degree, jdJobType(currentJob.jobType)].filter(Boolean).join(' · ')}<br />首次采集 {jdDate(currentJob.firstSeen)} · 最近发现 {jdDate(currentJob.lastSeen)}</p><div className="flex flex-wrap gap-2">{currentJob.jobUrl && /^https:\/\/(www\.)?zhipin\.com\//.test(currentJob.jobUrl) && <Button asChild variant="outline" size="sm"><a href={currentJob.jobUrl} target="_blank" rel="noopener noreferrer">原岗位<ArrowUpRight /></a></Button>}<Button variant="outline" size="sm" disabled={!!action} onClick={() => runAction('include', async signal => { await jdRequest(`/jobs/${selectedJobId}`, { method: 'PATCH', body: JSON.stringify({ included: currentExcluded || currentHistorical }), signal }); if (!signal.aborted) { setDetailRevision(value => value + 1); setRevision(value => value + 1); setMessage(currentExcluded || currentHistorical ? '岗位已纳入总结范围。' : '岗位已排除，原文继续保留。') } })}>{currentExcluded || currentHistorical ? '纳入总结' : '排除岗位'}</Button></div></div>
        <label htmlFor="jd-version" className="block space-y-2 text-xs font-medium"><span>JD 内容版本</span><select id="jd-version" className={selectClass} value={versionId ?? ''} onChange={event => setVersionId(Number(event.target.value))}>{detail.versions.map((version, index) => <option key={version.versionId} value={version.versionId}>版本 #{version.versionId}{version.createdAt ? ` · ${jdDate(version.createdAt)}` : ''}{version.versionId === currentJob.currentVersionId ? ' · 当前内容' : ''}</option>)}</select></label>
        <section><h3 className="mb-3 text-sm font-semibold">岗位原文</h3><div className="max-h-[50vh] overflow-y-auto whitespace-pre-wrap break-words rounded-xl border bg-muted/30 p-4 text-sm leading-7">{currentVersion?.description || currentJob.description || '该版本没有有效 JD 原文。'}</div></section>
        <details className="rounded-xl border p-4"><summary className="cursor-pointer text-sm font-semibold">搜索命中来源（{detail.matches.length}）</summary><div className="mt-3 space-y-3">{detail.matches.map((match, index) => <div key={index} className="rounded-xl bg-muted/35 p-3 text-xs leading-6"><p>关键词：{match.keyword || '历史导入'} · 请求城市：{match.searchCity || '未记录'} · {jdJobType(match.jobType)}</p><p className="text-muted-foreground">JD 版本 #{match.versionId || '—'} · {jdStatusLabel(match.matchStatus || (match.matched ? 'matched' : match.source))} · {jdDate(match.matchedAt || match.lastMatchedAt || match.lastSeen)}</p>{(match.configJson || match.config) && <details><summary className="cursor-pointer text-primary">当时筛选条件</summary><pre className="mt-2 max-h-52 overflow-auto whitespace-pre-wrap break-words text-[11px]">{match.configJson || JSON.stringify(match.config, null, 2)}</pre></details>}</div>)}</div></details>
      </>}
    </div></div></div>}
    {importConfirm && <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4"><div id="jd-import-confirm" role="dialog" aria-modal="true" aria-labelledby="jd-import-title" tabIndex={-1} className="w-full max-w-md rounded-2xl border bg-card p-6 shadow-xl"><History className="text-primary" size={24} /><h2 id="jd-import-title" className="mt-4 text-lg font-semibold">导入已有岗位 JD</h2><p className="mt-3 text-sm leading-7 text-muted-foreground">导入已有岗位记录中的有效 JD，并按岗位去重。历史记录未经过本次筛选确认，默认不参与总结，可在岗位详情中逐项纳入。</p><div className="mt-5 flex justify-end gap-2"><Button variant="outline" onClick={() => setImportConfirm(false)} data-modal-initial-focus>取消</Button><Button disabled={!!action} onClick={() => runAction('import', async signal => { const task = await jdRequest<JdReport>('/history-imports', { method: 'POST', signal }); if (!signal.aborted) { setImportTask(task); setImportConfirm(false); setMessage('已提交历史导入，原有投递记录保留。'); setRevision(value => value + 1) } })}><History />确认导入</Button></div></div></div>}
  </>
}
