#!/bin/bash
set -euo pipefail

# 하루종이 백업 루프 스크립트
# 매일 03:00 KST에 backup.sh를 실행하는 무한 루프
# (cron 없는 컨테이너 환경용)

export TZ=Asia/Seoul

BACKUP_SCRIPT="/scripts/backup.sh"

# 다음 03:00 시각을 계산하는 함수
get_next_backup_time() {
  local now=$(date +%s)
  local today_03=$(date -d "03:00" +%s 2>/dev/null || date -v03H -v0M -v0S +%s)

  # 현재 시각이 03:00을 지났으면 내일 03:00, 아니면 오늘 03:00
  if [ "$now" -ge "$today_03" ]; then
    # 내일 03:00 계산
    echo $((today_03 + 86400))
  else
    # 오늘 03:00
    echo "$today_03"
  fi
}

echo "[$(date +'%Y-%m-%d %H:%M:%S %Z')] 하루종이 백업 루프 시작 (매일 03:00 KST)"

while true; do
  next_time=$(get_next_backup_time)
  now=$(date +%s)
  sleep_seconds=$((next_time - now))

  next_time_readable=$(date -d @$next_time +'%Y-%m-%d %H:%M:%S %Z' 2>/dev/null || date -r $next_time +'%Y-%m-%d %H:%M:%S')

  echo "[$(date +'%Y-%m-%d %H:%M:%S %Z')] 다음 백업: $next_time_readable ($(($sleep_seconds / 3600))시간 $(($sleep_seconds % 3600 / 60))분 후)"

  # 다음 백업 시간까지 대기
  # 매분 확인하여 시간이 되면 즉시 실행 (정확도 향상)
  while [ "$(date +%s)" -lt "$next_time" ]; do
    sleep 60
  done

  # 백업 실행
  echo ""
  echo "[$(date +'%Y-%m-%d %H:%M:%S %Z')] === 정시 백업 시작 ==="

  if bash "$BACKUP_SCRIPT"; then
    echo "[$(date +'%Y-%m-%d %H:%M:%S %Z')] === 정시 백업 완료 ==="
  else
    echo "[$(date +'%Y-%m-%d %H:%M:%S %Z')] === 정시 백업 실패! 로그 확인 필요 ===" >&2
    # 실패해도 루프는 계속 돈다 (다음 03:00 대기)
  fi

  echo ""
done
