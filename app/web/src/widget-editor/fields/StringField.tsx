import type { PropField } from '../../types/widget'

/** string(한 줄)·text(여러 줄) 공통 처리. pattern이 "^[A-Z"로 시작하면 입력을 자동 대문자화한다
 * (작업지시서 07 5.2 stockChart.ticker 규칙 — 소문자는 서버에서 422). */
export function StringField({
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
  const str = typeof value === 'string' ? value : ''
  const uppercase = field.pattern?.startsWith('^[A-Z') ?? false
  const pattern = field.pattern ? new RegExp(field.pattern) : null
  const localError = str && pattern && !pattern.test(str) ? '형식이 올바르지 않습니다' : undefined
  const shown = error ?? localError
  const inputId = `widget-field-${field.key}`

  const handle = (raw: string) => {
    let next = uppercase ? raw.toUpperCase() : raw
    if (field.maxLength != null) next = next.slice(0, field.maxLength)
    onChange(next)
  }

  return (
    <div className="field">
      <label htmlFor={inputId}>{field.label}</label>
      {field.kind === 'text' ? (
        <textarea
          id={inputId}
          value={str}
          maxLength={field.maxLength}
          placeholder={field.placeholder}
          rows={4}
          onChange={(e) => handle(e.target.value)}
          aria-invalid={!!shown}
        />
      ) : (
        <input
          id={inputId}
          type="text"
          value={str}
          maxLength={field.maxLength}
          placeholder={field.placeholder}
          onChange={(e) => handle(e.target.value)}
          aria-invalid={!!shown}
        />
      )}
      {field.help ? <span className="field-help">{field.help}</span> : null}
      {shown ? <span className="field-error">{shown}</span> : null}
    </div>
  )
}
