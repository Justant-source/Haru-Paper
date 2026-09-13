import { useEffect, useState } from 'react'
import { historyApi } from '../api/history'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import type { HistoryEntry, ResultStatus } from '../types/history'
import { STATUS_LABELS_KO, STATUS_COLORS } from '../types/history'
import { formatDateTimeKo } from '../lib/date'

export function HistoryPage() {
  const [entries, setEntries] = useState<HistoryEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [filter, setFilter] = useState<'all' | 'printed' | 'problem'>('all')

  const loadHistory = async () => {
    try {
      setLoading(true)
      setError(null)
      const res = await historyApi.list()
      setEntries(res)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadHistory()
  }, [])

  const filteredEntries = entries.filter((entry) => {
    if (filter === 'all') return true
    if (filter === 'printed') return entry.status === 'printed'
    if (filter === 'problem') return entry.status !== 'printed' && entry.status !== 'dry_run'
    return true
  })

  if (loading && entries.length === 0) {
    return (
      <div className="page">
        <h1>이력</h1>
        <p>로드 중...</p>
      </div>
    )
  }

  if (entries.length === 0 && !loading) {
    return (
      <div className="page">
        <h1>이력</h1>
        <EmptyState message="아직 실행 기록이 없습니다." />
      </div>
    )
  }

  return (
    <div className="page">
      <h1>이력</h1>

      <ErrorBanner error={error} onRetry={loadHistory} />

      {/* 필터 버튼 */}
      <div className="filter-tabs">
        <button
          className={`filter-btn ${filter === 'all' ? 'active' : ''}`}
          onClick={() => setFilter('all')}
        >
          전체
        </button>
        <button
          className={`filter-btn ${filter === 'printed' ? 'active' : ''}`}
          onClick={() => setFilter('printed')}
        >
          인쇄됨
        </button>
        <button
          className={`filter-btn ${filter === 'problem' ? 'active' : ''}`}
          onClick={() => setFilter('problem')}
        >
          문제 있음
        </button>
      </div>

      {/* 새로고침 버튼 */}
      <div className="refresh-section">
        <button type="button" onClick={loadHistory} disabled={loading}>
          {loading ? '새로고침 중...' : '새로고침'}
        </button>
      </div>

      {/* 결과 목록 */}
      {filteredEntries.length === 0 && !loading ? (
        <EmptyState message="해당하는 기록이 없습니다." />
      ) : (
        <div className="history-list">
          {filteredEntries.map((entry) => (
            <HistoryEntryCard key={entry.resultId} entry={entry} />
          ))}
        </div>
      )}
    </div>
  )
}

function HistoryEntryCard({ entry }: { entry: HistoryEntry }) {
  const status = entry.status as ResultStatus
  const statusLabel = STATUS_LABELS_KO[status] || status
  const statusColor = STATUS_COLORS[status] || 'gray'
  const sourceLabel = entry.source === 'schedule' ? '예약' : '지금 인쇄'
  const timeStr = entry.executedAt ? formatDateTimeKo(entry.executedAt) : '-'

  return (
    <div className="history-entry">
      <div className="entry-header">
        <div className="status-badge" style={{ color: statusColor }}>
          ●
        </div>
        <div className="entry-info">
          <div className="entry-title">
            <span className="status-label" style={{ color: statusColor }}>
              {statusLabel}
            </span>
            <span className="source-label">{sourceLabel}</span>
          </div>
          {entry.formatName && <div className="format-name">{entry.formatName}</div>}
          <div className="entry-time">{timeStr}</div>
        </div>
      </div>

      {entry.detail && (
        <details className="entry-detail">
          <summary>상세 정보</summary>
          <div className="detail-content">{entry.detail}</div>
        </details>
      )}
    </div>
  )
}
