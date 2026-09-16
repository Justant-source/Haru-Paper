import { ApiError, type ProblemDetail } from '../types/problem'
import { getXsrfToken } from '../lib/xsrf'

/**
 * 공통 fetch 래퍼. 코드에서 API 주소를 하드코딩하지 않고 항상 상대 경로 /api/...를 쓴다
 * (docs/app/web.md 3절). 서버 오류(application/problem+json)는 ApiError로 변환해 던진다.
 * 네트워크 자체가 끊긴 경우(Tailscale 오프라인 등)는 TypeError를 그대로 던진다 —
 * 화면단 공통 오류 배너가 이를 구분해서 보여준다(docs/app/screens.md 공통절).
 *
 * 인증: 세션 쿠키는 credentials: 'include'로 자동 포함.
 * CSRF: POST/PUT/PATCH/DELETE는 X-XSRF-TOKEN 헤더가 필요 (쿠키에서 읽음).
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    ...(init?.body && !(init.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
    Accept: 'application/json',
  }

  // init?.headers를 문자열 헤더로 merge
  if (init?.headers) {
    if (init.headers instanceof Headers) {
      init.headers.forEach((value, key) => {
        headers[key] = value
      })
    } else if (Array.isArray(init.headers)) {
      for (const [key, value] of init.headers) {
        headers[key] = value
      }
    } else {
      Object.assign(headers, init.headers as Record<string, string>)
    }
  }

  // POST/PUT/PATCH/DELETE는 CSRF 토큰 헤더 추가
  const method = init?.method?.toUpperCase()
  if (method && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    const token = getXsrfToken()
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  const res = await fetch(path, {
    ...init,
    credentials: 'include',
    headers,
  })

  if (!res.ok) {
    let problem: ProblemDetail
    try {
      problem = await res.json()
    } catch {
      problem = { status: res.status, title: res.statusText }
    }
    if (problem.status == null) problem.status = res.status
    throw new ApiError(problem)
  }

  if (res.status === 204) {
    return undefined as T
  }

  const contentType = res.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    return (await res.json()) as T
  }
  return undefined as T
}

export const apiClient = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body !== undefined ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PATCH', body: body !== undefined ? JSON.stringify(body) : undefined }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  postForm: <T>(path: string, form: FormData) => request<T>(path, { method: 'POST', body: form }),
}

/** image/png 같은 바이너리 응답을 blob URL로 받을 때 쓴다(미리보기 이미지). */
export async function fetchBlobUrl(path: string): Promise<string> {
  const res = await fetch(path, { credentials: 'include' })
  if (!res.ok) {
    let problem: ProblemDetail
    try {
      problem = await res.json()
    } catch {
      problem = { status: res.status, title: res.statusText }
    }
    throw new ApiError(problem)
  }
  const blob = await res.blob()
  return URL.createObjectURL(blob)
}

export async function postForBlobUrl(path: string, body: unknown): Promise<string> {
  const token = getXsrfToken()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers['X-XSRF-TOKEN'] = token
  }

  const res = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers,
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    let problem: ProblemDetail
    try {
      problem = await res.json()
    } catch {
      problem = { status: res.status, title: res.statusText }
    }
    throw new ApiError(problem)
  }
  const blob = await res.blob()
  return URL.createObjectURL(blob)
}
