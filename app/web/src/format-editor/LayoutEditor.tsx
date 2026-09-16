import { DndContext, DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/core'
import { useState } from 'react'
import type { Row } from '../types/format'
import { LayoutCanvas } from './LayoutCanvas'
import { WidgetPalette } from './WidgetPalette'

interface LayoutEditorProps {
  rows: Row[]
  onChange: (rows: Row[]) => void
  onSelectSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
}

/**
 * Wraps LayoutCanvas and WidgetPalette in a DndContext so that widgets can be dragged from
 * the palette to the canvas.
 */
export function LayoutEditor({
  rows,
  onChange,
  onSelectSlot,
  printerWidthPx,
}: LayoutEditorProps) {
  const [draggedId, setDraggedId] = useState<string | null>(null)

  const handleDragStart = (event: DragStartEvent) => {
    setDraggedId(event.active.id as string)
  }

  const handleDragEnd = () => {
    setDraggedId(null)
  }

  const handleDragOver = (event: DragOverEvent) => {
    // Handling is done by child components
  }

  return (
    <DndContext onDragStart={handleDragStart} onDragEnd={handleDragEnd} onDragOver={handleDragOver}>
      <div className="layout-editor">
        <LayoutCanvas
          rows={rows}
          onChange={onChange}
          onSelectSlot={onSelectSlot}
          printerWidthPx={printerWidthPx}
        />
        <WidgetPalette onAddWidget={(rowIndex, row) => {
          const newRows = [...rows]
          if (rowIndex >= 0 && rowIndex < newRows.length) {
            newRows.splice(rowIndex, 0, row)
          } else {
            newRows.push(row)
          }
          onChange(newRows)
        }} />
      </div>
    </DndContext>
  )
}
