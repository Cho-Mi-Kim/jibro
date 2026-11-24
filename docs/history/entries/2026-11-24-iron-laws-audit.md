# Iron Laws 실제 실행과 상위 지적 대조

- 기록일: 2026-11-24 (Asia/Seoul)
- 기록 상태: 검토 완료
- 작업 상태: 실행 완료 · 개선 미적용 · 미확인 범위 남음
- 유형: 코드 품질 점검
- 담당·검토: Codex 실행·주 검토, 독립 backend/frontend 에이전트 읽기 전용 대조. 인간·외부 GPT Pro 검토 미수행.
- 기준: `review/local-updates-20261115` / `eb64b60`, 실행 전 기존 미커밋 변경943개 포함.

## 배경·결정

사용자가 [Iron Laws 저장소](https://github.com/JinHo-von-Choi/iron-laws)를 지정해 실행을 요청했다. 앞선 [첨부 원칙 기반 리팩토링 점검](2026-11-24-refactoring-review.md) 때 미실행이던 도구를 실제 실행하는 별도 작업이다. 자동 수정·훅 설치·배포로 확대하지 않았다.

원본 버전1.12.1, 커밋 `c5d84f56e8a83bf9214158882fad1332e86ac3da`를 별도 도구 폴더와 가상환경에 설치했다. 앱 의존성과 전역 Python은 그대로 유지했다. `.gitignore` 제외와 실행 자원 상한만 설정하고 기본 규칙·심각도·분석 기준은 유지했다. 지적 억제나 baseline으로 등급을 올리지 않았다.

## 실행·결과

`audit --format json --build-manifest git`로 실제 분석1회 수행했다. 1,496개 파일, 약6분5초, 지적897건(치명적9·높음57·중간421·낮음410), 등급F, 종료1이다. `scan_status=complete`, 분석 오류 진단0건이며 종료1은 지적 기준 미통과다. 최초 `check --format`은 지원하지 않는 옵션으로 분석 전에 종료2였고 코드 실패나 완료된 분석으로 세지 않는다.

치명적·높음66건을 개별 대조했다. 의도된 계약53·오탐8·개선 대상3·최종 오류 전파 미확인2건이다. 원본 finding ID와 처분 기록66건의 집합 일치를 확인했다. 중간·낮음831건은 일부 표본만 대조했으며 전수 처분하지 않았다.

새 개선3건은 다음과 같다. 자동 HIGH 보안 취약점으로 단정하지 않고 코드상 조건부 P2 데이터 보존·오류 인지 문제로 분류했다.

1. `LhNoticeClient.java:173`: 잘못된 공고일을 null로 만들어 `NoticeService.java:247`에서 기존 정상 공고일을 지울 수 있다. 오류와 누락을 구별하고 기존 날짜를 보존해야 한다.
2. `VisitReception.java:84`: 사전 연락 기한 판독 실패 시 연락 안내 전체가 사라진다. 원문 안내와 확인 필요 사유를 남길 필요가 있다.
3. `VisitReception.java:118`: 잘못된 점심 시각이 빈 값이 되어 안내 없음과 판독 실패가 구분되지 않는다. 불확실한 구간만 확인 필요로 전달해야 한다.

`AgencyIntakePost.java:197,263`은 잘못된 값의 적용 거부는 확인했으나 상위 검수의 확인 필요 신호까지 추적되지 않아 미확인2건으로 남겼다. 실제 공고 오류 발생·운영 피해는 재현하지 않았다.

중간 표본에서는 `AutomaticReviewService.java:82`의 두 지적이 앞선 R6과 같은 실제 부채임을 확인했다. `NoticeApplicationTimingMigrationTest.java`의 검증 없음3건은 공통 도우미가 기대 날짜·시각·DB 실행을 검증하므로 오탐이다. 앞선 R1~R9 수동 지적은 별도로 유지하며 수정 완료로 바꾸지 않는다.

## 한계·검증

- 기본 분석 한도를 넘은 파일·함수 간 흐름501개는 미확인이다.
- 크기 또는 생성물 판정으로17개 파일을 건너뛰었다. 일부 검수 문구·시험 자료가 포함된다.
- `NoticeSourceInformation.tsx` 파싱 경고1건은 별도 `npm run typecheck` 통과로 실제 TypeScript 오류와 구분했다. 해당 파일의 분석기 한계는 남는다.
- 무시된 로컬 키·환경 파일·DB·빌드 내용은 미검사다. 도구의 gitignore 처리로 `.env.example`, `deploy/.env.example`도 제외됐다.
- 원본 coverage ledger의 `contract_status=unmet`과 미지원 규칙·동적 호출 한계를 유지한다. 전체 파일이 모두 증명됐다는 의미가 아니다.
- 앱 코드 변경이 없으므로 전체 회귀·mutation·브라우저·실기기·외부 연동·운영 재현·배포를 수행하지 않았다. 과거 테스트 결과를 이번 실행 결과로 복사하지 않았다.

## 산출물·후속 작업

로컬 도구·결과 위치: `C:/Users/Administrator/.codex/tools/iron-laws-20261124`.

- [검토 요약](C:/Users/Administrator/.codex/tools/iron-laws-20261124/jibro-report/summary.md)
- [원본 JSON](C:/Users/Administrator/.codex/tools/iron-laws-20261124/jibro-report/report.json) · [전체 Markdown](C:/Users/Administrator/.codex/tools/iron-laws-20261124/jibro-report/report.md)
- [치명적·주입 관련13건](C:/Users/Administrator/.codex/tools/iron-laws-20261124/jibro-report/backend-triage.md) · [예외 처리53건](C:/Users/Administrator/.codex/tools/iron-laws-20261124/jibro-report/exception-triage.md)

자동 결과는 원본 그대로 보존하고 실제 코드에 근거한 처분을 별도로 기록했다. 새 개선3건과 기존 수동 지적의 구현, 미확인2건 및 미처분 중간·낮음 지적은 후속 과제다. 외부 GPT Pro 리뷰는 미수행이다.

추가: 분리된 분석 도구·보고서·이번 작업 기록. 삭제: 없음. 제품 책임·동작·설정 변경 없음, 임시 제품 코드 없음, 커밋·push·배포 없음. 실제 데이터 오류를 해결한 성과로 소개하지 않는다.
