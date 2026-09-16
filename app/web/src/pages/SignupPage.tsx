import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { authApi } from '../api/auth'
import type { SignupRequest } from '../types/auth'
import { ApiError } from '../types/problem'
import { ErrorBanner } from '../components/ErrorBanner'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'

// 서버 검증(AuthController.HANDLE_PATTERN)과 정확히 맞춘다 — 하이픈으로 시작/끝나면 안 된다.
const HANDLE_REGEX = /^[a-z0-9](?:[a-z0-9-]{1,18}[a-z0-9])?$/

export function SignupPage() {
  const { t } = useI18n()
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [handle, setHandle] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [generalError, setGeneralError] = useState<string | null>(null)

  const signupMutation = useMutation({
    mutationFn: (body: SignupRequest) => authApi.signup(body),
    onSuccess: () => {
      navigate('/login')
    },
    onError: (error) => {
      setGeneralError(null)
      setFieldErrors({})

      if (error instanceof ApiError && error.status === 422) {
        // 필드 에러
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

  const isHandleValid = handle === '' || HANDLE_REGEX.test(handle)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFieldErrors({})
    setGeneralError(null)

    if (!isHandleValid) {
      setFieldErrors({ handle: t('handleHint') })
      return
    }

    signupMutation.mutate({ email, password, handle, displayName })
  }

  return (
    <div className="page page-auth">
      <div className="auth-container">
        <h1 className="auth-title">Haru-Paper</h1>

        <form onSubmit={handleSubmit} className="auth-form">
          <ErrorBanner error={generalError ? new Error(generalError) : null} />

          <div className="field form-group">
            <label htmlFor="email">{t('email')}</label>
            <input
              id="email"
              type="email"
              required
              autoFocus
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={!!fieldErrors.email}
              aria-describedby={fieldErrors.email ? 'email-error' : undefined}
            />
            {fieldErrors.email && (
              <div id="email-error" className="field-error">
                {fieldErrors.email}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="password">{t('password')}</label>
            <input
              id="password"
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={!!fieldErrors.password}
              aria-describedby={fieldErrors.password ? 'password-error' : undefined}
            />
            {fieldErrors.password && (
              <div id="password-error" className="field-error">
                {fieldErrors.password}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="handle">{t('handle')}</label>
            <input
              id="handle"
              type="text"
              required
              value={handle}
              onChange={(e) => setHandle(e.target.value.toLowerCase())}
              placeholder="my-handle"
              aria-invalid={!isHandleValid || !!fieldErrors.handle}
              aria-describedby={
                !isHandleValid ? 'handle-hint' : fieldErrors.handle ? 'handle-error' : undefined
              }
            />
            {!isHandleValid && (
              <div id="handle-hint" className="field-hint">
                {t('handleHint')}
              </div>
            )}
            {fieldErrors.handle && (
              <div id="handle-error" className="field-error">
                {fieldErrors.handle}
              </div>
            )}
          </div>

          <div className="field form-group">
            <label htmlFor="displayName">{t('displayName')}</label>
            <input
              id="displayName"
              type="text"
              required
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="John Doe"
              aria-invalid={!!fieldErrors.displayName}
              aria-describedby={fieldErrors.displayName ? 'displayName-error' : undefined}
            />
            {fieldErrors.displayName && (
              <div id="displayName-error" className="field-error">
                {fieldErrors.displayName}
              </div>
            )}
          </div>

          <Button
            variant="primary"
            type="submit"
            disabled={signupMutation.isPending}
            className="auth-submit"
          >
            {signupMutation.isPending ? t('signingUp') : t('signup')}
          </Button>
        </form>

        <div className="auth-hint">
          <span>{t('signupHint')}</span>
          <Link to="/login">{t('login')}</Link>
        </div>
      </div>
    </div>
  )
}
