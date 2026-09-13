import { type Block } from '../types/format'

function getBlockSummary(block: Block): string {
  switch (block.type) {
    case 'text': {
      const text = (block.props as any)?.text || ''
      const firstLine = text.split('\n')[0]
      return firstLine.substring(0, 50) || '(빈 텍스트)'
    }
    case 'image':
      return '이미지'
    case 'dateHeader': {
      const pattern = (block.props as any)?.pattern || 'YYYY년 M월 D일 dddd'
      return pattern
    }
    case 'weather': {
      const fields = (block.props as any)?.fields || []
      return fields.length > 0 ? fields.join(', ') : '(필드 미선택)'
    }
    default:
      return '알 수 없음'
  }
}

function getBlockLabel(type: string): string {
  switch (type) {
    case 'text':
      return '텍스트'
    case 'image':
      return '이미지'
    case 'dateHeader':
      return '날짜 헤더'
    case 'weather':
      return '날씨'
    default:
      return type
  }
}

export function BlockList({
  blocks,
  selectedIndex,
  onSelect,
  onChange,
}: {
  blocks: Block[]
  selectedIndex: number | null
  onSelect: (index: number) => void
  onChange: (blocks: Block[]) => void
}) {
  const moveBlock = (index: number, direction: 'up' | 'down') => {
    if (
      (direction === 'up' && index === 0) ||
      (direction === 'down' && index === blocks.length - 1)
    ) {
      return
    }

    const newBlocks = [...blocks]
    const targetIndex = direction === 'up' ? index - 1 : index + 1
    ;[newBlocks[index], newBlocks[targetIndex]] = [
      newBlocks[targetIndex],
      newBlocks[index],
    ]
    onChange(newBlocks)
  }

  const deleteBlock = (index: number) => {
    onChange(blocks.filter((_, i) => i !== index))
    if (index < blocks.length - 1) {
      onSelect(index)
    } else if (blocks.length > 1) {
      onSelect(index - 1)
    }
  }

  return (
    <div className="block-list">
      {blocks.map((block, index) => (
        <div
          key={index}
          className={`block-item ${selectedIndex === index ? 'selected' : ''}`}
        >
          <button
            type="button"
            onClick={() => onSelect(index)}
            className="block-item-content"
          >
            <span className="block-label">{getBlockLabel(block.type)}</span>
            <span className="block-summary">{getBlockSummary(block)}</span>
          </button>

          <div className="block-item-controls">
            <button
              type="button"
              onClick={() => moveBlock(index, 'up')}
              disabled={index === 0}
              className="btn-move-up"
              title="위로"
            >
              ↑
            </button>
            <button
              type="button"
              onClick={() => moveBlock(index, 'down')}
              disabled={index === blocks.length - 1}
              className="btn-move-down"
              title="아래로"
            >
              ↓
            </button>
            <button
              type="button"
              onClick={() => deleteBlock(index)}
              className="btn-delete"
              title="삭제"
            >
              삭제
            </button>
          </div>
        </div>
      ))}
    </div>
  )
}
