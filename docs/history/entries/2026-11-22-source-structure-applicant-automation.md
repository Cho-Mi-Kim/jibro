# 그림·자동 번호·여러 쪽 표의 원문 대조와 상황별 질문 연결

- 기록일: 2026-11-22 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결 (지원 형식 판독·공통 연결 구현; 기존 보류 공고 전체 승인은 별도)
- 유형: 개발 / 문제 해결
- 담당·검토: Codex 원문 추출·대조·개인화 작업 분담, 별도 읽기 전용 검토자. 사용자·팀원 검토 미확인
- 관련 이슈·PR·커밋: 미등록. 이번 작업에서 commit/push/운영 배포 없음

## 배경·문제

그림 화살표와 HWPX 자동 번호가 텍스트에서 빠지고, PDF의 표가 여러 쪽으로 나뉘거나 셀 안에서 끊겨 같은 원문끼리도 대조가 실패했다. 실제 질문 생성 전에 표를 문장으로 바꿔 HWPX와 PDF를 다르게 비교하는 순서 문제도 있었다. 첫 신청 상황에 맞는 질문과 서류를 내 준비·일회성 확인에 같은 기준으로 전달하려는 요청이다.

## 고려한 선택지

이미 검수한 공고별 예외·원문 ID로 승인하는 방법과, 단어·숫자 집합만 맞으면 같은 문서로 보는 방법은 사용하지 않았다. 기존 추출·문서 비교 책임에서 실제 배치와 표 관계를 보존하는 방법을 선택했다. 알 수 없는 그림을 화살표로 가정하거나, 여러 자료 중 읽기 쉬운 한 파일만으로 전체 공고를 승인하지 않는다.

## 결정·이유

원문 생산자는 실제 번호·그림 방향·셀 경계·쪽 순서를 보존한다. SourceCoveragePolicy/DocumentVariantComparison은 원형으로 전체 대조하고, 선택된 자료만 기존 OfficialGuidanceRows/GuidanceReviewCompiler를 통해 질문으로 만든다. ApplicantSituationCompiler와 기존 공통 평가 정책을 두 화면이 함께 소비한다. 별도 양식은 VerifiedPreparationForms/OfficialFormConsistency가 기존 서류의 작성 방법·공식 양식 링크에 연결한다.

## 변경·해결 과정

- HWPX ZIP 열거 순서를 manifest/spine 순서로 교체. HwpxParagraphStyles를 HwpxStyles로 교체하여 명시 번호 시작·수준·형식과 셀 테두리를 함께 읽는다. worker 포함 목록도 교체했다. 편집 메모는 공식 조건에서 구분하며 각주·미주는 보존한다.
- RasterArrow의 단일 판독 모델에서 실제 잉크 범위·회색 채움·윤곽을 확인한다. 별도 solidRight 임시 경로는 삭제했다. JPEG overshoot를 글자로 오인하던 채움 비교를 실제 중앙 채움으로 수정했으며 임계값을 확대하지 않았다. 문자·잘린 머리·변형된 그림은 자동 방향으로 버리지 않는다.
- PdfPageLayout은 실제 clip, 열린 외곽/이중선/짧은 점선의 획과 접합을 읽는다. 끊긴 점선은 부분 선으로 유지한다. 전체 리소스 폭 대신 판독한 잉크 범위를 페이지에 투영한다.
- DocumentVariantComparison은 반대쪽 완전한 표를 증인으로 물리적 연속 쪽·행 내부 분할·병합 열·빈 여백 행을 대조한다. 장식 표는 분할표 검증 전에 평탄화하지 않는다. 숫자·예외·방향·모든 셀 값은 유지한다.
- 원형 전체 대조보다 질문용 투영이 먼저 실행되던 경로를 제거했다. 후속 공식 자료 비교도 원형 검수와 명시 항목 투영의 책임을 구분한다.
- 별도 양식의 지침은 서류 method/issueUrl에 직접 연결하고 중복 ACK 질문 경로를 삭제했다. 양식 번호로 기존 제출 조건을 지우는 두 번째 공통 승격 경로도 삭제했다. 원문 행 파서가 공통 제출 범위의 유일한 소유자다.

## 검증·결과

실제 HWPX173·PDF140 원형을 재추출하고 산곡 main 전체 HWPX/PDF 및 다자녀 main 전체 쌍의 동일성을 확인했다. byte 미보유 PDF2, 실제 OLE/HWP1과 과거 실패 자료를 보존했다. 같은 입력106건의 전체 재생 READY0, 문서대조 미확정100건, 확인 설명 후보43건760항목이다. 미종료70건(접수중13/예정55/시작미상2)은 READY0·문서대조 미확정64·설명후보30건558항목이다. 사유는 중복되며 설명후보를 개인화 질문·새 게시 수로 계산하지 않는다. 종료·제외36건은 읽기 통계만 포함하고 행동 대상으로 삼지 않았다.

산곡의 전체7자료는 공식 상세의 실질 재서술 한 사유가 남으며 자동승인하지 않는다. 별도로 전체 대조된6첨부를 실제 BoundedDocumentParser worker에서 읽어 검수후보→별도 testDB 저장형 유형전환→익명 일회성 MVC까지 연결했다. 예비신혼의 해당 질문/서류 전환·공통 가족관계증명서 REQUIRED·두 경로 동일·개인기록 불변·no-store 검사가 통과했다. 이전 전체7 READY 기대는 원문을 확인하기 전에 세운 잘못된 기대였으므로 실제 보류 계약으로 수정했고, 실제 판독/투영의 실패는 기대값을 낮추지 않고 원인 수정했다.

PDF13→14쪽의 공통 서류 첫 데이터행이 머리글로 오인되는 결함은 실제 인접 쪽·첫/마지막 표·열 구조·필수번호 연속성으로 수정했다. 같은 서식의 보호자 서명 연령/경계/부정과 미지원 어미·불릿 없는 지침도 확인 필요 및 원문 안내에 보존한다. 기존 생성 fixture는44질문·17서류·검수1을 유지한다. 화면 전체15검사, 관련 타입·린트 통과. 최종 Java 단위163개·통합8개가 실패/건너뛰기 없이 통과했고 bootJar 생성도 성공했다(`final-tests-build.log`, `final-test-counts.json`). 변경 Java37개 IntelliJ 서식 검사를 통과했으며, 추가로 발견한 테스트 문자열 공백/줄끝은 의미가 같은 명시 줄바꿈으로 정리하고 해당 파일 서식 검사도 다시 통과했다. `git diff --check`는 변경 범위 오류0이다. Gradle의 기존 deprecated 기능 안내는 남아 있으며 검사 실패와 구분한다.

검증된 jar를 `.tools/runtime/backend-service-scope.jar`에 반영해 `Restart-Local5173.ps1 -NoBrowser`로 재시작했다. 실제5173 HTML200·공고목록 API200·health UP/database UP 확인(`runtime-check.json`). 브라우저/실기기 새 QA나 운영 AWS 배포는 수행하지 않았다. 실제 화면 상호작용 검증은 React 소비자 검사15개이고 실기기 확인과 구분한다.

## 기여·한계

산곡의 공식 상세 게시글은 첨부를 그대로 복사한 문단만 있는 것이 아니라 모집대상·중복신청 등을 다르게 서술한다. 단순 부분 문자열·유사도로 동의어라고 승인하지 않으며 남은 실질 대조 사유를 유지한다. 회색 무문자 화살표 지원은 모든 그림·차트의 의미 판독을 뜻하지 않는다. 이번 작업의 원문 재생은 보관한 공식 바이트를 읽은 것이며 전체 공식 사이트를 새로 수집했다는 뜻이 아니다. 기존 개인 기록·기존 수동 승인·종료 공고 게시 상태는 변경하지 않았다.

Iron Laws 및 실제 GPT Pro 실행 도구는 제공되지 않아 실행하지 않았다. 독립 읽기 전용 검토에서 발견한 문자 그림 유실·이미지 위치·부분 유형·제출 면제·서식 앞 미해석 문단·반복 지침 유실·부분 문자열 대조 문제는 소유 책임에서 수정했다. 최신 원형/투영 순서·서명 문장 누락과 미지원 어미·공통서류 쪽 경계까지 독립 검토자가 다시 확인했고 미처분 확정 지적은 없다. 원인 제거 변이(번호·crop/NONE·그림 위치·점선·행 분할·서식 지침 연결)는 실패를 검출하고 바이트 원복 후 통과했다.

## 발표·포트폴리오 메모

원문을 읽는 것과 자격·서류를 승인하는 것을 구분하고 실제 원형→생산자→공통 소비자를 연결해 검사했다. 개인·팀 기여는 담당자 검토 후 사용한다.

## 후속 작업

- 전체 게시글의 재서술과 자유 형식 기준 대조는 보류 사유별로 별도 검증한다.
- 실제 배포 환경 적용은 이번 5173 반영과 구분한다.

## 변경 파일

- backend/src/main/java/org/jibro/collection/extraction/{HwpxPackageReader,HwpxStructuredText,HwpxStyles,HwpxPicture,RasterArrow,PdfPageLayout,PdfLayoutTextStripper}.java
- backend/src/main/java/org/jibro/collection/review/{DocumentVariantComparison,SourceCoveragePolicy,GuidanceReviewCompiler,OfficialFollowupComparison,OfficialGuidanceRows,OfficialGuidanceItem,EvidenceGuidanceCompiler,ApplicantSituationCompiler,VerifiedPreparationForms,OfficialFormConsistency,OfficialListingMetadata,AgencyProxyForms}.java
- backend/src/main/java/org/jibro/collection/review/local/{DocumentContextReader,GuidanceTableProjection}.java
- backend/build.gradle 및 해당 추출·검수·내 준비 통합 검사, 실제 공식 원형/생산 결과 fixture
- frontend/src/app/fixtures/applicant-generated-sangok.json 및 applicantContextFlows.test.tsx
- docs/contracts/automatic-review.md

임시 진단 helper는 Git 제외 `.tools/qa/source-structure-automation-20261122`에만 있으며 제품의 두 번째 실행 경로가 아니다. 실제 회귀/공식 fixture가 재현 계약을 맡으며 제품 배포물에는 포함하지 않는다. 남은 공고의 원문 대조 재현과 검토 기록 이관이 끝나면 해당 일회성 helper와 디버그 클래스는 삭제한다.
