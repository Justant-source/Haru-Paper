// 포맷 문서 v3 (위젯 그리드). 원본: docs/server/format-schema.md.
// 서버 record(FormatDocument, WidgetInstance)와 필드명을 그대로 맞춘다.
// 위젯 종류·크기·설정 스키마는 코드에 박지 않고 GET /api/widgets 로 받는다 → types/widget.ts

export type FontFamily = 'Pretendard' | 'Noto Sans KR'
export type Divider = 'none' | 'line' | 'dashed'

export interface ForkedFrom {
  name: string
  author: string
  schemaVersion: number
  importedAt: string
}

export interface FormatMeta {
  name: string
  author?: string | null
  description?: string | null
  forkedFrom?: ForkedFrom | null
}

export interface MarginMm {
  top: number
  right: number
  bottom: number
  left: number
}

/** v3 그리드는 fontFamily·baseFontSizePt·lineHeight·marginMm만 쓴다. blockGapMm·divider는 호환용으로 남아 있고 무시된다. */
export interface FormatStyle {
  fontFamily: FontFamily
  baseFontSizePt: number
  lineHeight: number
  marginMm: MarginMm
  blockGapMm: number
  divider: Divider
}

/** 포맷에 놓인 위젯 1개. size는 그 위젯 descriptor.sizes[].id 중 하나("2x4", "4xauto" 등). */
export interface WidgetInstance {
  id: string
  type: string
  size: string
  props: Record<string, unknown>
}

export const SCHEMA_VERSION = 3

export interface FormatDocument {
  schemaVersion: number
  meta: FormatMeta
  style?: FormatStyle
  widgets: WidgetInstance[]
}

export interface FormatSummary {
  id: string
  name: string
  author: string
  forkedFrom: ForkedFrom | null
  hasDynamicBlocks: boolean
  updatedAt: string
}

export interface FormatDetail {
  id: string
  document: FormatDocument
  hasDynamicBlocks: boolean
  createdAt: string
  updatedAt: string
}

export const DEFAULT_STYLE: FormatStyle = {
  fontFamily: 'Pretendard',
  baseFontSizePt: 11,
  lineHeight: 1.4,
  marginMm: { top: 3, right: 3, bottom: 8, left: 3 },
  blockGapMm: 3,
  divider: 'none',
}

/** 위젯이 하나도 없는 새 문서. 서버는 widgets 1개 이상을 요구하므로 저장 전에 채워야 한다. */
export function emptyDocument(name: string): FormatDocument {
  return {
    schemaVersion: SCHEMA_VERSION,
    meta: { name, author: '', description: '' },
    style: DEFAULT_STYLE,
    widgets: [],
  }
}
