# 자격 요약의 책임 통합과 비교 직접 진입 보완

- 기록일: 2026-11-22 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결
- 유형: 개발 / 문제 해결
- 담당·검토: AI 구현 보조, 구현에 참여하지 않은 별도 에이전트 읽기 전용 검토. 사람 담당자 미확인.
- 관련 이슈·PR·커밋: 신규 게시 없음. 작업 시작 `review/local-updates-20261115`, HEAD `eb64b60`; 기존 미커밋 작업 보존.

## 배경·문제

사용자가 첨부한 설계 우선·중복 경로 제거 기준을 적용했다. 실제 호출자를 확인한 결과 상세 화면은 자격 요약 결과를 화면에서 다시 수정하고, 비교 화면은 별도로 설명·배점표·근거를 선택했다. 그 결과 비교에 경주영천의 원문 대조 설명과 오산의 표별 근거가 빠졌다. 비교 API는 검토 문구 자료를 불러오지 않아 상세를 먼저 방문했는지에 따라 조건 문구도 달라졌다.

## 고려한 선택지

- 화면별 보정 추가: 중복 선택 경로가 더 생기므로 기각.
- 표 파싱·표시 선택을 같은 파일에 유지: 조건 설명이 표 파서를 호출하므로 순환 참조가 생겨 기각.
- 공통 표시 모델과 실제 공유 렌더러 사용: 화면은 펼침·탐색만 소유하고 자격 요약 선택은 한 곳에 모으기로 결정.
- 백엔드 검수·게시 서비스 통합: 출처·revision·수동 검수 보호 계약이 달라 이번 변경에서 제외. 메일도 이미 공통 발송 경로여서 새 계층을 만들지 않았다.

## 결정·이유

`housing/qualificationSummary`가 요약 행·배점표·검토된 설명·근거를 결정하고 `QualificationSummaryDetails`가 이를 두 화면에 배치한다. `parseQualificationTables`는 원래 표 파싱 로직을 유지한다. 비교 응답에서는 상세와 같은 `withReviewedConditions` 로더를 재사용한다. 새 범용 설정이나 호환 전달 함수는 만들지 않았다.

## 변경·해결 과정

- 추가: 공유 자격 표시 모델, 공유 상세 렌더러, 독립된 표 파서, 소비 화면 및 비교 데이터 경계 회귀 테스트.
- 제거: `qualificationSummaryRows.ts`, 상세 화면의 문구 비교·사후 수정, 두 화면의 별도 표·설명·근거 조립. 옛 import와 호출은 모두 변경.
- 기존 파서의 숫자 처리, 원문 일치 조건, 질문의 확인 필요 상태, 비교 `guideState` 공개 제한은 유지했다. 사용자 자료·DB·선택지·자격 판정은 변경하지 않았다.
- 표시 동작 보완: 비교에도 경주영천 설명과 오산 표별 PDF 근거를 표시하고 직접 접속 시 검토 문구를 준비한다. 이 부분은 동작 보존 리팩토링과 구분한다.
- 실제 실행 중 Windows의 대소문자 비구분 파일 탐색으로 파서와 `QualificationTables.tsx`가 충돌한 것을 발견했다. 파서를 `parseQualificationTables.ts`로 바꾸고 모든 호출자를 수정했다.
- 상세에 일반 원문 링크가 추가로 나타나는 중간 변경은 제거했다. 비교의 일반 원문 fallback은 구체적인 근거가 없을 때만 유지한다.
- 남긴 임시 제품 코드: 없음. 변경 전 사본과 실행 로그는 Git 제외 `.tools/qa`에만 저장했다.

## 검증·결과

- 관련 7개 테스트 파일 70개 통과. 새 두 파일의 8개 사례는 상세·비교의 숫자/예외/PDF 쪽수와 원문·검수 상태 차단, 직접 진입한 비교의 실제 검토 문구를 검사한다. HTTP 응답만 대역 처리하고 기존 자료 로더·표시 소비자는 실제 경로를 사용한다.
- 변이 검사: 비교 API의 검토 문구 로더를 잠시 제거했을 때 새 테스트가 검토 제목 누락으로 실패했다. 정확히 복구한 뒤 재통과했다. 단정 완화·skip 없음.
- 독립 검토: 구현하지 않은 에이전트가 변경 전 사본과 현재 구조·호출·테스트를 확인했다. 잔여 차단 지적 없음. 직접 실행한 새 테스트 8개와 계층 검사도 통과했다. 표시 동작 보완을 리팩토링과 구분하라는 지적은 이 기록에 반영했다.
- 5173 실제 Opera: 경주영천 상세의 펼친 내용 1,982자 및 근거 8개가 변경 전과 동일함을 대조했다. 비교에 직접 접속하여 경주영천 가족·소득·선정 설명과 오산 배점표·PDF 9/10쪽 링크를 확인했다. 비교 화면 콘솔 오류 없음.
- 브라우저 폭 360/390/768px에서 확인했고 문서 가로 넘침 없음. 좁은 화면의 표와 펼침·접힘 동작을 확인했다. 실제 휴대폰 검증과는 다르다.
- 전체 프런트 `npm run ci` 성공: 서식·린트(경고 0 허용)·계층/순환 검사 10개·검토 원고 102개/공식 출처 134개·타입·89개 파일의 681개 테스트·운영 빌드 통과. `.tools/qa/qualification-refactor-ci.log`에 실행 결과를 보관했다.
- 빌드의 500kB 초과 청크 안내는 남아 있다(주 번들 810.97kB, gzip 243.43kB). 기준을 올리거나 경고를 숨기지 않았다. 번들 분리는 별도 성능 개선 후보다. 테스트 환경의 `scrollTo` 미구현 안내는 실제 브라우저 펼침·접힘/스크롤 확인과 구분했다.

## 기여·한계

코드·실제 브라우저 검증은 로컬 5173 범위다. 운영 `jibro.live` 배포, Git push·PR·병합은 하지 않았다. 백엔드 소스와 DB는 변경하지 않아 백엔드 전체 검사는 재실행하지 않았다. 첨부의 Iron Laws 도구와 기존 GPT Pro 대화 연결은 사용 가능한 환경에서 확인되지 않아 실행하지 않았다. 기존 정적 검사와 독립 리뷰를 해당 검사의 실행으로 대신 표기하지 않는다.

## 발표·포트폴리오 메모

화면별 같은 업무 지식의 중복과 직접 진입 시 자료 로딩 누락을 실제 호출·브라우저에서 확인하고, 책임 통합과 기존 경로 제거로 수정했다. 검사 통과 외에 기존 상세 보존, 비교 표시와 의도적 오류 주입을 확인했다. 개인 기여는 담당자 확인 후 사용한다.

## 후속 작업

- 기능별 초기 번들 분리는 별도 성능 작업에서 로딩 비용과 사용자 흐름을 측정한 뒤 판단한다.
- 외부 Iron Laws·GPT Pro 검토는 연결 가능한 환경이 필요하다.

## 변경 파일

- `frontend/src/features/housing/qualificationSummary.ts`, `QualificationSummaryDetails.tsx`, `parseQualificationTables.ts`
- `frontend/src/features/housing/NoticeQualificationSummary.tsx`, `QualificationTables.tsx`, `osanConditionDetail.ts`, `structuredConditionDetail.ts`, `alignedScoreRows.ts`
- `frontend/src/features/comparison/ComparisonContent.tsx`, `comparisonApi.ts`, `comparisonApi.test.tsx`
- `frontend/src/features/housing/QualificationSummary.test.tsx`, `conciseNoticeText.test.ts`, `samryeNoticeSummary.test.ts`
- 삭제: `frontend/src/features/housing/qualificationSummaryRows.ts`
- `docs/architecture/frontend-contracts.md`
