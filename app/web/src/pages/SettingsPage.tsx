import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthApi } from '../api/settings'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Card } from '../components/Card'
import { Button } from '../components/Button'
import { Badge, type BadgeTone } from '../components/Badge'
import { useI18n, type Locale } from '../i18n'
import { getThemePref, setThemePref, type ThemePref } from '../lib/theme'
import { clearEvents, listEvents, subscribeEvents } from '../lib/analytics'

export function SettingsPage() {
  const { t, locale, setLocale } = useI18n()
  const [themePref, setThemePrefState] = useState<ThemePref>(() => getThemePref())
  const [events, setEvents] = useState(() => listEvents())
  const [serverStatus, setServerStatus] = useState<'connected' | 'disconnected' | 'checking'>('checking')

  const healthQuery = useQuery({
    queryKey: ['health'],
    queryFn: () => healthApi.check(),
    retry: 1,
  })

  useEffect(() => {
    return subscribeEvents(() => setEvents(listEvents()))
  }, [])

  useEffect(() => {
    if (healthQuery.isLoading) {
      setServerStatus('checking')
    } else if (healthQuery.isSuccess) {
      setServerStatus('connected')
    } else {
      setServerStatus('disconnected')
    }
  }, [healthQuery.isLoading, healthQuery.isSuccess, healthQuery.isError])

  const healthTone = ((): BadgeTone => {
    if (serverStatus === 'connected') return 'ok'
    if (serverStatus === 'disconnected') return 'danger'
    return 'neutral'
  })()

  const healthLabel =
    serverStatus === 'connected' ? '연결됨' : serverStatus === 'disconnected' ? '연결 안 됨' : '확인 중...'

  return (
    <div className="page page-with-header">
      <PageHeader title={t('settings')} />

      <ErrorBanner error={healthQuery.error} onRetry={() => healthQuery.refetch()} />

      <div className="stack">
        <Card>
          <h2 className="sheet-title">{t('appearance')}</h2>
          <div className="field">
            <label htmlFor="theme-pref">{t('theme')}</label>
            <select
              id="theme-pref"
              value={themePref}
              onChange={(e) => {
                const next = e.target.value as ThemePref
                setThemePrefState(next)
                setThemePref(next)
              }}
            >
              <option value="light">{t('themeLight')}</option>
              <option value="dark">{t('themeDark')}</option>
              <option value="system">{t('themeSystem')}</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="locale-pref">{t('language')}</label>
            <select
              id="locale-pref"
              value={locale}
              onChange={(e) => setLocale(e.target.value as Locale)}
            >
              <option value="ko">{t('langKo')}</option>
              <option value="en">{t('langEn')}</option>
            </select>
          </div>
        </Card>

        <Card>
          <h2 className="sheet-title">{t('analytics')}</h2>
          <p className="entry-time">{t('analyticsHint')}</p>
          {events.length === 0 ? (
            <p className="entry-time">{t('analyticsEmpty')}</p>
          ) : (
            <ul className="more-list">
              {[...events].reverse().slice(0, 8).map((ev) => (
                <li key={`${ev.at}-${ev.name}`}>
                  <span className="entry-time">
                    {ev.name} · {ev.at.slice(11, 19)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <Button
            variant="secondary"
            onClick={() => {
              clearEvents()
              setEvents([])
            }}
          >
            {t('analyticsClear')}
          </Button>
        </Card>

        <Card>
          <p className="entry-time">날씨 위치는 포맷의 날씨 위젯에서 정합니다.</p>
        </Card>

        <Card>
          <h2 className="sheet-title">앱 정보</h2>
          <div>
            <div>버전</div>
            <div>0.1.0</div>
          </div>
          <div>
            <div>서버 연결 상태</div>
            <Badge tone={healthTone}>{healthLabel}</Badge>
          </div>
        </Card>
      </div>
    </div>
  )
}
