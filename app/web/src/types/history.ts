// 실행 결과. 원본: docs/architecture.md 3.6절.

export type ResultStatus =
  | 'printed'
  | 'dry_run'
  | 'missed'
  | 'failed'
  | 'skipped_no_paper'
  | 'skipped_clock_unsynced'
  | 'skipped_printer_offline'

export type ResultSource = 'schedule' | 'command'

export interface HistoryEntry {
  resultId: string
  occurrenceKey: string | null
  commandId: string | null
  formatId: string | null
  formatName: string | null
  renderId: string | null
  status: ResultStatus
  detail: string | null
  scheduledAt: string | null
  executedAt: string
  source: ResultSource
}

export const STATUS_LABELS_KO: Record<ResultStatus, string> = {
  printed: '인쇄됨',
  dry_run: '모의 실행',
  missed: '놓침',
  failed: '실패',
  skipped_no_paper: '용지 없음',
  skipped_clock_unsynced: '시각 미확인',
  skipped_printer_offline: '프린터 응답 없음',
}

export const STATUS_COLORS: Record<ResultStatus, string> = {
  printed: 'green',
  dry_run: 'gray',
  missed: 'orange',
  failed: 'red',
  skipped_no_paper: 'orange',
  skipped_clock_unsynced: 'orange',
  skipped_printer_offline: 'red',
}
