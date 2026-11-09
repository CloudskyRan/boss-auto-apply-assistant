'use client'

import { useState, useEffect, useRef } from 'react'
import Link from 'next/link'
import { AlertCircle, ArrowUpRight, CheckCircle2, Eye, EyeOff, Info, KeyRound, Loader2, RotateCcw, Save, Server, Settings2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

type EnvConfigValues = { baseUrl: string; apiKey: string; model: string }

export default function EnvConfig() {
  const [envConfig, setEnvConfig] = useState<EnvConfigValues>({
    baseUrl: '',
    apiKey: '',
    model: '',
  })

  const [showApiKey, setShowApiKey] = useState(false)
  const [savedConfig, setSavedConfig] = useState<EnvConfigValues | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const request = useRef<AbortController | null>(null)
  const saveInFlight = useRef(false)
  const dirty = savedConfig !== null && (Object.keys(envConfig) as (keyof EnvConfigValues)[]).some((key) => envConfig[key] !== savedConfig[key])
  const formDisabled = loading || savedConfig === null || saving

  // 从数据库加载配置
  const fetchConfig = async () => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setLoading(true)
    setLoadError(null)
    try {
      const response = await fetch(`${API_BASE}/api/config`, { signal: controller.signal })
      if (!response.ok) throw new Error('load_failed')
      const result = await response.json()
      if (!result.success || !result.data) throw new Error('load_failed')
      if (controller.signal.aborted) return
      const config = {
        baseUrl: result.data.BASE_URL || '',
        apiKey: result.data.API_KEY || '',
        model: result.data.MODEL || '',
      }
      setEnvConfig(config)
      setSavedConfig(config)
    } catch {
      if (!controller.signal.aborted) setLoadError('设置加载失败。请重试后再编辑，避免覆盖已有 AI 连接配置。')
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }

  useEffect(() => {
    void fetchConfig()
    return () => request.current?.abort()
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

  const handleSave = async () => {
    if (formDisabled || !dirty || saveInFlight.current) return
    saveInFlight.current = true
    setSaving(true)
    setSaveResult(null)
    const snapshot = { ...envConfig }
    try {
      const configMap = {
        BASE_URL: snapshot.baseUrl,
        API_KEY: snapshot.apiKey,
        MODEL: snapshot.model,
      }

      const response = await fetch(`${API_BASE}/api/config`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(configMap),
      })

      if (!response.ok) throw new Error('save_failed')
      const result = await response.json()
      if (!result.success) throw new Error('save_failed')
      setSavedConfig(snapshot)
      setSaveResult({ success: true, message: 'AI 服务连接设置已保存。' })
    } catch {
      setSaveResult({ success: false, message: '保存未完成，编辑内容仍保留。请检查服务连接后重试。' })
    } finally {
      saveInFlight.current = false
      setSaving(false)
    }
  }

  const editConfig = <K extends keyof EnvConfigValues>(field: K, value: EnvConfigValues[K]) => {
    setEnvConfig((current) => ({ ...current, [field]: value }))
    setSaveResult(null)
  }

  return (
    <div className="mx-auto w-full max-w-[1120px] space-y-5">
      <PageHeader
        icon={<Settings2 className="h-5 w-5" />}
        title="系统设置"
        subtitle="配置 AI 服务的连接信息。"
      />

      {loading && (
        <div role="status" className="flex items-center gap-2 rounded-xl border border-border bg-secondary px-4 py-3 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />正在加载已保存的设置…
        </div>
      )}
      {loadError && <div role="alert" className="flex flex-col gap-3 rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 sm:flex-row sm:items-center sm:justify-between"><p className="flex items-start gap-2 text-sm leading-6 text-destructive"><AlertCircle className="mt-1 h-4 w-4 shrink-0" />{loadError}</p><Button type="button" variant="outline" size="sm" onClick={fetchConfig} className="shrink-0"><RotateCcw />重新加载</Button></div>}

      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_248px]">
        <form onSubmit={(event) => { event.preventDefault(); void handleSave() }} className="min-w-0 space-y-4">
          <fieldset disabled={formDisabled} className="min-w-0 space-y-4">
            <Card className="border-border bg-card shadow-sm">
              <CardHeader className="border-b border-border px-4 py-4 sm:px-5">
                <div className="flex items-center gap-3">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-secondary text-primary"><Server className="h-4 w-4" /></div>
                  <div className="space-y-1">
                    <CardTitle>AI 服务连接</CardTitle>
                    <CardDescription className="text-xs leading-5">生成 AI 招呼语时使用，固定信息模式可跳过。</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent className="space-y-4 p-4 sm:p-5">
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="baseUrl" className="text-sm font-medium text-foreground">服务地址 <span className="ml-1 font-normal text-muted-foreground">Base URL</span></Label>
                    <Input
                      id="baseUrl"
                      type="text"
                      value={envConfig.baseUrl}
                      onChange={(e) => editConfig('baseUrl', e.target.value)}
                      aria-describedby="base-url-help"
                      placeholder="输入你的 AI 服务地址"
                      className="h-10 bg-background"
                    />
                    <p id="base-url-help" className="text-xs leading-5 text-muted-foreground">服务商提供的 API 基础地址。</p>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="model" className="text-sm font-medium text-foreground">模型名称 <span className="ml-1 font-normal text-muted-foreground">Model</span></Label>
                    <Input
                      id="model"
                      type="text"
                      value={envConfig.model}
                      onChange={(e) => editConfig('model', e.target.value)}
                      aria-describedby="model-help"
                      placeholder="输入服务商提供的模型名称"
                      className="h-10 bg-background"
                    />
                    <p id="model-help" className="text-xs leading-5 text-muted-foreground">需与你的服务账号支持的模型一致。</p>
                  </div>
                </div>
                <div className="space-y-2 border-t border-border pt-4">
                  <Label htmlFor="apiKey" className="flex items-center gap-2 text-sm font-medium text-foreground"><KeyRound className="h-4 w-4 text-muted-foreground" />访问密钥 <span className="ml-1 font-normal text-muted-foreground">API Key</span></Label>
                  <div className="relative">
                    <Input
                      id="apiKey"
                      type={showApiKey ? 'text' : 'password'}
                      value={envConfig.apiKey}
                      onChange={(e) => editConfig('apiKey', e.target.value)}
                      aria-describedby="api-key-help"
                      placeholder="输入 API 访问密钥"
                      autoComplete="off"
                      className="h-10 bg-background pr-24"
                    />
                    <Button
                      onClick={() => setShowApiKey(!showApiKey)}
                      variant="ghost"
                      size="sm"
                      aria-label={showApiKey ? '隐藏API密钥' : '显示API密钥'}
                      aria-pressed={showApiKey}
                      className="absolute right-1.5 top-1/2 h-8 -translate-y-1/2 rounded-lg px-2.5 text-muted-foreground hover:bg-secondary"
                      type="button"
                    >
                      {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}{showApiKey ? '隐藏' : '显示'}
                    </Button>
                  </div>
                  <p id="api-key-help" className="text-xs leading-5 text-muted-foreground">默认隐藏，密钥与其他设置一起保存到本机。</p>
                </div>
              </CardContent>
            </Card>

            </fieldset>

          <div className="sticky bottom-3 z-20 flex flex-col gap-3 rounded-xl border border-border bg-card p-3 shadow-sm sm:flex-row sm:items-center sm:justify-between sm:px-4">
            <div role={saveResult?.success === false ? 'alert' : 'status'} aria-live="polite" className={`flex min-w-0 items-start gap-2 text-sm leading-5 ${saveResult?.success === false ? 'text-destructive' : 'text-muted-foreground'}`}>
              {saveResult?.success === false ? <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" /> : dirty ? <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-primary" /> : <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />}
              <span>{saving ? '正在保存，请稍候…' : saveResult?.message || (loading ? '正在读取配置' : loadError ? '加载成功后可编辑和保存' : dirty ? '有未保存的更改' : '所有更改已保存')}</span>
            </div>
            <Button type="submit" disabled={formDisabled || !dirty} className="h-10 w-full shrink-0 sm:w-auto">
              {saving ? <Loader2 className="animate-spin" /> : <Save />}{saving ? '正在保存…' : '保存设置'}
            </Button>
          </div>
        </form>

        <aside className="rounded-2xl border border-border bg-card p-4 xl:sticky xl:top-6 xl:p-5">
          <div className="flex items-center gap-2 text-sm font-semibold"><Info className="h-4 w-4 text-primary" />设置说明</div>
          <div className="mt-3 space-y-3 text-xs leading-5 text-muted-foreground">
            <p>使用固定招呼语时无需配置 AI 服务。启用 AI 前，填写服务地址、模型与密钥。</p>
            <p>切换页面不会自动保存设置，请先保存本页更改。</p>
          </div>
          <div className="mt-4 space-y-3 border-t border-border pt-4">
            <Link href="/ai-config" className="flex items-center justify-between text-sm font-medium text-primary">设置 AI 表达方式<ArrowUpRight className="h-4 w-4" /></Link>
            <Link href="/boss" className="flex items-center justify-between text-sm font-medium text-primary">返回投递工作台<ArrowUpRight className="h-4 w-4" /></Link>
          </div>
        </aside>
      </div>
    </div>
  )
}
