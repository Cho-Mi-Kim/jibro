# PR #31 병합 전 코드 검토와 독립 환경 검증

- 기록일: 2026-11-25 (Asia/Seoul)
- 기록 상태: 실행 근거 정리 완료
- 작업 상태: 초기 로컬 검사 통과 후 원격 CI의 날짜 경계 테스트 오류를 재현·수정하고 UTC 전체 로컬 재검사를 통과했다. 최신 CI 확인 후 develop 병합 예정이며 실제 병합 결과는 아래 PR에서 확인한다.
- 유형: 협업·검증·문서
- 담당·검토: 사용자가 문제 수정·재검사 후 병합을 요청했고, Codex가 코드 검토와 검사 실행을 수행했다. 이번 검사를 사용자가 직접 수행한 것으로 기록하지 않는다.
- 관련 PR: [#31](https://github.com/Cho-Mi-Kim/jibro/pull/31)
- 검토 기준: head `ba45e2d179ef6b92a1c402768f5476c0f34da852`, base develop `db9240cc939277b147353936739083f620a75b56`.

## 배경·문제

맞춤 신청 준비, 공식 자료 판독·검수, 계정 인증 및 운영 설정을 포함하는 대규모 PR의 병합을 요청받았다. 기존 작업 폴더에는 포트폴리오와 포스터 관련 미커밋 문서가 있어 이를 보존하며 별도 worktree에서 검토했다. 기존 개발 DB와 분리한 임시 PostgreSQL 17.6 데이터베이스로 검사를 실행했다.

## 고려한 선택지·결정

기존 CI 성공만으로 병합하는 대신 주요 데이터·권한 경계를 코드로 대조하고 로컬에서 전체 검사와 빌드를 다시 실행했다. 기능별 검토는 같은 PR에서 진행하며 기존 개인 문서나 다른 작업 브랜치의 내용을 추가하지 않는다. 확인된 결함 없이 임의 리팩터링하지 않고 검토·검증 기록만 추가한다.

## 검토·해결 과정

- 계정 비밀번호 변경·연결 해제·준비 삭제의 재인증 제한과 사용자 소유권, 행 잠금, revision 충돌 검사 경로를 확인했다. 기존 메모·준비 상태 보존 경로도 대조했다.
- 저장형·일회성 조건 확인, 과거 기록, 공고 비교의 공통 개인화 정책과 사용자·공고·버전 경계를 검토했다. 일회성 답변의 비저장 처리와 비교 조회 실패 시 공개 안내 유지 경로를 확인했다.
- 자동 공개의 출처·revision·검수 조건, 변경 안내의 버전 보존, 공식 자료 접근 호스트 제한과 문서 작업 프로세스의 크기·시간·동시 실행 제한을 확인했다.
- 관리자 API 권한과 방문자 확인, 운영 프록시 설정 및 이전 커밋의 요청 제한 초기화 수정을 검토했다.
- 초기 코드 검토에서는 추가 결함을 발견하지 못했으나, 기록 커밋 `9dd1e88`의 새 원격 CI에서 아래 테스트 오류를 발견해 병합을 보류했다. 제품 코드와 테스트 단정·제한 시간·skip 조건은 변경하지 않았다.

### 원격 CI에서 발견한 UTC·한국 날짜 경계 오류

[run 45456315626](https://github.com/Cho-Mi-Kim/jibro/actions/runs/45456315626)에서 `SourceReviewPublicationIntegrationTest.sourceReviewCannotRestoreBasicsWithWrongIdentityOrClosedRecruitment`의 미래 공고 거부 단정이 실패했다. 서비스의 `LhBasicReview`는 한국 날짜를 쓰지만, 테스트의 `LocalDate.now().plusDays(1)`은 JVM 기본 시간대를 사용했다. 실행 시 UTC는 11월 24일, 한국은 11월 25일이므로 테스트가 만든 날짜는 한국 기준 미래가 아닌 오늘이었다.

`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로 수정 전 해당 메서드를 실행해 같은 오류를 로컬에서도 재현했다. 테스트 `setup`에서 한국 기준 날짜를 한 번 계산하고 공고일·확인일·과거/미래 기준을 공유하도록 수정했다. 같은 클래스의 접수·현장 방문 일정도 이 기준에서 계산해 2026년 12월 이후 고정 일정 만료로 실패하지 않도록 했다. 기존 거부 단정과 공개 경계는 유지했다.

수정 전 재현 로그·XML과 GitHub 실패 보고서는 `.tools/review/`에 별도로 보존했다. 제품의 공개 정책 변경이나 실패 테스트 제외로 우회하지 않았다.

## 검증·결과

Windows의 Node.js 24.22.2, JDK 17, 별도 PostgreSQL 17.6 환경에서 실행했다. 운영 환경 파일과 외부 서비스 키는 로드하지 않았다.

| 검사 | 실제 실행 결과 |
| --- | --- |
| `npm --prefix frontend run ci` | 서식·lint·계층·검수 카탈로그·공개 페이지/보안·타입 검사, 109개 파일의 887개 테스트, 일반 빌드 통과 |
| `npm --prefix frontend run build:pages` | 시연 빌드 통과 |
| `./backend/gradlew.bat -p backend --no-daemon clean build integrationTest` | 전체 빌드 통과. 일반 테스트 1,010개 중 1,009개 통과·선택 검사 1개 제외, PostgreSQL 통합 테스트 451개 전부 통과 |
| `node --test scripts/project-history.test.mjs` | 7개 통과 |
| `python -m unittest discover -s scripts/ops -p 'test_*.py' -v` | 35개 중 31개 통과, POSIX/OpenSSL 필요 검사 4개 Windows에서 제외 |
| 기존 head의 GitHub Actions | [run 45381571777](https://github.com/Cho-Mi-Kim/jibro/actions/runs/45381571777): frontend·backend·operations·project-history·edge-security 모두 성공 |

일반 테스트에서 제외된 1개는 `JIBRO_GUIDE_LIVE_AUDIT=true`로 명시적으로 켜야 하는 공식 사이트 직접 접속 검사다. 로컬에서 제외된 인증서 갱신 검사와 실제 Nginx 경계 검사는 Linux CI 성공 여부를 함께 확인했다. 이전 기록의 간헐적 문서 작업 프로세스 실패는 이번 전체 실행에서 재발하지 않았으나, 이를 최초 실패 원인이 해결됐다는 증거로 사용하지 않는다.

위 표는 최초 코드 기준 로컬 검사 결과다. 수정 후에는 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` 환경에서 `clean build integrationTest`를 다시 실행해 일반 1,009개 통과·기존 선택 검사 1개 제외, 통합 451개 전부 통과를 확인했다. 실행 시간은 5분이었다. 같은 시간대의 수정 전 단일 테스트 실패와 수정 후 전체 테스트 성공을 비교했다.

변경한 Java 파일은 저장소의 공유 스타일 XML과 실제 IntelliJ 2025.1.4.1을 사용하는 `spotlessCheck`도 통과했다. 일회성 init script로 검사 대상을 변경 파일에만 제한했으며 SKIPPED가 아닌 검사 태스크 실행을 확인했다. 최초 init-script 상대 경로 오류는 바로잡고 재실행했으며 성공으로 계산하지 않았다. 일반 빌드 자체에는 Java 포맷터 검사가 포함되지 않는다. Gradle 9 호환성 관련 기존 경고가 출력됐으며 현재 Gradle 8.14.6 빌드에는 실패가 없었다. 원시 로컬 로그는 커밋에서 제외되는 `.tools/review/`에 보관했다.

## 기여·한계

검토는 위험도가 높은 코드 경계와 자동 회귀 검사 중심이다. 변경된 모든 원문·공고 수치의 정확성을 재검수하거나 모든 UI를 수동 조작한 것은 아니다. 실제 Google·카카오 동의, 메일 발송, LH 실시간 수집·운영 서버 배포는 이번 검사에서 재실행하지 않았다. 이전 운영 검증 결과와 이번 독립 환경 검증 결과를 구분한다.

## 발표·포트폴리오 메모

대규모 변경을 기존 작업과 분리해 검토하고, 데이터·권한 경계 검토와 단위·통합 검사 결과를 근거로 병합 여부를 판단했다. 이는 AI 보조 검토 기록이며 사용자의 직접 코드 작성이나 직접 테스트 수행 실적으로 바꾸지 않는다.

## 후속 작업

- 이 기록 커밋의 최신 SHA에 대한 CI 전체 성공과 develop 기준 충돌 여부를 확인한 뒤 PR #31을 병합한다. 완료 상태는 PR 코멘트와 GitHub 병합 기록을 근거로 한다.
- 문서 작업 프로세스의 이전 일회성 실패가 재발하면 종료 코드·부하·실행 환경을 함께 조사한다.
- main 반영과 운영 배포는 별도 요청·검증 범위로 관리한다.

## 변경 파일

- `docs/history/entries/2026-11-25-pr31-merge-review.md`
- `docs/history/README.md` — 작업 기록 목록 자동 갱신
- `backend/src/test/java/org/jibro/notice/SourceReviewPublicationIntegrationTest.java` — 한국 기준 날짜와 상대 일정 fixture
