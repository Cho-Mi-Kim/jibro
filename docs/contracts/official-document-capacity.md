# 공개 공식 문서의 처리 용량과 PDF 렌더링

2026-11-24 보안 보완. 기존 fileId·원문 지문·페이지 URL과 공식 원문 변경 시 오류 계약을 보존한다.

## 요청 경계

`OfficialDocumentCapacityFilter`는 기존 방문 확인·Spring Security 뒤에서 공개 공식 파일 GET/HEAD를 전역 2개로 제한한다. 파일 다운로드 전에 용량을 확보하고 동기 응답 본문을 모두 쓸 때까지 유지한다. 초과 요청은 다운로드나 파싱을 시작하지 않고 429 `OFFICIAL_DOCUMENT_BUSY`, `Retry-After: 2`, `Cache-Control: no-store`로 거부한다.

PDF 원본·페이지·텍스트·manifest·fingerprint가 같은 예산을 사용한다. 저장된 결과만 읽는 relocate와 일반 공고/health API는 이 제한에 포함하지 않는다. 예외와 응답 쓰기 실패에도 용량을 반환한다. 향후 비동기 응답으로 바꾸려면 본문 완료까지 용량을 유지하는 계약도 함께 바꿔야 한다.

## 렌더 경계

`OfficialPdfController.page`는 주 HTTP 서버에서 PDFBox를 실행하지 않는다. 공식 PDF와 요청 지문을 확인한 뒤 `BoundedDocumentParser.renderPdfPage`에 전달한다. 문서 추출과 같은 전역 워커 슬롯 2개·8초 감독·종료 확인·작업 디렉터리 정리·최소 환경을 공유한다. 새 독립 실행 관리 경로를 만들지 않는다.

기존 worker jar의 `DocumentParserWorker`에 `pdf-page` 명령을 추가했다. 자식 JVM은 힙 128MiB·메타스페이스 64MiB·직접 메모리 16MiB·프로세서 수 2를 사용하고 부모 종료/10초 watchdog에도 중단된다. PNG 출력은 상태 바이트 포함 최대 20MiB+1이며 부모는 제한을 넘는 출력·잘못된 PNG 서명을 거부한다. 실제 파싱·이미지 디코딩은 자식 프로세스에서만 수행한다.

최종 이미지는 기존과 같이 긴 변 최대 2400px, 최대 배율 1.5다. 없는 페이지는 404, 잘못된 페이지 크기는 422, 변경된 지문은 기존 409 `OFFICIAL_SOURCE_CHANGED`를 유지한다. 워커 용량 부족은 429, 중단/처리 실패는 비밀값이나 문서 내용을 포함하지 않는 503 `OFFICIAL_DOCUMENT_UNAVAILABLE`을 반환한다. PDF 원본 링크는 계속 제공한다.

## 회귀 검증

- `OfficialDocumentCapacityFilterTest`: 각 파일 경로와 HEAD의 초과 요청이 후속 작업 전에 거부되고 응답 완료/실패 후 용량이 복구됨.
- `BoundedPdfPageTest`: 실제 worker jar의 올바른 페이지/PNG, 기존 공식 PDF fixture, 없는 페이지, 고의 무한 대기·힙 고갈·출력 초과 프로세스의 종료와 다음 정상 처리.
- `OfficialPdfBoundaryIntegrationTest`: 실제 HTTP 경계와 자식 프로세스를 통한 PNG 응답, 원본·409·404 호환, 동시 다운로드 차단과 health 정상 응답.

이는 수집/렌더링 자원 고갈 경계의 보완이며 무제한 트래픽이나 임의 PDF에 대한 무취약성 보장은 아니다. 실제 운영 배포와 부하 수치는 해당 작업의 실행 결과와 구분한다.
