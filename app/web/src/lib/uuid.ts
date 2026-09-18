// crypto.randomUUID()는 secure context(HTTPS 또는 localhost)에서만 존재한다. 이 앱은
// docs/server/deploy.md 3.1절의 tailscale IP 직접 접속 경로(HTTP)로도 열리는데, 그 경로는
// secure context가 아니라서 crypto.randomUUID가 undefined다 — 호출하면 즉시 TypeError로 죽고,
// 이 호출은 렌더 중(useMemo)에 일어나므로 화면이 그냥 하얗게 비어 "버튼을 눌러도 반응이 없다"로
// 보인다(2026-09-18 실사용 중 발견·재현). crypto.getRandomValues()는 secure context 제약이
// 없으므로 그걸로 UUID v4를 직접 만든다.
export function uuid(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
