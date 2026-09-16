/**
 * CSRF 토큰 쿠키에서 읽기.
 * 서버가 `XSRF-TOKEN`이라는 이름의 쿠키(HttpOnly 아님)를 내려준다.
 * 이 값을 `X-XSRF-TOKEN` 헤더로 POST/PUT/PATCH/DELETE 요청에 실어야 한다.
 */
export function getXsrfToken(): string | undefined {
  const cookies = document.cookie.split('; ')
  for (const cookie of cookies) {
    const [name, value] = cookie.split('=')
    if (name === 'XSRF-TOKEN') {
      return decodeURIComponent(value)
    }
  }
  return undefined
}
