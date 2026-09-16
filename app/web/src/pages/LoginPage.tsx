import { useState } from 'react'
import { useNavigate, useLocation, Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '../api/auth'
import type { LoginRequest } from '../types/auth'
import { ApiError } from '../types/problem'
import { ErrorBanner } from '../components/ErrorBanner'
import { Button } from '../components/Button'
import { useI18n } from '../i18n'

export function LoginPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const from = (location.state as any)?.from?.pathname || '/'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [generalError, setGeneralError] = useState<string | null>(null)

  const loginMutation = useMutation({
    mutationFn: (body: LoginRequest) => authApi.login(body),
    onSuccess: (user) => {
      queryClient.setQueryData(['auth', 'me'], user)
      // 관리자가 임시 비밀번호를 발급한 계정은 로그인 직후 비밀번호부터 바꾸게 한다.
      navigate(user.mustChangePassword ? '/account' : from)
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
      } else if (error instanceof ApiError && error.status === 401) {
        setGeneralError(t('unknownError'))
      } else {
        setGeneralError(
          error instanceof Error ? error.message : t('unknownError'),
        )
      }
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setFieldErrors({})
    setGeneralError(null)

    loginMutation.mutate({ email, password })
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

          <Button
            variant="primary"
            type="submit"
            disabled={loginMutation.isPending}
            className="auth-submit"
          >
            {loginMutation.isPending ? t('loggingIn') : t('login')}
          </Button>
        </form>

        <div className="auth-hint">
          <span>{t('loginHint')}</span>
          <Link to="/signup">{t('signup')}</Link>
        </div>
      </div>
    </div>
  )
}
