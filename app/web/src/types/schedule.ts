// 예약. 원본: docs/architecture.md 3.2절, docs/server/api.md "예약" 절.

export type ScheduleType = 'recurring' | 'once'
export type DayOfWeekCode = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'

export interface Schedule {
  id: string
  formatId: string
  type: ScheduleType
  daysOfWeek?: DayOfWeekCode[] | null
  date?: string | null // YYYY-MM-DD, KST
  time: string // HH:mm(:ss)
  enabled: boolean
  nextOccurrenceAt: string | null
}

export interface ScheduleCreateRequest {
  formatId: string
  type: ScheduleType
  daysOfWeek?: DayOfWeekCode[]
  date?: string
  time: string
  enabled: boolean
}

export const DAY_LABELS_KO: Record<DayOfWeekCode, string> = {
  MON: '월',
  TUE: '화',
  WED: '수',
  THU: '목',
  FRI: '금',
  SAT: '토',
  SUN: '일',
}

export const ALL_DAYS: DayOfWeekCode[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
