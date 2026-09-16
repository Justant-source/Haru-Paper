import { useEffect } from 'react'
import { Route, Routes, Navigate } from 'react-router-dom'
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

function ProtectedRoutes() {
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

  return (
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
  )
}

export default function App() {
  return (
    <>
      <ThemeSync />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="*" element={<ProtectedRoutes />} />
      </Routes>
    </>
  )
}
