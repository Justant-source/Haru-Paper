import { type FormatStyle, type FontFamily, type Divider, DEFAULT_STYLE } from '../types/format'

const FONT_FAMILY_OPTIONS: { value: FontFamily; label: string }[] = [
  { value: 'Pretendard', label: 'Pretendard' },
  { value: 'Noto Sans KR', label: 'Noto Sans KR' },
]

const DIVIDER_OPTIONS: { value: Divider; label: string }[] = [
  { value: 'none', label: '없음' },
  { value: 'line', label: '실선' },
  { value: 'dashed', label: '점선' },
]

export function StyleForm({
  style,
  onChange,
}: {
  style: FormatStyle
  onChange: (style: FormatStyle) => void
}) {
  const s = style || DEFAULT_STYLE

  return (
    <div className="style-form">
      <label>
        폰트
        <select
          value={s.fontFamily}
          onChange={(e) => {
            onChange({ ...s, fontFamily: e.target.value as FontFamily })
          }}
        >
          {FONT_FAMILY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>

      <label>
        기본 글자 크기 (pt)
        <input
          type="number"
          min="6"
          max="48"
          value={s.baseFontSizePt}
          onChange={(e) => {
            onChange({
              ...s,
              baseFontSizePt: parseFloat(e.target.value),
            })
          }}
        />
      </label>

      <label>
        줄 높이 (배수)
        <input
          type="number"
          min="1.0"
          max="3.0"
          step="0.1"
          value={s.lineHeight}
          onChange={(e) => {
            onChange({
              ...s,
              lineHeight: parseFloat(e.target.value),
            })
          }}
        />
      </label>

      <fieldset className="margins-fieldset">
        <legend>여백 (mm)</legend>
        <div className="margins-grid">
          <label>
            위
            <input
              type="number"
              min="0"
              max="20"
              step="0.5"
              value={s.marginMm.top}
              onChange={(e) => {
                onChange({
                  ...s,
                  marginMm: {
                    ...s.marginMm,
                    top: parseFloat(e.target.value),
                  },
                })
              }}
            />
          </label>
          <label>
            오른쪽
            <input
              type="number"
              min="0"
              max="20"
              step="0.5"
              value={s.marginMm.right}
              onChange={(e) => {
                onChange({
                  ...s,
                  marginMm: {
                    ...s.marginMm,
                    right: parseFloat(e.target.value),
                  },
                })
              }}
            />
          </label>
          <label>
            아래
            <input
              type="number"
              min="0"
              max="20"
              step="0.5"
              value={s.marginMm.bottom}
              onChange={(e) => {
                onChange({
                  ...s,
                  marginMm: {
                    ...s.marginMm,
                    bottom: parseFloat(e.target.value),
                  },
                })
              }}
            />
          </label>
          <label>
            왼쪽
            <input
              type="number"
              min="0"
              max="20"
              step="0.5"
              value={s.marginMm.left}
              onChange={(e) => {
                onChange({
                  ...s,
                  marginMm: {
                    ...s.marginMm,
                    left: parseFloat(e.target.value),
                  },
                })
              }}
            />
          </label>
        </div>
      </fieldset>

      <label>
        블록 간격 (mm)
        <input
          type="number"
          min="0"
          max="30"
          step="0.5"
          value={s.blockGapMm}
          onChange={(e) => {
            onChange({
              ...s,
              blockGapMm: parseFloat(e.target.value),
            })
          }}
        />
      </label>

      <label>
        블록 구분선
        <select
          value={s.divider}
          onChange={(e) => {
            onChange({ ...s, divider: e.target.value as Divider })
          }}
        >
          {DIVIDER_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}
