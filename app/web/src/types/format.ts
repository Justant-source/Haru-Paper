// 포맷 문서 v1. 원본: docs/server/format-schema.md. 서버 record와 필드명을 그대로 맞춘다.

export type BlockType = 'text' | 'image' | 'dateHeader' | 'weather'
export type Align = 'left' | 'center' | 'right'
export type FontFamily = 'Pretendard' | 'Noto Sans KR'
export type Divider = 'none' | 'line' | 'dashed'
export type WeatherField = 'tempMin' | 'tempMax' | 'precipProb' | 'sky'

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

export interface FormatStyle {
  fontFamily: FontFamily
  baseFontSizePt: number
  lineHeight: number
  marginMm: MarginMm
  blockGapMm: number
  divider: Divider
}

export interface BlockStyle {
  align?: Align | null
  fontSizePt?: number | null
  bold?: boolean | null
  marginTopMm?: number | null
  marginBottomMm?: number | null
}

export interface TextProps {
  text: string
}

export interface ImageProps {
  assetId: string
  widthPercent?: number
}

export interface DateHeaderProps {
  pattern?: string
}

export interface WeatherProps {
  location?: 'default'
  fields?: WeatherField[]
}

export type BlockProps = TextProps | ImageProps | DateHeaderProps | WeatherProps

export interface Block {
  type: BlockType
  props: Record<string, unknown>
  style?: BlockStyle | null
}

export interface FormatDocument {
  schemaVersion: number
  meta: FormatMeta
  style?: FormatStyle
  blocks: Block[]
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

export function newBlock(type: BlockType): Block {
  switch (type) {
    case 'text':
      return { type, props: { text: '' } }
    case 'image':
      return { type, props: { assetId: '', widthPercent: 100 } }
    case 'dateHeader':
      return { type, props: {}, style: { align: 'center' } }
    case 'weather':
      return { type, props: { fields: ['tempMin', 'tempMax', 'precipProb', 'sky'] } }
  }
}
