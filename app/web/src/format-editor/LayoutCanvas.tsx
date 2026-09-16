import { SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { useDroppable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import { useEffect, useRef, useState } from 'react'
import type { Row, Slot, SlotWidth } from '../types/format'
import { useI18n } from '../i18n'
import { SlotContent } from './SlotContent'

interface LayoutCanvasProps {
  rows: Row[]
  onChange: (rows: Row[]) => void
  onSelectSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
}

/**
 * 행/슬롯을 그리는 순수 캔버스. 드래그 결과를 rows에 반영하는 reducer는 상위
 * LayoutEditor의 DndContext.onDragEnd가 담당한다 — 여기는 렌더링과 리사이즈
 * 제스처(별도 포인터 이벤트, sortable 아님)만 처리한다.
 */
export function LayoutCanvas({ rows, onChange, onSelectSlot, printerWidthPx }: LayoutCanvasProps) {
  const { t } = useI18n()
  const { setNodeRef: setTrashRef, isOver: isOverTrash } = useDroppable({ id: 'trash-zone' })

  return (
    <div className="layout-canvas-wrapper">
      {rows.length === 0 ? (
        <div className="layout-canvas">
          <div className="empty-state">{t('layoutCanvasEmpty')}</div>
        </div>
      ) : (
        <SortableContext items={rows.map((r) => r.id)} strategy={verticalListSortingStrategy}>
          <div className="layout-canvas">
            {rows.map((row) => (
              <RowComponent
                key={row.id}
                row={row}
                onSelectSlot={onSelectSlot}
                onChange={onChange}
                rows={rows}
                printerWidthPx={printerWidthPx}
              />
            ))}
          </div>
        </SortableContext>
      )}

      <div
        ref={setTrashRef}
        className={`layout-trash-zone ${isOverTrash ? 'layout-trash-zone-active' : ''}`}
      >
        <div>{t('trashZoneLabel')}</div>
      </div>
    </div>
  )
}

interface RowComponentProps {
  row: Row
  onSelectSlot: (rowId: string, slotId: string) => void
  onChange: (rows: Row[]) => void
  rows: Row[]
  printerWidthPx: number
}

function RowComponent({ row, onSelectSlot, onChange, rows, printerWidthPx }: RowComponentProps) {
  const { setNodeRef, transform, transition, isDragging } = useSortable({ id: row.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  }

  const is2Slot = row.slots.length === 2

  return (
    <div ref={setNodeRef} style={style} className={`layout-row ${isDragging ? 'layout-row-dragging' : ''}`}>
      {row.slots.map((slot) => {
        const slotWidthFraction = parseFloat(slot.width.split('/')[0]) / parseFloat(slot.width.split('/')[1])
        const flexBasisPercent = (slotWidthFraction * 100).toFixed(1)

        return (
          <SlotCard
            key={slot.id}
            slot={slot}
            rowId={row.id}
            onSelectSlot={onSelectSlot}
            printerWidthPx={printerWidthPx}
            slotWidthFraction={slotWidthFraction}
            flexBasisPercent={flexBasisPercent}
          />
        )
      })}

      {is2Slot && <ResizeHandle row={row} onChange={onChange} rows={rows} />}
    </div>
  )
}

interface SlotCardProps {
  slot: Slot
  rowId: string
  onSelectSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
  slotWidthFraction: number
  flexBasisPercent: string
}

function SlotCard({ slot, rowId, onSelectSlot, printerWidthPx, slotWidthFraction, flexBasisPercent }: SlotCardProps) {
  const { setNodeRef, transform, transition, isDragging, listeners, attributes } = useSortable({ id: slot.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.6 : 1,
    flexBasis: `${flexBasisPercent}%`,
    flex: `0 0 ${flexBasisPercent}%`,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`layout-slot ${isDragging ? 'layout-slot-dragging' : ''}`}
      onClick={() => onSelectSlot(rowId, slot.id)}
    >
      <div className="layout-slot-handle" {...listeners} {...attributes} />

      <div className="slot-content-wrapper">
        <SlotContent block={slot.block} printerWidthPx={printerWidthPx} slotWidthFraction={slotWidthFraction} />
      </div>
    </div>
  )
}

interface ResizeHandleProps {
  row: Row
  onChange: (rows: Row[]) => void
  rows: Row[]
}

function ResizeHandle({ row, onChange, rows }: ResizeHandleProps) {
  const handleRef = useRef<HTMLDivElement>(null)
  const [isResizing, setIsResizing] = useState(false)

  useEffect(() => {
    if (!isResizing) return

    const onPointerMove = (e: PointerEvent) => {
      if (!handleRef.current) return

      const rect = handleRef.current.parentElement?.getBoundingClientRect()
      if (!rect) return

      const x = e.clientX - rect.left

      const TWO_SLOT_WIDTH_PAIRS: [SlotWidth, SlotWidth][] = [
        ['1/2', '1/2'],
        ['2/3', '1/3'],
        ['1/3', '2/3'],
      ]

      const distances = TWO_SLOT_WIDTH_PAIRS.map(([w1]) => {
        const frac1 = parseFloat(w1.split('/')[0]) / parseFloat(w1.split('/')[1])
        const targetX1 = rect.width * frac1
        return Math.abs(x - targetX1)
      })

      const closestIndex = distances.indexOf(Math.min(...distances))
      const [w1, w2] = TWO_SLOT_WIDTH_PAIRS[closestIndex]

      const updatedRows = rows.map((r) => {
        if (r.id === row.id) {
          return {
            ...r,
            slots: [
              { ...r.slots[0], width: w1 },
              { ...r.slots[1], width: w2 },
            ],
          }
        }
        return r
      })
      onChange(updatedRows)
    }

    const onPointerUp = () => setIsResizing(false)

    document.addEventListener('pointermove', onPointerMove)
    document.addEventListener('pointerup', onPointerUp)
    return () => {
      document.removeEventListener('pointermove', onPointerMove)
      document.removeEventListener('pointerup', onPointerUp)
    }
  }, [isResizing, row, rows, onChange])

  return <div ref={handleRef} className="layout-resize-handle" onPointerDown={() => setIsResizing(true)} />
}
