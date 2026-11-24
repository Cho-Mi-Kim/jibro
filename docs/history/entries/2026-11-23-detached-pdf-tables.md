# 북구 PDF의 떨어진 표와 자동 맞춤 질문 연결

- 기록일: 2026-11-23 (Asia/Seoul)
- 기록 상태: 검증 완료·팀 담당자 검토 전
- 작업 상태: 완료
- 유형: 개발 / 문제 해결
- 담당·검토: Codex 구현 분담 및 독립 읽기 전용 리뷰. 팀 담당자 검토 전.
- 관련 이슈·PR·커밋: 새 등록 없음. 작업 기준 review/local-updates-20261115 / eb64b60bab5f8274129689f7d80f59af4b001b8e. 시작 dirty847의 선행 변경을 보존함.

## 배경·문제

북구 공고의 PDF6쪽에서 왼쪽 신청 순위 상자와 오른쪽 접수 일정표가 떨어져 있어 자동으로 같은 행을 연결하지 못했다. 기존 수동 검수 안내는 정상 공개되어 있었지만, 이를 새 공고의 자동 판독·질문 생성 성공으로 간주할 수 없었다. 사용자는 앞으로 수집되는 공고에도 적용하되 접수종료 공고를 제외하고, 내 준비·일회성 확인에 같은 결과를 제공하도록 요청했다.

## 고려한 선택지

가까운 표를 무조건 합치거나 빈 행·열을 삭제하는 방식은 서로 다른 순위와 시간, 입력칸을 잃을 수 있어 채택하지 않았다. 실제 PDF 좌표와 경계 관계를 추출 책임에서 보존하고, 의미와 반대 원문 대조는 기존 검수 책임에서 수행하기로 했다. 전체 한글/PDF 대조를 일정 일부의 일치로 대신하거나 공고 ID별 예외를 추가하지 않았다.

## 결정·이유

물리 연결은 PdfPageLayout/PdfLayoutTextStripper, 구조 읽기는 DocumentContextReader, 신청 순위·일정 연결은 ApplicationTableReader, 원문 동치는 DocumentVariantComparison이 소유한다. 검수된 후보를 기존 GuidanceReviewCompiler→ApplicantSituationCompiler→공통 준비 정책으로 전달한다. 화면별 또는 HWPX 전용 경로를 새로 만들지 않는다.

## 변경·해결 과정

- 같은 행 경계, 닫힌 표, 짝의 유일성과 사이의 빈 공간을 확인한 두 PDF 표에만 행 연결을 부여한다. 중간 본문·그림·획이 있으면 연결하지 않는다. 별도 순위 머리글을 확인한 뒤 해당 행의 접수에 연결하며 열람·발표 시각은 접수로 가져오지 않는다.
- 전체 원문 대조 중 드러난 PDF5쪽 절차 상자와 화살표, 15쪽 신청서 점선/끝점, 16~17쪽 동의 상자를 기존 좌표 판독에서 보완했다. 한글의 가운데 제목 상자·번호 제목·순서가 명시된 절차 흐름은 모든 내용과 중첩 표를 보존해 비교한다.
- 독립 리뷰에서 일반 빈 행/열 수축이 동의 입력칸 유실도 동치로 처리하는 반례를 확인했다. 해당 확대를 제거하고 실제 이중선 및 원문의 명시된 경계 속성을 소비하도록 보완했다. 지적을 단순 예외 목록으로 막지 않는다.
- 북구 실제 성년/미성년 예외 문단을 첫 상황과 후속 질문으로 읽는다. 같은 등본, 법정대리 동의, 외국인 부모 대리, 행방불명 신고접수증의 범위는 원문에 있는 경우만 연결한다. 성년에게 미성년 전용 질문을 요구하지 않는다.
- 별도 주택목록 PDF의 채움 사각형 격자를 실제 폭·색·접합으로 읽었다. 2쪽의 29호·20열을 보존하고 모든 셀·표 밖 본문·총쪽수를 확인해 자격 본문과 역할을 구분한다. 금칙어가 없다는 이유만으로 목록으로 승인하는 경로는 제거했다. 출력된 #### 금액은 복원하거나 추정하지 않는다.
- 번호와 분리된 입주자격 제목을 읽어 가족·연령·순위 구간이 누락되지 않도록 했다. 원문 완전성 검사도 구조 변환 전에 수행한다.
- 성공적으로 읽은 서류 행의 중복 일반 이름 질문을 제거했다. 다른 제출 대상의 사본이나 별도 추가 증빙은 유지한다. 발급 형식/기존 항목 예시는 원문 설명에 보존하고, 실제 장 경계를 읽어 세대 정의표를 새 서류로 오인하지 않는다. 일반 전역 동명 제목 삭제와 임시 범위 제목 특례는 제거했다.
- 미제출 시 접수가 거부된다는 두 경고 문장을 면제로 오인하던 분류를 바로잡았다. 산곡 5원문을 다시 생성해 서류17개·입력 질문 계약·검수1개의 내용이 동일하고 참고 구간만 추가되는 것을 확인한 뒤 소비자 fixture를 갱신했다.
- 기존 수동 안내, 개인 답변·메모·보관 기록을 보호한다. 정책 변경만으로 기존 안내를 덮어쓰지 않는다. 신규 수집은 기존 작업 프로세스에서 처리하고 접수종료 제외 경계를 유지한다.

## 검증·결과

2026-11-23 03:54 KST 공식 상세·HWPX·17쪽 PDF·2쪽 주택목록을 기존 추출 서비스로 다시 받았다. 전체 PDF/HWPX 동치와 빈 질문·서류 원고의 자동 안내 생성이 모두 성공했다. PDF6쪽의 순위별 접수 4일을 10:00~16:00으로 연결했고, 전체 시작 11/23 10:00 및 마감 12/15 16:00과 일치했다. 공고 전체와 기존 개인 준비·이력은 전후 같았다. 최종 포맷 후 같은 새 공식 원문을 재생해 ready=true/issues=[], 질문52개·서류18개·일정8개를 확인했다. 순위별 결과 발표도 1순위(우선) 2026-12-22 17:00 이후, 1순위(일반)·2순위 2027-01-27 17:00 이후로 각각 보존했다. 수동검수 v8 원고를 자동생성 입력으로 사용하지 않았다.

관련 단위324개·PostgreSQL통합18개, 프런트 전체837개 전부 통과. 백엔드 스킵0. 프런트 전체 CI의 서식·린트·계층 경계·검수조건·타입·빌드도 통과했다. 실제4source 재판독→빈원고 생성→저장형 서비스/비로그인HTTP의4상황에서 질문·서류필요도·UNKNOWN·no-store·개인기록보존을 대조했다. 접수종료 큐/direct게시 및 추출한 과거기간의 게시 거절도 통과했다. 테스트DB의 활성 날짜/samplePublish는 소비자 준비이며 자동게시 경계는 별도 통합검사로 구분했다.

핵심 변이는 ※문단 인식, rowCompanion 소비, 이중선 폭/원문 증명 검사를 각각 제거해 새 검사가 실패함을 확인하고 소스 원복 후 최종 검사를 통과했다. 실패했던 inline 합성배치는 실제 문자열 폭에 맞춰 바로잡았고, 두 원문이 동일해 동치 병합되던 목록 부정 시험은 서로 다른 지역 자격으로 실제 보호 경계를 확인하도록 수정했다. 기준 완화/skip은 하지 않았다.

최초 변경27개 및 R7 변경12개 Java 파일(중복 범위 포함)의 실제 IntelliJ 서식 검사, bootJar, 변경 범위 git diff --check 통과. 새 실행 파일을 로컬5173에 반영·재시작했고 health 및 DB UP, 실제 북구 v8 안내 유지, 익명4상황 응답을 확인했다. 기존 개인 준비·메모·이력은 바꾸지 않았다. 운영 서버 배포/새 Git push/실제 기기 시각 QA는 이번에 수행하지 않았다.

## 기여·한계

코드·원문 대조·시험은 Codex가 수행했다. 독립 리뷰는 구현에 참여하지 않은 별도 에이전트가 수행했다. 빈 입력칸 손실, 목록 분류, 중첩 안내상자, 특정 대상 방문 안내, 추가 증빙, 명시 예시의 범위 지적 R1~R7을 모두 수정 수락했고 미처분 코드 지적은 없다. 로컬 QA independent-review.md에 처분을 기록했다. 실제 Iron Laws와 GPT Pro 리뷰 도구는 제공되지 않아 실행했다고 주장하지 않는다. 미지원 구조를 임의 판정하지 않으며, 새 공고에서 내용 충돌·판독 실패는 기존 확인 필요/보류 경계를 유지한다. 운영 서버 배포·Git push는 이번 범위에 포함하지 않는다.

## 발표·포트폴리오 메모

물리적으로 떨어진 표의 관계를 추출 단계에서 보존하고, 같은 내용을 다른 형식의 공식 원문으로 대조한 뒤 질문·서류 소비자까지 연결한다. 검증된 수동 안내와 신규 자동 생성 결과를 구분해 평가한다. 단위·통합·실제원문·로컬실행 결과를 구분해 재사용하며, 팀 담당자 검토 여부는 별도로 확인한다.

## 후속 작업

- 이번 요청의 미완료 작업 없음. 지원하지 않는 표/원문 불일치는 기존 검토 상태로 유지한다. 별도 테스트·진단 도구는 .tools/qa에만 두며 제품 진입점이나 예약 작업으로 설치하지 않았다.
- 운영 배포와 Git 업로드는 별도 요청 시 진행한다.

## 변경 파일

- backend/src/main/java/org/jibro/collection/extraction/PdfPageLayout.java
- backend/src/main/java/org/jibro/collection/extraction/PdfLayoutTextStripper.java
- backend/src/main/java/org/jibro/collection/extraction/HwpxStyles.java
- backend/src/main/java/org/jibro/collection/review/local/DocumentContextReader.java
- backend/src/main/java/org/jibro/collection/review/DocumentVariantComparison.java
- backend/src/main/java/org/jibro/collection/review/ApplicationTableReader.java
- backend/src/main/java/org/jibro/collection/review/ApplicationPeriodCompiler.java
- backend/src/main/java/org/jibro/collection/review/ApplicantSituationCompiler.java
- backend/src/main/java/org/jibro/collection/review/GuidanceReviewCompiler.java
- backend/src/main/java/org/jibro/collection/review/SourceCoveragePolicy.java
- backend/src/main/java/org/jibro/collection/review/OfficialListingMetadata.java
- backend/src/main/java/org/jibro/collection/review/OfficialGuidanceRows.java
- backend/src/main/java/org/jibro/collection/review/EvidenceGuidanceCompiler.java
- backend/src/main/java/org/jibro/collection/review/PairedPreparationItems.java
- backend/src/main/java/org/jibro/collection/review/VerifiedReissueFiles.java
- frontend/src/app/fixtures/applicant-generated-sangok.json
- 관련 추출·검수·준비 테스트 및 공식 원문 fixture
- docs/contracts/automatic-review.md

## 최종 대조 추가 발견 R7 및 해결

검사 통과 후 원문 전체 일정을 다시 대조하면서 일반1순위·2순위의 결과발표 2027-01-27 17시 이후가 자동후보에서 빠진 것을 확인했다. 우선1순위 12/22와 구분해야 한다. 기존 검수 v8은 유지됐으나 새 자동처리의 완료 판정을 보류하고 이전 실행본으로 잠시 복구했다. 동일 일정 생산 책임에서 대상별 결과와 시각 한정을 보존하고 최종 재검증 후 새 실행본을 다시 반영했다. 이 과정에서 임시 복구해 둔 이전 실행본은 더 이상 실행 중이지 않다.


R7은 ApplicationTableReader→ScheduleSourceReader→AutomaticScheduleCompiler에서 결과 열과 원래 대상 범위를 전달한다. 결과 전용 범위는 접수 집계에 포함하지 않고 같은 원문의 같은 대상 접수 마감과 발표 순서를 대조한다. 미정·확인 중·날짜가 포함된 상태 문장은 해당 결과의 검수로 남기고, 불가능한 날짜·시각·모호한 숫자는 기존 게시 차단을 유지한다. 최종 검사에서 발견한 이 차단 회귀를 원인 소유자에서 복구했고 기존 시험 기대값은 낮추지 않았다. 도달 불가 중복 분기는 삭제했다.

공통 화면의 scheduleMilestone은 서로 다른 날짜·시각·대상 결과를 ‘대상별 안내’로 전달한다. 기존 ScheduleInfoDialog로 각각의 날짜·설명·근거를 표시하고 비교·타임라인도 같은 결과를 사용한다. 5173 실제 북구 공개 v8에서 390px·360px 브라우저 폭으로 두 발표와 6쪽 근거를 확인했다. 가로 넘침이 없고 닫기 터치 영역은44px였다. 임시 화면 폭은 원복했다. 이는 실제 휴대전화나 글자 확대 검증을 대신하지 않는다.

R7 결과 열 인식을 고의로 끊자 실제 PDF 생산 경계 검사가 실패했으며 파일을 원복한 뒤 일정 검사를 재통과했다. 프런트 대상별 판정을 끈 경우에도 두 실제 공유 화면 검사가 실패했고 원복 후 전체 CI가 통과했다. 정책은 source-review-v19로 갱신했다. 최종4source 재생 및 local5173 네 익명 상황 결과는 .tools/qa/detached-tables-20261123의 actual-result.json, test-summary.json, live-verification.json, mobile-verification.json에 보관한다. 새 자동 후보는 기존 검수 v8에 덮어쓰지 않았으며, 저장형·일회성 새 후보 소비는 테스트DB 통합으로 확인했다.

R7 관련 파일: ScheduleSourceReader.java, AutomaticScheduleCompiler.java, ScheduleCandidates.java, ConcurrentDocumentSubmission.java, IntakeChannelDates.java, AutomaticRankedScheduleTest.java, schedules.ts, noticeDetailDates.ts, ScheduleCard.tsx, scheduleTimelineModel.ts, comparisonModel.ts 및 관련 시험. 새 데이터베이스 계약·제품 진입점·별도 예약작업은 추가하지 않았다.
