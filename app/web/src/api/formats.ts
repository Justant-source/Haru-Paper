import { apiClient, fetchBlobUrl, postForBlobUrl } from './client'
import type { FormatDetail, FormatDocument, FormatSummary } from '../types/format'

export const formatsApi = {
  list: () => apiClient.get<FormatSummary[]>('/api/formats'),
  get: (id: string) => apiClient.get<FormatDetail>(`/api/formats/${id}`),
  create: (document: FormatDocument) => apiClient.post<FormatDetail>('/api/formats', document),
  update: (id: string, document: FormatDocument) =>
    apiClient.put<FormatDetail>(`/api/formats/${id}`, document),
  remove: (id: string) => apiClient.delete<void>(`/api/formats/${id}`),
  import: (exported: unknown) => apiClient.post<FormatDetail>('/api/formats/import', exported),
  exportUrl: (id: string) => `/api/formats/${id}/export`,
  previewUrl: (id: string, date?: string) =>
    `/api/formats/${id}/preview.png${date ? `?date=${date}` : ''}`,
  previewBlob: (id: string, date?: string) => fetchBlobUrl(formatsApi.previewUrl(id, date)),
  previewEphemeralBlob: (document: FormatDocument, date?: string) =>
    postForBlobUrl(`/api/formats/preview${date ? `?date=${date}` : ''}`, document),
}
