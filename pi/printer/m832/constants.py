"""M832 프린터 프로토콜 상수 (docs/pi/printer-m832.md 2.1~2.2절).

모든 상수는 detox-printer 실물 검증 기록 기반이다.
상수 옆의 근거 주석 형식: [확인됨·실물] docs/pi/printer-m832.md 섹션, detox-printer findings 참고.
"""

# USB 장치 식별자 (2.1절)
VID = 0x0483  # Phomemo M832. docs/pi/printer-m832.md 2.1절, m832/docs/device-descriptor.md, findings "0-b", "1단계"
PID = 0x5740  # Phomemo M832. docs/pi/printer-m832.md 2.1절, m832/docs/device-descriptor.md, findings "0-b", "1단계"

# USB 엔드포인트 (2.1절)
USB_INTERFACE = 0  # Interface 0 하나만 사용. docs/pi/printer-m832.md 2.1절, m832/docs/device-descriptor.md
EP_OUT = 0x02  # BULK OUT 엔드포인트. docs/pi/printer-m832.md 2.1절, m832/docs/device-descriptor.md
EP_IN = 0x81  # BULK IN 엔드포인트. docs/pi/printer-m832.md 2.1절, m832/docs/device-descriptor.md
USB_CHUNK_SIZE = 4096  # 청크 크기(libusb가 64바이트 패킷으로 분할). docs/pi/printer-m832.md 2.1절, m832/src/07_print_image.py, findings "E-1"
USB_WRITE_TIMEOUT_MS = 5000  # write 타임아웃(청크 1개당). docs/pi/printer-m832.md 2.1절, m832/src/07_print_image.py, findings "E-1"
USB_TOTAL_WRITE_DEADLINE_SEC = 60  # 전체 write 상한. 프린터가 중간에 데이터 소비를 멈추면(용지 걸림 등) 청크별 타임아웃은 통과하면서도 총 시간이 쌓여 폴링 스레드를 오래 막을 수 있다 — .temp/01-orangepi-poc-작업지시서-v1.2.md 4.3절. 570KB 기준 정상 전송은 수 초면 끝나므로 60초면 충분

# 래스터 스트림 파라미터 (2.2절, 110mm 연속 롤 고정)
WIDTH_BYTES = 163  # 전송 폭(바이트). 110mm 실측값. docs/pi/printer-m832.md 2.2절, m832/docs/protocol.md "110mm 실측", findings "D단계"
WIDTH_DOTS = 1304  # 전송 폭(도트). 163 bytes × 8. docs/pi/printer-m832.md 2.2절, m832/docs/protocol.md
DPI = 300  # 해상도(정사각 dot). docs/pi/printer-m832.md 2.2절, findings "E-3 — 대각선·원"

# 헤더 바이트 (2.2절)
HDR_CONTINUOUS_PAPER = bytes([0x1F, 0x11, 0x0B])  # 연속용지 헤더. docs/pi/printer-m832.md 2.2절
HDR_NO_COMPRESSION = bytes([0x1F, 0x11, 0x35, 0x00])  # 압축 Off. docs/pi/printer-m832.md 2.2절

# 비트맵 명령 프리픽스 (2.2절)
CMD_BMP_PREFIX = bytes([0x1D, 0x76, 0x30, 0x00])  # bCmdBMP "1D 76 30 00 xL xH yL yH". docs/pi/printer-m832.md 2.2절, m832/docs/protocol.md

# 꼬리 바이트 (2.2절, 110mm 고정)
CMD_AFTER_PAGE = bytes([0x1B, 0x64, 0x01])  # 페이지 종료. docs/pi/printer-m832.md 2.2절
CMD_AFTER_JOB = bytes([0x1B, 0x64, 0x02])  # 작업 종료. docs/pi/printer-m832.md 2.2절
CMD_FINDPAPER = bytes([0x1F, 0x11, 0x11])  # 용지 찾기. docs/pi/printer-m832.md 2.2절, m832/docs/filter-source-map.md
FOOTER_110MM = CMD_AFTER_PAGE + CMD_AFTER_JOB + CMD_FINDPAPER  # 총 9바이트. docs/pi/printer-m832.md 2.2절

# 비트맵 비트 극성 (2.3절)
# Pillow tobytes()는 1=흰색이므로, XOR 0xFF로 뒤집어 1=검정으로 변환한다.
# "비트 극성" 테이블에서: 1 = 검정 [확인됨·실물]
_INVERT_TABLE = bytes([i ^ 0xFF for i in range(256)])  # XOR 0xFF lookup table

# 좌우 정렬 보정 기본값 (2.3절)
H_OFFSET_MM_DEFAULT = 2.0  # 기본 보정: 좌우 여백 대칭. docs/pi/printer-m832.md 2.3절, findings "E-3 — 수평 정렬 보정"
# 24 dot = round(2.0 / 25.4 × 300)
H_OFFSET_DEFAULT_DOT = 24
