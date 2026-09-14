import { ApiError } from '../types/problem'
import { useI18n } from '../i18n'

/** 공통 오류 처리(docs/app/screens.md 공통절): 네트워크 오류 vs 서버 오류를 구분해서 보여준다. */
export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useI18n()
  if (!error) return null

  const isNetworkError = error instanceof TypeError
  const message = isNetworkError
    ? t('networkError')
    : error instanceof ApiError
      ? (error.problem.detail ?? error.problem.title ?? t('unknownError'))
      : error instanceof Error
        ? error.message
        : t('unknownError')

  return (
    <div className="banner banner-danger" role="alert">
      <span>{message}</span>
      {onRetry && (
        <button type="button" className="banner-retry" onClick={onRetry}>
          {t('retry')}
        </button>
      )}
    </div>
  )
}
