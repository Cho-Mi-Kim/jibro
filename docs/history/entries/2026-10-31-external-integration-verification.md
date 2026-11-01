# 외부 로그인 및 LH 실서비스 연동 검증

- 기록일: 2026-10-31 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 실제 로그인·수집 및 로그인 유지·로그아웃 확인 완료, Chrome Google 정체 원인 미확정
- 유형: 문제 해결 / 개발 / 문서
- 담당·검토: 사용자 외부 키 설정·본인 인증, Codex 요청·응답 및 DB 검증·수정, 팀 검토자 미확인
- 관련 이슈·PR·커밋: [PR #12](https://github.com/Cho-Mi-Kim/jibro/pull/12), 작업 브랜치 fix/social-login-lh-integration-20261031

## 배경·문제

사용자가 Google·카카오·LH 키 설정 후 실제 연동 확인을 요청했다. 값 자체를 출력하지 않고 5개 항목의 존재와 형식을 확인했다. 백엔드 상태 및 DB가 UP이고 두 로그인 제공자가 활성화된 것을 확인했다. 프론트엔드는 중지되어 있어 재시작했다.

## 고려한 선택지

- 설정 인식만으로 성공 판정하지 않고 실제 제공자 응답과 DB 저장까지 확인한다.
- LH의 403 응답에 대해 승인 문제와 이중 인코딩을 구분한다. 인코딩 흔적을 확인한 뒤 메모리에서 디코딩한 값으로 먼저 검증한다.
- 소셜 로그인은 본인 계정 선택·개인정보 제공 동의를 사용자에게 맡기고, 요청 수락과 최종 로그인 성공을 구분한다.

## 결정·이유

LH 키는 URL 인코딩된 값이어서 서버에서 이중 인코딩되고 있었다. 메모리에서 한 번 디코딩한 요청이 HTTP 200을 반환한 뒤 `.env`의 해당 값만 Decoding 형식으로 정규화했다. 실제 값을 문서·Git에 기록하지 않는다.

## 변경·해결 과정

- 초기 LH 응답은 HTTP 403, SERVICE_KEY_IS_NOT_REGISTERED_ERROR(30).
- 키 교정 후 200·SS_CODE=Y, 최초 검색 응답의 dsSch는 요청 의도와 다르게 최근 2개월이었다.
- 잘못 사용한 응답 필드 PAN_NT_ST_DT·CLSG_DT 대신 검색 필드 PAN_ST_DT·PAN_ED_DT(yyyyMMdd)를 적용했다. 한국 날짜 기준 20260731~20261031, 총 276건 응답을 확인했다.
- 날짜 범위·키 단일 인코딩 및 실제 응답 envelope 파싱을 위한 회귀 테스트 2건을 추가했다.
- 새 빌드를 기존 8080 서버에 반영했다. DB를 초기화하지 않았다.
- 카카오 실패 원인을 확인하기 위해 안전한 OAuth 진단을 추가했다. 예외 전문·토큰·개인정보·호출 IP 대신 오류 코드, 예외 종류, 허용 목록의 원인 분류만 기록한다. 실패한 OAuth 세션은 폐기한다.
- 화면의 제공자 인증 오류와 회원 생성·연결 오류를 구분했다. 미가입 사용자는 정상 소셜 인증 후 자동 생성되므로 이번 실패 원인으로 취급하지 않는다.
- 외부 키가 설정된 로컬 환경에서도 CollectionIntegrationTest는 빈 LH 키를 강제해 실제 외부 API를 호출하지 않게 했다.

## 검증·결과

- `gradlew.bat test --tests org.jibro.collection.LhNoticeClientTest bootJar --console=plain`: 성공, 수집 클라이언트 테스트 4건 통과 및 JAR 빌드.
- 관리자 수집 API 2회 모두 SUCCEEDED·276건 처리. LH 행 수 0→276→276, 기본/안내 검수 상태 HOLD/HOLD 276건.
- 관리자 검수 목록 조회 279건: 기존 샘플 3건과 실제 LH 276건. 실제 공고를 승인·공개하지 않았다.
- 검증용 합성 계정만 임시 ADMIN으로 승격했다. finally에서 USER로 회수하고 해당 계정의 갱신 토큰을 폐기했다. 수집 이력 FK를 위해 계정과 실행 이력은 보존했다.
- Google: 앞선 계정 선택 후 accounts.google.com에서 두 차례 정체가 있었으나, 이후 사용자가 실제 로그인 성공을 보고했다. DB에서도 Google 연결 계정 1건과 갱신 토큰 발급 이력 2건을 확인했다. 조회 당시 revoked=false이면서 expires_at > now()인 갱신 토큰은 0건이었다. 제공자 단계의 앞선 정체 원인은 확정하지 않았다.
- 카카오: 실제 동의 후 provider 오류로 돌아왔다. 안전한 진단에서 invalid_user_info_response, HTTP 401, 제공자 코드 -401 및 ip_restricted를 확인했다. 호출 허용 IP에 사설 IP를 등록했음을 사용자 답변으로 확인했다. 공인 IPv4로 변경한 후 사용자가 로그인 정상 동작을 보고했고, DB에서도 카카오 연결 계정 1건과 갱신 토큰 발급 이력 2건을 확인했다. 조회 당시 두 토큰은 모두 revoked=true였으며 폐기 사유·현재 브라우저 세션 상태는 별도로 확인하지 않았다.
- `gradlew.bat test bootJar`: 백엔드 단위 10건 및 JAR 빌드 성공. 추가된 진단 테스트는 예외의 비밀값·개인정보가 출력되지 않고 정해진 코드만 남는지 검증한다.
- `npm run ci`: 포맷·린트·타입·테스트 15건·빌드 성공.
- `gradlew.bat integrationTest`: 별도 jibro_test에서 11건 성공. 실제 키가 설정된 환경에서도 수집 통합 테스트의 키 없음 사례가 외부 호출 없이 통과했다.
- 사용자는 카카오 허용 IP 설정 완료를 보고했다. 이후 확인한 서버 로그에도 ip_restricted가 남아 있어 적용한 키·IP 재확인을 요청했다. Chrome 자동 제어 연결이 끊겨 탭 재연결·세션 재설정으로도 복구되지 않았으며, 사용자 직접 로그인 결과와 서버 기록을 대조하는 방식으로 전환했다.
- 외부 IP 조회 서비스 요청은 자동 승인 검토에서 목적지에 대한 네트워크 식별자 전송 승인 부족으로 거절되어 실행하지 않았다. 다른 조회 서비스로 우회하지 않고 사용자 설정 확인을 요청했다.

## 기여·한계

키 발급·본인 인증·카카오 호출 허용 IP 변경은 사용자 수행이며 코드 수정과 실행 조사는 AI 보조 범위다. 최초 Google·카카오 로그인 성공은 사용자 보고와 DB 계정·토큰 발급 이력으로 확인했다. 추가 검증에서 Google의 Edge 새로고침·로그아웃은 사용자가 확인했고 카카오의 Chrome 새로고침·로그아웃은 Codex가 직접 조작했으며, 양쪽 모두 서버 토큰 집계를 대조했다. 전체 UI 기능을 이번 작업에서 반복하지 않았으며 실제 LH 호출은 CI 테스트에 포함하지 않는다. 카카오 관리 콘솔의 접근 허용 범위는 임의 변경하지 않았다.

## 발표·포트폴리오 메모

공식 외부 응답과 합성 테스트의 차이를 실호출에서 발견하고, 인증키 형식과 검색 필드를 교정하여 수집·저장·중복 방지를 검증했다. 사용자 효과나 운영 안정성 전체를 입증한 것으로 확대하지 않는다.

## 후속 작업

- Google 제공자 화면 정체가 재발하면 당시 요청·응답을 확인한다. 이번 성공만으로 앞선 정체 원인을 단정하지 않는다.
- 네트워크가 바뀌면 카카오 호출 허용 IP를 재확인한다. 새로고침·로그아웃 검증은 아래 추가 기록으로 완료했다.
- 실제 공고 기본 정보·준비 안내 검수 및 공개는 별도 업무다.

## 변경 파일

- backend/src/main/java/org/jibro/collection/LhNoticeClient.java
- backend/src/test/java/org/jibro/collection/LhNoticeClientTest.java
- backend/src/test/java/org/jibro/collection/CollectionIntegrationTest.java
- backend/src/main/java/org/jibro/auth/OAuthConfig.java
- backend/src/main/java/org/jibro/auth/OAuthFailureDiagnostics.java
- backend/src/test/java/org/jibro/auth/OAuthFailureDiagnosticsTest.java
- frontend/src/pages/AccountPages.tsx
- docs/architecture/backend-contracts.md
- docs/getting-started.md
- docs/history/entries/2026-10-31-external-integration-verification.md
- docs/history/README.md
- docs/verification.md

## 추가 검증: 새로고침 유지·로그아웃

- 사용자 요청에 따라 두 제공자의 로그인 유지·로그아웃을 확인했다. Chrome 자동 제어 연결은 이번에는 정상 연결되었다.
- Google 계정 선택은 사용자에게 맡겼으나 Chrome의 제공자 화면에서 정체가 재발했다. 사용자가 Edge에서는 성공한다고 설명했다. Edge는 자동 제어 대상에 없었으므로 화면 확인은 사용자 수행, 서버 확인은 Codex 수행으로 나눴다.
- Google / Edge: 사용자가 로그인 후 마이 새로고침 시 이름·로그아웃 버튼 유지, 로그아웃·새로고침 후 내 준비 클릭 시 로그인 화면 이동을 각각 확인했다.
- 카카오 / Chrome: 로그인 버튼으로 실제 인증 후 내 준비 복귀, 마이의 로그아웃 버튼 표시, 전체 페이지 새로고침 후 동일 버튼 유지를 Codex가 확인했다. 로그아웃 시 홈 이동, 다시 새로고침 후 내 준비 클릭 시 로그인 화면 이동을 직접 확인했다.
- 로그인 유지 시 DB 집계: Google 연결 계정 1건·발급 이력 6건·유효 갱신 토큰 1건, 카카오 연결 계정 1건·발급 이력 5건·유효 갱신 토큰 1건.
- 로그아웃 후 DB 집계: Google 6건·카카오 5건 모두 revoked=true, 미폐기·미만료 갱신 토큰은 각각 0건. 개인정보나 토큰 값은 출력하지 않았다.
- 기존 자동 테스트 재실행: `gradlew.bat integrationTest --tests org.jibro.auth.AuthIntegrationTest --console=plain` 2건 성공, `npm run test:run -- src/shared/api/transport.test.ts` 5건 성공. 제품 코드는 이번 추가 검증에서 변경하지 않았다.
- 카카오 로그아웃 후 로그인 화면 증거는 로컬 `.tools/social-logout-verification.png`에 보관했다(Git 제외).
- 범위 한계: Google Chrome 정체 원인, 제공자 자체 세션 종료, 다른 기기 전체 로그아웃, 기발급 접근 토큰 즉시 무효화는 이번 성공 판정에 포함하지 않는다.

## 변경 제출 단위

사용자 요청으로 최신 develop과 같은 기준 커밋에서 `fix/social-login-lh-integration-20261031`을 만들었다. DB 실행 안내, LH 수집 요청·회귀 테스트, 소셜 로그인 진단·안내 및 검증 결과를 세 커밋으로 나눴다. 이 기록은 두 외부 연동의 통합 검증 근거이며 LH 수정 커밋에서 처음 포함하고 소셜 로그인 수정 커밋에서 제출 단위를 보완했다. 기존 사용자 화면 이미지 10개와 개인 `.vscode/` 변경은 제출 대상에서 제외했다. 실제 외부 키·로컬 실행 로그·검증 스크린샷은 Git에 포함하지 않는다. PR 대상은 develop이며 병합은 이번 요청 범위에 포함하지 않는다.

## PR #12 검토 — 2026-10-31

- 사용자 요청으로 PR #12의 13개 파일 변경과 최신 head `0119fbf7037845a39cf93ff443d3e1ffd3448501`을 확인했다. develop 기준은 `a8f084748944b3a47ac20f888763aa6864be8ae3`이며 충돌은 없다.
- LH 날짜 요청 필드·한국 날짜·단일 키 인코딩, 합성 키 회귀 테스트, 외부 키를 강제로 비우는 통합 테스트를 검토했다.
- OAuth 실패 세션 폐기, 예외 원문 대신 제한된 코드·분류만 출력하는 진단과 테스트, 프론트 오류 안내 분기를 검토했다. API·DB 스키마 변경 및 비밀키·개인 IDE·화면 이미지 포함은 없다.
- 검토 범위에서 병합을 막을 결함은 발견하지 못했다. Google Chrome 계정 선택 정체는 재현된 후속 조사 항목이며 해결로 기록하지 않는다.
- 원격 CI 실행 `41100431833`은 project-history·frontend·backend 모두 queued 상태로, 도구와 GitHub CLI로 대조했다. 실행 성공 확인 전에는 병합하지 않는다.
- 이후 CI는 queued에서 in_progress로 전환되었지만, 2026-10-31 02:50 KST 최종 조회까지 완료 결과는 없었다. 세 작업의 개별 조회에서는 in_progress와 빈 steps가 확인되었으며, GitHub API 502 및 도구 조회 시간 초과도 반복되었다. 지연 원인은 확정하지 않는다.
- 원격 CI 통과를 확인하지 못했으므로 PR 병합은 보류했다. 검토한 head는 그대로이며, 추가 제품 코드 변경·CI 우회·강제 병합은 하지 않았다. 이 검토 메모는 로컬 작업 기록에 남기고 커밋·push하지 않았다.

## PR #12 CI 통과·develop 병합 — 2026-10-31

- 사용자 재확인 요청으로 동일 head `0119fbf7037845a39cf93ff443d3e1ffd3448501`의 CI 실행 `41100431833`을 확인했다. 전체 실행과 project-history·frontend·backend 세 작업 모두 completed/success였다.
- 이미 검토한 head 및 develop 기준이 그대로이고 충돌이 없어 merge commit 방식으로 병합했다. GitHub 연결 도구의 쓰기 권한 부족(403)으로 기존 GitHub CLI를 사용했으며, `--match-head-commit`으로 검토 대상 변경 시 병합을 막았다.
- GitHub에서 PR merged=true 및 병합 시각 2026-10-31 02:59:29 KST를 확인했다. 원격 develop 병합 커밋은 `c78d0fa5619780b016ad5358dce06e43832deee7`이다.
- fetch 후 `git diff --exit-code 0119fbf7037845a39cf93ff443d3e1ffd3448501 origin/develop --`에서 차이 없음(종료 코드 0)을 확인했다. 병합 결과의 파일 내용이 CI 통과 head와 같으므로 같은 테스트를 반복하지 않았다.
- 작업 브랜치를 삭제하지 않았으며, 기존 `.gitignore`·화면 이미지 변경을 보존했다. 이 사후 검토·병합 메모는 로컬에만 남겼으며 별도 커밋·push하지 않았다. Google Chrome 로그인 정체는 기존 후속 조사 항목으로 유지한다.
- CI 근거: https://github.com/Cho-Mi-Kim/jibro/actions/runs/41100431833
