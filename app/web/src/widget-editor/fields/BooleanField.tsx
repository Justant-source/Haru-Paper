import type { PropField } from '../../types/widget'
import { Toggle } from '../../components/Toggle'

export function BooleanField({
  field,
  value,
  onChange,
}: {
  field: PropField
  value: unknown
  onChange: (value: boolean) => void
}) {
  return (
    <div className="field field-row">
      <span>{field.label}</span>
      <Toggle checked={value === true} onChange={onChange} label={field.label} />
    </div>
  )
}
