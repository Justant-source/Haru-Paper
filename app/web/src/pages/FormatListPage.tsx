import { useState, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import type { FormatDocument } from '../types/format'
import { DEFAULT_STYLE, newBlock } from '../types/format'
import { ApiError } from '../types/problem'
import { formatDateTimeKo } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'

export function FormatListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [importError, setImportError] = useState<string | null>(null)

  const { data: formats, isLoading, error, refetch } = useQuery({
    queryKey: ['formats'],
    queryFn: () => formatsApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: async () => {
      const newDoc: FormatDocument = {
        schemaVersion: 1,
        meta: { name: '새로운 포맷', author: '', description: '' },
        style: DEFAULT_STYLE,
        blocks: [newBlock('dateHeader')],
      }
      return formatsApi.create(newDoc)
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      navigate(`/formats/${result.id}/edit`)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => formatsApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      setDeleteConfirmId(null)
      setMenuOpenId(null)
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 409) {
        // 예약에 쓰이는 포맷
        alert('이 포맷을 쓰는 예약이 있습니다')
      } else {
        alert(err instanceof ApiError ? err.problem.detail ?? err.message : '삭제 실패')
      }
      setDeleteConfirmId(null)
    },
  })

  const importMutation = useMutation({
    mutationFn: async (document: unknown) => {
      return formatsApi.import(document)
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      setImportError(null)
      navigate(`/formats/${result.id}/edit`)
    },
    onError: (err) => {
      const message =
        err instanceof ApiError ? err.problem.detail ?? err.problem.title ?? '가져오기 실패' : '가져오기 실패'
      setImportError(message)
    },
  })

  const handleFileSelect = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return

    try {
      const text = await file.text()
      const doc = JSON.parse(text)
      await importMutation.mutateAsync(doc)
    } catch (err) {
      setImportError(err instanceof SyntaxError ? 'JSON 형식이 올바르지 않습니다' : '파일 읽기 실패')
    }

    // 다시 같은 파일을 선택할 수 있도록 초기화
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const handleDeleteClick = (id: string) => {
    setDeleteConfirmId(id)
  }

  const confirmDelete = () => {
    if (deleteConfirmId) {
      deleteMutation.mutate(deleteConfirmId)
    }
  }

  if (isLoading) {
    return (
      <div className="page">
        <h1>포맷 목록</h1>
        <p>로드 중...</p>
      </div>
    )
  }

  const isEmpty = !formats || formats.length === 0

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h1 style={{ margin: 0 }}>포맷 목록</h1>
      </div>

      <ErrorBanner error={error} onRetry={() => refetch()} />

      {importError && (
        <div
          style={{
            background: '#fef2f2',
            color: '#dc2626',
            border: '1px solid #fecaca',
            borderRadius: '8px',
            padding: '10px 12px',
            marginBottom: '12px',
            fontSize: '14px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span>{importError}</span>
          <button
            type="button"
            onClick={() => setImportError(null)}
            style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '18px' }}
          >
            ✕
          </button>
        </div>
      )}

      {isEmpty ? (
        <EmptyState
          message="아직 포맷이 없습니다. 첫 카드를 만들어 보세요."
          actionLabel="새 포맷"
          onAction={() => createMutation.mutate()}
        />
      ) : (
        <>
          <div style={{ display: 'flex', gap: '8px', marginBottom: '16px' }}>
            <button
              type="button"
              onClick={() => createMutation.mutate()}
              style={{
                flex: 1,
                background: '#111827',
                color: '#fff',
                border: 'none',
                borderRadius: '6px',
                padding: '10px 12px',
                fontSize: '14px',
                fontWeight: 500,
              }}
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? '생성 중...' : '새 포맷'}
            </button>
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              style={{
                flex: 1,
                background: '#f3f4f6',
                color: '#111827',
                border: '1px solid #d1d5db',
                borderRadius: '6px',
                padding: '10px 12px',
                fontSize: '14px',
                fontWeight: 500,
              }}
              disabled={importMutation.isPending}
            >
              {importMutation.isPending ? '가져오는 중...' : '가져오기'}
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json"
              onChange={handleFileSelect}
              style={{ display: 'none' }}
            />
          </div>

          <div className="format-list">
            {formats.map((format) => (
              <div key={format.id} className="format-card">
                <div style={{ display: 'flex', gap: '12px' }}>
                  {/* 썸네일 */}
                  <div style={{ position: 'relative', width: '60px', flexShrink: 0 }}>
                    <img
                      src={formatsApi.previewUrl(format.id)}
                      alt=""
                      style={{
                        width: '60px',
                        height: '80px',
                        objectFit: 'cover',
                        borderRadius: '4px',
                        background: '#f3f4f6',
                      }}
                      onError={(e) => {
                        e.currentTarget.style.display = 'none'
                      }}
                    />
                  </div>

                  {/* 포맷 정보 */}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 500, fontSize: '15px', marginBottom: '4px' }}>{format.name}</div>
                    {format.forkedFrom && (
                      <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px' }}>
                        원작자: {format.forkedFrom.author}
                      </div>
                    )}
                    <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '8px' }}>
                      {formatDateTimeKo(format.updatedAt)}
                    </div>
                  </div>

                  {/* 메뉴 버튼 */}
                  <div style={{ position: 'relative' }}>
                    <button
                      type="button"
                      onClick={() => setMenuOpenId(menuOpenId === format.id ? null : format.id)}
                      style={{
                        background: 'none',
                        border: 'none',
                        cursor: 'pointer',
                        fontSize: '20px',
                        padding: '4px 4px',
                        color: '#6b7280',
                      }}
                    >
                      ⋯
                    </button>

                    {/* 메뉴 드롭다운 */}
                    {menuOpenId === format.id && (
                      <div
                        style={{
                          position: 'absolute',
                          top: '100%',
                          right: 0,
                          background: '#fff',
                          border: '1px solid #e5e7eb',
                          borderRadius: '6px',
                          boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
                          zIndex: 10,
                          minWidth: '120px',
                          overflow: 'hidden',
                        }}
                      >
                        <button
                          type="button"
                          onClick={() => {
                            navigate(`/formats/${format.id}/edit`)
                            setMenuOpenId(null)
                          }}
                          style={{
                            display: 'block',
                            width: '100%',
                            padding: '10px 12px',
                            border: 'none',
                            background: 'none',
                            textAlign: 'left',
                            cursor: 'pointer',
                            fontSize: '14px',
                            borderBottom: '1px solid #e5e7eb',
                          }}
                        >
                          편집
                        </button>
                        <a
                          href={formatsApi.exportUrl(format.id)}
                          download
                          onClick={() => setMenuOpenId(null)}
                          style={{
                            display: 'block',
                            padding: '10px 12px',
                            textDecoration: 'none',
                            color: 'inherit',
                            fontSize: '14px',
                            borderBottom: '1px solid #e5e7eb',
                          }}
                        >
                          내보내기
                        </a>
                        <button
                          type="button"
                          onClick={() => {
                            navigate(`/print-now?formatId=${format.id}`)
                            setMenuOpenId(null)
                          }}
                          style={{
                            display: 'block',
                            width: '100%',
                            padding: '10px 12px',
                            border: 'none',
                            background: 'none',
                            textAlign: 'left',
                            cursor: 'pointer',
                            fontSize: '14px',
                            borderBottom: '1px solid #e5e7eb',
                          }}
                        >
                          지금 인쇄
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            handleDeleteClick(format.id)
                            setMenuOpenId(null)
                          }}
                          style={{
                            display: 'block',
                            width: '100%',
                            padding: '10px 12px',
                            border: 'none',
                            background: 'none',
                            textAlign: 'left',
                            cursor: 'pointer',
                            fontSize: '14px',
                            color: '#dc2626',
                          }}
                        >
                          삭제
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {/* 삭제 확인 대화상자 */}
      {deleteConfirmId && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0, 0, 0, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 100,
          }}
          onClick={() => setDeleteConfirmId(null)}
        >
          <div
            style={{
              background: '#fff',
              borderRadius: '8px',
              padding: '20px',
              maxWidth: '300px',
              boxShadow: '0 4px 12px rgba(0, 0, 0, 0.15)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 style={{ marginTop: 0, marginBottom: '12px', fontSize: '16px' }}>포맷을 삭제하시겠습니까?</h3>
            <p style={{ margin: '0 0 20px 0', fontSize: '14px', color: '#6b7280' }}>
              이 작업은 되돌릴 수 없습니다.
            </p>
            <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
              <button
                type="button"
                onClick={() => setDeleteConfirmId(null)}
                style={{
                  padding: '8px 12px',
                  border: '1px solid #d1d5db',
                  background: '#f3f4f6',
                  borderRadius: '4px',
                  cursor: 'pointer',
                  fontSize: '14px',
                }}
              >
                취소
              </button>
              <button
                type="button"
                onClick={confirmDelete}
                disabled={deleteMutation.isPending}
                style={{
                  padding: '8px 12px',
                  border: 'none',
                  background: '#dc2626',
                  color: '#fff',
                  borderRadius: '4px',
                  cursor: 'pointer',
                  fontSize: '14px',
                }}
              >
                {deleteMutation.isPending ? '삭제 중...' : '삭제'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
