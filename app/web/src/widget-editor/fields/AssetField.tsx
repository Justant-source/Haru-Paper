import { useRef, useState } from 'react'
import type { PropField } from '../../types/widget'
import { assetsApi } from '../../api/assets'
import { ApiError } from '../../types/problem'
import { IconWidgetImage } from '../../components/icons'

// 근거: server/src/main/resources/application.yml:33 haru.upload-max-mb 기본값(HARU_UPLOAD_MAX_MB로 바뀔 수 있다).
// 실제 판정은 항상 서버(413)다 — 여기서는 왕복을 아끼려는 편의 검사일 뿐이다.
const MAX_UPLOAD_MB = 10
const ACCEPTED_TYPES = ['image/png', 'image/jpeg']

/** kind: 'asset' 필드 전용 편집기. 위젯 종류(descriptor.type)를 보지 않는다 — asset kind를
 * 가진 위젯이면 어느 것이든 이 컴포넌트로 편집된다(CLAUDE.md 위젯 경계). */
export function AssetField({
  field,
  value,
  onChange,
  error,
}: {
  field: PropField
  value: unknown
  onChange: (assetId: string) => void
  error?: string
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const [imgFailed, setImgFailed] = useState(false)

  const assetId = typeof value === 'string' && value ? value : null
  const labelId = `widget-field-${field.key}-label`

  const handleFile = async (file: File) => {
    setLocalError(null)

    if (!ACCEPTED_TYPES.includes(file.type)) {
      setLocalError('PNG 또는 JPEG만 올릴 수 있습니다.')
      return
    }
    if (file.size > MAX_UPLOAD_MB * 1024 * 1024) {
      setLocalError(`파일이 너무 큽니다. ${MAX_UPLOAD_MB}MB 이하로 올려 주세요.`)
      return
    }

    setUploading(true)
    try {
      const res = await assetsApi.upload(file)
      setImgFailed(false)
      onChange(res.assetId)
    } catch (e) {
      setLocalError(mapUploadError(e))
    } finally {
      setUploading(false)
      if (inputRef.current) {
        inputRef.current.value = ''
      }
    }
  }

  const displayError = localError ?? mapFieldError(error)

  return (
    <div className="field asset-field">
      <span id={labelId}>{field.label}</span>

      {assetId && !imgFailed ? (
        <div className="asset-field-preview">
          <img
            src={assetsApi.thumbnailUrl(assetId)}
            alt=""
            className="asset-field-thumb"
            onError={() => setImgFailed(true)}
          />
          <div className="asset-field-actions">
            <button
              type="button"
              className="link-btn"
              disabled={uploading}
              onClick={() => inputRef.current?.click()}
            >
              {uploading ? '올리는 중...' : '이미지 바꾸기'}
            </button>
            <button type="button" className="link-btn asset-field-remove" onClick={() => onChange('')}>
              제거
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          className="asset-field-placeholder"
          aria-labelledby={labelId}
          aria-busy={uploading}
          disabled={uploading}
          onClick={() => inputRef.current?.click()}
        >
          <IconWidgetImage className="asset-field-placeholder-icon" />
          <span>
            {imgFailed
              ? '이미지를 불러올 수 없습니다 — 다시 올리려면 탭하세요'
              : uploading
                ? '올리는 중...'
                : '이미지 올리기'}
          </span>
        </button>
      )}

      {field.help ? <span className="field-help">{field.help}</span> : null}
      {displayError ? <span className="field-error">{displayError}</span> : null}

      <input
        ref={inputRef}
        className="visually-hidden"
        type="file"
        accept={ACCEPTED_TYPES.join(',')}
        tabIndex={-1}
        aria-hidden="true"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) {
            handleFile(file)
          }
        }}
      />
    </div>
  )
}

function mapUploadError(e: unknown): string {
  if (e instanceof TypeError) {
    return '서버에 연결할 수 없습니다.'
  }
  if (e instanceof ApiError) {
    if (e.status === 413) {
      return `파일이 너무 큽니다. ${MAX_UPLOAD_MB}MB 이하로 올려 주세요.`
    }
    if (e.status === 415) {
      return 'PNG 또는 JPEG만 올릴 수 있습니다.'
    }
    return e.problem.detail ?? e.problem.title ?? '업로드에 실패했습니다.'
  }
  return '업로드에 실패했습니다.'
}

/** 저장 시 서버 422로 돌아온 오류(부모가 fieldErrors.ts로 되짚어 내려준다).
 * "asset not found"는 24시간 지나 정리된 초안 이미지일 가능성이 높아 안내를 구체화한다. */
function mapFieldError(error: string | undefined): string | undefined {
  if (!error) return undefined
  if (error.includes('asset not found')) {
    return '이미지가 만료되었습니다. 다시 올려 주세요.'
  }
  return error
}
