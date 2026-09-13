#!/bin/bash
set -euo pipefail

# 하루종이 서버 백업 스크립트
# - DB 덤프: mariadb-dump (single-transaction)
# - 파일 백업: haru-files/uploads/ tar.gz
# - 보관 기간 정책: HARU_BACKUP_RETENTION_DAYS (기본 7일)

# 환경변수 기본값
BACKUP_DIR="${BACKUP_DIR:-/backups}"
MARIADB_ROOT_PASSWORD="${MARIADB_ROOT_PASSWORD:-}"
HARU_BACKUP_RETENTION_DAYS="${HARU_BACKUP_RETENTION_DAYS:-7}"
MARIADB_HOST="${MARIADB_HOST:-haru-db}"
DB_NAME="haru_paper"

# 오류 처리: 필수 환경변수 확인
if [ -z "$MARIADB_ROOT_PASSWORD" ]; then
  echo "ERROR: MARIADB_ROOT_PASSWORD 환경변수가 설정되지 않았습니다" >&2
  exit 1
fi

if [ ! -d "$BACKUP_DIR" ]; then
  echo "ERROR: $BACKUP_DIR 디렉터리가 없습니다" >&2
  exit 1
fi

echo "[$(date +'%Y-%m-%d %H:%M:%S')] 백업 시작..."

# ===== DB 백업 =====
DB_BACKUP_FILE="$BACKUP_DIR/db-$(date +%Y%m%d-%H%M).sql.gz"
echo "DB 덤프 중: $DB_BACKUP_FILE"

if mariadb-dump \
  --single-transaction \
  --host="$MARIADB_HOST" \
  --user=root \
  --password="$MARIADB_ROOT_PASSWORD" \
  "$DB_NAME" | gzip > "$DB_BACKUP_FILE"; then
  echo "DB 덤프 완료: $DB_BACKUP_FILE ($(stat -c%s "$DB_BACKUP_FILE" 2>/dev/null || echo '?') bytes)"
else
  echo "ERROR: DB 덤프 실패" >&2
  exit 1
fi

# ===== 파일 백업 (uploads 디렉터리) =====
FILES_BACKUP_FILE="$BACKUP_DIR/uploads-$(date +%Y%m%d).tar.gz"
HARU_FILES_DIR="/data/haru-files"

# uploads 디렉터리가 있는지 확인
if [ -d "$HARU_FILES_DIR/uploads" ]; then
  echo "파일 백업 중: $FILES_BACKUP_FILE"

  if tar -czf "$FILES_BACKUP_FILE" -C "$HARU_FILES_DIR" uploads/ 2>/dev/null; then
    echo "파일 백업 완료: $FILES_BACKUP_FILE ($(stat -c%s "$FILES_BACKUP_FILE" 2>/dev/null || echo '?') bytes)"
  else
    echo "ERROR: 파일 백업 실패" >&2
    exit 1
  fi
else
  echo "WARNING: $HARU_FILES_DIR/uploads 디렉터리가 없습니다. 스킵합니다"
fi

# ===== 오래된 백업 삭제 =====
echo "보관 기간($HARU_BACKUP_RETENTION_DAYS일) 지난 파일 삭제 중..."

# SQL 덤프 삭제
DELETE_COUNT=$(find "$BACKUP_DIR" -name "db-*.sql.gz" -mtime +$HARU_BACKUP_RETENTION_DAYS -print -delete 2>/dev/null | wc -l)
if [ "$DELETE_COUNT" -gt 0 ]; then
  echo "삭제된 DB 덤프: $DELETE_COUNT개"
fi

# 파일 백업 삭제
DELETE_COUNT=$(find "$BACKUP_DIR" -name "uploads-*.tar.gz" -mtime +$HARU_BACKUP_RETENTION_DAYS -print -delete 2>/dev/null | wc -l)
if [ "$DELETE_COUNT" -gt 0 ]; then
  echo "삭제된 파일 백업: $DELETE_COUNT개"
fi

# ===== 현재 백업 목록 =====
echo ""
echo "현재 백업 파일:"
ls -lh "$BACKUP_DIR"/*.{sql.gz,tar.gz} 2>/dev/null | tail -10 || echo "(백업 파일 없음)"

echo ""
echo "[$(date +'%Y-%m-%d %H:%M:%S')] 백업 완료"
