import { apiClient } from './client'
import type { AccountUpdateRequest, AuthResponse } from '../types/auth'

export const accountApi = {
  update: (body: AccountUpdateRequest) => apiClient.patch<AuthResponse>('/api/account', body),
}
