// 서버 422의 errors[].path(예: widgets[2].props.ticker)를 위젯 id 기준으로 옮긴다.
// 저장 요청을 보낸 뒤에도 화면에서 위젯 배열이 바뀔 수 있으므로, 요청을 보낸 시점의
// 위젯 id 순서(submittedIds)를 함께 받아 index → id로 옮긴다(작업지시서 07 7.1-7).
import { ApiError } from '../types/problem'

/** 위젯 id → (props 키 → 메시지). widgets[i]·widgets[i].size처럼 특정 필드가 아닌 오류는 '_general'에 담는다. */
export type WidgetFieldErrors = Record<string, Record<string, string>>

const PROP_PATH = /^widgets\[(\d+)\]\.props\.(.+)$/
const WIDGET_PATH = /^widgets\[(\d+)\]/

export function mapWidgetFieldErrors(error: unknown, submittedIds: string[]): WidgetFieldErrors {
  const result: WidgetFieldErrors = {}
  if (!(error instanceof ApiError) || !error.problem.errors) return result

  for (const e of error.problem.errors) {
    const propMatch = PROP_PATH.exec(e.path)
    if (propMatch) {
      const id = submittedIds[Number(propMatch[1])]
      if (!id) continue
      result[id] = { ...result[id], [propMatch[2]]: e.message }
      continue
    }
    const widgetMatch = WIDGET_PATH.exec(e.path)
    if (widgetMatch) {
      const id = submittedIds[Number(widgetMatch[1])]
      if (!id) continue
      result[id] = { ...result[id], _general: e.message }
    }
  }

  return result
}

export function hasWidgetError(errors: WidgetFieldErrors, widgetId: string): boolean {
  return Object.keys(errors[widgetId] ?? {}).length > 0
}
