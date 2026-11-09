import type { Metadata } from "next"
import AnalysisContent from "@/app/boss/analysis/AnalysisContent"

export const metadata: Metadata = {
  title: "岗位统计 · 觅途 Mitu",
  description: "查看真实岗位记录、投递结果与岗位分布，整理你的求职进程。",
}

export default function BossAnalysisPage() {
  return <AnalysisContent showHeader />
}
