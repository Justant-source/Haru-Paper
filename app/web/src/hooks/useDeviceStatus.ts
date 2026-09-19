import { useQuery } from '@tanstack/react-query'
import { deviceApi } from '../api/device'

/**
 * 홈·예약·지금 인쇄 화면이 공용으로 쓰는 기기 상태 조회. queryKey를 DevicePage와
 * 똑같이 ['device']로 두어 캐시를 공유한다 — 용지 토글을 어느 화면에서 켜도
 * 다른 화면의 배너가 같은 요청 없이 즉시 갱신된다.
 *
 * DevicePage 자체는 그 화면에 있는 동안 더 자주(30초) 갱신해야 하므로 별도로
 * refetchInterval을 30000으로 오버라이드해서 쓴다 — 이 훅의 기본값은 목록류
 * 화면에서 배너만 띄우는 용도라 더 느슨하게 잡는다.
 */
export function useDeviceStatus() {
  return useQuery({
    queryKey: ['device'],
    queryFn: () => deviceApi.get(),
    staleTime: 15_000,
    refetchInterval: 60_000,
  })
}
