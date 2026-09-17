import type { PropField } from '../../types/widget'

/** asset·모르는 kind: 값은 보존하되 이 앱 버전에서는 편집할 수 없다고 안내한다(작업지시서 07 7.1-6). */
export function UnsupportedField({ field }: { field: PropField }) {
  return (
    <div className="field">
      <span>{field.label}</span>
      <p className="field-unsupported">이 앱 버전에서는 편집할 수 없는 항목입니다.</p>
    </div>
  )
}
