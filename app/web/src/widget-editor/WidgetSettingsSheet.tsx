import type { WidgetInstance } from '../types/format'
import type { WidgetDescriptor } from '../types/widget'
import { BottomSheet } from '../components/BottomSheet'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'
import { SizeSelector } from './SizeSelector'
import { FieldRenderer } from './fields/FieldRenderer'

/**
 * 위젯 설정 시트. 위젯 이름·설명은 descriptor에서 읽어 안내로만 보여준다(WidgetInstance에는
 * 이름·설명 필드가 없다 — 문서 이름은 meta.name뿐이다, 작업지시서 07 3.2).
 * catalog=false이거나 카탈로그에 없는 type은 "이전 버전 위젯"으로 삭제·이동만 허용한다(07 7.1-6).
 */
export function WidgetSettingsSheet({
  open,
  widget,
  descriptor,
  fieldErrors,
  onClose,
  onChangeSize,
  onChangeProp,
  onDelete,
}: {
  open: boolean
  widget: WidgetInstance | null
  descriptor: WidgetDescriptor | undefined
  fieldErrors: Record<string, string> | undefined
  onClose: () => void
  onChangeSize: (size: string) => void
  onChangeProp: (key: string, value: unknown) => void
  onDelete: () => void
}) {
  const { t } = useI18n()
  const isLegacy = !widget || !descriptor || !descriptor.catalog
  const title = descriptor?.name ?? widget?.type ?? ''

  return (
    <BottomSheet open={open} title={title} onClose={onClose}>
      {!widget ? null : isLegacy ? (
        <div className="widget-settings-legacy">
          <p className="field-help">
            {descriptor
              ? descriptor.description
              : '이전 버전에서 만든 위젯입니다. 이 앱 버전에서는 설정을 편집할 수 없습니다. 삭제하거나 순서만 옮길 수 있습니다.'}
          </p>
          <Button variant="danger" onClick={onDelete}>
            {t('delete')}
          </Button>
        </div>
      ) : (
        <div className="widget-settings-form">
          {descriptor!.description ? <p className="widget-settings-desc">{descriptor!.description}</p> : null}
          {fieldErrors?._general ? <p className="field-error">{fieldErrors._general}</p> : null}

          <div className="field">
            <span>크기</span>
            <SizeSelector sizes={descriptor!.sizes} value={widget.size} onChange={onChangeSize} />
          </div>

          {descriptor!.fields.map((field) => (
            <FieldRenderer
              key={field.key}
              field={field}
              value={widget.props[field.key]}
              onChange={(value) => onChangeProp(field.key, value)}
              error={fieldErrors?.[field.key]}
            />
          ))}

          <Button variant="danger" onClick={onDelete}>
            {t('delete')}
          </Button>
        </div>
      )}
    </BottomSheet>
  )
}
