# main 반영 검증에서 발견한 UTC·한국 날짜 경계 수정

- 기록일: 2026-11-16 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: PR #29 develop 병합·PR #28 main 병합·Pages 공개 시연 검증 완료 / 로컬 develop 동기화 확인
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

- 아래 최종 검증 결과를 팀원이 확인하고 담당·검토 정보를 보완한다. 실제 외부 서비스 운영 검증은 별도 범위다.

## 최종 병합·배포 결과

- [수정 PR #29](https://github.com/Cho-Mi-Kim/jibro/pull/29)의 전체 CI 성공 후 develop에 병합했다. develop 커밋은 `68bd9e961db9104ae9d3292ca7527380414ac0fd`다.
- [PR #28 최종 CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43731112607)의 frontend·backend·operations·project-history가 모두 성공했다. main 병합 커밋은 `19c1a4f2284c433d79be3d424c0b646f849b163d`다.
- 해당 main 커밋의 [Pages workflow](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43731519733)에서 build·deploy가 모두 성공했다.
- [공개 시연 사이트](https://cho-mi-kim.github.io/jibro/)에서 공고 4건·미리보기·상세, 시연 사용자 진입, 조건 답변 2건과 결과, 준비 대상 서류 2건, 서류 완료 기록(1/2·50%), 신청 일정 표시를 직접 확인했다. 공고 경로 새로고침 후 정상 표시와 브라우저 콘솔 오류 없음도 확인했다. 실제 계정·서버 저장·외부 기관 신청 검증으로 확대하지 않는다.

## 로컬 동기화와 잔여 작업 확인

- 사용자 요청으로 `git fetch origin` 후 원본 `jibro-housing`의 develop에서 `git merge --ff-only --no-stat origin/develop`을 실행했다. 이미 최신 상태였고 `HEAD...origin/develop`의 앞섬·뒤처짐은 모두 0이었다. develop의 미푸시 커밋도 없었다.
- 점검 시작 시 원본 작업 폴더에 미커밋 변경이나 미추적 파일이 없었다. 당시 main과 develop의 파일 내용도 동일했다. 확인 뒤 본 기록의 보류 상태와 완료 결과를 갱신했으며, 당시에는 커밋·push 요청이 없어 로컬 변경으로 남겼다.
- 별도 작업 사본 `jibro-pr26-review`, `jibro-pr28-ci`는 깨끗했다. `.tools/pr-worktrees/gitignore`와 `.tools/pr-worktrees/mockups`에는 각각 2026-10-31 작업 기록 한 파일의 미커밋 추가 내용이 남아 있다. 줄바꿈·끝 공백을 정규화해 비교한 결과 현재 develop의 해당 기록과 내용이 같아, 누락된 별도 작업이 아니라 이미 반영된 사후 병합 기록의 사본이다. 사본을 초기화하거나 삭제하지 않았다.
- 기존 `.tools/runtime` 실행 기록과 `.tools/backups/jibro-before-develop-20261115.dump`도 유지했다. 이번 작업은 Git 상태와 문서 확인이므로 제품 빌드·기능 테스트를 반복하지 않았다.
- 후속 사용자 요청으로 `docs/deployment-sync-record-20261116` 브랜치를 만들어 본 배포·동기화 기록만 별도 develop 대상 PR로 제출한다. 제품 코드나 예전 작업 사본은 변경하지 않으며, PR 최신 커밋의 CI와 변경 범위를 검토한 뒤 병합한다. 최종 CI·병합 결과는 해당 PR과 Git 이력을 근거로 확인한다.

## 변경 파일

- backend/src/test/java/org/jibro/collection/attachments/OfficialFileCollectionIntegrationTest.java
- 본 기록과 docs/history/README.md
