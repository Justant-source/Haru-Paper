import type { FormatDocument, WidgetInstance } from '../types/format'
import { missingRequiredFields, newWidgetInstance, type WidgetCatalog, type WidgetDescriptor } from '../types/widget'

/** 위젯 목록을 바꾸는 조작(추가·순서·크기·값·삭제)을 한데 모은다. FormatEditPage에서 분리해 길이를 줄인다. */
export function useWidgetActions({
  document,
  catalog,
  change,
  settingsWidgetId,
  setSettingsWidgetId,
  closeAddSheet,
}: {
  document: FormatDocument | null
  catalog: WidgetCatalog | undefined
  change: (next: FormatDocument) => void
  settingsWidgetId: string | null
  setSettingsWidgetId: (id: string | null) => void
  closeAddSheet: () => void
}) {
  const updateWidgets = (widgets: WidgetInstance[]) => {
    if (!document) return
    change({ ...document, widgets })
  }

  const handleAddWidget = (descriptor: WidgetDescriptor) => {
    if (!document || !catalog || document.widgets.length >= catalog.grid.maxWidgets) return
    const instance = newWidgetInstance(descriptor)
    updateWidgets([...document.widgets, instance])
    closeAddSheet()
    if (missingRequiredFields(descriptor, instance).length > 0) {
      setSettingsWidgetId(instance.id)
    }
  }

  const handleMove = (widgetId: string, dir: -1 | 1) => {
    if (!document) return
    const idx = document.widgets.findIndex((w) => w.id === widgetId)
    const swapIdx = idx + dir
    if (idx < 0 || swapIdx < 0 || swapIdx >= document.widgets.length) return
    const next = [...document.widgets]
    ;[next[idx], next[swapIdx]] = [next[swapIdx], next[idx]]
    updateWidgets(next)
  }

  const handleDelete = (widgetId: string) => {
    if (!document) return
    updateWidgets(document.widgets.filter((w) => w.id !== widgetId))
    if (settingsWidgetId === widgetId) setSettingsWidgetId(null)
  }

  const handleChangeSize = (widgetId: string, size: string) => {
    if (!document) return
    updateWidgets(document.widgets.map((w) => (w.id === widgetId ? { ...w, size } : w)))
  }

  const handleChangeProp = (widgetId: string, key: string, value: unknown) => {
    if (!document) return
    updateWidgets(
      document.widgets.map((w) => (w.id === widgetId ? { ...w, props: { ...w.props, [key]: value } } : w)),
    )
  }

  return { handleAddWidget, handleMove, handleDelete, handleChangeSize, handleChangeProp }
}
