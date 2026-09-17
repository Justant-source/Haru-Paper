import { Button } from '../components/Button'
import { IconBack } from '../components/icons'
import { useI18n } from '../i18n'

/** 뒤로 · 저장 상태 · 저장 버튼. */
export function FormatEditHeader({
  dirty,
  saving,
  saveDisabled,
  onBack,
  onSave,
}: {
  dirty: boolean
  saving: boolean
  saveDisabled: boolean
  onBack: () => void
  onSave: () => void
}) {
  const { t } = useI18n()
  return (
    <header className="page-header">
      <button type="button" className="icon-btn" onClick={onBack} aria-label="뒤로">
        <IconBack />
      </button>
      <span className="save-status">{dirty ? t('unsaved') : t('saved')}</span>
      <Button variant="primary" className="btn-pill" onClick={onSave} disabled={saveDisabled}>
        {saving ? t('saving') : t('save')}
      </Button>
    </header>
  )
}
