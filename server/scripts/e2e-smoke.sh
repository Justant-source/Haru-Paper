#!/usr/bin/env bash
# M6 인증·기기·소유권 스코핑 e2e 스모크 테스트.
# 돌아가고 있는 배포(기본: http://127.0.0.1:18080, nginx 경유 — CSRF 쿠키 경로까지 실제 배포와 동일하게 검증)에
# curl로 직접 요청을 보내 전체 흐름을 검증한다. 별도 프레임워크 없이 CI/수동 실행 둘 다 가능하게 한다.
#
# 사용법: ./e2e-smoke.sh [base_url]
# 기본 base_url: http://127.0.0.1:18080
#
# 검증 범위 (.temp/03-플랫폼-작업지시서-v1.0.md 4.5절 통과 조건):
# - CSRF 쿠키 발급 → 헤더로 되돌리는 흐름이 실제로 동작하는가
# - 가입·로그인·로그아웃·세션 유지·me
# - 가입 검증(이메일 형식, 비밀번호 길이, 핸들 중복·예약어)
# - 두 계정이 서로의 레이아웃·기기를 못 본다(owner 스코핑)
# - 기기 토큰 발급 → 그 토큰으로 poll 인증
# - 페어링 코드 발급 → pair로 소비 → 재사용 실패
# - 관리자 전용 API가 일반 사용자에게는 403

set -uo pipefail

BASE_URL="${1:-http://127.0.0.1:18080}"
JAR="$(mktemp -d)"
PASS=0
FAIL=0
# 핸들 규칙(3~20자, [a-z0-9-])을 넘지 않게 숫자만 8자리로 줄인다.
RUN_ID="$(date +%s%N | tail -c 9)"

cleanup() { rm -rf "$JAR"; }
trap cleanup EXIT

log()  { printf '  %s\n' "$1"; }
ok()   { PASS=$((PASS+1)); printf '\033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '\033[31mFAIL\033[0m %s\n' "$1"; }

# --- 세션별 쿠키 jar를 쓰는 curl 래퍼 ---
# $1=jarfile $2=method $3=path 나머지는 curl 인자
req() {
  local jar="$1" method="$2" path="$3"; shift 3
  local xsrf=""
  if [ -f "$jar" ]; then
    xsrf="$(grep -oP 'XSRF-TOKEN\s+\K\S+' "$jar" 2>/dev/null || true)"
  fi
  local hdrs=(-H "Content-Type: application/json")
  if [ -n "$xsrf" ] && [ "$method" != "GET" ]; then
    hdrs+=(-H "X-XSRF-TOKEN: $xsrf")
  fi
  curl -sS -b "$jar" -c "$jar" -X "$method" "${hdrs[@]}" "$BASE_URL$path" "$@"
}

status_of() { req "$@" -o /dev/null -w '%{http_code}'; }

assert_status() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then ok "$desc ($actual)"; else bad "$desc (expected $expected, got $actual)"; fi
}

echo "== e2e smoke: $BASE_URL =="
echo

# ---------------------------------------------------------------------------
echo "-- 0. 헬스체크 --"
health="$(curl -sS "$BASE_URL/api/health")"
if echo "$health" | jq -e '.status == "ok"' >/dev/null 2>&1; then ok "GET /api/health"; else bad "GET /api/health ($health)"; fi

# ---------------------------------------------------------------------------
echo
echo "-- 1. CSRF 쿠키 발급 (첫 GET에서 XSRF-TOKEN이 실려야 한다) --"
jarA="$JAR/userA.txt"
req "$jarA" GET "/api/auth/me" -o /dev/null
if grep -q 'XSRF-TOKEN' "$jarA" 2>/dev/null; then
  ok "첫 GET 응답에 XSRF-TOKEN 쿠키가 실린다"
else
  bad "XSRF-TOKEN 쿠키가 없다 — SecurityConfig의 csrfCookieFilter/csrfTokenRequestHandler 확인"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 2. 회원가입 검증 --"
EMAIL_A="e2e-a-$RUN_ID@example.com"
HANDLE_A="e2ea$RUN_ID"
PASS_A="correct-horse-battery-1"

code=$(status_of "$jarA" POST "/api/auth/signup" -d "{\"email\":\"not-an-email\",\"password\":\"$PASS_A\",\"handle\":\"$HANDLE_A\",\"displayName\":\"A\"}")
assert_status "잘못된 이메일 형식 → 422" 422 "$code"

code=$(status_of "$jarA" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_A\",\"password\":\"short\",\"handle\":\"$HANDLE_A\",\"displayName\":\"A\"}")
assert_status "짧은 비밀번호 → 422" 422 "$code"

code=$(status_of "$jarA" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS_A\",\"handle\":\"admin\",\"displayName\":\"A\"}")
assert_status "예약어 핸들 → 422" 422 "$code"

resp=$(req "$jarA" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS_A\",\"handle\":\"$HANDLE_A\",\"displayName\":\"사용자A\"}")
if echo "$resp" | jq -e ".handle == \"$HANDLE_A\"" >/dev/null 2>&1; then
  ok "정상 가입 → 201, handle 일치"
else
  bad "정상 가입 실패: $resp"
fi

code=$(status_of "$jarA" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS_A\",\"handle\":\"other-$RUN_ID\",\"displayName\":\"A2\"}")
assert_status "이메일 중복 → 422" 422 "$code"

# ---------------------------------------------------------------------------
echo
echo "-- 3. 로그인·세션·me --"
code=$(status_of "$jarA" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_A\",\"password\":\"wrong-password\"}")
if [ "$code" = "422" ] || [ "$code" = "401" ]; then ok "틀린 비밀번호 로그인 거부 ($code)"; else bad "틀린 비밀번호인데 $code"; fi

code=$(status_of "$jarA" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS_A\"}")
assert_status "정상 로그인 → 200" 200 "$code"

resp=$(req "$jarA" GET "/api/auth/me")
if echo "$resp" | jq -e ".handle == \"$HANDLE_A\"" >/dev/null 2>&1; then
  ok "로그인 후 /api/auth/me가 본인 정보를 돌려준다"
else
  bad "/api/auth/me 불일치: $resp"
fi

resp=$(req "$jarA" PATCH "/api/account" -d '{"displayName":"사용자A-수정"}')
if echo "$resp" | jq -e '.displayName == "사용자A-수정"' >/dev/null 2>&1; then
  ok "PATCH /api/account로 표시이름 변경"
else
  bad "계정 수정 실패: $resp"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 4. 소유권 스코핑: 두 계정이 서로의 리소스를 못 본다 --"
jarB="$JAR/userB.txt"
EMAIL_B="e2e-b-$RUN_ID@example.com"
HANDLE_B="e2eb$RUN_ID"
PASS_B="correct-horse-battery-2"

req "$jarB" GET "/api/auth/me" -o /dev/null
req "$jarB" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS_B\",\"handle\":\"$HANDLE_B\",\"displayName\":\"사용자B\"}" -o /dev/null
status_of "$jarB" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS_B\"}" >/dev/null

# A가 포맷 생성
format_body='{"schemaVersion":1,"meta":{"name":"e2e 포맷","author":"A"},"style":{},"blocks":[{"type":"text","props":{"text":"e2e"},"style":{}}]}'
resp=$(req "$jarA" POST "/api/formats" -d "$format_body")
format_id=$(echo "$resp" | jq -r '.id // empty')
if [ -n "$format_id" ]; then ok "A가 포맷 생성 (id=$format_id)"; else bad "A의 포맷 생성 실패: $resp"; fi

if [ -n "$format_id" ]; then
  code=$(status_of "$jarB" GET "/api/formats/$format_id")
  if [ "$code" = "404" ]; then ok "B는 A의 포맷을 못 본다 (404)"; else bad "B가 A의 포맷에 접근 가능함 ($code) — 소유권 스코핑 구멍"; fi

  listA=$(req "$jarA" GET "/api/formats")
  listB=$(req "$jarB" GET "/api/formats")
  if echo "$listA" | jq -e "any(.[]; .id == \"$format_id\")" >/dev/null 2>&1 \
     && ! echo "$listB" | jq -e "any(.[]; .id == \"$format_id\")" >/dev/null 2>&1; then
    ok "목록 API도 소유자별로 분리된다"
  else
    bad "목록 API 소유권 분리 실패 (A목록=$listA / B목록=$listB)"
  fi
fi

# ---------------------------------------------------------------------------
echo
echo "-- 5. 기기 토큰·페어링 --"
resp=$(req "$jarA" POST "/api/devices/me/token")
device_token=$(echo "$resp" | jq -r '.token // empty')
if [ -n "$device_token" ]; then ok "기기 토큰 발급"; else bad "기기 토큰 발급 실패: $resp"; fi

if [ -n "$device_token" ]; then
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/poll" \
    -H "Authorization: Bearer $device_token" -H "Content-Type: application/json" \
    -d '{"agentVersion":"e2e-test","printerProfile":null,"printerStatus":null,"paperPolicy":null,"snapshotHash":null}')
  assert_status "발급받은 토큰으로 Pi poll 인증 통과" 200 "$code"

  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/poll" \
    -H "Authorization: Bearer wrong-token-obviously" -H "Content-Type: application/json" \
    -d '{}')
  assert_status "틀린 토큰으로 poll → 401" 401 "$code"
fi

resp=$(req "$jarA" POST "/api/devices/pairing-codes")
pair_code=$(echo "$resp" | jq -r '.code // empty')
if [ -n "$pair_code" ]; then ok "페어링 코드 발급 ($pair_code)"; else bad "페어링 코드 발급 실패: $resp"; fi

if [ -n "$pair_code" ]; then
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/pair" \
    -H "Content-Type: application/json" -d "{\"code\":\"$pair_code\"}")
  assert_status "발급된 코드로 pair 성공" 200 "$code"

  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/pair" \
    -H "Content-Type: application/json" -d "{\"code\":\"$pair_code\"}")
  if [ "$code" != "200" ]; then ok "같은 코드 재사용 거부 ($code)"; else bad "1회용 코드가 재사용됐다"; fi
fi

code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/pair" \
  -H "Content-Type: application/json" -d '{"code":"NOTREAL1"}')
if [ "$code" != "200" ]; then ok "존재하지 않는 코드 거부 ($code)"; else bad "존재하지 않는 코드가 통과했다"; fi

# ---------------------------------------------------------------------------
echo
echo "-- 6. 관리자 API 접근 제어 --"
code=$(status_of "$jarA" GET "/api/admin/users")
if [ "$code" = "403" ] || [ "$code" = "401" ]; then
  ok "일반 사용자는 /api/admin/users 접근 거부 ($code)"
else
  bad "일반 사용자가 관리자 API에 접근 가능함 ($code)"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 7. 로그아웃 --"
code=$(status_of "$jarA" POST "/api/auth/logout")
assert_status "로그아웃 → 204" 204 "$code"

code=$(status_of "$jarA" GET "/api/auth/me")
assert_status "로그아웃 후 /api/auth/me → 401" 401 "$code"

# ---------------------------------------------------------------------------
echo
echo "== 결과: PASS=$PASS FAIL=$FAIL =="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
