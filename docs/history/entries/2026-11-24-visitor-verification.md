# 첫 접속 Cloudflare 사람 확인

## 운영 연결 완료 (2026-11-24 00:56 KST)

사용자가 위젯 생성 완료 후 실제 기존 키를 받아 운영 서버에 연결했다. 키는 채팅에 재출력하지 않고 사용자 전용 로컬 파일과 서버 root600 파일로 보관했다. 기존 환경을 그대로 복사한 새 배포 설정에 Turnstile 항목만 추가하고, 기존 불변 이미지/환경 사본을 복구용으로 보존했다. /opt/jibro/current는 release-turnstile-20261124를 가리킨다. 앞으로 운영 설정은 활성 경로를 먼저 확인해야 한다.

운영 적용 후 첫 방문의 확인 필요 상태·쿠키 없는 API403, 실제 브라우저에서 Cloudflare 자동 검증 통과와 서버 verified=true를 확인했다. 새로고침 재확인 생략 및 공고117건 목록 표시도 확인했다. DB/서버 건강상태UP, HTTPS 기본 검사와 보안 헤더 정상. 기존 Turnstile 외 환경값·복구 사본 불변 및 DB 마이그레이션43개 동일 확인. 실제 12시간 경과 관찰은 아니며 유지시간은 구현/만료 검사의 계약이다. 로그인 권한은 별개이며 이번에는 실제 소셜 재로그인·메일 발송을 재실행하지 않았다.

증거 .tools/qa/turnstile-activation.log, turnstile-final-verification-20261124.json, turnstile-live-passed-20261124.jpg. 첫 접속 화면은 AX에서 확인됐고, 자동 통과가 빨라 first-visit 스크린샷은 이미 통과 후 홈이다. 키 없는 로컬5173은 비활성 유지하며 운영 jibro.live만 활성화했다. 커밋/push/네임서버 변경 없음. 아래는 구현·준비 당시의 이력이다.

## 배경·결정
사용자가 사이트 최초 접속에 Cloudflare 사람 확인을 요청했고 기존 계정이 있다고 답했다. Cloudflare 공식 Turnstile 문서의 독립 사용·서버 검증·hostname/action·만료 조건을 확인하고, 기존 Hosting.kr DNS와 AWS 경로를 유지하는 Managed 위젯을 채택했다. WAF 프록시 이전과 사이트 진입 확인을 구분한다.

## 구현
- `security/VisitorVerification`은 로그인과 별도의 host-only HttpOnly/Secure/SameSite=Lax 쿠키를 12시간 부여한다. HMAC에 사이트·용도·브라우저·발급시각을 포함한다. 키가 없는 활성 설정과 공개 dummy 키는 시작 단계에서 거부한다.
- `TurnstileVerifier`는 공식 Siteverify에 token을 보내 success/hostname/action=site_entry를 확인한다. 서버 연결·응답 제한과 동시 요청 상한을 적용하고 토큰/비밀키를 로그에 남기지 않는다.
- `VisitorVerificationFilter`는 통과 전 API와 OAuth 시작을 막는다. 상태·CSRF·헬스만 예외다. 기존 Spring Security 로그인 권한과 CSRF는 유지한다.
- `main → VisitorGate → TurnstileWidget`은 앱 실행 전 확인하고, 쿠키 저장을 서버에서 확인한 뒤 원래 주소를 표시한다. 오류/만료는 재시도, 이용 중 만료는 입력 중인 화면을 숨겨 보존하며 저장 요청을 자동 재전송하지 않는다. 중복 신호를 합치고 시연 어댑터는 외부 호출하지 않는다.
- 설정 예제와 `docs/contracts/visitor-verification.md`, 배포 문서를 추가했다. 기본 비활성은 키 없는 기존 환경을 유지한다.

## 검증·현재 상태
- 백엔드 단위4·통합3 통과: 위조/만료/브라우저/쿠키 속성·CSRF·API 직접 호출·hostname/action·개인 API 권한 분리. 기존 로그인 보안 통합11개 통과.
- 프런트 새 경계/위젯/전송14개 통과. 기존 App70개도 통과했다. 처음 새 경계 검사2개는 테스트 환경 ResizeObserver 누락으로 실패했고, 보완 후 모두 통과했다. 타입/변경 lint·Prettier/계층10개 및 생산 빌드 성공. Java 대상 서식 적용 성공.
- backend bootJar 및 로컬8080/5173 재시작. 실제 visitor 상태 API는 enabled=false/verified=true로 기존 이용 유지 확인. 외부 실제 위젯 통과로 기록하지 않는다.
- Cloudflare 대시보드를 열고 사용자 직접 로그인 요청 중이다. 실제 키 발급·로컬/운영 실연동·운영 배포는 미완료다. 비밀키 없이 성공으로 처리하거나 공개 dummy 키를 활성화하지 않았다. 키 연결 후 실제 사람 확인과 12시간 재접속·로그인 흐름을 확인해야 한다.

## 기여·후속

## Cloudflare 로그인 확인 후 배포 준비
Cloudflare 로그인 후 위젯 등록 양식(jibro-production, jibro.live, Managed)을 준비했다. 도메인 선택의 자동 조작이 정상 반응하지 않아 사용자에게 도메인 추가와 Create 직접 진행을 요청했다. 목록에서 실제 위젯 생성은 아직 확인되지 않았으며 키 발급/실연동 완료로 기록하지 않는다. 준비 화면 .tools/qa/turnstile-widget-setup-20261124.jpg.

프런트 전체 ci 104파일870검사 및 서식/lint/계층/타입/빌드가 통과했고 최신 backend bootJar를 만들었다. 기존 운영 패키지와 DB 마이그레이션43개가 동일하다. 비밀값 없는 배포 패키지252파일을 서버 별도 디렉터리 /opt/jibro/release-turnstile-20261124에 검증·준비하고 backend/web 이미지만 빌드했다. 기존 서비스·설정·DNS·DB는 변경하지 않았다. 실제 키 연결과 운영 활성화는 사용자 위젯 생성 후 이어간다.
AI가 설계·코드·검사·기록을 보조했다. 사용자 계정 로그인/인증키 발급 최종 단계는 사용자 진행이 필요하다. 커밋/push/네임서버 변경·운영 배포는 수행하지 않았다. 다음은 Cloudflare 로그인 완료 후 Managed 위젯 등록을 준비하고 키를 로컬 전용 파일에 보관해 연결하는 단계다.
