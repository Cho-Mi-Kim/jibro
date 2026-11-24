# 일정 완료 상태 공통 표시

ScheduleRecordCard, ScheduleTimeline, HistorySnapshot은 ScheduleCompletionBadge.tsx에서 일정 기록 여부의 문구·아이콘·색상을 가져온다. 일정 탭은 사용자의 준비 버전·일정과 일치하는 기록을 사용하고, 이전 기록은 선택한 버전의 recorded를 사용한다.

AI 보조 구현. review/local-updates-20261115, eb64b60 기준. 완료 표시·취소 저장 및 실패 후 재시도, 서로 다른 저장 버전의 표시를 포함한 프런트 테스트 505개 통과. 빌드·린트·계층·서식 검사 통과. Chrome 360·390·768px에서 두 상태가 일정 카드에 문구·아이콘으로 표시되고 가로로 넘치지 않음을 확인했다.
