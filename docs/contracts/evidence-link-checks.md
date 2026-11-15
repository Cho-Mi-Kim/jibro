# 출처·발급 링크 점검

기준: 2026-11-07. 코드 및 기술 규칙의 공식 발급 링크 검수, 기능별 계층 책임, 확인일·버전 구분을 따른다.

## 관리자 흐름

- 저장한 공고 원고의 공식 근거·첨부·조건·서류·일정·발급 주소를 관리자 화면에서 한 번에 점검한다. 미저장 원고에서는 점검하지 않는다.
- `POST /api/v1/admin/reviews/{id}/evidence-checks`, 요청 `{revision}`. 관리자 권한과 CSRF가 필요하다.
- 응답은 `{revision, version, checkedAt, results}`이며 결과마다 `kind, label, url, checkedAt, status, httpStatus`를 제공한다. 최상위 `checkedAt`은 **이번 주소 응답 점검 시각**이고, 항목의 `checkedAt`은 **원고에 기존 기록된 출처 확인일**이다. 발급 주소에 출처의 확인일을 임의로 복사하지 않는다.
- `EvidenceSourceReader`가 짧은 읽기 트랜잭션에서 원고를 복사하고, `EvidenceCheckService`가 DB 트랜잭션 밖에서 외부 주소를 점검한다. 시작 및 종료 시 revision이 다르면 409로 결과를 폐기한다. 화면도 변경된 revision의 이전 결과를 숨긴다.
- 점검으로 원고, 검수 PASS/HOLD, 출처 확인일, 기본/안내 확인일, 사용자 준비 상태를 변경하지 않는다. 결과는 해당 화면에서만 제공하고 별도 영구 검수 이력으로 저장하지 않는다.

## 응답 해석

| 상태 | 의미 |
| --- | --- |
| REACHABLE | HTTP 2xx 응답 확인. 문서 내용·발급 대상의 검수 완료가 아니다. |
| UNAVAILABLE | HTTP 404/410. 공식 상세 페이지와 현재 주소를 사람이 확인한다. |
| MANUAL | 이동 응답·접근 제한·HEAD 미지원 등으로 브라우저 직접 확인이 필요하다. |
| MISSING | 주소가 등록되지 않았다. 발급 기관과 공식 원문의 제출·발급 안내를 사용한다. |
| UNSUPPORTED | 자동 점검 허용 호스트 밖이다. 비공식 주소라고 단정하지 않는다. |
| UNCONFIRMED | DNS·연결·시간 제한 등으로 응답을 확인하지 못했다. |
| DEFERRED | 요청 수·시간·중단 제한으로 이번 점검에서 다루지 않았다. |

## 외부 요청과 검증 경계

- 기존 `AttachmentFingerprintClient`의 정확한 허용 호스트, 공용 IP 검사, HTTPS 443, 인증정보 제거, DNS/전체 요청 시간 제한을 재사용한다. `HEAD`만 보내며 리디렉션은 따라가지 않는다. 개인 답변·계정 쿠키·토큰은 보내지 않는다.
- 기본 허용 호스트는 `apply.lh.or.kr`, `www.lh.or.kr`이다. 다른 발급 기관은 자동점검 미지원으로 표시하고 직접 확인한다. 발급 주소를 만들기 위해 기관 홈페이지나 비공식 주소를 추정하지 않는다.
- 공고 한 번당 중복/페이지 fragment를 제외한 주소 최대 10개, 새 요청 시작 예산 30초, 단일 전송 한도 15초, 동시 점검 2개다. 나머지 주소는 보류로 표시한다.
- 주소 문법은 `EvidenceValidation`에서 확인한다. 출처 확인일은 유효한 날짜이고 미래일 수 없으며, 주소는 HTTPS·사용자정보 없음·기본/443 포트여야 한다. 주소가 없는 자료는 null을 유지한다.
- 주소가 응답해도 잘못된 내용·로그인 화면·안내 HTML일 수 있다. 사람은 **동일 공고/버전인지, 발췌 위치인지, 발급 대상·종류·기준일이 맞는지** 대조하고 기존 검수 흐름에 결과를 기록한다.

## 2026-11-07 외부 표본 확인

- 저장된 전세임대 공식 상세 페이지의 제목과 공고·첨부 목록을 확인했다: [2026년 청년 전세임대 1순위 수시모집](https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300019376&ccrCnntSysDsCd=03&uppAisTpCd=13&aisTpCd=17&mi=1026).
- 저장된 `fileid=68824433` 양식과 `fileid=68869885` 공고문은 기존 전송기와 같은 User-Agent로 HTTP 200 및 각각 HWPX/ZIP, PDF 파일 서명을 확인했다. 응답의 앞부분만 읽고 중단했으며 전체 문서 내용/현재 제출 적합성 검수는 수행하지 않았다.
- User-Agent 없는 요청은 HTML 응답을 반환했으며, 조회 도구에서도 첨부를 열지 못했다. 따라서 단순 HTTP 200이나 조회 도구 실패만으로 링크 검수 통과/실패를 단정하지 않는다. 원고의 과거 확인일이나 운영 DB는 수정하지 않았다.

자동 테스트: `EvidenceValidationTest`, `EvidenceCheckServiceTest`, `EvidenceChecksIntegrationTest`, `AttachmentFingerprintClientTest`, 프론트엔드 `EvidenceChecks.test.tsx`. 실행 결과는 이번 작업 기록을 따른다.
