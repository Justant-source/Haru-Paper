import { useEffect, useState, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import { DEFAULT_STYLE, newBlock, type FormatDocument, type BlockType } from '../types/format'
import { ErrorBanner } from '../components/ErrorBanner'
import { BlockList } from '../format-editor/BlockList'
import { StyleForm } from '../format-editor/StyleForm'
import { BlockStyleForm } from '../format-editor/BlockStyleForm'
import { TextBlockForm } from '../format-editor/blocks/TextBlockForm'
import { ImageBlockForm } from '../format-editor/blocks/ImageBlockForm'
import { DateHeaderBlockForm } from '../format-editor/blocks/DateHeaderBlockForm'
import { WeatherBlockForm } from '../format-editor/blocks/WeatherBlockForm'
import { Preview } from '../format-editor/Preview'

export function FormatEditPage() {
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()

  const [document, setDocument] = useState<FormatDocument | null>(null)
  const [loading, setLoading] = useState(!!id)
  const [error, setError] = useState<unknown>(null)
  const [saveError, setSaveError] = useState<unknown>(null)
  const [saved, setSaved] = useState(true)
  const [saving, setSaving] = useState(false)
  const [activeTab, setActiveTab] = useState<'edit' | 'preview'>('edit')
  const [showAddBlockMenu, setShowAddBlockMenu] = useState(false)

  // 선택된 블록 인덱스 (스타일 편집 폼 표시용)
  const [selectedBlockIndex, setSelectedBlockIndex] = useState<number | null>(null)

  const initialDocRef = useRef<FormatDocument | null>(null)

  // 포맷 로드
  useEffect(() => {
    if (!id) {
      // 새 포맷 — 최소 빈 문서로 시작 (실제로는 목록에서 생성 후 리다이렉트)
      setDocument({
        schemaVersion: 1,
        meta: { name: '', author: '', description: '' },
        style: DEFAULT_STYLE,
        blocks: [],
      })
      setLoading(false)
      return
    }

    const load = async () => {
      try {
        const detail = await formatsApi.get(id)
        setDocument(detail.document)
        initialDocRef.current = detail.document
        setSaved(true)
        setError(null)
      } catch (e) {
        setError(e)
      } finally {
        setLoading(false)
      }
    }

    load()
  }, [id])

  // 문서가 변경되었을 때
  const handleDocumentChange = (newDoc: FormatDocument) => {
    setDocument(newDoc)
    setSaved(false)
  }

  // 저장
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
        navigate(`/formats/${detail.id}/edit`, { replace: true })
      }
      setSaved(true)
      initialDocRef.current = document
    } catch (e) {
      setSaveError(e)
    } finally {
      setSaving(false)
    }
  }

  if (loading || !document) {
    return <div className="page">로드 중...</div>
  }

  const hasBlocks = document.blocks.length > 0

  return (
    <div className="format-edit-page">
      <ErrorBanner error={error || saveError} />

      {/* 상단 영역: 이름, 설명, 저장 버튼, 상태 */}
      <div className="format-edit-header">
        <input
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
        />
        <textarea
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
        />

        <div className="format-edit-actions">
          <button
            type="button"
            onClick={handleSave}
            disabled={saving || saved}
            className="btn-save"
          >
            {saving ? '저장 중...' : '저장'}
          </button>
          <span className="save-status">
            {saved ? '저장됨' : '변경 사항 있음'}
          </span>
        </div>
      </div>

      {/* 모바일 탭 */}
      <div className="format-edit-tabs">
        <button
          type="button"
          className={`tab ${activeTab === 'edit' ? 'active' : ''}`}
          onClick={() => setActiveTab('edit')}
        >
          편집
        </button>
        <button
          type="button"
          className={`tab ${activeTab === 'preview' ? 'active' : ''}`}
          onClick={() => setActiveTab('preview')}
        >
          미리보기
        </button>
      </div>

      <div className="format-edit-content">
        {/* 편집 탭 */}
        {activeTab === 'edit' && (
          <div className="format-edit-area">
            {/* 전체 스타일 */}
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

            {/* 블록 목록 및 편집 */}
            <div className="blocks-section">
              <h2>블록</h2>

              {!hasBlocks ? (
                <div className="empty-blocks">
                  <p>블록을 추가하세요</p>
                  <div className="add-block-buttons">
                    {(['text', 'image', 'dateHeader', 'weather'] as BlockType[]).map((type) => (
                      <button
                        key={type}
                        type="button"
                        onClick={() => {
                          handleDocumentChange({
                            ...document,
                            blocks: [...document.blocks, newBlock(type)],
                          })
                        }}
                        className="btn-add-block"
                      >
                        {type === 'text' && '텍스트'}
                        {type === 'image' && '이미지'}
                        {type === 'dateHeader' && '날짜 헤더'}
                        {type === 'weather' && '날씨'}
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <>
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

                  <div className="add-block-menu-wrapper">
                    <button
                      type="button"
                      onClick={() => setShowAddBlockMenu(!showAddBlockMenu)}
                      className="btn-add-block-main"
                    >
                      블록 추가
                    </button>
                    {showAddBlockMenu && (
                      <div className="add-block-menu">
                        {(['text', 'image', 'dateHeader', 'weather'] as BlockType[]).map((type) => (
                          <button
                            key={type}
                            type="button"
                            onClick={() => {
                              handleDocumentChange({
                                ...document,
                                blocks: [...document.blocks, newBlock(type)],
                              })
                              setShowAddBlockMenu(false)
                            }}
                            className="add-block-menu-item"
                          >
                            {type === 'text' && '텍스트'}
                            {type === 'image' && '이미지'}
                            {type === 'dateHeader' && '날짜 헤더'}
                            {type === 'weather' && '날씨'}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                </>
              )}
            </div>

            {/* 선택된 블록 편집 폼 */}
            {selectedBlockIndex !== null && document.blocks[selectedBlockIndex] && (
              <div className="block-edit-section">
                <h2>
                  블록 {selectedBlockIndex + 1} 편집
                  <button
                    type="button"
                    onClick={() => setSelectedBlockIndex(null)}
                    className="btn-close"
                  >
                    닫기
                  </button>
                </h2>

                {document.blocks[selectedBlockIndex].type === 'text' && (
                  <TextBlockForm
                    props={document.blocks[selectedBlockIndex].props as unknown as any}
                    onChange={(props) => {
                      const newBlocks = [...document.blocks]
                      newBlocks[selectedBlockIndex] = {
                        ...newBlocks[selectedBlockIndex],
                        props: props as unknown as Record<string, unknown>,
                      }
                      handleDocumentChange({
                        ...document,
                        blocks: newBlocks,
                      })
                    }}
                  />
                )}

                {document.blocks[selectedBlockIndex].type === 'image' && (
                  <ImageBlockForm
                    props={document.blocks[selectedBlockIndex].props as unknown as any}
                    onChange={(props) => {
                      const newBlocks = [...document.blocks]
                      newBlocks[selectedBlockIndex] = {
                        ...newBlocks[selectedBlockIndex],
                        props: props as unknown as Record<string, unknown>,
                      }
                      handleDocumentChange({
                        ...document,
                        blocks: newBlocks,
                      })
                    }}
                  />
                )}

                {document.blocks[selectedBlockIndex].type === 'dateHeader' && (
                  <DateHeaderBlockForm
                    props={document.blocks[selectedBlockIndex].props as unknown as any}
                    onChange={(props) => {
                      const newBlocks = [...document.blocks]
                      newBlocks[selectedBlockIndex] = {
                        ...newBlocks[selectedBlockIndex],
                        props: props as unknown as Record<string, unknown>,
                      }
                      handleDocumentChange({
                        ...document,
                        blocks: newBlocks,
                      })
                    }}
                  />
                )}

                {document.blocks[selectedBlockIndex].type === 'weather' && (
                  <WeatherBlockForm
                    props={document.blocks[selectedBlockIndex].props as unknown as any}
                    onChange={(props) => {
                      const newBlocks = [...document.blocks]
                      newBlocks[selectedBlockIndex] = {
                        ...newBlocks[selectedBlockIndex],
                        props: props as unknown as Record<string, unknown>,
                      }
                      handleDocumentChange({
                        ...document,
                        blocks: newBlocks,
                      })
                    }}
                  />
                )}

                <BlockStyleForm
                  style={document.blocks[selectedBlockIndex].style}
                  onChange={(style) => {
                    const newBlocks = [...document.blocks]
                    newBlocks[selectedBlockIndex] = {
                      ...newBlocks[selectedBlockIndex],
                      style,
                    }
                    handleDocumentChange({
                      ...document,
                      blocks: newBlocks,
                    })
                  }}
                />
              </div>
            )}
          </div>
        )}

        {/* 미리보기 탭 */}
        {activeTab === 'preview' && (
          <Preview document={document} />
        )}
      </div>
    </div>
  )
}
