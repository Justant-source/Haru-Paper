import { apiClient } from './client'
import type { KoreaLocation, WidgetCatalog } from '../types/widget'

export const widgetsApi = {
  /** 위젯 카탈로그(종류·크기·설정 스키마). 거의 안 바뀌므로 react-query에서 staleTime을 길게 잡는다. */
  catalog: () => apiClient.get<WidgetCatalog>('/api/widgets'),
  /** 대한민국 시·군·구 목록(약 250개). 통째로 받아서 앱에서 거른다. */
  locations: () => apiClient.get<KoreaLocation[]>('/api/widgets/locations'),
}
