# 네이티브 앱 (`/app/android`, `/app/ios`) — 예약만, 기술 미정

> 기준: [../init_plan.md](../init_plan.md) Q16·Q26·Q33 / 담당: 서버 세션
> 표기: **[미검증]** 확인 전 / **[추정]** 추론

## 1. 현재 상태

- `/app/android/.gitkeep`, `/app/ios/.gitkeep` — **빈 폴더 예약만** 해 두었다(Q26).
- **PoC 클라이언트는 웹앱(`/app/web`) 하나**다. 네이티브는 지금 만들지 않는다(Q6: "네이티브가 지금 당장 필요한 것은 아니고 POC 단계이니 웹앱으로도 가능").
- 네이티브 기술은 **미정**(Q33). **결정 시점은 PoC 완료 이후**.

## 2. 사용자 조건 (결정 시 전제)

- 사용자 폰: **Android**(`s21`). iOS 기기는 tailnet에 iPad 2대가 있으나 오프라인 상태 [참고]
- 편한 언어: Java, Python, React, TypeScript, JavaScript. 그 외 언어는 잘 모르지만 **iOS·Android 동시 개발에 유리하면 배울 의향 있음**
- 노트북(Windows + WSL)에 Flutter/JDK/Android SDK 없음 — 어떤 기술이든 개발 환경을 새로 깔아야 한다
- **Mac 보유 여부 미확인** [미검증]

## 3. 기술 후보

| 후보 | 장점 | 단점 | 폴더 사용 |
|---|---|---|---|
| **React Native (Expo)** | 기존 TS/React 지식과 웹앱 코드(API 클라이언트, 타입) 재사용. 한 코드로 Android·iOS. Expo EAS 클라우드 빌드로 Mac 없이 iOS 빌드 가능 | 네이티브 모듈(BLE 등)이 필요해지면 prebuild·설정이 늘어남. 원래 한 프로젝트 구조라 `android/`·`ios/` 분리 폴더와는 모양이 다름 | 한 프로젝트를 둘 중 한쪽 또는 새 폴더에 두고 나머지는 비워둘지 결정 필요 |
| **Kotlin(Android) + Swift(iOS)** | 각 플랫폼 기능·성능 제약 없음. 현재 예약한 `android/`·`ios/` 폴더 구조와 정확히 일치 | 같은 기능을 **2벌** 구현. Kotlin·Swift 둘 다 새로 배워야 함. iOS는 Mac 필수 | `android/` = Android Studio 프로젝트, `ios/` = Xcode 프로젝트 |
| **Flutter** | 한 코드로 Android·iOS, UI 일관성 좋음 | **Dart 새로 학습**. 웹앱(TS) 코드 재사용 불가 | 한 프로젝트 구조(React Native와 같은 고민) |

## 4. iOS 빌드 제약

- iOS 네이티브 빌드·시뮬레이터에는 **macOS + Xcode가 필요**하다.
- Mac이 없다면: React Native(Expo EAS Build) 또는 Flutter용 클라우드 빌드(Codemagic 등)로 빌드한다.
- 스토어/실기기 배포에는 Apple Developer Program(연 $99)이 필요하다. Android는 Google Play Console($25, 1회) — 개인용 사이드로딩이면 불필요.

## 5. 네이티브로 갈 때 함께 검토할 것 (상용화 관점)

PoC 범위(본인 1명, tailnet 전용)를 넘어가면 앱 기술보다 먼저 아래가 바뀐다.

| 항목 | PoC 현재 | 상용화 시 검토 |
|---|---|---|
| 접속 경로·인증 | Tailscale 내부망, 로그인 없음 | 공개 접속 경로(예: Cloudflare Tunnel) + 회원가입·로그인 |
| 알림 | 없음(이력 화면에서 확인) | 푸시 알림(인쇄 실패·용지 없음·Pi 오프라인) — FCM/APNs |
| 동기화 | Pi 30초 폴링 | 실시간 WebSocket(지금 인쇄 즉시 반영) |
| Pi 초기 설정 | SD카드에 Wi-Fi·SSH 키를 미리 넣는 헤드리스 설치 | 앱에서 Pi 페어링·Wi-Fi 설정 전달(BLE 프로비저닝 등), 기기를 계정에 등록 |
| 사용자·기기 수 | 1명, 프린터 1대 | 다수 사용자, 사용자별 여러 기기 |
| 포맷 공유 | JSON 파일 가져오기/내보내기 | 공유 갤러리 |

## 6. 결정 절차 (PoC 이후)

1. Mac 보유 여부 확인
2. 상용화 여부와 범위 결정(5절 항목)
3. 3절 후보 중 선택 → 이 문서와 `/app/README.md` 갱신
4. 폴더 사용 방식 확정: 한 코드 프로젝트(React Native/Flutter)라면 `android/`·`ios/` 예약 폴더를 어떻게 쓸지(또는 새 폴더로 옮길지) 정한다
