import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { deviceApi } from '../api/device'
import { evaluatePrintReadiness, type PrintReadiness } from '../lib/printReadiness'
import { useDeviceStatus } from '../hooks/useDeviceStatus'
import { BottomSheet } from './BottomSheet'
import { Button } from './Button'
import { ErrorBanner } from './ErrorBanner'

/**
 * 홈·예약 화면 상단에 뜨는 "인쇄가 막혀 있음" 배너 + 해결 시트.
 * Phomemo 벤치마킹 패턴 2번(docs/app/editor.md 11절): 다이얼로그(배너)는 무엇이
 * 막혔는지만, 상세·해결은 시트/별도 화면으로 분리한다.
 *
 * 가장 위험이 높은 것 1개만 배너로 보여준다 — 여러 개면 화면이 경고로 뒤덮인다.
 * 나머지는 시트 안에 전부 나열한다.
 */
export function PrintBlockedBanner() {
  const { data: device } = useDeviceStatus()
  const readiness = evaluatePrintReadiness(device)

  if (!readiness.primary) return null

  return <PrintBlockedBannerView readiness={readiness} />
}

function PrintBlockedBannerView({ readiness }: { readiness: PrintReadiness }) {
  const [sheetOpen, setSheetOpen] = useState(false)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const confirmPaperMutation = useMutation({
    mutationFn: () => deviceApi.setPaperState(true),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['device'] })
      setSheetOpen(false)
    },
  })

  const tone = readiness.primary!.severity === 'danger' ? 'banner-danger' : 'banner-warn'
  const role = readiness.primary!.severity === 'danger' ? 'alert' : 'status'

  return (
    <>
      <div className={`banner ${tone}`} role={role}>
        <span>
          <strong>인쇄가 막혀 있습니다</strong> — {readiness.primary!.title}
        </span>
        <button type="button" className="banner-retry" onClick={() => setSheetOpen(true)}>
          해결 방법 보기
        </button>
      </div>

      <BottomSheet open={sheetOpen} title="인쇄가 막힌 이유" onClose={() => setSheetOpen(false)}>
        <div className="stack">
          {readiness.issues.map((issue) => (
            <div
              key={issue.code}
              className={`banner print-blocked-issue ${issue.severity === 'danger' ? 'banner-danger' : 'banner-warn'}`}
            >
              <strong>{issue.title}</strong>
              <p>{issue.body}</p>
              {issue.steps.length > 0 && (
                <ol className="print-blocked-steps">
                  {issue.steps.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ol>
              )}
            </div>
          ))}

          {readiness.paperToggleActionable && (
            <>
              <ErrorBanner error={confirmPaperMutation.error} />
              <Button
                variant="primary"
                onClick={() => confirmPaperMutation.mutate()}
                disabled={confirmPaperMutation.isPending}
              >
                {confirmPaperMutation.isPending ? '처리 중...' : '프린터에 용지를 확인했습니다 — 용지 장착됨 켜기'}
              </Button>
            </>
          )}

          <button
            type="button"
            className="link-btn"
            onClick={() => {
              setSheetOpen(false)
              navigate('/device?focus=paper')
            }}
          >
            기기 화면에서 자세히 보기 →
          </button>
        </div>
      </BottomSheet>
    </>
  )
}
