import { type BlockStyle, type Align } from '../types/format'

const ALIGN_OPTIONS: { value: Align; label: string }[] = [
  { value: 'left', label: '왼쪽' },
  { value: 'center', label: '가운데' },
  { value: 'right', label: '오른쪽' },
]

export function BlockStyleForm({
  style,
  onChange,
}: {
  style: BlockStyle | null | undefined
  onChange: (style: BlockStyle) => void
}) {
  const s = style || {}

  return (
    <div className="block-form block-style-form">
      <h3>블록 스타일</h3>

      <label>
        정렬
        <select
          value={s.align || 'left'}
          onChange={(e) => {
            onChange({ ...s, align: e.target.value as Align })
          }}
        >
          {ALIGN_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>

      <label>
        글자 크기 (pt)
        <input
          type="number"
          min="6"
          max="72"
          value={s.fontSizePt ?? ''}
          onChange={(e) => {
            const val = e.target.value ? parseInt(e.target.value, 10) : undefined
            onChange({ ...s, fontSizePt: val })
          }}
          placeholder="기본값 사용"
        />
      </label>

      <label className="checkbox-label">
        <input
          type="checkbox"
          checked={s.bold || false}
          onChange={(e) => {
            onChange({ ...s, bold: e.target.checked || undefined })
          }}
        />
        굵게
      </label>

      <label>
        위쪽 여백 (mm)
        <input
          type="number"
          min="0"
          max="30"
          step="0.5"
          value={s.marginTopMm ?? ''}
          onChange={(e) => {
            const val = e.target.value ? parseFloat(e.target.value) : undefined
            onChange({ ...s, marginTopMm: val })
          }}
          placeholder="0"
        />
      </label>

      <label>
        아래쪽 여백 (mm)
        <input
          type="number"
          min="0"
          max="30"
          step="0.5"
          value={s.marginBottomMm ?? ''}
          onChange={(e) => {
            const val = e.target.value ? parseFloat(e.target.value) : undefined
            onChange({ ...s, marginBottomMm: val })
          }}
          placeholder="0"
        />
      </label>
    </div>
  )
}
