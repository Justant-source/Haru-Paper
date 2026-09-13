import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { FormatListPage } from './pages/FormatListPage'
import { FormatEditPage } from './pages/FormatEditPage'
import { ScheduleListPage } from './pages/ScheduleListPage'
import { PrintNowPage } from './pages/PrintNowPage'
import { HistoryPage } from './pages/HistoryPage'
import { DevicePage } from './pages/DevicePage'
import { SettingsPage } from './pages/SettingsPage'
import { MorePage } from './pages/MorePage'

export default function App() {
  return (
    <Routes>
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
      </Route>
    </Routes>
  )
}
