'use client'

import { useState, useEffect, useRef } from 'react'
import Link from 'next/link'
import { AlertCircle, ArrowUpRight, Bot, CheckCircle2, Info, Loader2, RotateCcw, Save, Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

type AiConfig = { introduce: string; prompt: string }

export default function AiConfigPage() {
  const [aiConfig, setAiConfig] = useState<AiConfig>({ introduce: '', prompt: '' })
  const [savedConfig, setSavedConfig] = useState<AiConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const saveInFlight = useRef(false)
  // AI 模式仍独立、即时保存，内容配置手动保存。
  const [enableAi, setEnableAi] = useState(0)
  const [modeReady, setModeReady] = useState(false)
  const [modeLoading, setModeLoading] = useState(true)
  const [modeSaving, setModeSaving] = useState(false)
  const [modeError, setModeError] = useState<string | null>(null)
  const modeInFlight = useRef(false)
  const configRequest = useRef<AbortController | null>(null)
  const modeRequest = useRef<AbortController | null>(null)
  const dirty = savedConfig !== null && (aiConfig.introduce !== savedConfig.introduce || aiConfig.prompt !== savedConfig.prompt)
  const formDisabled = loading || savedConfig === null || saving

  const fetchAiConfig = async () => {
    configRequest.current?.abort()
    const controller = new AbortController()
    configRequest.current = controller
    setLoading(true)
    setLoadError(null)
    try {
      const response = await fetch(`${API_BASE}/api/ai/config`, { signal: controller.signal })
      if (!response.ok) throw new Error('load_failed')
      const result = await response.json()
      if (!result.success || !result.data) throw new Error('load_failed')
      if (controller.signal.aborted) return
      const config = { introduce: result.data.introduce || '', prompt: result.data.prompt || '' }
      setAiConfig(config)
      setSavedConfig(config)
    } catch {
      if (!controller.signal.aborted) setLoadError('AI 内容加载失败。请重试后再编辑，避免覆盖已保存的内容。')
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }

  const fetchEnableAi = async () => {
    modeRequest.current?.abort()
    const controller = new AbortController()
    modeRequest.current = controller
    setModeLoading(true)
    setModeError(null)
    try {
      const response = await fetch(`${API_BASE}/api/boss/config`, { signal: controller.signal })
      if (!response.ok) throw new Error('load_failed')
      const result = await response.json()
      if (!result.config) throw new Error('load_failed')
      if (controller.signal.aborted) return
      const raw = String(result.config.enableAi ?? '').trim().toLowerCase()
      setEnableAi(raw === '1' || raw === 'true' || raw === 'on' ? 1 : 0)
      setModeReady(true)
    } catch {
      if (!controller.signal.aborted) setModeError('AI 模式读取失败，请重试。')
    } finally {
      if (!controller.signal.aborted) setModeLoading(false)
    }
  }

  useEffect(() => {
    void fetchAiConfig()
    void fetchEnableAi()
    return () => {
      configRequest.current?.abort()
      modeRequest.current?.abort()
    }
  }, [])

  useEffect(() => {
    if (!dirty) return
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnBeforeUnload)
    return () => window.removeEventListener('beforeunload', warnBeforeUnload)
  }, [dirty])

  const toggleEnableAi = async () => {
    if (!modeReady || modeLoading || modeInFlight.current) return
    modeInFlight.current = true
    setModeSaving(true)
    setModeError(null)
    const next = enableAi ? 0 : 1
    try {
      const response = await fetch(`${API_BASE}/api/boss/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enableAi: next }),
      })
      if (!response.ok) throw new Error('save_failed')
      setEnableAi(next)
    } catch {
      setModeError('切换未完成，当前模式保持不变。请检查服务连接后重试。')
    } finally {
      modeInFlight.current = false
      setModeSaving(false)
    }
  }

  const handleSave = async () => {
    if (formDisabled || !dirty || saveInFlight.current) return
    saveInFlight.current = true
    setSaving(true)
    setSaveResult(null)
    const snapshot = { ...aiConfig }
    try {
      const response = await fetch(`${API_BASE}/api/ai/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(snapshot),
      })
      if (!response.ok) throw new Error('save_failed')
      const result = await response.json()
      if (!result.success) throw new Error('save_failed')
      setSavedConfig(snapshot)
      setSaveResult({ success: true, message: '个人介绍与提示词已保存。' })
    } catch {
      setSaveResult({ success: false, message: '保存未完成，编辑内容仍保留。请检查服务连接后重试。' })
    } finally {
      saveInFlight.current = false
      setSaving(false)
    }
  }

  const editConfig = (field: keyof AiConfig, value: string) => {
    setAiConfig((current) => ({ ...current, [field]: value }))
    setSaveResult(null)
  }

  return (
    <div className="mx-auto w-full max-w-[1120px] space-y-5">
      <PageHeader
        icon={<Bot className="h-5 w-5" />}
        title="AI 表达助手"
        subtitle="管理个人介绍与表达规则，生成更贴近岗位的招呼语。"
      />

      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_248px]">
        <div className="min-w-0 space-y-4">
          <Card className="border-border bg-card shadow-sm">
            <CardContent className="flex flex-col gap-4 p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
              <div className="flex min-w-0 items-start gap-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-secondary text-primary">
                  <Sparkles className="h-4 w-4" />
                </div>
                <div>
                  <h2 className="text-sm font-semibold text-foreground">AI 招呼语</h2>
                  <p className="mt-1 text-xs leading-5 text-muted-foreground">
                    开关即时保存；下方内容编辑后需点击保存。
                  </p>
                  {modeError && <p role="alert" className="mt-2 text-xs leading-5 text-destructive">{modeError}</p>}
                </div>
              </div>
              <div className="flex shrink-0 items-center justify-end gap-3">
                {!modeReady && modeError ? <Button type="button" variant="outline" size="sm" onClick={fetchEnableAi} disabled={modeLoading}><RotateCcw />重新读取</Button> : <>
                <span role="status" className="text-xs text-muted-foreground">{modeLoading ? '读取中…' : modeSaving ? '更新中…' : enableAi ? '已启用' : '未启用'}</span>
                <button
                  type="button"
                  role="switch"
                  aria-label="AI启用开关"
                  aria-checked={Boolean(enableAi)}
                  aria-busy={modeSaving}
                  disabled={!modeReady || modeLoading || modeSaving}
                  onClick={toggleEnableAi}
                  className={`relative inline-flex h-7 w-12 shrink-0 rounded-full border transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-wait disabled:opacity-50 ${enableAi ? 'border-primary bg-primary' : 'border-border bg-muted'}`}
                >
                  <span className={`absolute left-1 top-1 h-[18px] w-[18px] rounded-full bg-card shadow-sm transition-transform ${enableAi ? 'translate-x-5' : 'translate-x-0'}`} />
                </button>
                </>}
              </div>
            </CardContent>
          </Card>

          {loading && <div role="status" className="flex items-center gap-2 rounded-xl border border-border bg-secondary px-4 py-3 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />正在加载已保存的 AI 内容…</div>}
          {loadError && <div role="alert" className="flex flex-col gap-3 rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 sm:flex-row sm:items-center sm:justify-between"><p className="flex items-start gap-2 text-sm leading-6 text-destructive"><AlertCircle className="mt-1 h-4 w-4 shrink-0" />{loadError}</p><Button type="button" variant="outline" size="sm" onClick={fetchAiConfig} className="shrink-0"><RotateCcw />重新加载</Button></div>}

          <form onSubmit={(event) => { event.preventDefault(); void handleSave() }} className="space-y-4">
            <Card className="border-border bg-card shadow-sm">
              <CardHeader className="border-b border-border px-4 py-4 sm:px-5">
                <CardTitle>内容配置</CardTitle>
                <CardDescription className="text-xs leading-5">提供真实经历，并说明希望使用的语气与长度。</CardDescription>
              </CardHeader>
              <fieldset disabled={formDisabled} className="min-w-0">
                <div className="space-y-2.5 p-4 sm:p-5">
                  <div className="flex items-center justify-between gap-3"><Label htmlFor="introduce" className="text-sm font-medium">个人技能与经历</Label><span className="text-xs text-muted-foreground">个人背景</span></div>
                  <Textarea
                    id="introduce"
                    value={aiConfig.introduce}
                    onChange={(e) => editConfig('introduce', e.target.value)}
                    aria-describedby="introduce-help"
                    placeholder="写下你的专业技能、项目经历与求职优势…"
                    className="min-h-[154px] resize-y bg-background px-3 py-2.5 text-sm leading-6"
                  />
                  <p id="introduce-help" className="text-xs leading-5 text-muted-foreground">建议写明擅长领域、实际项目与工作经验，为 AI 提供准确依据。</p>
                </div>
                <div className="space-y-2.5 border-t border-border p-4 sm:p-5">
                  <div className="flex items-center justify-between gap-3"><Label htmlFor="prompt" className="text-sm font-medium">提示词模板</Label><span className="text-xs text-muted-foreground">生成规则</span></div>
                  <Textarea
                    id="prompt"
                    value={aiConfig.prompt}
                    onChange={(e) => editConfig('prompt', e.target.value)}
                    aria-describedby="prompt-help"
                    placeholder="输入你的提示词模板，使用 %s 插入个人介绍与岗位信息…"
                    className="min-h-[176px] resize-y bg-background px-3 py-2.5 text-sm leading-6"
                  />
                  <p id="prompt-help" className="text-xs leading-5 text-muted-foreground">
                    保留模板中的 <code className="rounded-md bg-secondary px-1.5 py-0.5 text-secondary-foreground">%s</code> 占位符，投递时会自动填入个人介绍和岗位上下文。
                  </p>
                </div>
              </fieldset>
            </Card>

            <div className="sticky bottom-3 z-20 flex flex-col gap-3 rounded-xl border border-border bg-card p-3 shadow-sm sm:flex-row sm:items-center sm:justify-between sm:px-4">
              <div role={saveResult?.success === false ? 'alert' : 'status'} aria-live="polite" className={`flex min-w-0 items-start gap-2 text-sm leading-5 ${saveResult?.success === false ? 'text-destructive' : 'text-muted-foreground'}`}>
                {saveResult?.success === false ? <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" /> : dirty ? <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-primary" /> : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />}
                <span>{saving ? '正在保存，请稍候…' : saveResult?.message || (loading ? '正在读取配置' : loadError ? '加载成功后可编辑和保存' : dirty ? '有未保存的更改' : '所有更改已保存')}</span>
              </div>
              <Button type="submit" disabled={formDisabled || !dirty} className="h-10 w-full shrink-0 sm:w-auto">
                {saving ? <Loader2 className="animate-spin" /> : <Save />}{saving ? '正在保存…' : '保存 AI 配置'}
              </Button>
            </div>
          </form>
        </div>

        <aside className="rounded-2xl border border-border bg-card p-4 xl:sticky xl:top-6 xl:p-5">
          <div className="flex items-center gap-2 text-sm font-semibold"><Info className="h-4 w-4 text-primary" />使用说明</div>
          <ol className="mt-3 space-y-3 text-xs leading-5 text-muted-foreground">
            <li><span className="mr-2 font-semibold text-primary">1.</span>在系统设置中连接 AI 服务，填写地址、模型与密钥。</li>
            <li><span className="mr-2 font-semibold text-primary">2.</span>保存本页的个人介绍与提示词，再开启 AI 招呼语。</li>
            <li><span className="mr-2 font-semibold text-primary">3.</span>在工作台保留固定招呼语，生成失败时作为备用内容。</li>
          </ol>
          <p className="mt-3 text-xs leading-5 text-muted-foreground">切换页面不会自动保存内容，请先保存本页更改。</p>
          <div className="mt-4 space-y-3 border-t border-border pt-4">
            <Link href="/env-config" className="flex items-center justify-between text-sm font-medium text-primary">前往系统设置<ArrowUpRight className="h-4 w-4" /></Link>
            <Link href="/boss" className="flex items-center justify-between text-sm font-medium text-primary">返回投递工作台<ArrowUpRight className="h-4 w-4" /></Link>
          </div>
        </aside>
      </div>
    </div>
  )
}
