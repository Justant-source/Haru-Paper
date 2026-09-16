import { apiClient } from './client'
import type { SignupRequest, LoginRequest, AuthResponse } from '../types/auth'

export const authApi = {
  signup: (body: SignupRequest) => apiClient.post<AuthResponse>('/api/auth/signup', body),
  login: (body: LoginRequest) => apiClient.post<AuthResponse>('/api/auth/login', body),
  logout: () => apiClient.post<void>('/api/auth/logout'),
  me: () => apiClient.get<AuthResponse>('/api/auth/me'),
}
