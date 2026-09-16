import { useEffect } from 'react'
import { Route, Routes, Navigate, Outlet } from 'react-router-dom'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'
import { FormatListPage } from './pages/FormatListPage'
import { FormatEditPage } from './pages/FormatEditPage'
import { ScheduleListPage } from './pages/ScheduleListPage'
import { PrintNowPage } from './pages/PrintNowPage'
import { HistoryPage } from './pages/HistoryPage'
import { DevicePage } from './pages/DevicePage'
import { SettingsPage } from './pages/SettingsPage'
import { MorePage } from './pages/MorePage'
import { AccountPage } from './pages/AccountPage'
import { applyTheme } from './lib/theme'
import { useAuthGuard } from './hooks/useAuthGuard'

function ThemeSync() {
  useEffect(() => {
    applyTheme()
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme()
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return null
}

/**
 * <Route>는 <Routes> 바로 아래 자식으로만 쓸 수 있다 — 함수 컴포넌트가 <Route>를
 * 반환해서 element={<X/>}로 끼워 넣으면 React Router가 렌더 시점에 예외를 던지고
 * 화면이 통째로 빈 채로 남는다(2026-09-16 실사용 중 발견). 인증 게이트는 <Outlet/>을
 * 그리는 레이아웃 컴포넌트로 만들고, 실제 자식 라우트는 <Routes> 안에 직접 중첩한다.
 */
function RequireAuth() {
  const { status } = useAuthGuard()

  if (status === 'loading') {
    return (
      <div className="page" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <p>로드 중...</p>
      </div>
    )
  }

  if (status === 'unauthenticated') {
    return <Navigate to="/login" replace />
  }

  return <Outlet />
}

export default function App() {
  return (
    <>
      <ThemeSync />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route element={<RequireAuth />}>
          <Route element={<Layout />}>
            <Route index element={<FormatListPage />} />
            <Route path="formats/new" element={<FormatEditPage />} />
            <Route path="formats/:id/edit" element={<FormatEditPage />} />
            <Route path="schedules" element={<ScheduleListPage />} />
            <Route path="print-now" element={<PrintNowPage />} />
            <Route path="more" element={<MorePage />} />
            <Route path="history" element={<HistoryPage />} />
            <Route path="device" element={<DevicePage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="account" element={<AccountPage />} />
          </Route>
        </Route>
      </Routes>
    </>
  )
}
