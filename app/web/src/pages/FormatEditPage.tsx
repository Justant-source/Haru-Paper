import { useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import { deviceApi } from '../api/device'
import {
  DEFAULT_STYLE,
  newBlock,
  type FormatDocument,
  type Block,
  type BlockType,
  type Row,
  type TextProps,
  type ImageProps,
  type DateHeaderProps,
  type WeatherProps,
} from '../types/format'
import { ApiError } from '../types/problem'
import { ErrorBanner } from '../components/ErrorBanner'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { BottomSheet } from '../components/BottomSheet'
import { IconBack } from '../components/icons'
import { StyleForm } from '../format-editor/StyleForm'
import { BlockStyleForm } from '../format-editor/BlockStyleForm'
import { TextBlockForm } from '../format-editor/blocks/TextBlockForm'
import { ImageBlockForm } from '../format-editor/blocks/ImageBlockForm'
import { DateHeaderBlockForm } from '../format-editor/blocks/DateHeaderBlockForm'
import { WeatherBlockForm } from '../format-editor/blocks/WeatherBlockForm'
import { Preview } from '../format-editor/Preview'
import { useI18n } from '../i18n'
// 다른 에이전트들이 만드는 컴포넌트. 아직 없으면 위에 주석으로 남겨두고 먼저 코드 구조만 짜라
// import { LayoutCanvas } from '../format-editor/LayoutCanvas'
// import { WidgetPalette } from '../format-editor/WidgetPalette'

// PrinterProfile.DEFAULT 값 (서버와 같은 값)
// 기준: 110mm 롤, 300dpi → 1304px
const DEFAULT_PRINTER_WIDTH_PX = 1304

const BLOCK_TYPES: { type: BlockType; label: string }[] = [
  { type: 'text', label: '텍스트' },
  { type: 'image', label: '이미지' },
  { type: 'dateHeader', label: '날짜 헤더' },
  { type: 'weather', label: '날씨' },
]

export function FormatEditPage() {
  const { t } = useI18n()
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()

  const [document, setDocument] = useState<FormatDocument | null>(() =>
    id
      ? null
      : {
          schemaVersion: 2,
          meta: { name: '', author: '', description: '' },
          style: DEFAULT_STYLE,
          rows: [],
        },
  )
  const [loading, setLoading] = useState(!!id)
  const [error, setError] = useState<unknown>(null)
  const [saveError, setSaveError] = useState<unknown>(null)
  const [saved, setSaved] = useState(true)
  const [saving, setSaving] = useState(false)
  const [leaveOpen, setLeaveOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)

  // 슬롯 선택 상태: rowId와 slotId 쌍
  const [selectedSlot, setSelectedSlot] = useState<{ rowId: string; slotId: string } | null>(null)

  const [printerWidthPx, setPrinterWidthPx] = useState(DEFAULT_PRINTER_WIDTH_PX)

  const initialDocRef = useRef<FormatDocument | null>(null)
  const savedRef = useRef(true)

  useEffect(() => {
    savedRef.current = saved
  }, [saved])

  // 기기 정보 로드 (프린터 폭 가져오기)
  useEffect(() => {
    const load = async () => {
      try {
        const device = await deviceApi.get()
        if (device.printerProfile?.printableWidthPx) {
          setPrinterWidthPx(device.printerProfile.printableWidthPx)
        }
      } catch (e) {
        // 기기 로드 실패는 로깅만 하고 기본값 사용
        console.debug('Failed to load device profile, using default printer width')
      }
    }
    load()
  }, [])

  useEffect(() => {
    if (!id) return

    let cancelled = false
    const load = async () => {
      setLoading(true)
      try {
        const detail = await formatsApi.get(id)
        if (cancelled) return
        setDocument(detail.document)
        initialDocRef.current = detail.document
        setSaved(true)
        setError(null)
      } catch (e) {
        if (!cancelled) setError(e)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    load()
    return () => {
      cancelled = true
    }
  }, [id])

  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!savedRef.current) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  const handleDocumentChange = (newDoc: FormatDocument) => {
    setDocument(newDoc)
    setSaved(false)
  }

  const handleSave = async () => {
    if (!document) return
    setSaving(true)
    setSaveError(null)

    try {
      if (id) {
        await formatsApi.update(id, document)
      } else {
        const detail = await formatsApi.create(document)
        initialDocRef.current = detail.document
        setSaved(true)
        navigate(`/formats/${detail.id}/edit`, { replace: true })
        return
      }
      setSaved(true)
      initialDocRef.current = document
    } catch (e) {
      setSaveError(e)
    } finally {
      setSaving(false)
    }
  }

  const requestLeave = () => {
    if (!saved) {
      setLeaveOpen(true)
      return
    }
    navigate('/')
  }

  const confirmLeave = () => {
    setLeaveOpen(false)
    setSaved(true)
    navigate('/')
  }

  // 선택된 슬롯의 블록을 찾는 헬퍼
  const findBlockInSelectedSlot = (): { block: Block; rowIndex: number; slotIndex: number } | null => {
    if (!document || !selectedSlot) return null

    for (let rowIndex = 0; rowIndex < document.rows.length; rowIndex++) {
      const row = document.rows[rowIndex]
      if (row.id === selectedSlot.rowId) {
        for (let slotIndex = 0; slotIndex < row.slots.length; slotIndex++) {
          const slot = row.slots[slotIndex]
          if (slot.id === selectedSlot.slotId) {
            return { block: slot.block, rowIndex, slotIndex }
          }
        }
      }
    }
    return null
  }

  // 선택된 슬롯의 블록을 업데이트
  const updateSelectedBlockInSlot = (patch: Partial<Block>) => {
    if (!document || !selectedSlot) return

    const found = findBlockInSelectedSlot()
    if (!found) return

    const { rowIndex, slotIndex } = found
    const newRows = JSON.parse(JSON.stringify(document.rows)) as Row[]
    const updatedBlock = { ...newRows[rowIndex].slots[slotIndex].block, ...patch }
    newRows[rowIndex].slots[slotIndex].block = updatedBlock

    handleDocumentChange({ ...document, rows: newRows })
  }

  if (loading) {
    return <div className="page">{t('loading')}</div>
  }

  if (!document) {
    return (
      <div className="format-edit-page">
        <header className="page-header">
          <button type="button" className="icon-btn" onClick={() => navigate('/')} aria-label="뒤로">
            <IconBack />
          </button>
        </header>
        <div className="page">
          <ErrorBanner
            error={error}
            onRetry={() => {
              if (!id) return
              setLoading(true)
              setError(null)
              formatsApi
                .get(id)
                .then((detail) => {
                  setDocument(detail.document)
                  initialDocRef.current = detail.document
                  setSaved(true)
                })
                .catch(setError)
                .finally(() => setLoading(false))
            }}
          />
        </div>
      </div>
    )
  }

  const selectedBlockInfo = findBlockInSelectedSlot()
  const selectedBlock = selectedBlockInfo?.block
  const saveProblem = saveError instanceof ApiError ? saveError : null
  const nameError = saveProblem?.fieldMessage('meta.name')
  const descError = saveProblem?.fieldMessage('meta.description')

  return (
    <div className="format-edit-page">
      <header className="page-header">
        <button type="button" className="icon-btn" onClick={requestLeave} aria-label="뒤로">
          <IconBack />
        </button>
        <span className="save-status">{saved ? t('saved') : t('unsaved')}</span>
        <Button variant="primary" className="btn-pill" onClick={handleSave} disabled={saving || saved}>
          {saving ? t('saving') : t('save')}
        </Button>
      </header>

      <ErrorBanner error={error || saveError} />

      <div className="format-edit-header">
        <div className="field">
          <label htmlFor="format-name">포맷 이름</label>
          <input
            id="format-name"
            type="text"
            placeholder="포맷 이름"
            value={document.meta.name}
            onChange={(e) => {
              handleDocumentChange({
                ...document,
                meta: { ...document.meta, name: e.target.value },
              })
            }}
            className="format-edit-title"
            aria-invalid={!!nameError}
            aria-describedby={nameError ? 'format-name-error' : undefined}
          />
          {nameError ? (
            <span id="format-name-error" className="field-error">
              {nameError}
            </span>
          ) : null}
        </div>
        <div className="field">
          <label htmlFor="format-description">설명</label>
          <textarea
            id="format-description"
            placeholder="설명"
            value={document.meta.description || ''}
            onChange={(e) => {
              handleDocumentChange({
                ...document,
                meta: { ...document.meta, description: e.target.value },
              })
            }}
            className="format-edit-description"
            rows={2}
            aria-invalid={!!descError}
            aria-describedby={descError ? 'format-description-error' : undefined}
          />
          {descError ? (
            <span id="format-description-error" className="field-error">
              {descError}
            </span>
          ) : null}
        </div>
      </div>

      <div className="format-edit-content">
        <div className="format-edit-area">
          <Card>
            <details className="style-section">
              <summary>전체 스타일</summary>
              <StyleForm
                style={document.style || DEFAULT_STYLE}
                onChange={(style) => {
                  handleDocumentChange({
                    ...document,
                    style,
                  })
                }}
              />
            </details>
          </Card>

          {/* LayoutCanvas 자리 (아직 컴포넌트가 없으면 stub으로 대체) */}
          <div style={{ marginBottom: '1rem' }}>
            <p>레이아웃 캔버스 (구현 중)</p>
            <Button variant="secondary" onClick={() => setPreviewOpen(true)}>
              정확히 보기
            </Button>
          </div>

          {/* WidgetPalette 자리 (아직 컴포넌트가 없으면 stub으로 대체) */}
          <div style={{ marginBottom: '1rem' }}>
            <p>블록 팔레트 (구현 중)</p>
          </div>
        </div>
      </div>

      {/* 슬롯 속성 편집 BottomSheet */}
      <BottomSheet open={!!selectedSlot} title={t('editingBlock')} onClose={() => setSelectedSlot(null)}>
        {selectedBlock && (
          <>
            {selectedBlock.type === 'text' && (
              <TextBlockForm
                props={selectedBlock.props as unknown as TextProps}
                onChange={(props) => {
                  updateSelectedBlockInSlot({ props: props as unknown as Record<string, unknown> })
                }}
              />
            )}

            {selectedBlock.type === 'image' && (
              <ImageBlockForm
                props={selectedBlock.props as unknown as ImageProps}
                onChange={(props) => {
                  updateSelectedBlockInSlot({ props: props as unknown as Record<string, unknown> })
                }}
              />
            )}

            {selectedBlock.type === 'dateHeader' && (
              <DateHeaderBlockForm
                props={selectedBlock.props as unknown as DateHeaderProps}
                onChange={(props) => {
                  updateSelectedBlockInSlot({ props: props as unknown as Record<string, unknown> })
                }}
              />
            )}

            {selectedBlock.type === 'weather' && (
              <WeatherBlockForm
                props={selectedBlock.props as unknown as WeatherProps}
                onChange={(props) => {
                  updateSelectedBlockInSlot({ props: props as unknown as Record<string, unknown> })
                }}
              />
            )}

            <BlockStyleForm
              style={selectedBlock.style}
              onChange={(style) => {
                updateSelectedBlockInSlot({ style })
              }}
            />
          </>
        )}
      </BottomSheet>

      {/* 미리보기 모달 */}
      <BottomSheet open={previewOpen} title={t('exactPreview')} onClose={() => setPreviewOpen(false)}>
        <Preview document={document} />
      </BottomSheet>

      <BottomSheet open={leaveOpen} title={t('leaveTitle')} onClose={() => setLeaveOpen(false)}>
        <p>{t('leaveBody')}</p>
        <div className="stack">
          <Button variant="secondary" onClick={() => setLeaveOpen(false)}>
            {t('stay')}
          </Button>
          <Button variant="danger" onClick={confirmLeave}>
            {t('leave')}
          </Button>
        </div>
      </BottomSheet>
    </div>
  )
}
