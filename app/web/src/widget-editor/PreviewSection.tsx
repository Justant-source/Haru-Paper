import { useEffect, useRef, useState } from 'react'
import { formatsApi } from '../api/formats'
import type { FormatDocument } from '../types/format'
import { ApiError } from '../types/problem'
import { IconChevronDown, IconChevronUp } from '../components/icons'

const DEBOUNCE_MS = 1000

/**
 * 서버 실제 렌더로 미리보기를 만든다. 1초 디바운스로 최신 요청만 반영한다
 * (옛 format-editor/Preview.tsx 로직 재사용 — git show HEAD~1:app/web/src/format-editor/Preview.tsx).
 * 위젯이 0개거나 필수값이 빈 위젯이 있으면 호출하지 않는다(작업지시서 07 7.1-7).
 */
export function PreviewSection({
  document,
  canPreview,
  blockedReason,
}: {
  document: FormatDocument
  canPreview: boolean
  blockedReason?: string
}) {
  const [collapsed, setCollapsed] = useState(false)
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const requestIdRef = useRef(0)
  const blobUrlRef = useRef<string | null>(null)

  useEffect(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current)

    if (!canPreview) {
      requestIdRef.current += 1
      setLoading(false)
      setError(null)
      return
    }

    const requestId = ++requestIdRef.current
    timeoutRef.current = setTimeout(async () => {
      if (requestId !== requestIdRef.current) return
      setLoading(true)
      setError(null)
      try {
        const newBlobUrl = await formatsApi.previewEphemeralBlob(document)
        if (requestId !== requestIdRef.current) {
          URL.revokeObjectURL(newBlobUrl)
          return
        }
        if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current)
        blobUrlRef.current = newBlobUrl
        setBlobUrl(newBlobUrl)
      } catch (e) {
        if (requestId === requestIdRef.current) setError(e)
      } finally {
        if (requestId === requestIdRef.current) setLoading(false)
      }
    }, DEBOUNCE_MS)

    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current)
    }
  }, [document, canPreview])

  useEffect(
    () => () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current)
    },
    [],
  )

  const renderError = () => {
    if (!error) return null
    if (error instanceof ApiError) {
      return (
        <div className="preview-error">
          <p>{error.message}</p>
        </div>
      )
    }
    if (error instanceof TypeError) {
      return (
        <div className="preview-error">
          <p>서버에 연결할 수 없습니다.</p>
        </div>
      )
    }
    return (
      <div className="preview-error">
        <p>미리보기 렌더 실패</p>
      </div>
    )
  }

  return (
    <div className="widget-preview-card">
      <button
        type="button"
        className="widget-preview-toggle"
        onClick={() => setCollapsed((c) => !c)}
        aria-expanded={!collapsed}
      >
        <span>미리보기</span>
        {collapsed ? <IconChevronDown /> : <IconChevronUp />}
      </button>
      {collapsed ? null : (
        <div className="widget-preview-body">
          {!canPreview ? (
            <p className="preview-empty">{blockedReason}</p>
          ) : (
            <>
              {loading ? <div className="preview-loading">렌더 중...</div> : null}
              {renderError()}
              {blobUrl ? (
                <div className="paper-frame">
                  <img src={blobUrl} alt="미리보기" className="preview-image" />
                </div>
              ) : null}
              {!blobUrl && !loading && !error ? <p className="preview-empty">미리보기를 준비하는 중입니다</p> : null}
              <p className="preview-note">실제 인쇄 폭은 110mm입니다.</p>
            </>
          )}
        </div>
      )}
    </div>
  )
}
