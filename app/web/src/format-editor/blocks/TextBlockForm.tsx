import { useRef } from 'react'
import { type TextProps } from '../../types/format'
import { Button } from '../../components/Button'

export function TextBlockForm({
  props,
  onChange,
}: {
  props: TextProps
  onChange: (props: TextProps) => void
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const insertAtCursor = (token: string) => {
    const el = textareaRef.current
    const text = props.text || ''
    if (!el) {
      onChange({ ...props, text: text + token })
      return
    }
    const start = el.selectionStart ?? text.length
    const end = el.selectionEnd ?? text.length
    const next = text.slice(0, start) + token + text.slice(end)
    onChange({ ...props, text: next })
    requestAnimationFrame(() => {
      const pos = start + token.length
      el.focus()
      el.setSelectionRange(pos, pos)
    })
  }

  return (
    <div className="block-form text-block-form">
      <label>
        텍스트
        <textarea
          ref={textareaRef}
          value={props.text || ''}
          onChange={(e) => onChange({ ...props, text: e.target.value })}
          placeholder="텍스트 입력"
          rows={4}
        />
      </label>

      <div className="variable-buttons">
        <Button type="button" variant="secondary" onClick={() => insertAtCursor('{{date}}')}>
          {'{{date}}'}
        </Button>
        <Button type="button" variant="secondary" onClick={() => insertAtCursor('{{weekday}}')}>
          {'{{weekday}}'}
        </Button>
      </div>
    </div>
  )
}
