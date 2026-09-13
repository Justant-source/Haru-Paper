import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { schedulesApi } from '../api/schedules'
import { formatsApi } from '../api/formats'
import type { Schedule, ScheduleCreateRequest, ScheduleType, DayOfWeekCode } from '../types/schedule'
import { DAY_LABELS_KO, ALL_DAYS } from '../types/schedule'
import { ApiError } from '../types/problem'
import { formatDateTimeKo, todayKstDateString } from '../lib/date'
import { ErrorBanner } from '../components/ErrorBanner'
import { EmptyState } from '../components/EmptyState'

export function ScheduleListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // UI state
  const [showForm, setShowForm] = useState(false)
  const [editingSchedule, setEditingSchedule] = useState<Schedule | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)
  const [toggleErrorId, setToggleErrorId] = useState<string | null>(null)

  // Form state
  const [formType, setFormType] = useState<ScheduleType>('recurring')
  const [selectedDays, setSelectedDays] = useState<DayOfWeekCode[]>(['MON', 'TUE', 'WED', 'THU', 'FRI'])
  const [formDate, setFormDate] = useState(todayKstDateString())
  const [formTime, setFormTime] = useState('07:00')
  const [formFormatId, setFormFormatId] = useState<string>('')
  const [formEnabled, setFormEnabled] = useState(true)

  // Queries
  const { data: schedules, isLoading, error, refetch } = useQuery({
    queryKey: ['schedules'],
    queryFn: () => schedulesApi.list(),
  })

  const { data: formats } = useQuery({
    queryKey: ['formats'],
    queryFn: () => formatsApi.list(),
  })

  // Mutations
  const createMutation = useMutation({
    mutationFn: (body: ScheduleCreateRequest) => schedulesApi.create(body),
    onSuccess: () => {
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
      const schedule = schedules?.find((s) => s.id === id)
      if (!schedule) return Promise.reject()
      return schedulesApi.update(id, {
        formatId: schedule.formatId,
        type: schedule.type,
        daysOfWeek: schedule.daysOfWeek ?? undefined,
        date: schedule.date ?? undefined,
        time: schedule.time,
        enabled,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      setToggleErrorId(null)
    },
    onError: (_err, { id }) => {
      setToggleErrorId(id)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => schedulesApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      setDeleteConfirmId(null)
    },
  })

  // Handlers
  const resetForm = () => {
    setFormType('recurring')
    setSelectedDays(['MON', 'TUE', 'WED', 'THU', 'FRI'])
    setFormDate(todayKstDateString())
    setFormTime('07:00')
    setFormFormatId('')
    setFormEnabled(true)
    setFormError(null)
  }

  const handleNewSchedule = () => {
    if (!formats || formats.length === 0) {
      setFormError('먼저 포맷을 만드세요')
      return
    }
    resetForm()
    setEditingSchedule(null)
    setShowForm(true)
  }

  const handleEditSchedule = (schedule: Schedule) => {
    if (!formats || formats.length === 0) {
      setFormError('포맷을 찾을 수 없습니다')
      return
    }
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
    // Validation
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
      body.daysOfWeek = selectedDays
    } else {
      body.date = formDate
    }

    if (editingSchedule) {
      updateMutation.mutate({ id: editingSchedule.id, body })
    } else {
      createMutation.mutate(body)
    }
  }

  const handleToggleEnabled = (schedule: Schedule) => {
    toggleMutation.mutate({ id: schedule.id, enabled: !schedule.enabled })
  }

  const handleDeleteClick = (id: string) => {
    setDeleteConfirmId(id)
  }

  const confirmDelete = () => {
    if (deleteConfirmId) {
      deleteMutation.mutate(deleteConfirmId)
    }
  }

  // Rendering
  const isSaveDisabled = !formFormatId || (formType === 'recurring' && selectedDays.length === 0)

  const formatName = (formatId: string) => {
    return formats?.find((f) => f.id === formatId)?.name ?? '(삭제된 포맷)'
  }

  // Empty state: no formats
  if (!isLoading && formats && formats.length === 0 && showForm) {
    return (
      <div className="page">
        <h1>예약</h1>
        <EmptyState
          message="먼저 포맷을 만드세요"
          actionLabel="포맷 목록으로"
          onAction={() => navigate('/')}
        />
      </div>
    )
  }

  // Empty state: no schedules
  if (!isLoading && schedules && schedules.length === 0 && !showForm) {
    return (
      <div className="page">
        <h1>예약</h1>
        <EmptyState
          message="예약이 없습니다. 매일 아침 받을 카드를 예약하세요."
          actionLabel="새 예약"
          onAction={handleNewSchedule}
        />
      </div>
    )
  }

  return (
    <div className="page">
      <h1>예약</h1>

      {error && <ErrorBanner error={error} onRetry={refetch} />}

      {showForm ? (
        <div className="schedule-form-container">
          <div className="schedule-form">
            <h2>{editingSchedule ? '예약 편집' : '새 예약'}</h2>

            {formError && <div className="form-error">{formError}</div>}

            {/* Type selector */}
            <div className="form-group">
              <label>종류</label>
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

            {/* Days of week (recurring only) */}
            {formType === 'recurring' && (
              <div className="form-group">
                <label>요일 선택</label>
                <div className="day-toggles">
                  {ALL_DAYS.map((day) => (
                    <button
                      key={day}
                      type="button"
                      className={`day-toggle ${selectedDays.includes(day) ? 'active' : ''}`}
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
              </div>
            )}

            {/* Date (once only) */}
            {formType === 'once' && (
              <div className="form-group">
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

            {/* Time */}
            <div className="form-group">
              <label htmlFor="form-time">시각</label>
              <input
                id="form-time"
                type="time"
                value={formTime}
                onChange={(e) => setFormTime(e.target.value)}
              />
            </div>

            {/* Format selection */}
            <div className="form-group">
              <label htmlFor="form-format">포맷</label>
              <select value={formFormatId} onChange={(e) => setFormFormatId(e.target.value)}>
                <option value="">선택해주세요</option>
                {formats?.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.name}
                  </option>
                ))}
              </select>
            </div>

            {/* Enabled toggle */}
            <div className="form-group">
              <label>
                <input
                  type="checkbox"
                  checked={formEnabled}
                  onChange={(e) => setFormEnabled(e.target.checked)}
                />
                켜짐
              </label>
            </div>

            {/* Buttons */}
            <div className="form-actions">
              <button
                type="button"
                onClick={() => {
                  setShowForm(false)
                  setEditingSchedule(null)
                  resetForm()
                }}
              >
                취소
              </button>
              <button
                type="button"
                onClick={handleSaveSchedule}
                disabled={isSaveDisabled}
                className="primary"
              >
                저장
              </button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {/* Schedules list */}
          {isLoading ? (
            <p>로딩 중...</p>
          ) : schedules && schedules.length > 0 ? (
            <div className="schedule-list">
              {schedules.map((schedule) => (
                <div key={schedule.id} className="schedule-item">
                  <div className="schedule-info">
                    <div className="schedule-time">{schedule.time}</div>
                    <div className="schedule-days">
                      {schedule.type === 'recurring' && schedule.daysOfWeek
                        ? schedule.daysOfWeek.map((d) => DAY_LABELS_KO[d]).join(' ')
                        : schedule.date}
                    </div>
                    <div className="schedule-format">{formatName(schedule.formatId)}</div>
                    {schedule.nextOccurrenceAt && (
                      <div className="schedule-next">다음: {formatDateTimeKo(schedule.nextOccurrenceAt)}</div>
                    )}
                  </div>
                  <div className="schedule-controls">
                    <label className="checkbox-toggle">
                      <input
                        type="checkbox"
                        checked={schedule.enabled}
                        onChange={() => handleToggleEnabled(schedule)}
                        disabled={toggleErrorId === schedule.id}
                      />
                      <span></span>
                    </label>
                    <button
                      type="button"
                      className="icon-button"
                      onClick={() => handleEditSchedule(schedule)}
                      title="편집"
                    >
                      ✎
                    </button>
                    <button
                      type="button"
                      className="icon-button danger"
                      onClick={() => handleDeleteClick(schedule.id)}
                      title="삭제"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}

          {/* New schedule button */}
          {schedules && schedules.length > 0 && (
            <button type="button" onClick={handleNewSchedule} className="new-button">
              새 예약
            </button>
          )}

          {/* Guide text */}
          <div className="schedule-guide">
            <p>Pi는 약 30초마다 서버에서 예약을 받아옵니다. 변경 직후 바로 반영되지 않을 수 있습니다.</p>
            <p>예약 시각에 인터넷이 끊겨도 Pi가 마지막으로 받은 카드로 인쇄합니다. 이 경우 날짜·날씨는 마지막 렌더 값일 수 있습니다.</p>
          </div>
        </>
      )}

      {/* Delete confirmation dialog */}
      {deleteConfirmId && (
        <div className="modal-overlay" onClick={() => setDeleteConfirmId(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>예약을 삭제하시겠습니까?</h3>
            <div className="modal-actions">
              <button type="button" onClick={() => setDeleteConfirmId(null)}>
                취소
              </button>
              <button
                type="button"
                onClick={confirmDelete}
                className="danger"
                disabled={deleteMutation.isPending}
              >
                삭제
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
