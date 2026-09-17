import type { PropField } from '../../types/widget'

export function IntegerField({
  field,
  value,
  onChange,
  error,
}: {
  field: PropField
  value: unknown
  onChange: (value: number) => void
  error?: string
}) {
  const fallback = typeof field.defaultValue === 'number' ? field.defaultValue : (field.min ?? 0)
  const num = typeof value === 'number' && Number.isFinite(value) ? value : fallback
  const inputId = `widget-field-${field.key}`

  const clamp = (n: number) => {
    let v = n
    if (field.min != null) v = Math.max(field.min, v)
    if (field.max != null) v = Math.min(field.max, v)
    return v
  }
  const set = (n: number) => onChange(clamp(Math.round(n)))

  return (
    <div className="field">
      <label htmlFor={inputId}>{field.label}</label>
      <div className="stepper">
        <button
          type="button"
          className="stepper-btn"
          aria-label={`${field.label} 줄이기`}
          onClick={() => set(num - 1)}
          disabled={field.min != null && num <= field.min}
        >
          −
        </button>
        <input
          id={inputId}
          className="stepper-input"
          type="number"
          inputMode="numeric"
          value={num}
          min={field.min}
          max={field.max}
          onChange={(e) => {
            const n = Number(e.target.value)
            if (!Number.isNaN(n)) set(n)
          }}
          aria-invalid={!!error}
        />
        <button
          type="button"
          className="stepper-btn"
          aria-label={`${field.label} 늘리기`}
          onClick={() => set(num + 1)}
          disabled={field.max != null && num >= field.max}
        >
          ＋
        </button>
      </div>
      {field.help ? <span className="field-help">{field.help}</span> : null}
      {error ? <span className="field-error">{error}</span> : null}
    </div>
  )
}
