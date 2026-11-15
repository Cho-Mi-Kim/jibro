# 접수 종료 제외 후 미종료 공고 10건 준비 안내 반영

- 기록일: 2026-11-09 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 지정 대상 안내 반영 완료 / 기관 확인 쟁점 12개 유지
- 유형: 문제 해결 / 개발 / 공식 원문 검토
- 담당·검토: Codex 보조 구현 및 공고별 원문 대조·독립 교차검토. 사람 담당자의 승인·검토 여부는 미확인.
- 관련 이슈·PR·커밋: 새 이슈·PR·커밋·push 없음. 기준 브랜치 feature/launch-integrations-20261107, HEAD e288a99.

## 배경·문제

사용자가 검수 중인 공고를 정정본·공식 자료로 해결하도록 요청했다. 처음 선택한 범위는 접수 중·예정 및 최근 공고 우선이었고, 이후 **접수종료 공고는 아예 처리하지 말 것**으로 제한했다. 최근 게시된 공고여도 종료되면 이번 발췌·원고 작성·반영에서 제외했다.

공개 보류 15,926건의 최신 수집 응답을 읽기 전용으로 대조했다. 접수마감 15,895건, 미확정 과거 18건은 제외했다. 공식 상세에서 미종료로 확인한 13건 중 기존 논현 정정 관계 1건과 원본/정정 중복 2건을 분리해 신규 안내 10건을 검토했다. 시작 작업 트리는 476개 변경/미추적 항목이 있었으며 기존 작업을 reset·일괄 stage하지 않았다.

## 고려한 선택지

- 날짜가 없는 공고를 종료 또는 접수중으로 간주: 원자료에 없는 판단이라 채택하지 않았다.
- 보류 공고 전체 재처리: 최신 사용자 범위와 다르므로 제외했다.
- 본문 한 곳만으로 승인하거나 숫자가 다른 자료 중 하나를 선택: 충돌을 숨길 수 있어 채택하지 않았다.
- 미종료 고정 대상의 모든 첨부·공식 상세·정정 관계를 대조하고 실제 불일치만 국소화: 이 방식을 사용했다.

## 결정·이유

이번 작업용 실행기는 10개 ID 허용목록, 처리 직전/후 공식 상태 검사를 사용했다. 기존 예약 수집의 전역 설정을 변경한 것은 아니다. 새 안내는 기존 SourceReviewVerifier/SourceReviewPublicationService를 통과해야 하며 최신 revision, 발췌 지문, 전체 원문 문맥, 모든 첨부 원본 SHA, 원고·기간 제약을 완화하지 않았다. 실제 적용 전 로컬 DB backup을 생성하고 복원 목록의 완결성을 확인했다.

확인된 조건·서류는 제공하며 실제 자료의 불일치는 연결된 질문/서류/일정에만 남겼다. 현재 금액을 알 수 없는 전주는 다른 공고의 금액으로 대체하지 않았다. 군산·전주의 모집 완료 종료 조건은 근거와 함께 제공하며 종료일을 만들지 않았다. 태백장성의 접수기간 충돌은 날짜를 비워 유지했다.

## 변경·해결 과정

- DocumentTextParser는 한글 파일명이 MS949인 공식 ZIP에서 UTF-8 이름 해독이 실패한 경우에만 해당 문자셋으로 재시도한다. HwpxPackageReader/SpreadsheetTextParser의 형식 감지도 같은 문자셋을 사용한다. UTF-8 플래그, 손상 검출, 압축 해제·항목·본문 예산을 유지한다.
- AttachmentFingerprintClient의 명시적 fetchForSourceReview만 PNG/JPEG 원바이트 해시를 지원한다. 일반 fetch/download는 기존 문서 형식만 허용하며 HTML 위장·리다이렉트 거절을 유지한다. 이미지 파싱 성공이나 자동 검수 완료로 해석하지 않는다.
- ZIP 4개 출처는 실제 EXTRACTED로 복구했다. 원본 PNG/JPEG 도면 5개는 시각 대조와 SHA 재확인 후 REVIEWED 및 검토 위치·설명을 남겼다.
- 10개 공고의 본문·동일 내용의 HWP/HWPX/PDF·서식·Q&A·도면을 검토했다. 독립 교차검토에서 찾은 예비신혼 초본·가족증명, 신생아/혼인 상태 중첩, 가점 증빙 중첩, 장기카드대출 증빙, 대리신청 유형 차이와 외국인 서류 생략을 보완했다.
- 금융/자산서류 면제는 별도 복수선택 질문의 배타적 NONE·미확정 UNKNOWN과 제출 대상 선택에 연결했다. 검증 면제와 서류 제출 면제를 임의로 같게 취급하지 않았다.
- 동해·태백궁전 원고의 접수 시작/마감 제목이 기간 검증 계약과 맞지 않아 처음 적용이 거절됐다. 날짜를 변경하거나 검사를 우회하지 않고 기존 표준 제목·시간 괄호 형식으로 수정해 다시 공식 원문 검증 후 적용했다.
- 남원·동해 원본에서 최신 정정본으로 가는 관계는 공식 양방향 ID와 정정사유를 검증해 연결했다. 원본 내용 및 개인 준비 snapshot은 변경하지 않았다.

## 검증·결과

[공고별 결과·12개 미확정 쟁점](../../reviews/2026-11-09-active-pending-guidance/README.md) 및 [기계 검증 집계](../../reviews/2026-11-09-active-pending-guidance/summary.json)에 원문 검토 패키지를 함께 보존한다.

- 신규 제공10건: 완전 확인3건, 부분 제공7건. 질문141개·조건부 서류 후보278개·확정 일정21개. 모든 신청자에게278개 서류를 요구한다는 의미가 아니다.
- 현재 공고별 출처 참조62개(중복 첨부 포함)의 최신 문맥·원바이트 무결성을 확인했다. 10건 모두 실제 apply=true 및 AI_ASSISTED_SOURCE_REVIEW 이력으로 반영했다.
- backend `test integrationTest bootJar --console=plain`: 단위559/통합306, 실패0·생략0, BUILD SUCCESSFUL. 별도 시험 DB를 사용했다. 관련 파서·첨부 테스트와 documentParserJar도 먼저 통과했다. 최초 잘못된 parserWorkerJar 작업명 호출은 실패했으며 실제 documentParserJar 작업으로 정정했다.
- 최신 backend를 재시동했고 5173 frontend/8080 health HTTP200을 확인했다. 10건 모두 공개 API AVAILABLE/canStart=true, 원고의 질문·문서·일정·미확정 범위와 일치했다. Content가 이슈 연결 문항의 reviewRequired를 정규화하는 계약도 반영해 대조했다.
- 7건은 앱상 OPEN, 3건은 임의 마감일을 넣지 않아 UNKNOWN(기간 미확인)이다. 기관의 공식 미종료 상태와 앱의 날짜 기반 상태를 구분한다.
- 남원/동해 정정 API 2건 HTTP200 및 정확한 대상 ID 확인. 기존 논현 정정본은 읽기 전용으로 현재 근거와 공개 경로를 확인하고 보존했다.
- 프런트 코드·레이아웃 변경 없음. 이번 작업에서 실제 모바일 기기 검사는 수행하지 않았다.
- 제품 코드의 이미지 형식 제한과 ZIP 예산·파일명 검증을 별도로 읽기 전용 검토해 확정 결함이 없음을 확인했다.

## 기여·한계

공식 자료 대조와 코드·원고 작성에는 Codex가 보조했다. 기관 답변이나 최종 신청 자격을 대신 확정하지 않았다. 남은12개 쟁점은 해당 기관의 명시적 정정·확인이 필요하며 원문 근거와 적용 범위를 유지한다. 종료 공고 전체를 검수 완료로 만들거나 미래 수집 공고의 모든 의미 해석을 보장한 작업이 아니다.

개인 준비 snapshot, 기존 제공 중 안내, 이전 다른 작업 기록은 덮어쓰지 않았다. 기존 예약 작업이 별도로 수행한 변경을 이 작업 결과로 주장하지 않는다. 원문 전체 파일·렌더 이미지·비밀값은 공개 문서에 넣지 않았다.

## 발표·포트폴리오 메모

대량 과거 자료의 보류 상태를 먼저 공식 접수 상태로 나누고, 사용자 지정 미종료 범위에서만 모든 첨부의 동일성·조건부 서류 분기를 교차검증했다. 실제 충돌을 임의 해소하지 않고 검증된 부분을 제공하는 결과를 로컬 API에서 확인했다. 사람의 역할·효과 수치는 담당자 확인 후 사용한다.

## 후속 작업

- 남은12개 쟁점의 기관 정정·확인 자료가 나오면 같은 근거/범위 검증 경로로 보완한다.
- 접수종료 공고는 별도 사용자 요청 전 이번 처리 범위에 추가하지 않는다.
- 재시작된 로컬 서비스는 계속 이용 가능하다. 이번 작업에서 원격 배포·push는 수행하지 않았다.

## 변경 파일

- backend/src/main/java/org/jibro/collection/attachments/AttachmentFingerprintClient.java
- backend/src/main/java/org/jibro/collection/extraction/DocumentTextParser.java
- backend/src/main/java/org/jibro/collection/extraction/HwpxPackageReader.java
- backend/src/main/java/org/jibro/collection/extraction/SpreadsheetTextParser.java
- backend/src/test/java/org/jibro/collection/attachments/AttachmentFingerprintClientTest.java
- backend/src/test/java/org/jibro/collection/extraction/DocumentTextParserTest.java
- docs/contracts/official-text-extraction.md
- docs/reviews/2026-11-09-active-pending-guidance/

작업용 실행기·원문·렌더·검증 로그는 Git 제외 `.tools/qa/pending-guidance-20261109/`, 실행 전 backup은 `.tools/backups/before-active-guidance-20261109.dump`에 보존했다.
