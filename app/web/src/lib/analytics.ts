/** PoC 계측: 이 기기 localStorage만. 서버·외부로 보내지 않는다. */

export type AnalyticsEvent = {
  name: string
  at: string
  props?: Record<string, string>
}

const KEY = 'haru-events'
const LIMIT = 100
const listeners = new Set<() => void>()

export function track(name: string, props?: Record<string, string>) {
  const next: AnalyticsEvent[] = [...listEvents(), { name, at: new Date().toISOString(), props }]
  localStorage.setItem(KEY, JSON.stringify(next.slice(-LIMIT)))
  listeners.forEach((fn) => fn())
}

export function listEvents(): AnalyticsEvent[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as AnalyticsEvent[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function clearEvents() {
  localStorage.removeItem(KEY)
  listeners.forEach((fn) => fn())
}

export function subscribeEvents(fn: () => void) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
