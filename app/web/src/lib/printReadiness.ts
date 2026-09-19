// 예약 인쇄가 막혀 있는지 판정하는 공용 로직(docs/app/editor.md 11절 벤치마킹 표 2번).
// PrintNowPage·DevicePage에 각각 조금씩 다르게 있던 정책 분기·문구를 한 곳으로 모은다.
//
// paper_off(용지 정책 manual_flag인데 "용지 장착됨"이 꺼짐)가 poll_stale(Pi 폴링 지연)보다
// 항상 우선한다 — 스케줄의 원본은 서버, 실행은 Pi이고 Pi는 인터넷이 끊겨도 캐시로 인쇄하므로
// 폴링이 늦다고 예약이 막히지는 않는다(CLAUDE.md 구성요소 경계). 반면 용지 토글이 꺼져 있으면
// manual_flag 정책에서는 무조건 막힌다.
import type { DeviceResponse, PaperPolicy } from '../types/device'

// 근거: pages/DevicePage.tsx의 기존 isOldPoll 판정(2분)을 그대로 옮김.
const POLL_STALE_MINUTES = 2

export type PrintBlockCode = 'no_device' | 'paper_off' | 'printer_offline' | 'poll_stale' | 'unverified_policy'

export interface PrintBlockIssue {
  code: PrintBlockCode
  severity: 'danger' | 'warn'
  title: string
  body: string
  steps: string[]
}

export interface PrintReadiness {
  /** danger 등급이 하나라도 있으면 true — 예약 인쇄가 실제로 막혀 있다는 뜻 */
  blocked: boolean
  /** 배너에 띄울 것 1개(우선순위 최상위). 문제 없으면 null */
  primary: PrintBlockIssue | null
  /** 시트에 전부 나열할 목록(우선순위 정렬) */
  issues: PrintBlockIssue[]
  /** 시트에서 "용지 장착됨" 확인형 버튼을 보여줄지 */
  paperToggleActionable: boolean
}

const NO_DEVICE: PrintBlockIssue = {
  code: 'no_device',
  severity: 'danger',
  title: 'Pi가 아직 연결되지 않았습니다',
  body: '예약 인쇄를 실행할 Pi가 없습니다. 먼저 기기를 페어링하세요.',
  steps: ['"기기" 화면에서 토큰 또는 페어링 코드를 발급받습니다', 'Pi에 등록하고 재시작합니다'],
}

function paperOffIssue(pollStale: boolean): PrintBlockIssue {
  return {
    code: 'paper_off',
    severity: 'danger',
    title: '용지 장착됨이 꺼져 있습니다',
    body: pollStale
      ? '지금 켜도 Pi가 다시 접속(최대 30초)해야 반영됩니다.'
      : '이 상태에서는 예약 인쇄가 매일 조용히 실패합니다.',
    steps: ['프린터에 용지가 실제로 있는지 눈으로 확인합니다', '확인했으면 아래에서 "용지 장착됨"을 켭니다'],
  }
}

function printerOfflineIssue(detail: string | null): PrintBlockIssue {
  return {
    code: 'printer_offline',
    severity: 'danger',
    title: '프린터에 연결할 수 없습니다',
    body: detail || 'Pi가 프린터와 통신하지 못하고 있습니다.',
    steps: ['프린터 전원과 Pi-프린터 간 블루투스 연결을 확인합니다'],
  }
}

const POLL_STALE_ISSUE: PrintBlockIssue = {
  code: 'poll_stale',
  severity: 'warn',
  title: 'Pi가 최근에 접속하지 않았습니다',
  body: '인터넷이 끊겨도 Pi는 마지막으로 받은 내용으로 예약대로 인쇄합니다. 다만 방금 바꾼 설정은 아직 반영되지 않았을 수 있습니다.',
  steps: [],
}

function unverifiedPolicyIssue(): PrintBlockIssue {
  return {
    code: 'unverified_policy',
    severity: 'warn',
    title: '용지 감지가 아직 확인되지 않았습니다',
    body: '예약 인쇄는 지금 모의 실행(dry_run)만 기록됩니다. "지금 인쇄"는 매번 사람 확인 후에만 보낼 수 있습니다.',
    steps: [],
  }
}

export function evaluatePrintReadiness(
  device: DeviceResponse | null | undefined,
  now: number = Date.now(),
): PrintReadiness {
  // 로딩 중이거나 아직 조회 전이면 경고를 깜빡이지 않는다.
  if (device === null || device === undefined) {
    return { blocked: false, primary: null, issues: [], paperToggleActionable: false }
  }

  const issues: PrintBlockIssue[] = []

  if (!device.deviceId) {
    issues.push(NO_DEVICE)
  }

  const lastPollAt = device.lastPollAt
  const pollMinutesAgo = lastPollAt ? Math.floor((now - new Date(lastPollAt).getTime()) / 60000) : null
  const pollStale = pollMinutesAgo !== null && pollMinutesAgo >= POLL_STALE_MINUTES

  const paperOff = device.paperPolicy === 'manual_flag' && !device.paperState?.loaded
  if (paperOff) {
    issues.push(paperOffIssue(pollStale))
  }

  if (device.printerStatus && (device.printerStatus.state === 'offline' || device.printerStatus.state === 'error')) {
    issues.push(printerOfflineIssue(device.printerStatus.detail))
  }

  if (pollStale) {
    issues.push(POLL_STALE_ISSUE)
  }

  if (device.paperPolicy === 'unverified') {
    issues.push(unverifiedPolicyIssue())
  }

  const danger = issues.filter((i) => i.severity === 'danger')
  const primary = danger[0] ?? issues[0] ?? null

  return {
    blocked: danger.length > 0,
    primary,
    issues,
    paperToggleActionable: paperOff,
  }
}

/** PrintNowPage·DevicePage가 각자 다른 문구로 갖고 있던 정책 설명을 한 곳으로 통일. */
export function paperPolicyDescription(policy: PaperPolicy): string {
  switch (policy) {
    case 'unverified':
      return '프린터 용지 감지가 아직 확인되지 않음. 예약 인쇄는 모의 실행(dry_run)만 기록하고, 지금 인쇄만 사람 확인 후 전송'
    case 'status_query':
      return 'Pi가 인쇄 직전 프린터에 상태를 물어 용지를 확인'
    case 'manual_flag':
      return '서버의 수동 "용지 장착됨" 상태가 켜져 있을 때만 무인 인쇄'
    case null:
      return '정책 없음'
    default:
      return policy
  }
}
