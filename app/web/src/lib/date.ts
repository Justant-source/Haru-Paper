// 모든 시각은 KST 고정(Asia/Seoul). 서버가 이미 +09:00 오프셋으로 내려주므로
// 브라우저 로캘과 무관하게 KST로 통일해 표시한다(docs/app/screens.md 공통절).

const KST_TZ = 'Asia/Seoul'

export function formatDateKo(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: KST_TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  }).formatToParts(d)
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')} (${get('weekday')})`
}

export function formatTimeKo(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: KST_TZ,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d)
}

export function formatDateTimeKo(iso: string | null | undefined): string {
  if (!iso) return ''
  return `${formatDateKo(iso)} ${formatTimeKo(iso)}`
}

/** YYYY-MM-DD (KST 기준 오늘) — <input type="date"> min 속성 등에 쓴다. */
export function todayKstDateString(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: KST_TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}

/** HH:mm (KST 지금) — 오늘 날짜의 일회성 예약에서 과거 시각을 막는다. */
export function nowKstTimeString(): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: KST_TZ,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date())
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('hour')}:${get('minute')}`
}

export function timeAgoKo(iso: string | null | undefined): string {
  if (!iso) return ''
  const diffMs = Date.now() - new Date(iso).getTime()
  const sec = Math.floor(diffMs / 1000)
  if (sec < 60) return `${sec}초 전`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}분 전`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}시간 전`
  return `${Math.floor(hour / 24)}일 전`
}
