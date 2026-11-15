# 진행 중인 조건 질문 이어가기

- 기록일: 2026-11-07 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결
- 유형: 개발 / 문제 해결
- 담당·검토: 사용자 요청, Codex 구현·검증, 담당자 최종 검토 미확인
- 관련 이슈·PR·커밋: 미등록, `fix/condition-resume-20261107` 로컬 작업

## 배경·문제

사용자가 지금 할 일의 `확인하지 못한 조건 살펴보기`에서 진행 중인 질문으로 이동하기를 요청했다. 서버와 시연 응답의 nextAction은 `/conditions/edit`를 제공하지만 ConditionsPage는 질문 쿼리가 없으면 항상 첫 질문을 표시했다.

## 고려한 선택지

홈·개요 링크마다 질문 번호를 붙이는 방법과 질문 화면의 기본 진입 기준을 통합하는 방법을 검토했다. 링크마다 처리하면 직접 주소 진입·다른 시작 경로에 같은 문제가 남는다.

## 결정·이유

유효한 명시 질문 → 첫 미응답/유효하지 않은 답변 → 첫 모름·추가 확인 답변 → 첫 질문 순으로 공통 탐색 함수를 적용했다. 미응답이 있으면 이전의 모름 응답으로 되돌아가지 않고 진행을 이어가며, 모두 답한 경우에는 남은 추가 확인을 보완한다. 화면설계서의 미입력·모름 구분과 저장 답변 복원 기준을 따른다.

## 변경·해결 과정

- `questionNavigation.ts`가 기존 validAnswer·needsFollowUp을 사용해 질문 인덱스를 선택한다. 자격·진행도 정책은 재구현하지 않는다.
- `ConditionsPage`가 기본 진입 질문을 URL replace로 고정해 재조회 후에도 같은 질문을 유지한다. 명시한 질문, 이전·다음과 복귀 state는 보존한다.
- 조건 탭의 `준비 이어하기`도 같은 탐색 함수를 사용한다. 서버 nextAction·답변 저장 API는 변경하지 않았다.

## 검증·결과

- 초기 테스트 데이터의 확인일 자료형 오류를 수정한 뒤 타입 검사·변경 파일 ESLint 통과. Prettier 정리 완료.
- `questionNavigation.test.ts`, `App.test.tsx`, `ConditionsTypedQuestions.test.tsx`: 48개 통과. 미응답 우선·모름/추가 확인 복귀·명시 질문·잘못된 질문·빈 목록·이전·자동 저장·실패 보존·입력 자료형 흐름 포함.
- 경계 검사 테스트 10개와 실제 계층·순환·선택자 계약 검사 통과.
- 5173의 별도 임시 검증 세션에서 첫 질문에 UNKNOWN을 저장하고 준비 화면으로 나간 뒤 지금 할 일을 클릭했다. 393×852 화면에서 질문 2/7과 미선택 선택지를 표시하고 `question=unmarried`로 고정되는 것을 확인했다. 기존 사용자의 답변은 변경하지 않았다.
- 확인 이미지: `.tools/qa/condition-resume/mobile-393.png` (로컬 산출물).

## 기여·한계

Codex가 구현·검증하고 별도 에이전트가 원인·공통화 범위·회귀 위험을 읽기 검토했다. 실제 기기 검증은 아니며 별도 임시 검증 세션의 기록은 기존 만료 정책을 따른다. 서버·외부 서비스 변경이나 원격 업로드는 없다.

## 발표·포트폴리오 메모

링크별 예외 대신 질문 진입점에 재개 규칙을 모아 미입력·모름을 구분한 이어가기 동작을 구현했다. 사용자 효과 수치는 측정하지 않았다.

## 후속 작업

- 사용자가 실제 진행 중인 준비 건에서 이어가기 확인.

## 변경 파일

- `frontend/src/features/preparation/questionNavigation.ts`
- `frontend/src/features/preparation/questionNavigation.test.ts`
- `frontend/src/features/preparation/PreparationTabLayout.tsx`
- `frontend/src/pages/ConditionsPage.tsx`
- `frontend/src/app/App.test.tsx`
