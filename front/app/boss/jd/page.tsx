import type { Metadata } from 'next'
import JdContent from './JdContent'

export const metadata: Metadata = {
  title: 'JD 岗位库 · 觅途 Mitu',
  description: '留存目标岗位原文，积累岗位要求，为简历调整提供依据。',
}

export default function BossJdPage() { return <JdContent /> }
