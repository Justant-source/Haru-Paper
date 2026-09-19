// 기기. 원본: docs/architecture.md 3.5, 3.7절, docs/server/api.md "기기" 절.

export interface PrinterProfile {
  model: string
  dpi: number
  paperWidthMm: number
  printableWidthPx: number
}

/**
 * Pi가 아직 프로필을 보고하지 않았을 때 서버가 쓰는 기본 프로필과 같은 값.
 * 원본: server/src/main/java/com/harupaper/server/device/PrinterProfile.java
 *       (PrinterProfile.DEFAULT), docs/server/api.md 4.1절.
 * 앱은 프린터 프로필만 알고 프린터 프로토콜 상수는 모른다(CLAUDE.md "구성요소 경계").
 */
export const DEFAULT_PRINTER_PROFILE: PrinterProfile = {
  model: 'm832',
  dpi: 300,
  paperWidthMm: 110,
  printableWidthPx: 1300,
}

export type PrinterState = 'ok' | 'offline' | 'error' | 'unknown'

export interface PrinterStatus {
  state: PrinterState
  detail: string
}

export type PaperPolicy = 'unverified' | 'status_query' | 'manual_flag' | null

export interface PaperState {
  loaded: boolean
  updatedAt: string | null
  updatedBy?: 'app' | 'server' | null
}

export interface DeviceResponse {
  deviceId: string | null
  online: boolean
  lastPollAt: string | null
  agentVersion: string | null
  printerProfile: PrinterProfile | null
  printerStatus: PrinterStatus | null
  paperPolicy: PaperPolicy
  // Pi가 한 번도 페어링되지 않았으면 서버가 null을 준다(DeviceController.getDevice, 미페어링 분기).
  paperState: PaperState | null
}
