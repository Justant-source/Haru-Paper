import { type TextProps } from '../../types/format'

export function TextBlockForm({
  props,
  onChange,
}: {
  props: TextProps
  onChange: (props: TextProps) => void
}) {
  return (
    <div className="block-form text-block-form">
      <label>
        텍스트
        <textarea
          value={props.text || ''}
          onChange={(e) => onChange({ ...props, text: e.target.value })}
          placeholder="텍스트 입력"
          rows={4}
        />
      </label>

      <div className="variable-buttons">
        <button
          type="button"
          onClick={() => {
            onChange({
              ...props,
              text: (props.text || '') + '{{date}}',
            })
          }}
          className="btn-variable"
        >
          {'{{date}}'}
        </button>
        <button
          type="button"
          onClick={() => {
            onChange({
              ...props,
              text: (props.text || '') + '{{weekday}}',
            })
          }}
          className="btn-variable"
        >
          {'{{weekday}}'}
        </button>
      </div>
    </div>
  )
}
