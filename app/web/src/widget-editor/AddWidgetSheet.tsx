import type { WidgetCatalog, WidgetDescriptor } from '../types/widget'
import { BottomSheet } from '../components/BottomSheet'
import { WidgetIcon } from './WidgetIcon'

/** catalog=true인 위젯만 보여준다(작업지시서 07 7.1-5). */
export function AddWidgetSheet({
  open,
  onClose,
  catalog,
  atMax,
  onAdd,
}: {
  open: boolean
  onClose: () => void
  catalog: WidgetCatalog
  atMax: boolean
  onAdd: (descriptor: WidgetDescriptor) => void
}) {
  const catalogWidgets = catalog.widgets.filter((w) => w.catalog)

  return (
    <BottomSheet open={open} title="위젯 추가" onClose={onClose}>
      {atMax ? (
        <div className="banner banner-warn">
          <span>위젯은 최대 {catalog.grid.maxWidgets}개까지 넣을 수 있습니다</span>
        </div>
      ) : null}
      <ul className="widget-catalog-list">
        {catalogWidgets.map((d) => (
          <li key={d.type}>
            <button
              type="button"
              className="widget-catalog-item"
              disabled={atMax}
              onClick={() => onAdd(d)}
              aria-label={`${d.name} 추가`}
            >
              <span className="widget-catalog-icon">
                <WidgetIcon icon={d.icon} />
              </span>
              <span className="widget-catalog-body">
                <span className="widget-catalog-name-row">
                  <span className="widget-catalog-name">{d.name}</span>
                  {d.dynamic ? (
                    <span className="badge badge-neutral" title="인쇄 직전 최신 내용으로 갱신">
                      동적
                    </span>
                  ) : null}
                </span>
                <span className="widget-catalog-desc">{d.description}</span>
                <span className="widget-catalog-sizes">
                  {d.sizes.map((s) => (
                    <span key={s.id} className="size-chip">
                      {s.label}
                    </span>
                  ))}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </BottomSheet>
  )
}
