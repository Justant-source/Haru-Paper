import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { settingsApi, healthApi } from '../api/settings'
import type { SettingsResponse } from '../types/settings'
import { ErrorBanner } from '../components/ErrorBanner'

export function SettingsPage() {
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

  // 초기값 로드
  useEffect(() => {
    if (settings) {
      setLat(settings.weather.lat.toString())
      setLon(settings.weather.lon.toString())
      setLabel(settings.weather.label)
    }
  }, [settings])

  // 서버 연결 상태 업데이트
  useEffect(() => {
    if (healthQuery.isLoading) {
      setServerStatus('checking')
    } else if (healthQuery.isSuccess) {
      setServerStatus('connected')
    } else {
      setServerStatus('disconnected')
    }
  }, [healthQuery.isLoading, healthQuery.isSuccess, healthQuery.isError])

  if (isLoading) {
    return (
      <div className="page">
        <h1>설정</h1>
        <p>로드 중...</p>
      </div>
    )
  }

  // 범위 검증
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

  return (
    <div className="page">
      <h1>설정</h1>

      <ErrorBanner error={error} onRetry={() => refetch()} />

      {localError && (
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
          {localError}
        </div>
      )}

      {/* 날씨 기본 위치 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '16px',
          marginBottom: '16px',
        }}
      >
        <h2 style={{ marginTop: 0, marginBottom: '12px', fontSize: '15px', fontWeight: 600 }}>
          날씨 기본 위치
        </h2>

        <div style={{ marginBottom: '12px' }}>
          <label
            style={{
              display: 'block',
              fontSize: '13px',
              fontWeight: 500,
              marginBottom: '4px',
              color: '#111827',
            }}
          >
            위도 (latitude)
          </label>
          <input
            type="number"
            min="-90"
            max="90"
            step="0.0001"
            value={lat}
            onChange={(e) => setLat(e.target.value)}
            placeholder="37.5663"
            style={{
              width: '100%',
              padding: '8px 10px',
              border: `1px solid ${isLatValid ? '#d1d5db' : '#dc2626'}`,
              borderRadius: '4px',
              fontSize: '14px',
              fontFamily: 'inherit',
              boxSizing: 'border-box',
            }}
          />
          {!isLatValid && (
            <div style={{ fontSize: '12px', color: '#dc2626', marginTop: '4px' }}>
              -90 ~ 90 사이의 값을 입력해주세요
            </div>
          )}
        </div>

        <div style={{ marginBottom: '12px' }}>
          <label
            style={{
              display: 'block',
              fontSize: '13px',
              fontWeight: 500,
              marginBottom: '4px',
              color: '#111827',
            }}
          >
            경도 (longitude)
          </label>
          <input
            type="number"
            min="-180"
            max="180"
            step="0.0001"
            value={lon}
            onChange={(e) => setLon(e.target.value)}
            placeholder="126.9779"
            style={{
              width: '100%',
              padding: '8px 10px',
              border: `1px solid ${isLonValid ? '#d1d5db' : '#dc2626'}`,
              borderRadius: '4px',
              fontSize: '14px',
              fontFamily: 'inherit',
              boxSizing: 'border-box',
            }}
          />
          {!isLonValid && (
            <div style={{ fontSize: '12px', color: '#dc2626', marginTop: '4px' }}>
              -180 ~ 180 사이의 값을 입력해주세요
            </div>
          )}
        </div>

        <div style={{ marginBottom: '16px' }}>
          <label
            style={{
              display: 'block',
              fontSize: '13px',
              fontWeight: 500,
              marginBottom: '4px',
              color: '#111827',
            }}
          >
            위치 이름 (표시용)
          </label>
          <input
            type="text"
            maxLength={50}
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="서울시청"
            style={{
              width: '100%',
              padding: '8px 10px',
              border: `1px solid ${isLabelValid ? '#d1d5db' : '#dc2626'}`,
              borderRadius: '4px',
              fontSize: '14px',
              fontFamily: 'inherit',
              boxSizing: 'border-box',
            }}
          />
          <div style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
            {label.length}/50자
          </div>
          {label.length === 0 && (
            <div style={{ fontSize: '12px', color: '#dc2626', marginTop: '2px' }}>필수 입력값입니다</div>
          )}
          {label.length > 50 && (
            <div style={{ fontSize: '12px', color: '#dc2626', marginTop: '2px' }}>50자를 초과했습니다</div>
          )}
        </div>

        <button
          type="button"
          onClick={handleSave}
          disabled={!isFormValid || updateMutation.isPending}
          style={{
            width: '100%',
            padding: '10px 12px',
            background: isFormValid ? '#111827' : '#d1d5db',
            color: isFormValid ? '#fff' : '#6b7280',
            border: 'none',
            borderRadius: '4px',
            fontSize: '14px',
            fontWeight: 500,
            cursor: isFormValid && !updateMutation.isPending ? 'pointer' : 'not-allowed',
          }}
        >
          {updateMutation.isPending ? '저장 중...' : '저장'}
        </button>

        <div style={{ fontSize: '12px', color: '#6b7280', marginTop: '12px', lineHeight: '1.5' }}>
          날씨는 인쇄 약 60분 전에 서버가 다시 받아 카드에 반영합니다.
        </div>
      </div>

      {/* 앱 정보 */}
      <div
        style={{
          background: '#f3f4f6',
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '16px',
        }}
      >
        <h2 style={{ marginTop: 0, marginBottom: '12px', fontSize: '15px', fontWeight: 600 }}>앱 정보</h2>

        <div style={{ marginBottom: '12px' }}>
          <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '4px' }}>버전</div>
          <div style={{ fontSize: '14px', fontWeight: 500 }}>0.1.0</div>
        </div>

        <div>
          <div style={{ fontSize: '13px', color: '#6b7280', marginBottom: '4px' }}>서버 연결 상태</div>
          <div
            style={{
              fontSize: '14px',
              fontWeight: 500,
              color:
                serverStatus === 'connected'
                  ? '#16a34a'
                  : serverStatus === 'disconnected'
                    ? '#dc2626'
                    : '#6b7280',
            }}
          >
            {serverStatus === 'connected' && '연결됨'}
            {serverStatus === 'disconnected' && '연결 안 됨'}
            {serverStatus === 'checking' && '확인 중...'}
          </div>
        </div>
      </div>
    </div>
  )
}
