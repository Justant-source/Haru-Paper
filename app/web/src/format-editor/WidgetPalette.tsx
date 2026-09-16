import { useDraggable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import type { BlockType } from '../types/format'
import { newBlock, newRow } from '../types/format'
import { useI18n } from '../i18n'

interface WidgetPaletteProps {
  onAddWidget: (rowIndex: number, row: any) => void
}

export function WidgetPalette({ onAddWidget }: WidgetPaletteProps) {
  const { t } = useI18n()

  const blockTypes: BlockType[] = ['text', 'image', 'dateHeader', 'weather']

  const blockTypeLabels: Record<BlockType, string> = {
    text: t('widgetTypeText'),
    image: t('widgetTypeImage'),
    dateHeader: t('widgetTypeDateHeader'),
    weather: t('widgetTypeWeather'),
  }

  return (
    <div className="widget-palette">
      <div className="widget-palette-header">{t('addWidgetHint')}</div>
      <div className="widget-palette-cards">
        {blockTypes.map((blockType) => (
          <WidgetCard
            key={blockType}
            blockType={blockType}
            label={blockTypeLabels[blockType]}
          />
        ))}
      </div>
    </div>
  )
}

interface WidgetCardProps {
  blockType: BlockType
  label: string
}

function WidgetCard({ blockType, label }: WidgetCardProps) {
  const {
    setNodeRef,
    isDragging,
    transform,
    transition,
    listeners,
    attributes,
    over,
  } = useDraggable({
    id: `widget-${blockType}`,
    data: { type: 'widget', blockType },
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`widget-palette-card ${isDragging ? 'widget-palette-card-dragging' : ''}`}
      {...listeners}
      {...attributes}
    >
      <span>{label}</span>
    </div>
  )
}
