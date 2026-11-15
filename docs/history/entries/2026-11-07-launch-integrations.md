# 출시 준비: 수집·접수 시각·근거·인증·알림 연결 보완

- 기록일: 2026-11-07 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 코드 구현·로컬 검증 완료, 외부 연동 검증 대기
- 유형: 개발 / 문제 해결 / 운영 준비
- 담당·검토: Codex 보조 구현·교차 검토. 팀 담당자 검토 미확인
- 관련 이슈·PR·커밋: 미등록. 로컬 `feature/launch-integrations-20261107`

## 배경·문제

사용자가 출시 준비 목록 1, 2, 8, 17, 18, 20, 21을 선택하고, 과거 실연동 성공 기록을 재사용하기보다 GitHub 코드 구현 규칙에 맞춰 보완하도록 요청했다. 시작 HEAD는 `e288a99`, 기존 브랜치는 `fix/condition-resume-20261107`이며 기존 변경 326개를 보존했다. 원격 develop `ebccaa7`은 현재 HEAD의 선행 커밋이었다. 이 작업의 변경을 선행 작업 전체의 성과로 집계하지 않는다.

GitHub main의 `docs/conventions/coding-conventions.md`와 로컬 문서 blob SHA `cfa54b8ebeacb811ca21b4e6713c7bd038a04e81`이 같음을 확인했다. 기능별 모듈, HTTP/업무/저장 책임, 서버 정책, 관리자 검수, 재현 가능한 DB 변경을 기준으로 했다.

## 고려한 선택지

- 최근 3개월·단일 LH 분류를 유지하는 대신 분류와 과거 게시일 범위를 명시하고 실행별로 고정했다. 넓은 범위는 요청량이 커지므로 페이지 한도·재시도·이어읽기를 함께 유지했다.
- 화면마다 마감 시각을 추측하는 대신 서버가 확정 시각을 제공하고 검색·화면·알림·캘린더가 이를 사용하게 했다. 시각이 불명확하면 날짜 기준 동작을 유지한다.
- HTTP 접근 성공으로 근거 검수를 자동 완료하는 방식은 채택하지 않았다. URL 접근 점검과 원문·발췌 위치·발급 대상의 사람 검수를 분리했다.
- 외부 설정이 없는데 과거 성공을 현재 검증으로 표시하지 않는다. 설정 누락은 비활성, 설정 형식 통과는 미검증 연결로 표시한다.

## 결정·변경

| 항목 | 구현과 책임 | 현재 확인 범위 |
| --- | --- | --- |
| 1 수집 범위 | `CollectionQueryPlan`의 분류 06/13·게시일 하한, 실행별 조회 범위·범주/페이지 checkpoint. 분류별 500페이지 상한은 PARTIAL 처리 | 모의 API·DB 검증. 2000-01-01은 기본 설정이며 실제 제공 이력의 보증이 아니다 |
| 2 접수 시각 | 순수 `ApplicationPeriod`, Notice 파생 시각 저장, Querydsl 상태 필터, `applicationTiming` 응답. 프런트 경계 재조회·캘린더·서버 알림 반영 | 정각 경계·한국 날짜·오전/오후·상충·원고 갱신·검색/상세 일치 |
| 8 출처·발급 링크 | `EvidenceChecksController → EvidenceCheckService → EvidenceSourceReader`, 제한된 공식 URL 점검, 관리자 화면. 검수 결과·확인일·버전은 자동 변경하지 않음 | 권한·CSRF·revision·안전한 연결 검사 및 공개 공식 자료 응답 확인 |
| 17 LH 연결 검증 준비 | 전체 범위·중복/반복 페이지·응답 보관·재시도 검사, 설정 검사 도구 보완 | 현재 LH 키가 없어 실제 기관 API 수집은 미검증 |
| 18 예약 수집 | 한국 시각 00·06·09~18시, DB 예약 슬롯 중복 방지·재시작 복구·임대·이어읽기 | 모의 시각/DB 검사. 실제 키로 하루 예약 실행은 미검증 |
| 20 Google·카카오 | 고정 callback origin, 운영 HTTPS 동일 출처, 누락/부분 설정 처리. `RefreshCookie`를 HTTP 어댑터로 분리 | 로그인 연결/해제·경합·세션 검사. 실제 provider 계정 왕복은 미검증 |
| 21 알림 채널 | 사용자별 실패 격리, 발송 직전 동의·이메일 확인·기기 소유권, 만료/영구 실패/재시도 구분. API/브라우저 구독/React 훅 분리 | 모의 발송·DB·비동기 계정 전환 검사. 실제 메일/푸시 수신은 미검증 |

푸시 등록에는 `expectedUserId`를 보내 현재 인증 계정과 대조한다. 다른 탭 로그인이나 토큰 갱신으로 계정이 달라져도 이전 구독 작업을 새 계정에 저장하지 않는다. 새로 만든 미등록 구독만 조건부 정리하며 기존·이미 등록된 구독은 보존한다.

V20은 수집 범위/원본 응답 필수 컬럼과 checkpoint를 추가했다. 구버전 수집 INSERT와 호환하지 않으므로 무조건적인 서버 rollback은 금지한다. V21은 현재 DTO/정책 클래스에 의존하지 않는 동결된 파서로 검색용 시각 컬럼을 보완한다. 준비 snapshot·공고 안내 버전은 변경하지 않는다. 자세한 운영 호환 범위는 `deploy/README.md`에 남겼다.

## 실패 원인과 해결

- 수집 전송 테스트의 Mockito 중첩 stub을 분리했다.
- Windows 파서 취소에서 중복 interrupt와 프로세스 종료 직후 파일 핸들 지연 때문에 임시 폴더 정리가 실패했다. 종료 대기 예산·interrupt 보존·닫힌 디렉터리 순회·Windows 공유 위반에 한정된 짧은 재시도로 수정했다. 파일 접근 거부를 임의로 무시하지 않는다.
- 일정 타임라인의 기존 날짜 전용 표현과 invalid date 처리를 보존하면서 새 서버 시각 응답을 적용했다.
- 알림 통합 테스트가 소셜 가입 직후 이메일 확인 완료를 잘못 가정했다. 실제 확인 토큰 처리 전 차단/처리 후 허용을 검증하도록 수정했다.
- Spring Security Test `jwt()`가 CSRF를 건너뛰어 관리자 요청 테스트가 잘못 통과했다. `authentication()`으로 인증을 주입하고 CSRF 누락/오류 403과 정상 경로를 검사했다. 제품 보안을 완화하지 않았다.

## 검증·결과

- `frontend: npm run ci` 통과: Prettier·ESLint·계층/순환 검사 10개·타입 검사·Vitest 45파일 248테스트·배포 빌드. 기존 크기 경고(500kB 초과 JS 청크)는 남아 있다.
- `backend: gradlew test integrationTest bootJar` 통과: 단위 100개, 별도 PostgreSQL DB 통합 130개. V20/V21 적용과 기존 데이터 읽기/변경 경로 포함.
- 이번 변경 Java 49파일: IntelliJ 2026.2.3 + 저장소 공유 XML로 적용 후 dry-run 49파일 통과. 포맷 후 `bootJar` 재빌드 통과. 팀/원격 CI와 포맷터 버전 일치는 이 로컬 실행으로 보증하지 않는다.
- 운영 설정 도구 Python 테스트 26개 통과. 실제 로컬 `.env` 검사는 설정 오류 0, LH·SMTP·Web Push·Google·Kakao 모두 disabled. 외부 요청을 하지 않는 형식 검사다.
- 로컬 DB를 `.tools/backups/launch-integrations-before-20261107.dump`에 백업한 뒤 V20/V21 적용. 이 백업의 복구 실습은 이번 작업에서 수행하지 않았다.
- 기존 프로젝트 프로세스를 확인하여 최신 jar로 백엔드를 재시작했다. 5173의 기존 Vite를 유지했다. `/api/v1/health` DB UP, 공고 3건의 시각 응답, 목록/상세 일치, provider 미설정 비활성 확인.
- Chrome 브라우저 360·390·393·768px에서 공고 목록·상세·로그인 12화면, 상세 200% 글자 확대 확인. 가로 넘침 0, pageerror 0. 393×852 캡처에서 일정·고정 하단 버튼을 확인했다. 실제 모바일 기기 검증은 아니다.
- `git diff --check` 통과. 원격 push/PR/배포는 수행하지 않았다.

## 기여·한계

사용자는 구현 범위와 GitHub 규칙 준수를 지정했다. Codex가 코드·테스트·문서 구현과 교차 검토를 보조했다. 과거 ChatGPT 코드나 외부 배포본을 복사하지 않았다. 공개 LH 첨부의 형식과 응답을 확인한 것이 원고 전체의 내용 적합성을 검수한 것은 아니다.

## 발표·포트폴리오 메모

수집·정확한 접수 시각·공식 근거·인증·알림의 경계를 기능별로 분리하고, 성공 판정의 근거를 로컬 검사와 외부 실제 수신으로 나눴다. 실제 서비스 연동 완료로 소개하지 않는다. 개인 기여 표기는 팀 담당자 확인 후 사용한다.

## 후속 작업

- LH 키: 분류별 실제 제공 이력·응답 한도·수집 총수와 원본 이력 대조, 예약 슬롯 하루 관찰.
- 공개 HTTPS 도메인과 Google·카카오 앱: callback 등록 후 본인 계정 로그인/연결/해제/로그아웃.
- SMTP·Web Push: 본인 테스트 수신처·브라우저로 동의/철회·실제 수신·만료/실패 재시도 확인.
- 관리자: 공고별 날짜·시각·조건·발췌 위치·발급 기관의 내용 검수. 자동 접근 점검으로 대체하지 않는다.
- 운영: `deploy/README.md`의 호환 버전·백업 복구·기동 확인 절차로 별도 검증.

## 변경 파일

- `backend/src/main/java/org/jibro/{collection,notice,auth,member}`의 위 표에 명시한 정책·서비스·어댑터와 관련 테스트
- `backend/src/main/resources/db/migration/V20__collection_scope_cursors.sql`, `backend/src/main/java/db/migration/V21__notice_application_timing.java`
- `frontend/src/features/{housing,preparation,member,admin}`의 일정·알림·근거 모듈 및 `pages/{NoticePages,AdminPage}.tsx`
- `.env.example`, `deploy/runtime.env.example`, `backend/src/main/resources/application.yml`, `scripts/ops/{check_configuration,test_operations}.py`
- `docs/contracts/{application-timing,evidence-link-checks,collection-schedule,collection-response-archive,account-connections,member-features,official-text-extraction}.md`, `docs/architecture/*-contracts.md`, `data/notices/README.md`, `deploy/README.md`
