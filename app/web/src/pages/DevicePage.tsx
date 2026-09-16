import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { deviceApi } from '../api/device'
import { devicesApi } from '../api/devices'
import { timeAgoKo, formatDateTimeKo } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { Card } from '../components/Card'
import { Badge, type BadgeTone } from '../components/Badge'
import { Toggle } from '../components/Toggle'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'

export function DevicePage() {
  const { t } = useI18n()
  const [paperToggleError, setPaperToggleError] = useState<string | null>(null)
  const [paperTogglePending, setPaperTogglePending] = useState(false)
  const [tokenCopied, setTokenCopied] = useState(false)
  const [issuedToken, setIssuedToken] = useState<string | null>(null)
  const [issuedPairingCode, setIssuedPairingCode] = useState<{
    code: string
    expiresAt: string
  } | null>(null)
  const [pairingCodeCountdown, setPairingCodeCountdown] = useState<number | null>(null)

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

  const issueTokenMutation = useMutation({
    mutationFn: () => devicesApi.getToken(),
    onSuccess: (data) => {
      setIssuedToken(data.token)
    },
  })

  const issuePairingCodeMutation = useMutation({
    mutationFn: () => devicesApi.getPairingCodes(),
    onSuccess: (data) => {
      setIssuedPairingCode(data)
      const expiresAt = new Date(data.expiresAt).getTime()
      const now = Date.now()
      setPairingCodeCountdown(Math.max(0, Math.floor((expiresAt - now) / 1000)))
    },
  })

  // 페어링 코드 카운트다운
  useEffect(() => {
    if (pairingCodeCountdown === null || pairingCodeCountdown <= 0) {
      return
    }

    const timer = setTimeout(() => {
      setPairingCodeCountdown(pairingCodeCountdown - 1)
    }, 1000)

    return () => clearTimeout(timer)
  }, [pairingCodeCountdown])

  const handlePaperToggle = async (next: boolean) => {
    setPaperTogglePending(true)
    try {
      await setPaperStateMutation.mutateAsync(next)
    } finally {
      setPaperTogglePending(false)
    }
  }

  const handleCopyToken = () => {
    if (issuedToken) {
      navigator.clipboard.writeText(issuedToken)
      setTokenCopied(true)
      setTimeout(() => setTokenCopied(false), 2000)
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

  // Pi가 한 번도 poll하지 않은 상태(아직 아무도 페어링 안 한 최초 상태 — 로그인한
  // 사용자라면 전부 여기 해당한다)에서도 토큰 발급·페어링 코드 카드는 항상 보여야 한다.
  // 이게 없으면 M6의 온보딩 흐름(앱에서 토큰 발급 → Pi에 붙여넣기) 자체가 화면에서
  // 도달 불가능해진다 — 2026-09-16 정적 코드 감사에서 발견.
  const lastPollAt = device?.lastPollAt ?? null
  const hasPolled = lastPollAt !== null
  const pollDiffMs = lastPollAt !== null ? Date.now() - new Date(lastPollAt).getTime() : 0
  const pollDiffMin = Math.floor(pollDiffMs / 1000 / 60)
  const isOldPoll = hasPolled && pollDiffMin >= 2

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

  const isPaperStateDisabled = device?.paperPolicy !== 'manual_flag'
  const currentPaperLoaded = device?.paperState?.loaded ?? false

  return (
    <div className="page page-with-header">
      <PageHeader title={t('device')} />

      <ErrorBanner error={error} onRetry={() => refetch()} />
      <ErrorBanner error={paperToggleError ? new Error(paperToggleError) : null} />

      <div className="stack">
        {!hasPolled && <EmptyState message="아직 Pi가 연결되지 않았습니다. 아래에서 토큰을 발급받아 Pi에 등록하세요." />}

        {lastPollAt !== null && (
          <>
            <Card>
              <h2 className="sheet-title">Pi 마지막 폴링</h2>
              <p>
                {formatDateTimeKo(lastPollAt)} ({timeAgoKo(lastPollAt)})
              </p>
              {isOldPoll && (
                <div className="banner banner-warn" role="status">
                  2분 이상 응답이 없습니다.
                </div>
              )}
            </Card>

            <Card>
              <h2 className="sheet-title">프린터 프로필</h2>
              {device!.printerProfile ? (
                <dl>
                  <div>
                    <dt>모델</dt>
                    <dd>{device!.printerProfile.model}</dd>
                  </div>
                  <div>
                    <dt>DPI</dt>
                    <dd>{device!.printerProfile.dpi}</dd>
                  </div>
                  <div>
                    <dt>용지 너비</dt>
                    <dd>{device!.printerProfile.paperWidthMm}mm</dd>
                  </div>
                  <div>
                    <dt>인쇄 가능 폭</dt>
                    <dd>{device!.printerProfile.printableWidthPx}px</dd>
                  </div>
                </dl>
              ) : (
                <p>아직 연결된 적 없음</p>
              )}
              {device!.printerStatus && (
                <div>
                  <h3 className="sheet-title">프린터 상태</h3>
                  <Badge tone={getStatusTone(device!.printerStatus.state)}>
                    {getStatusLabel(device!.printerStatus.state)}
                  </Badge>
                  {device!.printerStatus.detail ? <p>{device!.printerStatus.detail}</p> : null}
                </div>
              )}
            </Card>

            <Card>
              <h2 className="sheet-title">용지 정책</h2>
              <p>{device!.paperPolicy || '정책 없음'}</p>
              <p>{getPolicyDescription(device!.paperPolicy)}</p>
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
          </>
        )}

        <Card>
          <h2 className="sheet-title">{t('deviceToken')}</h2>
          {issuedToken ? (
            <>
              <div className="device-token-display">
                <code>{issuedToken}</code>
              </div>
              <Button
                variant="secondary"
                onClick={handleCopyToken}
                className="device-token-copy"
              >
                {tokenCopied ? '복사됨' : t('copyToken')}
              </Button>
              <div className="banner banner-warn" role="alert">
                {t('tokenWarning')}
              </div>
            </>
          ) : (
            <>
              <p>기기를 식별하기 위한 토큰을 발급받습니다. 이 토큰은 한 번만 표시되므로, Pi의 .env 파일에 저장해야 합니다.</p>
              <Button
                variant="primary"
                onClick={() => issueTokenMutation.mutate()}
                disabled={issueTokenMutation.isPending}
              >
                {issueTokenMutation.isPending ? t('issuingToken') : t('issueToken')}
              </Button>
            </>
          )}
        </Card>

        <Card>
          <h2 className="sheet-title">{t('pairingCode')}</h2>
          {issuedPairingCode ? (
            <>
              <div className="pairing-code-display">
                <div className="code">{issuedPairingCode.code}</div>
                {pairingCodeCountdown !== null && (
                  <div className="countdown">
                    {t('expiresIn')}: {Math.floor(pairingCodeCountdown / 60)}분 {pairingCodeCountdown % 60}초
                  </div>
                )}
              </div>
              <div className="banner banner-info" role="alert">
                {t('pairingCodeWarning')}
              </div>
            </>
          ) : (
            <>
              <p>Pi와 페어링하기 위한 코드를 발급받습니다.</p>
              <Button
                variant="primary"
                onClick={() => issuePairingCodeMutation.mutate()}
                disabled={issuePairingCodeMutation.isPending}
              >
                {issuePairingCodeMutation.isPending ? t('issuingCode') : t('issuePairingCode')}
              </Button>
            </>
          )}
        </Card>
      </div>
    </div>
  )
}
