import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { useEffect, useRef, useState } from 'react'
import type { Row, SlotWidth } from '../types/format'
import { useI18n } from '../i18n'

// SlotContent is created by agent 3, import with these props
import { SlotContent } from './SlotContent'

interface LayoutCanvasProps {
  rows: Row[]
  onChange: (rows: Row[]) => void
  onSelectSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
}

export function LayoutCanvas({
  rows,
  onChange,
  onSelectSlot,
  printerWidthPx,
}: LayoutCanvasProps) {
  const [deletedState, setDeletedState] = useState<Row[] | null>(null)
  const [undoTimeoutId, setUndoTimeoutId] = useState<number | null>(null)
  const { t } = useI18n()

  // Cleanup undo timeout on unmount
  useEffect(() => {
    return () => {
      if (undoTimeoutId !== null) {
        clearTimeout(undoTimeoutId)
      }
    }
  }, [undoTimeoutId])

  if (rows.length === 0) {
    return (
      <div className="layout-canvas">
        <div className="empty-state">{t('layoutCanvasEmpty')}</div>
      </div>
    )
  }

  const handleDeleteSlot = (rowId: string, slotId: string) => {
    // Save current state for undo
    setDeletedState(rows)

    // Clear existing undo timeout
    if (undoTimeoutId !== null) {
      clearTimeout(undoTimeoutId)
    }

    // Remove the slot
    const updatedRows = rows
      .map((row) => {
        if (row.id === rowId) {
          return {
            ...row,
            slots: row.slots.filter((s) => s.id !== slotId),
          }
        }
        return row
      })
      .filter((row) => row.slots.length > 0) // Remove empty rows

    onChange(updatedRows)

    // Set undo timeout (5 seconds)
    const timeoutId = window.setTimeout(() => {
      setDeletedState(null)
      setUndoTimeoutId(null)
    }, 5000)
    setUndoTimeoutId(timeoutId)
  }

  const handleUndo = () => {
    if (deletedState !== null && undoTimeoutId !== null) {
      clearTimeout(undoTimeoutId)
      onChange(deletedState)
      setDeletedState(null)
      setUndoTimeoutId(null)
    }
  }

  return (
    <div className="layout-canvas-wrapper">
      <SortableContext items={rows.map((r) => r.id)} strategy={verticalListSortingStrategy}>
        <div className="layout-canvas">
          {rows.map((row) => (
            <RowComponent
              key={row.id}
              row={row}
              onSelectSlot={onSelectSlot}
              onDeleteSlot={handleDeleteSlot}
              onChange={onChange}
              rows={rows}
              printerWidthPx={printerWidthPx}
            />
          ))}
        </div>
      </SortableContext>

      <TrashZone />

      {deletedState !== null && undoTimeoutId !== null && (
        <UndoToast onUndo={handleUndo} onDismiss={() => setDeletedState(null)} />
      )}
    </div>
  )
}

interface RowComponentProps {
  row: Row
  onSelectSlot: (rowId: string, slotId: string) => void
  onDeleteSlot: (rowId: string, slotId: string) => void
  onChange: (rows: Row[]) => void
  rows: Row[]
  printerWidthPx: number
}

function RowComponent({
  row,
  onSelectSlot,
  onDeleteSlot,
  onChange,
  rows,
  printerWidthPx,
}: RowComponentProps) {
  const { setNodeRef, transform, transition, isDragging } = useSortable({
    id: row.id,
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  }

  const is2Slot = row.slots.length === 2

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`layout-row ${isDragging ? 'layout-row-dragging' : ''}`}
    >
      {row.slots.map((slot) => {
        const slotWidthFraction = parseFloat(slot.width.split('/')[0]) / parseFloat(slot.width.split('/')[1])
        const flexBasisPercent = (slotWidthFraction * 100).toFixed(1)

        return (
          <SlotCard
            key={slot.id}
            slot={slot}
            rowId={row.id}
            onSelectSlot={onSelectSlot}
            onDeleteSlot={onDeleteSlot}
            printerWidthPx={printerWidthPx}
            slotWidthFraction={slotWidthFraction}
            flexBasisPercent={flexBasisPercent}
            rowSlotsCount={row.slots.length}
          />
        )
      })}

      {is2Slot && (
        <ResizeHandle
          row={row}
          onChange={onChange}
          rows={rows}
        />
      )}
    </div>
  )
}

interface SlotCardProps {
  slot: any
  rowId: string
  onSelectSlot: (rowId: string, slotId: string) => void
  onDeleteSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
  slotWidthFraction: number
  flexBasisPercent: string
  rowSlotsCount: number
}

function SlotCard({
  slot,
  rowId,
  onSelectSlot,
  onDeleteSlot,
  printerWidthPx,
  slotWidthFraction,
  flexBasisPercent,
  rowSlotsCount,
}: SlotCardProps) {
  const { setNodeRef, transform, transition, isDragging, listeners, attributes } = useSortable({
    id: slot.id,
  })

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
        <SlotContent
          block={slot.block}
          printerWidthPx={printerWidthPx}
          slotWidthFraction={slotWidthFraction}
        />
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
      const centerX = rect.width / 2
      const offset = x - centerX

      // Find the closest width pair
      const TWO_SLOT_WIDTH_PAIRS: [SlotWidth, SlotWidth][] = [
        ['1/2', '1/2'],
        ['2/3', '1/3'],
        ['1/3', '2/3'],
      ]

      const distances = TWO_SLOT_WIDTH_PAIRS.map(([w1, w2]) => {
        const frac1 = parseFloat(w1.split('/')[0]) / parseFloat(w1.split('/')[1])
        const frac2 = parseFloat(w2.split('/')[0]) / parseFloat(w2.split('/')[1])
        const targetX1 = rect.width * frac1
        return Math.abs(x - targetX1)
      })

      const closestIndex = distances.indexOf(Math.min(...distances))
      const [w1, w2] = TWO_SLOT_WIDTH_PAIRS[closestIndex]

      // Update rows
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

    const onPointerUp = () => {
      setIsResizing(false)
    }

    document.addEventListener('pointermove', onPointerMove)
    document.addEventListener('pointerup', onPointerUp)
    return () => {
      document.removeEventListener('pointermove', onPointerMove)
      document.removeEventListener('pointerup', onPointerUp)
    }
  }, [isResizing, row, rows, onChange])

  return (
    <div
      ref={handleRef}
      className="layout-resize-handle"
      onPointerDown={() => setIsResizing(true)}
    />
  )
}

interface TrashZoneProps {}

function TrashZone({}: TrashZoneProps) {
  const { t } = useI18n()
  return (
    <div className="layout-trash-zone">
      <div>{t('trashZoneLabel')}</div>
    </div>
  )
}

interface UndoToastProps {
  onUndo: () => void
  onDismiss: () => void
}

function UndoToast({ onUndo, onDismiss }: UndoToastProps) {
  const { t } = useI18n()

  useEffect(() => {
    const timer = setTimeout(onDismiss, 5000)
    return () => clearTimeout(timer)
  }, [onDismiss])

  return (
    <div className="layout-undo-toast">
      <span>{t('deletedToast')}</span>
      <button onClick={onUndo}>{t('undoDelete')}</button>
    </div>
  )
}
