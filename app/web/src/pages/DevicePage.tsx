import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { deviceApi } from '../api/device'
import { timeAgoKo, formatDateTimeKo } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'

export function DevicePage() {
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
      // 토글을 원래 상태로 되돌리기 위해 refetch
      refetch()
    },
    onSuccess: () => {
      setPaperToggleError(null)
      refetch()
    },
  })

  const handlePaperToggle = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const newLoaded = e.target.checked
    setPaperTogglePending(true)
    try {
      await setPaperStateMutation.mutateAsync(newLoaded)
    } finally {
      setPaperTogglePending(false)
    }
  }

  if (isLoading) {
    return (
      <div className="page">
        <h1>기기</h1>
        <p>로드 중...</p>
      </div>
    )
  }

  // 빈 상태: Pi가 한 번도 폴링하지 않음
  if (!device?.lastPollAt) {
    return (
      <div className="page">
        <h1>기기</h1>
        <ErrorBanner error={error} onRetry={() => refetch()} />
        <div
          style={{
            textAlign: 'center',
            color: '#6b7280',
            padding: '48px 16px',
          }}
        >
          아직 Pi가 연결되지 않았습니다.
        </div>
      </div>
    )
  }

  // Pi 마지막 폴링 경과 시간 계산
  const pollDiffMs = Date.now() - new Date(device.lastPollAt).getTime()
  const pollDiffMin = Math.floor(pollDiffMs / 1000 / 60)
  const isOldPoll = pollDiffMin >= 2

  // 프린터 상태 색상 결정
  const getStatusColor = (state: string) => {
    switch (state) {
      case 'ok':
        return '#16a34a' // 초록
      case 'offline':
      case 'error':
        return '#dc2626' // 빨강
      default:
        return '#6b7280' // 회색
    }
  }

  // 프린터 상태 라벨
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

  // 용지 정책 설명
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
    <div className="page">
      <h1>기기</h1>

      <ErrorBanner error={error} onRetry={() => refetch()} />

      {paperToggleError && (
        <div
          style={{
            background: '#fef2f2',
            color: '#dc2626',
            border: '1px solid #fecaca',
            borderRadius: '8px',
            padding: '10px 12px',
            marginBottom: '12px',
            fontSize: '14px',
          }}
        >
          {paperToggleError}
        </div>
      )}

      {/* Pi 마지막 폴링 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '12px',
          marginBottom: '16px',
        }}
      >
        <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '4px' }}>Pi 마지막 폴링</div>
        <div
          style={{
            fontSize: '15px',
            fontWeight: 500,
            color: isOldPoll ? '#dc2626' : '#111827',
          }}
        >
          {formatDateTimeKo(device.lastPollAt)} ({timeAgoKo(device.lastPollAt)})
        </div>
        {isOldPoll && (
          <div style={{ fontSize: '12px', color: '#dc2626', marginTop: '4px' }}>
            2분 이상 응답이 없습니다.
          </div>
        )}
      </div>

      {/* 프린터 프로필 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '12px',
          marginBottom: '16px',
        }}
      >
        <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '8px' }}>프린터 프로필</div>
        {device.printerProfile ? (
          <div style={{ fontSize: '14px' }}>
            <div style={{ marginBottom: '6px' }}>
              <span style={{ color: '#6b7280' }}>모델:</span> <strong>{device.printerProfile.model}</strong>
            </div>
            <div style={{ marginBottom: '6px' }}>
              <span style={{ color: '#6b7280' }}>DPI:</span> <strong>{device.printerProfile.dpi}</strong>
            </div>
            <div style={{ marginBottom: '6px' }}>
              <span style={{ color: '#6b7280' }}>용지 너비:</span> <strong>{device.printerProfile.paperWidthMm}mm</strong>
            </div>
            <div>
              <span style={{ color: '#6b7280' }}>인쇄 가능 폭:</span>{' '}
              <strong>{device.printerProfile.printableWidthPx}px</strong>
            </div>
          </div>
        ) : (
          <div style={{ fontSize: '14px', color: '#6b7280' }}>아직 연결된 적 없음</div>
        )}
      </div>

      {/* 프린터 상태 */}
      {device.printerStatus && (
        <div
          style={{
            background: '#f3f4f6',
            border: '1px solid #d1d5db',
            borderRadius: '8px',
            padding: '12px',
            marginBottom: '16px',
          }}
        >
          <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '8px' }}>프린터 상태</div>
          <div
            style={{
              fontSize: '15px',
              fontWeight: 500,
              color: getStatusColor(device.printerStatus.state),
              marginBottom: device.printerStatus.detail ? '4px' : '0',
            }}
          >
            {getStatusLabel(device.printerStatus.state)}
          </div>
          {device.printerStatus.detail && (
            <div style={{ fontSize: '12px', color: '#6b7280' }}>{device.printerStatus.detail}</div>
          )}
        </div>
      )}

      {/* 용지 정책 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '12px',
          marginBottom: '16px',
        }}
      >
        <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '8px' }}>용지 정책</div>
        <div style={{ fontSize: '14px', fontWeight: 500, marginBottom: '6px' }}>
          {device.paperPolicy || '정책 없음'}
        </div>
        <div style={{ fontSize: '12px', color: '#6b7280', lineHeight: '1.5' }}>
          {getPolicyDescription(device.paperPolicy)}
        </div>
      </div>

      {/* 용지 장착됨 토글 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '12px',
          marginBottom: '16px',
          opacity: isPaperStateDisabled ? 0.6 : 1,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <div style={{ fontSize: '13px', fontWeight: 500, marginBottom: '4px' }}>용지 장착됨</div>
            {isPaperStateDisabled && (
              <div style={{ fontSize: '12px', color: '#6b7280' }}>현재 정책에서는 사용하지 않습니다</div>
            )}
          </div>
          <input
            type="checkbox"
            checked={currentPaperLoaded}
            onChange={handlePaperToggle}
            disabled={isPaperStateDisabled || paperTogglePending}
            style={{
              width: '20px',
              height: '20px',
              cursor: isPaperStateDisabled ? 'not-allowed' : 'pointer',
            }}
          />
        </div>
      </div>
    </div>
  )
}
