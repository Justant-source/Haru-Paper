import { ApiError } from '../types/problem'

/** 공통 오류 처리(docs/app/screens.md 공통절): 네트워크 오류 vs 서버 오류를 구분해서 보여준다. */
export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null

  const isNetworkError = error instanceof TypeError
  const message = isNetworkError
    ? '서버에 연결할 수 없습니다. 폰의 Tailscale이 켜져 있는지 확인하세요.'
    : error instanceof ApiError
      ? (error.problem.detail ?? error.problem.title ?? '알 수 없는 오류가 발생했습니다.')
      : error instanceof Error
        ? error.message
        : '알 수 없는 오류가 발생했습니다.'

  return (
    <div className="error-banner" role="alert">
      <span>{message}</span>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  )
}
