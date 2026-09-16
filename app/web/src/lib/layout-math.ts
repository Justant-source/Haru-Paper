/**
 * 단위 변환 함수들. 서버 HtmlTemplateBuilder와 동일한 공식을 사용한다.
 * docs/server/format-schema.md: 단위는 mm(길이)와 pt(글자 크기)이며,
 * 렌더할 때 프린터 프로필 dpi로 환산한다.
 */

/**
 * mm → px 변환
 * 공식: mm / 25.4 × dpi
 * 근거: 1 inch = 25.4 mm, 1 inch = dpi pixels
 * 서버 HtmlTemplateBuilder.convertMmToPx와 동일
 */
export function mmToPx(mm: number, dpi: number): number {
  return (mm / 25.4) * dpi
}

/**
 * pt → px 변환
 * 공식: pt / 72 × dpi
 * 근거: 1 inch = 72 pt, 1 inch = dpi pixels
 * 서버 HtmlTemplateBuilder.convertPtToPx와 동일
 */
export function ptToPx(pt: number, dpi: number): number {
  return (pt / 72) * dpi
}
