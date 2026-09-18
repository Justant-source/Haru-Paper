// 위젯 카탈로그. 원본: docs/server/widgets.md, 서버 record WidgetDescriptor·WidgetSize·PropField.
// 앱은 위젯 종류를 하드코딩하지 않는다 — 서버가 준 descriptor만 보고 카탈로그·크기 선택·설정 폼을 그린다.
// 그래서 서버에 위젯이 새로 추가돼도 앱 코드는 고칠 필요가 없다.

import type { WidgetInstance } from './format'
import { uuid } from '../lib/uuid'

export interface GridInfo {
  columns: number // 4
  rowUnitMm: number // 12
  gapMm: number // 2
  maxWidgets: number // 20
}

export interface WidgetSize {
  id: string // "2x4" | "4xauto" ...
  cols: number
  rows: number | null // null = 자동 높이(항상 4열)
  previewRows: number // 배치도에서 자동 높이 위젯을 몇 행으로 그릴지
  label: string
  autoHeight: boolean
}

export type PropFieldKind =
  | 'string'
  | 'text'
  | 'integer'
  | 'boolean'
  | 'enum'
  | 'koreaLocation'
  | 'asset'

export interface PropFieldOption {
  value: string
  label: string
}

/** 서버는 null인 항목을 생략한다(@JsonInclude NON_NULL) → 전부 선택적이다. */
export interface PropField {
  key: string
  label: string
  kind: PropFieldKind
  required: boolean
  defaultValue?: unknown
  min?: number
  max?: number
  maxLength?: number
  pattern?: string
  placeholder?: string
  help?: string
  options?: PropFieldOption[]
}

export interface WidgetDescriptor {
  type: string
  name: string
  description: string
  icon: string // letter | chart | weather | calendar | text | image — 모르는 값이면 기본 아이콘
  dynamic: boolean
  catalog: boolean // false면 "위젯 추가" 목록에 안 보인다(이전 포맷 호환용)
  sizes: WidgetSize[]
  defaultSize: string
  fields: PropField[]
}

export interface WidgetCatalog {
  grid: GridInfo
  widgets: WidgetDescriptor[]
}

/** koreaLocation 필드의 값. */
export interface KoreaLocationValue {
  label: string
  lat: number
  lon: number
}

/** GET /api/widgets/locations 항목. label = "경기도 성남시 분당구" */
export interface KoreaLocation {
  sido: string
  name: string
  label: string
  lat: number
  lon: number
}

/** descriptor의 기본 크기·기본 설정값으로 새 위젯을 만든다. overrides로 일부 props를 덮어쓸 수 있다. */
export function newWidgetInstance(
  descriptor: WidgetDescriptor,
  overrides?: { size?: string; props?: Record<string, unknown> },
): WidgetInstance {
  const props: Record<string, unknown> = {}
  for (const field of descriptor.fields) {
    if (field.defaultValue !== undefined && field.defaultValue !== null) {
      props[field.key] = field.defaultValue
    }
  }
  return {
    id: uuid(),
    type: descriptor.type,
    size: overrides?.size ?? descriptor.defaultSize,
    props: { ...props, ...overrides?.props },
  }
}

/** 필수인데 값이 비어 있는 필드들. 비어 있지 않으면 저장 전에 설정 시트를 열어 채우게 한다. */
export function missingRequiredFields(descriptor: WidgetDescriptor, widget: WidgetInstance): PropField[] {
  return descriptor.fields.filter((field) => {
    if (!field.required) return false
    const value = widget.props[field.key]
    return value === undefined || value === null || value === ''
  })
}
