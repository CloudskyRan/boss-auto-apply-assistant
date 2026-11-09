'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { AlertCircle, ArrowUpRight, Check, CheckCircle2, ChevronDown, ChevronUp, FileImage, GraduationCap, Info, Keyboard, Loader2, Monitor, Play, RefreshCw, Save, ScanText, Send, ShieldCheck, Square, WandSparkles, X } from 'lucide-react'
import PageHeader from '@/app/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { EXAM_QUESTION_LIMIT, ExamDesktopStatus, ExamSettings, readExamDesktopStatus, readExamSettings, readExamText, requestExamJson, validateExamImage, validateExamProcessName } from '@/lib/exam-assistant'

type Operation = 'save' | 'start' | 'stop' | 'send' | 'capture' | 'recognize' | 'solve'
type Feedback = { success: boolean; message: string }
type Answer = { question: string; text: string }
type Recognition = { text: string; source: string }

const DRAFT_KEY = 'mitu-exam-draft-v1'
const desktopOperations = new Set<Operation>(['save', 'start', 'stop', 'send', 'capture'])

export default function ExamAssistant() {
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<Answer | null>(null)
  const [answerVisible, setAnswerVisible] = useState(true)
  const [pendingRecognition, setPendingRecognition] = useState<Recognition | null>(null)
  const [pendingDesktopAnswer, setPendingDesktopAnswer] = useState<Answer | null>(null)
  const [image, setImage] = useState<File | null>(null)
  const [settings, setSettings] = useState<ExamSettings>({ processName: '', excludeFromCapture: true })
  const [savedSettings, setSavedSettings] = useState<ExamSettings | null>(null)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState<string | null>(null)
  const [desktop, setDesktop] = useState<ExamDesktopStatus | null>(null)
  const [desktopError, setDesktopError] = useState<string | null>(null)
  const [statusPending, setStatusPending] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [operation, setOperation] = useState<Operation | null>(null)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [draftReady, setDraftReady] = useState(false)
  const mounted = useRef(false)
  const questionRef = useRef('')
  const operationRef = useRef<Operation | null>(null)
  const statusEpoch = useRef(0)
  const settingsEpoch = useRef(0)
  const statusInFlight = useRef(false)
  const lastRemoteQuestion = useRef('')
  const lastRemoteAnswer = useRef('')
  const requests = useRef(new Set<AbortController>())
  const imageInput = useRef<HTMLInputElement>(null)

  const settingsDirty = savedSettings !== null && (settings.processName !== savedSettings.processName || settings.excludeFromCapture !== savedSettings.excludeFromCapture)
  const processNameError = settings.processName ? validateExamProcessName(settings.processName) : '请输入桌面进程名称。'
  const questionError = question.length > EXAM_QUESTION_LIMIT ? `题目不能超过 ${EXAM_QUESTION_LIMIT.toLocaleString()} 字符。` : null
  const answerStale = answer !== null && answer.question !== question
  const answerTooLong = answer !== null && answer.text.length > EXAM_QUESTION_LIMIT
  const statusTrusted = desktop !== null && desktopError === null && !statusPending
  const desktopAvailable = statusTrusted && desktop.supported
  const settingsDisabled = settingsLoading || savedSettings === null || operation !== null || !statusTrusted || desktop.running

  const editQuestion = useCallback((text: string) => {
    questionRef.current = text
    setQuestion(text)
  }, [])

  const acceptRecognition = useCallback((text: string, source: string) => {
    if (text === questionRef.current) return
    if (!questionRef.current.trim() && !operationRef.current) {
      editQuestion(text)
    } else {
      setPendingRecognition({ text, source })
    }
  }, [editQuestion])

  const callApi = useCallback(async <T,>(path: string, init: RequestInit = {}, timeoutMs = 30_000): Promise<T> => {
    const controller = new AbortController()
    requests.current.add(controller)
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
    try {
      return await requestExamJson<T>(path, { ...init, signal: controller.signal })
    } catch (error) {
      if (controller.signal.aborted) throw new Error('请求已超时或页面已关闭，请检查状态后重试。')
      throw error
    } finally {
      window.clearTimeout(timeout)
      requests.current.delete(controller)
    }
  }, [])

  const loadSettings = useCallback(async () => {
    const epoch = ++settingsEpoch.current
    setSettingsLoading(true)
    setSettingsError(null)
    try {
      const result = readExamSettings(await callApi('/settings', {}, 10_000))
      if (!mounted.current || epoch !== settingsEpoch.current) return
      setSettings(result)
      setSavedSettings(result)
    } catch (error) {
      if (mounted.current && epoch === settingsEpoch.current) setSettingsError(error instanceof Error ? error.message : '设置加载失败，请重试。')
    } finally {
      if (mounted.current && epoch === settingsEpoch.current) setSettingsLoading(false)
    }
  }, [callApi])

  const refreshDesktop = useCallback(async () => {
    if (statusInFlight.current || (operationRef.current !== null && desktopOperations.has(operationRef.current))) return
    statusInFlight.current = true
    const epoch = statusEpoch.current
    if (mounted.current) setRefreshing(true)
    try {
      const result = readExamDesktopStatus(await callApi('/desktop/status', {}, 10_000))
      if (!mounted.current || epoch !== statusEpoch.current) return
      setDesktop(result)
      setDesktopError(null)
      setStatusPending(false)
      if (result.question !== lastRemoteQuestion.current) {
        lastRemoteQuestion.current = result.question
        if (result.question.trim()) acceptRecognition(result.question, '桌面截图')
      }
      const remoteAnswer = JSON.stringify([result.question, result.answer])
      if (remoteAnswer !== lastRemoteAnswer.current) {
        lastRemoteAnswer.current = remoteAnswer
        if (result.question.trim() && result.answer.trim()) {
          setPendingDesktopAnswer({ question: result.question, text: result.answer })
        }
      }
    } catch (error) {
      if (mounted.current && epoch === statusEpoch.current) {
        setStatusPending(false)
        setDesktopError(error instanceof Error ? error.message : '无法读取桌面状态，请重试。')
      }
    } finally {
      statusInFlight.current = false
      if (mounted.current) setRefreshing(false)
    }
  }, [acceptRecognition, callApi])

  useEffect(() => {
    mounted.current = true
    try {
      const draft = JSON.parse(sessionStorage.getItem(DRAFT_KEY) || 'null') as { question?: unknown; answer?: unknown; answerVisible?: unknown } | null
      if (draft && typeof draft.question === 'string' && draft.question.length <= EXAM_QUESTION_LIMIT) editQuestion(draft.question)
      const draftAnswer = draft?.answer as Partial<Answer> | null
      if (draftAnswer && typeof draftAnswer.question === 'string' && typeof draftAnswer.text === 'string') {
        setAnswer({ question: draftAnswer.question, text: draftAnswer.text })
        setAnswerVisible(draft?.answerVisible !== false)
      }
    } catch { /* 浏览器禁用存储时仍可正常使用当前页面。 */ }
    setDraftReady(true)
    void loadSettings()
    void refreshDesktop()
    const timer = window.setInterval(() => { void refreshDesktop() }, 2_000)
    return () => {
      mounted.current = false
      statusEpoch.current += 1
      settingsEpoch.current += 1
      window.clearInterval(timer)
      requests.current.forEach((controller) => controller.abort())
    }
  }, [editQuestion, loadSettings, refreshDesktop])

  useEffect(() => {
    if (!draftReady) return
    try { sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ question, answer, answerVisible })) } catch { /* 不因本地存储失败丢弃内存草稿。 */ }
  }, [answer, answerVisible, draftReady, question])

  useEffect(() => {
    if (!settingsDirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [settingsDirty])

  const runOperation = async (next: Operation, task: () => Promise<string | null>) => {
    if (operationRef.current !== null) return
    operationRef.current = next
    setOperation(next)
    setFeedback(null)
    if (desktopOperations.has(next)) {
      statusEpoch.current += 1
      setStatusPending(true)
    }
    try {
      const message = await task()
      if (mounted.current && message) setFeedback({ success: true, message })
    } catch (error) {
      if (mounted.current) setFeedback({ success: false, message: error instanceof Error ? error.message : '操作未完成，编辑内容仍保留。' })
    } finally {
      operationRef.current = null
      if (mounted.current) {
        setOperation(null)
        if (desktopOperations.has(next)) {
          statusEpoch.current += 1
          void refreshDesktop()
        }
      }
    }
  }

  const postJson = (path: string, body: unknown, timeoutMs?: number) => callApi(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }, timeoutMs)

  const saveSettings = async () => {
    if (settingsDisabled || !settingsDirty || processNameError) return
    const snapshot = { ...settings }
    await runOperation('save', async () => {
      const result = readExamSettings(await callApi('/settings', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(snapshot),
      }))
      if (mounted.current) {
        setSavedSettings(result)
        setSettings((current) => current.processName === snapshot.processName && current.excludeFromCapture === snapshot.excludeFromCapture ? result : current)
      }
      return '桌面设置已保存，下次启动时生效。'
    })
  }

  const startDesktop = async () => {
    if (!desktopAvailable || desktop.running || operationRef.current || settingsLoading || savedSettings === null || settingsDirty || processNameError) return
    await runOperation('start', async () => { await postJson('/desktop/start', {}, 60_000); return '桌面窗口已启动。' })
  }

  const stopDesktop = async () => {
    if (!statusTrusted || !desktop.running || operationRef.current) return
    await runOperation('stop', async () => { await postJson('/desktop/stop', {}); return '桌面窗口已关闭。' })
  }

  const capture = async () => {
    if (!desktopAvailable || !desktop.running || desktop.busy || operationRef.current) return
    await runOperation('capture', async () => {
      const text = readExamText(await postJson('/desktop/capture', {}, 60_000), 'text')
      if (mounted.current) {
        lastRemoteQuestion.current = text
        if (!questionRef.current.trim()) editQuestion(text)
        else if (text !== questionRef.current) setPendingRecognition({ text, source: '桌面截图' })
      }
      return '截图识别完成，请检查题目后再生成解析。'
    })
  }

  const recognizeImage = async () => {
    if (!image || operationRef.current || desktop?.busy) return
    const validation = validateExamImage(image)
    if (validation) { setFeedback({ success: false, message: validation }); return }
    const form = new FormData()
    form.append('image', image)
    await runOperation('recognize', async () => {
      const text = readExamText(await callApi('/recognize', { method: 'POST', body: form }, 60_000), 'text')
      if (mounted.current) {
        if (!questionRef.current.trim()) editQuestion(text)
        else if (text !== questionRef.current) setPendingRecognition({ text, source: '上传图片' })
      }
      return '图片识别完成，请检查题干、选项和代码。'
    })
  }

  const solve = async () => {
    if (!question.trim() || questionError || operationRef.current || desktop?.busy) return
    const snapshot = question
    await runOperation('solve', async () => {
      const text = readExamText(await postJson('/solve', { question: snapshot }, 120_000), 'answer')
      if (mounted.current) {
        setAnswer({ question: snapshot, text })
        setAnswerVisible(true)
      }
      return '解析已生成。'
    })
  }

  const sendToDesktop = async () => {
    if (!answer || answerStale || answerTooLong || !desktopAvailable || !desktop.running || desktop.busy || operationRef.current) return
    const snapshot = { question, answer: answer.text }
    await runOperation('send', async () => {
      await postJson('/desktop/content', snapshot)
      if (mounted.current) {
        lastRemoteQuestion.current = snapshot.question
        lastRemoteAnswer.current = JSON.stringify([snapshot.question, snapshot.answer])
        setPendingDesktopAnswer(null)
        setAnswerVisible(false)
      }
      return '解析已发送到桌面窗口，网页解析已收起。'
    })
  }

  return (
    <div className="mx-auto w-full max-w-[1200px] space-y-5">
      <PageHeader icon={<GraduationCap className="h-5 w-5" />} title="笔试助手" subtitle="识别题目、生成解析，并在独立桌面窗口中查看。" />

      {feedback && <div role={feedback.success ? 'status' : 'alert'} className={`flex items-start gap-2 rounded-xl border px-4 py-3 text-sm leading-6 ${feedback.success ? 'border-primary/20 bg-secondary text-foreground' : 'border-destructive/20 bg-destructive/5 text-destructive'}`}>{feedback.success ? <CheckCircle2 className="mt-1 h-4 w-4 shrink-0 text-primary" /> : <AlertCircle className="mt-1 h-4 w-4 shrink-0" />}<span>{feedback.message}</span></div>}

      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="min-w-0 space-y-5">
          <Card className="border-border bg-card shadow-sm">
            <CardHeader className="border-b border-border px-4 py-4 sm:px-5">
              <div className="flex items-center gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-secondary text-primary"><ScanText className="h-4 w-4" /></span><div className="space-y-1"><CardTitle>1. 准备题目</CardTitle><CardDescription className="text-xs leading-5">粘贴文字，上传题目图片，或从桌面窗口截屏识别。</CardDescription></div></div>
            </CardHeader>
            <CardContent className="space-y-4 p-4 sm:p-5">
              <div className="flex flex-col gap-3 rounded-xl border border-dashed border-border bg-background p-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0"><p className="flex items-center gap-2 text-sm font-medium"><FileImage className="h-4 w-4 shrink-0 text-primary" /><span className="truncate" title={image?.name}>{image?.name || '上传题目图片'}</span></p><p className="mt-1 text-xs text-muted-foreground">PNG / JPEG，单张不超过 5 MB</p></div>
                <div className="flex shrink-0 flex-wrap items-center gap-2">
                  <input ref={imageInput} id="exam-image" aria-label="选择题目图片" type="file" accept="image/png,image/jpeg" className="sr-only" disabled={operation !== null || desktop?.busy} onChange={(event) => {
                    const file = event.target.files?.[0]
                    if (!file) return
                    const error = validateExamImage(file)
                    if (error) { setImage(null); setFeedback({ success: false, message: error }); event.target.value = ''; return }
                    setImage(file)
                    setFeedback(null)
                  }} />
                  <Button type="button" variant="outline" size="sm" disabled={operation !== null || desktop?.busy} onClick={() => imageInput.current?.click()}>选择图片</Button>
                  <Button type="button" variant="secondary" size="sm" disabled={!image || operation !== null || desktop?.busy} onClick={recognizeImage}>{operation === 'recognize' ? <Loader2 className="animate-spin" /> : <ScanText />}{operation === 'recognize' ? '识别中…' : '识别图片'}</Button>
                </div>
              </div>

              {pendingRecognition && <div className="space-y-3 rounded-xl border border-primary/20 bg-secondary p-3"><div className="flex items-start justify-between gap-2"><div><p className="text-sm font-medium">收到{pendingRecognition.source}识别结果</p><p className="mt-1 text-xs leading-5 text-muted-foreground">当前题目已保留，确认后再替换。</p></div><Button type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" aria-label="忽略识别结果" onClick={() => setPendingRecognition(null)}><X className="h-4 w-4" /></Button></div><pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-card p-3 font-sans text-xs leading-6">{pendingRecognition.text}</pre><div className="flex flex-wrap gap-2"><Button type="button" size="sm" onClick={() => { editQuestion(pendingRecognition.text); setPendingRecognition(null); setFeedback(null) }}><Check />替换题目</Button><Button type="button" variant="outline" size="sm" onClick={() => setPendingRecognition(null)}>保留原题</Button></div></div>}

              <div className="space-y-2"><div className="flex items-center justify-between gap-3"><Label htmlFor="exam-question">题目内容</Label><span className={`text-xs ${questionError ? 'text-destructive' : 'text-muted-foreground'}`}>{question.length.toLocaleString()} / {EXAM_QUESTION_LIMIT.toLocaleString()}</span></div><Textarea id="exam-question" value={question} onChange={(event) => { editQuestion(event.target.value); setFeedback(null) }} aria-invalid={!!questionError} aria-describedby="exam-question-help" className="min-h-[260px] resize-y bg-background font-mono text-[13px] sm:min-h-[300px]" placeholder="在这里粘贴题干、选项或代码。识别图片后可继续校对、编辑。" /><p id="exam-question-help" className={`text-xs leading-5 ${questionError ? 'text-destructive' : 'text-muted-foreground'}`}>{questionError || '识别后的公式、图表与代码可能需要校对。题目仅在你点击生成解析后发送给配置的 AI 服务。'}</p></div>

              <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-center sm:justify-between"><div className="space-y-1"><Link href="/env-config" className="inline-flex items-center gap-1 text-xs font-medium text-primary">使用系统设置中的 AI 服务<ArrowUpRight className="h-3.5 w-3.5" /></Link>{desktop?.busy && <p role="status" className="text-xs text-muted-foreground">桌面正在处理题目，请稍候。</p>}</div><Button type="button" disabled={!question.trim() || !!questionError || operation !== null || desktop?.busy} onClick={solve} className="h-10 w-full shrink-0 sm:w-auto">{operation === 'solve' ? <Loader2 className="animate-spin" /> : <WandSparkles />}{operation === 'solve' ? '正在生成解析…' : '生成解析'}</Button></div>
            </CardContent>
          </Card>

          <Card className="border-border bg-card shadow-sm">
            <CardHeader className="border-b border-border px-4 py-4 sm:px-5"><div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><div className="space-y-1"><CardTitle>2. 查看解析</CardTitle><CardDescription className="text-xs leading-5">解析可发送至已启动的桌面窗口。</CardDescription></div>{answer && <Button type="button" variant="outline" size="sm" onClick={() => setAnswerVisible(!answerVisible)} aria-expanded={answerVisible}>{answerVisible ? <ChevronUp /> : <ChevronDown />}{answerVisible ? '收起网页解析' : '展开网页解析'}</Button>}</div></CardHeader>
            <CardContent className="space-y-4 p-4 sm:p-5">
              {pendingDesktopAnswer && <div className="space-y-3 rounded-xl border border-primary/20 bg-secondary p-3"><div className="flex items-start justify-between gap-2"><div><p className="text-sm font-medium">桌面已生成新解析</p><p className="mt-1 text-xs leading-5 text-muted-foreground">{pendingDesktopAnswer.question === question ? '可继续在桌面查看，或在网页展开。' : '桌面题目与当前草稿不同。使用桌面内容会替换当前题目与解析。'}</p></div><Button type="button" variant="ghost" size="icon" className="h-7 w-7 shrink-0" aria-label="忽略桌面解析" onClick={() => setPendingDesktopAnswer(null)}><X className="h-4 w-4" /></Button></div><Button type="button" variant="outline" size="sm" disabled={operation !== null} onClick={() => { editQuestion(pendingDesktopAnswer.question); setAnswer(pendingDesktopAnswer); setAnswerVisible(true); setPendingDesktopAnswer(null); setPendingRecognition(null) }}>{pendingDesktopAnswer.question === question ? '在网页查看' : '使用桌面题目与解析'}</Button></div>}
              {answerStale && <p role="status" className="flex items-start gap-2 rounded-lg bg-secondary px-3 py-2 text-xs leading-5 text-muted-foreground"><Info className="mt-0.5 h-4 w-4 shrink-0" />题目已修改，以下解析对应修改前的内容。重新生成后可发送到桌面。</p>}
              {answerTooLong && <p role="status" className="flex items-start gap-2 rounded-lg bg-secondary px-3 py-2 text-xs leading-5 text-muted-foreground"><Info className="mt-0.5 h-4 w-4 shrink-0" />解析超过桌面窗口的 20,000 字符上限，已保留完整内容供网页查看。缩短题目后重新生成可发送至桌面。</p>}
              {answer ? answerVisible ? <pre className="max-h-[620px] overflow-auto whitespace-pre-wrap break-words font-sans text-sm leading-7">{answer.text}</pre> : <p className="rounded-xl bg-background px-4 py-6 text-center text-sm text-muted-foreground">网页解析已收起，可在桌面窗口查看或重新展开。</p> : <div className="flex flex-col items-center gap-2 rounded-xl bg-background px-4 py-9 text-center"><WandSparkles className="h-6 w-6 text-primary" /><p className="text-sm font-medium">解析将在这里显示</p><p className="text-xs leading-5 text-muted-foreground">准备题目后，点击“生成解析”。</p></div>}
              <div className="flex flex-col gap-3 border-t border-border pt-4 sm:flex-row sm:items-center sm:justify-between"><p className="max-w-md text-xs leading-5 text-muted-foreground">发送后网页解析自动收起。窗口捕获排除只作用桌面窗口，本页面仍会出现在屏幕共享中。</p><Button type="button" variant="outline" disabled={!answer || answerStale || answerTooLong || !desktopAvailable || !desktop.running || desktop.busy || operation !== null} onClick={sendToDesktop} className="h-10 w-full shrink-0 sm:w-auto">{operation === 'send' ? <Loader2 className="animate-spin" /> : <Send />}{operation === 'send' ? '发送中…' : '发送到桌面'}</Button></div>
            </CardContent>
          </Card>
        </div>

        <aside className="min-w-0 space-y-5 xl:sticky xl:top-6">
          <Card className="border-border bg-card shadow-sm">
            <CardHeader className="border-b border-border px-4 py-4 sm:px-5"><div className="flex items-center justify-between gap-2"><CardTitle className="flex items-center gap-2"><Monitor className="h-4 w-4 text-primary" />桌面窗口</CardTitle><Button type="button" variant="ghost" size="icon" className="h-7 w-7" aria-label="刷新桌面状态" disabled={refreshing || (operation !== null && desktopOperations.has(operation))} onClick={refreshDesktop}><RefreshCw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} /></Button></div></CardHeader>
            <CardContent className="space-y-4 p-4 sm:p-5">
              <div className="flex items-center gap-2 text-sm"><span className={`h-2 w-2 rounded-full ${statusTrusted && desktop.running ? 'bg-emerald-500' : 'bg-muted-foreground/40'}`} />{!desktop ? desktopError ? '桌面服务未连接' : '正在连接桌面服务…' : desktopError || statusPending ? '桌面状态待确认' : !desktop.supported ? '当前系统不支持' : desktop.running ? desktop.busy ? '桌面正在处理题目' : '桌面窗口运行中' : '桌面窗口未启动'}</div>
              {desktopError && <p role="alert" className="text-xs leading-5 text-destructive">{desktopError} 正在自动重试。</p>}
              {desktop?.message && <p className="text-xs leading-5 text-muted-foreground">{desktop.message}</p>}
              {desktop?.running && <div className="space-y-2 rounded-xl bg-background p-3 text-xs"><div className="flex items-start justify-between gap-3"><span className="shrink-0 text-muted-foreground">运行进程</span><span className="break-all text-right font-medium">{desktop.processName || '—'}</span></div><div className="flex justify-between gap-3"><span className="text-muted-foreground">进程 ID</span><span>{desktop.pid ?? '—'}</span></div><div className="flex items-start justify-between gap-3"><span className="shrink-0 text-muted-foreground">捕获排除</span><span className={`text-right ${desktop.captureProtectionApplied ? 'text-primary' : 'text-muted-foreground'}`}>{desktop.captureProtectionApplied ? '已应用' : desktop.excludeFromCapture ? '未成功应用' : '未启用'}</span></div></div>}
              <div className="grid grid-cols-2 gap-2"><Button type="button" className="h-10" disabled={!desktopAvailable || desktop.running || operation !== null || settingsLoading || savedSettings === null || settingsDirty || !!processNameError} onClick={startDesktop}>{operation === 'start' ? <Loader2 className="animate-spin" /> : <Play />}{operation === 'start' ? '启动中…' : '启动桌面'}</Button><Button type="button" variant="outline" className="h-10" disabled={!statusTrusted || !desktop.running || operation !== null} onClick={stopDesktop}>{operation === 'stop' ? <Loader2 className="animate-spin" /> : <Square />}{operation === 'stop' ? '关闭中…' : '关闭桌面'}</Button></div>
              {settingsDirty && !desktop?.running && <p className="text-xs leading-5 text-primary">请先保存下方设置，再启动桌面窗口。</p>}
              <div className="space-y-3 border-t border-border pt-4"><Button type="button" variant="secondary" className="h-10 w-full" disabled={!desktopAvailable || !desktop.running || desktop.busy || operation !== null} onClick={capture}>{operation === 'capture' ? <Loader2 className="animate-spin" /> : <ScanText />}{operation === 'capture' ? '正在截屏识别…' : '截屏识题'}</Button><div className="flex items-start gap-2 text-xs leading-5 text-muted-foreground"><Keyboard className="mt-0.5 h-4 w-4 shrink-0" /><p>桌面快捷键 <span className="break-words font-medium text-foreground">Ctrl + Alt + Shift + Enter</span>。可在题目页面使用，识别鼠标所在的整块屏幕；只在点击或按下快捷键时截屏。检查识别结果后再生成解析。</p></div></div>
            </CardContent>
          </Card>

          <form onSubmit={(event) => { event.preventDefault(); void saveSettings() }}>
            <Card className="border-border bg-card shadow-sm"><CardHeader className="border-b border-border px-4 py-4 sm:px-5"><CardTitle className="flex items-center gap-2"><ShieldCheck className="h-4 w-4 text-primary" />桌面设置</CardTitle><CardDescription className="text-xs leading-5">进程名称与窗口捕获方式。</CardDescription></CardHeader><CardContent className="space-y-4 p-4 sm:p-5">
              {settingsLoading && <p role="status" className="flex items-center gap-2 text-xs text-muted-foreground"><Loader2 className="h-3.5 w-3.5 animate-spin" />读取已保存设置…</p>}
              {settingsError && <div role="alert" className="space-y-2 text-xs leading-5 text-destructive"><p>{settingsError} 加载成功后才可保存。</p><Button type="button" variant="outline" size="sm" onClick={loadSettings} disabled={settingsLoading}><RefreshCw />重新加载</Button></div>}
              <fieldset disabled={settingsDisabled} className="min-w-0 space-y-4">
                <div className="space-y-2"><Label htmlFor="exam-process-name">桌面进程名称</Label><Input id="exam-process-name" value={settings.processName} maxLength={48} autoComplete="off" spellCheck={false} onChange={(event) => { setSettings((current) => ({ ...current, processName: event.target.value })); setFeedback(null) }} className="h-10 bg-background" placeholder={settingsLoading ? '正在加载…' : '例如 MituStudy'} aria-invalid={!!processNameError && savedSettings !== null} aria-describedby="exam-process-help" /><p id="exam-process-help" className={`text-xs leading-5 ${processNameError && savedSettings !== null ? 'text-destructive' : 'text-muted-foreground'}`}>{processNameError && savedSettings !== null ? processNameError : '1–48 位，以英文字母开头。无需填写 .exe；修改后重新启动桌面生效。'}</p></div>
                <label htmlFor="exam-exclude-capture" className="flex cursor-pointer items-start gap-3 rounded-xl border border-border bg-background p-3"><input id="exam-exclude-capture" type="checkbox" checked={settings.excludeFromCapture} onChange={(event) => { setSettings((current) => ({ ...current, excludeFromCapture: event.target.checked })); setFeedback(null) }} className="mt-0.5 h-4 w-4 shrink-0 accent-primary" /><span className="min-w-0"><span className="block text-sm font-medium">从屏幕捕获中排除窗口</span><span className="mt-1 block text-xs leading-5 text-muted-foreground">桌面窗口本机可见，使用 Windows 的窗口捕获排除能力。</span></span></label>
              </fieldset>
              {desktop?.running && <p className="rounded-lg bg-secondary px-3 py-2 text-xs leading-5 text-muted-foreground">先关闭桌面窗口，再修改设置。</p>}
              <div className="space-y-3 border-t border-border pt-4"><p role="status" className="flex items-center gap-2 text-xs text-muted-foreground">{settingsDirty ? <span className="h-1.5 w-1.5 rounded-full bg-primary" /> : <CheckCircle2 className="h-3.5 w-3.5" />}{settingsLoading ? '正在读取设置' : savedSettings === null ? '设置尚未加载' : settingsDirty ? '有未保存的更改' : '设置已保存'}</p><Button type="submit" variant="outline" className="h-10 w-full" disabled={settingsDisabled || !settingsDirty || !!processNameError}>{operation === 'save' ? <Loader2 className="animate-spin" /> : <Save />}{operation === 'save' ? '保存中…' : '保存桌面设置'}</Button></div>
              <p className="text-xs leading-5 text-muted-foreground">捕获排除的效果取决于录屏方式，不代表程序无法被检测。请在使用的共享工具中确认实际画面。</p>
            </CardContent></Card>
          </form>
        </aside>
      </div>
    </div>
  )
}
