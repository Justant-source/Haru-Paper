import { NavLink, Outlet } from 'react-router-dom'

// 하단 탭 4개: 포맷 · 예약 · 지금 인쇄 · 더보기(이력/기기/설정) — docs/app/screens.md 공통절
const TABS = [
  { to: '/', label: '포맷', end: true },
  { to: '/schedules', label: '예약' },
  { to: '/print-now', label: '지금 인쇄' },
  { to: '/more', label: '더보기' },
]

export function Layout() {
  return (
    <div className="app-shell">
      <main className="app-content">
        <Outlet />
      </main>
      <nav className="bottom-tabs">
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) => `bottom-tab${isActive ? ' active' : ''}`}
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
