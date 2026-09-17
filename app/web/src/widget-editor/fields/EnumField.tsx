import type { PropField } from '../../types/widget'

/** 선택지 4개 이하면 세그먼트 버튼, 많으면 select(작업지시서 07 7.1-6). */
export function EnumField({
  field,
  value,
  onChange,
  error,
}: {
  field: PropField
  value: unknown
  onChange: (value: string) => void
  error?: string
}) {
  const options = field.options ?? []
  const str = typeof value === 'string' ? value : ''
  const inputId = `widget-field-${field.key}`

  if (options.length > 0 && options.length <= 4) {
    return (
      <div className="field">
        <span id={`${inputId}-label`}>{field.label}</span>
        <div className="segmented" role="radiogroup" aria-labelledby={`${inputId}-label`}>
          {options.map((o) => (
            <button
              key={o.value}
              type="button"
              role="radio"
              aria-checked={str === o.value}
              className={`segmented-btn${str === o.value ? ' active' : ''}`}
              onClick={() => onChange(o.value)}
            >
              {o.label}
            </button>
          ))}
        </div>
        {error ? <span className="field-error">{error}</span> : null}
      </div>
    )
  }

  return (
    <div className="field">
      <label htmlFor={inputId}>{field.label}</label>
      <select id={inputId} value={str} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {error ? <span className="field-error">{error}</span> : null}
    </div>
  )
}
