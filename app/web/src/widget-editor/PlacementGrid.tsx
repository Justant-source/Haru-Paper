import { useRef } from 'react'
import type { FormatDocument } from '../types/format'
import type { WidgetCatalog } from '../types/widget'
import { missingRequiredFields } from '../types/widget'
import { useGridMetrics } from './gridMath'
import { WidgetBox } from './WidgetBox'
import { hasWidgetError, type WidgetFieldErrors } from './fieldErrors'

/**
 * 서버와 같은 4열 CSS 그리드(grid-auto-flow: row dense)로 위젯을 그린다.
 * 서버 렌더러도 같은 dense 배치를 쓰므로 배치도와 실제 인쇄 결과의 위젯 위치가 일치한다
 * (작업지시서 07 D1·7.1-3).
 */
export function PlacementGrid({
  document,
  catalog,
  fieldErrors,
  onSelect,
}: {
  document: FormatDocument
  catalog: WidgetCatalog
  fieldErrors: WidgetFieldErrors
  onSelect: (widgetId: string) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const metrics = useGridMetrics(containerRef, catalog.grid)

  if (document.widgets.length === 0) {
    return (
      <div className="widget-grid-empty">
        <p>아직 위젯이 없습니다. 아래에서 위젯을 추가하세요.</p>
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className="widget-grid"
      style={{
        gridTemplateColumns: `repeat(${catalog.grid.columns}, 1fr)`,
        columnGap: metrics.gapPx ? `${metrics.gapPx}px` : undefined,
        rowGap: metrics.gapPx ? `${metrics.gapPx}px` : undefined,
        gridAutoRows: metrics.rowHeightPx ? `${metrics.rowHeightPx}px` : undefined,
      }}
    >
      {document.widgets.map((widget) => {
        const descriptor = catalog.widgets.find((d) => d.type === widget.type)
        const size = descriptor?.sizes.find((s) => s.id === widget.size)
        const cols = size?.cols ?? catalog.grid.columns
        const rows = size?.autoHeight ? size.previewRows : (size?.rows ?? 1)
        const missing = descriptor ? missingRequiredFields(descriptor, widget).length > 0 : false

        return (
          <WidgetBox
            key={widget.id}
            widget={widget}
            descriptor={descriptor}
            size={size}
            missing={missing}
            hasError={hasWidgetError(fieldErrors, widget.id)}
            style={{ gridColumn: `span ${cols}`, gridRow: `span ${rows}` }}
            onClick={() => onSelect(widget.id)}
          />
        )
      })}
    </div>
  )
}
