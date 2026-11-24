# 조건 답변 상태 공통 표시

내 준비, 이전 준비 기록, 일회성 결과는 ConditionAnswerStatus로 답변 상태를 표시한다. questions.ts의 conditionAnswerState가 공식 확인 필요, 미응답, 추가 확인 필요, 답변 완료를 구분하며 needsFollowUp도 이 판정을 사용한다. conditionAnswerPresentation.ts에서 상태 문구·아이콘·색상과 집계 문구를 관리한다. 저장 기록의 질문과 답변은 해당 버전에서 전달한다.

AI 보조 구현. review/local-updates-20261115, eb64b60 기준. 프런트 테스트 505개와 빌드 통과. Chrome 공통 배지 미리보기에서 360·390·768px 네 상태의 문구와 아이콘을 확인했다. 집계 구분자 공백과 추가 확인 필요 문구를 수정한 후 전체 테스트를 다시 통과했다.
