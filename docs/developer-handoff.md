# 개발 인수인계 — 2026-11-15 당시 구현 현황과 다음 작업

> 과거 인수인계 기록입니다. 아래 미배포·미연결 및 H01~H12 상태는 2026-11-15 기준입니다. 이후 로컬·운영 작업은 [2026-11-24 업로드 기록](history/entries/2026-11-24-team-review-upload.md), [운영 기능 반영 기록](history/entries/2026-11-24-site-basics-implementation.md), [작업 이력](history/README.md)을 확인하세요.

최종 대조일: **2026-11-15 (Asia/Seoul)**. 11월 10일 초안을 PR #26 검증 커밋 `69fd749` 기준으로 갱신했다. main/Pages와 PR의 구현 기준을 구분한다. 이 문서는 전달용 현황과 착수 순서를 관리하며, API의 상세 계약은 별도 계약 문서를 따른다. 우선순위·완료 기준은 인수인계를 위한 제안이고 담당자·일정은 아직 배정하지 않았다.

## 1. 현재 상태부터 확인하기

**핵심 웹 기능과 로컬 API·DB 연결은 구현되어 있고, 공개된 것은 GitHub Pages의 가상 데이터 시연이다. 실제 서비스 서버를 AWS에 배포한 상태는 아니다.**

| 기준 | 확인한 상태 |
| --- | --- |
| 코드 대조·검증 기준 | [PR #26](https://github.com/Cho-Mi-Kim/jibro/pull/26)의 `69fd7496f91e247c1facc5f42c74642a04dbc793`. PR #24 이후 추가 기능과 충돌 해결 포함 |
| 병합 확인 | PR #26은 develop `8428e78`에 병합됐고 검증한 `69fd749`와 파일 내용이 동일하다. 문서 후속 작업은 [PR #27](https://github.com/Cho-Mi-Kim/jibro/pull/27)에서 별도로 검토한다. 새 작업 전 최신 develop을 받는다. |
| main·공개 시연 기준 | 이번 확인 시 main은 PR #25의 `90a1535`. PR #26의 develop 반영이 main/Pages 배포를 뜻하지 않는다. |
| 공개 화면 | [Pages 시연 사이트](https://cho-mi-kim.github.io/jibro/) — 가상 데이터, 실제 서버 저장·외부 로그인 아님 |
| 최신 원격 검사 | [69fd749 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43724321929): frontend·backend·operations·project-history 모두 통과 |
| 로컬 검증 | 프론트 69개 파일 419건, 백엔드 단위·구조 668건 및 PostgreSQL 통합 358건 통과. OCR 테스트 후속 수정은 관련 5건 재실행 통과 후 Linux 전체 CI로 확인 |
| 과거 공개 사이트 확인 | 11월 10일 PR #25 Pages 배포 성공 및 목록→상세→시연 사용자→조건 첫 질문 진입 확인. PR #26 화면의 공개 배포 검증으로 해석하지 않음 |

공개 사이트의 전체 기능을 실제 기기에서 재검증했다는 뜻은 아니다. 실제 Google·카카오 왕복 인증, SMTP·Web Push 실발송, 운영 DB 업그레이드·복구는 위 CI의 통과 범위와 다르다.

기술 구성은 React·TypeScript·Vite, Java 17·Spring Boot·Spring MVC·Spring Security·Gradle, PostgreSQL·JPA·OpenFeign Querydsl이다. 프론트는 VS Code, 백엔드는 IntelliJ를 사용한다. 최종 의존성은 `frontend/package-lock.json`·`backend/build.gradle`·Gradle Wrapper가 기준이다.

## 2. 이미 구현되어 있어 다시 만들 필요가 없는 항목

여기서 ‘구현’은 코드·경로·저장 처리가 있다는 뜻이다. 모든 외부 환경에서 검증을 마쳤다는 뜻은 아니다.

| 영역 | 현재 구현 | 주의할 경계 |
| --- | --- | --- |
| 공통 화면 | 홈·공고·내 준비·가이드·마이 반응형 화면, 준비도 %와 단계별 벽돌집 | 모바일 실기기·확대·키보드 검증은 별도 |
| 공고 탐색 | 복합 필터·검색·정렬·페이지, 미리보기·상세, 관심 공고, 공고 두 건 비교 | 비교는 자격 자동 판정·추천 점수가 아님 |
| 공개본 비교 | 공개된 공고의 버전 저장·변경 비교 | 저장된 과거 공개본이 없으면 비교 불가. 과거 전체 자료의 소급 복원은 없음 |
| 조건·서류 | 단일/복수 선택·숫자·날짜 질문, 모름/추가 확인, 준비 대상 서류·메모·공식 링크, 저장하지 않는 일회 확인 | 조건부 서류는 단일 질문+선택값 규칙. 숫자/날짜의 유효한 입력을 자격 충족으로 해석하지 않음 |
| 준비 관리 | 준비도·집 단계·다음 행동 서버 계산, 신청 여부 기록, 수정 공고 기준 갱신 | 준비도는 신청 자격·선정 가능성·실제 접수 완료가 아님 |
| 일정 | 공식 일정 표시, 상태 판별, 개인 기록, 선택 일정 `.ics` 내보내기 | 캘린더 파일은 복사본. 이후 정정이 외부 캘린더에 자동 반영되지 않음 |
| 보관함 | 보관·복원·경험 메모·본인 기록끼리 명시적 연결 | 100% 달성이나 기관 신청 완료를 보관 조건으로 강제하지 않음 |
| 인증 | 이메일·비밀번호, Google·카카오 OAuth, 토큰 재발급·로그아웃·권한·CSRF | 제공자 키 필요. 네이버는 미구현이며 기존 합의 로그인 제공자는 Google·카카오 |
| 계정 | 프로필·이메일 확인·비밀번호 변경/재설정·탈퇴, Google/카카오 연결·해제, 로그인 요청 제한 및 현재 세션 즉시 폐기 | 마지막 로그인 수단 보호·재인증·실제 제공자 왕복과 메일 수신 검증 필요 |
| 체험 | `local-sample` 서버 TRIAL 계정·24시간 만료·정리 | Pages 메모리 시연과 별개. 프로필 활성화가 가상 공고 생성을 의미하지 않음 |
| 알림 | 목록·읽음·수신 설정, 열린 사이트 기기 알림, 서버 이메일·Web Push 발송 코드 | 외부 실발송은 설정·동의·기기 검증 필요. 서버 발송은 실행 중인 서버에 의존 |
| 가이드 | 공개·검수 자료에서 가이드 생성, 검색·카테고리 | 편집형 가이드 CMS는 없음. 공개 자료가 없으면 빈 목록 가능 |
| 관리자 | 예약·수동 수집, 재시도·복구, 원문/첨부 추출·선택적 로컬 OCR, 첨부 변경 감지, 자동·수동 검수 및 감사 기록 | 추출과 공개는 별개. 자동 검수의 지원 범위·PARTIAL/HOLD·수동 원고 보호는 [자동 검수 계약](contracts/automatic-review.md) 참조 |
| 개발·배포 | 프론트/백엔드 별도 실행·빌드, DB 마이그레이션, PR CI, Pages 배포, 운영 패키징·설정 검사·백업/복구 도구 | [운영 준비](../deploy/README.md)는 구현됨. 실제 AWS 자원·HTTPS·RDS 검증 완료와 구분 |

API·설정의 상세 기준: [프론트 계약](architecture/frontend-contracts.md), [백엔드 계약](architecture/backend-contracts.md), [회원 기능 계약](contracts/member-features.md), [질문·서류 계약](contracts/question-options-and-documents.md).

## 3. 첫날 실행하고 확인할 것

기준 브랜치는 최신 develop이다. 새 작업은 develop에서 새 브랜치로 시작하고 **작업 브랜치마다 PR**을 만든다. 이전 PR #24의 브랜치에 새 기능을 누적하지 않는다. 의존 PR은 선행 작업부터 검토·병합한다.

실행 명령은 저장소 루트의 PowerShell 기준이며 [개발 시작 안내](getting-started.md)의 설치·초기화 절차를 먼저 따른다.

```powershell
# 시연: 프론트엔드만 실행
. .\scripts\Use-DevEnvironment.ps1
npm --prefix frontend ci
npm --prefix frontend run dev:demo -- --port 5174
```

```powershell
# 실제 API: Docker Desktop 실행, 최초 .env/DB 초기화 이후
docker compose up -d --wait postgres
.\scripts\Start-Backend.ps1
# 다른 터미널
.\scripts\Start-Frontend.ps1
```

| 확인 목적 | 환경·주소 |
| --- | --- |
| 화면 흐름·가상 데이터 | `http://127.0.0.1:5174` 또는 Pages. 새로고침하면 초기화 |
| 실제 계정·DB 저장 | `http://127.0.0.1:5173` |
| 소셜 버튼·연동 | `http://127.0.0.1:5173/login`, `/api/v1/auth/providers` |
| API·DB 상태 | `http://127.0.0.1:5173/development`, `http://127.0.0.1:8080/api/v1/health` |
| 서버 체험·테스트 메일(샘플 자동 생성 아님) | 백엔드를 `Start-Backend.ps1 -SampleData`로 실행. 기존 백엔드를 종료하고 재시작 |

- `.env`, `.tools`, 개발 DB·개인 계정은 Git clone에 포함되지 않는다. 기존 개발자 DB에 있던 공고가 새 개발자 PC에도 있다고 가정하지 않는다.
- 실제 공고 초기 자료는 [data/notices 안내](../data/notices/README.md)를 읽고 적용 전 대상 DB·버전을 확인한다. 스크립트의 `--apply`는 DB 변경이므로 내용 확인 없이 실행하지 않는다.
- 시연은 Google·카카오 버튼을 숨긴다. 실제 서버 모드에 제공자 키가 설정됐을 때 활성화된다. `VITE_API_MODE=demo`를 수동 설정한 환경에서는 제거/변경 후 재시작한다.
- 일반 프로필은 SMTP가 없으면 메일 기능이 unavailable일 수 있다. 로컬 `.eml`은 루프백+`local-sample`에서만 제공되며 저장소 루트 `.tools/account-mail`에 남는다.
- 서버 체험과 샘플 데이터를 운영 활성화 방법으로 사용하지 않는다. 운영은 별도 DB·환경으로 구성한다.
- `node scripts/project-history.mjs install`로 clone별 작업 기록 hook을 설치한다. 테스트 명령은 [README 검사·빌드](../README.md#검사빌드)를 따른다.

## 4. 다음 작업 목록과 완료 기준

**P1**은 팀의 실제 연동 테스트 또는 외부 서비스 공개 전에 필요한 일이다. 서비스 공개 계획이 아직 없다면 AWS부터 구현하기보다 실행 재현·공고 검수·핵심 회귀를 먼저 진행한다. **P2**는 신뢰성과 유지보수 개선, **선택**은 별도 범위 결정 후 진행할 확장이다. 아래 항목은 모두 현재 실패가 재현된 버그라는 뜻이 아니다.

| ID·우선순위 | 현재 상태·해야 할 일 | 완료 기준 | 시작 위치 |
| --- | --- | --- | --- |
| H01 · P1 · 환경 재현 | `.env.example`의 OAuth/LH/메일/VAPID/OCR 예시와 실행 안내를 새 개발자 환경에서 확인 | 두 실행 모드·새 DB·관리자·TRIAL과 샘플 제외를 팀원 한 명이 재현. 비밀값은 문서에 기재하지 않음 | `scripts/`, `.env.example`, [실행 안내](getting-started.md) |
| H02 · P1 · 공고 검수 | 수집량과 서비스 가능한 준비 안내를 구분하고 실제 공고의 날짜·질문·서류·공식 발급 링크를 재대조 | 대표 공고별 원문 위치·확인일·검수 담당·미지원 분기를 기록. 대상/비대상/모름/정정 후 재확인 사례가 기대대로 동작 | `data/notices/`, 검수 보고서, `AdminPage`, `NoticeService`, `PreparationRules` |
| H03 · P1 · 인증·계정 검증 | 로그인·계정 연결/해제·요청 제한·즉시 세션 폐기 구현에 대한 실제 제공자 검증 | Google/카카오 왕복·취소·이메일 충돌, 연결/해제 후 마지막 수단 보호, 현재/다른 기기 세션 구분, 실제 메일 만료/재사용 거절을 기록 | `auth/AccountConnections`, `LoginSessions`, `LoginAttemptLimiter`, [계정 연결](contracts/account-connections.md) |
| H04 · P1 · 화면·저장 회귀 | 실제 API를 쓰는 브라우저 시나리오 및 모바일 실기기 확인. 뒤로가기 미저장 보호 보완 | 공고→조건→서류→일정→보관/복원, 새로고침, 저장 실패/재시도, 두 탭 충돌, 키보드·확대·실기기 결과 기록. 뒤로가기/앞으로가기에서 데이터 유실 여부 검증 | `App.test.tsx`, `useUnsavedChanges`, `features/preparation`, [화면설계서](design/screen-spec.md) |
| H05 · P1 · 발송 실연동 | 메일·Web Push 코드는 있지만 실제 발송 환경·브라우저 검증 필요 | SMTP 발신 설정 후 테스트 주소로 인증/재설정/알림 수신, VAPID 구독·수신·권한 거절·해지·로그아웃·기기 변경, 중복 방지·실패 재시도·410 제거 확인 | `AccountMail`, `BrowserPush`, `ReminderDelivery`, `browserDevice.ts`, `frontend/public/reminder-worker.js` |
| H06 · P1 · 실제 서버 공개 | 배포 파일·설정 검사·백업/복구 도구는 있음. 실제 AWS·HTTPS·RDS 적용과 리허설 필요 | 최신 migration 전체 업그레이드·새 DB 복구, TLS·프록시·OAuth 복귀 주소·쿠키·롤백·OCR 메모리/프로세스 한도 확인. Pages와 별도 배포 | [운영 준비](../deploy/README.md), `scripts/ops/`, `backend/src/main/resources/db/migration/` |
| H07 · P1 · 공개 인증 보호 | 로그인 계정/IP 제한은 구현됨. 가입·체험·재설정의 별도 보호 범위와 운영 프록시 설정 확인 | 기존 로그인 제한 회귀 유지, 실제 신뢰 프록시·직접 접근 차단 검증, 추가 제한이 필요한 엔드포인트와 기준 합의 | [세션·요청 제한](contracts/login-sessions-and-limits.md), `LoginAttemptLimiter`, `SecurityConfig` |
| H08 · P2 · LH 예약·복구 검증 | 영속 커서·예약 슬롯·실행 임대·재시도·중단 복구·공급자 대기 구현됨 | 실제 키의 조회량/한도, 예약 장기 실행, 중단/재시작·PARTIAL·중복 방지를 테스트 DB와 운영 전 환경에서 재현 | [예약 수집](contracts/collection-schedule.md), `CollectionScheduler`, `CollectionRunStore` |
| H09 · P2 · 수집 범위·정정 검증 | 복수 분류·게시일 범위·첨부 해시·공식 정정 연결 구현됨. 자료 완전성은 별도 검증 | 오래된 장기 접수·정정·첨부만 바뀐 자료·미판독 표본 확보. 누락/실패를 취소·완료로 오판하지 않고 개인 스냅샷 보존 | `CollectionQueryPlan`, `collection/attachments/`, [첨부 감지](contracts/attachment-change-detection.md), 백엔드 계약 |
| H10 · P2 · 검수 품질 | 수동 작업실·원문 뷰어·지원 규칙 자동 검수·PARTIAL·요약이 구현됨. 실제 담당자의 교차 검수 필요 | 원문 근거·날짜·제출 범위·OCR 미판독·충돌을 표본으로 확인하고 보류/해결 사유와 담당 기록. 자동화를 위해 근거 검증을 약화하지 않음 | [자동 검수](contracts/automatic-review.md), [원문 추출](contracts/official-text-extraction.md), `collection/review/` |
| H11 · P2 · 알림 운영 | 순차 발송·제한된 재시도는 있음. 최종 실패와 처리 지연의 운영 확인 보완 | 실패/재시도 소진/지연 확인 방법, 재처리 기준, 중복 및 개인정보 노출 없는 로그·지표 마련 | `ReminderDelivery`, `alert_delivery`, `AlertService` |
| H12 · P2 · 개발 기준 | Java 포맷의 팀·CI 동일 실행과 필수 검사 연결이 남음. 구 문서에 초기 미구현 표현 존재 | IntelliJ CLI 버전·실행 시간을 검증한 뒤 gate 적용. 실제 구현과 어긋난 기획/화면/버전 문서의 상태를 날짜 기준으로 정리 | `backend/build.gradle`, `.github/workflows/ci.yml`, `docs/conventions`, `docs/planning` |

H08의 중단 복구는 구현되어 있다. H08·H11의 운영 관찰은 구현을 실제 환경에서 검증하기 위한 후속 제안이다. 이번 인수인계에서 서버 강제 종료나 대량 발송 장애를 실제 재현한 것은 아니다. H06의 무료 이용 여부는 AWS 계정·크레딧·이용 기간·리소스 비용을 따로 확인해야 하며 프리티어를 보장하지 않는다.

## 5. 반드시 전달해야 할 제약과 정책

### 데이터 수집·추출·검수·공개를 구분하기

- [예약 수집](contracts/collection-schedule.md)은 LH 분류 `06,13`과 명시적 게시일 범위, 영속 페이지 커서를 사용한다. 한 작업 단위의 페이지 한도는 제품 공고 수 제한이 아니다. 기본 게시일 하한 2000-01-01은 API의 과거 자료 완전성을 보장하지 않는다.
- 목록 가져오기만으로 준비 안내가 완성되지 않는다. 별도 원문/첨부 추출, 선택적 로컬 OCR, 지원 규칙 검수와 공개 단계가 있다. 누락·미판독·모순을 임의 값으로 채우지 않는다. 실제 지원 범위는 [원문 추출](contracts/official-text-extraction.md)과 [자동 검수](contracts/automatic-review.md)를 따른다.
- PR #26에는 사용자가 승인한 제한적 자동 검수·공개가 포함된다. 기본/준비 안내 공개 경계를 구분하고, 영향 범위를 식별한 문제는 PARTIAL, 해석 불가·근거 불충분은 HOLD로 남긴다. 자동화가 수동 원고나 개인 준비 스냅샷을 덮어쓰지 않도록 보호한다. ‘반드시 전건 수동 검수’라는 11월 10일 초안은 현행 계약과 다르다.
- 등록 첨부의 본문 해시 변경 감지와 공식 정정 관계 처리가 구현되어 있다. 기관 자료의 누락·네트워크 오류를 취소로 추정하지 않는다. 기존 개인 준비는 당시 스냅샷을 보존하고 새 기준 적용은 명시적 동작으로 처리한다.
- Gemini 연결은 선택적 부분 문맥 보조 검토다. 원문 전체 자동 해석이나 신청 자격 확정이 아니다. 키·모델·예산 설정 및 실제 호출 검증을 [자동 검수 계약](contracts/automatic-review.md)과 함께 확인한다.
- 2026-10-31의 ‘276건 수집’과 저장소의 날짜별 검수 보고서는 당시 표본이다. 현재 공개 공고 수·팀원 DB의 보유량·운영 검수 완료를 의미하지 않는다.

### 미구현이지만 무조건 다음 스프린트에 넣을 것은 아닌 것

| 항목 | 현재 경계·판단 |
| --- | --- |
| 네이버 로그인 | 미구현·기존 범위 밖. 필요성이 확인되면 별도 제공자/계정 계약으로 추가 |
| 복수 질문 AND/OR 조건 규칙 | 현재 단일 질문+답변값 조건보다 복잡한 규칙은 미지원. 실제 공고가 요구할 때 스키마·버전·회귀 사례와 함께 확장; 지원 전에는 HOLD/UNSUPPORTED |
| 편집형 가이드 CMS | 미구현. 현재 공개 공고 기반 생성/검색과 구분; 별도 콘텐츠 운영 수요가 있을 때 결정 |
| 외부 캘린더 자동 동기화 | 미구현. `.ics` 내보내기는 이미 구현. 외부 연동 권한·정정/취소 정책이 필요한 별도 범위 |
| 대규모 외부 큐·분산 운영 | 수집의 DB 대기/임대/중복 방지는 구현됨. 별도 메시지 브로커 및 실제 다중 서버 부하·장애 운영은 별도 판단 |

### 기존 정책을 결함으로 오해하지 않기

- 준비도와 집 건축 표현은 초기 완성 범위에 유지한다. 준비도는 준비 작업의 기록이며 자격 판정이나 선정 확률이 아니다.
- 주민등록번호·증빙 원본을 저장하지 않는다. 공고 공식 링크 이동만으로 서류·신청 상태를 완료 처리하지 않는다.
- 일반 로그아웃은 현재 `sid` 세션의 접근·갱신 토큰을 즉시 거절하고 다른 기기는 유지한다. 비밀번호 변경·로그인 수단 해제·탈퇴에 따른 전체 인증 폐기와 구분한다. [세션 계약](contracts/login-sessions-and-limits.md)이 기준이다.
- 알림은 시각 단위 예약이 아니라 날짜 범위와 현재 공개 데이터에서 계산한다. 읽은 알림은 발송하지 않는다. 시연 알림은 실제 서버보다 단순하다.
- 기본 이메일 확인을 모든 로그인에 강제하지 않는다. 이메일 알림에는 확인이 필요하다. 강제 인증으로 바꾸려면 제품 흐름을 먼저 합의한다.

## 6. 해결된 문제와 남은 재현 항목

| 항목 | 상태·인계할 내용 |
| --- | --- |
| 보관 시 다른 탭의 메모 덮어쓰기 | **해결**. `2918a43`: 이미 보관됐거나 revision이 오래된 요청 409, 보관·복원 시 DB/JSON revision 동시 증가, 반복 복원은 무변경. 서버/시연 회귀 포함 |
| 로그인 복귀 테스트의 간헐 대기 실패 | **해결**. App 테스트에서만 시연용 180ms 지연 제거, 저장·오류·재시도 단정 유지. 실제 시연 지연은 유지 |
| DB 연결 거부·깨진 한글 로그 | 과거 원인은 PostgreSQL 미실행이었다. 한글 폴더 문제로 단정하지 말고 15432 포트·컨테이너 health·환경 변수를 확인 |
| 카카오 호출 허용 IP | 과거 사설 IP 등록을 공인 IP로 바꾼 뒤 성공. 새 PC/네트워크에서는 운영자가 등록 IP를 확인해야 함 |
| Google 계정 선택 후 Chrome 정체 | 과거 재현, 원인 미확정. Edge 성공 기록과 구분. 최신 브라우저/환경에서 재현 여부부터 확인하고 계정 등록 유무를 원인으로 단정하지 않음 |
| 브라우저 뒤로가기 미저장 보호 | `useUnsavedChanges`는 링크 클릭·beforeunload·명시적 기록 변경 이벤트를 처리한다. history 뒤로/앞으로를 전면 보호하지 않아 H04에서 보완·검증 필요 |

## 7. 기능별로 코드를 찾는 지도

아래 경로는 저장소 루트 기준이다.

| 작업 | 주요 진입점 |
| --- | --- |
| 화면 경로·로그인 상태 | `frontend/src/app/App.tsx`, `frontend/src/features/auth/useSession.ts`, `frontend/src/shared/api/transport.ts` |
| 실 API/시연 계약 | `frontend/src/features/housing/api.ts`, `types.ts`, `demo.ts`, `demoPreparationRules.ts`, `frontend/src/features/member/demoMember.ts` |
| 공고·준비·가이드 화면 | `frontend/src/pages/`, `frontend/src/features/housing/`, `frontend/src/features/preparation/` |
| 준비 업무 규칙 | `backend/src/main/java/org/jibro/preparation/PreparationRules.java`, `PreparationService.java`, `data/contracts/preparation-rules.json` |
| 공고·검수·공개본 | `backend/src/main/java/org/jibro/notice/NoticeService.java`, `NoticeSchedules.java`, `NoticeVersions.java` |
| 수집 | `backend/src/main/java/org/jibro/collection/` |
| 인증·계정·체험 | `backend/src/main/java/org/jibro/auth/`, `backend/src/main/java/org/jibro/global/config/SecurityConfig.java` |
| 관심·알림·보관 | `backend/src/main/java/org/jibro/member/`, `frontend/src/features/member/` |
| DB·CI·배포 | `backend/src/main/resources/db/migration/`, `.github/workflows/ci.yml`, `.github/workflows/pages.yml` |
| 구조 시각화 | [코드 모델 안내](models/README.md), `frontend/public/jibro-code-model.html` |

SQL·행 매핑은 Repository/Store가 담당하고 Service는 업무 순서·트랜잭션을 소유한다. JPA·Querydsl·JDBC가 함께 사용된다. Service에 직접 DB 접근을 되살리지 않으며 `PersistenceBoundaryTest`와 revision·행 잠금·스냅샷 회귀를 유지한다. 프론트에서도 [프론트 계약](architecture/frontend-contracts.md)의 기능별 모듈·스타일 소유권을 따른다.

## 8. 권장 착수 순서와 인계 완료 기준

1. **새 개발자 환경 재현(H01)**: develop clone, 두 실행 모드, DB·샘플·관리자·외부 키 소유자 확인. 첫 실행과 기본 테스트 결과를 남긴다.
2. **실제 공고 한 건의 전 과정 검증(H02·H04)**: 원문 검수부터 조건·서류·일정·정정·보관까지. 한 건은 첫 회귀 표본이며 전체 서비스 범위를 한 유형으로 제한한다는 뜻이 아니다. 다음 표본에는 다른 유형과 예외를 추가한다.
3. **외부 인증·계정·발송 검증(H03·H05)**: 테스트 계정·수신 주소·기기를 정하고 환경별 결과를 구분한다. 로컬 `.eml` 성공을 SMTP 수신 성공으로 기록하지 않는다.
4. **수집 운영 검증(H08·H09)**: 구현된 예약·부분 수집·정정·실패 복구를 실제 조건에서 확인하고 발견된 문제를 개선한다. API 오류·중단·없는 값은 성공/확정값으로 대체하지 않는다.
5. **외부 서비스 공개가 결정되면 H06·H07 선행**: 배포·복구·요청 제한·비용 확인 후 공개하고, H10~H12는 사용 결과에 따라 진행한다.

인계 완료 시 팀이 남길 자료:

- 담당자가 정해진 H01~H12 목록과 첫 PR 범위. 미확정 담당자를 임의 지정하지 않는다.
- 브랜치·커밋·실행 모드·DB 종류·샘플 여부가 포함된 재현 기록.
- 테스트 계정 권한과 비밀값 전달 채널. 키·비밀번호·실제 개인 기록은 문서/Git에 넣지 않는다.
- 대표 공고 원문과 검수 근거, 지원하지 않는 조건 분기, 실제 서비스 공개 전 완료할 항목.
- PR별 관련 계약·검증·작업 기록. 기능 PR → develop → main 순서와 Pages/실제 서버 배포 차이 준수.

이 문서는 저장소와 확인된 실행 기록을 대조해 작성했다. 신규 장애를 전수 탐지한 보안 감사나 운영 인증이 아니며, 설정이 없는 기능을 구현되지 않은 것으로 분류하지 않았다. 이번 작성에서 제품 코드·DB·외부 서비스 설정은 변경하지 않았다.
