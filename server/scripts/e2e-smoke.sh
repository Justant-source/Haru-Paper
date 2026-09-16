#!/usr/bin/env bash
# M6 (계정·기기 소유) e2e 스모크 테스트 — 배포된 스택에 curl로 실제 요청을 보내 전체
# 기본 동작을 검증한다. 사람이 브라우저로 하나하나 눌러볼 필요 없이 이 한 스크립트로
# 회귀를 잡는 것이 목적이다.
#
# 사용법: ./e2e-smoke.sh [base_url]
# 기본 base_url: http://127.0.0.1:18080
#
# admin 절(11)은 docker exec로 DB에 접근할 수 있을 때만 돈다(같은 호스트에서 실행 시 자동 감지).
# 접근할 수 없으면 그 절만 건너뛰고 나머지는 그대로 진행한다.
#
# 검증 범위 (.temp/03-플랫폼-작업지시서-v1.0.md 4.5절 통과 조건 + 기존 M2 CRUD 회귀):
#  0 헬스체크
#  1 CSRF 쿠키 발급·왕복
#  2 회원가입 검증(이메일 형식·비밀번호 길이·예약어·중복)
#  3 로그인·세션·me·계정 수정
#  4 포맷 CRUD + 소유권 스코핑(단건·목록)
#  5 포맷 가져오기(import)도 가져온 사람 소유가 되는가 (owner 스코핑 회귀)
#  6 예약(Schedule) CRUD + 소유권 스코핑 + 남의 포맷 참조 거부
#  7 에셋(Asset) 업로드 + 소유권 스코핑
#  8 설정(Settings) GET/PUT — 사용자별로 분리되는가
#  9 기기 토큰 발급 → Pi poll 인증 / 페어링 코드 발급 → pair 소비 → 재사용 거부
# 10 기기 정보(GET/PATCH /api/devices/me)
# 11 지금 인쇄(print-now) → 이력(history)에 사용자 스코핑으로 나타나는가
# 12 관리자: 목록·정지·임시비밀번호·레거시 소유권 이전 (DB 접근 가능할 때만)
# 13 관리자 API 접근 제어(일반 사용자 403)
# 14 로그아웃 → 세션 종료 확인
#
# 이 스크립트가 만든 테스트 데이터(e2e-*@example.com 계정과 그 소유 리소스)는
# cascade 삭제로 지워지므로, 끝나면 DB 접근이 가능한 경우 자동으로 정리한다.

set -uo pipefail

BASE_URL="${1:-http://127.0.0.1:18080}"
JAR="$(mktemp -d)"
PASS=0
FAIL=0
# 핸들 규칙(3~20자, [a-z0-9-])을 넘지 않게 숫자만 8자리로 줄인다.
RUN_ID="$(date +%s%N | tail -c 9)"

cleanup() { rm -rf "$JAR"; }
trap cleanup EXIT

ok()   { PASS=$((PASS+1)); printf '\033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '\033[31mFAIL\033[0m %s\n' "$1"; }
note() { printf '\033[33mSKIP\033[0m %s\n' "$1"; }

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

# multipart(-F) 업로드는 req()의 강제 Content-Type: application/json과 충돌하므로 별도 래퍼를 쓴다.
req_multipart() {
  local jar="$1" method="$2" path="$3"; shift 3
  local xsrf=""
  if [ -f "$jar" ]; then
    xsrf="$(grep -oP 'XSRF-TOKEN\s+\K\S+' "$jar" 2>/dev/null || true)"
  fi
  local hdrs=()
  if [ -n "$xsrf" ]; then
    hdrs+=(-H "X-XSRF-TOKEN: $xsrf")
  fi
  curl -sS -b "$jar" -c "$jar" -X "$method" "${hdrs[@]}" "$BASE_URL$path" "$@"
}

assert_status() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then ok "$desc ($actual)"; else bad "$desc (expected $expected, got $actual)"; fi
}

# DB 직접 접근 가능 여부 (관리자 절, 정리 절에서 쓴다)
DB_CONTAINER=""
if command -v docker >/dev/null 2>&1 && docker exec haru-paper-haru-db-1 true 2>/dev/null; then
  DB_CONTAINER="haru-paper-haru-db-1"
fi
db_sql() {
  docker exec "$DB_CONTAINER" sh -c "mariadb -u root -p\"\$MARIADB_ROOT_PASSWORD\" haru_paper -N -B -e \"$1\"" 2>/dev/null
}

echo "== e2e smoke: $BASE_URL =="
[ -n "$DB_CONTAINER" ] && echo "(DB 직접 접근 가능 — 관리자 절 포함)" || echo "(DB 접근 불가 — 관리자 절 생략)"
echo

# ---------------------------------------------------------------------------
echo "-- 0. 헬스체크 --"
health="$(curl -sS "$BASE_URL/api/health")"
if echo "$health" | jq -e '.status == "ok"' >/dev/null 2>&1; then ok "GET /api/health"; else bad "GET /api/health ($health)"; fi

# ---------------------------------------------------------------------------
echo
echo "-- 1. CSRF 쿠키 발급 --"
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

code=$(status_of "$jarA" PATCH "/api/account" -d '{"newPassword":"x","currentPassword":"wrong"}')
assert_status "틀린 currentPassword로 비밀번호 변경 시도 → 422" 422 "$code"

# ---------------------------------------------------------------------------
echo
echo "-- 4. 포맷 CRUD + 소유권 스코핑 --"
jarB="$JAR/userB.txt"
EMAIL_B="e2e-b-$RUN_ID@example.com"
HANDLE_B="e2eb$RUN_ID"
PASS_B="correct-horse-battery-2"

req "$jarB" GET "/api/auth/me" -o /dev/null
req "$jarB" POST "/api/auth/signup" -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS_B\",\"handle\":\"$HANDLE_B\",\"displayName\":\"사용자B\"}" -o /dev/null
status_of "$jarB" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS_B\"}" >/dev/null

format_body='{"schemaVersion":1,"meta":{"name":"e2e 포맷","author":"A"},"style":{},"blocks":[{"type":"text","props":{"text":"e2e"},"style":{}}]}'
resp=$(req "$jarA" POST "/api/formats" -d "$format_body")
format_id=$(echo "$resp" | jq -r '.id // empty')
if [ -n "$format_id" ]; then ok "A가 포맷 생성 (id=$format_id)"; else bad "A의 포맷 생성 실패: $resp"; fi

if [ -n "$format_id" ]; then
  code=$(status_of "$jarB" GET "/api/formats/$format_id")
  if [ "$code" = "404" ]; then ok "B는 A의 포맷을 못 본다 (404)"; else bad "B가 A의 포맷에 접근 가능함 ($code) — 소유권 스코핑 구멍"; fi

  code=$(status_of "$jarB" PUT "/api/formats/$format_id" -d "$format_body")
  if [ "$code" = "404" ]; then ok "B는 A의 포맷을 수정 못 한다 (404)"; else bad "B가 A의 포맷을 수정 가능함 ($code)"; fi

  code=$(status_of "$jarB" DELETE "/api/formats/$format_id")
  if [ "$code" = "404" ]; then ok "B는 A의 포맷을 삭제 못 한다 (404)"; else bad "B가 A의 포맷을 삭제 가능함 ($code)"; fi

  listA=$(req "$jarA" GET "/api/formats")
  listB=$(req "$jarB" GET "/api/formats")
  if echo "$listA" | jq -e "any(.[]; .id == \"$format_id\")" >/dev/null 2>&1 \
     && ! echo "$listB" | jq -e "any(.[]; .id == \"$format_id\")" >/dev/null 2>&1; then
    ok "목록 API도 소유자별로 분리된다"
  else
    bad "목록 API 소유권 분리 실패 (A목록=$listA / B목록=$listB)"
  fi

  updated_body='{"schemaVersion":1,"meta":{"name":"e2e 포맷-수정","author":"A"},"style":{},"blocks":[{"type":"text","props":{"text":"e2e-updated"},"style":{}}]}'
  resp=$(req "$jarA" PUT "/api/formats/$format_id" -d "$updated_body")
  if echo "$resp" | jq -e '.document.meta.name == "e2e 포맷-수정"' >/dev/null 2>&1; then
    ok "A는 본인 포맷을 수정할 수 있다"
  else
    bad "A의 본인 포맷 수정 실패: $resp"
  fi
fi

# ---------------------------------------------------------------------------
echo
echo "-- 5. 포맷 가져오기(import)도 가져온 사람 소유가 된다 --"
export_resp=$(req "$jarA" GET "/api/formats/$format_id/export")
if [ -n "$(echo "$export_resp" | jq -r '.schemaVersion // empty')" ]; then
  ok "A가 본인 포맷을 export"
  import_resp=$(req "$jarB" POST "/api/formats/import" -d "$export_resp")
  imported_id=$(echo "$import_resp" | jq -r '.id // empty')
  if [ -n "$imported_id" ]; then
    ok "B가 A의 export JSON을 import (id=$imported_id)"
    code=$(status_of "$jarB" GET "/api/formats/$imported_id")
    if [ "$code" = "200" ]; then ok "import한 포맷이 B 소유로 보인다"; else bad "import한 포맷을 B가 못 본다 ($code) — owner 미설정 버그"; fi
    code=$(status_of "$jarA" GET "/api/formats/$imported_id")
    if [ "$code" = "404" ]; then ok "import한 포맷은 원 작성자(A)에게는 안 보인다"; else bad "import한 포맷이 A에게도 보임 ($code)"; fi
  else
    bad "import 실패: $import_resp"
  fi
else
  bad "export 실패: $export_resp"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 6. 예약(Schedule) CRUD + 소유권 스코핑 --"
schedule_body="{\"formatId\":\"$format_id\",\"type\":\"recurring\",\"daysOfWeek\":[\"MON\",\"WED\"],\"time\":\"07:00\",\"enabled\":true}"
resp=$(req "$jarA" POST "/api/schedules" -d "$schedule_body")
schedule_id=$(echo "$resp" | jq -r '.id // empty')
if [ -n "$schedule_id" ]; then ok "A가 예약 생성 (id=$schedule_id)"; else bad "예약 생성 실패: $resp"; fi

if [ -n "$format_id" ]; then
  code=$(status_of "$jarB" POST "/api/schedules" -d "$schedule_body")
  if [ "$code" = "404" ]; then ok "B는 A 소유 포맷으로 예약을 못 만든다 (404)"; else bad "B가 A의 포맷으로 예약 생성 가능함 ($code) — 참조 소유권 구멍"; fi
fi

if [ -n "$schedule_id" ]; then
  listA=$(req "$jarA" GET "/api/schedules")
  listB=$(req "$jarB" GET "/api/schedules")
  if echo "$listA" | jq -e "any(.[]; .id == \"$schedule_id\")" >/dev/null 2>&1 \
     && ! echo "$listB" | jq -e "any(.[]; .id == \"$schedule_id\")" >/dev/null 2>&1; then
    ok "예약 목록도 소유자별로 분리된다"
  else
    bad "예약 목록 소유권 분리 실패"
  fi

  toggled_body="{\"formatId\":\"$format_id\",\"type\":\"recurring\",\"daysOfWeek\":[\"MON\"],\"time\":\"08:00\",\"enabled\":false}"
  resp=$(req "$jarA" PUT "/api/schedules/$schedule_id" -d "$toggled_body")
  if echo "$resp" | jq -e '.enabled == false and .time == "08:00"' >/dev/null 2>&1; then
    ok "A가 본인 예약을 수정(끄기 포함)할 수 있다"
  else
    bad "예약 수정 실패: $resp"
  fi

  code=$(status_of "$jarB" DELETE "/api/schedules/$schedule_id")
  if [ "$code" = "404" ]; then ok "B는 A의 예약을 삭제 못 한다 (404)"; else bad "B가 A의 예약을 삭제 가능함 ($code)"; fi

  code=$(status_of "$jarA" DELETE "/api/schedules/$schedule_id")
  assert_status "A가 본인 예약 삭제 → 204" 204 "$code"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 7. 에셋(Asset) 업로드 + 소유권 스코핑 --"
PNG_FILE="$JAR/1x1.png"
base64 -d > "$PNG_FILE" <<< "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

resp=$(req_multipart "$jarA" POST "/api/assets" -F "file=@${PNG_FILE};type=image/png")
asset_id=$(echo "$resp" | jq -r '.assetId // empty')
if [ -n "$asset_id" ]; then ok "A가 에셋 업로드 (id=$asset_id)"; else bad "에셋 업로드 실패: $resp"; fi

if [ -n "$asset_id" ]; then
  code=$(status_of "$jarA" GET "/api/assets/$asset_id")
  assert_status "A는 본인 에셋 조회 가능" 200 "$code"

  code=$(status_of "$jarB" GET "/api/assets/$asset_id")
  if [ "$code" = "404" ]; then ok "B는 A의 에셋을 못 본다 (404)"; else bad "B가 A의 에셋에 접근 가능함 ($code)"; fi
fi

code=$(req_multipart "$jarA" POST "/api/assets" -F "file=@$0;type=text/plain" -o /dev/null -w '%{http_code}')
if [ "$code" = "415" ] || [ "$code" = "422" ]; then
  ok "이미지가 아닌 파일 업로드 거부 ($code)"
else
  bad "이미지가 아닌 파일이 업로드됨 ($code)"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 8. 설정(Settings) 사용자별 분리 --"
resp=$(req "$jarA" PUT "/api/settings" -d '{"weather":{"lat":35.1043,"lon":129.0325,"label":"부산시청"}}')
if echo "$resp" | jq -e '.weather.label == "부산시청"' >/dev/null 2>&1; then
  ok "A가 날씨 위치를 부산으로 저장"
else
  bad "설정 저장 실패: $resp"
fi

resp=$(req "$jarB" GET "/api/settings")
label_b=$(echo "$resp" | jq -r '.weather.label // empty')
if [ "$label_b" != "부산시청" ]; then
  ok "B의 설정은 A와 분리되어 있다 (B의 label=$label_b)"
else
  bad "B가 A의 설정을 보게 된다 — 설정 사용자 분리 실패"
fi

code=$(status_of "$jarA" PUT "/api/settings" -d '{"weather":{"lat":999,"lon":129.0325,"label":"잘못된위도"}}')
assert_status "위도 범위 밖 → 422" 422 "$code"

# ---------------------------------------------------------------------------
echo
echo "-- 9. 기기 토큰·페어링 --"
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
echo "-- 10. 기기 정보 (GET/PATCH /api/devices/me) --"
resp=$(req "$jarA" GET "/api/devices/me")
if echo "$resp" | jq -e '.name != null' >/dev/null 2>&1; then
  ok "A의 기기 정보 조회 (name=$(echo "$resp" | jq -r .name))"
else
  bad "기기 정보 조회 실패: $resp"
fi

resp=$(req "$jarA" PATCH "/api/devices/me" -d '{"name":"거실 프린터"}')
if echo "$resp" | jq -e '.name == "거실 프린터"' >/dev/null 2>&1; then
  ok "기기 이름 변경"
else
  bad "기기 이름 변경 실패: $resp"
fi

resp=$(req "$jarB" GET "/api/devices/me")
if echo "$resp" | jq -e '.deviceId == null' >/dev/null 2>&1; then
  ok "페어링 전인 B는 빈 기기 정보를 받는다(404 아님, 기존 UX 유지)"
else
  bad "B가 기기 정보를 받으면 안 되는데 받음: $resp"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 11. 지금 인쇄(print-now) → 이력(history) --"
# 9절에서 페어링 코드로 pair를 소비하면 같은 기기의 토큰이 새로 발급되면서
# 9절 앞부분에서 받은 $device_token은 무효화된다(토큰 회전 — 의도된 보안 동작이다).
# 여기서 다시 발급받아 최신 토큰으로 갱신한다.
resp=$(req "$jarA" POST "/api/devices/me/token")
device_token=$(echo "$resp" | jq -r '.token // empty')

print_body="{\"formatId\":\"$format_id\",\"paperConfirmed\":true}"
resp=$(req "$jarA" POST "/api/print-now" -d "$print_body")
command_id=$(echo "$resp" | jq -r '.commandId // empty')
if [ -n "$command_id" ]; then
  ok "A가 지금 인쇄 명령 생성 (commandId=$command_id, 렌더 포함이라 몇 초 걸릴 수 있음)"
else
  bad "지금 인쇄 실패: $resp"
fi

code=$(status_of "$jarB" POST "/api/print-now" -d "$print_body")
if [ "$code" = "404" ]; then ok "B는 A 소유 포맷으로 지금 인쇄 못 한다 (404)"; else bad "B가 A의 포맷으로 인쇄 명령 생성 가능함 ($code)"; fi

if [ -n "$command_id" ] && [ -n "$device_token" ]; then
  now_iso="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
  result_id="e2e-result-$RUN_ID"
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/results" \
    -H "Authorization: Bearer $device_token" -H "Content-Type: application/json" \
    -d "{\"results\":[{\"resultId\":\"$result_id\",\"commandId\":\"$command_id\",\"formatId\":\"$format_id\",\"status\":\"printed\",\"executedAt\":\"$now_iso\"}]}")
  assert_status "Pi가 인쇄 결과 업로드" 200 "$code"

  resp=$(req "$jarA" GET "/api/history?limit=10")
  if echo "$resp" | jq -e "any(.[]; .resultId == \"$result_id\")" >/dev/null 2>&1; then
    ok "A의 이력에 방금 인쇄 결과가 보인다"
  else
    bad "이력에 결과가 안 보인다: $resp"
  fi

  resp=$(req "$jarB" GET "/api/history?limit=10")
  if ! echo "$resp" | jq -e "any(.[]; .resultId == \"$result_id\")" >/dev/null 2>&1; then
    ok "B의 이력에는 A의 결과가 안 보인다"
  else
    bad "B가 A의 이력을 보게 된다 — 이력 소유권 분리 실패"
  fi

  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/device/results" \
    -H "Authorization: Bearer $device_token" -H "Content-Type: application/json" \
    -d "{\"results\":[{\"resultId\":\"$result_id\",\"commandId\":\"$command_id\",\"formatId\":\"$format_id\",\"status\":\"printed\",\"executedAt\":\"$now_iso\"}]}")
  assert_status "같은 resultId 재전송은 멱등하게 200(중복 처리)" 200 "$code"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 12. 관리자 기능 --"
if [ -n "$DB_CONTAINER" ]; then
  user_a_id=$(db_sql "SELECT id FROM users WHERE email='$EMAIL_A'")
  user_b_id=$(db_sql "SELECT id FROM users WHERE email='$EMAIL_B'")
  if [ -n "$user_a_id" ]; then
    db_sql "UPDATE users SET role='admin' WHERE id='$user_a_id'" >/dev/null
    jarAdmin="$JAR/admin.txt"
    req "$jarAdmin" GET "/api/auth/me" -o /dev/null
    status_of "$jarAdmin" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS_A\"}" >/dev/null

    resp=$(req "$jarAdmin" GET "/api/admin/users")
    if echo "$resp" | jq -e "any(.[]; .email == \"$EMAIL_B\")" >/dev/null 2>&1; then
      ok "관리자가 사용자 목록 조회 (B 포함)"
    else
      bad "관리자 사용자 목록 조회 실패: $resp"
    fi

    resp=$(req "$jarAdmin" POST "/api/admin/users/$user_b_id/temp-password")
    temp_password=$(echo "$resp" | jq -r '.tempPassword // empty')
    if [ -n "$temp_password" ]; then
      ok "관리자가 B의 임시 비밀번호 발급"
      jarB2="$JAR/userB2.txt"
      req "$jarB2" GET "/api/auth/me" -o /dev/null   # CSRF 쿠키를 먼저 심는다(새 jar이므로 필요)
      code=$(status_of "$jarB2" POST "/api/auth/login" -d "{\"email\":\"$EMAIL_B\",\"password\":\"$temp_password\"}")
      assert_status "B가 임시 비밀번호로 로그인 가능" 200 "$code"
    else
      bad "임시 비밀번호 발급 실패: $resp"
    fi

    resp=$(req "$jarAdmin" POST "/api/admin/users/$user_b_id/suspend" -d '{"status":"suspended"}')
    if echo "$resp" | jq -e '.status == "suspended"' >/dev/null 2>&1; then
      ok "관리자가 B를 정지시킴"
    else
      bad "정지 처리 실패: $resp"
    fi
    # 원복(다른 검증에 영향 주지 않게)
    req "$jarAdmin" POST "/api/admin/users/$user_b_id/suspend" -d '{"status":"active"}' -o /dev/null

    # claim-legacy는 owner_user_id가 NULL인 모든 행을 이 서버의 실제 기존 데이터까지
    # 포함해 관리자에게 넘긴다. 지금 배포 DB에는 M6 이전부터 있던 진짜 레거시 행이
    # 있을 수 있으므로(이 스크립트가 만든 것이 아니다), 반드시 건드리기 전 ID를
    # 스냅샷해서 테스트가 끝나면 정확히 그 ID들만 다시 NULL로 되돌린다 — cascade
    # 삭제(테스트 계정 정리)가 진짜 데이터를 지우는 사고를 막기 위해서다.
    preexisting_format_ids=$(db_sql "SELECT id FROM formats WHERE owner_user_id IS NULL")
    preexisting_schedule_ids=$(db_sql "SELECT id FROM schedules WHERE owner_user_id IS NULL")
    preexisting_asset_ids=$(db_sql "SELECT id FROM assets WHERE owner_user_id IS NULL")

    db_sql "UPDATE formats SET owner_user_id=NULL WHERE id='$format_id'" >/dev/null
    resp=$(req "$jarAdmin" POST "/api/admin/claim-legacy")
    claimed=$(echo "$resp" | jq -r '.updatedFormatCount // 0')
    expected_min=$(( $(echo "$preexisting_format_ids" | grep -c . ) + 1 ))
    if [ "$claimed" -ge "$expected_min" ] 2>/dev/null; then
      ok "claim-legacy가 소유자 없는 포맷을 관리자에게 이전 (updatedFormatCount=$claimed)"
    else
      bad "claim-legacy 실패 또는 예상보다 적음(기대 >=$expected_min): $resp"
    fi

    # 기존(이 스크립트가 만들지 않은) 레거시 행을 정확히 원래 상태(owner_user_id NULL)로 되돌린다.
    # 내가 만든 테스트 포맷($format_id)만 admin 소유로 남겨서, 아래 계정 정리의 cascade로
    # 자연스럽게 같이 지워지게 한다.
    restored=0
    while IFS= read -r fid; do
      [ -z "$fid" ] && continue
      db_sql "UPDATE formats SET owner_user_id=NULL WHERE id='$fid'" >/dev/null
      restored=$((restored+1))
    done <<< "$preexisting_format_ids"
    while IFS= read -r sid; do
      [ -z "$sid" ] && continue
      db_sql "UPDATE schedules SET owner_user_id=NULL WHERE id='$sid'" >/dev/null
    done <<< "$preexisting_schedule_ids"
    while IFS= read -r aid; do
      [ -z "$aid" ] && continue
      db_sql "UPDATE assets SET owner_user_id=NULL WHERE id='$aid'" >/dev/null
    done <<< "$preexisting_asset_ids"

    still_null=$(db_sql "SELECT COUNT(*) FROM formats WHERE owner_user_id IS NULL")
    if [ "$still_null" = "$(echo "$preexisting_format_ids" | grep -c .)" ]; then
      ok "claim-legacy 테스트 후 기존 레거시 포맷 $restored건을 원상 복구했다"
    else
      bad "기존 레거시 포맷 복구가 어긋났다 (복구 전 후보=$restored, 현재 NULL 개수=$still_null) — 수동 확인 필요"
    fi
  else
    note "관리자 절 — 테스트 사용자를 못 찾음"
  fi
else
  note "관리자 절 — DB에 직접 접근할 수 없어 생략(docker exec 불가)"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 13. 관리자 API 접근 제어 (일반 사용자) --"
code=$(status_of "$jarB" GET "/api/admin/users")
if [ "$code" = "403" ] || [ "$code" = "401" ]; then
  ok "일반 사용자는 /api/admin/users 접근 거부 ($code)"
else
  bad "일반 사용자가 관리자 API에 접근 가능함 ($code)"
fi

# ---------------------------------------------------------------------------
echo
echo "-- 14. 로그아웃 --"
code=$(status_of "$jarB" POST "/api/auth/logout")
assert_status "로그아웃 → 204" 204 "$code"

code=$(status_of "$jarB" GET "/api/auth/me")
assert_status "로그아웃 후 /api/auth/me → 401" 401 "$code"

# ---------------------------------------------------------------------------
echo
echo "-- 정리: 이 스크립트가 만든 테스트 계정 삭제 --"
if [ -n "$DB_CONTAINER" ]; then
  db_sql "DELETE FROM users WHERE email IN ('$EMAIL_A','$EMAIL_B')" >/dev/null
  remaining=$(db_sql "SELECT COUNT(*) FROM users WHERE email LIKE 'e2e-%@example.com'")
  if [ "$remaining" = "0" ]; then ok "테스트 계정 정리 완료"; else bad "테스트 계정이 남아있음 ($remaining개)"; fi
else
  note "DB 접근 불가 — 테스트 계정 수동 정리 필요 ($EMAIL_A, $EMAIL_B)"
fi

# ---------------------------------------------------------------------------
echo
echo "== 결과: PASS=$PASS FAIL=$FAIL =="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
