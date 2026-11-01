# 질문별 답변과 준비 대상 서류 필터

- 기록일: 2026-11-01 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 구현·로컬 적용 완료
- 유형: 개발 / 사용자 피드백 / 데이터 계약
- 담당·검토: Codex 구현·검증 보조, 사람 검토 미확인
- 관련 브랜치: `feature/question-options-documents-20261101`

## 배경·문제

사용자는 모든 질문에 동일한 답변을 강제하지 말고 질문에 적합한 답변을 받으며, 답변 후 준비 대상 서류만 표시하도록 요청했다. 기존 서버는 YES/NO/UNKNOWN enum만 저장했고 화면은 해당 없음 서류도 편집 목록에 렌더링했다. 이미 실제 전세임대 v2 준비 기록 1건이 있어 답변 구조 변경 시 보존이 필요했다.

## 고려한 선택지

버튼 문구만 바꾸는 방법은 신청 유형·증명서 명의의 복수 선택값을 저장하지 못한다. 임의 자유 입력은 검수된 서류 분기와 연결하기 어렵고 불필요한 개인정보 입력을 늘린다. 질문별 단일 선택지 계약을 도입하고, 미확정 서류를 제외해 버리는 대신 별도 추가 확인 안내를 제공하기로 했다.

## 결정·이유

- 질문별 선택값·표시 문구·추가 확인 필요 여부를 저장한다. 질문에 없는 값은 서버가 거절한다.
- 중복된 신청 유형 질문을 합쳐 전세임대 v3는 7문항이다. 선택값에 따라 대학생/취업준비생/형제자매 증빙을 분기한다.
- 준비 목록은 REQUIRED/CONDITIONAL만 표시한다. UNKNOWN은 별도 답변 보완 안내, NOT_APPLICABLE은 숨김으로 처리한다. 기록 자체는 삭제하지 않는다.
- 기존 준비는 사용자가 새 안내 적용을 선택하면 이전 스냅샷을 보관한다. 바뀐 질문의 응답을 임의 추론하지 않는다. 메모를 유지하고 완료 서류는 재확인한다.

## 변경·해결 과정

`HousingModels` 선택지·조건값 계약, 서버 저장값 검증·서류 분기·진행률, 질문/결과/서류 UI, v3 공고 데이터, 이전 스냅샷 보관 V5 마이그레이션과 새 안내 적용 API를 구현했다. 공고 적용은 기존 검수 자료와 버전이 일치할 때만 실행한다.

첫 통합 검사에서 선택적 `requiresFollowUp` 필드가 없는 JSON을 primitive boolean으로 읽지 못하는 문제가 발견됐다. nullable 필드를 받아 false로 정규화하여 기존 자료와 호환되게 수정했다.

화면 변경은 서버·데이터 계약과 별도 커밋으로 나눴다. 결과 화면에는 사용자가 선택한 문구를 그대로 표시하며, 모든 답을 공통 예/아니오 의미로 재해석하지 않는다. 추가 확인 필요 여부도 질문별 선택지 정의를 따른다.

## 검증·결과

- 프론트엔드 `npm run ci`: 포맷·린트·타입 검사, 23개 테스트, 프로덕션 빌드 통과.
- 백엔드 `gradlew.bat --no-daemon test integrationTest bootJar`: 단위 10개·통합 16개 통과. 허용되지 않은 선택값 거절, 학생/취업준비생 분기, UNKNOWN 처리, 스냅샷 보관·소유자·리비전 검사, 메모 보존·완료 서류 재확인을 검증했다.
- V5 적용 후 전세임대 공고 v3를 로컬에 반영했다. 적용 스크립트를 재실행해 version/revision 3 유지와 기존 v2 준비 기록 1건 보존을 확인했다.
- 브라우저는 별도 가상 계정으로 검증했다. 7문항을 답변하고 대학생 유형·본인 증명서·부모 초본 조건 미해당 사례에서 공통 4개+대학생 1개만 준비 목록에 표시됨을 확인했다. 다른 4개 해당자 서류는 숨겨졌다.
- 브라우저 검증 계정은 로그아웃 후 이번 생성 계정 ID·이메일·이름을 대조해 정리했다. 기존 준비 기록 7건이 남았으며 사용자의 답변을 대신 입력하거나 새 버전으로 자동 갱신하지 않았다.
- 화면 증거: `.tools/redesign-review/question-specific-options.png`, `.tools/redesign-review/preparation-target-documents.png`.

## 기여·한계

AI 보조 구현이며 실제 신청 자격을 판정하는 기능이 아니다. 이번 질문은 단일 선택으로 충분해 숫자·날짜·다중 선택 입력은 추가하지 않았다. 예전 스냅샷은 호환해 읽고, 새 질문은 사용자의 명시적 새 안내 적용 후 사용한다. 이전 스냅샷 조회 UI는 아직 없으며 DB에 보관된다.

## 발표·포트폴리오 메모

사용자 피드백으로 획일적인 답변 구조를 질문별 계약으로 바꾸고, 답변에 따른 대상 서류만 제시하도록 개선했다. 기존 기록 보존과 알 수 없음 처리까지 함께 설계했다. 실제 사용자 효과는 별도 검증이 필요하다.

## 후속 작업

- 보관된 이전 안내·답변 조회 UI.
- 매입임대의 복합 조건 검수는 기존 보고서의 보류 항목을 계속 따른다.

## 변경 파일

- `backend/src/main/java/org/jibro/notice`, `backend/src/main/java/org/jibro/preparation`
- `backend/src/main/resources/db/migration/V5__archive_preparation_guidance_refresh.sql`
- `backend/src/test/java/org/jibro/notice/HousingIntegrationTest.java`, `backend/src/test/java/org/jibro/preparation/PreparationProgressTest.java`
- `frontend/src/features/housing`, `frontend/src/pages/PreparationPages.tsx`, `frontend/src/shared/ui/HousingUI.tsx`, `frontend/src/app/App.test.tsx`
- `data/notices/question-options-20261101.json`, `scripts/apply-guidance-review.mjs`
- `docs/contracts/question-options-and-documents.md`, 관련 검수 문서
