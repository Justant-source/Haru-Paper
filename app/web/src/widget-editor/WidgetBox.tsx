import type { CSSProperties } from 'react'
import type { WidgetInstance } from '../types/format'
import type { WidgetDescriptor, WidgetSize } from '../types/widget'
import { WidgetIcon } from './WidgetIcon'
import { summarizeWidgetProps } from './widgetSummary'

/** 배치도 한 칸. 자동 높이 위젯은 아래쪽을 점선으로 그려 "내용 길이만큼"임을 나타낸다(작업지시서 07 7.1-3). */
export function WidgetBox({
  widget,
  descriptor,
  size,
  missing,
  hasError,
  style,
  onClick,
}: {
  widget: WidgetInstance
  descriptor: WidgetDescriptor | undefined
  size: WidgetSize | undefined
  missing: boolean
  hasError: boolean
  style: CSSProperties
  onClick: () => void
}) {
  const isLegacy = !descriptor || !descriptor.catalog
  const summary = descriptor ? summarizeWidgetProps(descriptor, widget) : ''

  return (
    <button
      type="button"
      className={[
        'widget-box',
        size?.autoHeight ? 'widget-box-auto' : '',
        missing ? 'widget-box-missing' : '',
        hasError ? 'widget-box-error' : '',
        isLegacy ? 'widget-box-legacy' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      style={style}
      onClick={onClick}
      aria-label={`${descriptor?.name ?? widget.type} 설정 열기`}
    >
      <span className="widget-box-icon">
        <WidgetIcon icon={descriptor?.icon} />
      </span>
      <span className="widget-box-name">{descriptor?.name ?? widget.type}</span>
      {summary ? <span className="widget-box-summary">{summary}</span> : null}
      {missing ? <span className="widget-box-badge">설정 필요</span> : null}
    </button>
  )
}
