import { apiClient } from './client'

export interface PrintNowRequest {
  formatId: string
  paperConfirmed: boolean
}

export interface PrintNowResponse {
  commandId: string
  formatId: string
  renderId: string
  paperConfirmed: boolean
  status: 'pending'
  createdAt: string
}

export const printNowApi = {
  send: (body: PrintNowRequest) => apiClient.post<PrintNowResponse>('/api/print-now', body),
}
