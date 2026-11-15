# PR #26 충돌 조사와 해결 방향

- 기록일: 2026-11-15 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 최초 조사 보존 / 후속 해결·CI 결과는 연결 기록 참조
- 유형: 문제 해결 / 협업
- 담당·검토: Codex가 Git 증거와 코드를 대조. 구현 담당자·최종 검토자 미확인
- 관련 PR: https://github.com/Cho-Mi-Kim/jibro/pull/26

> 이후 사용자 요청으로 `dbecf44`·`69fd749`에서 충돌·중복 테스트·Linux 테스트 경합을 해결하고 CI가 통과했다. [해결 기록](2026-11-15-pr26-conflict-resolution.md)과 [CI](https://github.com/Cho-Mi-Kim/jibro/actions/runs/43724321929)를 참조한다. 아래 CONFLICTING 및 미실행 표시는 최초 조사 시점의 사실이다.

## 배경·문제

사용자는 GitHub 충돌 화면의 올바른 해결 방향을 요청했다. 실제 충돌 수정·원격 게시·병합은 요청하지 않았다.
검토 head는 `eb64b60bab5f8274129689f7d80f59af4b001b8e`, base develop은 `06ccac36c8b03344cb299f26a9ef61b2d7558801`이다.
공통 조상은 `e288a993a9a828a798d44eea165c0f276ab34a60`이다. develop의 PR #24 후속 계약·보관 보호·테스트 지연 분리·실행 안내와 PR #26 변경이 겹친다.

## 고려한 선택지

- PR 쪽 일괄 선택: 새 구현을 보존하지만 develop의 실행 안내·계약 보완을 잃을 수 있다.
- develop 일괄 선택: Repository 분리와 신규 기능을 훼손하며 ExperienceService에 존재하지 않는 db 참조를 넣을 수 있다.
- 구간별 결합 후 자동 병합 파일 재검토: 새 구조와 기존 보호 동작을 함께 보존하므로 권장한다.

## 결정·이유

| 충돌 파일 | 권장 방향 |
| --- | --- |
| backend/src/main/java/org/jibro/member/ExperienceService.java | 두 구간 모두 PR의 Repository 호출 유지. ExperienceRepository가 행 잠금, revision과 JSON snapshot 동시 증가, 중복 보관 차단에 필요한 구현을 보유한다. |
| frontend/src/app/App.test.tsx | PR의 memberApi import 유지. 충돌 밖 develop의 demoDelay mock도 유지한다. |
| frontend/src/features/housing/demo.ts | PR의 documentNeedsReview 및 정렬 import 유지. develop의 waitForDemoResponse import/call 유지한다. |
| docs/architecture/backend-contracts.md | PR 최신 기능 기준으로 수동 통합. develop의 계정·스냅샷·검증 범위 설명 중 유효한 내용을 보존하고 구형 미구현 설명을 제거한다. |
| docs/architecture/frontend-contracts.md | PR 모듈/스타일 소유권 절을 유지하고 develop의 현재 회원 기능 문단과 결합. 계정 연결 미구현 등 충돌 밖 낡은 설명도 수정한다. |
| docs/contracts/member-features.md | PR 보관 revision 규칙 + develop V6~V9 구분 및 과거 공개본 복원 한계를 함께 유지한다. |
| docs/getting-started.md | 수정일을 실제 해결일로 갱신. 자동 결합된 시연/실서버 실행 안내와 PR 샘플 제외 정책 모두 보존한다. |
| docs/history/README.md | 양쪽 기록 파일을 보존하고 project-history sync로 목록 재생성한다. |

## 변경·해결 과정

기존 미커밋 인수인계 문서를 보존하고 브랜치를 전환하지 않았다. 원격 두 브랜치를 fetch한 뒤 `git merge-tree --write-tree --name-only`로 작업 트리 밖의 병합 결과를 계산했다.
생성된 조사용 tree는 `1e0e5266376628f36278b4c71ddf8164475b21c5`다. 실제 Git merge나 충돌 해결 커밋은 만들지 않았다.

## 검증·결과

- GitHub mergeable은 CONFLICTING, 조회 당시 statusCheckRollup은 빈 목록이다. CI 통과로 해석하지 않는다.
- 명시적 충돌은 위 8개 파일이다.
- **자동 병합 파일의 추가 문제:** `backend/src/test/java/org/jibro/member/MemberFeaturesIntegrationTest.java`에 `assertRevision(UUID,int)`, `secondArchiveCannotOverwriteAnAlreadyArchivedMemo()`, `restoreInvalidatesOldCommandsAndKeepsBothRevisionsConsistent()`가 각각 중복 생성된다. 조사 tree의 위치는 98/165, 104/171, 120/187행이다. 동일 시그니처 중복으로 Java 테스트 소스 컴파일이 실패할 구조다. 각 테스트·helper를 한 번만 유지해야 한다.
- ExperienceRepository의 ADVANCE_REVISION, 서비스 트랜잭션, 시연 보관 revision 처리, PersistenceBoundaryTest의 서비스 직접 DB 접근 금지를 코드로 대조했다.
- PR 변경 목록에는 994개 파일과 scripts/ops/__pycache__의 .pyc 3개도 있다. 전체 기능 검토는 별도이며 캐시 파일 제외를 권장한다.
- 이번 조사는 정적 비교다. 충돌 해결 결과의 빌드·테스트·실기기 검증은 실행하지 않았다.

## 기여·한계

AI 보조로 충돌 원인과 자동 병합의 중복 메서드를 확인했다. GitHub 웹 편집기의 저장하지 않은 입력은 확인하지 않았다. 확인 기준은 원격 커밋이며 이후 head/base가 바뀌면 재조사가 필요하다.

## 발표·포트폴리오 메모

텍스트 충돌뿐 아니라 자동 병합 결과의 테스트 메서드 중복까지 조사한 사례다. 아직 수정·CI 성공 성과로 기록하지 않는다.

## 후속 작업

- PR head에 최신 develop을 병합하면서 위 방향 적용. 작업 트리 미커밋 문서와 분리된 환경 사용 권장.
- 자동 병합된 통합 테스트 중복 제거와 회귀 조건 유지.
- 프론트 ci·Pages 빌드, 백엔드 build/integrationTest와 구조 경계 검사, 작업 기록 검사를 재실행.
- 새 head의 CI 및 diff 재검토 후 별도 병합 판단.

## 변경 파일

- docs/history/entries/2026-11-15-pr26-conflict-review.md
- docs/history/README.md (기록 목록 재생성)
