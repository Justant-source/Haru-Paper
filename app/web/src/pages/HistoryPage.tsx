import { useEffect, useState } from 'react'
import { historyApi } from '../api/history'
import { Badge, type BadgeTone } from '../components/Badge'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { EmptyState } from '../components/EmptyState'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import type { HistoryEntry, ResultStatus } from '../types/history'
import { STATUS_LABELS_KO } from '../types/history'
import { formatDateTimeKo } from '../lib/date'
import { useI18n } from '../i18n'

function statusTone(status: ResultStatus): BadgeTone {
  switch (status) {
    case 'printed':
      return 'ok'
    case 'dry_run':
      return 'neutral'
    case 'failed':
    case 'skipped_printer_offline':
      return 'danger'
    case 'missed':
    case 'skipped_no_paper':
    case 'skipped_clock_unsynced':
      return 'warn'
  }
}

export function HistoryPage() {
  const { t } = useI18n()
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

  return (
    <div className="page page-with-header">
      <PageHeader
        title={t('history')}
        action={
          <Button variant="secondary" onClick={loadHistory} disabled={loading}>
            {loading ? t('refreshing') : t('refresh')}
          </Button>
        }
      />

      <ErrorBanner error={error} onRetry={loadHistory} />

      {loading && entries.length === 0 ? (
        <p>{t('loading')}</p>
      ) : entries.length === 0 ? (
        <EmptyState message={t('historyEmpty')} />
      ) : (
        <>
          <div className="filter-tabs">
            <button
              type="button"
              className={`filter-btn ${filter === 'all' ? 'active' : ''}`}
              onClick={() => setFilter('all')}
            >
              {t('filterAll')}
            </button>
            <button
              type="button"
              className={`filter-btn ${filter === 'printed' ? 'active' : ''}`}
              onClick={() => setFilter('printed')}
            >
              {t('filterPrinted')}
            </button>
            <button
              type="button"
              className={`filter-btn ${filter === 'problem' ? 'active' : ''}`}
              onClick={() => setFilter('problem')}
            >
              {t('filterProblem')}
            </button>
          </div>

          {filteredEntries.length === 0 && !loading ? (
            <EmptyState message="해당하는 기록이 없습니다." />
          ) : (
            <div className="history-list">
              {filteredEntries.map((entry) => (
                <HistoryEntryCard key={entry.resultId} entry={entry} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function HistoryEntryCard({ entry }: { entry: HistoryEntry }) {
  const { t } = useI18n()
  const status = entry.status
  const statusLabel = STATUS_LABELS_KO[status] ?? status
  const sourceLabel = entry.source === 'schedule' ? t('sourceSchedule') : t('sourceCommand')

  return (
    <Card>
      <div className="entry-header">
        <div className="entry-info">
          <div className="entry-title">
            <Badge tone={statusTone(status)}>{statusLabel}</Badge>
            <span className="source-label">{sourceLabel}</span>
          </div>
          {entry.formatName && <div className="format-name">{entry.formatName}</div>}
          {entry.scheduledAt && (
            <div className="entry-time">예약 {formatDateTimeKo(entry.scheduledAt)}</div>
          )}
          <div className="entry-time">
            실행 {entry.executedAt ? formatDateTimeKo(entry.executedAt) : '-'}
          </div>
        </div>
      </div>

      {entry.detail && (
        <details className="entry-detail">
          <summary>상세 정보</summary>
          <div className="detail-content">{entry.detail}</div>
        </details>
      )}
    </Card>
  )
}
