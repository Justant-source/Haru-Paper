export type ThemePref = 'light' | 'dark' | 'system'

const KEY = 'haru-theme'
const listeners = new Set<() => void>()

export function getThemePref(): ThemePref {
  const raw = localStorage.getItem(KEY)
  if (raw === 'dark' || raw === 'light' || raw === 'system') return raw
  return 'light'
}

function systemDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function resolvedDark(pref: ThemePref = getThemePref()) {
  return pref === 'dark' || (pref === 'system' && systemDark())
}

export function applyTheme(pref: ThemePref = getThemePref()) {
  const dark = resolvedDark(pref)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  const meta = document.querySelector('meta[name="theme-color"]')
  meta?.setAttribute('content', dark ? '#1a1612' : '#f4ede3')
  listeners.forEach((fn) => fn())
}

export function setThemePref(pref: ThemePref) {
  localStorage.setItem(KEY, pref)
  applyTheme(pref)
}

export function subscribeTheme(fn: () => void) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}
