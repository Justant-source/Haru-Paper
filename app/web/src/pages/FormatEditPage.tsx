// 위젯 그리드 편집 화면(.temp/07-위젯그리드-작업지시서.md 7.1절, 담당 A1).
// 드래그 편집기를 대체한다 — 안드로이드 홈 화면 위젯처럼 정해진 크기의 위젯을 4열 그리드에 넣는다.
//
// 문서 상태는 "로드/템플릿 데이터를 effect로 복사"하지 않고 draft(사용자가 실제로 편집한 값)만
// 상태로 들고, 편집 전에는 서버 응답·템플릿을 그대로 파생해서 쓴다(document = draft ?? 원본).
// 그래서 로드 완료 시점에 setState를 맞출 필요가 없고, 저장 직후 URL이 /formats/new에서
// /formats/:id/edit로 바뀌어도(같은 컴포넌트 인스턴스) draft가 이미 서버 응답을 담고 있어
// 화면이 깜빡이지 않는다. 위젯 추가·순서·삭제 조작은 widget-editor/useWidgetActions.ts로 뺐다.
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { formatsApi } from '../api/formats'
import { widgetsApi } from '../api/widgets'
import { buildTemplate, isTemplateName } from '../lib/format-templates'
import { SCHEMA_VERSION, type FormatDocument } from '../types/format'
import { missingRequiredFields } from '../types/widget'
import { ApiError } from '../types/problem'
import { ErrorBanner } from '../components/ErrorBanner'
import { Button } from '../components/Button'
import { IconPlus } from '../components/icons'
import { PlacementGrid } from '../widget-editor/PlacementGrid'
import { WidgetList } from '../widget-editor/WidgetList'
import { PreviewSection } from '../widget-editor/PreviewSection'
import { FormatEditHeader } from '../widget-editor/FormatEditHeader'
import { FormatEditLoadingState } from '../widget-editor/FormatEditLoadingState'
import { FormatEditSheets } from '../widget-editor/FormatEditSheets'
import { useWidgetActions } from '../widget-editor/useWidgetActions'
import { mapWidgetFieldErrors } from '../widget-editor/fieldErrors'
import { useI18n } from '../i18n'
import { track } from '../lib/analytics'

export function FormatEditPage() {
  const { t } = useI18n()
  const { id } = useParams<{ id?: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const catalogQuery = useQuery({
    queryKey: ['widget-catalog'],
    queryFn: () => widgetsApi.catalog(),
    staleTime: Infinity, // 위젯 카탈로그는 거의 안 바뀐다(작업지시서 07 7.1-1)
  })
  const catalog = catalogQuery.data

  const detailQuery = useQuery({
    queryKey: ['format-detail', id],
    queryFn: () => formatsApi.get(id as string),
    enabled: !!id,
  })

  const templateParam = searchParams.get('template')
  const templateName = isTemplateName(templateParam) ? templateParam : 'empty'
  // buildTemplate은 위젯마다 새 uuid를 만든다 — catalog·template 이름이 그대로면 재계산해도
  // 같은 값을 메모하도록 useMemo로 고정한다(아래 draft가 없을 때만 쓰인다).
  const templateDoc = useMemo(
    () => (!id && catalog ? buildTemplate(templateName, catalog) : null),
    [id, catalog, templateName],
  )

  const [draft, setDraft] = useState<FormatDocument | null>(null)
  const [dirty, setDirty] = useState(false)
  const [saveError, setSaveError] = useState<unknown>(null)
  const [saving, setSaving] = useState(false)
  const [leaveOpen, setLeaveOpen] = useState(false)
  const [addOpen, setAddOpen] = useState(false)
  const [settingsWidgetId, setSettingsWidgetId] = useState<string | null>(null)
  const [submittedIds, setSubmittedIds] = useState<string[]>([])

  // draft가 서버 응답을 담고 있으면(방금 저장 직후 등) 그걸 우선한다.
  const document = draft ?? (id ? (detailQuery.data?.document ?? null) : templateDoc)

  const dirtyRef = useRef(dirty)
  useEffect(() => {
    dirtyRef.current = dirty
  }, [dirty])

  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  const change = (next: FormatDocument) => {
    setDraft(next)
    setDirty(true)
  }

  const { handleAddWidget, handleMove, handleDelete, handleChangeSize, handleChangeProp } = useWidgetActions({
    document,
    catalog,
    change,
    settingsWidgetId,
    setSettingsWidgetId,
    closeAddSheet: () => setAddOpen(false),
  })

  const missingCount = useMemo(() => {
    if (!document || !catalog) return 0
    return document.widgets.reduce((n, w) => {
      const d = catalog.widgets.find((desc) => desc.type === w.type)
      return d && missingRequiredFields(d, w).length > 0 ? n + 1 : n
    }, 0)
  }, [document, catalog])

  // 미리보기는 위젯·스타일이 바뀔 때만 다시 그린다. 이름·설명 입력은 렌더 결과에 영향이 없는데
  // document를 그대로 넘기면 글자를 칠 때마다 서버 렌더(Chromium + 외부 데이터 조회)가 돈다.
  // 이름은 고정값으로 바꿔 보낸다 — 이름이 비어 있어도 미리보기가 422로 막히지 않게.
  const widgets = document?.widgets
  const style = document?.style
  const previewDocument = useMemo<FormatDocument | null>(
    () => (widgets ? { schemaVersion: SCHEMA_VERSION, meta: { name: 'preview' }, style, widgets } : null),
    [widgets, style],
  )

  const fieldErrors = useMemo(() => mapWidgetFieldErrors(saveError, submittedIds), [saveError, submittedIds])

  const requestLeave = () => {
    if (dirty) {
      setLeaveOpen(true)
      return
    }
    navigate('/')
  }
  const confirmLeave = () => {
    setLeaveOpen(false)
    setDirty(false)
    navigate('/')
  }

  const handleSave = async () => {
    if (!document) return
    if (!document.meta.name.trim()) {
      setSaveError(
        new ApiError({ status: 422, title: '입력 확인', errors: [{ path: 'meta.name', message: '이름을 입력하세요' }] }),
      )
      return
    }
    setSubmittedIds(document.widgets.map((w) => w.id))
    setSaving(true)
    setSaveError(null)
    try {
      if (id) {
        const detail = await formatsApi.update(id, document)
        setDraft(detail.document)
        setDirty(false)
        // 상세 캐시도 맞춰 둔다 — 안 그러면 이 화면에 다시 들어왔을 때 저장 전 내용이 잠깐 보이고,
        // 그 사이에 편집을 시작하면 옛 문서에서 draft가 만들어진다.
        queryClient.setQueryData(['format-detail', id], detail)
        queryClient.invalidateQueries({ queryKey: ['formats'] })
      } else {
        const detail = await formatsApi.create(document)
        track('format_create')
        setDraft(detail.document)
        setDirty(false)
        queryClient.setQueryData(['format-detail', detail.id], detail)
        queryClient.invalidateQueries({ queryKey: ['formats'] })
        navigate(`/formats/${detail.id}/edit`, { replace: true })
        return
      }
    } catch (e) {
      setSaveError(e)
    } finally {
      setSaving(false)
    }
  }

  if (catalogQuery.isError) {
    return (
      <FormatEditLoadingState
        onBack={() => navigate('/')}
        error={catalogQuery.error}
        onRetry={() => catalogQuery.refetch()}
        loadingLabel={t('loading')}
      />
    )
  }

  // draft가 이미 있으면(저장 직후 등) detailQuery가 배경에서 다시 돌아도 로드 화면을 보여주지 않는다.
  const stillLoading = catalogQuery.isLoading || (!!id && !draft && detailQuery.isLoading)
  const detailError = !draft && id ? detailQuery.error : null

  if (stillLoading || !document || !catalog) {
    return (
      <FormatEditLoadingState
        onBack={() => navigate('/')}
        error={detailError}
        onRetry={() => detailQuery.refetch()}
        loadingLabel={t('loading')}
      />
    )
  }

  const settingsWidget = document.widgets.find((w) => w.id === settingsWidgetId) ?? null
  const settingsDescriptor = settingsWidget ? catalog.widgets.find((w) => w.type === settingsWidget.type) : undefined
  const atMax = document.widgets.length >= catalog.grid.maxWidgets
  const canPreview = document.widgets.length > 0 && missingCount === 0
  const previewBlockedReason =
    document.widgets.length === 0
      ? '위젯을 추가하면 미리보기가 나타납니다'
      : missingCount > 0
        ? '필수값이 빈 위젯이 있습니다. 설정을 먼저 채워 주세요'
        : undefined

  const blockedReasons: string[] = []
  if (document.widgets.length === 0) blockedReasons.push('위젯을 1개 이상 추가하세요')
  if (missingCount > 0) blockedReasons.push(`설정이 필요한 위젯이 ${missingCount}개 있습니다`)

  const nameError = saveError instanceof ApiError ? saveError.fieldMessage('meta.name') : undefined

  return (
    <div className="format-edit-page">
      <FormatEditHeader
        dirty={dirty}
        saving={saving}
        saveDisabled={saving || blockedReasons.length > 0}
        onBack={requestLeave}
        onSave={handleSave}
      />

      <div className="widget-editor-body">
        {saveError ? <ErrorBanner error={saveError} /> : null}

        <div className="field">
          <label htmlFor="format-name">포맷 이름</label>
          <input
            id="format-name"
            type="text"
            placeholder="포맷 이름"
            value={document.meta.name}
            onChange={(e) => change({ ...document, meta: { ...document.meta, name: e.target.value } })}
            className="format-edit-title"
            aria-invalid={!!nameError}
          />
          {nameError ? <span className="field-error">{nameError}</span> : null}
        </div>

        <details className="format-edit-description-details">
          <summary>설명 (선택)</summary>
          <textarea
            placeholder="설명"
            value={document.meta.description ?? ''}
            onChange={(e) => change({ ...document, meta: { ...document.meta, description: e.target.value } })}
            className="format-edit-description"
            rows={2}
          />
        </details>

        {blockedReasons.length > 0 ? (
          <div className="format-edit-notice">
            {blockedReasons.map((r) => (
              <p key={r}>{r}</p>
            ))}
          </div>
        ) : null}

        <PlacementGrid document={document} catalog={catalog} fieldErrors={fieldErrors} onSelect={setSettingsWidgetId} />

        <WidgetList
          document={document}
          catalog={catalog}
          fieldErrors={fieldErrors}
          onOpenSettings={setSettingsWidgetId}
          onMoveUp={(wid) => handleMove(wid, -1)}
          onMoveDown={(wid) => handleMove(wid, 1)}
          onDelete={handleDelete}
        />

        <button type="button" className="add-widget-btn" onClick={() => setAddOpen(true)} disabled={atMax}>
          <IconPlus />
          <span>위젯 추가</span>
        </button>

        <PreviewSection
          document={previewDocument ?? document}
          canPreview={canPreview}
          blockedReason={previewBlockedReason}
        />

        {id ? (
          <div className="format-edit-actions">
            <Button variant="secondary" onClick={() => navigate(`/schedules?formatId=${id}`)}>
              이 포맷으로 예약하기
            </Button>
            <Button variant="secondary" onClick={() => navigate(`/print-now?formatId=${id}`)}>
              {t('tabPrintNow')}
            </Button>
          </div>
        ) : null}
      </div>

      <FormatEditSheets
        catalog={catalog}
        atMax={atMax}
        addOpen={addOpen}
        onCloseAdd={() => setAddOpen(false)}
        onAddWidget={handleAddWidget}
        settingsWidget={settingsWidget}
        settingsDescriptor={settingsDescriptor}
        settingsFieldErrors={settingsWidget ? fieldErrors[settingsWidget.id] : undefined}
        onCloseSettings={() => setSettingsWidgetId(null)}
        onChangeSize={(size) => settingsWidget && handleChangeSize(settingsWidget.id, size)}
        onChangeProp={(key, value) => settingsWidget && handleChangeProp(settingsWidget.id, key, value)}
        onDeleteWidget={() => {
          if (settingsWidget) handleDelete(settingsWidget.id)
        }}
        leaveOpen={leaveOpen}
        onCloseLeave={() => setLeaveOpen(false)}
        onConfirmLeave={confirmLeave}
      />
    </div>
  )
}
