# Google·카카오 로그인 연결

집으로는 Spring Security의 인가 코드 흐름을 사용한다. 프런트 버튼은 `/api/v1/auth/providers`에 등록된 제공자만 활성화한다. 외부 연결 설정과 실제 로그인 성공은 구분한다.

## 로컬 주소와 비밀 설정

현재 로컬 접속은 `http://127.0.0.1:5173`으로 통일한다. `localhost`와 `127.0.0.1`은 쿠키와 브라우저 저장소를 공유하지 않으므로 섞지 않는다.

프로젝트 루트의 Git 제외 파일 `.env`에 아래 이름을 사용한다. 키는 채팅·프런트 코드·문서·Git에 넣지 않는다. 제공자마다 ID와 Secret을 모두 입력한 뒤 서버를 재시작한다. 둘 중 하나만 있으면 서버가 시작하지 않는다.

```dotenv
FRONTEND_URL=http://127.0.0.1:5173
OAUTH_BASE_URL=http://127.0.0.1:8080
AUTH_SECURE_COOKIE=false
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
```

## Google

Google Auth Platform에서 앱 이름·지원 이메일·개발자 연락처를 정하고 외부 사용자 앱으로 등록한다. 웹 애플리케이션 유형의 OAuth 클라이언트를 생성한다.

- 리디렉션 URI: `http://127.0.0.1:8080/login/oauth2/code/google`
- 발급한 클라이언트 ID와 Secret을 각각 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`에 입력한다.
- 현재 범위는 `openid profile email`이다. 브라우저 JavaScript SDK가 아닌 서버 방식이므로 JavaScript 원본 등록은 이 구현의 필수 항목이 아니다.
- 실제 운영 도메인을 정한 뒤 별도 운영 리디렉션 주소와 공개 상태를 검토한다. 현재 로컬 등록을 운영 공개로 간주하지 않는다.

근거: [Google 웹 서버 OAuth](https://developers.google.com/identity/protocols/oauth2/web-server), [Google 인증 플랫폼 설정](https://support.google.com/cloud/answer/15544987?hl=en), [앱 공개 상태와 기본 신원 범위 예외](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview?hl=en).

## 카카오

앱을 등록하고 카카오 로그인 사용을 켠다. 플랫폼 키의 REST API 키와 해당 Client Secret을 사용한다. JavaScript 키나 Admin 키를 로그인 클라이언트 ID로 사용하지 않는다.

- 리디렉션 URI: `http://127.0.0.1:8080/login/oauth2/code/kakao`
- 현재 콘솔에서는 `[앱] → [플랫폼 키] → 해당 REST API 키 수정`의 카카오 로그인 리다이렉트 URI에 등록한다. 앱 대표 도메인에는 서비스의 대표 주소를 넣으며 Google·카카오 callback 경로와 구분한다.
- REST API 키 → `KAKAO_CLIENT_ID`, 해당 Secret → `KAKAO_CLIENT_SECRET`.
- 닉네임(`profile_nickname`)과 카카오계정 이메일(`account_email`) 동의항목을 설정한다. 이메일 권한이 없는 앱은 현재 요청 범위와 맞지 않아 KOE205가 발생할 수 있다.
- 신규 가입은 검증된 이메일이 필요하다. 이메일 설정은 비즈 앱 또는 테스트 앱 권한을 확인한다. 개인 개발자도 본인인증과 카카오비즈니스 약관 동의를 거쳐 비즈 앱으로 전환할 수 있다. 테스트 앱은 멤버에게만 제공된다.
- 비즈 앱 전환에는 앱 아이콘 등록이 선행된다. 사업자등록번호가 없는 경우 개인 개발자 전환을 사용한다. 등록 단계에서 이메일 권한이 없으면 키 연결만으로 가입이 완성되지 않으며, 이메일 검증이나 계정 자동 병합 규칙을 우회하지 않는다.
- 현재 구현은 일반 OAuth 사용자 정보 조회이며 카카오 OpenID Connect를 켤 필요는 없다.

근거: [카카오 로그인 사전 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [앱·비즈 앱 설정](https://developers.kakao.com/docs/ko/app-setting/app), [동의항목](https://developers.kakao.com/docs/ko/kakaologin/utilize).

## 실행 확인

1. 재시작 후 `/api/v1/auth/providers`에 입력한 제공자가 표시되는지 확인한다.
2. 집으로 로그인 버튼에서 실제 제공자 화면으로 이동하고 계정 로그인·동의를 마친다.
3. 집으로 원래 화면 복귀, 새로고침 후 로그인 유지, 로그아웃을 확인한다. 키 값·토큰·쿠키를 로그에 출력하지 않는다.
4. 취소/실패 후 다시 시도할 때도 원래 화면 경로를 유지한다. 외부 사이트로 향하는 임의 복귀 주소는 차단한다.

같은 이메일의 기존 계정과 소셜 계정은 자동 병합하지 않는다. 기존 계정은 먼저 로그인한 뒤 계정 설정에서 소셜 연결을 진행한다. 제공자의 동의를 취소하거나 이메일 검증·권한이 부족한 결과를 성공으로 표시하지 않는다.

운영은 HTTPS 단일 출처·Secure 쿠키가 필수이고, `OAUTH_BASE_URL`과 `FRONTEND_URL`은 같아야 한다. Google·카카오 모두 운영 주소를 정확히 등록한 뒤 별도 왕복 검증을 한다.
