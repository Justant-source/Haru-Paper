import type { WidgetSize } from '../types/widget'

/** descriptor.sizes를 카드로 보여준다. label 옆에 cols:rows 비율을 작은 도식으로 곁들인다(작업지시서 07 7.1-6). */
export function SizeSelector({
  sizes,
  value,
  onChange,
}: {
  sizes: WidgetSize[]
  value: string
  onChange: (id: string) => void
}) {
  return (
    <div className="size-selector" role="radiogroup" aria-label="크기">
      {sizes.map((s) => (
        <button
          key={s.id}
          type="button"
          role="radio"
          aria-checked={value === s.id}
          className={`size-option${value === s.id ? ' active' : ''}`}
          onClick={() => onChange(s.id)}
        >
          <span
            className={`size-option-swatch${s.autoHeight ? ' size-option-swatch-auto' : ''}`}
            style={s.autoHeight ? undefined : { aspectRatio: `${s.cols} / ${s.rows ?? 1}` }}
            aria-hidden="true"
          />
          <span className="size-option-label">{s.label}</span>
        </button>
      ))}
    </div>
  )
}
