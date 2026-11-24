# 계정 로그인 수단 연결

사용자 확정 범위: 기존 계정에 Google·카카오 로그인 연결/해제를 추가한다. 이메일이 같다는 이유로 계정을 자동 병합하지 않는 기존 기술 문서의 원칙을 유지한다.

## 화면과 API

계정 설정의 `AccountConnections`가 연결 상태, 환경별 이용 가능 여부, 재인증 폼을 관리한다. 일반 USER·ADMIN 계정만 사용할 수 있고 임시 체험 계정에는 제공하지 않는다.

| API | 요청/응답 |
| --- | --- |
| `GET /api/v1/member/connections` | 비밀번호 로그인 여부, 메일 모드, Google·카카오별 연결·환경 설정·해제 가능 여부 |
| `POST /api/v1/member/connections/proof` | 등록 이메일에 일회용 확인 코드 발송. 1분 이내 재요청은 429 |
| `POST /api/v1/member/connections/{provider}/start` | `{password}` 또는 `{code}`. 성공하면 고정된 자체 OAuth 시작 경로 `authorizationUrl` 반환 |
| `POST /api/v1/member/connections/{provider}/disconnect` | 같은 재인증 입력. 성공하면 모든 로그인 세션을 만료시키고 프런트는 로그인으로 이동 |

연결 완료/실패는 `/settings?connection=success` 또는 `failed`로 돌아온 뒤 서버의 연결 상태를 다시 조회한다. 쿼리 문자열은 결과 안내에만 쓰며 연결 권한을 부여하지 않는다. 기존 일반 OAuth 로그인은 `/auth/callback` 흐름을 유지한다.

## 보안 경계

- API의 사용자 ID는 검증된 자체 JWT에서 얻는다. 요청 본문으로 연결 대상을 지정할 수 없다. 기존 API 보안 정책처럼 Bearer 인증 요청은 쿠키 인증에 의존하지 않는다.
- 현재 비밀번호 또는 등록 이메일 소유를 확인한 일회용 코드로 재인증한다. 256비트 무작위 코드는 서버에 SHA-256 해시로 저장하고 10분 뒤 만료되며 한 번만 사용할 수 있다. 실패 제한은 로그인·비밀번호 변경·준비 삭제와 [같은 계정·접속 주소 예산](login-sessions-and-limits.md)을 공유한다. 목적별 코드의 소유자·제공자 바인딩은 유지한다.
- 연결 요청도 10분 동안 유효하다. 사용자, `security_version`, 제공자, 브라우저 세션의 무작위 바인딩, OAuth state를 함께 확인한다. 요청을 다시 사용하거나 다른 세션·제공자·state로 완료할 수 없다.
- `ConnectionOAuthFlow`는 Spring의 세션 기반 authorization request repository가 state를 확인한 요청에만 연결 문맥을 붙인다. 연결 의도를 callback 쿼리에서 받아들이지 않는다. 자체 JWT·확인 코드·연결 요청 ID를 리디렉션 URL에 넣지 않는다. 제공자가 반환하는 OAuth authorization code/state는 표준 callback에만 사용한다. 이 구현은 [Spring Security의 authorization request 저장 계약](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)과 [RFC 9700의 브라우저에 결합된 state 검증 원칙](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.7.1)을 따른다.
- `(provider, subject)`는 전체 계정에서 유일하고 사용자별 동일 제공자 연결도 하나다. 다른 사용자가 이미 보유한 소셜 계정을 가져오거나 동일 이메일을 근거로 계정을 합치지 않는다.
- 해제 후에는 비밀번호 또는 **현재 서버에 설정된 다른 연결 제공자**가 하나 이상 남아야 한다. 해제 시 security version 증가, refresh token·계정 티켓·진행 중 연결 요청/확인 코드 폐기로 모든 세션을 만료시킨다. 비밀번호 변경·재설정·탈퇴도 진행 중 연결 요청과 코드를 폐기한다.
- 사용자 행 잠금 후 로그인 수단을 재확인하므로, 해제 직전에 소셜 식별자를 조회한 로그인 요청이 해제 완료 뒤 새 세션을 발급할 수 없다. 로그인 계정 전환·로그아웃 시 브라우저 OAuth 세션도 폐기한다.
- 브라우저 OAuth 세션 쿠키는 HttpOnly/SameSite=Lax이며 `AUTH_SECURE_COOKIE`를 따른다. 운영에서는 HTTPS와 secure cookie를 유지한다.

## 환경과 검증 범위

- 2026-11-07 구현 규칙 보완: `OAuthEndpoints`가 프런트 복귀·OAuth callback origin을 설정값으로 고정한다. 요청의 Host/forwarded 헤더로 callback을 만들지 않는다. `OAUTH_BASE_URL`을 생략하면 로컬은 `http://127.0.0.1:8080`, production은 `FRONTEND_URL`을 사용한다. 운영에서는 두 origin이 같아야 하며 HTTPS 기본 포트만 허용한다. localhost 개발 콜백을 사용한다면 명시적으로 `OAUTH_BASE_URL=http://localhost:8080`을 설정하고 제공자 콘솔에도 같은 URL을 등록한다.
- 제공자 ID/secret 중 하나만 설정하면 시작 검사에서 실패한다. 둘 다 없을 때만 해당 제공자를 비활성화한다. 등록된 callback은 Google·카카오 각각 `{OAUTH_BASE_URL}/login/oauth2/code/google`, `/kakao`이며, Google 동의 화면/카카오 앱의 실제 운영 허용 조건은 운영자가 별도로 확인한다.
- `RefreshCookie`가 비밀번호·OAuth HTTP 진입점의 HttpOnly/Secure/SameSite/Path/만료 계약을 공유한다. OAuth 완료 handler가 Controller를 호출하던 경로는 제거했다. 인증 응답과 OAuth 복귀는 `Cache-Control: no-store`이며 서비스 토큰을 주소에 넣지 않는다.

- `GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET`이 있는 제공자만 연결을 시작할 수 있다. 기존 OAuth callback 등록 및 이메일 동의 설정을 사용한다. 연결 때 선택할 계정을 명확히 하도록 `prompt=select_account`를 사용한다. [Google 공식 OAuth 안내](https://developers.google.com/identity/protocols/oauth2/web-server), [카카오 공식 REST API 안내](https://developers.kakao.com/docs/ko/kakaologin/rest-api).
- SMTP 미설정 상태에서는 비밀번호 재인증을 사용할 수 있다. 로컬 `local-sample` 모드의 이메일 확인 코드는 기존 AccountMail 경로 `.tools/account-mail`에 기록된다. 메일 파일은 사이트에서 제공하지 않는다.
- 실제 PostgreSQL 통합 테스트: 연결 성공/충돌, 교차 사용자·제공자·state·브라우저 거부, 만료·재사용 거부, 재인증 제한, 마지막 사용 가능한 수단 보호, 해제/비밀번호 변경/탈퇴 무효화, 일반 로그인 유지. 별도 커밋된 fixture와 독립 트랜잭션으로 로그인·해제 경합을 재현한다.
- React 테스트: 임시 계정 숨김, 환경 미설정 안내, 재인증/메일 코드, 실패 입력 유지, 마지막 수단 해제 금지, 명시적 해제 확인 및 로그인 복귀.
- 테스트는 제공자 응답 fixture와 모의 메일을 사용한다. 실제 Google·카카오 계정의 동의·연결/해제, 실제 SMTP 수신을 검증했다고 간주하지 않는다. 운영 계정으로 이 절차를 시험하는 것은 별도 확인 사항이다.

### 운영 도메인 확인 순서

1. 실제 도메인과 각 제공자 등록 redirect가 정확히 일치하는지 확인한다. 설정값은 서버에서만 읽고 프런트 번들·로그로 내보내지 않는다.
2. Google·카카오 각각 신규 사용자, 기존 연결 사용자, 동일 이메일의 다른 계정, 이메일 미동의, 사용자 취소를 확인한다. 동일 이메일 자동 병합이나 토큰 URL 노출이 없어야 한다.
3. 새로고침 세션 복원 → 로그아웃 → 이전 토큰/쿠키 재사용 거부, 계정 연결/해제 → 마지막 로그인 수단 보호를 확인한다.
4. iPhone Safari·Android Chrome·데스크톱 Chrome에서 동일 흐름을 확인한다. 모의 provider 테스트 통과를 이 실연동 결과로 대신하지 않는다.
