'use client'

import { usePathname } from 'next/navigation'
import { useEffect, useState, ReactNode } from 'react'
import { ChevronRight, CalendarDays } from 'lucide-react'
import { APP_NAME } from '@/lib/brand'

export default function ContentArea({ children }: { children: ReactNode }) {
  const pathname = usePathname()?.replace(/\/$/, '')
  const [date, setDate] = useState('')
  useEffect(() => {
    setDate(new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date()))
  }, [])
  const title = pathname === '/boss/analysis' ? '岗位统计' : pathname === '/boss/jd' ? 'JD 岗位库' : pathname === '/exam-assistant' ? '笔试助手' : pathname === '/ai-config' ? 'AI 配置' : pathname === '/env-config' ? '系统设置' : '投递工作台'
  return (
    <main id="main-content" tabIndex={-1} className="min-h-screen min-w-0 bg-background outline-none lg:ml-[232px]">
      <div className="flex h-12 items-center justify-between gap-3 border-b px-4 text-xs sm:px-6 lg:px-8">
        <div className="flex items-center gap-2 text-muted-foreground"><span>{APP_NAME}</span><ChevronRight size={13} /><span className="font-medium text-foreground">{title}</span></div>
        <div className="hidden items-center gap-2 text-muted-foreground sm:flex"><CalendarDays size={13} /><span>{date}</span></div>
      </div>
      <div className="mx-auto w-full max-w-[1440px] px-4 py-5 sm:px-6 lg:px-8 lg:py-6">{children}</div>
      <footer className="mx-5 flex flex-wrap items-center justify-between gap-2 border-t py-5 text-[10px] tracking-wide text-muted-foreground sm:mx-8 lg:mx-10"><span>MITU · YOUR NEXT CHAPTER</span><span>每一步，都由你决定。</span></footer>
    </main>
  )
}
