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
  deviceId: string | null
  name: string | null
  paired: boolean
}
