import { useQuery } from '@tanstack/react-query'
import { useNavigate, useLocation } from 'react-router-dom'
import { authApi } from '../api/auth'
import type { AuthResponse } from '../types/auth'
import { ApiError } from '../types/problem'

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated'

export function useAuthGuard() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const isPublicPath = pathname === '/login' || pathname === '/signup'

  const { data: user, isLoading, error } = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.me(),
    retry: false,
  })

  // 401 에러가 발생하면 비인증 상태로 간주
  const isUnauthenticated = error instanceof ApiError && error.status === 401

  // 공개 페이지 (로그인, 가입)
  if (isPublicPath) {
    // 이미 인증되어 있으면 포맷 페이지로
    if (!isLoading && user && !isUnauthenticated) {
      navigate('/')
    }
    return {
      status: 'loading' as const,
      user: null,
    }
  }

  // 인증 필요 페이지
  if (isLoading) {
    return {
      status: 'loading' as const,
      user: null,
    }
  }

  if (isUnauthenticated) {
    navigate('/login')
    return {
      status: 'unauthenticated' as const,
      user: null,
    }
  }

  if (error) {
    // 기타 에러 (네트워크 등)
    return {
      status: 'loading' as const,
      user: null,
    }
  }

  return {
    status: 'authenticated' as const,
    user: user || null,
  }
}

/**
 * 단순히 인증 상태만 필요할 때
 */
export function useAuthStatus() {
  const { data: user, isLoading, error } = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: () => authApi.me(),
    retry: false,
  })

  const isUnauthenticated = error instanceof ApiError && error.status === 401

  if (isLoading) {
    return { status: 'loading' as const, user: null }
  }

  if (isUnauthenticated) {
    return { status: 'unauthenticated' as const, user: null }
  }

  return {
    status: 'authenticated' as const,
    user: user as AuthResponse | null,
  }
}
