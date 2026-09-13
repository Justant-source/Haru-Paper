import { type DateHeaderProps } from '../../types/format'

export function DateHeaderBlockForm({
  props,
  onChange,
}: {
  props: DateHeaderProps
  onChange: (props: DateHeaderProps) => void
}) {
  return (
    <div className="block-form date-header-block-form">
      <label>
        날짜 패턴
        <input
          type="text"
          value={props.pattern || 'YYYY년 M월 D일 dddd'}
          onChange={(e) => onChange({ ...props, pattern: e.target.value })}
          placeholder="예: YYYY년 M월 D일 dddd"
        />
      </label>
      <p className="hint">토큰: YYYY, MM, M, DD, D, dddd(월요일), ddd(월)</p>
    </div>
  )
}
