import { DndContext } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { arrayMove } from '@dnd-kit/sortable'
import { useEffect, useState } from 'react'
import type { BlockType, Row } from '../types/format'
import { newBlock, newRow } from '../types/format'
import { useI18n } from '../i18n'
import { LayoutCanvas } from './LayoutCanvas'
import { WidgetPalette } from './WidgetPalette'

interface LayoutEditorProps {
  rows: Row[]
  onChange: (rows: Row[]) => void
  onSelectSlot: (rowId: string, slotId: string) => void
  printerWidthPx: number
}

function findSlot(rows: Row[], slotId: string): { rowId: string; rowIndex: number; slotIndex: number } | null {
  for (let rowIndex = 0; rowIndex < rows.length; rowIndex++) {
    const slotIndex = rows[rowIndex].slots.findIndex((s) => s.id === slotId)
    if (slotIndex >= 0) return { rowId: rows[rowIndex].id, rowIndex, slotIndex }
  }
  return null
}

/**
 * DndContext를 하나만 열어 LayoutCanvas(캔버스)와 WidgetPalette(팔레트)를 감싼다 —
 * 팔레트→캔버스 드롭이 되려면 같은 컨텍스트여야 한다. 실제 드래그 결과를 rows
 * 배열에 반영하는 reducer(onDragEnd)가 이 컴포넌트의 핵심이다.
 * .temp/04-드래그편집기-작업지시서.md 4절 "에이전트 2" 참고.
 */
export function LayoutEditor({ rows, onChange, onSelectSlot, printerWidthPx }: LayoutEditorProps) {
  const { t } = useI18n()
  const [pendingDelete, setPendingDelete] = useState<Row[] | null>(null)
  const [undoTimeoutId, setUndoTimeoutId] = useState<number | null>(null)

  useEffect(() => {
    return () => {
      if (undoTimeoutId !== null) clearTimeout(undoTimeoutId)
    }
  }, [undoTimeoutId])

  const scheduleUndoExpiry = () => {
    if (undoTimeoutId !== null) clearTimeout(undoTimeoutId)
    const id = window.setTimeout(() => {
      setPendingDelete(null)
      setUndoTimeoutId(null)
    }, 5000)
    setUndoTimeoutId(id)
  }

  const handleUndo = () => {
    if (pendingDelete === null) return
    if (undoTimeoutId !== null) clearTimeout(undoTimeoutId)
    onChange(pendingDelete)
    setPendingDelete(null)
    setUndoTimeoutId(null)
  }

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over) return

    const activeData = active.data.current as { type?: string; blockType?: BlockType } | undefined
    const activeId = String(active.id)
    const overId = String(over.id)

    // 1) 팔레트 카드를 캔버스에 드롭 → 새 행 추가
    if (activeData?.type === 'widget' && activeData.blockType) {
      const created = newRow(newBlock(activeData.blockType))
      const overRowIndex = rows.findIndex((r) => r.id === overId || r.slots.some((s) => s.id === overId))
      if (overRowIndex >= 0) {
        const next = [...rows]
        next.splice(overRowIndex + 1, 0, created)
        onChange(next)
      } else {
        onChange([...rows, created])
      }
      return
    }

    // 2) 휴지통에 드롭 → 삭제 + 되돌리기 유예
    if (overId === 'trash-zone') {
      const location = findSlot(rows, activeId)
      if (!location) return
      setPendingDelete(rows)
      const next = rows
        .map((r) => (r.id === location.rowId ? { ...r, slots: r.slots.filter((s) => s.id !== activeId) } : r))
        .filter((r) => r.slots.length > 0)
      onChange(next)
      scheduleUndoExpiry()
      return
    }

    if (activeId === overId) return

    // 3) 행 자체를 드래그(순서 변경)
    const activeRowIndex = rows.findIndex((r) => r.id === activeId)
    if (activeRowIndex >= 0) {
      const overRowIndex = rows.findIndex((r) => r.id === overId)
      if (overRowIndex >= 0 && overRowIndex !== activeRowIndex) {
        onChange(arrayMove(rows, activeRowIndex, overRowIndex))
      }
      return
    }

    // 4) 슬롯을 드래그(같은 행 안 재배치, 또는 다른 행으로 이동)
    const source = findSlot(rows, activeId)
    if (!source) return
    const targetRowIndex = rows.findIndex((r) => r.id === overId || r.slots.some((s) => s.id === overId))
    if (targetRowIndex < 0) return
    const targetRow = rows[targetRowIndex]
    const targetSlotIndex = targetRow.slots.findIndex((s) => s.id === overId)

    if (targetRow.id === source.rowId) {
      if (targetSlotIndex < 0 || targetSlotIndex === source.slotIndex) return
      const newSlots = arrayMove(targetRow.slots, source.slotIndex, targetSlotIndex)
      onChange(rows.map((r, i) => (i === targetRowIndex ? { ...r, slots: newSlots } : r)))
      return
    }

    // 다른 행으로 이동 — 대상 행이 이미 2슬롯이면 거부(드롭 불가)
    if (targetRow.slots.length >= 2) return

    const movedSlot = rows[source.rowIndex].slots[source.slotIndex]
    let next = rows.map((r, i) =>
      i === source.rowIndex ? { ...r, slots: r.slots.filter((s) => s.id !== activeId) } : r,
    )
    const insertIndex = targetSlotIndex >= 0 ? targetSlotIndex : targetRow.slots.length
    next = next.map((r) => {
      if (r.id !== targetRow.id) return r
      const newSlots = [...r.slots]
      newSlots.splice(insertIndex, 0, { ...movedSlot, width: '1/1' })
      if (newSlots.length === 2) {
        newSlots[0] = { ...newSlots[0], width: '1/2' }
        newSlots[1] = { ...newSlots[1], width: '1/2' }
      }
      return { ...r, slots: newSlots }
    })
    onChange(next.filter((r) => r.slots.length > 0))
  }

  return (
    <DndContext onDragEnd={handleDragEnd}>
      <div className="layout-editor">
        <LayoutCanvas rows={rows} onChange={onChange} onSelectSlot={onSelectSlot} printerWidthPx={printerWidthPx} />
        <WidgetPalette />
        {pendingDelete !== null && (
          <div className="layout-undo-toast">
            <span>{t('deletedToast')}</span>
            <button type="button" onClick={handleUndo}>
              {t('undoDelete')}
            </button>
          </div>
        )}
      </div>
    </DndContext>
  )
}
