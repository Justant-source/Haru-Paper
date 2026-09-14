import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useI18n } from '../i18n'
import { IconFormats, IconMore, IconPrint, IconSchedules } from './icons'

function hideBottomTabs(pathname: string) {
  return pathname === '/formats/new' || /^\/formats\/[^/]+\/edit$/.test(pathname)
}

export function Layout() {
  const { pathname } = useLocation()
  const { t } = useI18n()
  const tabsHidden = hideBottomTabs(pathname)
  const tabs = [
    { to: '/', label: t('tabFormat'), Icon: IconFormats },
    { to: '/schedules', label: t('tabSchedules'), Icon: IconSchedules },
    { to: '/print-now', label: t('tabPrintNow'), Icon: IconPrint },
    { to: '/more', label: t('tabMore'), Icon: IconMore },
  ] as const

  return (
    <div className={`app-shell${tabsHidden ? ' app-shell-no-tabs' : ''}`}>
      <main className="app-content">
        <Outlet />
      </main>
      {tabsHidden ? null : (
        <nav className="bottom-tabs" aria-label={t('navMain')}>
          {tabs.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              end={tab.to === '/'}
              className={({ isActive }) => `bottom-tab${isActive ? ' active' : ''}`}
            >
              {({ isActive }) => (
                <>
                  <tab.Icon active={isActive} />
                  <span>{tab.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
      )}
    </div>
  )
}
