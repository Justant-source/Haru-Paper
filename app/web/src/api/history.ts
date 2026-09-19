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
  // 그날 실제로 나간 렌더(지금 다시 렌더하는 preview.png와 다르다). renderAvailable이
  // true일 때만 걸어야 한다 — false면 아직은 부르지 않고, 그래도 <img onError>로 방어한다.
  renderUrl: (resultId: string) => `/api/history/${resultId}/render.png`,
}
