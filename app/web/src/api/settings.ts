import { apiClient } from './client'
import type { SettingsResponse } from '../types/settings'

export const settingsApi = {
  get: () => apiClient.get<SettingsResponse>('/api/settings'),
  update: (body: SettingsResponse) => apiClient.put<SettingsResponse>('/api/settings', body),
}

export const healthApi = {
  check: () => apiClient.get<{ status: string; time: string }>('/api/health'),
}
