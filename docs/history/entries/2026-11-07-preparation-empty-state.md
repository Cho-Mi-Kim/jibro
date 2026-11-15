# 내 준비 공고 미선택 화면 정리

- 기록일: 2026-11-07 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결
- 유형: 개발 / 의사결정
- 담당·검토: 사용자 요청, Codex 구현·로컬 검증, 담당자 최종 검토 미확인
- 관련 이슈·PR·커밋: 미등록, 로컬 작업만 수행

## 배경·문제

준비 목록이 비었을 때 보관함 링크가 카드 위에 따로 있고, 시작 안내와 작은 공고 버튼만 보였다. 사용자가 이 화면의 UI 개선을 요청했다.

## 고려한 선택지

기존 카드의 여백만 조정하는 방법과 공고 선택·보관함을 한 카드 안에서 주·보조 행동으로 구분하는 방법을 검토했다.

## 결정·이유

공고 선택을 주 행동으로 강조하고 보관함은 하단 보조 링크로 통합했다. 전체 준비를 보관한 재방문자도 같은 빈 상태를 보므로 제목을 `준비할 공고를 선택해 주세요`로 정리했다. 준비 시작을 안내하고 공고 진입을 제공하는 문서의 목적을 유지한다.

## 변경·해결 과정

- `PreparationEmptyState`에 안내 아이콘·제목·설명·전체 너비 공고 버튼·구분선 아래 보관함 링크를 배치했다.
- `PreparationListPage`의 빈 상태 표시만 교체했다. `PreparationHeader`의 빈 상태 보관함 중복을 제거하고 활성 준비 선택기 안의 보관함은 유지했다.
- 기존 조회 성공·로딩·오류 분기, 인증 보호, 준비 선택 정책과 API는 변경하지 않았다. 기존 색상 토큰으로 라이트·다크 모드를 함께 지원한다.

## 검증·결과

- `npm run typecheck` 통과.
- 변경 TSX의 ESLint 통과. Prettier 지적 파일을 정리했다.
- `npx vitest run src/app/App.test.tsx`: 33개 통과.
- 5173 브라우저에서 393×852, 360×800 화면 확인. 360px에서 가로 넘침 없고 두 링크 높이는 약 51px·44px이다.
- 공고 둘러보기 → 공고 목록, 보관함 → 보관함 이동 확인. 기존 준비가 있는 별도 세션의 선택기·탭도 표시 유지 확인.
- 360px·다크 모드·아주 큰 글자에서 문구·버튼 잘림과 하단 탐색 겹침 없음. 검증 후 표시 설정을 원래 값으로 복원했다.
- 확인 이미지: `.tools/qa/preparation-empty-state/mobile-393.png` (로컬 산출물).

## 기여·한계

Codex가 구현·검증했고 별도 에이전트가 조회·라우팅 영향만 읽기 검토했다. 브라우저 크기 검증이며 실제 휴대전화 확인은 아니다. 서버·외부 연동 변경이나 원격 업로드는 수행하지 않았다.

## 발표·포트폴리오 메모

빈 상태의 분산된 링크를 한 카드에 모으고 공고 선택과 기존 기록 조회의 시각적 우선순위를 구분했다. 사용성 개선 수치는 측정하지 않았다.

## 후속 작업

- 사용자가 실제 모바일 화면에서 배치·문구를 검토한다.

## 변경 파일

- `frontend/src/features/preparation/PreparationEmptyState.tsx`
- `frontend/src/features/preparation/PreparationEmptyState.module.css`
- `frontend/src/features/preparation/PreparationHeader.tsx`
- `frontend/src/features/preparation/PreparationHeader.module.css`
- `frontend/src/pages/PreparationPages.tsx`

## 후속 요청: 개요 하단 기록 동작 정리

- 사용자 요청에 따라 개요의 `준비 계속하기` 링크를 제거하고 `이전 준비 기록 보기`와 `준비 기록 삭제`를 한 줄 좌우에 배치했다.
- `PreparationOverview.tsx`와 `Overview.module.css`의 기록 행만 변경했다. 이전 여정 메모는 행 밖에 보존하며 시연 모드의 삭제 제한 안내는 전체 열을 사용한다. 삭제 확인·인증·실제 삭제 로직은 변경하지 않았다.
- 미사용 `.continue` 스타일을 제거했다. 기존 활성 준비 전환 테스트는 남아 있는 `지금 할 일` 경로와 현재 준비 ID의 이력 경로를 확인하도록 갱신했다.
- 타입 검사, 변경 파일 Prettier·ESLint 통과. `App.test.tsx`와 `PreparationDeleteDialog.test.tsx`의 38개 테스트 통과.
- 393×852·360×800 브라우저에서 두 동작의 상단 좌표와 44px 높이가 동일하고 가로 넘침 없음. 실제 삭제는 실행하지 않았다. 확인 이미지 `.tools/qa/preparation-overview-actions/mobile-393.png`.
