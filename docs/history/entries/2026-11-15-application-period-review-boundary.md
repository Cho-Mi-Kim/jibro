# 접수기간 검수 저장 계층 수정과 전체 테스트 검증

ApplicationPeriodReviewService가 SQL을 직접 실행해 PersistenceBoundaryTest가 실패했다. 대상 조회, 추출 원본 대조, 중복 판정 이력 조회와 결과 저장을 ApplicationPeriodReviewRepository로 옮겼다. 서비스는 공고 잠금과 트랜잭션 안에서 검수 판단 및 기간 갱신을 수행한다.

백엔드 build integrationTest 통과. 단위 664건, 별도 PostgreSQL DB 통합 352건 모두 통과했으며 접수기간 검수 통합 사례 7건을 포함한다. 저장 계층 검사 구현은 유지했다.

프런트 전체 실행에서 드러난 오래된 화면 기대값과 테스트 데이터도 현재 요구에 맞췄다. 미확인 조건만 답한 뒤 종료하는 동작, 홈 카드의 독립된 상세 링크, 서류·일정별 다음 행동, 개별 안내의 PDF 4쪽 연결을 확인했다. Vitest 402건 및 의존 관계 도구 검사 10건 통과. 프런트·백엔드 빌드 성공. AI 보조 코드 정리 및 검증.
