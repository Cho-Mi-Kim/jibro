# 새 수집 공고의 서류마감 자동 연결과 접수종료 제외

- 기록일: 2026-11-10 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 구현·로컬 검증 완료, 외부 운영 배포 안 함
- 유형: 개발 / 문제 해결
- 담당·검토: Codex 구현·검증 보조. 팀 검토자 미확인
- 관련 커밋·PR: 이번 작업의 커밋·push 없음

## 배경·문제

사용자는 앞서 현재 공개 공고의 서류마감을 공식 자료와 대조해 보정한 뒤, 앞으로 수집되는 공고도 같은 누락을 방지하고 접수종료 공고는 제외하도록 요청했다. 기존 일정 해석기는 명시적인 서류 기간/마감은 읽지만 일반 신청자의 동시 서류 제출 안내를 신청 기한에 연결하지 않았다. 자동 대기열에는 종료 제외 조건이 없었다.

## 고려한 선택지·결정

- 모든 신청마감을 서류마감으로 복사하는 방법은 별도 대상자 제출/계약일을 오인하므로 채택하지 않음.
- 공고 ID별 지난 보정값 재생 대신 동일 공식 원문의 명시적 동시 제출 관계만 연결하는 순수 모듈을 추가.
- 별도 서류 날짜, 미정/충돌, 여러 순위·단지별 일정은 기존 검수 범위를 보존. 종료 여부는 대기열과 최종 저장 양쪽에서 확인.

## 변경·해결 과정

- `ScheduleSourceReader`: 단독 서류 제출 표제와 인접 날짜, `서류 제출: 날짜`, 신청·서류 공동 접수기간 지원. 무관한 표제 사이의 날짜는 연결하지 않음.
- `ConcurrentDocumentSubmission`: 일반 스캔 첨부/동시 제출/서류 지참 접수 안내를 동일 원문의 단일 마감과 연결. 날짜·시각·조기중단 조건·두 근거 위치 보존. 고령자/개별 동의서/계약 시 제출 등을 공통 제출 기한으로 확대하지 않음.
- `AutomaticReviewEligibility`: 검수·후속 대조·로컬/API 분석 대기열의 종료 제외 조건 공유. 기존 공식 종료 사실, 한국 시각의 날짜/시간 경계, 처리 중 마감 경과 및 처음 읽은 지난 기간을 검증. 종료 공고 24시간 재검토 분기 제거.
- 정책 `official-rules-v7`, `source-review-v7-docs`로 기존 결과와 구분. 통합 검사에서 합성 캐시 키의 DB 길이 제한을 확인해 짧은 식별자로 수정.
- 기존 SHA·revision·공식 후속 자료 검증과 수동 검수 보호 유지. 개인 준비 스냅샷 변경 없음. 기존 11건 보정을 다시 실행하지 않음.

## 검증·결과

- 단위/서비스 회귀 66건 통과: `AutomaticScheduleCompilerTest`, `ConcurrentDocumentSubmissionTest`, `AutomaticReviewEligibilityTest`, `GuidanceReview*Test`, `AutomaticReviewServiceCoverageTest`, `AutomaticFollowupServiceTest`.
- 별도 PostgreSQL `jibro_test` 통합 44건 통과: 자동 게시 8, 후속 게시 28, 로컬 분석 8. 동시 서류마감의 실제 저장·공개·재실행 멱등성, 접수종료 대기열 제외, 작업 도중 종료, 새 원문에서 처음 발견한 지난 기간, 종료 후 API 결과 저장 차단 검증.
- `bootJar` 성공. 5173이 사용하는 로컬 백엔드 재시작 완료: 5173 HTTP 200, 8080 health/database UP. 재시작 전후 종료 확인 공고 27건의 전체 행 해시 및 개인 준비 행 해시 동일(현재 준비 행 0). 시작 후 오류 로그 없음. `.tools/qa/automatic-document-deadlines-20261110/runtime.json` 참조.
- 실제 저장된 11개 공고 원문 묶음을 읽기 전용 재생. 미검증 OCR/미지원 표/미완성 자료/여러 적용 범위 때문에 이 원문 묶음들을 새로 일괄 승인하지 않았음. 기존 명시적 검토 결과를 덮지 않음. 산출물 `.tools/qa/automatic-document-deadlines-20261110/replay.json`은 로컬 QA용.
- 명령: `backend/gradlew.bat -p backend test --tests '*AutomaticScheduleCompilerTest' --tests '*ConcurrentDocumentSubmissionTest' --tests '*AutomaticReviewEligibilityTest' --tests '*GuidanceReview*Test' --tests '*AutomaticReviewServiceCoverageTest' --tests '*AutomaticFollowupServiceTest' integrationTest --tests '*AutomaticPublicationIntegrationTest' --tests '*AutomaticFollowupPublicationIntegrationTest' --tests '*LocalAnalysisIntegrationTest' bootJar --console=plain`.

## 기여·한계

규칙 구현·검사·로컬 반영은 확인했지만 미래의 모든 자연어/스캔 문서를 자동 판독할 수 있다는 의미는 아니다. 출처나 적용 범위가 불명확하면 검수 상태를 유지한다. 아직 기간을 모르는 공고의 종료 여부는 원문을 읽기 전에 단정할 수 없다. 운영 서버 배포 및 미래 예약 수집의 실제 신규 공고 처리는 미관찰이다.

## 발표·포트폴리오 메모

단순 날짜 복사 대신 출처가 같은 동시 제출 관계를 모델링하고, 기한 경과를 저장 경계에서 다시 검사했다. 개인 기여 표기는 담당자 확인 후 사용한다.

## 후속 작업

- 신규 수집 로그에서 미지원 표·문장 사례가 발견되면 원문/적용 범위 증거와 회귀 사례를 추가한다.
- 배포 시 동일 종료 제외 정책을 운영 환경에도 적용하고 신규 예약 수집 결과를 확인한다.
