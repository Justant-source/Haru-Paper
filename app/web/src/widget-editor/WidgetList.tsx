import type { FormatDocument } from '../types/format'
import type { WidgetCatalog } from '../types/widget'
import { missingRequiredFields } from '../types/widget'
import { Badge } from '../components/Badge'
import { IconChevronDown, IconChevronUp, IconGear, IconTrash } from '../components/icons'
import { WidgetIcon } from './WidgetIcon'
import { summarizeWidgetProps } from './widgetSummary'
import { hasWidgetError, type WidgetFieldErrors } from './fieldErrors'

/** 인쇄 순서 = 이 목록 순서. 드래그 없이 ▲▼로만 옮긴다(작업지시서 07 7.1-4). */
export function WidgetList({
  document,
  catalog,
  fieldErrors,
  onOpenSettings,
  onMoveUp,
  onMoveDown,
  onDelete,
}: {
  document: FormatDocument
  catalog: WidgetCatalog
  fieldErrors: WidgetFieldErrors
  onOpenSettings: (widgetId: string) => void
  onMoveUp: (widgetId: string) => void
  onMoveDown: (widgetId: string) => void
  onDelete: (widgetId: string) => void
}) {
  if (document.widgets.length === 0) return null

  return (
    <ul className="widget-list">
      {document.widgets.map((widget, index) => {
        const descriptor = catalog.widgets.find((d) => d.type === widget.type)
        const isLegacy = !descriptor || !descriptor.catalog
        const missing = descriptor ? missingRequiredFields(descriptor, widget).length > 0 : false
        const summary = descriptor ? summarizeWidgetProps(descriptor, widget) : ''
        const name = descriptor?.name ?? widget.type

        return (
          <li key={widget.id} className="widget-list-item">
            <button
              type="button"
              className="widget-list-info"
              onClick={() => onOpenSettings(widget.id)}
              aria-label={`${name} 설정 열기`}
            >
              <span className="widget-list-icon">
                <WidgetIcon icon={descriptor?.icon} />
              </span>
              <span className="widget-list-body">
                <span className="widget-list-name-row">
                  <span className="widget-list-name">{name}</span>
                  {isLegacy ? <Badge tone="neutral">이전 버전</Badge> : null}
                  {missing ? <Badge tone="warn">설정 필요</Badge> : null}
                  {hasWidgetError(fieldErrors, widget.id) ? <Badge tone="danger">오류</Badge> : null}
                </span>
                {summary ? <span className="widget-list-summary">{summary}</span> : null}
              </span>
            </button>
            <div className="widget-list-controls">
              <button
                type="button"
                className="icon-btn"
                aria-label={`${name} 위로 이동`}
                onClick={() => onMoveUp(widget.id)}
                disabled={index === 0}
              >
                <IconChevronUp />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label={`${name} 아래로 이동`}
                onClick={() => onMoveDown(widget.id)}
                disabled={index === document.widgets.length - 1}
              >
                <IconChevronDown />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label={`${name} 설정`}
                onClick={() => onOpenSettings(widget.id)}
              >
                <IconGear />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label={`${name} 삭제`}
                onClick={() => onDelete(widget.id)}
              >
                <IconTrash />
              </button>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
