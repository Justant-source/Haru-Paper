import { useState } from 'react'
import type { PropField } from '../../types/widget'
import { IconChevron } from '../../components/icons'
import { LocationPickerSheet } from './LocationPickerSheet'

function isLocationValue(value: unknown): value is { label: string } {
  return typeof value === 'object' && value !== null && typeof (value as { label?: unknown }).label === 'string'
}

export function KoreaLocationField({
  field,
  value,
  onChange,
  error,
}: {
  field: PropField
  value: unknown
  onChange: (value: { label: string; lat: number; lon: number }) => void
  error?: string
}) {
  const [pickerOpen, setPickerOpen] = useState(false)
  const label = isLocationValue(value) ? value.label : null

  return (
    <div className="field">
      <span id={`widget-field-${field.key}-label`}>{field.label}</span>
      <button
        type="button"
        className="location-picker-trigger"
        aria-labelledby={`widget-field-${field.key}-label`}
        onClick={() => setPickerOpen(true)}
      >
        {label ? <span>{label}</span> : <span className="location-picker-placeholder">위치를 선택하세요</span>}
        <IconChevron className="location-picker-chevron" />
      </button>
      {field.help ? <span className="field-help">{field.help}</span> : null}
      {error ? <span className="field-error">{error}</span> : null}
      <LocationPickerSheet
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onSelect={(next) => {
          onChange(next)
          setPickerOpen(false)
        }}
      />
    </div>
  )
}
