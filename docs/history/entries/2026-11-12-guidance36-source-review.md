# 검수 대기·첨부 읽기 오류 36건 원문 대조

- 기록일: 2026-11-12 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 처리 완료, 외부 근거가 필요한 4건 보류
- 유형: 문제 해결 / 개발 / 자료 검수
- 담당·검토: Codex AI 보조 구현·원문 대조·실행 검증. 팀 담당자 검토 미확인
- 기준: feature/launch-integrations-20261107, e288a99. 기존 작업 변경 보존. 커밋·push 없음

## 배경·문제

사용자가 기본 목록에서 준비 안내가 온전히 제공되지 않는 사유 중 3·4·5번 범주를 해결하도록 요청했다. 시작 시 목록 스냅샷에서 대기 16건·발췌 후 미검수 15건·읽기 오류 5건의 36개 ID를 고정했다. 기존 일부확인필요 17건과 미제공 2건은 이번 대상에 포함하지 않았다. 접수종료 공고 재처리 제외 지시를 유지했다.

## 고려한 선택지

- 판독 결과가 존재한다는 이유로 일괄 승인: 순위별 날짜·완화 예외·원문 상충을 놓칠 수 있어 사용하지 않았다.
- 원문 대조 후 전체 보류: 확인된 조건·서류까지 감추므로 충돌 항목만 별도로 남기는 기존 기능을 사용했다.
- 오래된 공고를 연도만 보고 종료 처리: 공급완료시까지 선착순 계약인 자료에서 종료일을 추정하게 되므로 사용하지 않았다.

## 결정·이유

공식 상세와 현재 모집 PDF/HWP의 대상·조건·서류·일정을 대조했다. 단지 도면·과거 팸플릿은 별도 역할로 분류하고 현재 자격 근거로 쓰지 않았다. 복잡한 자격은 원문 확인형 질문으로 유지하며 개인별 최종 자격을 자동 확정하지 않는다. 게시 직전 원문·첨부 지문과 공고 revision을 다시 확인하고 기존 SourceReviewPublicationService를 통해 감사 이력을 남겼다.

## 변경·해결 과정

- OcrDocumentParser: 일반 텍스트가 많은 PDF를 총 쪽수 40장 제한으로 잘못 거절하던 경로를 수정했다. 실제 OCR 이미지 40장·시간·화소 예산은 유지한다.
- AttachmentFingerprintClient: 명시적 원문검수의 스트리밍 지문 확인 상한만 100MiB에서 150MiB로 늘렸다. 일반 수집 20MiB 제한과 검수 시간 제한은 유지한다.
- 5개 대용량 팸플릿/ZIP과 전주팔복 혼합 PDF 발췌를 복구했다.
- 순위별 접수일, 현장·온라인 접수 차이, 우편 소인 마감, 선계약 후검증, 가구·계층별 증빙을 구분해 검수 패키지에 기록했다.
- 준비 안내 제공 28건, 부분 제공 3건, 기관 공급으로 개인 준비 미제공 1건, 외부 확인 보류 4건으로 처리했다.

## 검증·결과

- `./gradlew.bat test --tests '*AttachmentFingerprintClientTest' --tests '*OcrDocumentParserTest' bootJar --console=plain`: 성공. JUnit 결과 15 + 14개, 실패·오류 0.
- 신규 테스트는 45쪽 텍스트 + 1쪽 스캔 처리, 41장 실제 스캔 제한, 120MiB 검수 다운로드·151MiB 거절 및 일반 다운로드 한도 보존을 확인한다.
- 새 jar로 기존 백엔드만 재시작. `/api/v1/health` 서비스·DB UP. 프론트엔드 5173 유지.
- 5173을 통한 상세 API 36건에서 공개 상태·준비 시작 가능 여부·검수된 접수기간·조건부 서류 연결·부분검수 항목을 대조했고 오류 0.
- UI 디자인 변경 없음. 실제 모바일 기기 검증·외부기관 문의·원격 배포는 수행하지 않았다.

## 기여·한계

대구신서·경산삼주봉황의 접수기간, 삼척원조의 과거 첫날 시각, 김제검산1의 기준일·배점 지역·현재 모집 여부는 원문만으로 확정할 수 없어 해당 항목만 남겼다. 강원혁신B2는 공식 원문 파일 오류, 평택소사벌B5·충북혁신B7·논산내동2 A1은 과거 선착순 모집의 현재 종료 여부를 확인하지 못해 보류했다. 전체 36건을 모두 준비 안내 제공으로 바꾼 결과가 아니다.

## 발표·포트폴리오 메모

파일 읽기 성공과 준비 안내 검수 완료를 구분하고, 원문 재조회·지문·revision 확인을 거쳐 부분 공개와 보류를 구별했다. 모든 신규 공고의 무조건 자동 승인이나 실제 신청 자격 검증 완료를 의미하지 않는다.

## 후속 작업

- 보류 4건의 공식 원문 복구·현 모집 종료 근거 확보 시 해당 검수만 재개.
- 부분 제공 공고의 정정 또는 기관 답변 확보 시 충돌 항목만 재검수.
- 상세 결과·원문 링크·검수 패키지: [검수 결과](../../reviews/2026-11-12-guidance36/README.md).

## 변경 파일

- backend/src/main/java/org/jibro/collection/extraction/OcrDocumentParser.java
- backend/src/test/java/org/jibro/collection/extraction/OcrDocumentParserTest.java
- backend/src/main/java/org/jibro/collection/attachments/AttachmentFingerprintClient.java
- backend/src/test/java/org/jibro/collection/attachments/AttachmentFingerprintClientTest.java
- docs/reviews/2026-11-12-guidance36/README.md
- docs/reviews/2026-11-12-guidance36/review-packages.json
- docs/reviews/2026-11-12-guidance36/public-verification.json
