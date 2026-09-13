import { apiClient } from './client'

export interface AssetUploadResponse {
  assetId: string
  contentType: string
  widthPx: number
  heightPx: number
  sizeBytes: number
}

export const assetsApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return apiClient.postForm<AssetUploadResponse>('/api/assets', form)
  },
  thumbnailUrl: (assetId: string) => `/api/assets/${assetId}`,
}
