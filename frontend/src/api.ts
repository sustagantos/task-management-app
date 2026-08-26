export type TaskStatus = 'OPEN' | 'DONE' | 'CANCELLED'
export type TaskContext = 'WORK' | 'PERSONAL'

export interface TaskView {
  id: string
  parentId: string | null
  title: string
  description: string | null
  priority: number
  context: TaskContext
  status: TaskStatus
  tags: string[]
  createdAt: string
  closedAt: string | null
  dueAt: string | null
  childrenDone: number
  childrenTotal: number
}

export interface MainPage {
  open: TaskView[]
  closedToday: TaskView[]
  /** Count only. The list itself lives on the history page. */
  closedYesterday: number
  /** The server's day-boundary timezone. The browser must not substitute its own. */
  timezone: string
}

export interface NewTask {
  title: string
  description?: string | null
  priority?: number
  context: TaskContext
  parentId?: string | null
  dueAt?: string | null
  tags?: string[]
}

/** Spring's CSRF cookie. Not HttpOnly by design - see SecurityConfig. */
function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = init.method ?? 'GET'
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) }
  if (method !== 'GET') {
    headers['Content-Type'] = 'application/json'
    headers['X-XSRF-TOKEN'] = csrfToken()
  }

  const res = await fetch(path, { ...init, method, headers, credentials: 'same-origin' })

  // The API answers 401 rather than redirecting, because a browser cannot
  // follow a cross-origin 302 from fetch(). Send the whole page to Entra.
  if (res.status === 401) {
    window.location.href = '/oauth2/authorization/entra'
    throw new Error('Session expired')
  }

  if (!res.ok) {
    // The server writes these messages for people to read.
    const body = await res.json().catch(() => null)
    throw new Error(body?.message ?? `Request failed (${res.status})`)
  }

  return res.status === 204 ? (undefined as T) : res.json()
}

export const getMainPage = () => api<MainPage>('/api/tasks')

export interface HistoryPage {
  items: TaskView[]
  /** Total before the limit, so the page can say "50 of 312" rather than leaving it ambiguous. */
  total: number
  limit: number
  offset: number
  /** Same day-boundary timezone the main page uses, so date inputs agree with the server. */
  timezone: string
}

export interface HistoryQuery {
  from?: string
  to?: string
  status?: 'DONE' | 'CANCELLED'
  context?: TaskContext
  tag?: string
  q?: string
  limit?: number
  offset?: number
}

export function getHistory(query: HistoryQuery) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return api<HistoryPage>('/api/tasks/history?' + params.toString())
}

export const createTask = (task: NewTask) =>
  api<TaskView>('/api/tasks', { method: 'POST', body: JSON.stringify(task) })

export const closeTask = (id: string) => api<TaskView>(`/api/tasks/${id}/close`, { method: 'POST' })
export const cancelTask = (id: string) => api<TaskView>(`/api/tasks/${id}/cancel`, { method: 'POST' })
export const reopenTask = (id: string) => api<TaskView>(`/api/tasks/${id}/reopen`, { method: 'POST' })

export async function signOut() {
  await fetch('/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
  })
  window.location.href = '/'
}

/**
 * Milliseconds until midnight in the SERVER's timezone.
 *
 * <p>"Closed today" is a server-side rule. A tab left open overnight must roll
 * over on the server's day boundary, not the browser's - otherwise a laptop in
 * another timezone shows yesterday's work as today's.
 */
export function msUntilLocalMidnight(timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date())

  const [h, m, s] = parts.split(':').map(Number)
  const elapsed = ((h * 60 + m) * 60 + s) * 1000
  return 24 * 60 * 60 * 1000 - elapsed + 1000
}

export const PRIORITY_LABELS = ['P0', 'P1', 'P2', 'P3']

export function ageInDays(iso: string): number {
  return Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
}

// ---- analytics ----

export interface ThroughputPoint {
  day: string
  workDone: number
  personalDone: number
  cancelled: number
  trailing7: number
  trailing28: number
}

export interface Throughput {
  points: ThroughputPoint[]
  timezone: string
}

export interface CycleTimeRow {
  dimension: 'OVERALL' | 'PRIORITY' | 'CONTEXT'
  bucket: string
  count: number
  p50Seconds: number | null
  p90Seconds: number | null
}

export interface BacklogPoint {
  week: string
  created: number
  closed: number
  net: number
  cumulative: number
}

export interface AgingBucket {
  bucket: string
  count: number
}

export interface OpenTask {
  id: string
  title: string
  priority: number
  context: TaskContext
  createdAt: string
  ageDays: number
}

export interface Aging {
  buckets: AgingBucket[]
  oldest: OpenTask[]
}

export interface ContextCount {
  context: TaskContext
  count: number
}

export interface WeeklyReview {
  done: ContextCount[]
  cancelled: ContextCount[]
  created: number
  closed: number
  net: number
  oldestOpen: OpenTask[]
  stale: OpenTask[]
  staleAfterDays: number
}

export const getThroughput = (days = 90) =>
  api<Throughput>(`/api/analytics/throughput?days=${days}`)

export const getCycleTime = (days = 180) =>
  api<CycleTimeRow[]>(`/api/analytics/cycle-time?days=${days}`)

export const getBacklog = (weeks = 12) =>
  api<BacklogPoint[]>(`/api/analytics/backlog?weeks=${weeks}`)

export const getAging = () => api<Aging>('/api/analytics/aging')

export const getWeeklyReview = () => api<WeeklyReview>('/api/analytics/weekly-review')

/**
 * Cycle time in the largest unit that still reads honestly.
 *
 * Minutes below an hour, hours below a day, then days with one decimal - a
 * median of "2.3d" says more than "199584s" or a rounded "2d".
 */
export function formatDuration(seconds: number | null): string {
  if (seconds === null || Number.isNaN(seconds)) return '-'
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)}h`
  return `${(seconds / 86400).toFixed(1)}d`
}

/** dd MMM for chart axes, in the server timezone. */
export function shortDate(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone, day: '2-digit', month: 'short' })
    .format(new Date(iso + 'T12:00:00Z'))
}
