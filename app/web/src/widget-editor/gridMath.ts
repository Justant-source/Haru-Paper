// 배치도(그리드) 치수 계산 — 서버 GridSpec.java와 같은 비율을 써서 앱 배치도가 실제 인쇄 레이아웃과
// 일치하게 한다(.temp/07-위젯그리드-작업지시서.md 2절·7.1-3).
//
// GET /api/widgets가 주는 GridInfo는 columns·rowUnitMm·gapMm·maxWidgets만 준다 — 열 폭(mm)은 주지
// 않는다. 열 폭은 이 PoC의 고정 하드웨어 범위(CLAUDE.md: 용지 110mm 연속 롤 고정)에서 유도된 상수다:
//   콘텐츠 폭 104mm = 110mm 용지 − 좌우 여백 3mm×2 (작업지시서 07 2절)
//   4열, 칸 간격 2mm×3 → 열 폭 24.5mm, 행 높이 12mm
// 서버가 그리드 규격이나 기본 여백을 바꾸면 이 상수도 같이 바꿔야 한다.
import { useLayoutEffect, useState, type RefObject } from 'react'
import type { GridInfo } from '../types/widget'

const CONTENT_WIDTH_MM = 104

export interface GridMetrics {
  gapPx: number
  colWidthPx: number
  rowHeightPx: number
}

const EMPTY_METRICS: GridMetrics = { gapPx: 0, colWidthPx: 0, rowHeightPx: 0 }

/** 컨테이너 실측 폭(ResizeObserver)에 맞춰 칸 간격·행 높이를 px로 계산한다. */
export function useGridMetrics(containerRef: RefObject<HTMLElement | null>, grid: GridInfo): GridMetrics {
  const [metrics, setMetrics] = useState<GridMetrics>(EMPTY_METRICS)
  const { columns, gapMm, rowUnitMm } = grid

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return

    const compute = () => {
      const width = el.clientWidth
      if (width <= 0) return
      const colWidthMm = (CONTENT_WIDTH_MM - gapMm * (columns - 1)) / columns
      const gapPx = width * (gapMm / CONTENT_WIDTH_MM)
      const colWidthPx = (width - gapPx * (columns - 1)) / columns
      const rowHeightPx = colWidthPx * (rowUnitMm / colWidthMm)
      setMetrics({ gapPx, colWidthPx, rowHeightPx })
    }

    compute()
    const ro = new ResizeObserver(compute)
    ro.observe(el)
    return () => ro.disconnect()
  }, [containerRef, columns, gapMm, rowUnitMm])

  return metrics
}
