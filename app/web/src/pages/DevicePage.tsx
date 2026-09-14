import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { deviceApi } from '../api/device'
import { timeAgoKo, formatDateTimeKo } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { Card } from '../components/Card'
import { Badge, type BadgeTone } from '../components/Badge'
import { Toggle } from '../components/Toggle'
import { useI18n } from '../i18n'

export function DevicePage() {
  const { t } = useI18n()
  const [paperToggleError, setPaperToggleError] = useState<string | null>(null)
  const [paperTogglePending, setPaperTogglePending] = useState(false)

  const { data: device, error, refetch, isLoading } = useQuery({
    queryKey: ['device'],
    queryFn: () => deviceApi.get(),
    refetchInterval: 30000, // 30초마다 재조회
  })

  const setPaperStateMutation = useMutation({
    mutationFn: (loaded: boolean) => deviceApi.setPaperState(loaded),
    onError: () => {
      setPaperToggleError('용지 상태 변경 실패')
      refetch()
    },
    onSuccess: () => {
      setPaperToggleError(null)
      refetch()
    },
  })

  const handlePaperToggle = async (next: boolean) => {
    setPaperTogglePending(true)
    try {
      await setPaperStateMutation.mutateAsync(next)
    } finally {
      setPaperTogglePending(false)
    }
  }

  if (isLoading) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('device')} />
        <p>{t('loading')}</p>
      </div>
    )
  }

  if (!device?.lastPollAt) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('device')} />
        <ErrorBanner error={error} onRetry={() => refetch()} />
        <EmptyState message="아직 Pi가 연결되지 않았습니다." />
      </div>
    )
  }

  const pollDiffMs = Date.now() - new Date(device.lastPollAt).getTime()
  const pollDiffMin = Math.floor(pollDiffMs / 1000 / 60)
  const isOldPoll = pollDiffMin >= 2

  const getStatusTone = (state: string): BadgeTone => {
    switch (state) {
      case 'ok':
        return 'ok'
      case 'offline':
      case 'error':
        return 'danger'
      default:
        return 'neutral'
    }
  }

  const getStatusLabel = (state: string) => {
    switch (state) {
      case 'ok':
        return '정상'
      case 'offline':
        return '프린터 연결 안 됨'
      case 'error':
        return '오류'
      case 'unknown':
        return '알 수 없음'
      default:
        return state
    }
  }

  const getPolicyDescription = (policy: string | null) => {
    switch (policy) {
      case 'unverified':
        return '프린터 용지 감지가 아직 확인되지 않음. 예약 인쇄는 모의 실행만 기록하고, 지금 인쇄만 사람 확인 후 전송'
      case 'status_query':
        return 'Pi가 인쇄 직전 프린터에 상태를 물어 용지 확인'
      case 'manual_flag':
        return '서버의 수동 "용지 장착됨" 상태가 켜져 있을 때만 무인 인쇄'
      default:
        return '정책 없음'
    }
  }

  const isPaperStateDisabled = device.paperPolicy !== 'manual_flag'
  const currentPaperLoaded = device.paperState?.loaded ?? false

  return (
    <div className="page page-with-header">
      <PageHeader title={t('device')} />

      <ErrorBanner error={error} onRetry={() => refetch()} />
      <ErrorBanner error={paperToggleError ? new Error(paperToggleError) : null} />

      <div className="stack">
        <Card>
          <h2 className="sheet-title">Pi 마지막 폴링</h2>
          <p>
            {formatDateTimeKo(device.lastPollAt)} ({timeAgoKo(device.lastPollAt)})
          </p>
          {isOldPoll && (
            <div className="banner banner-warn" role="status">
              2분 이상 응답이 없습니다.
            </div>
          )}
        </Card>

        <Card>
          <h2 className="sheet-title">프린터 프로필</h2>
          {device.printerProfile ? (
            <dl>
              <div>
                <dt>모델</dt>
                <dd>{device.printerProfile.model}</dd>
              </div>
              <div>
                <dt>DPI</dt>
                <dd>{device.printerProfile.dpi}</dd>
              </div>
              <div>
                <dt>용지 너비</dt>
                <dd>{device.printerProfile.paperWidthMm}mm</dd>
              </div>
              <div>
                <dt>인쇄 가능 폭</dt>
                <dd>{device.printerProfile.printableWidthPx}px</dd>
              </div>
            </dl>
          ) : (
            <p>아직 연결된 적 없음</p>
          )}
          {device.printerStatus && (
            <div>
              <h3 className="sheet-title">프린터 상태</h3>
              <Badge tone={getStatusTone(device.printerStatus.state)}>
                {getStatusLabel(device.printerStatus.state)}
              </Badge>
              {device.printerStatus.detail ? <p>{device.printerStatus.detail}</p> : null}
            </div>
          )}
        </Card>

        <Card>
          <h2 className="sheet-title">용지 정책</h2>
          <p>{device.paperPolicy || '정책 없음'}</p>
          <p>{getPolicyDescription(device.paperPolicy)}</p>
          <div className="row">
            <div>
              <div>용지 장착됨</div>
              {isPaperStateDisabled && <p>현재 정책에서는 사용하지 않습니다</p>}
            </div>
            <Toggle
              label="용지 장착됨"
              checked={currentPaperLoaded}
              disabled={isPaperStateDisabled || paperTogglePending}
              onChange={handlePaperToggle}
            />
          </div>
        </Card>
      </div>
    </div>
  )
}
