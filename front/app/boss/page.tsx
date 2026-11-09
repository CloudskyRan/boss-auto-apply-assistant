'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { createPortal } from 'react-dom'
import { BiBriefcase, BiSave, BiSearch, BiMap, BiBuilding, BiTrash, BiPlus, BiPlay, BiStop, BiLogOut, BiMessageSquareDetail, BiCheck } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import Link from 'next/link'
import { bossFormIssues, bossPayloadKey, buildBossPayload, toggleBossChoice } from '@/lib/boss-form'
import { API_BASE } from '@/lib/api'
import { useModalFocus } from '@/lib/use-modal-focus'

interface BossConfig {
  id?: number
  debugger?: number
  waitTime?: number
  maxSearchJobs?: number | null
  maxDeliveryJobs?: number | null
  keywords?: string
  cityCode?: string
  industry?: string
  jobType?: string
  experience?: string
  degree?: string
  salary?: string
  scale?: string
  stage?: string
  sayHi?: string
  enableAi?: number
  sendImgResume?: number
  filterDeadHr?: number
  deadStatus?: string
}

interface BossOption {
  id: number
  type: string
  name: string
  code: string
  // 可选的排序字段（来自后端/数据库），用于前端排序显示
  sort_order?: number
  sortOrder?: number
}

interface BossOptions {
  city: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  jobType: BossOption[]
  salary: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
}

interface BlacklistItem {
  id: number
  value: string
  type: string
}

interface DeliveryProgress {
  platform?: string
  type?: string
  message: string
  current?: number | null
  total?: number | null
}

interface JdCollection {
  created: number
  updated: number
  duplicate: number
  failed: number
}

interface QuietSettings {
  quietEnabled: boolean
  available: boolean
}

interface QuietStatus {
  enabled: boolean
  available: boolean
  windowState: 'normal' | 'minimized' | 'unknown'
  needsAttention: boolean
  message: string
  pendingAction?: 'show' | 'background' | ''
}

function quietStatusFrom(raw: unknown): QuietStatus | null {
  if (!raw || typeof raw !== 'object') return null
  const value = raw as Partial<QuietStatus>
  if (typeof value.enabled !== 'boolean' || typeof value.available !== 'boolean' || typeof value.needsAttention !== 'boolean'
    || !['normal', 'minimized', 'unknown'].includes(String(value.windowState))) return null
  return { enabled: value.enabled, available: value.available, needsAttention: value.needsAttention,
    windowState: value.windowState!, message: typeof value.message === 'string' ? value.message : '',
    pendingAction: value.pendingAction === 'show' || value.pendingAction === 'background' ? value.pendingAction : '' }
}

async function quietRequest(path: string, init?: RequestInit): Promise<Record<string, unknown>> {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : undefined
  const timeout = controller ? setTimeout(() => controller.abort(), 20_000) : undefined
  try {
    const response = await fetch(`${API_BASE}/api/boss/quiet${path}`, { ...init, cache: 'no-store', signal: controller?.signal })
    const data = await response.json().catch(() => null)
    if (!response.ok || !data || data.success === false) throw new Error(data?.message || '浏览器控制请求未完成。')
    return data
  } catch (error) {
    if (controller?.signal.aborted) throw new Error('浏览器控制请求超时，请确认任务状态后重试。')
    throw error
  } finally {
    if (timeout !== undefined) clearTimeout(timeout)
  }
}

export default function BossPage() {
  const [config, setConfig] = useState<BossConfig>({
    keywords: '',
    cityCode: '',
    industry: '',
    jobType: '',
    experience: '',
    degree: '',
    salary: '',
    scale: '',
    stage: '',
    sayHi: '',
    enableAi: 0,
    filterDeadHr: 0,
    maxSearchJobs: 0,
    maxDeliveryJobs: 0,
  })
  // 关键词显示用（无括号无引号，逗号分隔）
  const [keywordsDisplay, setKeywordsDisplay] = useState<string>('')
  // 职位类型只保留一个代码；其它筛选条件仍使用多选集合。
  const [selectedIndustry, setSelectedIndustry] = useState<string[]>([])
  const [selectedExperience, setSelectedExperience] = useState<string[]>([])
  const [selectedDegree, setSelectedDegree] = useState<string[]>([])
  const [selectedScale, setSelectedScale] = useState<string[]>([])
  const [selectedStage, setSelectedStage] = useState<string[]>([])
  const [selectedSalary, setSelectedSalary] = useState<string[]>([])
  const [selectedJobType, setSelectedJobType] = useState<string[]>([])
  const [options, setOptions] = useState<BossOptions>({
    city: [],
    industry: [],
    experience: [],
    jobType: [],
    salary: [],
    degree: [],
    scale: [],
    stage: [],
  })
  const [blacklist, setBlacklist] = useState<BlacklistItem[]>([])
  const [newBlacklistKeyword, setNewBlacklistKeyword] = useState('')
  const [blacklistType, setBlacklistType] = useState('company') // 默认为公司
  const [loading, setLoading] = useState(true)
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  const [deliveryProgress, setDeliveryProgress] = useState<DeliveryProgress | null>(null)
  const [jdCollection, setJdCollection] = useState<JdCollection | null>(null)
  const [quietSettings, setQuietSettings] = useState<QuietSettings | null>(null)
  const [quietDraft, setQuietDraft] = useState(true)
  const [quietState, setQuietState] = useState<QuietStatus | null>(null)
  const [quietLoading, setQuietLoading] = useState(true)
  const [quietBusy, setQuietBusy] = useState<'save' | 'show' | 'background' | ''>('')
  const [quietError, setQuietError] = useState('')
  const [quietNotice, setQuietNotice] = useState('')
  const [quietRevision, setQuietRevision] = useState(0)
  const quietActionRef = useRef(false)
  const startingDelivery = useRef(false)
  const statusSyncAfter = useRef(0)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string; title?: string } | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [savedKey, setSavedKey] = useState('')
  const [saving, setSaving] = useState(false)
  const [starting, setStarting] = useState(false)
  const [stopping, setStopping] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [statusKnown, setStatusKnown] = useState(false)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [blacklistBusy, setBlacklistBusy] = useState(false)
  const [blacklistError, setBlacklistError] = useState<string | null>(null)
  const [communicationOpen, setCommunicationOpen] = useState(false)
  const [validationVisible, setValidationVisible] = useState(false)
  const savingRef = useRef(false)
  const stopRequest = useRef(false)
  const logoutRef = useRef(false)
  const blacklistRequest = useRef(false)
  const statusRevision = useRef(0)
  const statusInFlight = useRef(false)
  const mounted = useRef(true)
  const initialSnapshotPending = useRef(false)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const activeDialogId = showLogoutResultDialog && logoutResult ? 'boss-logout-result' : showLogoutDialog ? 'boss-logout-confirm' : null
  useModalFocus(activeDialogId, () => {
    if (activeDialogId === 'boss-logout-result') setShowLogoutResultDialog(false)
    else setShowLogoutDialog(false)
  })

  const payload = buildBossPayload(config, keywordsDisplay, {
    industry: selectedIndustry, experience: selectedExperience, jobType: selectedJobType,
    degree: selectedDegree, scale: selectedScale, stage: selectedStage, salary: selectedSalary,
  })
  const payloadKey = bossPayloadKey(payload)
  const fieldIssues = bossFormIssues(payload)
  const deliveryIssues = bossFormIssues(payload, true)
  const dirty = Boolean(savedKey) && payloadKey !== savedKey
  const quietDirty = quietSettings !== null && quietDraft !== quietSettings.quietEnabled
  const formDisabled = loading || Boolean(loadError) || saving || starting || isDelivering || stopping || loggingOut

  useEffect(() => {
    if (!loading && !loadError && initialSnapshotPending.current) {
      initialSnapshotPending.current = false
      setSavedKey(payloadKey)
    }
  }, [loading, loadError, payloadKey])

  useEffect(() => {
    if (!dirty && !quietDirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, quietDirty])

  useEffect(() => {
    let active = true
    setQuietLoading(true)
    setQuietError('')
    quietRequest('/settings').then(data => {
      if (!active) return
      if (typeof data.quietEnabled !== 'boolean' || typeof data.available !== 'boolean') throw new Error('静默设置响应无效。')
      setQuietSettings({ quietEnabled: data.quietEnabled, available: data.available })
      setQuietDraft(data.quietEnabled)
      if (!data.available) setQuietError('静默投递暂不可用，请检查本地浏览器状态。')
    }).catch(error => {
      if (active) setQuietError(error instanceof Error ? error.message : '静默设置加载失败。')
    }).finally(() => { if (active) setQuietLoading(false) })
    return () => { active = false }
  }, [quietRevision])

  const readStatus = async () => {
    const response = await fetch(`${API_BASE}/api/boss/status`, { cache: 'no-store' })
    if (!response.ok) throw new Error('状态读取失败')
    const data = await response.json()
    if (typeof data.isRunning !== 'boolean' || typeof data.isLoggedIn !== 'boolean') throw new Error('状态格式异常')
    return data as { isRunning: boolean; isLoggedIn: boolean; jdCollection?: JdCollection; quiet?: QuietStatus }
  }

  const refreshStatus = useCallback(async () => {
    if (statusInFlight.current || startingDelivery.current || stopRequest.current || savingRef.current || logoutRef.current || quietActionRef.current) return
    statusInFlight.current = true
    const revision = statusRevision.current
    try {
      const data = await readStatus()
      if (!mounted.current || revision !== statusRevision.current) return
      setIsLoggedIn(data.isLoggedIn)
      setCheckingLogin(false)
      setStatusKnown(true)
      setStatusError(null)
      if (data.jdCollection) setJdCollection(data.jdCollection)
      const quiet = quietStatusFrom(data.quiet)
      if (quiet) setQuietState(quiet)
      if (Date.now() >= statusSyncAfter.current) {
        setIsDelivering(data.isRunning)
        if (!data.isRunning) setStopping(false)
      }
    } catch {
      if (mounted.current && revision === statusRevision.current) {
        setCheckingLogin(false)
        setStatusKnown(false)
        setStatusError('暂时无法确认任务状态，正在重试。')
      }
    } finally {
      statusInFlight.current = false
    }
  }, [])

  const saveQuietSettings = async () => {
    if (quietActionRef.current || formDisabled || !statusKnown || quietLoading || !quietSettings?.available || !quietDirty) return
    const submitted = quietDraft
    quietActionRef.current = true
    setQuietBusy('save'); setQuietError(''); setQuietNotice('')
    statusRevision.current++
    try {
      const status = await readStatus()
      if (!mounted.current) return
      setIsLoggedIn(status.isLoggedIn); setIsDelivering(status.isRunning)
      setStatusKnown(true); setStatusError(null)
      if (status.isRunning) throw new Error('任务正在运行，结束后才能修改静默模式。')
      const data = await quietRequest('/settings', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ quietEnabled: submitted }),
      })
      if (!mounted.current) return
      if (typeof data.quietEnabled !== 'boolean' || data.available !== true || data.quietEnabled !== submitted) {
        throw new Error('未确认静默设置已保存，请重试。')
      }
      setQuietSettings({ quietEnabled: data.quietEnabled, available: true })
      setQuietNotice(data.quietEnabled ? '静默模式已保存，下次任务将在后台运行。' : '普通显示模式已保存，下次任务使用该设置。')
    } catch (error) {
      if (mounted.current) setQuietError(error instanceof Error ? error.message : '静默设置保存失败，草稿已保留。')
    } finally {
      quietActionRef.current = false
      statusRevision.current++
      if (mounted.current) { setQuietBusy(''); refreshStatus() }
    }
  }

  const controlQuietBrowser = async (action: 'show' | 'background') => {
    if (quietActionRef.current || starting || stopping || loggingOut || saving || !statusKnown || !quietState?.available) return
    quietActionRef.current = true
    setQuietBusy(action); setQuietError(''); setQuietNotice('')
    statusRevision.current++
    try {
      const data = await quietRequest(`/${action}`, { method: 'POST' })
      if (!mounted.current) return
      if (data.accepted !== true) throw new Error('未确认浏览器控制请求已接收，请检查状态后重试。')
      const quiet = quietStatusFrom(data.quiet ?? data)
      if (quiet) setQuietState(quiet)
      setQuietNotice(action === 'show' ? '已请求显示浏览器，当前操作结束后执行。' : '已请求继续后台运行，当前操作结束后执行。')
    } catch (error) {
      if (mounted.current) setQuietError(error instanceof Error ? error.message : '浏览器控制失败，请重试。')
    } finally {
      quietActionRef.current = false
      statusRevision.current++
      if (mounted.current) { setQuietBusy(''); refreshStatus() }
    }
  }

  useEffect(() => {
    mounted.current = true
    refreshStatus()
    const interval = setInterval(refreshStatus, 3000)
    const progressClient = createSSEWithBackoff(`${API_BASE}/api/boss/stream`, {
      listeners: [{ name: 'progress', handler: (event) => {
        try {
          const progress: DeliveryProgress = JSON.parse(event.data)
          if (!mounted.current || (progress.platform && progress.platform !== 'boss')) return
          setDeliveryProgress(progress)
          // SSE 终态发出后还有清理；只有状态接口确认任务结束才释放操作。
          refreshStatus()
        } catch { /* 无效事件不覆盖已确认状态。 */ }
      } }],
    })
    return () => {
      mounted.current = false
      clearInterval(interval)
      progressClient.close()
    }
  }, [refreshStatus])

  useEffect(() => {
    fetchAllData()

    // 确保在客户端环境且 EventSource 可用
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => {
        console.log('[SSE] 连接已打开')
      },
      onError: (e, attempt, delay) => {
        console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setIsLoggedIn(data.bossLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              if (data.platform === 'boss') {
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => {
      client.close()
    }
  }, [])

  const fetchAllData = async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const response = await fetch(`${API_BASE}/api/boss/config`)
      if (!response.ok) throw new Error('配置读取失败')
      const data = await response.json()
      if (!data.config || !data.options) throw new Error('配置响应不完整')
      initialSnapshotPending.current = true
      setCommunicationOpen(!data.config.sayHi?.trim())

      if (data.config) {
        // 兼容旧接口可能返回的字符串/布尔值，统一成配置表使用的 0/1。
        const normalizeEnableAi = (raw: unknown): number => {
          const value = String(raw ?? '').trim().toLowerCase()
          return value === '1' || value === 'true' || value === 'on' || Number(raw) === 1 ? 1 : 0
        }
        // 规范化城市编码：后端可能返回单值或括号列表，此处取第一个值用于下拉回显
        const normalizeCityCode = (raw?: string): string => {
          if (!raw) return ''
          const list = parseListString(raw)
          if (list.length > 0) return list[0]
          return raw
        }
        setConfig({
          ...data.config,
          cityCode: normalizeCityCode(data.config.cityCode),
          enableAi: normalizeEnableAi(data.config.enableAi),
          maxSearchJobs: data.config.maxSearchJobs ?? 0,
          maxDeliveryJobs: data.config.maxDeliveryJobs ?? 0,
          // 职位类型由 selectedJobType 负责单选回显；这里保留原始值供兼容旧配置。
          jobType: data.config.jobType || '',
        })
        // 将后端存储的关键词（可能是 JSON 数组或括号列表）转为展示用逗号分隔文本
        const toDisplayKeywords = (raw?: string): string => {
          if (!raw) return ''
          const s = raw.trim()
          // 尝试作为 JSON 数组解析
          if (s.startsWith('[') && s.endsWith(']')) {
            try {
              const arr = JSON.parse(s)
              if (Array.isArray(arr)) {
                return arr.map((v) => String(v).trim()).filter((v) => v.length > 0).join(', ')
              }
            } catch (_) {
              // 非严格 JSON，如 [a,b]，走拆括号与逗号分隔
              const inner = s.slice(1, -1)
              return inner
                .split(',')
                .map((v) => v.trim().replace(/^"|"$/g, ''))
                .filter((v) => v.length > 0)
                .join(', ')
            }
          }
          // 普通文本：直接返回，去掉多余空格
          return s
        }
        setKeywordsDisplay(toDisplayKeywords(data.config.keywords))
        // 解析括号列表为数组
        setSelectedIndustry(parseListString(data.config.industry))
        setSelectedExperience(parseListString(data.config.experience))
        setSelectedDegree(parseListString(data.config.degree))
        setSelectedScale(parseListString(data.config.scale))
        setSelectedStage(parseListString(data.config.stage))
        setSelectedSalary(parseListString(data.config.salary))
      }
      if (data.options) {
        // 按 sort_order 或固定名称顺序排序；都没有时按名称兜底
        const CITY_ORDER = [
          // 顶层：全国 + 一线（北上广深）
          '全国','北京','上海','广州','深圳',
          // 准一线（图片顺序，从上到下）
          '杭州','成都','南京',
          '武汉','苏州','重庆','天津',
          '长沙','青岛','宁波','无锡',
          '西安','郑州','合肥','厦门','东莞',
          // 二线（图片顺序）
          '济南','福州','佛山','昆明','大连','沈阳','常州','哈尔滨','南昌','泉州',
          '南通','烟台','温州','贵阳','南宁','石家庄','长春','嘉兴','珠海','太原',
          '绍兴','金华','潍坊','徐州','惠州','台州','扬州','中山','乌鲁木齐','兰州',
          // 省会补充（图片底部出现的省会/直辖市）
          '海口','呼和浩特','银川'
        ]
        const orderMap = new Map<string, number>(CITY_ORDER.map((n, i) => [n, i + 1]))
        // 城市排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const cityList = data.options.city || []
        const cityHasOrder = cityList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        let sortedCity = cityHasOrder
          ? [...cityList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx // 都无排序，保持原序
                if (ar == null) return 1 // 无排序的排在已排序之后，保持原序
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx // 稳定排序
              })
              .map(({ o }) => o)
          : cityList

        // 兜底：如果后端未返回『不限』（code='0'），前端补充一个，确保可选
        const hasUnlimitedCity = sortedCity.some((c: BossOption) => c.code === '0' || c.name === '不限')
        if (!hasUnlimitedCity) {
          sortedCity = [{ id: -1, type: 'city', name: '不限', code: '0', sortOrder: 0 }, ...sortedCity]
        }

        // 行业排序：仅在后端提供 sortOrder 时按其排序；否则保留后端返回顺序
        const industryList = data.options.industry || []
        const industryHasOrder = industryList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
        const sortedIndustry = industryHasOrder
          ? [...industryList]
              .map((o, idx) => ({ o, idx }))
              .sort((a, b) => {
                const ar = a.o.sortOrder ?? a.o.sort_order
                const br = b.o.sortOrder ?? b.o.sort_order
                if (ar == null && br == null) return a.idx - b.idx
                if (ar == null) return 1
                if (br == null) return -1
                if (ar !== br) return ar - br
                return a.idx - b.idx
              })
              .map(({ o }) => o)
          : industryList

        setOptions({
          ...data.options,
          city: sortedCity,
          industry: sortedIndustry,
        })

        // 将配置中的中文值映射为代码，用于UI显示与选择匹配
        const toCodes = (opts: BossOption[], items: string[]) => {
          const codeSet = new Set(opts.map(o => o.code))
          return items.map(it => {
            if (codeSet.has(it)) return it
            const byName = opts.find(o => o.name === it)
            return byName ? byName.code : it
          })
        }

        // 城市：若当前为中文名，转换为对应的 code 以在下拉中回显
        const currentCityRaw = data.config?.cityCode || ''
        const currentCityHead = (() => {
          const list = parseListString(currentCityRaw)
          return list.length > 0 ? list[0] : currentCityRaw
        })()
        const cityMatchByCode = sortedCity.find((c: BossOption) => c.code === currentCityHead)
        const cityMatchByName = sortedCity.find((c: BossOption) => c.name === currentCityHead)
        const normalizedCityCode = cityMatchByCode ? cityMatchByCode.code : (cityMatchByName ? cityMatchByName.code : '0')
        setConfig(prev => ({ ...prev, cityCode: normalizedCityCode }))

        // 职位类型：兼容旧的括号/逗号列表，但只回显第一个选项。
        const currentJobTypeRaw = data.config?.jobType || ''
        const normalizedJobTypes = toCodes(data.options.jobType || [], parseListString(currentJobTypeRaw))
        // 后端空配置按“不限”处理；新配置严格保持一个职位类型。
        const selectedJobTypes = normalizedJobTypes.length > 0 ? [normalizedJobTypes[0]] : ['0']
        setSelectedJobType(selectedJobTypes)
        setConfig(prev => ({ ...prev, jobType: toBracketList(selectedJobTypes) }))

        // HR活跃过滤开关：后端为 0/1，前端直接回显为数字
        const normalizedFilterDeadHr = (data.config?.filterDeadHr ?? 0)
        setConfig(prev => ({ ...prev, filterDeadHr: normalizedFilterDeadHr }))

        // 其它多选选项：将中文名称转换为代码以匹配 MultiSelect 的 selected
        setSelectedIndustry(toCodes(data.options.industry || [], parseListString(data.config?.industry)))
        setSelectedExperience(toCodes(data.options.experience || [], parseListString(data.config?.experience)))
        setSelectedDegree(toCodes(data.options.degree || [], parseListString(data.config?.degree)))
        setSelectedScale(toCodes(data.options.scale || [], parseListString(data.config?.scale)))
        setSelectedStage(toCodes(data.options.stage || [], parseListString(data.config?.stage)))
        setSelectedSalary(toCodes(data.options.salary || [], parseListString(data.config?.salary)))
      }
      if (data.blacklist) {
        // 兼容处理：将type为'boss'的旧数据视为'company'
        const normalizedBlacklist = data.blacklist.map((item: BlacklistItem) => ({
          ...item,
          type: item.type === 'boss' ? 'company' : item.type
        }))
        setBlacklist(normalizedBlacklist)
      }
    } catch (error) {
      setLoadError('未能加载配置，请检查本地服务后重试。')
    } finally {
      setLoading(false)
    }
  }

  // 工具：解析括号列表字符串为数组，如 "[a,b]" 或 "a,b"
  const parseListString = (raw?: string): string[] => {
    if (!raw) return []
    let s = raw.trim()
    if (s.startsWith('[') && s.endsWith(']')) {
      s = s.slice(1, -1)
    }
    if (!s) return []
    return s
      .split(',')
      .map(v => v.trim().replace(/^"|"$/g, ''))
      .filter(v => v.length > 0)
  }

  // 工具：将数组转为括号列表字符串
  const toBracketList = (list: string[]): string => {
    if (!list || list.length === 0) return ''
    return `[${list.join(',')}]`
  }

  const handleSave = async (silent = false): Promise<boolean> => {
    if (savingRef.current || loading || loadError || logoutRef.current || (isDelivering && !startingDelivery.current)) return false
    const snapshot = payload
    const issues = bossFormIssues(snapshot)
    if (Object.keys(issues).length) {
      setValidationVisible(true)
      setSaveResult({ success: false, message: '请先修正职位类型和数量上限，再保存。' })
      return false
    }
    savingRef.current = true
    setSaving(true)
    statusRevision.current++
    try {
      if (!startingDelivery.current) {
        const status = await readStatus()
        setIsDelivering(status.isRunning)
        setIsLoggedIn(status.isLoggedIn)
        setStatusKnown(true)
        setStatusError(null)
        if (status.isRunning) throw new Error('已有任务在运行，请等待结束后再保存。')
      }
      const response = await fetch(`${API_BASE}/api/boss/config`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(snapshot),
      })
      const data = await response.json().catch(() => null)
      if (!response.ok || !data || data.success === false) throw new Error(data?.message || '配置保存失败。')
      setSavedKey(bossPayloadKey(snapshot))
      let cookieSaved = false
      try {
        const cookieResponse = await fetch(`${API_BASE}/api/cookie/save?platform=boss`, { method: 'POST' })
        const cookieData = await cookieResponse.json().catch(() => null)
        cookieSaved = cookieResponse.ok && cookieData?.success === true
      } catch { /* 配置已保存，登录状态保存失败单独提示。 */ }
      if (!silent || !cookieSaved) setSaveResult({ success: true, message: cookieSaved ? '配置已保存。' : '配置已保存；登录状态保存未成功，请检查本地服务。' })
      // 仅更新保存快照，不重载正在编辑的表单。
      return true
    } catch (error) {
      setSaveResult({ success: false, message: error instanceof Error ? error.message : '保存失败，请检查本地服务。' })
      return false
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const loadBlacklist = async () => {
    const response = await fetch(`${API_BASE}/api/boss/config/blacklist`, { cache: 'no-store' })
    if (!response.ok) throw new Error('黑名单加载失败，请重试。')
    const data: BlacklistItem[] = await response.json()
    if (!Array.isArray(data)) throw new Error('黑名单返回异常，请重试。')
    const normalized = data.map(item => ({ ...item, type: item.type === 'boss' ? 'company' : item.type }))
    setBlacklist(normalized)
    return normalized
  }

  const handleBlacklist = async (operation: 'refresh' | 'add' | 'delete', id?: number) => {
    if (blacklistRequest.current || (operation !== 'refresh' && formDisabled)) return
    const value = newBlacklistKeyword.trim()
    if (operation === 'add' && !value) return
    if (operation === 'add' && blacklist.some(item => item.type === blacklistType && item.value === value)) {
      setBlacklistError('该关键词已在此分类中。')
      return
    }
    blacklistRequest.current = true
    setBlacklistBusy(true)
    setBlacklistError(null)
    try {
      if (operation !== 'refresh') {
        const response = await fetch(`${API_BASE}/api/boss/config/blacklist${operation === 'delete' ? `/${id}` : ''}`, {
          method: operation === 'add' ? 'POST' : 'DELETE',
          ...(operation === 'add' ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value, type: blacklistType }) } : {}),
        })
        const data = await response.json().catch(() => null)
        if (!response.ok || !data || data.success === false) throw new Error(`${operation === 'add' ? '添加' : '删除'}未成功，请重试。`)
      }
      const updated = await loadBlacklist()
      if (operation === 'add' && !updated.some(item => item.type === blacklistType && item.value === value)) throw new Error('未能确认添加成功，输入已保留，请刷新后重试。')
      if (operation === 'delete' && updated.some(item => item.id === id)) throw new Error('未能确认删除成功，请刷新后重试。')
      if (operation === 'add') setNewBlacklistKeyword('')
    } catch (error) {
      setBlacklistError(error instanceof Error ? error.message : '黑名单操作失败，请检查本地服务。')
    } finally {
      blacklistRequest.current = false
      setBlacklistBusy(false)
    }
  }

  const focusCommunication = () => {
    setCommunicationOpen(true)
    setTimeout(() => document.getElementById('sayHi')?.focus(), 0)
  }

  const handleStartDelivery = async () => {
    if (isDelivering || startingDelivery.current || savingRef.current || logoutRef.current || loading || loadError || !statusKnown) return
    if (quietDirty || quietActionRef.current) {
      setQuietError(quietDirty ? '请先保存静默模式，再开始新任务。' : '请等待浏览器控制操作完成。')
      return
    }
    setValidationVisible(true)
    if (Object.keys(deliveryIssues).length) {
      setSaveResult({ success: false, message: '请完善搜索条件、招呼语和数量上限。' })
      if (deliveryIssues.keywords) document.getElementById('keywords')?.focus()
      else if (deliveryIssues.sayHi) focusCommunication()
      return
    }
    startingDelivery.current = true
    setStarting(true)
    statusRevision.current++
    setSaveResult(null)
    try {
      // 启动前再确认服务状态，防止其他页面已启动任务。
      const status = await readStatus()
      setIsLoggedIn(status.isLoggedIn)
      setIsDelivering(status.isRunning)
      setStatusKnown(true)
      setStatusError(null)
      if (status.isRunning || !status.isLoggedIn) {
        setSaveResult({ success: false, message: status.isRunning ? '已有任务在运行，请等待结束。' : '请先在 Boss 浏览器中登录。' })
        return
      }
      if (!await handleSave(true)) return
      setDeliveryProgress(null)
      setIsDelivering(true)
      statusSyncAfter.current = Date.now() + 5000
      const response = await fetch(`${API_BASE}/api/boss/start`, { method: 'POST' })
      const data = await response.json()
      if (!response.ok || !data.success) {
        statusSyncAfter.current = 0
        setSaveResult({ success: false, message: data.message || '启动失败，请检查服务。' })
      } else {
        statusSyncAfter.current = Date.now() + 5000
      }
    } catch {
      statusSyncAfter.current = 0
      setStatusKnown(false)
      setStatusError('启动结果暂时无法确认，正在同步任务状态。')
      setSaveResult({ success: false, message: '连接中断；状态确认前请等待，不要重复启动。' })
    } finally {
      startingDelivery.current = false
      setStarting(false)
      statusRevision.current++
      refreshStatus()
    }
  }

  const handleStopDelivery = async () => {
    if (stopRequest.current || stopping || startingDelivery.current || !isDelivering) return
    stopRequest.current = true
    setStopping(true)
    statusRevision.current++
    statusSyncAfter.current = 0
    try {
      const response = await fetch(`${API_BASE}/api/boss/stop`, { method: 'POST' })
      const data = await response.json()
      if (!response.ok || !data.success) throw new Error(data.message || '停止请求未成功，请重试。')
      setDeliveryProgress({ platform: 'boss', type: 'info', message: '正在停止，等待当前操作与清理结束…' })
    } catch (error) {
      setStopping(false)
      setSaveResult({ success: false, message: error instanceof Error ? error.message : '无法确认停止结果，请检查连接后重试。' })
    } finally {
      stopRequest.current = false
      statusRevision.current++
      refreshStatus()
    }
  }

  const triggerLogout = async () => {
    if (formDisabled || logoutRef.current) return
    logoutRef.current = true
    setLoggingOut(true)
    statusRevision.current++
    try {
      const status = await readStatus()
      if (status.isRunning) {
        setIsDelivering(true)
        throw new Error('任务仍在运行，结束后才能退出登录。')
      }
      const response = await fetch(`${API_BASE}/api/boss/logout`, { method: 'POST' })
      const data = await response.json()
      if (!response.ok || !data.success) throw new Error(data.message || '退出登录失败。')
      setIsLoggedIn(false)
      setLogoutResult({ success: true, message: '已退出登录，请在 Boss 浏览器中重新登录。' })
    } catch (error) {
      setLogoutResult({ success: false, message: error instanceof Error ? error.message : '退出登录失败，请检查本地服务。' })
    } finally {
      logoutRef.current = false
      setLoggingOut(false)
      setShowLogoutDialog(false)
      setShowLogoutResultDialog(true)
      refreshStatus()
    }
  }

  const targetCity = options.city.find((city) => city.code === config.cityCode)?.name || '未选择城市'
  const keywordCount = parseListString(keywordsDisplay.replace(/，/g, ',')).filter((keyword) => keyword.trim()).length
  const targetJobTypes = options.jobType.filter((option) => selectedJobType.includes(option.code)).map((option) => option.name).join('、') || '不限类型'
  const limitLabel = (value?: number | null) => value == null || value === 0 ? '不限' : Number.isInteger(value) && value > 0 ? value.toLocaleString() : '待修正'

  if (loading) {
    return <div className="flex min-h-[60vh] items-center justify-center text-sm text-muted-foreground">正在准备你的投递工作台…</div>
  }

  if (loadError) {
    return <div><PageHeader icon={<BiBriefcase />} title="投递工作台" subtitle="准备搜索条件与沟通内容，按你的节奏寻找机会。" /><Card><CardContent className="space-y-4 pt-6"><p role="alert" className="text-sm text-destructive">{loadError}</p><Button onClick={fetchAllData} variant="outline">重新加载</Button></CardContent></Card></div>
  }

  const taskLabel = starting ? '正在启动' : stopping ? '正在停止' : isDelivering ? quietState?.needsAttention ? '待人工处理' : '运行中' : statusKnown ? '待开始' : '状态待确认'
  const saveDisabled = formDisabled || !statusKnown || Boolean(Object.keys(fieldIssues).length)
  const startDisabled = formDisabled || blacklistBusy || !statusKnown || checkingLogin || !isLoggedIn || quietDirty || Boolean(quietBusy) || Boolean(Object.keys(fieldIssues).length)
  const preferences = [
    { id: 'salary', label: '薪资待遇', options: options.salary, selected: selectedSalary, onChange: setSelectedSalary },
    { id: 'experience', label: '工作经验', options: options.experience, selected: selectedExperience, onChange: setSelectedExperience },
    { id: 'degree', label: '学历要求', options: options.degree, selected: selectedDegree, onChange: setSelectedDegree },
    { id: 'industry', label: '公司行业', options: options.industry, selected: selectedIndustry, onChange: setSelectedIndustry },
    { id: 'scale', label: '公司规模', options: options.scale, selected: selectedScale, onChange: setSelectedScale },
    { id: 'stage', label: '融资阶段', options: options.stage, selected: selectedStage, onChange: setSelectedStage },
  ]

  return (
    <div className="space-y-5">
      <PageHeader icon={<BiBriefcase className="text-xl" />} title="投递工作台" subtitle="准备搜索条件与沟通内容，按你的节奏寻找机会。" actions={<Link href="/boss/analysis" className="inline-flex h-9 items-center justify-center gap-2 rounded-xl border border-border bg-card px-3 text-xs font-medium hover:bg-muted"><BiSearch />查看岗位记录</Link>} />
      <Tabs defaultValue="config" className="w-full">
        <TabsList className="mb-5 grid w-full grid-cols-2 sm:max-w-xs"><TabsTrigger value="config">投递设置</TabsTrigger><TabsTrigger value="blacklist">黑名单 · {blacklist.length}</TabsTrigger></TabsList>
        <TabsContent value="config" className="mt-0">
          <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
            <div className="min-w-0 space-y-5">
              <Card>
                <CardHeader className="pb-4"><CardTitle className="flex items-center gap-2"><BiSearch className="text-primary" />搜索与数量</CardTitle><CardDescription>多个关键词依次搜索，两项上限按整次任务累计。</CardDescription></CardHeader>
                <CardContent>
                  <fieldset disabled={formDisabled} className="grid min-w-0 grid-cols-1 gap-5 sm:grid-cols-2">
                    <div className="space-y-2 sm:col-span-2"><Label htmlFor="keywords">搜索关键词</Label><Input id="keywords" value={keywordsDisplay} onChange={e => setKeywordsDisplay(e.target.value)} placeholder="例如：Java 开发工程师, AI 产品经理" aria-invalid={validationVisible && Boolean(deliveryIssues.keywords)} aria-describedby="keywords-help" /><p id="keywords-help" className={`text-xs ${validationVisible && deliveryIssues.keywords ? 'text-destructive' : 'text-muted-foreground'}`}>{validationVisible && deliveryIssues.keywords || '多个关键词用逗号分隔。'}</p></div>
                    <div className="space-y-2"><Label htmlFor="city">工作城市</Label><Select id="city" disabled={formDisabled} value={config.cityCode || ''} onChange={e => setConfig({ ...config, cityCode: e.target.value })}>{options.city.map(city => <option key={city.id} value={city.code}>{city.name}</option>)}</Select><p className="text-xs text-muted-foreground">“不限”会搜索全国岗位。</p></div>
                    <div className="space-y-2"><Label htmlFor="jobType">职位类型</Label><Select id="jobType" disabled={formDisabled} value={selectedJobType[0] || '0'} onChange={e => setSelectedJobType([e.target.value])}><option value="0">不限</option>{options.jobType.filter(option => option.code !== '0').map(option => <option key={option.id} value={option.code}>{option.name}</option>)}</Select><p className={`text-xs ${validationVisible && fieldIssues.jobType ? 'text-destructive' : 'text-muted-foreground'}`}>{validationVisible && fieldIssues.jobType || '职位类型有且仅能选择一个。'}</p></div>
                    <div className="space-y-2"><Label htmlFor="maxSearchJobs">搜索岗位上限</Label><Input id="maxSearchJobs" type="number" min={0} max={2147483647} step={1} value={Number.isNaN(config.maxSearchJobs) ? '' : config.maxSearchJobs ?? ''} onChange={e => setConfig({ ...config, maxSearchJobs: e.target.value === '' ? null : e.target.valueAsNumber })} placeholder="0 表示不限" aria-invalid={Boolean(fieldIssues.maxSearchJobs)} aria-describedby="search-limit-help" /><p id="search-limit-help" className={`text-xs leading-5 ${fieldIssues.maxSearchJobs ? 'text-destructive' : 'text-muted-foreground'}`}>{fieldIssues.maxSearchJobs || '检查过的候选岗位计入，包含过滤、跳过和失败。'}</p></div>
                    <div className="space-y-2"><Label htmlFor="maxDeliveryJobs">本次投递上限</Label><Input id="maxDeliveryJobs" type="number" min={0} max={2147483647} step={1} value={Number.isNaN(config.maxDeliveryJobs) ? '' : config.maxDeliveryJobs ?? ''} onChange={e => setConfig({ ...config, maxDeliveryJobs: e.target.value === '' ? null : e.target.valueAsNumber })} placeholder="0 表示不限" aria-invalid={Boolean(fieldIssues.maxDeliveryJobs)} aria-describedby="delivery-limit-help" /><p id="delivery-limit-help" className={`text-xs leading-5 ${fieldIssues.maxDeliveryJobs ? 'text-destructive' : 'text-muted-foreground'}`}>{fieldIssues.maxDeliveryJobs || '按尝试发起沟通计数，发起后发送失败仍计入。'}</p></div>
                  </fieldset>
                  <p className="mt-4 rounded-xl bg-secondary/60 px-3 py-2.5 text-xs leading-5 text-primary">0 表示不限，达到任一上限即结束。数量只控制本次任务，不计算当天剩余名额。</p>
                </CardContent>
              </Card>
              <Card>
                <div className="flex items-center justify-between gap-3 p-5">
                  <div><h2 className="flex items-center gap-2 text-base font-semibold"><BiMessageSquareDetail className="text-primary" />沟通内容</h2><p className="mt-1 text-xs text-muted-foreground">{Number(config.enableAi) === 1 ? 'AI 按岗位生成' : '固定信息'} · {config.sayHi?.trim() ? '固定招呼语已填写' : '固定招呼语待填写'}</p></div>
                  <Button variant="outline" size="sm" aria-expanded={communicationOpen} aria-controls="communication-fields" onClick={() => setCommunicationOpen(!communicationOpen)}>{communicationOpen ? '收起' : '编辑'}</Button>
                </div>
                {communicationOpen && <CardContent id="communication-fields" className="space-y-4 border-t border-border pt-5">
                  <fieldset disabled={formDisabled} className="space-y-4">
                    <div className="space-y-2"><Label htmlFor="enableAi">招呼语模式</Label><Select id="enableAi" disabled={formDisabled} value={String(config.enableAi ?? 0)} onChange={e => setConfig({ ...config, enableAi: Number(e.target.value) })}><option value="0">固定信息</option><option value="1">AI 按岗位生成</option></Select></div>
                    <div className="space-y-2"><Label htmlFor="sayHi">固定招呼语</Label><Textarea id="sayHi" value={config.sayHi || ''} onChange={e => setConfig({ ...config, sayHi: e.target.value })} placeholder="填写向招聘者发送的信息" rows={5} maxLength={2000} aria-invalid={validationVisible && Boolean(deliveryIssues.sayHi)} aria-describedby="sayhi-help" /><p id="sayhi-help" className={`text-xs leading-5 ${validationVisible && deliveryIssues.sayHi ? 'text-destructive' : 'text-muted-foreground'}`}>{validationVisible && deliveryIssues.sayHi || `当前 ${(config.sayHi || '').length} 字。100 字以内优先作为首条招呼发送；超过100字时先发Boss默认招呼，再按历史聊天编辑器路径发送完整固定信息。若只想发送这段固定信息，请先在 Boss“消息通知-设置打招呼语”中关闭账号级自动招呼；否则平台会先发送默认提示词。AI 模式也使用此内容作为备用信息。`}</p></div>
                  </fieldset>
                  {Number(config.enableAi) === 1 && <Link href="/ai-config" className="text-xs font-medium text-primary underline underline-offset-4">查看 AI 配置</Link>}
                </CardContent>}
              </Card>
              <Card>
                <details className="group">
                  <summary className="flex cursor-pointer list-none items-center justify-between gap-3 p-5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/20"><span><span className="block text-base font-semibold">高级偏好</span><span className="mt-1 block text-xs text-muted-foreground">薪资、经验、学历、公司条件与 HR 活跃过滤</span></span><span className="shrink-0 text-xs text-primary group-open:hidden">展开</span><span className="hidden shrink-0 text-xs text-primary group-open:block">收起</span></summary>
                  <CardContent className="border-t border-border pt-5">
                    <fieldset disabled={formDisabled} className="grid min-w-0 grid-cols-1 gap-5 sm:grid-cols-2">
                      {preferences.map(item => <div key={item.id} className="space-y-2"><Label htmlFor={item.id}>{item.label}</Label><MultiSelect {...item} disabled={formDisabled} placeholder="不限" /></div>)}
                      <div className="space-y-2 sm:col-span-2"><Label htmlFor="filterDeadHr">HR 活跃过滤</Label><Select id="filterDeadHr" disabled={formDisabled} value={String(config.filterDeadHr ?? 0)} onChange={e => setConfig({ ...config, filterDeadHr: Number(e.target.value) })}><option value="0">关闭</option><option value="1">开启</option></Select><p className="text-xs text-muted-foreground">过滤活跃状态包含“年”的 HR，岗位记录仍会保留。</p></div>
                    </fieldset>
                  </CardContent>
                </details>
              </Card>
            </div>
            <aside className="space-y-4 xl:sticky xl:top-5">
              <Card className="overflow-hidden">
                <CardHeader className="bg-secondary/40 pb-4"><CardTitle className="flex items-center justify-between gap-2 text-base">本次任务<span className="rounded-full bg-card px-2.5 py-1 text-[11px] font-medium text-primary" role="status">{taskLabel}</span></CardTitle><CardDescription>{isDelivering ? '任务结束后可修改配置' : '检查准备情况，然后开始'}</CardDescription></CardHeader>
                <CardContent className="space-y-4 pt-5">
                  <div className="flex items-center gap-2 text-xs" role="status"><span className={`h-2 w-2 rounded-full ${isLoggedIn ? 'bg-primary' : 'bg-muted-foreground/50'}`} />{checkingLogin ? '正在检查登录' : isLoggedIn ? 'Boss 已登录' : '请在 Boss 浏览器中登录'}</div>
                  {statusError && <p className="text-xs leading-5 text-destructive" role="status">{statusError}</p>}
                  <dl className="space-y-2 rounded-xl bg-muted/40 p-3 text-xs"><div className="flex justify-between gap-3"><dt className="text-muted-foreground">搜索范围</dt><dd className="text-right">{targetCity} · {keywordCount} 个关键词</dd></div><div className="flex justify-between gap-3"><dt className="text-muted-foreground">职位类型</dt><dd className="max-w-[65%] text-right">{targetJobTypes}</dd></div><div className="flex justify-between gap-3"><dt className="text-muted-foreground">搜索 / 投递上限</dt><dd>{limitLabel(config.maxSearchJobs)} / {limitLabel(config.maxDeliveryJobs)}</dd></div></dl>
                  <div className="space-y-2 text-xs"><p className="flex items-center gap-2"><BiCheck className={keywordCount ? 'text-primary' : 'text-muted-foreground'} />{keywordCount ? '搜索关键词已填写' : '搜索关键词待填写'}</p><button type="button" onClick={focusCommunication} className="flex items-center gap-2 text-left hover:text-primary"><BiCheck className={config.sayHi?.trim() ? 'text-primary' : 'text-muted-foreground'} />{config.sayHi?.trim() ? '固定招呼语已填写 · 修改' : '填写固定招呼语'}</button></div>
                  {deliveryProgress && <div className={`rounded-xl p-3 text-xs leading-5 ${deliveryProgress.type === 'error' ? 'bg-destructive/10 text-destructive' : 'bg-secondary/60 text-primary'}`} role="status">{deliveryProgress.message}{deliveryProgress.current != null && deliveryProgress.total != null && <p className="mt-1 font-medium">{deliveryProgress.current} / {deliveryProgress.total}</p>}</div>}
                  <div className="rounded-xl border border-primary/15 bg-secondary/35 p-3"><div className="flex items-center justify-between gap-2"><span className="text-xs font-semibold">JD 自动留存</span><Link href="/boss/jd" className="text-[11px] font-medium text-primary hover:underline">查看岗位库 ↗</Link></div><p className="mt-1.5 text-[11px] leading-5 text-muted-foreground">通过筛选即保存，与沟通结果分别记录。</p>{jdCollection && <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-[11px]">{[['新增 JD', jdCollection.created], ['重复命中', jdCollection.duplicate], ['版本更新', jdCollection.updated], ['采集失败', jdCollection.failed]].map(([label, value]) => <div key={String(label)} className="flex items-center justify-between gap-2"><dt className="text-muted-foreground">{label}</dt><dd className="font-semibold">{value}</dd></div>)}</dl>}{Boolean(jdCollection?.failed) && <p className="mt-2 text-[11px] text-destructive">部分 JD 未成功留存，请查看任务日志。</p>}</div>
                  <div className="border-t border-border pt-4">
                    <p className={`mb-3 text-xs ${dirty ? 'text-primary' : 'text-muted-foreground'}`} role="status">{dirty ? '有未保存修改，开始时会自动保存' : '配置已同步'}</p>
                    <div className="grid grid-cols-2 gap-2"><Button variant="outline" disabled={saveDisabled} onClick={() => handleSave()}><BiSave />{saving ? '保存中' : '保存'}</Button>{isDelivering && !starting ? <Button variant="destructive" disabled={stopping} onClick={handleStopDelivery}><BiStop />{stopping ? '停止中' : '停止'}</Button> : <Button disabled={startDisabled} onClick={handleStartDelivery}><BiPlay />{starting ? '启动中' : '开始投递'}</Button>}</div>
                    <p className="mt-3 text-xs leading-5 text-muted-foreground">{starting ? '正在确认状态并保存当前配置。' : stopping ? '等待当前操作与清理结束后，才可再次启动。' : quietDirty ? '请先在下方保存静默模式，再开始新任务。' : Object.keys(fieldIssues).length ? '请修正左侧数量上限。' : !statusKnown ? '确认服务状态后才能开始。' : !isLoggedIn ? '登录后才能开始投递。' : '开始会先保存当前表单；任务运行时配置暂不可编辑。'}</p>
                  </div>
                  {saveResult && <p role="status" className={`rounded-xl p-3 text-xs leading-5 ${saveResult.success ? 'bg-secondary/60 text-primary' : 'bg-destructive/10 text-destructive'}`}>{saveResult.message}</p>}
                </CardContent>
              </Card>
              <Card>
                <CardHeader className="pb-3"><CardTitle className="text-base">静默投递</CardTitle><CardDescription>保留有界面浏览器，在后台处理岗位；不需要逐次显示窗口。</CardDescription></CardHeader>
                <CardContent className="space-y-3">
                  <fieldset disabled={formDisabled || !statusKnown || quietLoading || !!quietBusy || !quietSettings?.available}>
                    <label htmlFor="quiet-delivery" className="flex cursor-pointer items-center justify-between gap-4 rounded-xl bg-secondary/45 p-3 text-xs font-medium">
                      <span>静默投递模式</span><input id="quiet-delivery" type="checkbox" checked={quietDraft} onChange={event => { setQuietDraft(event.target.checked); setQuietNotice(''); setQuietError('') }} className="h-4 w-4 shrink-0 accent-primary" />
                    </label>
                  </fieldset>
                  <p className="text-[11px] leading-5 text-muted-foreground" role="status">{quietLoading ? '正在读取静默设置…' : !quietSettings ? '静默设置尚未加载，不会保存默认值。' : quietDirty ? '模式修改尚未保存，新任务开始前需先保存。' : quietSettings.quietEnabled ? '已保存：下次任务使用静默模式。' : '已保存：下次任务使用普通显示模式。'}{isDelivering && ' 任务运行中暂不能修改模式。'}</p>
                  <Button size="sm" variant="outline" className="w-full" disabled={formDisabled || !statusKnown || quietLoading || !!quietBusy || !quietSettings?.available || !quietDirty} onClick={saveQuietSettings}>{quietBusy === 'save' ? '保存模式中…' : '保存静默模式'}</Button>
                  {quietState && <p className="text-[11px] leading-5 text-muted-foreground" role="status">浏览器状态：{quietState.pendingAction ? quietState.pendingAction === 'show' ? '等待显示' : '等待进入后台' : quietState.windowState === 'minimized' ? '已最小化' : quietState.windowState === 'normal' ? '显示中' : '尚未确认'}{!quietState.available && ' · 当前无可控制的项目浏览器'}</p>}
                  {quietState?.needsAttention && <div role="alert" className="rounded-xl border border-amber-300/50 bg-amber-50 p-3 text-xs leading-5 text-amber-900 dark:border-amber-500/30 dark:bg-amber-950/30 dark:text-amber-200"><p className="font-semibold">任务等待人工处理</p><p className="mt-1">{quietState.message || '请查看浏览器，完成登录或验证后再继续后台运行。'}</p></div>}
                  {!quietState?.needsAttention && quietState?.message && <p className="text-[11px] leading-5 text-muted-foreground">{quietState.message}</p>}
                  <div className="grid grid-cols-2 gap-2"><Button size="sm" variant="outline" disabled={!!quietBusy || starting || stopping || loggingOut || saving || !statusKnown || !quietState?.available || !!quietState.pendingAction} onClick={() => controlQuietBrowser('show')}>{quietBusy === 'show' ? '请求中…' : '查看浏览器'}</Button><Button size="sm" variant="outline" disabled={!!quietBusy || starting || stopping || loggingOut || saving || !statusKnown || !quietState?.available || !!quietState.pendingAction} onClick={() => controlQuietBrowser('background')}>{quietBusy === 'background' ? '请求中…' : '继续后台'}</Button></div>
                  <p className="text-[11px] leading-5 text-muted-foreground">控制请求会在当前操作结束后处理；仅作用于本项目浏览器，不会启动投递。需要登录或验证时，请手动查看浏览器。</p>
                  {quietNotice && <p role="status" className="text-xs leading-5 text-primary">{quietNotice}</p>}
                  {quietError && <p role="alert" className="text-xs leading-5 text-destructive">{quietError}</p>}
                  {!quietLoading && (!quietSettings || !quietSettings.available) && <Button size="sm" variant="ghost" className="w-full" disabled={!!quietBusy} onClick={() => setQuietRevision(value => value + 1)}>重试读取静默设置</Button>}
                </CardContent>
              </Card>
              <Button disabled={formDisabled || blacklistBusy} variant="ghost" size="sm" className="w-full text-muted-foreground" onClick={() => setShowLogoutDialog(true)}><BiLogOut />退出 Boss 登录</Button>
            </aside>
          </div>
        </TabsContent>
        <TabsContent value="blacklist" className="mt-0">
          <Card><CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><div><CardTitle>排除条件</CardTitle><CardDescription className="mt-1">添加或删除立即生效，无需保存投递设置。</CardDescription></div><Button variant="outline" size="sm" disabled={blacklistBusy} onClick={() => handleBlacklist('refresh')}>{blacklistBusy ? '处理中' : '刷新黑名单'}</Button></div></CardHeader>
            <CardContent className="space-y-5">
              {dirty && <p className="text-xs text-primary">投递设置中的修改尚未保存，返回后可继续编辑。</p>}
              <div className="grid grid-cols-1 items-end gap-3 sm:grid-cols-[130px_minmax(0,1fr)_auto]"><div className="space-y-2"><Label htmlFor="blacklist-type">分类</Label><Select id="blacklist-type" disabled={formDisabled || blacklistBusy} value={blacklistType} onChange={e => setBlacklistType(e.target.value)}><option value="company">公司</option><option value="job">岗位</option><option value="recruiter">HR</option></Select></div><div className="space-y-2"><Label htmlFor="blacklist-value">排除关键词</Label><Input id="blacklist-value" disabled={formDisabled || blacklistBusy} value={newBlacklistKeyword} onChange={e => setNewBlacklistKeyword(e.target.value)} placeholder="输入希望排除的关键词" onKeyDown={e => { if (e.key === 'Enter') handleBlacklist('add') }} /></div><Button disabled={formDisabled || blacklistBusy || !newBlacklistKeyword.trim()} onClick={() => handleBlacklist('add')}><BiPlus />添加</Button></div>
              {blacklistError && <p role="alert" className="text-xs text-destructive">{blacklistError}</p>}
              {isDelivering && <p className="text-xs text-muted-foreground">任务运行期间暂不可更改黑名单。</p>}
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">{[{ type: 'company', label: '公司', icon: BiBuilding }, { type: 'job', label: '岗位', icon: BiBriefcase }, { type: 'recruiter', label: 'HR', icon: BiSearch }].map(group => {
                const items = blacklist.filter(item => item.type === group.type)
                const Icon = group.icon
                return <div key={group.type} className="min-w-0 rounded-xl border border-border p-4"><h3 className="mb-3 flex items-center gap-2 text-sm font-semibold"><Icon className="text-primary" />{group.label}<span className="ml-auto text-xs font-normal text-muted-foreground">{items.length} 条</span></h3>{items.length ? <div className="space-y-2">{items.map(item => <div key={item.id} className="flex items-center justify-between gap-2 rounded-lg bg-muted/50 px-3 py-2"><span className="min-w-0 break-words text-xs">{item.value}</span><Button variant="ghost" size="sm" disabled={formDisabled || blacklistBusy} onClick={() => handleBlacklist('delete', item.id)} className="h-7 shrink-0 px-1.5 text-muted-foreground hover:text-destructive" aria-label={`删除${group.label}黑名单 ${item.value}`}><BiTrash /></Button></div>)}</div> : <p className="py-6 text-center text-xs text-muted-foreground">暂无{group.label}排除条件</p>}</div>
              })}</div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {showLogoutDialog && (
        <div id="boss-logout-confirm" tabIndex={-1} className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/30 p-4" role="dialog" aria-modal={activeDialogId === 'boss-logout-confirm'} aria-hidden={activeDialogId !== 'boss-logout-confirm' || undefined} aria-label="确认退出登录">
          <Card className="w-full max-w-sm shadow-lg"><CardHeader><CardTitle className="flex items-center gap-2 text-lg"><BiLogOut className="text-primary" />确认退出登录</CardTitle></CardHeader><CardContent><p className="mb-6 text-sm leading-6 text-muted-foreground">退出后需要重新登录 Boss，才能继续投递。</p><div className="flex justify-end gap-2"><Button disabled={loggingOut} variant="outline" onClick={() => setShowLogoutDialog(false)}>取消</Button><Button disabled={loggingOut} variant="destructive" onClick={triggerLogout}>{loggingOut ? '退出中' : '确认退出'}</Button></div></CardContent></Card>
        </div>
      )}
      {showLogoutResultDialog && logoutResult && (
        <div id="boss-logout-result" tabIndex={-1} className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/30 p-4" role="dialog" aria-modal={activeDialogId === 'boss-logout-result'} aria-hidden={activeDialogId !== 'boss-logout-result' || undefined} aria-label="退出登录结果">
          <Card className="w-full max-w-sm shadow-lg"><CardHeader><CardTitle className="flex items-center gap-2 text-lg"><BiLogOut className={logoutResult.success ? 'text-primary' : 'text-destructive'} />{logoutResult.success ? '退出登录成功' : '退出登录失败'}</CardTitle></CardHeader><CardContent><p className="mb-6 text-sm leading-6 text-muted-foreground">{logoutResult.message}</p><div className="flex justify-end"><Button onClick={() => setShowLogoutResultDialog(false)}>知道了</Button></div></CardContent></Card>
        </div>
      )}

    </div>
  )
}
// 选择状态留在父表单；Portal 必须显式接收禁用状态。
function MultiSelect({ id, options, selected, onChange, placeholder, disabled = false }: {
  id?: string; options: BossOption[]; selected: string[]; onChange: (v: string[]) => void; placeholder?: string; disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [mounted, setMounted] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState({ top: 0, left: 0, width: 0 })
  useEffect(() => { setMounted(true) }, [])
  useEffect(() => { if (disabled) setOpen(false) }, [disabled])
  const updatePosition = useCallback(() => {
    const rect = buttonRef.current?.getBoundingClientRect()
    if (rect) setPosition({ top: rect.bottom + 8, left: rect.left, width: rect.width })
  }, [])
  useEffect(() => {
    if (!open) return
    updatePosition()
    const outside = (event: MouseEvent) => { if (!wrapperRef.current?.contains(event.target as Node) && !dropdownRef.current?.contains(event.target as Node)) setOpen(false) }
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); buttonRef.current?.focus() } }
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    window.addEventListener('scroll', updatePosition, true)
    window.addEventListener('resize', updatePosition)
    return () => {
      document.removeEventListener('mousedown', outside)
      document.removeEventListener('keydown', escape)
      window.removeEventListener('scroll', updatePosition, true)
      window.removeEventListener('resize', updatePosition)
    }
  }, [open, updatePosition])
  const selectedNames = options.filter(option => selected.includes(option.code)).map(option => option.name)
  return <div ref={wrapperRef} className="relative">
    <button id={id} ref={buttonRef} type="button" disabled={disabled} aria-expanded={open} aria-controls={open ? `${id}-choices` : undefined} onClick={() => setOpen(!open)} className="flex h-11 w-full min-w-0 items-center justify-between rounded-xl border border-input bg-card px-3.5 text-sm transition-colors hover:border-primary/40 focus:outline-none focus:ring-2 focus:ring-ring/20 disabled:cursor-not-allowed disabled:opacity-50"><span className="truncate">{selectedNames.join('，') || placeholder || '不限'}</span><span className="ml-2 text-xs text-muted-foreground">{open ? '▴' : '▾'}</span></button>
    {open && !disabled && mounted && createPortal(<div ref={dropdownRef} id={`${id}-choices`} className="dropdown-panel p-2" style={position}><div className="space-y-1">{options.map(option => {
      const checked = selected.includes(option.code)
      return <button key={option.id} type="button" role="checkbox" aria-checked={checked} onClick={() => { if (!disabled) onChange(toggleBossChoice(selected, option.code)) }} className={`flex w-full items-center gap-3 rounded-lg border px-3 py-2.5 text-left text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring/20 ${checked ? 'border-primary/20 bg-secondary text-primary' : 'border-transparent hover:bg-muted'}`}><span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${checked ? 'border-primary bg-primary text-primary-foreground' : 'border-input'}`}>{checked && <BiCheck />}</span><span className="truncate">{option.name}</span></button>
    })}</div></div>, document.body)}
  </div>
}
