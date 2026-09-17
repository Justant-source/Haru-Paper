import { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { schedulesApi } from '../api/schedules'
import { formatsApi } from '../api/formats'
import type { Schedule, ScheduleCreateRequest, ScheduleType, DayOfWeekCode } from '../types/schedule'
import { DAY_LABELS_KO, ALL_DAYS } from '../types/schedule'
import { ApiError } from '../types/problem'
import { formatDateKo, formatDateTimeKo, nowKstTimeString, todayKstDateString } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/Button'
import { Card } from '../components/Card'
import { BottomSheet } from '../components/BottomSheet'
import { Toggle } from '../components/Toggle'
import { useI18n } from '../i18n'
import { track } from '../lib/analytics'
import '../styles/format-flow.css'

const WEEKDAYS: DayOfWeekCode[] = ['MON', 'TUE', 'WED', 'THU', 'FRI']
const WEEKEND: DayOfWeekCode[] = ['SAT', 'SUN']

const QUICK_DAY_SETS: { label: string; days: DayOfWeekCode[] }[] = [
  { label: '매일', days: ALL_DAYS },
  { label: '평일', days: WEEKDAYS },
  { label: '주말', days: WEEKEND },
]

/** 서버로 보낼 때는 항상 MON..SUN 순서로 정렬한다(토글 클릭 순서와 무관하게). */
function sortDays(days: DayOfWeekCode[]): DayOfWeekCode[] {
  return ALL_DAYS.filter((d) => days.includes(d))
}

function sameDaySet(a: DayOfWeekCode[], b: DayOfWeekCode[]): boolean {
  return a.length === b.length && b.every((d) => a.includes(d))
}

export function ScheduleListPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const [showForm, setShowForm] = useState(false)
  const [editingSchedule, setEditingSchedule] = useState<Schedule | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)
  const [toggleError, setToggleError] = useState<unknown>(null)
  const [lastToggle, setLastToggle] = useState<{ id: string; enabled: boolean } | null>(null)
  const [noFormatHint, setNoFormatHint] = useState(false)

  const [formType, setFormType] = useState<ScheduleType>('recurring')
  const [selectedDays, setSelectedDays] = useState<DayOfWeekCode[]>(ALL_DAYS)
  const [formDate, setFormDate] = useState(todayKstDateString())
  const [formTime, setFormTime] = useState('07:00')
  const [formFormatId, setFormFormatId] = useState<string>('')
  const [formEnabled, setFormEnabled] = useState(true)

  const { data: schedules, isLoading, error, refetch } = useQuery({
    queryKey: ['schedules'],
    queryFn: () => schedulesApi.list(),
  })

  const { data: formats, isFetching: formatsFetching } = useQuery({
    queryKey: ['formats'],
    queryFn: () => formatsApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: (body: ScheduleCreateRequest) => schedulesApi.create(body),
    onSuccess: () => {
      track('schedule_save', { mode: 'create' })
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      resetForm()
      setShowForm(false)
    },
    onError: (err) => {
      const message =
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title ?? '저장 실패') : '저장 실패'
      setFormError(message)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, body }: { id: string; body: ScheduleCreateRequest }) =>
      schedulesApi.update(id, body),
    onSuccess: () => {
      track('schedule_save', { mode: 'update' })
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      resetForm()
      setShowForm(false)
      setEditingSchedule(null)
    },
    onError: (err) => {
      const message =
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title ?? '저장 실패') : '저장 실패'
      setFormError(message)
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => {
      const schedule = queryClient.getQueryData<Schedule[]>(['schedules'])?.find((s) => s.id === id)
      if (!schedule) return Promise.reject(new Error('예약을 찾을 수 없습니다'))
      return schedulesApi.update(id, {
        formatId: schedule.formatId,
        type: schedule.type,
        daysOfWeek: schedule.daysOfWeek ?? undefined,
        date: schedule.date ?? undefined,
        time: schedule.time,
        enabled,
      })
    },
    onMutate: async ({ id, enabled }) => {
      await queryClient.cancelQueries({ queryKey: ['schedules'] })
      const previous = queryClient.getQueryData<Schedule[]>(['schedules'])
      queryClient.setQueryData<Schedule[]>(['schedules'], (old) =>
        old?.map((s) => (s.id === id ? { ...s, enabled } : s)),
      )
      setToggleError(null)
      return { previous }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      setToggleError(null)
    },
    onError: (err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['schedules'], context.previous)
      }
      setToggleError(err)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => schedulesApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      setDeleteConfirmId(null)
    },
  })

  const resetForm = () => {
    setFormType('recurring')
    setSelectedDays(ALL_DAYS)
    setFormDate(todayKstDateString())
    setFormTime('07:00')
    setFormFormatId('')
    setFormEnabled(true)
    setFormError(null)
  }

  const closeForm = () => {
    setShowForm(false)
    setEditingSchedule(null)
    resetForm()
  }

  const handleNewSchedule = () => {
    if (!formats || formats.length === 0) {
      setNoFormatHint(true)
      return
    }
    setNoFormatHint(false)
    resetForm()
    setEditingSchedule(null)
    setShowForm(true)
  }

  // 포맷 목록에서 "예약하기"로 들어오면(?formatId=) 그 포맷이 선택된 채로 새 예약 시트를 연다.
  // 쿼리는 열고 나서 곧바로 지운다(뒤로 가기·새로고침 때 다시 열리지 않게). 없는 id는 조용히 무시.
  useEffect(() => {
    const formatId = searchParams.get('formatId')
    if (!formatId || !formats) return

    const found = formats.some((f) => f.id === formatId)
    // 편집 화면에서 방금 만든 포맷은 옛 캐시 목록에 없을 수 있다 — 목록을 다시 받는 중이면 기다린다.
    // (안 그러면 stale 목록으로 판단해 쿼리만 지우고 시트를 안 연다, 2026-09-18 실측)
    if (!found && formatsFetching) return

    if (found) {
      resetForm()
      setEditingSchedule(null)
      setFormFormatId(formatId)
      setNoFormatHint(false)
      setShowForm(true)
    }

    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.delete('formatId')
        return next
      },
      { replace: true },
    )
    // formats가 로드된 뒤 한 번만 처리한다 — searchParams 변경으로 다시 돌지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [formats, formatsFetching])

  const handleEditSchedule = (schedule: Schedule) => {
    if (!formats || formats.length === 0) {
      setFormError('포맷을 찾을 수 없습니다')
      return
    }
    setNoFormatHint(false)
    setFormType(schedule.type)
    if (schedule.type === 'recurring' && schedule.daysOfWeek) {
      setSelectedDays(schedule.daysOfWeek)
    } else {
      setSelectedDays([])
    }
    if (schedule.date) {
      setFormDate(schedule.date)
    }
    setFormTime(schedule.time)
    setFormFormatId(schedule.formatId)
    setFormEnabled(schedule.enabled)
    setFormError(null)
    setEditingSchedule(schedule)
    setShowForm(true)
  }

  const handleSaveSchedule = async () => {
    if (!formFormatId) {
      setFormError('포맷을 선택해주세요')
      return
    }

    if (formType === 'recurring' && selectedDays.length === 0) {
      setFormError('최소 하나의 요일을 선택해주세요')
      return
    }

    const body: ScheduleCreateRequest = {
      formatId: formFormatId,
      type: formType,
      time: formTime,
      enabled: formEnabled,
    }

    if (formType === 'recurring') {
      body.daysOfWeek = sortDays(selectedDays)
    } else {
      body.date = formDate
    }

    if (editingSchedule) {
      updateMutation.mutate({ id: editingSchedule.id, body })
    } else {
      createMutation.mutate(body)
    }
  }

  const handleToggleEnabled = (schedule: Schedule, enabled: boolean) => {
    const next = { id: schedule.id, enabled }
    setLastToggle(next)
    toggleMutation.mutate(next)
  }

  const confirmDelete = () => {
    if (deleteConfirmId) {
      deleteMutation.mutate(deleteConfirmId)
    }
  }

  const isSaveDisabled = !formFormatId || (formType === 'recurring' && selectedDays.length === 0)
  const formatMissing = Boolean(formError) && !formFormatId
  const daysMissing = Boolean(formError) && formType === 'recurring' && selectedDays.length === 0

  const formatName = (formatId: string) => {
    return formats?.find((f) => f.id === formatId)?.name ?? '(삭제된 포맷)'
  }

  const onceDateLabel = (date: string) => formatDateKo(`${date}T12:00:00+09:00`)

  const header = (
    <PageHeader
      title={t('tabSchedules')}
      action={
        <Button variant="primary" className="btn-pill" onClick={handleNewSchedule}>
          {t('newSchedule')}
        </Button>
      }
    />
  )

  const banners = (
    <>
      {error && <ErrorBanner error={error} onRetry={refetch} />}
      {toggleError && (
        <ErrorBanner
          error={toggleError}
          onRetry={lastToggle ? () => toggleMutation.mutate(lastToggle) : undefined}
        />
      )}
    </>
  )

  const formSheet = (
    <BottomSheet open={showForm} title={editingSchedule ? '예약 편집' : '새 예약'} onClose={closeForm}>
      {formError && (
        <p className="field-error" role="alert">
          {formError}
        </p>
      )}

      <div className="field">
        <span>종류</span>
        <div className="radio-group">
          <label>
            <input
              type="radio"
              value="recurring"
              checked={formType === 'recurring'}
              onChange={(e) => setFormType(e.target.value as ScheduleType)}
            />
            반복
          </label>
          <label>
            <input
              type="radio"
              value="once"
              checked={formType === 'once'}
              onChange={(e) => setFormType(e.target.value as ScheduleType)}
            />
            일회성
          </label>
        </div>
      </div>

      {formType === 'recurring' && (
        <div className="field">
          <span id="schedule-days-label">요일 선택</span>
          <div className="quick-day-chips" role="group" aria-label="빠른 선택">
            {QUICK_DAY_SETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                className={`quick-day-chip ${sameDaySet(selectedDays, preset.days) ? 'active' : ''}`}
                aria-pressed={sameDaySet(selectedDays, preset.days)}
                onClick={() => setSelectedDays(preset.days)}
              >
                {preset.label}
              </button>
            ))}
          </div>
          <div className="day-toggles" role="group" aria-labelledby="schedule-days-label">
            {ALL_DAYS.map((day) => (
              <button
                key={day}
                type="button"
                className={`day-toggle ${selectedDays.includes(day) ? 'active' : ''}`}
                aria-pressed={selectedDays.includes(day)}
                aria-invalid={daysMissing || undefined}
                aria-describedby={daysMissing ? 'schedule-days-error' : undefined}
                onClick={() => {
                  if (selectedDays.includes(day)) {
                    setSelectedDays(selectedDays.filter((d) => d !== day))
                  } else {
                    setSelectedDays([...selectedDays, day])
                  }
                }}
              >
                {DAY_LABELS_KO[day]}
              </button>
            ))}
          </div>
          {daysMissing && (
            <p id="schedule-days-error" className="field-error">
              최소 하나의 요일을 선택해주세요
            </p>
          )}
        </div>
      )}

      {formType === 'once' && (
        <div className="field">
          <label htmlFor="form-date">날짜</label>
          <input
            id="form-date"
            type="date"
            value={formDate}
            onChange={(e) => setFormDate(e.target.value)}
            min={todayKstDateString()}
          />
        </div>
      )}

      <div className="field">
        <label htmlFor="form-time">시각</label>
          <input
            id="form-time"
            type="time"
            value={formTime}
            onChange={(e) => setFormTime(e.target.value)}
            min={formType === 'once' && formDate === todayKstDateString() ? nowKstTimeString() : undefined}
          />
      </div>

      <div className="field">
        <label htmlFor="form-format">포맷</label>
        <select
          id="form-format"
          value={formFormatId}
          onChange={(e) => setFormFormatId(e.target.value)}
          aria-invalid={formatMissing || undefined}
          aria-describedby={formatMissing ? 'schedule-format-error' : undefined}
        >
          <option value="">선택해주세요</option>
          {formats?.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
        {formatMissing && (
          <p id="schedule-format-error" className="field-error">
            포맷을 선택해주세요
          </p>
        )}
      </div>

      <div className="field">
        <span>켜짐</span>
        <Toggle checked={formEnabled} onChange={setFormEnabled} label="켜짐" />
      </div>

      <div className="row">
        <Button variant="secondary" onClick={closeForm}>
          취소
        </Button>
        <Button
          variant="primary"
          onClick={handleSaveSchedule}
          disabled={isSaveDisabled || createMutation.isPending || updateMutation.isPending}
        >
          저장
        </Button>
      </div>
    </BottomSheet>
  )

  const deleteSheet = (
    <BottomSheet
      open={deleteConfirmId !== null}
      title="예약을 삭제하시겠습니까?"
      onClose={() => setDeleteConfirmId(null)}
    >
      <div className="row">
        <Button variant="secondary" onClick={() => setDeleteConfirmId(null)}>
          취소
        </Button>
        <Button variant="danger" onClick={confirmDelete} disabled={deleteMutation.isPending}>
          삭제
        </Button>
      </div>
    </BottomSheet>
  )

  if (!isLoading && formats && formats.length === 0 && noFormatHint) {
    return (
      <div className="page page-with-header">
        {header}
        {banners}
        <EmptyState
          message={t('needFormatFirst')}
          actionLabel="포맷 목록으로"
          onAction={() => navigate('/')}
        />
        {deleteSheet}
      </div>
    )
  }

  if (!isLoading && schedules && schedules.length === 0 && !showForm) {
    return (
      <div className="page page-with-header">
        {header}
        {banners}
        <EmptyState
          message={t('schedulesEmpty')}
          actionLabel="새 예약"
          onAction={handleNewSchedule}
        />
        {formSheet}
        {deleteSheet}
      </div>
    )
  }

  return (
    <div className="page page-with-header">
      {header}
      {banners}

      {isLoading ? (
        <p>로딩 중...</p>
      ) : schedules && schedules.length > 0 ? (
        <div className="schedule-list">
          {schedules.map((schedule) => (
            <Card key={schedule.id} className="schedule-item">
              <div className="schedule-main">
                <img
                  className="schedule-thumb"
                  src={formatsApi.previewUrl(schedule.formatId)}
                  alt=""
                  onError={(e) => {
                    e.currentTarget.style.display = 'none'
                  }}
                />
                <div className="schedule-info">
                  <div className="schedule-time">{schedule.time}</div>
                  <div className="schedule-days">
                    {schedule.type === 'recurring' && schedule.daysOfWeek
                      ? schedule.daysOfWeek.map((d) => DAY_LABELS_KO[d]).join(' ')
                      : schedule.date
                        ? onceDateLabel(schedule.date)
                        : null}
                  </div>
                  {formats?.some((f) => f.id === schedule.formatId) ? (
                    <button
                      type="button"
                      className="schedule-format-link"
                      onClick={() => navigate(`/formats/${schedule.formatId}/edit`)}
                    >
                      {formatName(schedule.formatId)}
                    </button>
                  ) : (
                    <div className="schedule-format">{formatName(schedule.formatId)}</div>
                  )}
                  {schedule.nextOccurrenceAt && (
                    <div className="schedule-next">다음: {formatDateTimeKo(schedule.nextOccurrenceAt)}</div>
                  )}
                </div>
              </div>
              <div className="schedule-controls">
                <Toggle
                  checked={schedule.enabled}
                  label={schedule.enabled ? '켜짐' : '꺼짐'}
                  disabled={toggleMutation.isPending && toggleMutation.variables?.id === schedule.id}
                  onChange={(next) => handleToggleEnabled(schedule, next)}
                />
                <Button variant="ghost" onClick={() => handleEditSchedule(schedule)}>
                  편집
                </Button>
                <Button variant="ghost" onClick={() => setDeleteConfirmId(schedule.id)}>
                  삭제
                </Button>
              </div>
            </Card>
          ))}
        </div>
      ) : null}

      <div className="schedule-guide">
        <p>Pi는 약 30초마다 서버에서 예약을 받아옵니다. 변경 직후 바로 반영되지 않을 수 있습니다.</p>
        <p>
          예약 시각에 인터넷이 끊겨도 Pi가 마지막으로 받은 카드로 인쇄합니다. 이 경우 날짜·날씨는 마지막 렌더
          값일 수 있습니다.
        </p>
      </div>

      {formSheet}
      {deleteSheet}
    </div>
  )
}
