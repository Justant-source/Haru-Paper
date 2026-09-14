import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { deviceApi } from '../api/device'
import { formatsApi } from '../api/formats'
import { printNowApi } from '../api/printNow'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import type { FormatSummary } from '../types/format'
import type { DeviceResponse, PaperPolicy } from '../types/device'
import { formatDateTimeKo, timeAgoKo } from '../lib/date'

export function PrintNowPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const initialFormatId = searchParams.get('formatId') || undefined

  const [formats, setFormats] = useState<FormatSummary[]>([])
  const [device, setDevice] = useState<DeviceResponse | null>(null)
  const [selectedFormatId, setSelectedFormatId] = useState<string>('')
  const [paperConfirmed, setPaperConfirmed] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [sending, setSending] = useState(false)
  const [sendSuccess, setSendSuccess] = useState(false)

  // 포맷 목록과 기기 정보 로드
  useEffect(() => {
    const loadData = async () => {
      try {
        setError(null)
        const [formatsRes, deviceRes] = await Promise.all([formatsApi.list(), deviceApi.get()])

        setFormats(formatsRes)
        setDevice(deviceRes)

        // URL의 formatId가 있으면 설정
        if (initialFormatId && formatsRes.some((f) => f.id === initialFormatId)) {
          setSelectedFormatId(initialFormatId)
        } else if (formatsRes.length > 0) {
          // 포맷이 있으면 첫 번째 선택
          setSelectedFormatId(formatsRes[0].id)
        }
      } catch (e) {
        setError(e)
      } finally {
        setLoading(false)
      }
    }

    loadData()
  }, [initialFormatId])

  const handlePrintNow = async () => {
    if (!selectedFormatId) return

    try {
      setSending(true)
      setError(null)
      await printNowApi.send({
        formatId: selectedFormatId,
        paperConfirmed,
      })
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
      <div className="page">
        <h1>지금 인쇄</h1>
        <p>로드 중...</p>
      </div>
    )
  }

  // 포맷이 없는 경우
  if (formats.length === 0) {
    return (
      <div className="page">
        <h1>지금 인쇄</h1>
        <EmptyState
          message="먼저 포맷을 만드세요"
          actionLabel="포맷 만들기"
          onAction={() => navigate('/')}
        />
      </div>
    )
  }

  // 전송 성공 후
  if (sendSuccess) {
    return (
      <div className="page">
        <h1>지금 인쇄</h1>
        <div className="success-message">
          <p>명령을 보냈습니다. Pi가 다음 폴링(최대 약 30초)에 받아 인쇄합니다.</p>
          <button type="button" className="primary-btn" onClick={() => navigate('/history')}>
            이력 보기
          </button>
        </div>
      </div>
    )
  }

  // 기기 정보 (lastPollAt이 null이면 아직 연결 안 됨)
  const lastPollAt = device?.lastPollAt
  const lastPollMinutesAgo = lastPollAt
    ? Math.floor((Date.now() - new Date(lastPollAt).getTime()) / 60000)
    : null
  const isPollOld = lastPollMinutesAgo !== null && lastPollMinutesAgo >= 2

  // 용지 정책별 체크박스/버튼 상태
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
    <div className="page">
      <h1>지금 인쇄</h1>

      <ErrorBanner error={error} onRetry={() => window.location.reload()} />

      {isPollOld && (
        <div className="warn-banner">
          Pi가 최근에 접속하지 않았습니다. 명령은 10분 안에 Pi가 다시 접속하면 처리되고, 그렇지 않으면 만료되어
          인쇄되지 않습니다.
        </div>
      )}

      {/* 기기 상태 요약 */}
      {device && (
        <div className="device-status">
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
            <span className="value">{getPaperPolicyLabel(paperPolicy)}</span>
          </div>
        </div>
      )}

      {/* 포맷 선택 */}
      <div className="form-group">
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

      {/* 미리보기 */}
      {selectedFormatId && (
        <div className="preview-section">
          <img src={formatsApi.previewUrl(selectedFormatId)} alt="Preview" />
        </div>
      )}

      {/* 체크박스 */}
      {(paperPolicy === 'unverified' || paperPolicy === 'status_query' || paperPolicy === 'manual_flag') && (
        <div className="form-group checkbox-group">
          <label>
            <input type="checkbox" checked={paperConfirmed} onChange={(e) => setPaperConfirmed(e.target.checked)} />
            {checkboxLabel}
          </label>
        </div>
      )}

      {/* 비활성 이유 표시 */}
      {disableReason && <div className="disable-reason">{disableReason}</div>}

      {/* 지금 인쇄 버튼 */}
      <button
        type="button"
        className="primary-btn"
        disabled={printButtonDisabled}
        onClick={handlePrintNow}
      >
        {sending ? '전송 중...' : '지금 인쇄'}
      </button>
    </div>
  )
}

function getPaperPolicyLabel(policy: PaperPolicy): string {
  switch (policy) {
    case 'unverified':
      return '프린터 용지 감지가 아직 확인되지 않음. 예약 인쇄는 dry_run 기록만 하고, 지금 인쇄만 사람 확인 후 전송'
    case 'status_query':
      return 'Pi가 인쇄 직전 프린터에 상태를 물어 용지 확인'
    case 'manual_flag':
      return '서버의 수동 "용지 장착됨" 상태가 켜져 있을 때만 무인 인쇄'
    case null:
      return '미확인'
    default:
      return policy
  }
}
