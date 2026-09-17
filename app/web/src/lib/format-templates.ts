// 새 포맷을 만들 때 고르는 시작 구성. 위젯 종류는 서버 카탈로그에서 찾고, 카탈로그에 없는 것은 조용히 건너뛴다
// (서버에서 위젯을 빼도 앱이 깨지지 않게).

import { emptyDocument, type FormatDocument, type WidgetInstance } from '../types/format'
import { newWidgetInstance, type WidgetCatalog } from '../types/widget'

export type TemplateName = 'morning' | 'empty'

export function isTemplateName(value: string | null): value is TemplateName {
  return value === 'morning' || value === 'empty'
}

export function buildTemplate(name: TemplateName, catalog: WidgetCatalog): FormatDocument {
  if (name === 'empty') {
    return emptyDocument('')
  }

  const find = (type: string) => catalog.widgets.find((w) => w.type === type)
  const widgets: WidgetInstance[] = []
  const add = (type: string, size: string, props?: Record<string, unknown>) => {
    const descriptor = find(type)
    if (!descriptor) return
    // 템플릿이 원하는 크기를 그 위젯이 지원하지 않으면 기본 크기로
    const supported = descriptor.sizes.some((s) => s.id === size)
    widgets.push(newWidgetInstance(descriptor, { size: supported ? size : undefined, props }))
  }

  // 아침 브리핑: 날짜 → 아침편지 → (날씨 | 증시) 나란히
  add('dateHeader', '4x1')
  add('morningLetter', '4xauto')
  add('weather', '2x4') // 위치는 필수값이라 비워 둔다 → 편집 화면이 바로 설정을 요구한다
  add('stockChart', '2x4', { ticker: 'AAPL' })

  return { ...emptyDocument('아침 브리핑'), widgets }
}
