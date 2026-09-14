import { useEffect, useState } from 'react'
import { en } from './en'
import { ko, type MessageKey } from './ko'

export type Locale = 'ko' | 'en'

const KEY = 'haru-locale'
const tables = { ko, en } as const
const listeners = new Set<() => void>()

function readLocale(): Locale {
  const raw = localStorage.getItem(KEY)
  return raw === 'en' ? 'en' : 'ko'
}

let current: Locale = typeof localStorage === 'undefined' ? 'ko' : readLocale()

export function getLocale(): Locale {
  return current
}

export function t(key: MessageKey): string {
  return tables[current][key] ?? ko[key]
}

export function setLocale(next: Locale) {
  current = next
  localStorage.setItem(KEY, next)
  document.documentElement.lang = next === 'en' ? 'en' : 'ko'
  listeners.forEach((fn) => fn())
}

export function useI18n() {
  const [, bump] = useState(0)
  useEffect(() => {
    const fn = () => bump((n) => n + 1)
    listeners.add(fn)
    return () => {
      listeners.delete(fn)
    }
  }, [])
  return { t, locale: current, setLocale }
}

export function applyLocale() {
  document.documentElement.lang = current === 'en' ? 'en' : 'ko'
}
