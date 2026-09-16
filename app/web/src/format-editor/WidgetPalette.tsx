import { useDraggable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import type { BlockType } from '../types/format'
import { useI18n } from '../i18n'

/**
 * 블록 타입 4종을 드래그 소스로 보여준다. 실제로 캔버스에 새 행을 추가하는 로직은
 * 이 컴포넌트가 아니라 상위 LayoutEditor의 DndContext.onDragEnd가 처리한다
 * (active.data.current로 { type: 'widget', blockType }를 실어 보내기만 한다).
 */
export function WidgetPalette() {
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
          <WidgetCard key={blockType} blockType={blockType} label={blockTypeLabels[blockType]} />
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
  const { setNodeRef, isDragging, transform, listeners, attributes } = useDraggable({
    id: `widget-${blockType}`,
    data: { type: 'widget', blockType },
  })

  const style = {
    transform: CSS.Translate.toString(transform),
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
