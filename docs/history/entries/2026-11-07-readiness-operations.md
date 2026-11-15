# 공식 종료 상태·일회 확인·수집 원본과 운영 준비

- 기록일: 2026-11-07 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 로컬 구현·검증 완료, 실제 운영 적용·외부 연동 검증 미실행
- 유형: 개발 / 문제 해결 / 의사결정 / 문서
- 담당·검토: Codex가 기능별 구현·독립 검토·검증을 보조했다. 인간 개발·운영 담당자의 기여 및 최종 검토자는 미확인이다.
- 관련 이슈·PR·커밋: 이번 작업 신규 등록 없음. 작업 브랜치 `feature/readiness-operations-20261107`, 기준 HEAD `e288a993a9a828a798d44eea165c0f276ab34a60`. 기존 미커밋 변경을 보존하고 이번에도 commit/push/배포하지 않았다.

## 배경·문제

사용자는 남은 구현과 운영 준비를 확인한 뒤 공식 조기 마감·취소, 답변을 저장하지 않는 ‘이번만 확인’, 원본 API 응답 기록, 문서 파서 격리와 로컬 운영 준비를 승인했다. 실제 LH/SMTP/Web Push 자격 정보와 운영 서버·도메인은 제공되지 않았다. 승인 범위는 로컬 구현·검증과 배포할 수 있는 패키지 준비이며 실제 운영 적용이 아니다. 다국어는 앞선 사용자 결정대로 제외했다.

기존 날짜 계산만으로는 운영기관이 일찍 접수를 종료하거나 공고를 취소한 사실을 표현하지 못했다. 조건 확인은 저장하는 개인 준비 흐름이 중심이었고, 수집 결과 정규화·변경 해시는 원본 API 응답 보관을 대신하지 못했다. 문서 파서의 스레드 제한도 오류·메모리 고갈을 서버 프로세스와 분리하는 경계는 아니었다.

## 고려한 선택지

- 조기 마감 때 원래 날짜를 덮어쓰거나 비공개 처리하는 대신 공식 종료 상태를 별도로 둔다. 날짜·검수·공개와 실제 운영기관의 조치를 구분하고 기존 기록을 보존하기 위해서다.
- 일회 확인을 새 임시 계정·준비 행으로 저장했다가 삭제하는 방식 대신 읽기 전용 평가와 화면 메모리를 사용한다. 기존 자동 저장 흐름은 그대로 두고 사용자 선택과 저장 경계를 분리한다.
- 원본 응답을 해시만 남기거나 PDF 파일까지 모두 보관하는 대신 공식 JSON 전체 필드를 인증 정보 제거 후 저장한다. 원본 목록 검토에 필요한 기록을 확보하되 과거 PDF 저장과 S3는 이번 범위에서 제외한다.
- 파서 스레드를 중단하는 방식 대신 입력·출력·시간·JVM 메모리를 제한한 자식 프로세스를 사용한다. 컨테이너 전체 격리나 운영체제 접근 차단까지 구현한 것으로 표현하지 않는다.
- 운영 서버를 임의로 생성·적용하지 않고 독립 프런트/서버 이미지 구성, 설정 검사, 백업·복구 도구와 산출물 패키지를 준비한다. 실제 자격 정보·도메인·비용·담당자 확인은 운영 단계에 남긴다.

## 결정·이유

1. 공식 상태는 `NORMAL/EARLY_CLOSED/CANCELLED`로 분리하고 관리자 공식 근거·확인일·이유·명시 확인을 요구한다. 행 잠금·revision 검증 후 상태 revision과 감사 이력을 남기되 안내 version·검수 결과·개인 스냅샷을 변경하지 않는다. 정상 복원도 동일하게 공식 근거를 확인한다.
2. 일회 평가는 공개 AVAILABLE 공고의 현재 version과 canStart를 검증한다. 저장 흐름과 같은 `PreparationSnapshots`·`PreparationRules`를 이용하되 개인 저장 경로를 호출하지 않고 응답은 no-store로 제공한다. 화면 이탈·새로고침·pagehide 후 답변과 결과를 복구하지 않는다.
3. 공식 JSON은 저장한 UTF-8 본문의 SHA-256으로 공유하고 실행 회차·조회 기준일·페이지별 요청 이력은 별도로 남긴다. 키·인증 필드 제거와 실패/미수신/과대 응답 구분을 수행하며 관리자만 조회할 수 있다. JSON 재직렬화 때문에 바이트 원문과 동일하지 않음을 문서화한다.
4. 파서 worker는 실행 jar 안에 포함하고 환경 변수에서 DB·JWT·API 키를 상속하지 않는다. 실패·시간 초과·과대 출력 후 프로세스 종료를 확인한 뒤 임시 파일을 정리한다. OCR·개인 증빙 업로드·자동 자격 판정은 추가하지 않는다.
5. 기존 문서의 EC2·Nginx·Docker Compose·비공개 RDS 구조를 배포 파일로 구체화한다. 실제 운영 적용과 로컬 도구 검증을 분리하고 백업은 기존 DB에 복원하지 않는 새 임시 DB 실습으로 확인한다.

## 변경·해결 과정

- `NoticeLifecycleController/Service/Repository`·V18, 목록/상세/홈/준비/비교 상태 표시를 추가했다. 조기 마감은 신청 관련 기한, 취소는 모든 기한을 알림·다음 일정·캘린더에서 제외한다. 변경 자체는 별도 lifecycle revision 알림으로 제공하고 과거 날짜·개인 기록은 보존한다. [공식 상태 계약](../../contracts/notice-lifecycle.md).
- `OneTimeCheckController/Service`와 `OneTimeCheckSession/Result`를 연결했다. 저장하고 이어하기와 이번만 확인을 선택하며 같은 입력 계약·서류 분류를 사용한다. 서버 저장 및 브라우저 영구 저장소를 사용하지 않고 결과에서 준비도·저장됨으로 오인할 표시를 피한다.
- `CollectionResponseService/Repository`·V19, `SourceResponseSanitizer`, 관리자 `CollectionResponses`를 구현했다. JSON 보존·중복 공유·마스킹·HTTP 수신 크기 제한·실패 이력과 권한 경계를 확인했다. [원본 응답 계약](../../contracts/collection-response-archive.md).
- `BoundedDocumentParser/DocumentParserWorker/ParserProtocol`과 `documentParserJar` 빌드를 추가했다. bootJar에 내장한 worker 실행을 확인했다. [공식 발췌 계약](../../contracts/official-text-extraction.md).
- `deploy/`와 `scripts/ops/`에 배포 설정, 비밀값을 출력하지 않는 설정 검사, 논리 백업·새 DB 복구 확인, 패키징·스모크 확인 도구를 추가했다. [운영 준비 절차](../../../deploy/README.md).
- 독립 검토에서 Nginx가 서버 재생성 후 이전 Docker IP를 계속 사용할 수 있음을 발견해 DNS 재해석을 구성했다. 공개 URL 포트가 실제 443 구성과 다른데도 설정 검사를 통과하던 경우도 차단했다.
- 최종 독립 검토에서 Nginx 요청 오류 로그와 HTTP 리다이렉트의 기본 접근 로그에 OAuth query가 남을 수 있는 경로를 발견했다. 두 서버의 요청 오류 로그를 버리고 HTTP 접근 로그도 query 없는 형식으로 고정했다. 클라이언트가 넘긴 forwarded Port/Prefix도 고정·제거했다. 요청 오류 원문을 보관하지 않는 운영상 제한은 배포 문서에 명시했다.
- 백업 도구의 libpq 기본 연결은 운영 RDS 인증서 확인을 보장하지 않아 명시 PGHOST·verify-full·읽을 수 있는 CA 파일을 필수로 요구했다. 로컬 예외는 `--allow-local`과 loopback만 허용하며 서비스 설정·소켓·다중 호스트 우회를 막았다. 실제 복구 실습 이후의 이 보완은 모의 명령 호출과 거부 경계 검사로 검증했다.
- 조기 마감의 서버·프런트 일정 분류에서 ‘공식 신청 여부 확인’·‘신청 완료 확인’ 처리가 다르던 부분을 맞췄다. 서류·결과 발표·공고 게시를 먼저 분류하여 신청/마감 문구가 섞인 제목을 잘못 제외하지 않도록 양쪽 회귀 검사를 추가했다.
- 최초 lifecycle API 테스트는 CSRF 없이 권한만 검증하려다 403을 받았다. 보안 설정을 완화하지 않고 CSRF 미포함/포함·익명/일반/관리자 요청을 분리해 실제 계약을 검증했다.

## 검증·결과

전체 검사는 검증 담당 Codex(root)가 실행한 해당 시점의 결과이며, 뒤의 보완은 별도 회귀 검사로 확인했다. 다른 기능의 과거 통과를 이번 실행으로 합산하지 않는다.

| 범위 | 확인 결과 | 해석과 한계 |
| --- | --- | --- |
| 프런트 전체 CI | 210개 테스트와 포맷·Lint·경계·타입·빌드 통과 보고 | 해당 전체 검사 시점 결과. 뒤의 상태 분류 등 회귀 검사도 별도 통과했다. |
| 서버 전체 검사 | unit 62개, integration 115개 통과 보고 | 실제 PostgreSQL 테스트 DB와 V18/V19 적용 포함. 후속 회귀 검사를 별도 통과했다. |
| 공식 상태 targeted | `NoticeLifecycleIntegrationTest` 4개 통과 | 공개/검수 유지, 개인 스냅샷 보존, 새 시작 차단, 상태 필터, 수정 이력, 알림 중지·복원, revision·권한·CSRF 확인. |
| 일회 확인 | 저장·미저장 계산 공통화와 개인 DB 불변·화면 폐기 검사 통과 보고 | 실제 외부 관찰 도구의 본문 수집 설정까지 검증한 결과는 아니다. |
| 수집 원본·파서 | 마스킹/실패/중복/권한 및 worker 시간·메모리·출력 제한 검사 통과 보고 | 실제 LH 자격 정보가 없는 합성 응답 검증과 실제 운영 수집을 구분한다. |
| 실행 jar의 worker | 내장 fat jar 자식 프로세스에서 HWPX 추출 성공 보고 | 로컬 Java 실행이며 Linux 컨테이너 이미지 실행 검증은 아니다. |
| DB 백업·복구 | 실제 PostgreSQL dump를 별도 새 임시 DB로 복구하여 29개 테이블 확인·정리 성공 보고 | 애플리케이션 계정에 CREATEDB 권한이 없음을 확인하고 별도 운영자 권한을 사용했다. 운영 RDS 복구/RPO/RTO 검증은 아니다. |
| Nginx | 1.30.6 설정 문법 검사 통과 | 실제 loopback HTTPS 서버 실행은 자동 승인 정책에서 차단되어 미검증. 우회하지 않았다. |
| Docker Compose·이미지 | Compose 설정 검사 통과, 이미지 빌드 미실행 | Docker 엔진이 없어 실제 image build 및 컨테이너 동작은 미검증이다. |
| 운영 도구 최종 검사 | Python unittest 14개 통과 | 잘못된 checksum·복구 실패 정리·TLS/로컬 예외·비밀값 출력 방지 경계. 실제 외부 DB 연결은 아니다. |
| 최종 배포 파일 | `.tools/releases/readiness-operations-20261107-v2.zip`, 19개 파일 해시 대조 통과 | 실제 env·개인 DB 미포함. SHA-256 `55cd02a470220325e8c7f25b82e41762a4d4e22f8c43532bcaf3f552c1054d45`. 미커밋 작업 트리 산출물이며 배포 성공을 의미하지 않는다. |
| 모바일 브라우저 | 360/393/768px 가로 넘침 없음, 질문 선택지58px·이전/다음48px, 768px 상단 탐색 확인 | 실제 5173의 합성 모름 답변 7개→결과→새로고침 후 첫 질문·미선택으로 초기화. console error 없음. 실제 기기·스크린리더·글자 확대는 이번 검사에 포함하지 않았다. |
| 외부 기능 | 현재 LH/SMTP/Web Push 자격 정보 없음 확인 | 실제 예약 수집·메일 받은편지함·브라우저 Push 수신 완료로 표시하지 않는다. 로컬 메일 테스트는 외부 수신 성공을 뜻하지 않는다. |

로컬 검사 명령은 프런트 `npm run ci`, 서버 `gradlew test integrationTest bootJar` 및 실패/변경 범위에 대한 개별 테스트다. 배포 설정·백업·패키지 명령은 `deploy/README.md`를 따른다. 신규 UI의 브라우저 크기 확인과 최종 패키지 목록은 검증 담당의 작업 기록에서 따로 관리하며 여기서 실제 기기 검증이나 운영 배포를 완료했다고 추정하지 않는다.

## 기여·한계

Codex가 기능별 구현, 코드·설정의 독립 검토, 자동 검사와 로컬 운영 실습, 기록 정리를 보조했다. 실제 인간 담당자의 개인 성과·팀 의사결정·사용자 효과는 확인하지 않았다. 기존 미커밋 변경과 이번 변경이 같은 작업 트리에 있으므로 기준 HEAD만으로 이번 산출물을 재현했다고 주장하지 않는다. 패키지는 소스 HEAD와 작업 트리 변경 여부·파일 해시를 함께 기록한다.

공식 근거의 진위·조기 마감 적용 책임은 검수자에게 있다. 일회 답변은 서버에서 일시 처리하므로 ‘서버로 전송하지 않음’이 아니다. 원본 JSON 보관은 인증 정보 제거 후 전체 필드 보존이며 과거 PDF 원본 저장과 다르다. 이미 발송된 메시지·가져온 캘린더 파일을 회수하지 않는다. 모든 로컬 검사는 실제 운영 자원·비밀값·실기기 검증을 대신하지 않는다.

## 발표·포트폴리오 메모

날짜 기반 공고 상태와 공식 중단 조치, 저장하는 준비 기록과 일회 평가, 정규화 결과와 수집 원본을 각각 분리했다. 기존 안내 버전·개인 스냅샷 계약을 보존하면서 별도 상태·읽기 전용 계산·원본 이력 경계를 추가하고 자동 검사로 확인한 사례다. 운영 배포 완료나 실제 사용자 개선 수치로 표현하지 않는다. 인간 담당자 확인 후 개인 기여를 구분해 사용한다.

## 후속 작업

- 실제 담당자·서버·도메인·인증서·비밀값을 지정한 뒤 Docker 이미지 빌드, Linux 컨테이너·HTTPS 프록시·OAuth 복귀·Secure 쿠키를 확인한다.
- 승인된 본인 테스트 계정으로 LH 수집·정각 실행·SMTP 수신·Web Push 허용/수신/해제를 검증한다.
- 운영 RDS 백업 보관량·암호화·기간과 복구 절차·삭제 재반영·RPO/RTO를 결정하고 실습한다. JSON 이력 증가량도 포함한다.
- 모바일 브라우저 크기 확인과 실제 기기/스크린리더 확인을 구분해 완료한다.
- 사용자 피드백·명시적 답변 모순 규칙·기관 확대·OCR 등 설계만 채택한 범위는 별도 요구·근거가 있을 때 진행한다.
- 이 기록의 검증 증거·담당 기여를 인간 검토자가 확인한다. 이번 작업에서는 commit/push/원격 게시·운영 배포를 하지 않는다.

## 변경 파일

- `backend/src/main/java/org/jibro/notice/NoticeLifecycle*`, `NoticeService`, `NoticeSchedules`, `HousingModels`, `backend/src/main/resources/db/migration/V18__notice_official_lifecycle.sql`
- `backend/src/main/java/org/jibro/preparation/OneTimeCheck*`, `PreparationSnapshots`, `PreparationService`
- `backend/src/main/java/org/jibro/collection/CollectionResponse*`, `SourceResponse*`, V19 migration
- `backend/src/main/java/org/jibro/collection/extraction/BoundedDocumentParser.java`, `DocumentParserWorker.java`, `ParserProtocol.java`, `backend/build.gradle`
- `frontend/src/features/housing/noticeLifecycle*`, `NoticeLifecycleNotice*`, `frontend/src/features/admin/NoticeLifecycleEditor.tsx`, `CollectionResponses*`
- `frontend/src/features/preparation/OneTimeCheck*`, `oneTimeCheckApi.ts`, `frontend/src/pages/OneTimeCheckPage*`, 준비·일정·상세·비교 연결 화면
- `deploy/`, `scripts/ops/`, 운영 프로필·설정 검사, 릴리스 workflow와 관련 테스트
- `docs/planning/backlog-decisions-20261106.md`, `docs/architecture/backend-contracts.md`, 관련 기능 계약
