# main 반영 검증에서 발견한 UTC·한국 날짜 경계 수정

- 기록일: 2026-11-16 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: PR #28 병합 보류 / 별도 수정 브랜치에서 날짜 회귀 검증
- 유형: 문제 해결 / 테스트 / 배포 검증
- 담당·검토: 사용자 요청으로 Codex가 PR·CI·테스트를 대조했다. 최종 공개 사이트 확인 결과는 PR #28의 배포 기록으로 구분한다.
- 관련 PR: [main 반영 PR #28](https://github.com/Cho-Mi-Kim/jibro/pull/28), 선행 PR #26·#27

## 배경·문제

사용자가 develop의 실행 화면을 확인한 뒤 main PR·CI 검토·병합·GitHub Pages 시연 배포 확인을 요청했다. develop `501f900`에서 PR #28을 생성했다. frontend·operations·project-history는 통과했으나 [최초 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43729988321)의 PostgreSQL 통합 테스트 358건 중 한 건이 실패했다.

## 고려한 선택지와 결정·이유

- 같은 CI를 성공할 때까지 반복하거나 서비스의 한국 시간 기준을 바꾸지 않는다.
- 테스트 fixture가 사용하는 날짜만 서비스와 동일한 Asia/Seoul 기준으로 명시한다. 제품 코드와 기존 기대값·단정은 유지한다.
- 작업 브랜치별 PR 원칙에 따라 `fix/collection-test-timezone-20261116`을 develop 대상 별도 PR로 반영하고 PR #28을 다시 검증한다. 실행 중인 원래 develop 프로젝트와 개발 DB는 유지하며 독립 worktree·임시 테스트 DB를 사용한다.

## 변경·해결 과정

`OfficialFileCollectionIntegrationTest.collectedNoticeBecomesEligibleAfterDateReviewAndLinksARealMemberNameAcrossViews`가 UTC PostgreSQL의 `current_date+1`을 접수 시작일로 저장하고 UPCOMING을 기대했다. ApplicationPeriod는 한국 시간의 날짜 경계와 현재 Instant로 상태를 계산한다. CI 시각은 UTC 11월 15일 15:29, 한국 시간 11월 16일 00:29였으므로 해당 날짜는 이미 접수 시작일이어서 OPEN이 맞았다.

- 테스트의 게시일·신청 기간은 `LocalDate.now(ApplicationPeriod.SEOUL)`에서 계산해 JDBC 매개변수로 넣는다. 시작은 이틀 뒤·종료는 나흘 뒤로 두어 테스트 실행 중 날짜가 넘어가도 미래 기간을 유지한다. 정확한 날짜 경계 자체는 기존 ApplicationPeriodTest의 범위다.
- 테스트 DB 세션을 `SET LOCAL TIME ZONE 'UTC'`로 명시해 로컬 DB 시간대와 무관하게 CI 환경 차이를 유지한다. 모든 공고·서류 파일·개인 준비 연결 및 종료 후 첨부 숨김 단정은 그대로다.
- 임시 PostgreSQL 조회에서 UTC 날짜 `2026-11-15`, 한국 날짜 `2026-11-16`의 실제 차이를 확인했다. JVM도 UTC로 실행해 해당 회귀를 검증한다.

## 검증·결과

- 최초 실패 보고서: expected UPCOMING, actual OPEN. 본문·날짜·공개 정책 제품 결함이 아니라 테스트의 시간대 혼용이다.
- Java 17, `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`, 별도 PostgreSQL 17.6의 `jibro_test`에서 해당 통합 테스트 1건이 통과했다. `git diff --check`도 통과했다. 전체 회귀는 수정 PR과 PR #28의 최신 CI로 확인한다.
- main 반영 변경 검토에서는 Pages 전용 모드, `/jibro/` base, HashRouter, 실제 API를 호출하지 않는 시연 어댑터 회귀를 대조했다. 기존 문서 한 곳의 EOF 공백 경고는 실행에 영향을 주지 않는 서식 항목으로 구분했다.

## 기여·한계

AI가 CI 실패를 날짜 경계와 연결해 테스트를 수정했다. 실제 LH·OAuth·SMTP·Web Push 운영 왕복을 이번 수정의 성공으로 주장하지 않는다. 기존 코드 997개 변경 파일 전체를 신규 보안 감사한 것은 아니며, 선행 PR 검토·CI·사용자의 develop 화면 확인 위에 main 배포 경계를 검토했다.

## 발표·포트폴리오 메모

UTC CI와 한국 서비스 시간대가 자정 이후 달라지는 실패를 기대값 완화 없이 해결하고, 제품 판정과 테스트 fixture의 책임을 분리했다.

## 후속 작업

- 수정 PR의 CI 성공 후 develop에 먼저 병합하고 PR #28의 최신 head CI·차이를 재검토한다.
- main 병합 후 해당 merge commit의 Pages build/deploy와 공개 사이트 시연 진입·조건/서류/일정·새로고침을 확인한다. 최종 배포·검증 결과는 PR #28 본문과 GitHub Actions에서 확인한다.

## 변경 파일

- backend/src/test/java/org/jibro/collection/attachments/OfficialFileCollectionIntegrationTest.java
- 본 기록과 docs/history/README.md
