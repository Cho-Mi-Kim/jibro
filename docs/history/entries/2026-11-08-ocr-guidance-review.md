# 스캔 원문 오독 대조와 확인된 조건·서류 안내 제공

- 기록일: 2026-11-08 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결 — 로컬 반영·실행 검증 완료, 원문 자체 미확정 범위 유지
- 유형: 개발 / 문제 해결 / 의사결정
- 담당·검토: Codex 보조 구현·원문 대조·독립 검토. 팀 담당자 최종 검토 미확인
- 관련 이슈·PR·커밋: 이번 변경 커밋·push·PR·원격 배포 없음

## 배경·문제

사용자는 앞서 읽기 경로를 복구한 스캔 PDF·도면 18개에 남은 오독을 공식 원문과 대조하고, 확인된 조건과 서류를 준비 안내로 제공해 달라고 요청했다. 대상 첨부는 공고 17건에 속한다. OCR 결과를 읽을 수 있게 된 것과 원문에 맞는 준비 안내가 검토·게시된 것은 별개의 단계다.

일부 공고는 조건·서류를 확인할 수 있어도 공식 상세와 첨부의 신청 마감일이 서로 달랐다. 기존 명시적 원문 검토 경로도 시작·마감 두 날짜를 모두 요구하여, 날짜 하나의 미확정 때문에 확인된 준비 안내 전체가 막혔다.

## 고려한 선택지

- OCR 추출 성공만으로 전체 승인: 숫자·표 열 결합 오독과 실제 원문 충돌을 구분하지 못해 채택하지 않았다.
- 기간이 미확정인 공고 전체 보류: 확인된 조건·서류도 사용할 수 없어 사용자의 항목별 제공 요구와 맞지 않았다.
- 원문을 대조한 명시적 검토 원고를 적용하고 미확정 항목만 분리: 검토 범위·공식 근거·출처 무결성을 기록할 수 있어 채택했다.

## 결정·이유

확인된 사실만 기존 준비 질문·조건부 서류·일정 구조에 작성했다. 과거 공고에 현재 연도의 자격 기준을 적용하거나 유사한 공고의 누락 규칙을 가져오지 않았다. 해당자 서류는 답변 조건과 연결했고, 실제 출처 충돌은 해당 질문·서류 또는 일정의 `reviewIssues`로 남겼다. 전체 OCR 자동 승인 정책은 완화하지 않았다.

명시적 원문 검토의 `PARTIAL` 원고에 한해, 미확정 신청 경계를 `applicationPeriodIssueId`로 일정 이슈와 정확히 연결하도록 했다. 알려진 경계는 날짜가 일치하는 기본 일정이 필요하고, 모르는 경계에는 기본 일정을 만들 수 없다. 기존 원문 SHA·전체 상세 문맥·첨부 목록·revision·추출 지문·검토 유효시간 검사는 유지했다.

## 변경·해결 과정

1. 공고별 상세 HTML 전체, native HWP 및 관련 양식·목록을 읽고 스캔 PDF의 주요 조건·숫자·서류·일정 표를 이미지로 대조했다. 실제 읽기·시각 대조 범위는 [공고별 기록](../../reviews/2026-11-08-ocr-guidance-source-review.md)에 보존했다.
2. `ReviewedApplicationPeriod`에 명시적 부분 검토의 기간 정책을 분리했다. 원문 검토 패키지에 선택적 이슈 ID를 추가하고 `SourceReviewVerifier`와 `SourceReviewPublicationService`에 연결했다. 알려진 경계와 미확정 경계가 캘린더에 뒤섞이지 않도록 회귀 검사를 추가했다.
3. 최초 반영 중 11건은 `OFFICIAL_DETAIL_CHANGED`로 정상 차단되었다. 현재 파서가 접수처정보까지 읽도록 이전 작업에서 개선되어, 오래된 저장 문맥보다 접수처 주소·운영시간이 추가된 것이 원인이었다. 같은 최신 HTML을 보관된 이전 파서로 비교 재현하면 11건 모두 기존 문맥과 일치했다. 검사를 무시하지 않고 추가 영역까지 검토한 최신 스냅샷을 만들었다. 모든 첨부 바이트 SHA도 다시 확인했다.
4. 일반 증명서의 공고일 이후 발급 안내가 신분증에 확대되지 않도록 공주·화성 문구를 바로잡았다. 먼저 반영된 화성은 이전 공개 원고 지문을 확인하는 amend 경로로 수정했다. 별가람의 세대 범위도 동일 주민등록표 조건을 보존하도록 교차 검토했다.
5. 공식 HTML·첨부 전체 무결성을 다시 확인한 후 17건 모두 기존 명시적 원문 검토 서비스로 로컬에 반영했다. 4건은 PASS, 12건은 PARTIAL, 자격·서류 없는 김포마송 마감 안내문 1건은 UNSUPPORTED다. 전체 16건에서 질문 249개, 서류 템플릿 312개를 제공한다. 실제 미확정 이슈 20개는 확정 정보로 바꾸지 않았다.
6. 모바일에서 양쪽 날짜가 미확정일 때 `확인 필요–확인 필요`가 길어지는 현상을 확인했다. 접수기간 표시를 한 번의 확인 안내 또는 알려진 경계·미정 경계로 나누고 기존 3칸 레이아웃을 유지했다. 별도의 CSS 덧대기는 하지 않았다.
7. 청주·성남 정정 공고는 실제 양방향 근거가 있는데 30자리 공고 ID와 LH의 정적 GoCrc 기본/빈 분기를 지원하지 않아 연결이 막혔다. 해당 형식 전체와 공식 제출 경로만 좁게 허용하고, 동적 대입·다른 목적지·종류 불일치·단방향 근거 거부 검사를 추가했다. 최신 공식 원문으로 2쌍을 연결했고 내용·개인 기록은 보존했다. 내포는 역방향 원본 ID 근거가 없어 연결하지 않았다.
8. 독립 검토에서 미확정 경계를 `접수 종료` 같은 별칭이나 `확인 필요`가 붙은 일정으로 넣을 여지를 발견했다. 명시적 원문 검토 정책에서 이를 거부하고, 단지·순위별 확정 날짜와 서류·계약 절차는 유지하는 회귀를 추가했다. 현재 17개 패키지도 새 정책을 통과했다.

## 검증·결과

- 최종 `gradlew test integrationTest bootJar`: backend 단위 466개 및 별도 테스트 DB 통합 275개, 합계 741개 통과, 실패·오류·건너뜀 0. 실제 운영 DB를 테스트 초기화 대상으로 사용하지 않았다. 추가 테스트의 초기 기대값이 기존 허용 제목 `접수 개시`를 오인한 부분은 테스트 입력을 바로잡고 기존 허용 양성 사례도 확인했다.
- frontend 날짜·화면·캘린더 관련 4파일 28개 검사, 변경 파일 ESLint·Prettier, 타입 및 production build 통과. 이전 준비 snapshot에 있던 마감일을 최신 미확정 경계의 일정으로 되살리지 않는다. Vite의 기존 500kB 초과 청크 경고는 빌드를 실패시키지 않았으며 이번 작업에서 번들 분할을 바꾸지 않았다.
- 로컬 공개 API 17건을 패키지와 대조하여 안내 상태, 질문·서류 수, 이슈 연결, 날짜·시간, 확정 일정 제외를 확인했다.
- 기존 승인 공고 32건의 version·revision·내용 해시와 개인 준비 기록 비교값을 전후 대조해 동일함을 확인했다. 당시 개인 준비 기록은 0건이었다.
- 실제 브라우저 393×852 및 360×800에서 조건·서류 탭, 미확정 기간, 정정본 링크 이동을 확인했다. 360px 일정표의 가로 넘침 0, 콘솔 오류 0. 실제 휴대전화 테스트와 구분한다.
- 최신 서버 재시작 후 공개 API 17건과 정정 링크 2건(200)·미검증 내포 링크 없음(204)을 재확인했다. 실행 JAR SHA256은 `8ad0fdf3f6377db4833b78d2aaf57ad3c5bb2d4ef3a57ef1eb76928e285ab9b8`이다. 5173 프런트와 비교용 5174/5184는 교체하지 않았다.
- 최종 원고: [17건 패키지](../../reviews/2026-11-08-ocr-guidance-packages.json). 실행·보존 근거: [runtime JSON](../../reviews/2026-11-08-ocr-guidance-runtime.json).

## 기여·한계

AI 보조로 코드 수정, 공식 원문 비교, 원고 작성과 교차 검토, 로컬 실행 검증을 수행했다. 사람이 최종 검토했다고 표시하지 않는다. 모든 PDF 문자를 이미지로 전수 전사했다는 의미도 아니다. 공고별 실제 대조 페이지와 전체 native 텍스트 검토 범위를 기록했다.

12건의 20개 항목은 OCR 문제가 아니라 원문 자체의 날짜·계좌·기준 차이 또는 참조 자료 부재다. 임의 선택하지 않았으며 공식 정정·기관 확인이 필요하다. 과거 공고의 준비 안내 제공은 현재 접수 가능이나 신청자 자격 승인을 의미하지 않는다.

## 발표·포트폴리오 메모

읽기 복구와 검토·게시 단계를 분리하고, 전체 보류 대신 근거가 확인된 범위만 제공했다. 출처 변경 검사에 막힌 사례는 검증 완화 대신 파서 버전 차이와 추가 문맥을 대조하여 해소했다. 담당자 확인 전 개인 성과로 귀속하거나 사용자 효과를 수치화하지 않는다.

## 후속 작업

- 실제 충돌 20개 항목은 공식 정정 또는 기관 답변이 추가되면 해당 근거로만 재검토한다.
- 내포 정정 연결은 공식 역방향 ID 근거가 추가되기 전까지 생성하지 않는다. 제목·정정 사유만으로 연결 검증을 완화하지 않는다.

## 변경 파일

- `backend/src/main/java/org/jibro/notice/ReviewedApplicationPeriod.java`
- `backend/src/main/java/org/jibro/notice/SourceReviewModels.java`
- `backend/src/main/java/org/jibro/notice/SourceReviewVerifier.java`
- `backend/src/main/java/org/jibro/notice/SourceReviewPublicationService.java`
- `backend/src/test/java/org/jibro/notice/SourceReviewPublicationIntegrationTest.java`
- `backend/src/test/java/org/jibro/notice/ReviewedApplicationPeriodTest.java`
- `backend/src/main/java/org/jibro/notice/OfficialCorrectionLink.java`
- `backend/src/test/java/org/jibro/notice/OfficialCorrectionTemplateTest.java`
- `backend/src/test/java/org/jibro/notice/NoticeCorrectionIntegrationTest.java`
- `frontend/src/features/housing/NoticeDetailOverview.tsx`
- `frontend/src/features/housing/noticeDetailDates.ts`
- `frontend/src/features/housing/noticeDetailDates.test.ts`
- `frontend/src/features/preparation/calendar.test.ts`
- `docs/contracts/automatic-review.md`
- `docs/reviews/2026-11-08-ocr-guidance-*`
