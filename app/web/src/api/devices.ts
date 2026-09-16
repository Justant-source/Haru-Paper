import { apiClient } from './client'
import type { DeviceTokenResponse, PairingCodeResponse, MyDeviceResponse } from '../types/devices'

export const devicesApi = {
  getToken: () => apiClient.post<DeviceTokenResponse>('/api/devices/me/token'),
  getPairingCodes: () => apiClient.post<PairingCodeResponse>('/api/devices/pairing-codes'),
  getMe: () => apiClient.get<MyDeviceResponse>('/api/devices/me'),
}
