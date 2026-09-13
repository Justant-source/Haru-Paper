import { useState } from 'react'
import { assetsApi } from '../../api/assets'
import { type ImageProps } from '../../types/format'
import { ApiError } from '../../types/problem'

const MAX_IMAGE_SIZE = 10 * 1024 * 1024 // 10MB

export function ImageBlockForm({
  props,
  onChange,
}: {
  props: ImageProps
  onChange: (props: ImageProps) => void
}) {
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)

  const handleFileSelect = async (file: File) => {
    if (file.size > MAX_IMAGE_SIZE) {
      setUploadError(`파일 크기가 10MB를 초과합니다 (${(file.size / 1024 / 1024).toFixed(1)}MB)`)
      return
    }

    setUploading(true)
    setUploadError(null)

    try {
      const response = await assetsApi.upload(file)
      onChange({
        ...props,
        assetId: response.assetId,
      })
    } catch (e) {
      setUploadError(
        e instanceof ApiError
          ? e.message
          : e instanceof Error
            ? e.message
            : '업로드 실패'
      )
    } finally {
      setUploading(false)
    }
  }

  const thumbnailUrl = props.assetId
    ? assetsApi.thumbnailUrl(props.assetId)
    : null

  return (
    <div className="block-form image-block-form">
      {uploadError && (
        <div className="error-message">{uploadError}</div>
      )}

      {thumbnailUrl && (
        <div className="image-preview">
          <img src={thumbnailUrl} alt="미리보기" />
        </div>
      )}

      <label className="file-input-label">
        이미지 선택
        <input
          type="file"
          accept="image/png,image/jpeg"
          onChange={(e) => {
            const file = e.currentTarget.files?.[0]
            if (file) handleFileSelect(file)
          }}
          disabled={uploading}
        />
      </label>

      <label>
        너비 ({props.widthPercent || 100}%)
        <input
          type="range"
          min="10"
          max="100"
          step="5"
          value={props.widthPercent || 100}
          onChange={(e) => {
            onChange({
              ...props,
              widthPercent: parseInt(e.target.value, 10),
            })
          }}
        />
      </label>
    </div>
  )
}
