'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'
import { BarChart3, Settings2, Sparkles, LayoutDashboard, Moon, Sun, ArrowUpRight, Circle, GraduationCap, BookOpen } from 'lucide-react'
import { useTheme } from 'next-themes'
import { API_BASE } from '@/lib/api'
import { APP_NAME, APP_VERSION, APP_TAGLINE } from '@/lib/brand'
import BrandMark from './BrandMark'

const navigation = [
  { href: '/boss', icon: LayoutDashboard, label: '投递工作台', caption: 'WORKSPACE' },
  { href: '/boss/analysis', icon: BarChart3, label: '岗位统计', caption: 'INSIGHTS' },
  { href: '/boss/jd', icon: BookOpen, label: 'JD 岗位库', caption: 'JOB REQUIREMENTS' },
  { href: '/exam-assistant', icon: GraduationCap, label: '笔试助手', caption: 'EXAM ASSISTANT' },
  { href: '/ai-config', icon: Sparkles, label: 'AI 配置', caption: 'WRITING ASSISTANT' },
  { href: '/env-config', icon: Settings2, label: '系统设置', caption: 'SETTINGS' },
]

export default function Sidebar() {
  const pathname = usePathname()?.replace(/\/$/, '')
  const { resolvedTheme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [health, setHealth] = useState<'up' | 'degraded' | 'down' | 'unknown'>('unknown')

  useEffect(() => {
    setMounted(true)
    let active = true
    let checking = false
    const check = async () => {
      if (checking) return
      checking = true
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 3000)
      try {
        let response = await fetch(`${API_BASE}/api/health`, { signal: controller.signal })
        if (response.status === 404) response = await fetch(`${API_BASE}/actuator/health`, { signal: controller.signal })
        if (!response.ok) throw new Error('Health unavailable')
        const data = await response.json()
        const state = String(data.status || data.state || '').toUpperCase()
        if (active) setHealth(['UP', 'HEALTHY'].includes(state) ? 'up' : ['DEGRADED', 'WARN'].includes(state) ? 'degraded' : 'down')
      } catch {
        if (active) setHealth('unknown')
      } finally {
        clearTimeout(timeout)
        checking = false
      }
    }
    check()
    const interval = setInterval(check, 30000)
    return () => { active = false; clearInterval(interval) }
  }, [])

  const themeButton = (
    <button type="button" disabled={!mounted} onClick={() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark')}
      aria-label={resolvedTheme === 'dark' ? '切换到浅色' : '切换到深色'}
      className="inline-flex h-9 w-9 items-center justify-center rounded-xl border text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-40">
      {mounted && resolvedTheme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  )

  return (
    <aside className="border-b bg-card lg:fixed lg:inset-y-0 lg:left-0 lg:z-30 lg:flex lg:w-[232px] lg:flex-col lg:border-b-0 lg:border-r">
      <div className="flex items-center justify-between gap-3 px-5 py-5 lg:px-6 lg:pb-9 lg:pt-8">
        <Link href="/boss" className="flex items-center gap-3" aria-label={`${APP_NAME}首页`}>
          <BrandMark className="h-10 w-10 shrink-0 text-primary" />
          <div><div className="text-xl font-bold tracking-tight">{APP_NAME}<span className="ml-2 text-[11px] font-medium tracking-wide text-muted-foreground">Mitu</span></div><div className="mt-0.5 text-[10px] tracking-widest text-muted-foreground">{APP_TAGLINE}</div></div>
        </Link>
        <div className="lg:hidden">{themeButton}</div>
      </div>

      <nav aria-label="主导航" className="flex gap-1 overflow-x-auto px-3 pb-3 lg:block lg:space-y-1.5 lg:px-4">
        <p className="eyebrow mb-3 hidden px-3 lg:block">YOUR WORKSPACE</p>
        {navigation.map(({ href, icon: Icon, label, caption }) => {
          const isActive = pathname === href
          return (
            <Link key={href} href={href} aria-current={isActive ? 'page' : undefined}
              className={`group flex shrink-0 items-center gap-2.5 rounded-xl px-3 py-3 text-sm transition-colors lg:gap-3 ${isActive ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-secondary hover:text-foreground'}`}>
              <Icon size={18} strokeWidth={1.7} className="shrink-0" />
              <span><span className="block font-medium">{label}</span><span className={`mt-0.5 hidden text-[8px] font-medium tracking-[0.13em] lg:block ${isActive ? 'text-primary-foreground' : 'text-muted-foreground'}`}>{caption}</span></span>
              {isActive && <span className="ml-auto hidden h-1.5 w-1.5 rounded-full bg-[#ffab91] lg:block" />}
            </Link>
          )
        })}
      </nav>

      <div className="mx-5 mt-auto hidden space-y-5 pb-6 pt-10 lg:block">
        <div className="rounded-2xl border border-primary/10 bg-secondary p-4">
          <div className="mb-3 flex items-center justify-between"><span className="text-[10px] font-semibold tracking-[0.12em] text-muted-foreground">ONE STEP CLOSER</span><ArrowUpRight size={14} className="text-primary" /></div>
          <p className="text-sm font-semibold leading-relaxed">每一次主动，<br />都在靠近下一站。</p>
          <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">专注合适的机会，<br />用自己的节奏前进。</p>
        </div>
        <div className="flex items-center justify-between border-t pt-4">
          <div className="space-y-1.5"><div className="flex items-center gap-1.5 text-[11px] text-muted-foreground"><Circle size={7} fill="currentColor" className={health === 'up' ? 'text-emerald-600 dark:text-emerald-400' : health === 'degraded' ? 'text-amber-500' : 'text-muted-foreground'} />{health === 'up' ? '服务已连接' : health === 'degraded' ? '服务降级' : health === 'down' ? '服务异常' : '等待服务连接'}</div><div className="text-[10px] text-muted-foreground/70">Mitu · v{APP_VERSION}</div></div>
          {themeButton}
        </div>
      </div>
    </aside>
  )
}
