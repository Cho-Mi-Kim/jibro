# 첫 접속 사람 확인

사용자 요청(2026-11-24)에 따라 Cloudflare Turnstile Managed 위젯으로 사이트 첫 접속을 확인한다. 도메인 네임서버를 이전하지 않아도 사용할 수 있다. Cloudflare WAF/DDoS 프록시나 접속 자격 판정을 대신하지 않는다.

## 실행 흐름

- main의 VisitorGate는 사용자 세션과 화면을 처음 실행하기 전 GET `/api/v1/security/visitor`를 조회한다. 비활성 또는 유효한 통과 쿠키이면 기존 주소로 바로 들어간다. 시연 어댑터는 외부 확인을 호출하지 않는다.
- 활성 상태에서는 공식 스크립트 `https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit`를 직접 읽고, 한국어 Managed 위젯에 action=`site_entry`를 전달한다. 실패·만료·연결 지연은 다시 시도를 제공한다.
- POST 같은 주소에 `{token}`을 CSRF 헤더와 함께 보낸다. 서버는 Siteverify에 토큰을 전달하고 success, FRONTEND_URL의 정확한 hostname, action을 모두 확인한다. 토큰 재사용·만료는 Cloudflare가 거부한다. 비밀키/토큰을 로그에 남기지 않는다.
- 성공 시 host-only `jibro_visitor` HttpOnly/SameSite=Lax/Secure 쿠키를 최대 12시간 부여한다. 사이트·용도·브라우저 정보와 발급 시간을 HMAC으로 서명하며 로그인 권한은 부여하지 않는다. 쿠키 차단 시 통과한 것처럼 화면을 열지 않는다.
- VisitorVerificationFilter는 확인 전 API 직접 호출과 OAuth 시작을 403/HUMAN_VERIFICATION_REQUIRED로 거부한다. 상태·CSRF·헬스 조회만 예외다. OAuth callback 경로는 외부 제공자의 기존 state/session 검증을 유지한다.
- 이용 중 쿠키가 만료되면 전송 계층의 별도 신호로 다시 확인한다. 입력 중인 화면은 숨긴 채 유지하며 저장 요청은 자동 재전송하지 않는다. 로그인 만료와 혼동하지 않는다.

## 연결 설정

backend 전용 환경 파일:

```
TURNSTILE_ENABLED=true
TURNSTILE_SITE_KEY=<공개 사이트키>
TURNSTILE_SECRET_KEY=<서버 전용 비밀키>
```

Cloudflare에서 위젯 이름 `jibro-production`, hostname `jibro.live`(필요한 경우 `www.jibro.live`), Managed 모드로 등록한다. 로컬 검증은 별도 `jibro-local` 위젯에 `127.0.0.1`/`localhost`를 등록하고 개발 환경 파일에만 저장한다. 실제 접속 주소와 FRONTEND_URL의 hostname이 같아야 한다. 키는 채팅·Git·frontend 환경 파일에 넣지 않는다. 이 앱은 모두 통과하는 공개 테스트키로 보안을 활성화하지 않는다.

기본값 false는 아직 연결하지 않은 기존 실행 환경을 보존하기 위한 값이다. true인데 필수 설정이 빠지거나 공개 테스트키이면 서버가 시작하지 않는다. 운영 반영은 기존 배포·복구 절차를 따른다. 연결 불가 시 확인을 우회하지 않으며, 운영자가 기능을 비활성화하는 복구는 명시적으로 수행해야 한다.

## 검증 범위

VisitorVerificationTest/IntegrationTest는 위조·기한·브라우저·쿠키 속성, 출처/용도 불일치, CSRF, API 직접 우회, 확인 후에도 개인 API 로그인 필요를 검사한다. VisitorGate/TurnstileWidget/transport 검사는 확인 전 실행 차단, 오류·쿠키 차단·만료·입력 유지·중복 신호·공식 위젯 해제를 확인한다. mock 통과는 실제 Cloudflare 키 연결이나 운영 접속 성공으로 기록하지 않는다.

공식 안내: [시작하기](https://developers.cloudflare.com/turnstile/get-started/), [서버 검증](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/), [위젯 설정](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/).
