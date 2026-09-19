// 위젯 상자·목록 카드에 보여줄 요약 문구. descriptor.fields를 훑어서 만든다 —
// 위젯 type별로 하드코딩하지 않는다(작업지시서 07 7.1-3).
import type { WidgetDescriptor } from '../types/widget'
import type { WidgetInstance } from '../types/format'

const MAX_LENGTH = 40

function isKoreaLocationValue(value: unknown): value is { label: string } {
  return typeof value === 'object' && value !== null && typeof (value as { label?: unknown }).label === 'string'
}

export function summarizeWidgetProps(descriptor: WidgetDescriptor, widget: WidgetInstance): string {
  const parts: string[] = []

  for (const field of descriptor.fields) {
    const value = widget.props[field.key]
    if (value === undefined || value === null || value === '') continue

    switch (field.kind) {
      case 'string':
      case 'text':
        if (typeof value === 'string') parts.push(value)
        break
      case 'integer':
        if (typeof value === 'number') parts.push(String(value))
        break
      case 'enum': {
        const option = field.options?.find((o) => o.value === value)
        parts.push(option?.label ?? String(value))
        break
      }
      case 'koreaLocation':
        if (isKoreaLocationValue(value)) parts.push(value.label)
        break
      case 'boolean':
        // boolean은 요약에 넣지 않는다(작업지시서 07 7.1-3)
        break
      case 'asset':
        // assetId 값 자체는 의미 없는 문자열이라 "이미지 있음"으로만 표시(2026-09-19)
        if (typeof value === 'string') parts.push('이미지 있음')
        break
      default:
        // 모르는 kind: 요약에 표시할 값이 마땅치 않다
        break
    }
  }

  const joined = parts.join(' · ')
  return joined.length > MAX_LENGTH ? `${joined.slice(0, MAX_LENGTH - 1)}…` : joined
}
