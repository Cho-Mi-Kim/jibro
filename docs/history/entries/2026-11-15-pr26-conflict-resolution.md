# PR #26의 develop 충돌 해결과 회귀 검증

- 기록일: 2026-11-15 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 충돌 해결·로컬/원격 CI 완료 / 후속 사용자 승인으로 develop 병합 완료
- 유형: 문제 해결 / 협업
- 담당·검토: Codex가 충돌 해결·자동 검증 보조. 최종 팀 검토는 별도
- 관련 PR: https://github.com/Cho-Mi-Kim/jibro/pull/26
- 검토 기준: PR head `eb64b60`, develop `06ccac3`, 공통 조상 `e288a99`

> 후속 결과: `69fd749`의 [CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43724321929) 네 작업이 통과했고, 이후 사용자가 병합을 승인하여 develop `8428e78`에 반영했다. 아래 병합 보류 지침은 최초 해결 요청 당시의 범위다. [후속 문서·병합 기록](2026-11-15-handoff-docs-review.md)을 참조한다.

## 배경·문제

PR #26이 PR #24 최종 보완 이전 이력에서 이어져 보관 보호·테스트·문서 변경 8개 파일에서 충돌했다. 사용자는 해결·검사·커밋·푸시·PR 코멘트를 요청했으며 PR 병합은 보류했다. 기존 작업 공간의 미커밋 인수인계 문서를 건드리지 않도록 별도 worktree에서 PR head에 develop을 통합했다.

## 고려한 선택지와 결정·이유

양쪽 일괄 선택 대신 새 Repository 구조와 기존 보호 동작을 함께 보존했다. ExperienceRepository는 소유자 행 잠금과 DB revision/JSON snapshot의 동시 증가를 이미 구현하므로 ExperienceService의 PR 쪽 호출을 유지했다. 직접 JdbcTemplate 호출을 서비스에 되살리지 않았다.

## 변경·해결 과정

- ExperienceService의 두 충돌은 PR의 Repository 호출을 유지했다.
- App.test의 memberApi 및 demo.ts의 검수·정렬 import를 보존하고 develop의 demoDelay 경계·테스트 mock을 유지했다.
- 계약 문서는 PR의 모듈·스타일·새 인증 기능과 develop의 회원 기능·마이그레이션·외부 연동 검증 한계를 합쳤다. PR #24 미병합, 소셜 계정 연결 미구현, 구형 로그아웃 설명을 현행 코드에 맞췄다.
- 실행 안내의 시연/실서버 전환 절차와 PR의 샘플 비공개 정책을 함께 보존했다. 양쪽 작업 기록은 삭제하지 않고 목록을 재생성했다.
- 자동 병합된 MemberFeaturesIntegrationTest의 동일 helper assertRevision와 보관/복원 회귀 테스트 두 개가 중복됨을 확인해 각 하나만 유지했다. 두 블록의 본문 동일성을 비교한 뒤 제거했으며 단정은 축소하지 않았다.
- PR에 포함된 Python bytecode 3개를 제거하고 __pycache__/ 및 *.py[cod]를 제외했다.
- 첫 frontend ci가 기존 PR 파일 17개의 Prettier 오류로 중단됐다. 해당 파일에만 프로젝트 포맷터를 적용했으며 검사를 우회하거나 제외하지 않았다.

## 검증·결과

- 충돌 마커와 unmerged index 없음, git diff --check 통과. 보관/복원 helper·테스트는 각각 한 번만 정의되며 PR head의 회귀 단정과 일치한다.
- Java 17 / Gradle 8.14.6: clean build 성공, 단위·구조 테스트 97개 파일 668건 통과. integrationTest는 55개 파일 358건 통과(실패·오류·건너뜀 0). PR 전용 PostgreSQL 17.6 컨테이너의 jibro_test를 사용했으며 종료 후 이 임시 컨테이너·볼륨만 제거했다.
- 프론트 포맷·린트·타입·구조 검사 통과. 구조 검사 자체 테스트 10건, TS 262개·CSS 78개의 경계 검사 통과.
- 최초 Vitest는 .tools 하위 worktree가 Vite server.fs.deny의 보호 대상에 들어가 setup.ts 로딩 단계에서 69개 suite가 실패했다(테스트 실행 전). 보안 설정을 완화하지 않고 worktree를 작업 폴더의 jibro-pr26-review로 옮긴 뒤 test:run을 재실행해 69개 파일 419건 통과했다.
- npm run build와 npm run build:pages 모두 성공했다. 기존 500kB 초과 번들 경고는 남아 있으며 실패로 처리되지는 않는다.
- node --test scripts/project-history.test.mjs 7건, Python 운영 도구 unittest 26건, evidence-relocation.node-tests.mjs 2건 통과.
- 포맷 변경한 evidence-extracts.json/evidence-manifests.json의 파싱 결과가 HEAD와 동일함을 비교했다. 캐시 3개는 추적 목록에서 제거됐고 재생성한 캐시가 gitignore로 제외됨을 확인했다.
- 기존 미커밋 인수인계 문서와 원래 작업 브랜치는 보존했다. IntelliJ formatter gate는 기존 CI에서 비활성화된 상태로 이번 검증에 포함하지 않았다.

## 기여·한계

### 원격 CI의 Linux 전용 테스트 경합 후속 수정

첫 push `dbecf44`의 [CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43723686327)에서 frontend·operations·project-history는 통과했으나 backend의 `TesseractOcrTest.watchdogAwaitsDescendantTerminationWhenTheOwningApplicationIsGone` 한 건이 실패했다. 보고서는 IOException을 기대했으나 예외가 없었다고 기록했다.

테스트용 OcrTestWorker가 watchdog 시작 후 child.waitFor()에서 대기하는 구조였다. Linux에서 watchdog이 자식을 종료하면 main이 먼저 반환하여 JVM이 0으로 종료될 수 있고, daemon watchdog의 halt(3)가 뒤따르기 전에 프로세스가 끝나는 경합이 있었다. watchdog 테스트 모드에서 main을 별도로 유지하도록 수정해 종료를 watchdog이 담당하게 했다. 제품 프로세스 정리 코드나 테스트의 예외·자손 종료 단정은 변경하지 않았다. Windows 로컬 전체 검증 통과가 Linux CI 통과를 보장하지 않음을 구분한다.

AI 보조로 텍스트 충돌과 자동 병합의 중복 메서드를 조사·수정했다. 전체 PR의 기능 정책 재심사, 실제 OAuth·LH·메일·푸시 제공자 왕복, 운영 배포 검증은 이번 범위에 포함하지 않는다. PR을 develop/main에 병합하지 않는다.

후속 수정 후 Java 17에서 `test --tests org.jibro.collection.extraction.TesseractOcrTest`를 재실행해 5건 모두 통과했다(실패·오류·건너뜀 0). Linux 검증은 후속 push의 원격 CI 결과로 별도 확인한다.

## 발표·포트폴리오 메모

충돌 표시 파일뿐 아니라 자동 병합 파일까지 확인해 동일 시그니처 중복으로 발생할 테스트 컴파일 오류를 예방했다. 실제 테스트 결과와 개발/운영 검증의 차이를 구분해 기록한다.

## 후속 작업

- 원격 CI·PR 코멘트 공유·후속 사용자 승인에 따른 develop 병합 완료.
- main/Pages 및 실제 운영 배포는 별도 지시와 검증으로 진행한다.

## 변경 파일

- 충돌 파일 8개 및 자동 병합된 MemberFeaturesIntegrationTest
- .gitignore 및 scripts/ops/__pycache__ 캐시 삭제
- 프론트엔드 포맷 파일 17개
- docs/history/entries/2026-11-15-pr26-conflict-resolution.md
- docs/history/README.md
