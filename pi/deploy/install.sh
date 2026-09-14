#!/bin/bash
# Haru-Paper Pi 에이전트 설치 스크립트
# 여러 번 실행해도 안전(idempotent)하게 설계됨
# setup.md 5절 절차 참고

set -euo pipefail

# 색상 출력 (선택)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 1. apt 패키지 설치
log_info "Step 1/10: apt 패키지 설치"
sudo apt-get update
sudo apt-get install -y \
    git \
    python3-venv \
    python3-pip \
    libusb-1.0-0 \
    bluez  # BT 방식일 때만 필요, 기본값으로 포함

# 2. 서비스 사용자 생성 및 그룹 추가
log_info "Step 2/10: 서비스 사용자 'haru' 설정"
if id haru &>/dev/null; then
    log_warn "사용자 'haru'가 이미 존재합니다"
else
    log_info "사용자 'haru' 생성 중..."
    sudo useradd --system --home /var/lib/haru-paper --shell /usr/sbin/nologin haru
fi

# plugdev, bluetooth 그룹에 추가
for group in plugdev bluetooth; do
    if sudo usermod -a -G "$group" haru 2>/dev/null || [ $? -eq 4 ]; then
        # 그룹이 없으면 생성 후 추가
        if ! grep -q "^$group:" /etc/group; then
            log_info "그룹 '$group' 생성 중..."
            sudo groupadd "$group" 2>/dev/null || true
        fi
        sudo usermod -a -G "$group" haru || log_warn "그룹 '$group' 추가 실패"
    fi
done

# 3. 저장소 클론 또는 업데이트
log_info "Step 3/10: 저장소 클론/업데이트 (/opt/haru-paper)"
REPO_PATH="/opt/haru-paper"
REPO_URL="https://github.com/Justant-source/Haru-Paper.git"

if [ ! -d "$REPO_PATH" ]; then
    log_info "저장소 클론 중..."
    sudo git clone "$REPO_URL" "$REPO_PATH"
else
    log_info "저장소 업데이트 중 (git pull --ff-only)..."
    if ! sudo git -C "$REPO_PATH" pull --ff-only 2>&1 | tee /tmp/git_pull.log; then
        log_error "git pull --ff-only 실패!"
        log_error "fast-forward가 불가능합니다. 로컬 변경사항을 수동으로 처리하세요."
        exit 1
    fi
fi

# 4. Python venv 생성 및 의존성 설치
log_info "Step 4/10: Python venv 및 의존성 설치"
VENV_PATH="$REPO_PATH/pi/.venv"

if [ ! -d "$VENV_PATH" ]; then
    log_info "venv 생성 중..."
    python3 -m venv "$VENV_PATH"
fi

log_info "의존성 설치 중 (pip install -r requirements.txt)..."
"$VENV_PATH/bin/pip" install --upgrade pip setuptools wheel
"$VENV_PATH/bin/pip" install -r "$REPO_PATH/pi/requirements.txt"

# 5. udev 규칙 설치
log_info "Step 5/10: udev 규칙 설정 (M832 USB 0483:5740)"
UDEV_RULES_FILE="/etc/udev/rules.d/99-haru-m832.rules"
UDEV_RULE='SUBSYSTEM=="usb", ATTR{idVendor}=="0483", ATTR{idProduct}=="5740", MODE="0660", GROUP="plugdev"'

if [ ! -f "$UDEV_RULES_FILE" ]; then
    log_info "udev 규칙 파일 생성 중..."
    echo "$UDEV_RULE" | sudo tee "$UDEV_RULES_FILE" > /dev/null
    sudo udevadm control --reload-rules
    sudo udevadm trigger
else
    log_warn "udev 규칙 파일이 이미 존재합니다. 스킵합니다."
    log_warn "파일: $UDEV_RULES_FILE"
fi

# 6. .env 파일 설정
log_info "Step 6/10: .env 파일 설정"
ENV_FILE="$REPO_PATH/pi/.env"
ENV_EXAMPLE="$REPO_PATH/pi/.env.example"

if [ ! -f "$ENV_FILE" ]; then
    if [ -f "$ENV_EXAMPLE" ]; then
        log_info ".env 파일을 .env.example에서 복사 중..."
        cp "$ENV_EXAMPLE" "$ENV_FILE"
        chmod 600 "$ENV_FILE"

        log_error ""
        log_error "=== .env 파일 설정이 필요합니다 ==="
        log_error ".env 파일이 생성되었습니다: $ENV_FILE"
        log_error "다음 필드들을 채우세요:"
        log_error "  - HARU_DEVICE_TOKEN: 서버에서 발급한 기기 토큰"
        log_error ""
        log_error "편집: sudo nano $ENV_FILE"
        log_error ""
        exit 1
    else
        log_error ".env.example 파일을 찾을 수 없습니다!"
        exit 1
    fi
else
    log_warn ".env 파일이 이미 존재합니다. 스킵합니다."
fi

# 7. systemd unit 설치
log_info "Step 7/10: systemd unit 설치"
SYSTEMD_UNIT_FILE="/etc/systemd/system/haru-paper-agent.service"
SYSTEMD_UNIT_SOURCE="$REPO_PATH/pi/deploy/haru-paper-agent.service"

if [ -f "$SYSTEMD_UNIT_SOURCE" ]; then
    log_info "systemd unit 파일 복사 중..."
    sudo cp "$SYSTEMD_UNIT_SOURCE" "$SYSTEMD_UNIT_FILE"
    sudo chmod 644 "$SYSTEMD_UNIT_FILE"

    log_info "systemctl daemon-reload 실행..."
    sudo systemctl daemon-reload

    log_info "서비스 활성화 및 시작 (enable --now)..."
    sudo systemctl enable --now haru-paper-agent
else
    log_error "systemd unit 파일을 찾을 수 없습니다: $SYSTEMD_UNIT_SOURCE"
    exit 1
fi

# 8. journald 크기 제한 설정
log_info "Step 8/10: journald 크기 제한 설정"
JOURNALD_CONF_DIR="/etc/systemd/journald.conf.d"
JOURNALD_CONF_FILE="$JOURNALD_CONF_DIR/haru-paper.conf"

sudo mkdir -p "$JOURNALD_CONF_DIR"
sudo tee "$JOURNALD_CONF_FILE" > /dev/null <<EOF
[Journal]
SystemMaxUse=50M
EOF
sudo chmod 644 "$JOURNALD_CONF_FILE"

log_info "journald 재시작..."
sudo systemctl restart systemd-journald

# 9. 시간대 설정
log_info "Step 9/10: 시간대 설정 (Asia/Seoul)"
CURRENT_TZ=$(timedatectl show --property=Timezone --value)
if [ "$CURRENT_TZ" != "Asia/Seoul" ]; then
    log_info "시간대를 Asia/Seoul로 변경 중..."
    sudo timedatectl set-timezone Asia/Seoul
else
    log_warn "시간대가 이미 Asia/Seoul로 설정되어 있습니다"
fi

# 10. 상태 확인 및 로그 출력
log_info "Step 10/10: 상태 확인"
echo ""
log_info "=== systemd 서비스 상태 ==="
sudo systemctl status haru-paper-agent --no-pager || true

echo ""
log_info "=== 최근 서비스 로그 (마지막 20줄) ==="
sudo journalctl -u haru-paper-agent -n 20 --no-pager || true

echo ""
log_info "=== 설치 완료 ==="
log_info "서비스 상태 확인: systemctl status haru-paper-agent"
log_info "로그 확인: journalctl -u haru-paper-agent -f"
log_info "설정 재로드: cd /opt/haru-paper && git pull --ff-only && sudo systemctl restart haru-paper-agent"
