# 누적 PR 검토와 develop 통합 검증

- 기록일: 2026-10-30 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 진행 중
- 유형: 문제 해결 / 개발 / 협업
- 담당·검토: 사용자가 순차 검토·develop 병합을 요청. Codex가 코드 검토·수정·검증을 수행하며 사람의 별도 코드 승인으로 간주하지 않는다.
- 관련 PR: [#3](https://github.com/Cho-Mi-Kim/jibro/pull/3) → [#4](https://github.com/Cho-Mi-Kim/jibro/pull/4) → [#5](https://github.com/Cho-Mi-Kim/jibro/pull/5) → [#6](https://github.com/Cho-Mi-Kim/jibro/pull/6) → [#7](https://github.com/Cho-Mi-Kim/jibro/pull/7) → [#8](https://github.com/Cho-Mi-Kim/jibro/pull/8) → [#9](https://github.com/Cho-Mi-Kim/jibro/pull/9) → [#10](https://github.com/Cho-Mi-Kim/jibro/pull/10)

## 배경·문제

프론트엔드부터 백엔드 연동까지 8개 PR이 앞 브랜치를 대상으로 쌓여 있었다. 중간 구현의 후속 보완은 마지막 연동 PR에 있으므로 누적 결과와 개별 변경 목적을 함께 검토했다. develop 통합 후 실제 DB·화면 흐름까지 다시 확인하는 것이 사용자 요청이다.

## 고려한 선택지

- 각 브랜치를 squash하면 후속 PR에 선행 변경이 다시 나타날 수 있어 기존 merge commit·브랜치 보존 규칙을 적용한다.
- 서류 저장 때 전체 편집기를 초기화하는 기존 방식은 다른 서류의 미저장 메모를 잃는다. 서류별 초안을 유지하고 성공한 서류의 초안만 비우는 방식으로 수정한다.
- 준비도 반올림 자체는 유지하되 미완료 항목이 남아 있으면 99%까지만 표시한다. 100%·마지막 집 단계는 실제 준비 항목이 모두 기록된 경우에만 도달한다.

## 결정·이유

PR #3부터 #10까지 순서대로 develop에 merge commit으로 병합하며 브랜치는 삭제하지 않는다. 수정은 최종 연동 PR #10에 포함하고 main은 이번 통합 대상에 포함하지 않는다. API 인증·소유자 제한·버전 충돌·공개 정책과 화면의 실패 처리를 우선 검토한다.

## 변경·해결 과정

| PR | 검토 범위·판단 |
| --- | --- |
| #3 | 구현 준비 문서와 미확정 사항·후속 검증의 구분 |
| #4 | 공통 API 오류, 로그인 복귀 경로, 외부 링크 처리, 데모의 개발 환경 한정 |
| #5 | 보호된 경로, 조건·서류·일정 저장, 준비도와 원문 안내 |
| #6 | 검색·시연·회귀 테스트와 화면 검증 범위 |
| #7 | JWT issuer/audience/만료, CSRF, 갱신 회전, 관리자 권한, 소셜 계정 자동 연결 방지 |
| #8 | 검색 AND/OR, 소유자별 조회, revision 충돌, 원고 변경 시 기존 기록 보존·수정 차단 |
| #9 | 공식 URL 제한, 키 미설정 처리, 페이지 한도·부분 성공, 변경 감지 후 재검수 |
| #10 | 실제 API 응답과 화면 계약, 갱신 후 업무 오류 보존, 샘플 표시, 관리자 편집, 이번 검토 수정 |

서류 편집기의 key에 전체 준비 건 revision이 포함되어 저장마다 모든 편집기가 다시 생성됐다. 두 서류에 메모를 입력한 뒤 첫 서류만 저장하는 회귀 테스트에서 두 번째 메모가 빈 문자열이 되는 실패를 확인했다. key를 준비 건·서류 ID로 고정하고 서버 값과 미저장 초안을 분리했다. 저장 중 해당 서류 입력은 잠가 늦게 도착한 성공 응답이 새 입력을 지우지 않게 했다.

준비도 계산을 검토하면서 질문 100개 중 하나가 UNKNOWN이고 나머지 서류·일정이 완료된 경우 99.7%가 100%로 반올림되는 문제를 발견했다. 서버와 시연 계산에 미완료 상한을 적용하고 마지막 질문 확인 전후의 99%/100%·집 단계 4/5를 검사하는 테스트를 추가했다.

## 검증·결과

- 수정 전 프론트 회귀 테스트: 신규 1건 실패, 기존 4건 통과. 실패 내용은 두 번째 서류의 미저장 메모 유실이다.
- 수정 후 프론트 format/lint/typecheck/build 및 테스트 15건, 백엔드 IntelliJ 포맷·빌드·단위 5건·DB 통합 11건, 기록 자동화 7건이 통과했다. 총 38건이다.
- PR #4의 독립 CI에서 React Fast Refresh 내보내기 경고가 발견되어 후속 PR의 모듈 분리만 앞당겨 반영한다. PR #5~#9의 독립 CI는 통과했다. 최종 develop 실서버 검증 결과는 병합 완료 후 추가한다.

## 기여·한계

코드 검토·수정·실행 검증은 AI 보조 작업이다. 사용자·팀원의 구현 기여나 사람의 코드 승인을 추정하지 않는다. Google·카카오·LH 외부 키가 없어 실제 제공자 로그인·수집 검증은 여전히 후속 항목이다. 가상 공고로 확인한 흐름을 실제 정책 정확성 검증으로 표현하지 않는다.

## 발표·포트폴리오 메모

정상 흐름뿐 아니라 여러 항목 동시 편집과 준비도 반올림 경계를 점검했다. 발견한 입력 유실과 완료 오표시 문제를 수정하고 회귀 테스트로 남겼다. 통합 결과와 검증 범위를 확인 가능한 Git·테스트 근거에 연결한다.

## 후속 작업

- 병합 후 develop 기준 전체 테스트·실제 HTTP·브라우저 재확인 및 원격 CI 결과 기록.
- 제공자 키 설정 후 Google·카카오 로그인과 실제 LH 응답 별도 확인.
- AWS 운영 배포, 실제 공고 검수, 새 공고 버전으로 기존 준비 기록을 옮기는 흐름은 기존 후속 범위를 유지.

## 변경 파일

- `frontend/src/pages/PreparationPages.tsx`, `frontend/src/app/App.test.tsx`
- `frontend/src/features/housing/demo.ts`
- `backend/src/main/java/org/jibro/preparation/PreparationService.java`
- `backend/src/test/java/org/jibro/preparation/PreparationProgressTest.java`
- `docs/architecture/frontend-contracts.md`, `docs/architecture/backend-contracts.md`
