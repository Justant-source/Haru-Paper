import { apiClient } from './client'

// 날씨 기본 위치 설정(GET/PUT /api/settings)은 더 이상 앱에서 쓰지 않는다 — 위치는
// 포맷의 날씨 위젯 설정(props.location)에 들어간다(.temp/07-위젯그리드-작업지시서.md D6).
// 서버 API 자체는 그대로 둔다(docs/server/api.md).

export const healthApi = {
  check: () => apiClient.get<{ status: string; time: string }>('/api/health'),
}
