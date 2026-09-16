import React from 'react'
import type { Block } from '../types/format'
import { ptToPx } from '../lib/layout-math'

/**
 * 프린터 프로필 기본값 (PrinterProfile.DEFAULT와 일치)
 * m832, 300 dpi, 110mm, 1300px printableWidth
 */
const DEFAULT_PRINTER_WIDTH_PX = 1300
const DEFAULT_DPI = 300

/**
 * 슬롯 내용을 클라이언트 CSS로 근사 렌더링한다.
 * 순수 프레젠테이션 컴포넌트 — 드래그 로직, 상태 변경 없음.
 * docs/.temp/04-드래그편집기-작업지시서.md 3.3절 시그니처 그대로
 */
export function SlotContent({
  block,
  printerWidthPx = DEFAULT_PRINTER_WIDTH_PX,
  slotWidthFraction: _slotWidthFraction = 1,
}: {
  block: Block
  printerWidthPx?: number
  slotWidthFraction?: number
}): React.JSX.Element {
  switch (block.type) {
    case 'text':
      return renderText(block)
    case 'image':
      return renderImage(block, printerWidthPx)
    case 'dateHeader':
      return renderDateHeader(block)
    case 'weather':
      return renderWeather()
    default:
      return <div className="slot-content">Unknown block type</div>
  }
}

/**
 * text 블록 렌더링
 * props.text를 보여주고, 글자 크기는 block.style?.fontSizePt 또는 기본값 11pt
 */
function renderText(block: Block): React.JSX.Element {
  const text = (block.props?.text as string) || ''
  const fontSizePt = block.style?.fontSizePt ?? 11 // 기본값 11pt (docs/server/format-schema.md 3.2절)
  const fontSizePx = ptToPx(fontSizePt, DEFAULT_DPI)
  const align = block.style?.align ?? 'left'
  const bold = block.style?.bold ?? false

  return (
    <div
      className="slot-content"
      style={{
        fontSize: `${fontSizePx}px`,
        textAlign: align as React.CSSProperties['textAlign'],
        fontWeight: bold ? 'bold' : 'normal',
        whiteSpace: 'pre-wrap',
        wordWrap: 'break-word',
      }}
    >
      {text}
    </div>
  )
}

/**
 * image 블록 렌더링
 * props.assetId로 GET /api/assets/{assetId} 이미지를 표시
 * 같은 origin이라 쿠키 인증 자동 포함
 */
function renderImage(block: Block, _printerWidthPx: number): React.JSX.Element {
  const assetId = (block.props?.assetId as string) || ''
  const widthPercent = (block.props?.widthPercent as number) ?? 100
  const align = block.style?.align ?? 'left'

  // 컨테이너 정렬
  let containerAlign: React.CSSProperties['textAlign'] = 'left'
  if (align === 'center') containerAlign = 'center'
  else if (align === 'right') containerAlign = 'right'

  return (
    <div
      className="slot-content"
      style={{
        textAlign: containerAlign,
      }}
    >
      {assetId ? (
        <img
          src={`/api/assets/${assetId}`}
          style={{
            maxWidth: '100%',
            width: `${widthPercent}%`,
            height: 'auto',
          }}
          alt="Format asset"
        />
      ) : (
        <div style={{ color: '#999' }}>이미지를 선택해주세요</div>
      )}
    </div>
  )
}

/**
 * dateHeader 블록 렌더링
 * pattern을 오늘 날짜로 치환
 * 토큰 규칙: docs/server/format-schema.md 4.3절 dateHeader
 * dddd(월요일), YYYY(2026), MM(09), DD(05), ddd(월), M(9), D(5)
 */
function renderDateHeader(block: Block): React.JSX.Element {
  const pattern = (block.props?.pattern as string) ?? 'YYYY년 M월 D일 dddd'
  const fontSizePt = block.style?.fontSizePt ?? 11
  const fontSizePx = ptToPx(fontSizePt, DEFAULT_DPI)
  const align = block.style?.align ?? 'center'
  const bold = block.style?.bold ?? false

  const formattedText = formatDateHeader(pattern)

  return (
    <div
      className="slot-content"
      style={{
        fontSize: `${fontSizePx}px`,
        textAlign: align as React.CSSProperties['textAlign'],
        fontWeight: bold ? 'bold' : 'normal',
      }}
    >
      {formattedText}
    </div>
  )
}

/**
 * weather 블록 렌더링
 * 실제 날씨 데이터가 없으므로 플레이스홀더 텍스트만 표시
 */
function renderWeather(): React.JSX.Element {
  return (
    <div className="slot-content" style={{ color: '#666', fontStyle: 'italic' }}>
      날씨 (인쇄 시점에 채워짐)
    </div>
  )
}

/**
 * dateHeader 패턴 포맷팅
 * 서버 HtmlTemplateBuilder.formatDateHeader와 동일 로직
 * 긴 토큰(dddd, YYYY, MM, DD)부터 치환해야 짧은 토큰(ddd, M, D)이 먼저 먹어버리지 않는다.
 *
 * 토큰 (docs/server/format-schema.md 4.3절):
 * - YYYY: 2026 (4-digit year)
 * - MM: 09 (2-digit month)
 * - M: 9 (month without leading zero)
 * - DD: 05 (2-digit day)
 * - D: 5 (day without leading zero)
 * - dddd: 월요일 (full weekday)
 * - ddd: 월 (short weekday)
 */
function formatDateHeader(pattern: string): string {
  const today = new Date()
  const year = today.getFullYear()
  const month = today.getMonth() + 1 // 0-indexed → 1-indexed
  const day = today.getDate()

  // 요일 계산 (getDay: Sunday=0, Monday=1, ..., Saturday=6)
  // 서버는 getDayOfWeek().getValue() - 1을 사용 (MONDAY=1 → index 0)
  // JavaScript getDay: Sunday=0, Monday=1, ..., Saturday=6
  // 변환: getDay() % 7을 하면 Sunday가 0이 되는데,
  // 서버는 Monday=0으로 정렬했으므로 (getDay() + 6) % 7이 필요
  const dayOfWeekIndex = (today.getDay() + 6) % 7 // Monday=0, Tuesday=1, ..., Sunday=6

  const weekdaysFull = ['월요일', '화요일', '수요일', '목요일', '금요일', '토요일', '일요일']
  const weekdaysShort = ['월', '화', '수', '목', '금', '토', '일']

  let result = pattern
  // 긴 토큰부터 치환 (순서 중요!)
  result = result.replace(/dddd/g, weekdaysFull[dayOfWeekIndex])
  result = result.replace(/YYYY/g, String(year).padStart(4, '0'))
  result = result.replace(/MM/g, String(month).padStart(2, '0'))
  result = result.replace(/DD/g, String(day).padStart(2, '0'))
  result = result.replace(/ddd/g, weekdaysShort[dayOfWeekIndex])
  result = result.replace(/M/g, String(month))
  result = result.replace(/D/g, String(day))

  return result
}
