# 개인정보처리방침 / Privacy Policy

시행일 / Effective: 2026-11-08

## 한국어

Tern(이하 "앱")은 개인정보를 수집하지 않음

**수집하는 정보**
- 없음
- 분석 도구, 광고, 오류 보고 도구, 계정 없음
- 개발자가 운영하는 서버로 보내는 데이터 없음

**기기에 저장하는 정보**
- 호스트 설정, 스니펫, 비밀번호, 개인 키, 앱 설정은 이 기기에만 저장
- 비밀번호와 개인 키는 Android Keystore의 키로 암호화
- Android 백업과 기기 간 전송에서 제외, 앱을 삭제하면 함께 삭제
- 내보낸 개인 키는 사용자가 고른 위치에 파일로 저장. 앱이 관리하거나 다른 곳으로 보내지 않음

**네트워크 연결**
- 사용자가 입력한 서버에만 SSH와 SFTP로 연결
- 접속할 때 터미널 종류와 언어 설정(TERM, LANG, LC_TERMINAL 환경 변수)을 그 서버에 전송
- 포트 포워딩: 이 기기의 루프백 주소(127.0.0.1)에서만 연결을 받아 그 서버로 전달
- 에이전트 포워딩: 켠 서버가 앱에 서명을 요청할 수 있음. 서명은 기기 안에서 만들고 개인 키는 기기 밖으로 나가지 않음

**결제**
- 후원은 Google Play 결제 시스템으로 처리
- 결제 정보는 Google이 직접 받아 처리하며 앱은 볼 수 없음
- 앱은 후원 완료 처리에 필요한 구매 정보만 Google Play에서 받고 저장하지 않음
- 결제에는 Google의 개인정보처리방침 적용

**권한**
- 인터넷: 사용자가 지정한 서버에 연결
- 알림, 포그라운드 서비스: 앱을 나가도 SSH 세션 유지, 연결된 세션을 알림으로 표시
- 생체 인증: 지문으로 앱 잠금 해제. 생체 정보는 Android가 처리하며 앱은 접근 불가
- 진동: 터미널 벨

**제3자 제공**
- 없음

**아동**
- 만 13세 미만 아동 대상 아님

**변경**
- 이 방침이 바뀌면 이 페이지에 게시

**문의**
- dev@luftaquila.io

## English

Tern ("the app") collects no personal data.

**Data collected**
- None
- No analytics, ads, crash reporting or accounts
- Nothing sent to servers run by the developer

**Data stored on the device**
- Hosts, snippets, passwords, private keys and settings stored only on this device
- Passwords and private keys encrypted with a key in the Android Keystore
- Excluded from Android backups and device transfers; removed when the app is uninstalled
- Exported private keys saved as files where you choose; the app does not manage them or send them anywhere

**Network connections**
- Only the servers you enter, over SSH and SFTP
- On connecting: the terminal type and locale (TERM, LANG and LC_TERMINAL environment variables) sent to that server
- Port forwards accept connections only on this device's loopback address (127.0.0.1) and pass them to that server
- Agent forwarding: a server you enable it for can ask the app for signatures; signatures made on the device, private keys never leave it

**Payments**
- Donations processed by Google Play's billing system
- Payment details received and handled by Google; the app cannot see them
- The app only receives the purchase details it needs from Google Play to complete a donation and stores none of them
- Google's privacy policy applies to payments

**Permissions**
- Internet: connecting to the servers you choose
- Notifications, foreground service: keeping SSH sessions connected when you leave the app, showing them in a notification
- Biometrics: unlocking the app lock with your fingerprint; biometric data handled by Android, no access for the app
- Vibration: the terminal bell

**Sharing with third parties**
- None

**Children**
- Not directed at children under 13

**Changes**
- Changes to this policy posted on this page

**Contact**
- dev@luftaquila.io
