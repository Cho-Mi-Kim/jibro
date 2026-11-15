# 익산한스빌 유형별 준비 안내 초안과 출처 검토 보완

- 기록일: 2026-11-08 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 검수 보조·구조 추출 개선 구현 및 검사 완료 / 실제 18건의 준비 안내 공개는 보류 유지
- 유형: 개발 / 문제 해결 / 검수
- 담당·검토: Codex 보조 구현·원문 대조·검사, 팀원 검토 미확인
- 관련 이슈·PR·커밋: 미등록. 작업 기준 `feature/launch-integrations-20261107` / `e288a99`, 기존 미커밋 변경 보존. 커밋·push·배포 없음.

## 배경·문제

사용자는 과거 크롤링 방식에서는 동작하던 준비 안내를 현재 코드 규칙에 맞게 보완하도록 요청했다. 이전 [공개 보류 18건 검토](2026-11-08-public18-review-and-notice-sort.md)에서 원문 읽기 성공과 안내 규칙 검증을 구분했으며, 문서 전체 문자열 비교와 원문 조각별 질문 생성이 과도한 보류와 중복 질문을 만들고 있었다.

이번에는 읽기 가능한 익산한스빌 국민임대 자격완화 공고를 우선 대조했다. 정확한 공고 ID는 `01c79a68-cc52-4c44-bec7-c142130c57af`이며, 이전 작업 요약의 ID를 그대로 사용하지 않고 실제 제목·공식 주소·저장 자료를 다시 확인했다. 과거 프로젝트의 코드는 재사용하지 않았다.

## 고려한 선택지

- 질문 수 제한만 늘리는 방식: 세대·예외·서류 조건의 중복과 적용 범위가 해결되지 않아 채택하지 않았다.
- PDF 우선으로 HWPX·다른 첨부를 무시하거나 단어·숫자 집합이 같으면 동등하다고 보는 방식: 표의 대상·열·부정·예외 관계가 바뀌어도 통과할 수 있어 채택하지 않았다.
- 일부 API 검토의 충돌 없음 결과를 전체 안내 승인으로 사용하는 방식: 전달한 부분 문맥 밖의 자료와 조건을 보증할 수 없어 채택하지 않았다.
- 유형별로 정리된 검토 초안과 공개 판정을 분리하는 방식: 확인한 정보를 검토자가 읽을 수 있게 만들면서도 실제 모순·미확인 조건을 숨기지 않으므로 채택했다.

## 결정·이유

기존 공개 계약을 유지한다. 후보 질문과 서류를 생성할 수 있다는 사실은 준비 안내 PASS나 신규 개인 준비 시작 허용을 뜻하지 않는다. 현재 구조로 충분히 검증되지 않은 규칙·공식 문서의 불일치가 남으면 HOLD를 유지한다.

소득·총자산 배제와 자동차 기준 유지, 최초 모집과 재계약, 공통 서류와 대리 신청 방식의 선택 서류를 구분한다. 같은 공고의 정보를 의미 단위로 묶되, 해석하지 못한 내용을 임의로 생략하고 완전한 안내라고 표시하지 않는다.

## 변경·해결 과정

### 원문 구조와 출처 비교

- `HwpxStructuredText`를 분리하여 표 번호, 행·열 주소, 병합 범위, 중첩 표와 각주·미주·머리말·꼬리말 경계를 보존한다. 없는 좌표는 `unknown`으로 남기고 빈 셀을 추정해서 채우지 않는다.
- 외부 XML·DTD, 과도한 깊이·셀 수·출력 크기, 표 범위를 벗어나거나 겹치는 셀, 문서가 구조 표식을 위조하는 경우를 제한한다. 기존 독립 worker와 시간·메모리 한도는 유지한다.
- 로컬 규칙 해석에서 구조 경계를 이용하고 구조 표식을 원문 자격 문장으로 해석하지 않도록 보완했다. 형식 정보가 생겼다는 이유로 병합 표 전체의 의미 해석 완료를 선언하지 않는다.
- `SourceCoveragePolicy`가 확인된 목록 메타데이터와 신청 자격 본문을 구분하고, 일정 출처는 계속 대조하도록 했다. 모르는 본문·읽기 실패·정정 자료는 제외하지 않는다.
- `DocumentVariantComparison`은 공백·유니코드 조합·독립 쪽 번호 등 의미를 바꾸지 않는 차이를 정리하고, 완전하고 유일한 실제 쪽 번호가 있을 때만 수집 순서를 복원한다. 임의 문단 재정렬이나 숫자 집합 비교는 하지 않는다. 실제 익산 PDF/HWPX 전체 문맥의 동등성은 여전히 미확인이다.

### 유형별 검토 초안

- `NationalRentalSections`와 `NationalRentalGuidancePreview`가 지원하는 국민임대 자격완화 원문에서 출처가 연결된 질문 14개와 서류 19개 후보를 구성한다. 익산 ID나 공고 날짜로 처리 여부를 하드코딩하지 않는다.
- 소득·총자산 한도 숫자 질문을 만들지 않고 배제 사실을 안내한다. 자동차는 세대 전체, 최고 개별 비영업용 승용차 가액, 제외 차량과 보조금 차감을 구분한다.
- 미성년 예외, 세대 범위, 분리 배우자·예비신혼, 주택 특례, 불법전대 제한, 현장 대리 방식 등의 질문과 서류 연결을 나눴다. 미입력·모름과 추가 확인 선택을 완료나 불필요로 바꾸지 않는다.
- 인감 대리와 본인서명 대리 중 선택한 서류 묶음만 연결한다. 계약·재계약 서류를 최초 접수 체크리스트에 일괄 추가하지 않는다. 임신·입양 관련 서류처럼 이번 완화 모집의 적용 관계가 남은 항목은 별도 검토 사유로 표시한다.
- `OfficialFormConsistency`가 본문 모집공고일과 첨부 서식이 명시적으로 지칭한 공고일을 비교한다. 일반적인 과거 법령 날짜나 예시 날짜를 현재 공고일로 취급하지 않는다.
- `NationalRentalScopeReview`가 소득 배제와 소득가구원용 서류, 선착순 선정과 가산점용 서류의 적용 관계를 검토 항목으로 남긴다. 다른 행의 서류 면제 문구로 해당 항목의 미확인을 지우지 않는다.

### 관리자 미리보기와 공개 처리 분리

- `GuidanceReviewCompiler`를 자동 검수와 관리자 미리보기의 공통 평가 경로로 사용한다.
- 관리자 전용 `GET /api/v1/admin/reviews/{noticeId}/extractions/{extractionId}/guidance-preview`와 `GuidanceReviewPreview` 화면을 추가했다. 후보 조건·서류·일정과 보류 이유를 출처 링크와 함께 읽을 수 있다.
- 이 GET은 공개 원고, 검수 결과, 개인 준비 기록을 수정하지 않고 외부 모델을 호출하지 않는다. 다른 공고의 발췌, 변경된 revision, 더 최신 발췌, 읽는 도중 교체된 파일을 현재 미리보기로 제공하지 않는다.
- 자동 검수·로컬 분석 정책 식별자를 변경하여 이전 결과와 새로운 해석 결과를 구분한다. 기존 외부 API 예산·캐시·수동 원고 보호는 유지한다.

## 검증·결과

### 실제 공식 자료 대조

- 익산한스빌 공식 PDF와 HWPX를 다시 내려받아 저장된 SHA-256과 일치함을 확인했다. PDF 1·2·11·12·32쪽의 표·서식은 렌더하여 육안 대조했다.
- 본문·공식 상세의 모집공고일은 `2026.11.04`인데 PDF 32쪽과 HWPX의 확약서는 `26.10.08` 공고에 따른 입주자라고 명시한다. 양쪽 파일에 같은 불일치가 있어 단순 추출 순서 문제나 PDF/HWPX 간 내용 차이로 설명하지 않았다. 오타로 단정하거나 날짜를 자동 수정하지 않았다.
- 이 실제 날짜 불일치 1종과, 소득 배제/임신·입양 서류 및 선착순/가산점 서류의 적용 범위 미확인 2종을 구분한다. 최종 공통 검사에서는 PDF·HWPX 각각의 출처를 보존하여 총 3종·6개 근거 행을 반환한다. 이 3종을 모두 확정된 자격 조건 충돌이라고 표현하지 않는다.
- 같은 SHA의 익산 HWPX를 새 파서로 재추출하고 새 정책의 로컬 분석을 저장했다. 단순 검사 성공만 기록한 것이 아니라 실제 자료의 재추출 결과를 사용했다.
- 기존 지정 18건 최종 재평가 결과 시각: **2026-11-08 01:35:03 KST**. 18건 모두 안내 HOLD 유지, 후보 안내는 익산 1건에서 질문 14개·서류 19개를 확인하고 `validateContent`를 통과했다. 다른 공고와 문서 형식의 의미 동등성·누락 자료·일정 등 보류 항목은 해소됐다고 표시하지 않았다.
- 로컬 상세 근거: `.tools/qa/iksan-audit/audit.md`, `.tools/qa/original18-review-v4-results.json`. 이 파일들은 개발 환경의 검증 산출물이며 운영 배포로 간주하지 않는다. 별도 읽기 전용 보고서는 `frontend/public/review-iksan-20261108.html`이다.

### 자동 검사

- 최종 `gradlew test integrationTest bootJar`: 단위 48클래스 290개, PostgreSQL 통합 35클래스 181개, 합계 471개에서 실패·오류·생략 0. 전체 실행 당시 JUnit XML을 확인했다.
- 전체 실행 이후 HWPX 소비 경계의 추가 회귀 사례를 포함한 `LocalRuleInterpreterHwpxTest` 8개를 별도 재실행하여 통과했고 bootJar를 확인했다. 이 부분 실행으로 교체된 XML 수를 전체 실행 수와 혼동하거나 검사 횟수를 합산하지 않는다.
- 새 관리자 미리보기 통합 6개는 비인증 401·일반회원 403, 다른 공고/없는 발췌 404, revision·최신 발췌·파일 교체 409, 실제 후보 내용, 반복 GET 전후 공고 전체 행·공개 버전·수동/자동 검수 이력·로컬 분석·Gemini 시도/캐시 불변을 검증했다.
- 프런트 관련 검사 15개, typecheck·lint·모듈 경계·build 및 변경 파일 Prettier 검사 통과. 보고서는 실제 브라우저 393×852/360×800에서 가로 넘침 없음과 펼침 동작을 확인하고 화면을 확인했다. 실제 모바일 기기 확인이나 관리자 화면 전체 여정·전체 프런트 테스트 완료로 확대 해석하지 않는다. 기존 500kB 번들 경고는 유지된다.

### 최종 교차 검토에서 보완한 판정 경로

- 검증된 EMPTY 목록 메타데이터의 모집공고일을 건너뛰어 HWPX 단독 확약서의 날짜 차이를 놓치는 경로를 재현했다. 확인된 목록 필드만 날짜 근거에 포함하고 실패·미확인·그 밖의 EMPTY는 제외했다.
- 특정 PDF 유형 초안 밖에서 서류 적용범위 검사가 승인 판정에 누락되는 문제를 모든 출처의 공통 finding 수집으로 수정했다. 로컬 분석과 공개 검수가 같은 사유로 보류한다.
- 대체 해석 경로가 기존 규칙 모순을 지우지 않게 했다. 발표가 접수 마감보다 이른 명확한 일정은 최종 공통 검사로 보류하며 순위별 접수·조기 마감은 범위를 추정하지 않는다. 서류 제출 후 최종 결과 발표처럼 정상적인 순서는 막지 않는다.
- 새 긍정 회귀 테스트에서 `서류 마감`을 상위 자료 판독기가 누락한 것을 발견해 명시된 축약 표현도 읽도록 연결했다. 기대 항목 수를 낮춰 검사를 통과시키지 않았다.
- 공개 감사 정책 `official-rules-v4`, 로컬 파생 정책 `source-review-v4`로 이전 결과와 구분한다. 최종 검사 이전에 발견한 HOLD 날짜 전달과 긴 정책 키의 DB 길이 오류도 수정했다.
- 실제 DB에서 자동 안내 PASS 감사 이력 없이 남아 있던 안내 PASS 1건은 기존 수동 원고이고 `automatic_review_blocked=true`임을 확인했다. 새 자동 승인으로 표시하거나 수정하지 않았다.
- 최종 로컬 서버: PID67368, `.tools/runtime/backend-typed-review-v5.jar`, 5173의 health/database 모두 UP. 5174·5184는 변경하지 않았다. 보고서 정적 검사(질문14·서류19·일정3·확인3종·나머지17건, 공식 LH HTTPS 링크, 저장·입력 기능 없음) 통과. 배치·미리보기는 Gemini 호출 없이 수행했으며 기존 상시 서버 전체의 외부 호출을 중지했다는 뜻은 아니다.

## 기여·한계

Codex가 구현, 공식 자료 대조, 자동 검사와 보고서 작성을 보조했다. 팀원의 별도 검토와 기관 확인은 수행 여부가 확인되지 않았다.

이번 결과는 **검토 가능한 유형별 후보 생성과 보류 이유의 구체화**다. 실제 신규 준비 안내 공개는 0건이며 18건 전부 자동 검수 완료라고 표현하지 않는다. 후보 19개 서류는 모든 사용자에게 필수인 19개를 뜻하지 않는다. 사용자 답변에 따른 조건부 묶음과 검토가 필요한 항목을 포함한다.

PDF/HWPX 전체 문맥의 의미 대조, 지원 범위를 벗어난 다른 국민임대·행복주택·기관 모집 조건, 재계약과 특례의 모든 결합 규칙을 해결한 것은 아니다. 후보 미리보기는 전체 규칙 컴파일러를 우회하지 않으며, Gemini의 부분 검토로 이 제한을 해제하지 않는다. 실제 사용자 계정으로 새 준비 건을 저장하는 전체 여정, 기관 확인, 운영 배포는 이번 검증 완료 범위에 포함하지 않는다.

## 발표·포트폴리오 메모

문서 전체 불일치와 중복 질문으로 막히던 과정을 출처 구조 보존, 유형별 질문·서류 후보, 실제 불일치와 적용 범위 검토로 분리했다. 공식 자료에서 날짜 불일치를 직접 확인했고, 검토 화면 조회가 공개 상태나 외부 API 사용량을 바꾸지 않는 것을 검증했다. 모든 공고의 자격을 자동 판단하거나 준비 안내를 일괄 승인한 것으로 표현하지 않는다.

## 후속 작업

- 익산 확약서 공고일과 확인서/확약서의 실제 적용 관계를 공식 기관에 확인한다.
- 소득 배제 시 임신·입양 서류와 선착순 모집의 가산점 서류 적용 범위를 확인한다.
- PDF/HWPX 대응 표·문장의 대상, 값, 부정·예외 관계를 보존하는 비교를 추가 표본으로 검증한다.
- 확인된 유형별 규칙을 전체 컴파일·공개 요건과 연결한 뒤 격리된 계정에서 질문→답변→서류→저장·재조회·원문 변경 흐름을 검증한다.
- 원래 18건 외에 예약 수집된 공고는 별도 범위로 다룬다. 이번 결과를 전체 수집 건의 검수 완료로 전용하지 않는다.

## 변경 파일

- `backend/src/main/java/org/jibro/collection/extraction/HwpxStructuredText.java`
- `backend/src/main/java/org/jibro/collection/extraction/DocumentTextParser.java`
- `backend/src/main/java/org/jibro/collection/review/SourceCoveragePolicy.java`
- `backend/src/main/java/org/jibro/collection/review/DocumentVariantComparison.java`
- `backend/src/main/java/org/jibro/collection/review/NationalRentalSections.java`
- `backend/src/main/java/org/jibro/collection/review/NationalRentalGuidancePreview.java`
- `backend/src/main/java/org/jibro/collection/review/NationalRentalScopeReview.java`
- `backend/src/main/java/org/jibro/collection/review/OfficialFormConsistency.java`
- `backend/src/main/java/org/jibro/collection/review/GuidanceFinding.java`
- `backend/src/main/java/org/jibro/collection/review/GuidanceReviewCompiler.java`
- `backend/src/main/java/org/jibro/collection/review/GuidanceReviewPreviewController.java`
- `backend/src/main/java/org/jibro/collection/review/AutomaticReviewService.java`
- `backend/src/main/java/org/jibro/collection/review/local/LocalRuleInterpreter.java`
- `backend/src/main/java/org/jibro/collection/review/local/LocalAnalysisService.java`
- `backend/src/main/java/org/jibro/notice/AutomaticReviewRepository.java`
- `backend/src/test/java/org/jibro/collection/review/GuidanceReviewPreviewIntegrationTest.java` 및 관련 단위·회귀 검사
- `backend/src/test/resources/review/iksan-waiver-20261104.json`
- `frontend/src/features/admin/GuidanceReviewPreview.tsx`, 관련 CSS·API·테스트 및 관리자 발췌 화면 연결
