// 기기. 원본: docs/architecture.md 3.5, 3.7절, docs/server/api.md "기기" 절.

export interface PrinterProfile {
  model: string
  dpi: number
  paperWidthMm: number
  printableWidthPx: number
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
  deviceId: string
  online: boolean
  lastPollAt: string | null
  agentVersion: string | null
  printerProfile: PrinterProfile | null
  printerStatus: PrinterStatus | null
  paperPolicy: PaperPolicy
  paperState: PaperState
}
