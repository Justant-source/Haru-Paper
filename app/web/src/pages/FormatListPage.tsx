import { useState, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { formatsApi } from '../api/formats'
import type { FormatDocument } from '../types/format'
import { DEFAULT_STYLE, newBlock, newRow } from '../types/format'
import { ApiError } from '../types/problem'
import { formatDateTimeKo } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { BottomSheet } from '../components/BottomSheet'
import { IconKebab } from '../components/icons'
import { useI18n } from '../i18n'
import { track } from '../lib/analytics'

type SheetState =
  | { type: 'menu'; id: string }
  | { type: 'confirm'; id: string }
  | { type: 'notice'; title: string; message: string }

export function FormatListPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [sheet, setSheet] = useState<SheetState | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const closeSheet = () => setSheet(null)

  const { data: formats, isLoading, error, refetch } = useQuery({
    queryKey: ['formats'],
    queryFn: () => formatsApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: async () => {
      const newDoc: FormatDocument = {
        schemaVersion: 2,
        meta: { name: '새로운 포맷', author: '', description: '' },
        style: DEFAULT_STYLE,
        rows: [newRow(newBlock('dateHeader'))],
      }
      return formatsApi.create(newDoc)
    },
    onSuccess: (result) => {
      track('format_create')
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      navigate(`/formats/${result.id}/edit`)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => formatsApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      setSheet(null)
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 409) {
        setSheet({
          type: 'notice',
          title: t('cannotDelete'),
          message: t('formatInUse'),
        })
        return
      }
      setSheet({
        type: 'notice',
        title: t('deleteFailed'),
        message: err instanceof ApiError ? (err.problem.detail ?? err.message) : t('deleteFailed'),
      })
    },
  })

  const importMutation = useMutation({
    mutationFn: async (document: unknown) => {
      return formatsApi.import(document)
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['formats'] })
      setSheet(null)
      navigate(`/formats/${result.id}/edit`)
    },
    onError: (err) => {
      const message =
        err instanceof ApiError ? err.problem.detail ?? err.problem.title ?? '가져오기 실패' : '가져오기 실패'
      setSheet({ type: 'notice', title: '가져오기 실패', message })
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
      setSheet({
        type: 'notice',
        title: '가져오기 실패',
        message: err instanceof SyntaxError ? 'JSON 형식이 올바르지 않습니다' : '파일 읽기 실패',
      })
    }

    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const confirmDelete = () => {
    if (sheet?.type === 'confirm') {
      deleteMutation.mutate(sheet.id)
    }
  }

  const menuFormat = sheet?.type === 'menu' ? formats?.find((f) => f.id === sheet.id) : undefined
  const isEmpty = !formats || formats.length === 0

  return (
    <div className="page page-with-header">
      <PageHeader
        title={t('tabFormat')}
        action={
          <Button
            variant="primary"
            className="btn-pill"
            onClick={() => createMutation.mutate()}
            disabled={createMutation.isPending}
          >
            {createMutation.isPending ? t('creating') : t('newFormat')}
          </Button>
        }
      />

      <ErrorBanner error={error} onRetry={() => refetch()} />

      {isLoading ? (
        <p>{t('loading')}</p>
      ) : (
        <div className="stack">
          <Button
            variant="secondary"
            onClick={() => fileInputRef.current?.click()}
            disabled={importMutation.isPending}
          >
            {importMutation.isPending ? t('importing') : t('import')}
          </Button>

          {isEmpty ? (
            <EmptyState
              message={t('formatsEmpty')}
              actionLabel={t('newFormat')}
              onAction={() => createMutation.mutate()}
            />
          ) : (
          <div className="format-list">
            {formats.map((format) => (
              <Card key={format.id}>
                <div className="row">
                  <img
                    className="format-thumb"
                    src={formatsApi.previewUrl(format.id)}
                    alt=""
                    onError={(e) => {
                      e.currentTarget.style.display = 'none'
                    }}
                  />

                  <div className="stack grow">
                    <div className="format-name">{format.name}</div>
                    {format.forkedFrom && (
                      <div className="entry-time">{t('forkedFrom')}: {format.forkedFrom.author}</div>
                    )}
                    <div className="entry-time">{formatDateTimeKo(format.updatedAt)}</div>
                  </div>

                  <button
                    type="button"
                    className="icon-btn"
                    aria-label={t('menu')}
                    onClick={() => setSheet({ type: 'menu', id: format.id })}
                  >
                    <IconKebab />
                  </button>
                </div>
              </Card>
            ))}
          </div>
          )}
        </div>
      )}

      <input
        ref={fileInputRef}
        className="visually-hidden"
        type="file"
        accept=".json"
        onChange={handleFileSelect}
        tabIndex={-1}
        aria-hidden="true"
      />

      <BottomSheet
        open={sheet?.type === 'menu'}
        title={menuFormat?.name ?? t('menu')}
        onClose={closeSheet}
      >
        <button
          type="button"
          className="sheet-item"
          onClick={() => {
            if (sheet?.type === 'menu') navigate(`/formats/${sheet.id}/edit`)
            closeSheet()
          }}
        >
          {t('edit')}
        </button>
        {sheet?.type === 'menu' && (
          <a
            className="sheet-item"
            href={formatsApi.exportUrl(sheet.id)}
            download
            onClick={closeSheet}
          >
            {t('export')}
          </a>
        )}
        <button
          type="button"
          className="sheet-item"
          onClick={() => {
            if (sheet?.type === 'menu') navigate(`/print-now?formatId=${sheet.id}`)
            closeSheet()
          }}
        >
          {t('tabPrintNow')}
        </button>
        <button
          type="button"
          className="sheet-item danger"
          onClick={() => {
            if (sheet?.type === 'menu') setSheet({ type: 'confirm', id: sheet.id })
          }}
        >
          {t('delete')}
        </button>
      </BottomSheet>

      <BottomSheet open={sheet?.type === 'confirm'} title={t('deleteFormatTitle')} onClose={closeSheet}>
        <p className="entry-time">{t('deleteFormatBody')}</p>
        <div className="row">
          <Button variant="secondary" onClick={closeSheet}>
            {t('cancel')}
          </Button>
          <Button variant="danger" onClick={confirmDelete} disabled={deleteMutation.isPending}>
            {deleteMutation.isPending ? t('deleting') : t('delete')}
          </Button>
        </div>
      </BottomSheet>

      <BottomSheet
        open={sheet?.type === 'notice'}
        title={sheet?.type === 'notice' ? sheet.title : ''}
        onClose={closeSheet}
      >
        <p>{sheet?.type === 'notice' ? sheet.message : null}</p>
        <Button variant="secondary" onClick={closeSheet}>
          {t('confirm')}
        </Button>
      </BottomSheet>
    </div>
  )
}
