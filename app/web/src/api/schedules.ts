import { apiClient } from './client'
import type { Schedule, ScheduleCreateRequest } from '../types/schedule'

export const schedulesApi = {
  list: () => apiClient.get<Schedule[]>('/api/schedules'),
  create: (body: ScheduleCreateRequest) => apiClient.post<Schedule>('/api/schedules', body),
  update: (id: string, body: ScheduleCreateRequest) =>
    apiClient.put<Schedule>(`/api/schedules/${id}`, body),
  remove: (id: string) => apiClient.delete<void>(`/api/schedules/${id}`),
}
