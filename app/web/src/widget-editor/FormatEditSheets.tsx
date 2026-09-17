import type { WidgetInstance } from '../types/format'
import type { WidgetCatalog, WidgetDescriptor } from '../types/widget'
import { BottomSheet } from '../components/BottomSheet'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'
import { AddWidgetSheet } from './AddWidgetSheet'
import { WidgetSettingsSheet } from './WidgetSettingsSheet'

/** 편집 화면 하단에 뜨는 시트 3종(위젯 추가 · 위젯 설정 · 나가기 확인)을 한데 묶는다. */
export function FormatEditSheets({
  catalog,
  atMax,
  addOpen,
  onCloseAdd,
  onAddWidget,
  settingsWidget,
  settingsDescriptor,
  settingsFieldErrors,
  onCloseSettings,
  onChangeSize,
  onChangeProp,
  onDeleteWidget,
  leaveOpen,
  onCloseLeave,
  onConfirmLeave,
}: {
  catalog: WidgetCatalog
  atMax: boolean
  addOpen: boolean
  onCloseAdd: () => void
  onAddWidget: (descriptor: WidgetDescriptor) => void
  settingsWidget: WidgetInstance | null
  settingsDescriptor: WidgetDescriptor | undefined
  settingsFieldErrors: Record<string, string> | undefined
  onCloseSettings: () => void
  onChangeSize: (size: string) => void
  onChangeProp: (key: string, value: unknown) => void
  onDeleteWidget: () => void
  leaveOpen: boolean
  onCloseLeave: () => void
  onConfirmLeave: () => void
}) {
  const { t } = useI18n()

  return (
    <>
      <AddWidgetSheet open={addOpen} onClose={onCloseAdd} catalog={catalog} atMax={atMax} onAdd={onAddWidget} />

      <WidgetSettingsSheet
        open={!!settingsWidget}
        widget={settingsWidget}
        descriptor={settingsDescriptor}
        fieldErrors={settingsFieldErrors}
        onClose={onCloseSettings}
        onChangeSize={onChangeSize}
        onChangeProp={onChangeProp}
        onDelete={onDeleteWidget}
      />

      <BottomSheet open={leaveOpen} title={t('leaveTitle')} onClose={onCloseLeave}>
        <p>{t('leaveBody')}</p>
        <div className="stack">
          <Button variant="secondary" onClick={onCloseLeave}>
            {t('stay')}
          </Button>
          <Button variant="danger" onClick={onConfirmLeave}>
            {t('leave')}
          </Button>
        </div>
      </BottomSheet>
    </>
  )
}
