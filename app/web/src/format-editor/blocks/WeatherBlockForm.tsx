import { type WeatherProps, type WeatherField } from '../../types/format'

const WEATHER_FIELDS: { id: WeatherField; label: string }[] = [
  { id: 'tempMin', label: '최저기온' },
  { id: 'tempMax', label: '최고기온' },
  { id: 'precipProb', label: '강수확률' },
  { id: 'sky', label: '하늘 상태' },
]

export function WeatherBlockForm({
  props,
  onChange,
}: {
  props: WeatherProps
  onChange: (props: WeatherProps) => void
}) {
  const fields = props.fields || ['tempMin', 'tempMax', 'precipProb', 'sky']

  const toggleField = (field: WeatherField) => {
    if (fields.includes(field)) {
      onChange({
        ...props,
        fields: fields.filter((f) => f !== field),
      })
    } else {
      onChange({
        ...props,
        fields: [...fields, field],
      })
    }
  }

  return (
    <div className="block-form weather-block-form">
      <p className="hint">위치: 설정의 기본 위치</p>
      <p>표시할 필드 선택 (최소 1개):</p>
      <div className="weather-checkboxes">
        {WEATHER_FIELDS.map((field) => (
          <label key={field.id} className="checkbox-label">
            <input
              type="checkbox"
              checked={fields.includes(field.id)}
              onChange={() => toggleField(field.id)}
            />
            {field.label}
          </label>
        ))}
      </div>
    </div>
  )
}
