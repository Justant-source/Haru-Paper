import { apiClient } from './client'
import type { DeviceResponse } from '../types/device'

export const deviceApi = {
  get: () => apiClient.get<DeviceResponse>('/api/device'),
  setPaperState: (loaded: boolean) =>
    apiClient.put<{ loaded: boolean; updatedAt: string; updatedBy: string }>('/api/device/paper-state', {
      loaded,
    }),
}
