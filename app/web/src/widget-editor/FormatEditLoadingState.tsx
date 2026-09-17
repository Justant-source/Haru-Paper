import { ErrorBanner } from '../components/ErrorBanner'
import { IconBack } from '../components/icons'

/** 로드 중 / 로드 실패일 때 공통으로 쓰는 골격(뒤로 버튼만 있는 헤더 + 본문). */
export function FormatEditLoadingState({
  onBack,
  error,
  onRetry,
  loadingLabel,
}: {
  onBack: () => void
  error?: unknown
  onRetry?: () => void
  loadingLabel: string
}) {
  return (
    <div className="format-edit-page">
      <header className="page-header">
        <button type="button" className="icon-btn" onClick={onBack} aria-label="뒤로">
          <IconBack />
        </button>
      </header>
      <div className="page">
        {error ? <ErrorBanner error={error} onRetry={onRetry} /> : <p>{loadingLabel}</p>}
      </div>
    </div>
  )
}
