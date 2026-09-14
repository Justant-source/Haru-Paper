import { Link } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { IconChevron } from '../components/icons'
import { useI18n } from '../i18n'

export function MorePage() {
  const { t } = useI18n()
  return (
    <div className="page page-with-header">
      <PageHeader title={t('tabMore')} />
      <ul className="more-list">
        <li>
          <Link to="/history">
            {t('history')}
            <IconChevron />
          </Link>
        </li>
        <li>
          <Link to="/device">
            {t('device')}
            <IconChevron />
          </Link>
        </li>
        <li>
          <Link to="/settings">
            {t('settings')}
            <IconChevron />
          </Link>
        </li>
      </ul>
    </div>
  )
}
