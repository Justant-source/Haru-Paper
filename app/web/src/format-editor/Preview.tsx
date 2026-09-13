import { useEffect, useState, useRef } from 'react'
import { formatsApi } from '../api/formats'
import { type FormatDocument } from '../types/format'
import { ApiError } from '../types/problem'

export function Preview({ document }: { document: FormatDocument }) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [outOfDate, setOutOfDate] = useState(false)

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const requestCountRef = useRef(0)

  // 1초 디바운스로 미리보기 업데이트
  useEffect(() => {
    // 이전 타임아웃 취소
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
    }

    setOutOfDate(true)
    const requestId = ++requestCountRef.current

    timeoutRef.current = setTimeout(async () => {
      // 이 요청보다 나중에 발생한 요청이 있으면 취소
      if (requestId !== requestCountRef.current) {
        return
      }

      setLoading(true)
      setError(null)

      try {
        const newBlobUrl = await formatsApi.previewEphemeralBlob(document)

        // 응답이 도착했을 때 더 최신 요청이 없으면 상태 업데이트
        if (requestId === requestCountRef.current) {
          if (blobUrl) {
            URL.revokeObjectURL(blobUrl)
          }
          setBlobUrl(newBlobUrl)
          setOutOfDate(false)
          setError(null)
        }
      } catch (e) {
        if (requestId === requestCountRef.current) {
          setError(e)
          // 전체 에러가 아니라 필드 에러라면 이전 이미지는 유지
          if (e instanceof ApiError && e.problem.errors && e.problem.errors.length > 0) {
            setOutOfDate(true)
          }
        }
      } finally {
        if (requestId === requestCountRef.current) {
          setLoading(false)
        }
      }
    }, 1000)

    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
      }
    }
  }, [document, blobUrl])

  // 언마운트 시 blob URL 정리
  useEffect(() => {
    return () => {
      if (blobUrl) {
        URL.revokeObjectURL(blobUrl)
      }
    }
  }, [])

  const renderError = () => {
    if (!error) return null
    if (error instanceof ApiError) {
      return (
        <div className="preview-error">
          <p>{error.message}</p>
          {error.problem.errors && error.problem.errors.length > 0 && (
            <ul>
              {error.problem.errors.map((err, i) => (
                <li key={i}>
                  <strong>{err.path}</strong>: {err.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )
    } else if (error instanceof Error) {
      return (
        <div className="preview-error">
          <p>{error.message}</p>
        </div>
      )
    } else {
      return (
        <div className="preview-error">
          <p>미리보기 렌더 실패</p>
        </div>
      )
    }
  }

  return (
    <div className="preview-area">
      {loading && <div className="preview-loading">렌더 중...</div>}

      {renderError()}

      {outOfDate && !error && (
        <div className="preview-notice">미리보기가 최신이 아닙니다</div>
      )}

      {blobUrl && (
        <div className="preview-image-container">
          <img
            src={blobUrl}
            alt="미리보기"
            className="preview-image"
          />
        </div>
      )}

      {!blobUrl && !loading && !error && (
        <div className="preview-empty">
          <p>미리보기를 준비하는 중입니다</p>
        </div>
      )}
    </div>
  )
}
