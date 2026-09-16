export interface DeviceTokenResponse {
  deviceId: string
  token: string
  issuedAt: string
}

export interface PairingCodeResponse {
  code: string
  expiresAt: string
}

export interface MyDeviceResponse {
  id: string
  name?: string
  lastPollAt?: string
}
