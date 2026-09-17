import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { widgetsApi } from '../../api/widgets'
import type { KoreaLocation, KoreaLocationValue } from '../../types/widget'
import { BottomSheet } from '../../components/BottomSheet'
import { IconSearch } from '../../components/icons'

/** 공백 무시 + 대소문자 무시 부분 일치(작업지시서 07 7.1-6). */
function normalize(s: string): string {
  return s.replace(/\s+/g, '').toLowerCase()
}

export function LocationPickerSheet({
  open,
  onClose,
  onSelect,
}: {
  open: boolean
  onClose: () => void
  onSelect: (value: KoreaLocationValue) => void
}) {
  const [query, setQuery] = useState('')

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['korea-locations'],
    queryFn: () => widgetsApi.locations(),
    staleTime: Infinity,
  })

  const q = normalize(query)
  const filtered = q
    ? (data ?? []).filter(
        (l) => normalize(l.label).includes(q) || normalize(l.name).includes(q) || normalize(l.sido).includes(q),
      )
    : null

  const grouped = useMemo(() => {
    if (!data) return [] as [string, KoreaLocation[]][]
    const map = new Map<string, KoreaLocation[]>()
    for (const loc of data) {
      const arr = map.get(loc.sido) ?? []
      arr.push(loc)
      map.set(loc.sido, arr)
    }
    return Array.from(map.entries())
  }, [data])

  const select = (loc: KoreaLocation) => onSelect({ label: loc.label, lat: loc.lat, lon: loc.lon })

  return (
    <BottomSheet open={open} title="위치 선택" onClose={onClose}>
      <div className="location-search-row">
        <IconSearch className="location-search-icon" />
        <input
          type="search"
          className="location-search"
          placeholder="시·도, 시·군·구 검색"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="위치 검색"
        />
      </div>

      {isLoading ? <p className="location-status">불러오는 중...</p> : null}

      {error ? (
        <div className="banner banner-danger">
          <span>위치 목록을 불러오지 못했습니다</span>
          <button type="button" className="banner-retry" onClick={() => refetch()}>
            다시 시도
          </button>
        </div>
      ) : null}

      {filtered ? (
        <ul className="location-list">
          {filtered.length === 0 ? <li className="location-empty">검색 결과가 없습니다</li> : null}
          {filtered.map((l) => (
            <li key={`${l.sido}-${l.name}`}>
              <button type="button" className="location-item" onClick={() => select(l)}>
                {l.label}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <div className="location-groups">
          {grouped.map(([sido, locs]) => (
            <details key={sido} className="location-group">
              <summary>{sido}</summary>
              <ul className="location-list">
                {locs.map((l) => (
                  <li key={`${l.sido}-${l.name}`}>
                    <button type="button" className="location-item" onClick={() => select(l)}>
                      {/* 시·도 자체 항목은 name이 빈 문자열이다(GET /api/widgets/locations) */}
                      {l.name || `${l.sido} 전체`}
                    </button>
                  </li>
                ))}
              </ul>
            </details>
          ))}
        </div>
      )}
    </BottomSheet>
  )
}
