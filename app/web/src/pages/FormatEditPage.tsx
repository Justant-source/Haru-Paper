import { useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import {
  DEFAULT_STYLE,
  newBlock,
  type FormatDocument,
  type Block,
  type BlockType,
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
import { IconBack, IconClose } from '../components/icons'
import { BlockList } from '../format-editor/BlockList'
import { StyleForm } from '../format-editor/StyleForm'
import { BlockStyleForm } from '../format-editor/BlockStyleForm'
import { TextBlockForm } from '../format-editor/blocks/TextBlockForm'
import { ImageBlockForm } from '../format-editor/blocks/ImageBlockForm'
import { DateHeaderBlockForm } from '../format-editor/blocks/DateHeaderBlockForm'
import { WeatherBlockForm } from '../format-editor/blocks/WeatherBlockForm'
import { Preview } from '../format-editor/Preview'
import { useI18n } from '../i18n'

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
          schemaVersion: 1,
          meta: { name: '', author: '', description: '' },
          style: DEFAULT_STYLE,
          blocks: [],
        },
  )
  const [loading, setLoading] = useState(!!id)
  const [error, setError] = useState<unknown>(null)
  const [saveError, setSaveError] = useState<unknown>(null)
  const [saved, setSaved] = useState(true)
  const [saving, setSaving] = useState(false)
  const [activeTab, setActiveTab] = useState<'edit' | 'preview'>('edit')
  const [addBlockOpen, setAddBlockOpen] = useState(false)
  const [leaveOpen, setLeaveOpen] = useState(false)

  const [selectedBlockIndex, setSelectedBlockIndex] = useState<number | null>(null)

  const initialDocRef = useRef<FormatDocument | null>(null)
  const savedRef = useRef(true)

  useEffect(() => {
    savedRef.current = saved
  }, [saved])

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

  const addBlock = (type: BlockType) => {
    if (!document) return
    const blocks = [...document.blocks, newBlock(type)]
    handleDocumentChange({ ...document, blocks })
    setSelectedBlockIndex(blocks.length - 1)
    setAddBlockOpen(false)
  }

  const updateSelectedBlock = (patch: Partial<Block>) => {
    if (!document || selectedBlockIndex === null) return
    const newBlocks = [...document.blocks]
    newBlocks[selectedBlockIndex] = { ...newBlocks[selectedBlockIndex], ...patch }
    handleDocumentChange({ ...document, blocks: newBlocks })
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

  const hasBlocks = document.blocks.length > 0
  const selected = selectedBlockIndex !== null ? document.blocks[selectedBlockIndex] : undefined
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

      <div className="format-edit-tabs">
        <button
          type="button"
          className={`tab${activeTab === 'edit' ? ' active' : ''}`}
          onClick={() => setActiveTab('edit')}
        >
          {t('editTab')}
        </button>
        <button
          type="button"
          className={`tab${activeTab === 'preview' ? ' active' : ''}`}
          onClick={() => setActiveTab('preview')}
        >
          {t('previewTab')}
        </button>
      </div>

      <div className="format-edit-content">
        {activeTab === 'edit' && (
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

            <div className="blocks-section">
              <h2>블록</h2>

              {!hasBlocks ? (
                <Card className="empty-blocks">
                  <p>블록을 추가하세요</p>
                  <div className="add-block-buttons">
                    {BLOCK_TYPES.map(({ type, label }) => (
                      <Button key={type} variant="secondary" onClick={() => addBlock(type)}>
                        {label}
                      </Button>
                    ))}
                  </div>
                </Card>
              ) : (
                <>
                  <Card>
                    <BlockList
                      blocks={document.blocks}
                      selectedIndex={selectedBlockIndex}
                      onSelect={setSelectedBlockIndex}
                      onChange={(blocks) => {
                        handleDocumentChange({
                          ...document,
                          blocks,
                        })
                      }}
                    />
                  </Card>

                  <Button variant="secondary" onClick={() => setAddBlockOpen(true)}>
                    {t('addBlock')}
                  </Button>
                </>
              )}
            </div>

            {selected && selectedBlockIndex !== null && (
              <Card>
                <div className="sheet-head">
                  <h2 className="sheet-title">블록 {selectedBlockIndex + 1} 편집</h2>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={() => setSelectedBlockIndex(null)}
                    aria-label="닫기"
                  >
                    <IconClose />
                  </button>
                </div>

                {selected.type === 'text' && (
                  <TextBlockForm
                    props={selected.props as unknown as TextProps}
                    onChange={(props) => {
                      updateSelectedBlock({ props: props as unknown as Record<string, unknown> })
                    }}
                  />
                )}

                {selected.type === 'image' && (
                  <ImageBlockForm
                    props={selected.props as unknown as ImageProps}
                    onChange={(props) => {
                      updateSelectedBlock({ props: props as unknown as Record<string, unknown> })
                    }}
                  />
                )}

                {selected.type === 'dateHeader' && (
                  <DateHeaderBlockForm
                    props={selected.props as unknown as DateHeaderProps}
                    onChange={(props) => {
                      updateSelectedBlock({ props: props as unknown as Record<string, unknown> })
                    }}
                  />
                )}

                {selected.type === 'weather' && (
                  <WeatherBlockForm
                    props={selected.props as unknown as WeatherProps}
                    onChange={(props) => {
                      updateSelectedBlock({ props: props as unknown as Record<string, unknown> })
                    }}
                  />
                )}

                <BlockStyleForm
                  style={selected.style}
                  onChange={(style) => {
                    updateSelectedBlock({ style })
                  }}
                />
              </Card>
            )}
          </div>
        )}

        {activeTab === 'preview' && <Preview document={document} />}
      </div>

      <BottomSheet open={addBlockOpen} title={t('addBlock')} onClose={() => setAddBlockOpen(false)}>
        {BLOCK_TYPES.map(({ type, label }) => (
          <button key={type} type="button" className="sheet-item" onClick={() => addBlock(type)}>
            {label}
          </button>
        ))}
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
