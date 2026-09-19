import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import { printNowApi } from '../api/printNow'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import type { FormatSummary } from '../types/format'
import { formatDateTimeKo, timeAgoKo } from '../lib/date'
import { useI18n } from '../i18n'
import { track } from '../lib/analytics'
import { useDeviceStatus } from '../hooks/useDeviceStatus'
import { paperPolicyDescription } from '../lib/printReadiness'

export function PrintNowPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const initialFormatId = searchParams.get('formatId') || undefined

  const [formats, setFormats] = useState<FormatSummary[]>([])
  const [selectedFormatId, setSelectedFormatId] = useState<string>('')
  const [paperConfirmed, setPaperConfirmed] = useState(false)
  const [formatsLoading, setFormatsLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [sending, setSending] = useState(false)
  const [sendSuccess, setSendSuccess] = useState(false)

  // 기기 상태는 홈·예약과 캐시를 공유하는 공용 훅으로(lib/printReadiness.ts 참고).
  // 포맷 목록과 로딩 소스가 둘로 갈리므로, 페이지 진입 첫 화면은 둘 다 끝난 뒤에 그린다
  // (기존처럼 Promise.all로 한 번에 기다리던 것과 같은 체감 — 따로 다루면 기기 패널이
  // 늦게 팝인하며 깜빡인다).
  const { data: device, isLoading: deviceLoading, error: deviceError } = useDeviceStatus()

  useEffect(() => {
    const loadFormats = async () => {
      try {
        setError(null)
        const formatsRes = await formatsApi.list()

        setFormats(formatsRes)

        if (initialFormatId && formatsRes.some((f) => f.id === initialFormatId)) {
          setSelectedFormatId(initialFormatId)
        } else if (formatsRes.length > 0) {
          setSelectedFormatId(formatsRes[0].id)
        }
      } catch (e) {
        setError(e)
      } finally {
        setFormatsLoading(false)
      }
    }

    loadFormats()
  }, [initialFormatId])

  const loading = formatsLoading || deviceLoading
  const displayError = error ?? deviceError

  const handlePrintNow = async () => {
    if (!selectedFormatId) return

    try {
      setSending(true)
      setError(null)
      await printNowApi.send({
        formatId: selectedFormatId,
        paperConfirmed,
      })
      track('print_now')
      setSendSuccess(true)
      setPaperConfirmed(false)
    } catch (e) {
      setError(e)
    } finally {
      setSending(false)
    }
  }

  if (loading) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('tabPrintNow')} />
        <p>{t('loading')}</p>
      </div>
    )
  }

  if (formats.length === 0) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('tabPrintNow')} />
        <ErrorBanner error={displayError} onRetry={() => window.location.reload()} />
        {!displayError && (
          <EmptyState
            message={t('needFormatFirst')}
            actionLabel="포맷 만들기"
            onAction={() => navigate('/')}
          />
        )}
      </div>
    )
  }

  if (sendSuccess) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('tabPrintNow')} />
        <div className="banner banner-ok">
          <p>명령을 보냈습니다. Pi가 다음 폴링(최대 약 30초)에 받아 인쇄합니다.</p>
        </div>
        <Link to="/history" className="btn btn-primary">
          이력 보기
        </Link>
      </div>
    )
  }

  const lastPollAt = device?.lastPollAt
  const lastPollMinutesAgo = lastPollAt
    ? Math.floor((Date.now() - new Date(lastPollAt).getTime()) / 60000)
    : null
  const isPollOld = lastPollMinutesAgo !== null && lastPollMinutesAgo >= 2

  const paperPolicy = device?.paperPolicy || null
  const paperState = device?.paperState

  let checkboxRequired = false
  let checkboxLabel = '프린터 앞에서 용지를 눈으로 확인함'
  let disableReason: string | null = null

  if (paperPolicy === 'unverified') {
    checkboxRequired = true
    if (!paperConfirmed) {
      disableReason = '용지 감지가 아직 확인되지 않아, 용지를 직접 확인해야 인쇄할 수 있습니다'
    }
  } else if (paperPolicy === 'status_query') {
    checkboxRequired = false
  } else if (paperPolicy === 'manual_flag') {
    checkboxRequired = true
    if (!paperState?.loaded) {
      disableReason = '기기 화면에서 용지 장착됨을 켜세요'
    }
  }

  const printButtonDisabled =
    !selectedFormatId || sending || disableReason !== null || (checkboxRequired && !paperConfirmed)

  return (
    <div className="page page-with-header">
      <PageHeader title={t('tabPrintNow')} />

      <div className="stack">
        <ErrorBanner error={displayError} onRetry={() => window.location.reload()} />

        {device && (
        <Card>
          <div className="status-row">
            <span className="label">Pi 마지막 폴링:</span>
            <span className="value">
              {lastPollAt ? (
                <>
                  {formatDateTimeKo(lastPollAt)} ({timeAgoKo(lastPollAt)})
                </>
              ) : (
                '아직 접속 없음'
              )}
            </span>
          </div>
          <div className="status-row">
            <span className="label">용지 정책:</span>
            <span className="value">{paperPolicyDescription(paperPolicy)}</span>
          </div>
        </Card>
      )}

      <div className="field">
        <label htmlFor="format-select">포맷 선택</label>
        <select
          id="format-select"
          value={selectedFormatId}
          onChange={(e) => {
            setSelectedFormatId(e.target.value)
            setPaperConfirmed(false)
          }}
        >
          <option value="">선택하세요</option>
          {formats.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {selectedFormatId && (
        <div className="paper-frame">
          <img src={formatsApi.previewUrl(selectedFormatId)} alt="Preview" />
        </div>
      )}

      {isPollOld && (
        <div className="banner banner-warn">
          Pi가 최근에 접속하지 않았습니다. 명령은 10분 안에 Pi가 다시 접속하면 처리되고, 그렇지 않으면 만료되어
          인쇄되지 않습니다.
        </div>
      )}

      {(paperPolicy === 'unverified' || paperPolicy === 'status_query' || paperPolicy === 'manual_flag') && (
        <div className="form-group checkbox-group">
          <label htmlFor="paper-confirmed" className="checkbox-label">
            <input
              id="paper-confirmed"
              type="checkbox"
              checked={paperConfirmed}
              onChange={(e) => setPaperConfirmed(e.target.checked)}
            />
            {checkboxLabel}
          </label>
        </div>
      )}

      {disableReason &&
        (paperPolicy === 'manual_flag' && !paperState?.loaded ? (
          <div className="disable-reason">
            <Link to="/device">{disableReason}</Link>
          </div>
        ) : (
          <div className="disable-reason">{disableReason}</div>
        ))}

      <Button variant="primary" disabled={printButtonDisabled} onClick={handlePrintNow}>
        {sending ? '전송 중...' : '지금 인쇄'}
      </Button>
      </div>
    </div>
  )
}
