import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '../api/auth'
import { accountApi } from '../api/account'
import type { AccountUpdateRequest } from '../types/auth'
import { ErrorBanner } from '../components/ErrorBanner'
import { PageHeader } from '../components/PageHeader'
import { Card } from '../components/Card'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'
import { ApiError } from '../types/problem'

export function AccountPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [displayName, setDisplayName] = useState('')
  const [bio, setBio] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [generalError, setGeneralError] = useState<string | null>(null)

  const { data: user, isLoading, error } = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.me(),
  })

  useEffect(() => {
    if (user) {
      setDisplayName(user.displayName || '')
      setBio(user.bio || '')
    }
  }, [user])

  const updateMutation = useMutation({
    mutationFn: (body: AccountUpdateRequest) => accountApi.update(body),
    onSuccess: (updated) => {
      queryClient.setQueryData(['auth', 'me'], updated)
      setFieldErrors({})
      setGeneralError(null)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
    },
    onError: (error) => {
      setFieldErrors({})
      setGeneralError(null)

      if (error instanceof ApiError && error.status === 422) {
        const newErrors: Record<string, string> = {}
        if (error.problem.errors) {
          for (const err of error.problem.errors) {
            newErrors[err.path] = err.message
          }
        }
        setFieldErrors(newErrors)
      } else {
        setGeneralError(
          error instanceof Error ? error.message : t('unknownError'),
        )
      }
    },
  })

  const logoutMutation = useMutation({
    mutationFn: () => authApi.logout(),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['auth', 'me'] })
      navigate('/login')
    },
    onError: (error) => {
      setGeneralError(
        error instanceof Error ? error.message : t('unknownError'),
      )
    },
  })

  const handleSaveProfile = () => {
    setFieldErrors({})
    setGeneralError(null)

    updateMutation.mutate({ displayName, bio })
  }

  const handleChangePassword = () => {
    setFieldErrors({})
    setGeneralError(null)

    if (newPassword !== confirmPassword) {
      setFieldErrors({ newPassword: t('unknownError') })
      return
    }

    updateMutation.mutate({ currentPassword, newPassword })
  }

  const handleLogout = () => {
    logoutMutation.mutate()
  }

  if (isLoading) {
    return (
      <div className="page page-with-header">
        <PageHeader title={t('account')} />
        <p>{t('loading')}</p>
      </div>
    )
  }

  return (
    <div className="page page-with-header">
      <PageHeader title={t('account')} />

      <ErrorBanner error={error} />
      <ErrorBanner error={generalError ? new Error(generalError) : null} />
      {user?.mustChangePassword && (
        <div className="banner banner-warn" role="alert">
          관리자가 발급한 임시 비밀번호로 로그인했습니다. 아래에서 새 비밀번호로 바꿔주세요.
        </div>
      )}

      <div className="stack">
        <Card>
          <h2 className="sheet-title">{t('displayName')}</h2>

          <div className="field form-group">
            <label htmlFor="displayName">{t('displayName')}</label>
            <input
              id="displayName"
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              aria-invalid={!!fieldErrors.displayName}
              aria-describedby={fieldErrors.displayName ? 'displayName-error' : undefined}
            />
            {fieldErrors.displayName && (
              <div id="displayName-error" className="field-error">
                {fieldErrors.displayName}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="bio">{t('bio')}</label>
            <textarea
              id="bio"
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              aria-invalid={!!fieldErrors.bio}
              aria-describedby={fieldErrors.bio ? 'bio-error' : undefined}
              rows={3}
            />
            {fieldErrors.bio && (
              <div id="bio-error" className="field-error">
                {fieldErrors.bio}
              </div>
            )}
          </div>

          <Button
            variant="primary"
            onClick={handleSaveProfile}
            disabled={updateMutation.isPending}
          >
            {updateMutation.isPending ? t('updating') : t('save')}
          </Button>
        </Card>

        <Card>
          <h2 className="sheet-title">{t('password')}</h2>

          <div className="field form-group">
            <label htmlFor="currentPassword">{t('currentPassword')}</label>
            <input
              id="currentPassword"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              aria-invalid={!!fieldErrors.currentPassword}
              aria-describedby={fieldErrors.currentPassword ? 'currentPassword-error' : undefined}
            />
            {fieldErrors.currentPassword && (
              <div id="currentPassword-error" className="field-error">
                {fieldErrors.currentPassword}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="newPassword">{t('newPassword')}</label>
            <input
              id="newPassword"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              aria-invalid={!!fieldErrors.newPassword}
              aria-describedby={fieldErrors.newPassword ? 'newPassword-error' : undefined}
            />
            {fieldErrors.newPassword && (
              <div id="newPassword-error" className="field-error">
                {fieldErrors.newPassword}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="confirmPassword">비밀번호 확인</label>
            <input
              id="confirmPassword"
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </div>

          <Button
            variant="primary"
            onClick={handleChangePassword}
            disabled={updateMutation.isPending}
          >
            {updateMutation.isPending ? t('updating') : t('save')}
          </Button>
        </Card>

        <Card>
          <h2 className="sheet-title">{t('logout')}</h2>
          <p>로그아웃하시겠습니까?</p>
          <Button
            variant="danger"
            onClick={handleLogout}
            disabled={logoutMutation.isPending}
          >
            {logoutMutation.isPending ? t('loggingOut') : t('logout')}
          </Button>
        </Card>
      </div>
    </div>
  )
}
