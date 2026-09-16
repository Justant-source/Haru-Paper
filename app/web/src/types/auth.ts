export interface SignupRequest {
  email: string
  password: string
  handle: string
  displayName: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface AuthResponse {
  userId: string
  email: string
  handle: string
  displayName: string
  bio?: string
  role?: string
  status?: string
  mustChangePassword?: boolean
}

export interface AccountUpdateRequest {
  displayName?: string
  bio?: string
  currentPassword?: string
  newPassword?: string
}
