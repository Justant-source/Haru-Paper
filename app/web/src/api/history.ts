import { apiClient } from './client'
import type { HistoryEntry } from '../types/history'

export const historyApi = {
  list: (params?: { limit?: number; before?: string }) => {
    const search = new URLSearchParams()
    if (params?.limit) search.set('limit', String(params.limit))
    if (params?.before) search.set('before', params.before)
    const qs = search.toString()
    return apiClient.get<HistoryEntry[]>(`/api/history${qs ? `?${qs}` : ''}`)
  },
}
