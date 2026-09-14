import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { settingsApi, healthApi } from '../api/settings'
import type { SettingsResponse } from '../types/settings'
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
  const [lat, setLat] = useState('')
  const [lon, setLon] = useState('')
  const [label, setLabel] = useState('')
  const [localError, setLocalError] = useState<string | null>(null)
  const [serverStatus, setServerStatus] = useState<'connected' | 'disconnected' | 'checking'>('checking')

  const { data: settings, isLoading, error, refetch } = useQuery({
    queryKey: ['settings'],
    queryFn: () => settingsApi.get(),
  })

  const healthQuery = useQuery({
    queryKey: ['health'],
    queryFn: () => healthApi.check(),
    retry: 1,
  })

  const updateMutation = useMutation({
    mutationFn: (body: SettingsResponse) => settingsApi.update(body),
    onSuccess: () => {
      setLocalError(null)
      refetch()
    },
    onError: (err) => {
      setLocalError(err instanceof Error ? err.message : '설정 저장 실패')
    },
  })

  useEffect(() => {
    return subscribeEvents(() => setEvents(listEvents()))
  }, [])

  useEffect(() => {
    if (settings) {
      setLat(settings.weather.lat.toString())
      setLon(settings.weather.lon.toString())
      setLabel(settings.weather.label)
    }
  }, [settings])

  useEffect(() => {
    if (healthQuery.isLoading) {
      setServerStatus('checking')
    } else if (healthQuery.isSuccess) {
      setServerStatus('connected')
    } else {
      setServerStatus('disconnected')
    }
  }, [healthQuery.isLoading, healthQuery.isSuccess, healthQuery.isError])

  const latNum = parseFloat(lat)
  const lonNum = parseFloat(lon)
  const isLatValid = lat === '' || (!isNaN(latNum) && latNum >= -90 && latNum <= 90)
  const isLonValid = lon === '' || (!isNaN(lonNum) && lonNum >= -180 && lonNum <= 180)
  const isLabelValid = label.length > 0 && label.length <= 50
  const isFormValid = isLatValid && isLonValid && isLabelValid

  const handleSave = () => {
    if (!isFormValid) {
      setLocalError('입력값을 확인해주세요')
      return
    }

    const updatedSettings: SettingsResponse = {
      weather: {
        lat: parseFloat(lat),
        lon: parseFloat(lon),
        label,
      },
    }

    updateMutation.mutate(updatedSettings)
  }

  const healthTone = ((): BadgeTone => {
    if (serverStatus === 'connected') return 'ok'
    if (serverStatus === 'disconnected') return 'danger'
    return 'neutral'
  })()

  const healthLabel =
    serverStatus === 'connected' ? '연결됨' : serverStatus === 'disconnected' ? '연결 안 됨' : '확인 중...'

  if (isLoading) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('settings')} />
        <p>{t('loading')}</p>
      </div>
    )
  }

  return (
    <div className="page page-with-header">
      <PageHeader title={t('settings')} />

      <ErrorBanner error={error} onRetry={() => refetch()} />
      <ErrorBanner error={localError ? new Error(localError) : null} />

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
          <h2 className="sheet-title">날씨 기본 위치</h2>

          <div className="field form-group">
            <label htmlFor="weather-lat">위도 (latitude)</label>
            <input
              id="weather-lat"
              type="number"
              min="-90"
              max="90"
              step="0.0001"
              value={lat}
              onChange={(e) => setLat(e.target.value)}
              placeholder="37.5663"
              aria-invalid={!isLatValid}
              aria-describedby={!isLatValid ? 'weather-lat-error' : undefined}
            />
            {!isLatValid && (
              <div id="weather-lat-error" className="field-error">
                -90 ~ 90 사이의 값을 입력해주세요
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="weather-lon">경도 (longitude)</label>
            <input
              id="weather-lon"
              type="number"
              min="-180"
              max="180"
              step="0.0001"
              value={lon}
              onChange={(e) => setLon(e.target.value)}
              placeholder="126.9779"
              aria-invalid={!isLonValid}
              aria-describedby={!isLonValid ? 'weather-lon-error' : undefined}
            />
            {!isLonValid && (
              <div id="weather-lon-error" className="field-error">
                -180 ~ 180 사이의 값을 입력해주세요
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="weather-label">위치 이름 (표시용)</label>
            <input
              id="weather-label"
              type="text"
              maxLength={50}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="서울시청"
              aria-invalid={!isLabelValid}
              aria-describedby="weather-label-hint weather-label-error"
            />
            <div id="weather-label-hint">
              {label.length}/50자
            </div>
            {!isLabelValid && (
              <div id="weather-label-error" className="field-error">
                {label.length === 0 ? '필수 입력값입니다' : '50자를 초과했습니다'}
              </div>
            )}
          </div>

          <Button
            variant="primary"
            onClick={handleSave}
            disabled={!isFormValid || updateMutation.isPending}
          >
            {updateMutation.isPending ? t('saving') : t('save')}
          </Button>

          <p>날씨는 인쇄 약 60분 전에 서버가 다시 받아 카드에 반영합니다.</p>
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
