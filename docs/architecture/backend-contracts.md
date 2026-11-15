# 백엔드 구현 계약과 운영 설정

- 초기 기준일: 2026-10-30. 최종 대조일: 2026-11-15. PR #24는 develop/main에 반영됐으며, 이 문서는 PR #26의 추가 구현과 develop 통합 결과를 설명한다. PR #26의 병합·운영 배포 완료를 뜻하지 않는다.
- 기준: [프론트엔드 기능 계약](frontend-contracts.md), 구현 DTO `HousingModels`, 인증 DTO `AuthController`
- 구현 범위: 이메일·비밀번호 로그인, Google·카카오 OAuth 연결, 공고 검색, 개인 준비 기록, 서버 준비도 계산, 관리자 검수·구조화 편집, 공식 조기 마감·취소, 저장하지 않는 일회 조건 확인, LH 목록 자동 수집·원본 JSON 기록

## 인증·권한

회원가입은 이름 2~30자, 이메일 최대 254자, 비밀번호 10자 이상이며 BCrypt의 UTF-8 72바이트 제한을 적용한다. 이메일은 소문자로 정규화한다. 중복 이메일은 409다. 이메일 소유 확인·비밀번호 재설정·탈퇴는 [회원 기능](../contracts/member-features.md), 계정 연결·해제는 [계정 연결](../contracts/account-connections.md) 계약대로 구현했다. 실제 외부 수신·제공자 왕복 검증은 구현과 구분한다.

접근 토큰은 HS256, issuer `jibro`, audience `jibro-web`, 15분 만료다. `JWT_SECRET`은 최소 32바이트이며 저장소에 넣지 않는다. 갱신 토큰은 임의 32바이트 값을 발급하고 DB에는 SHA-256 해시만 보관한다. 7일 만료, 행 잠금으로 단일 회전을 보장한다. 요청마다 계정 활성 여부·체험 만료·security_version과 JWT sv를 대조하며, sid 기반 [세션 즉시 폐기](../contracts/login-sessions-and-limits.md)로 로그아웃한 세션의 접근·갱신을 차단한다. 비밀번호 변경·재설정·탈퇴에 따른 기존 세션 무효화와 다른 기기 세션의 범위를 구분한다.

쿠키 `jibro_refresh`는 HttpOnly, SameSite=Lax, Path=/api/v1/auth다. Secure 기본값은 true이며 로컬 HTTP에만 `AUTH_SECURE_COOKIE=false`를 사용한다. 실제 배포는 HTTPS·동일 출처 역방향 프록시가 전제다. `GET /auth/csrf`의 마스킹된 토큰을 `X-CSRF-TOKEN` 헤더로 보낸다. JWT와 CSRF 토큰을 URL·로그·localStorage에 기록하지 않는다.

소셜 로그인은 제공자별 CLIENT_ID와 CLIENT_SECRET이 모두 있을 때 활성화된다. `/auth/providers`가 활성 목록을 반환한다. 제공자+subject로 사용자를 식별하고 검증된 이메일을 사용한다. 같은 이메일의 기존 계정으로 자동 연결하지 않으며 충돌은 가입/연결 오류로 처리한다. OAuth state/OIDC 검증은 Spring Security를 사용한다. 성공 시 갱신 쿠키를 설정하고 고정된 `FRONTEND_URL/auth/callback`으로 이동한다. 접근 토큰은 URL에 포함하지 않는다.

관리자 권한은 서버에서 확인한다. 일반 회원가입은 USER만 생성한다. 최초 관리자는 운영자가 가입된 계정을 확인하고 DB에서 승격한다. 공개 관리자 비밀번호나 자동 관리자 계정은 제공하지 않는다. 이메일 인증과 로그인 요청 제한은 구현되어 있으며, OAuth·SMTP 실제 설정과 운영 검증은 별도다. 메일 확인 링크의 계정·용도별 1분 발급 간격은 로그인 요청 제한과 별개이며, 가입·체험을 포함한 모든 엔드포인트가 같은 제한을 받는다고 간주하지 않는다.

`local-sample`의 서버 체험은 정적 Pages 시연과 구분한다. 임시 TRIAL 계정의 만료·재사용·권한·정리 정책은 [회원 기능 계약](../contracts/member-features.md)을 따른다. 프로필 이름이 가상 공고 자동 생성을 의미하지는 않는다.

## 저장·동시 수정

2026-11-07 사용자 승인: 기본 정보/준비 안내의 자동 검수는 [자동 검수 계약](../contracts/automatic-review.md)을 따른다. 수동 편집 차단, 최신 발췌 내용 해시 대조, 별도 자동 감사 이력, Gemini 후보와 검증기의 분리를 적용한다. 불확실한 원문을 검수 완료로 취급하지 않는다.

- 공고: 검색 필드는 관계형 컬럼과 인덱스, 가변 질문·서류·일정은 JSONB로 저장한다. Querydsl로 필터 내부 OR, 필터 사이 AND를 적용한다.
- 준비: 사용자+공고 유일 제약, 소유자 조건 조회, 공고 당시 스냅샷과 조건부 서류 규칙을 저장한다. 다른 사용자의 ID는 404다.
- revision: 수정 요청과 현재 revision이 다르면 409. 사용자 기록과 공고 잠금으로 검수/기록 변경의 경쟁을 막는다.
- 보관·복원: 실제 상태 전환마다 preparation 테이블과 JSON 스냅샷의 revision을 함께 올린다. 이미 보관된 건의 재보관은 거절하고, 이미 진행 중인 건의 반복 복원은 변경하지 않는다. 세부 API는 [회원 기능 계약](../contracts/member-features.md)을 따른다.
- 공고 내용 수정: 공고 version과 검수 revision을 올리고 기본·안내 모두 HOLD로 돌린다. 기존 준비 기록은 원래 내용을 보존하고 수정은 막는다. 현행 `POST /preparations/{id}/refresh`는 사용자 요청과 revision·새 AVAILABLE 버전을 확인한 뒤 이전 스냅샷을 이력에 보존한다. 동일한 질문 계약의 답변과 동일한 ID·제목·날짜의 일정 기록만 이어받고, 기존 완료 서류는 재확인으로 전환한다.
- 준비도: 조건 30%, 적용 서류 50%, 일정 기록 20%. 모름·미입력·해당 여부 불명은 완료 점수를 주지 않는다. 해당 없는 서류는 분모에서 제외한다. 분모가 0인 영역은 0점이다. 정수 반올림 결과가 100이어도 미확인 답변·미완료 서류·미기록 일정이 하나라도 남으면 99%로 제한한다. 조건부 서류의 해당 여부가 바뀌면 완료 표시를 재확인으로 돌린다.
- DB migration: 기존 테스트용 V1을 보존하고 운영 스키마는 V2부터 시작한다. 테스트 DB에는 운영 migration과 테스트용 migration을 함께 적용한다.

`PreparationRules`는 서류 필요 여부와 준비도·집 단계·경고·다음 행동을 계산하는 순수 규칙이다. DB에 접근하거나 전달받은 준비 기록을 수정하지 않는다. `PreparationService`는 소유권·잠금·revision·입력 검증·트랜잭션·기준 갱신을 유지하고 규칙 결과를 저장한다. `PreparationSnapshots`는 스냅샷 생성·답변 적용과 규칙 결과 반영을 공용화한다. 저장 흐름과 일회 평가가 같은 입력 검증·필요 여부 계산을 사용한다. 시연 계산과의 기대값 공유 및 실제 서버 검증의 경계는 [프론트엔드 개인 준비 계약](frontend-contracts.md#개인-준비)을 따른다.

### 저장하지 않는 조건 확인 (현행: 2026-11-07)

`POST /notices/{id}/check-once`는 로그인 없이도 CSRF 보호 아래 사용할 수 있다. `OneTimeCheckService`는 읽기 전용 REPEATABLE_READ 트랜잭션에서 공개 Notice의 AVAILABLE·canStart·version을 확인하고 `PreparationSnapshots`로 임시 평가한다. 개인 준비·답변·파생 결과를 DB에 저장하지 않고 `Cache-Control: no-store`로 반환한다. 입력과 결과는 URL·브라우저 저장소·쿼리 캐시에 넣지 않으며 화면 이탈·새로고침·pagehide 때 폐기한다. 기존 저장 준비를 변경하지 않는다. 프록시/운영 관찰 도구에서도 요청·응답 본문을 기록하지 않도록 구성해야 한다.

### 공식 조기 마감·취소 (현행: 2026-11-07)

기본 날짜와 확인된 접수 시각에서 계산하는 `Notice.status`, 공개·안내 검수, 공식 `Notice.lifecycle`은 별개다. 관리자 전용 `PATCH /admin/reviews/{id}/lifecycle`은 공식 근거 주소·확인일·이유·명시 확인과 현재 revision을 요구한다. `NoticeLifecycleService`는 공고 행 잠금 아래 notice revision과 lifecycle revision을 증가시키고 `NoticeLifecycleRepository`에 감사 이력을 남긴다. 안내 version·기본/안내 검수 결과·개인 스냅샷은 유지한다. 새 조건 확인만 차단하고 기존 개인 기록은 보존한다. 원문 누락·다운로드 오류로 취소를 자동 판정하지 않는다. 공개 수정 이력에는 상태 변경 근거를 포함하며 과거 공개본은 소급 변경하지 않는다. 자세한 API·알림·복원 경계는 [공식 상태 계약](../contracts/notice-lifecycle.md)을 따른다.

### 저장 계층 경계 (현행: 2026-11-07)

- Controller는 HTTP 요청·응답, Service는 업무 판단·작업 순서·트랜잭션을 담당한다. SQL·행 매핑은 기능별 Repository가 맡는다. `notice/NoticeQueryRepository`가 Querydsl 검색·필터를, 기존 `NoticeRepository`가 JPA 공고 저장·잠금을, `NoticeAuditRepository`가 검수 이력 저장을 담당한다. 기존 JDBC 행 잠금·JSONB 스냅샷을 다른 스키마나 JPA 모델로 전환한 변경은 아니다.
- `PreparationRepository`, `PreparationHistoryRepository`, `PreparationDeletionRepository`는 현재 준비·당시 이력·삭제 증명 저장을 구분한다. 소유권·revision·재인증 판단과 삭제 실패 횟수를 보존하는 `noRollbackFor`는 Service에 남긴다. 회원 기능도 `SavedNoticeRepository`, `ExperienceRepository`, `AlertRepository`, `ReminderRepository`가 저장을 맡고 알림 구성·외부 발송·재시도 순서는 기존 Service가 유지한다.
- 인증 저장은 `AuthRepository`, `AccountRepository`, `AccountConnectionRepository`, `TrialAccountRepository`, `LoginAttemptRepository`, `LoginSessionRepository`로 나눈다. 인증 Service는 갱신 회전·연결·만료·실패 제한의 순서와 트랜잭션을 소유한다. `SecurityConfig`의 접근 세션 검사는 `LoginSessionRepository.isAccessSessionValid`를 사용한다.
- `GuideController → GuideService → GuideRepository`는 공개 근거를 읽고 검수 상태에 맞게 가이드를 조합한다. `NoticeVersions → NoticeVersionService → NoticeVersionRepository`는 공개 여부·안내 포함 범위를 Service에서 결정한다. 상태 점검 Controller는 `DatabaseHealthRepository` 결과를 HTTP 상태로 변환한다.
- `CollectionRunStore`, `ExtractionStore`, `AttachmentCheckStore`는 이미 잠금·임대·발췌·점검 저장 경계를 제공하므로 중계 Repository를 덧붙이지 않는다. 이 세 클래스는 `@Component`를 사용한다. `JdbcTemplate`이 SQL 예외를 변환하며, `@Repository`의 추가 예외 변환은 `LeaseLost`·입력 오류 등 기존 도메인 예외의 타입을 바꾸므로 적용하지 않는다. 짧은 Store 트랜잭션과 네트워크 작업의 분리를 유지한다.
- `backend/src/test/java/org/jibro/architecture/PersistenceBoundaryTest.java`는 실제 Service·Controller·SecurityConfig의 컴파일된 코드를 스캔해 JDBC·Querydsl·EntityManager 직접 의존의 재유입을 검사한다. 구조 검사와 실제 DB 동시성·권한 검증은 별개이며 실행 결과는 해당 작업 기록을 따른다.

## 관리자 구조화 계약

`GET /admin/reviews/{id}/draft`로 기본 정보와 구조화 원고를 읽고 `PUT`으로 `{revision, draft}`를 저장한다. draft는 title, agency, region, type, startDate, endDate, publishedDate, content를 갖는다. 날짜가 확인되지 않으면 null이다.

content는 summary, evidence, attachments, questions, documents, schedules다. 질문 inputKind는 SINGLE/MULTIPLE/NUMBER/DATE이며 제약과 허용 값은 서버에서 검증한다. 서류의 conditionQuestionId가 null이면 필수다. 연결 질문에는 conditionAnswerValues에 지정된 선택값 규칙을 적용하고, conditionAnswerValues를 생략한 구버전은 YES를 필요 조건으로 읽는다. UNKNOWN·미입력은 미확인이다. 숫자·날짜를 임의 자격 판정이나 조건부 서류 규칙으로 추정하지 않는다. 이 단순 규칙으로 표현할 수 없는 복잡한 자격·예외는 자동 판단하지 말고 준비 안내 HOLD 또는 UNSUPPORTED로 둔다. 질문은 이 규칙에 맞는 의미로 작성해야 한다. 선택지의 value·label·requiresFollowUp과 조건부 서류 판별·전이 상세는 [질문·서류 계약](../contracts/question-options-and-documents.md)을 따른다.

2026-11-10 사용자 요청의 지원 대상·지원 방식 2~3문장 자동 요약은 [공고 지원 내용 자동 요약](../contracts/automatic-support-summary.md)을 따른다. `GuidanceReviewCompiler`가 검증된 원문을 로컬 요약기로 전달하며, 접수종료·근거 불충분·대상 충돌·수동 원고 보호와 기존 게시 경계를 유지한다.

질문·서류·일정 ID는 공백 없는 영문·숫자·하이픈·밑줄, 최대 64자이며 영역 내 중복을 금지한다. 질문·서류·일정 각각 최대 100개, 첨부 50개, 메모·검수 사유 최대 2,000자다. 외부 링크는 HTTPS만 허용하며 운영자가 공식 발급·공고 링크를 검증한다. `PATCH /admin/reviews/{id}`는 기본·안내 결과를 함께 기록한다. 준비 안내 PASS에는 질문·서류·일정이 필요하다.

## 공식 수집의 범위와 한계

출처: [공공데이터포털 LH 분양임대공고문 조회 API](https://www.data.go.kr/data/15058530/openapi.do). `LH_SERVICE_KEY`에는 URL 인코딩 전 Decoding 키를 넣는다. 서버가 HTTPS 공식 엔드포인트에 키를 한 번 인코딩하여 보낸다. Encoding 키를 넣으면 이중 인코딩되어 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 발생할 수 있다. 2026-10-31 실제 API 및 관리자 수집 API로 276건 저장과 재수집 시 중복 없음, 전건 HOLD/HOLD를 확인했다. 자동 테스트는 외부 키 없이 합성 자료를 사용하며 실제 응답의 `dsSch`·`dsList`·`resHeader` 구조를 반영한다.

2026-11-07부터 수집은 LH 분류 06/13과 명시적인 게시일 조회 범위를 사용한다. 기본 시작일 2000-01-01은 운영자가 조정할 수 있는 보수적 조회 설정이며 API의 전 기간 제공을 보증하지 않는다. 분류·날짜 범위·페이지 커서는 실행에 고정하고 한도 내에서 이어 읽는다. 기존 실행은 당시 06·최근 3개월 범위를 보존한다. 게시일 검색에는 `PAN_ST_DT`와 `PAN_ED_DT`를 `yyyyMMdd` 형식으로 사용한다. `PAN_NT_ST_DT`와 `CLSG_DT`는 응답 필드이며 검색 범위로 사용하지 않는다. 페이지 단위 한도와 이어읽기는 제품의 공고 수 상한이 아니다. [예약 수집](../contracts/collection-schedule.md)은 한국시간 00·06·09~18시 정각과 실패 재시도를 구현했고 DB 임대로 단일 실행을 유지한다. 키 없음·실패·부분 처리를 성공으로 표시하지 않는다. 별도 메시지 서버는 도입하지 않았다.

동일 공식 상세 URL은 동일 출처로 취급한다. 목록 순번·전체 건수·응답 시각을 제외한 원본 목록 필드의 해시가 바뀌면 공고를 재검수한다. 2026-11-06부터 등록된 공식 첨부는 목록 수집 후 또는 관리자 개별 점검으로 실제 SHA-256을 비교한다. 첫 성공은 기준 등록이며, 같은 주소의 바이트 변경은 공고 버전 증가와 기본·안내 HOLD/CHANGE_REVIEW로 전환한다. 실패는 기존 기준·공개 상태를 유지하며 기존 개인 준비 스냅샷은 수정하지 않는다. 요청 한도·지원 형식·동시성·관리자 API는 [첨부 변경 감지 계약](../contracts/attachment-change-detection.md)을 따른다. 미등록 첨부 자동 발견과 원문 의미 해석은 포함하지 않는다. 원문을 임의 추론하여 질문이나 지원 자격을 생성하지 않는다. 신규 수집 자료는 기본·안내 모두 HOLD이며 검수 후 기본만 공개하거나 안내까지 제공할 수 있다. 목록의 게시일·마감일을 실제 신청 기간으로 자동 전용하지 않는다.

2026-11-07부터 `CollectionResponseService/Repository`·V19가 실행·회차·조회 기준일·페이지별 요청/응답 이력과 인증 정보를 제거한 원본 JSON 필드를 보관한다. 본문은 재직렬화된 JSON이며 저장 본문 SHA-256으로 중복을 공유한다. 검증 실패 JSON도 성공과 구분해 보관하고 비JSON·스칼라·5 MB 초과 본문은 저장하지 않는다. 관리자 전용 `/admin/collections/{runId}/responses` 및 하위 응답 조회에서만 제공하며 공개 공고와 개인 스냅샷에는 포함하지 않는다. 기존 미보관 응답을 소급 생성하지 않는다. [원본 응답 계약](../contracts/collection-response-archive.md)을 따른다.

`BoundedDocumentParser`는 PDF/HWP/HWPX/ZIP 처리를 독립 Java worker로 제한하고 종료 확인 후 임시 파일을 정리한다. `documentParserJar`를 bootJar에 포함한다. 발췌는 관리자 초안이며 원문 파일 장기 보관·OCR·자동 자격 판정은 구현 범위가 아니다. 프로세스 격리·시간/메모리/출력 한도와 배포 조건은 [공식 발췌 계약](../contracts/official-text-extraction.md)을 따른다.

### 로컬 해석과 제한된 API 보조 검토 (현행: 2026-11-07)

`LocalRuleInterpreter → CompactReviewPlanner → LocalAnalysisService`가 공식 발췌를 먼저 로컬에서 처리한다. 원문 인용·출처 해시·페이지와 위치·적용 대상·수치·예외 및 참조 관계를 `LocalRuleBook`에 남기고, 특정 관계의 추가 검토가 필요한 문맥만 Gemini에 전달한다. 원문 전체를 보내던 검수 경로는 제거했다. 로컬에서 추출한 사실도 자격 판정은 아니며, 누락된 참조·읽기 실패·문맥 초과를 임의로 보완하거나 잘라서 보내지 않는다.

요청 하나의 정확한 `payloadJson()`은 메타데이터와 JSON 이스케이프를 포함해 최대 6,000자다. 이 한도는 시스템 지시문·응답 스키마·HTTP 포장을 포함한 전체 요청 크기나 토큰 수가 아니다. 공고별 첫 계획은 최대 3개 요청이며, 나머지 `deferredPacketIds`와 일반 미해석 항목 `LOCAL_REVIEW_PENDING`은 미완료로 남는다. 현재 예약 처리는 저장된 첫 계획만 소비하며 보류분 전체를 자동 완료하지 않는다. 출처와 범위가 다른 문장을 같은 내용으로 간주하지 않고, 동일한 전체 근거를 가진 질문만 중복 제거한다.

V26의 `notice_local_analysis`는 발췌 ID·발췌 내용 지문·해석 정책을 키로 규칙 묶음, 요청 계획, 출처 충족 여부와 API 결과를 별도로 보관한다. 관리자 전용 `GET /api/v1/admin/reviews/{noticeId}/extractions/{extractionId}/local-analysis`는 해당 자료를 조회하며 없으면 로컬 분석을 생성한다. 이 조회 자체는 외부 API를 호출하지 않는다. 예약 처리는 최신 revision·발췌 지문과 수동 검수 차단 여부를 확인한다. API 캐시는 정확한 요청 내용·정책·근거 해시·모델에 묶이며, 공용 DB 예산은 한국시간 하루 최대 10회·요청 간 최소 60초를 강제한다. 설정으로 더 줄일 수 있고, 키 미설정·한도·제공자 오류는 성공과 구분한다.

`ScopedReviewSchema/Validator`는 각 질문의 응답과 허용된 근거 참조를 검증한다. 결과는 `CONFIRMED_CONFLICT`, `NO_CONFLICT`, `UNRESOLVED`의 보조 검토 기록이며, `NO_CONFLICT`도 전달한 부분 문맥에만 적용된다. 이 결과가 공고 전체의 완전성·개인 자격·준비 안내 PASS를 뜻하지 않으며 기존 HOLD를 해제하지 않는다. 공개 검수와 개인 준비 기록은 기존의 별도 계약을 유지한다.

## 로컬 샘플과 운영 자료

2026-11-07 사용자 요청에 따라 테스트용 가상 공고를 실제 서비스 흐름에서 제외했다. `source_key`가 `local-sample-`로 시작하는 공고는 검색·필터·상세·비교·가이드·검수 목록에 노출하지 않는다. `GET /notices?includeSamples=true`도 예외를 허용하지 않으며 상세·비교는 404다. 새 준비 시작과 관심 공고 저장을 차단하고, 활성 준비·관심 공고·알림 조회에서도 제외한다. 일반 실행에서 예시 공고를 보여 주는 우회 경로로 사용하지 않는다.

공식 초기 자료와 로컬 적용 방법은 [공식 공고 대조 자료](../../data/notices/README.md)를 따른다. 자동 수집 전체 결과를 일괄 공개하지 않는다.

`local-sample` 프로필은 로컬 체험 인증 등을 위해 남기되 샘플 생성은 기본 중지한다. `SampleData`는 해당 프로필과 명시적인 `app.sample-data.enabled=true`가 함께 있는 격리된 fixture 환경에서만 실행한다. `/meta.sampleData`는 false이며 체험 추천도 검수된 실제 LH 공고 중 정상 상태이고 기한이 남은 공고를 사용한다. 샘플 전용 가이드는 생성하지 않고 `/guides`는 공개 공식 공고와 검수된 조건·서류·일정을 기반으로 구성한다. 단위·통합 테스트 fixture와 별도 demo 빌드는 실제 데이터 실행과 분리한다.

표시 제외 정책 자체는 DB 행이나 개인 준비·과거 스냅샷을 삭제하지 않는다. 2026-11-07 이 컴퓨터의 개발 DB 정리는 별도 백업(`.tools/backups/before-held-review-20261107.dump`) 후 개인 기록·감사 이력 참조가 없음을 확인한 `local-sample-1`, `local-sample-2`, `local-sample-3`만 대상으로 실행했다. 가상 공고 3건을 제거하고 기존 수동 검수 자료를 포함한 실제 LH 공고 19건을 유지했다. 이 수치는 당시 로컬 확인 결과이며 수집에 따른 공고 수의 상한이나 다른 환경의 상태를 의미하지 않는다. 재시작 때 자동 삭제하거나 운영 DB를 초기화하는 절차는 없다.

예전 `scripts/verify-api.mjs`의 공개 샘플 흐름은 더 이상 지원하지 않는다. 보호 조건을 완화하여 실제 DB에서 실행하지 않고, 별도 `jibro_test`의 회귀 테스트를 사용한다. 운영은 별도 DB를 사용한다.

운영 배포·도메인·HTTPS·관리자 지정·외부 키·실제 공고 검수·메일 수신·실제 정각 수집은 별도 설정/실행 검증 대상이다. [배포 패키지와 복구 절차](../../deploy/README.md)는 준비되어 있으며 실제 AWS 적용 완료를 뜻하지 않는다.

수집 재개: POST /admin/collections?startPage=6 처럼 시작 페이지를 지정할 수 있다. 기본값은 1이며 1~9999를 허용한다. 같은 페이지를 다시 요청해도 동일 출처를 중복 생성하지 않는다.

## 관리자 준비 안내 초안 검토 (2026-11-08)

`GET /api/v1/admin/reviews/{noticeId}/extractions/{extractionId}/guidance-preview`는 현재 공고·최신 발췌에 대한 읽기 전용 검토 결과를 반환한다. `GuidanceReviewPreviewController`는 `AutomaticReviewService.preview`에 위임하고, `GuidanceReviewCompiler`가 자동 검수와 같은 원문 범위·일정·문서 충돌 검사를 사용한다. 관리자 권한, 공고와 발췌의 소유 관계, revision과 첨부 지문이 맞아야 한다. 인증 없음 401, 권한 없음 403, 소유 관계 오류 404, 낡거나 변경된 자료 409를 반환한다.

응답의 `candidate`는 검토용 질문·서류·일정 초안이며 공개된 준비 안내가 아니다. 유형별 초안만 생성되어도 기존 전체 규칙 검사가 미완료이면 `ready=false`와 `issues/findings`를 유지한다. 이 조회는 공고·개인 준비·자동 검수 이력을 변경하거나 Gemini를 호출하지 않는다. 실제 공개는 기존 잠금·최신 자료·수동 검수 보호가 적용된 `AutomaticPublicationService` 경계에서만 처리한다. 상세 지원 범위와 보류 조건은 [자동 검수 계약](../contracts/automatic-review.md)을 따른다.

## 공개 일정

확정 날짜 없이 개별 통보하는 결과 발표는 `ScheduleTemplate.timingDescription`에 공식 근거의 소요 기간·안내 방식을 저장한다. 이 경우 `date`는 빈 문자열이며 결과/당첨자/입주대상자 발표 제목만 허용한다. 날짜가 있는 일정에 설명을 혼합하거나 날짜와 설명을 모두 비워 저장할 수 없다. 기존 4인자 일정 생성과 JSON은 호환하며 개인 준비 스냅샷에도 설명을 복사한다. 프런트는 제목에 ` · 개별 안내`가 명시된 일정만 버튼과 근거를 포함한 설명 팝업으로 표시한다. 날짜가 있는 서류 대상자 발표와 최종 개별 통보가 공존하면 날짜를 보존하고 설명 버튼을 함께 표시한다. 출처 간 발표일 차이도 해당 공고 설명에서 구분하며 날짜를 임의로 삭제하거나 추정하지 않는다. 빈 날짜를 예상 날짜로 계산하지 않으며 기한 알림·캘린더 내보내기 대상에서 제외한다.

공개 Notice DTO의 `schedules: ScheduleTemplate[]`는 기본 정보 검수 후 공개되는 공식 일정이며, 준비 안내 HOLD 상태에서도 노출한다. 개인별 질문·서류와 준비 시작 제한은 유지한다. 프론트엔드의 대상자 발표는 서류제출대상자 발표를 먼저 선택하고, 해당 일정이 없으면 다른 발표 일정을 사용한다. 날짜만 존재할 때 임의 시각을 표시하지 않는다. 이는 기존 공개 계약이며 개인 준비 계산 분리로 변경하지 않는다.

`NoticeSchedules`는 알림용 공개 일정 선택과 기본 접수 일정의 중복 정리를 담당하고 `AlertService`는 회원별 알림 조회·읽음 처리를 담당한다. 같은 날의 기본 마감과 시각이 보완된 동등한 마감은 하나로 취급하되 기존 `end` 식별자를 보존한다. 다른 지역·별도 시각·서류 마감 등 서로 다른 일정은 유지한다. 공개 일정의 대표 날짜·시각과 캘린더 반영, 개인 스냅샷의 완료 기록을 현재 일정에 연결하는 조건은 [프론트엔드 일정 계약](frontend-contracts.md#일정-해석내보내기-현행-2026-11-06)을 따른다. 일정 중복 정리 자체는 별도 DB 일정 종류를 추가하지 않는다. 공식 lifecycle의 EARLY_CLOSED는 신청 시작·마감·신청 완료/여부 확인을 알림·다음 일정·캘린더에서 제외하고, CANCELLED는 모든 기한 알림을 제외한다. 서류·결과 발표·공고 게시 분류를 먼저 적용하여 제목 속 신청/마감 문구를 오인하지 않는다. 원래 날짜와 개인 기록은 남기며 상태 변경 소식은 lifecycle revision 키로 별도 제공한다.

## 접수 시각·근거 점검 보완 (2026-11-07)

[접수 시각 계약](../contracts/application-timing.md)의 ApplicationPeriod·V21이 조회/검색/알림/캘린더의 마감 경계를 맞춘다. [근거 링크 점검](../contracts/evidence-link-checks.md)은 관리자 원고 revision을 확인하고 제한된 HEAD 요청을 수행한다. 링크 도달 가능은 사람 검수나 공식 발급 적합성의 확인이 아니며 검수 상태·확인일을 자동 갱신하지 않는다.


## 공식 정정 공고 연결 (2026-11-08)

### 기본 목록 범위와 기존 공고 대조 (2026-11-11)

- 상태 필터 미선택 시 `DefaultNoticeScope`는 NORMAL 공고 중 OPEN·UPCOMING·UNKNOWN 및 한국 시간 오늘 기준 마감일이 28일 이내인 CLOSED만 포함한다. 같은 조건을 검색·정렬·페이지 나누기·전체 개수에 적용한다. 명시적으로 상태 필터를 선택하면 기존 상태별 전체 검색을 유지한다. 상세·개인 준비 기록의 보관/삭제 정책은 아니다.
- `LhNoticeClient.Item.officiallyClosed`는 공식 `PAN_SS`의 접수마감·접수종료·공고종료만 읽는다. V35 `notice.source_closed`는 `NoticeService.importSource`에서 해당 수집 지문과 함께 갱신하고, 후속 공고중/접수중 응답이면 해제한다. `CLSG_DT`를 신청 마감 날짜로 전용하지 않는다. 목록/상세의 CLOSED 표시와 자동 검수·정정 연결 제외가 동일한 상태를 사용한다. 날짜가 없는 공식 마감 공고는 UNKNOWN이 아니며 28일 범위를 입증할 수 없어 기본 목록에서 제외한다.
- 기존 자료의 상태 보완은 수락된 공식 API 보관 응답을 다시 파싱하고 현재 `source_key`·`source_fingerprint`가 정확히 같은 경우만 반영했다. 조건·서류·일정·원고 revision 및 개인 준비를 변경하지 않는다. 실행 범위와 결과는 `docs/reviews/2026-11-11-correction-backfill.json`에 있다.
- 정정 연결은 DB 종료 상태 외에도 현재 공고 ID에 바인딩된 공식 상세의 공고상태를 확인한다. 최신본·확인 시작 공고·각 원공고가 종료됐으면 연결 대상에서 제외하고 저장 직전에도 재검사한다. `CorrectionCheckRepository.claim(UUID)`는 명시 재점검의 대상만 지정하며 기존 임대·공개·종료 제외 조건을 우회하지 않는다.

2026-11-11 목록 정책: 검증된 `notice_correction` 관계의 정정본이 기본 정보 PASS/LH 수집 원본이면 원공고는 목록에서 제외한다. 동일 조건을 페이지 나누기 전 검색·정렬·건수·필터 선택지에 적용한다. 준비 안내 검수와 목록 공개는 별개이며 내용 revision 증가로 동일 공식 공고 ID 관계를 해제하지 않는다. 정정본 비공개 시 원공고를 다시 노출한다. 원공고 상세·개인 준비는 보존한다. 제목 유사성만으로 제외하지 않는다.

공공임대(시스템02/분류06/유형08)의 역방향 ID 미포함 형식은 공식 이력 팝업의 원공고 링크·양쪽 제목·날짜·종류와 양쪽 상세의 현재 ID/정정종류/정정사유를 함께 검증한다. 지원하는 일반→정정 2행 형식 이외는 거부한다. 이 경우 이력 URL·SHA도 관계 evidence에 보관한다. 기존 양방향 검증은 유지한다.

### 수집 후 자동 정정 연결 (2026-11-11 후속)

공식 이력의 원공고 행이 소실된 구형 공공임대는 자동으로 제목을 대조해 연결하지 않는다. 사용자가 지정한 대상의 실제 공식 공고문 전체를 별도 검토한 경우에만 내부 `reconcileReviewedLegacyAttachments`를 명시 호출할 수 있다. `ReviewedLegacyCorrection`은 지정된 두 상세 URL·공고 ID·일반/정정 종류·같은 공고일·제목·실제 수정정보와 양쪽 상세에 연결된 공고문 PDF/HWP의 검토된 SHA-256을 다시 확인한다. 공통 서식명·한 형식의 파일만으로는 처리하지 않는다. 검토 사유와 파일 ID/형식/SHA는 관계 evidence의 `attachmentReview`에 기록한다. 이 경로는 예약 수집·HTTP에 노출하지 않으며, SHA 자체를 모집 관계를 자동 판정하는 규칙으로 사용하지 않는다. 지정 원공고의 종료 여부만 관계 보완 범위로 허용하고 최신본 종료 제외·현재 지문·revision·임대·그래프 잠금·검증 유효기간은 공통 경로를 유지한다. 공고 내용·준비 안내·개인 기록은 바꾸지 않는다.

구형 LH 공공임대(시스템02/분류06/유형08) 일반 원공고가 자기 ID·일반공고 상태를 유지하는 형식도 `verifyFamilyHistory`가 지원한다. 단, 최신 정정본의 공식 이력에 해당 원공고 ID/종류/제목이 명시되고 최신본의 현재 ID/정정종류/제목과 일치해야 한다. 다른 정정본을 가리키는 원공고나 단순 추가모집·잔여세대·재공고 제목은 이 예외의 근거가 아니다. 해당 형식의 `수정정보 :` 본문은 비어 있지 않은 실제 수정 내용을 최대2000자로 보관한다.

사용자가 특정 관계를 명시 요청한 경우에만 내부 `reconcileRequestedLegacyRoot(job, originalId)` 명령으로 종료된 구형 원공고의 관계를 보완할 수 있다. 최신본 종료 제외·수집 바인딩·전체 공식이력 검증·현재 revision/지문·임대·잠금은 유지한다. 이 명령은 반드시 해당 원공고의 구형 형식을 다시 증명하며 다른 이력 항목은 변경하지 않는다. HTTP/수집 worker는 이 명령을 호출하지 않고 일반 `reconcile`의 종료 제외를 유지한다. 2026-11-11 명시 요청6건 중 종료 원공고5건의 관계만 연결했으며, 종료 상태·원고·개인 준비는 변경하지 않았다. 실행 근거: `docs/reviews/2026-11-11-legacy-six-corrections.json`.

`CollectionService → AutomaticCorrectionService.process → CorrectionCheckRepository.enqueue`는 공식 원문 식별자와 수집 지문을 지속 큐에 등록한다. 공식 확인은 독립 worker가 30초 간격으로 한 건씩 실행하며 목록 API에서는 외부 요청을 하지 않는다. `AUTOMATIC_CORRECTION_ENABLED` 기본 true, `AUTOMATIC_CORRECTION_POLL_MS` 기본 30000이다. 예약 수집과 동일하게 서버 실행이 필요하다.

`AutomaticCorrectionLinkService`는 수집에 바인딩된 공식 URL의 현재 공고 ID와 정정 이동 정보를 읽고, 최신 상세와 공고이력조회(일반 원공고 및 최대 19개 정정본)를 대조한다. 이력의 링크·종류·날짜 순서·각 상세 제목·현재 ID·정정사유가 함께 일치해야 한다. 이력을 읽을 수 없는 경우 기존 양방향 ID 검증으로 입증되는 원공고 한 쌍만 허용한다. 제목의 `[정정]`은 작업 우선순위에만 쓰이며 연결의 근거가 아니다.

최신본의 기본 정보 공개 후 관계를 적용하며 준비 안내 PASS는 요구하지 않는다. 알려진 접수 종료 공고는 기존 `AutomaticReviewEligibility` 기준으로 제외한다. 원공고·중간 정정본을 직접 최신본으로 연결하고 이전 연결 근거는 verification_history에 보관한다. V34는 여러 이전 버전이 같은 최신본을 가리키도록 대상 unique를 일반 index로 바꾸고 notice_correction_check를 추가한다. 원공고 내용·기존 개인 준비는 수정하지 않는다.

검증 HTTP는 트랜잭션 밖에서 끝내고, 짧은 관계 변경 트랜잭션에서 그래프 잠금·공고 revision/지문·작업 토큰·최신본 공개/기한을 다시 확인한다. 기존 연결을 현재 공식 이력 밖의 다른 공고로 교체하거나 이미 더 최신본을 가리키는 대상에 연결하지 않는다. 다중 수집 worker는 SKIP LOCKED/5분 lease로 중복 작업을 피하며 만료 작업은 재시도한다. 원공고 미수집·비공개·판독 불일치·전송 실패는 DEFERRED 및 원인 코드로 기록한다. 재시도는 10분부터 최대 6시간, 연결 후 6시간·정정 없음은 24시간 뒤 다시 확인하며 수집 지문 변경 시 즉시 재확인한다.

`GET /api/v1/notices/{id}/correction`은 확인된 별도 정정 공고를 `{noticeId,title,reason,evidence,checkedAt}`로 반환한다. 유효 연결이 없으면 204, 비공개 원본이면 404다. 정상 조회 응답(200/204)은 `Cache-Control: no-store`다. 대상 공고의 기본·준비 안내 PASS, NORMAL 및 양쪽 revision이 그대로인 경우만 공개한다. 원본과 정정본을 합치거나 원본을 취소/PASS로 바꾸지 않는다.

`NoticeCorrectionService.link`는 로컬·편집 검토에서 명시 호출하는 내부 명령이며 이 수동 명령을 부르는 공개 쓰기 API/예약 호출은 없다. 자동 수집은 위의 AutomaticCorrectionLinkService를 사용한다. 수동 명령은 수집 당시 source_key와 결합된 LH 상세 URL을 발췌 이력에서 식별하고, 두 공식 페이지를 새로 읽어 양방향 정정 ID·최신 정정 종류·사유를 검증한다. 대표 안내 근거가 PDF여도 게시글 출처와 구분한다. 외부 HTTP 중에는 트랜잭션을 열지 않으며, 저장 시 관계 그래프와 두 공고를 잠금하고 revision·공개 상태·120초 검증 유효기간을 확인한다. V28에서 도입한 URL·공식 ID·HTML SHA·확인 시각 저장과 수동 명령의 중복/자기 연결/체인 거절은 유지한다. 자동 다중 버전 관계는 V34 및 위 검증 규칙을 따른다. 기존 공고 내용·개인 준비 스냅샷은 유지한다.

프런트 `NoticeCorrectionBanner`는 원본 상세에 명시적인 정정본 이동 경로를 제공한다. 자동 이동·답변 이전을 하지 않고, 기존 홈/검색 복귀 상태를 이어받는다. 연결 조회 실패에는 재시도를 제공한다. 최신 upstream 변경을 수집하기 전까지 무조건 현재라고 보증하는 기능은 아니며 확인 시각을 근거에 표시한다.

개별 발표 자동 추출은 `IndividualAnnouncementCompiler` 및 `IndividualAnnouncementTableReader`가 담당하며 `AutomaticScheduleCompiler`를 거쳐 일반/후속 공식자료 검수에 연결한다. 공고별 원문 설명만 저장하며 접수종료 제외·설명 충돌 격리·날짜 미생성 계약은 [자동 검수 계약](../contracts/automatic-review.md#개별-결과-발표-자동-안내-2026-11-10)을 따른다.

## 가이드 페이지 계약 (2026-11-11)

`GET /api/v1/guides`는 category·keyword·page(기본0)를 받아 `{items,page,hasMore}`를 반환한다. 페이지당20개, 공개 자료32개 단위 읽기. 검색·분류는 전체 공개 자료에서 적용한 뒤 페이지를 나누며, 검수 전/변경 검수/충돌 항목과 local-sample 제외는 기존대로다. GuideRepository가 SQL 후보 제한·정렬·배치를, GuideService가 가이드 구성·검수 및 최종 검색 일치를 맡는다. 요약/문의만 필요한 요청도 전체 목록을 한 응답으로 반환하지 않는다. 이전 무제한 배열 응답은 제거했고 서버와 프런트를 함께 반영한다. 상세 검증은 `2026-11-11-guide-loading-pagination` 이력에 있다.

## 공고·개인 준비 목록 조회 (2026-11-11)

- `GET /notices`의 페이지 항목은 `NoticeListItem`이다. 식별자·제목·기관·지역·유형·접수 상태/기한·준비 안내 상태·부분 확인 여부(`partialGuidance`)·공식 lifecycle·수집 시각만 제공한다. 질문·서류·첨부·요약·검수 항목 전체·상세 일정은 `/notices/{id}`에서 받는다. 목록 미리보기도 이 작은 응답으로 표시한다.
- NoticeQueryRepository는 필터·정렬 후 페이지 제한을 적용하고 명시적 필드만 선택한다. Content JSON 전체를 가져오거나 NoticeEntity를 로딩하지 않는다. NoticeService가 공통 ApplicationPeriod와 안내 상태 규칙으로 응답을 만든다. 정렬·필터·개수·마감 시각 및 공식 중단 상태 계약은 유지한다.
- `GET /preparations`는 `PreparationListItem[]`이며 `id,noticeId,title,version,revision,progress`만 반환한다. DB에 저장된 평가 결과 progress는 선택기/건수용이며 최신 수정 가능 여부의 근거가 아니다. 목록은 소유자·보관 제외·샘플 제외 조건을 유지하고 한 번의 JDBC 조회로 구성한다. 모든 스냅샷 역직렬화와 준비 건별 공고 재조회를 하지 않는다.
- `/preparations/{id}`와 모든 쓰기 응답은 기존 전체 Preparation을 유지한다. 선택한 상세 조회에서 최신 안내/lifecycle를 대조하고, 쓰기는 소유권·revision·안내 버전·수정 가능 상태를 다시 검사한다. 상세 응답의 documentConditions와 documentConditionValues는 해당 버전의 저장된 서류별 조건 질문 ID와 대상 선택지를 전달한다. 선택지 지정이 없는 기존 조건은 YES로 해석한다. 개인 답변·메모·기록 저장 형식은 변경하지 않는다.
- 이전 전체 목록 응답은 제거했으며 프런트와 함께 반영한다. 별도의 전체 목록 호환 경로를 두지 않는다.
