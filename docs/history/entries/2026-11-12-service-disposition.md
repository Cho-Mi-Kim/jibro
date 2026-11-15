# 과거 공고 종료 처리와 비주거 모집 서비스 제외

## 배경·요청
2026-11-12 사용자는 현재 목록의 검수 보류 4건을 종료 처리하고, 기업체 선정·어린이집 운영자 모집 3건을 서비스에서 제외하도록 명시했다. 기존 69건 중 완전 제공42/부분20/보류4/미제공3을 API로 확인했다.

## 결정·이유
공식 종료일이나 현재 모집완료 사실을 새로 확인한 것은 아니다. LH 원천 sourceClosed·원문·접수 날짜와 구분되는 serviceClosed로 사용자 운영 결정을 보존한다. 종료일은 만들지 않으며, 종료일 없는 종료 공고는 기존 기본 범위 규칙에 따라 제외하되 명시 종료 검색과 상세는 유지한다. 비주거 모집은 serviceExcluded로 목록·상세·공식 출처·관심공고·가이드·알림 대상에서 제외하고 원본과 개인 준비 기록은 삭제하지 않는다.

## 구현·범위
- V37/NoticeEntity: 서비스 종료·제외 상태, 사유·시각 및 notice_service_decision 감사 이력.
- NoticeDispositionService: 내부 명시 결정 명령, 행 잠금/revision 검사. 공개 변경 API 추가 없음.
- NonResidentialNoticeScope → NoticeService.importSource: 기업체 선정·기관공급 또는 어린이집 운영자 모집임이 제목에 명시된 신규 수집 제외. 근로자 개인 입주자 모집·어린이집 편의시설 언급·일반 기관공급은 무조건 제외하지 않는다. 기존 제외는 재수집으로 해제하지 않는다.
- NoticeQueryRepository/DefaultNoticeScope/NoticeService: 종료 상태·기본 목록·명시 검색·개수·상세 일치. AutomaticReviewEligibility는 자동 검수/발췌에서 서비스 종료·제외를 제외. 수동 원문 게시도 명시 결정 보호.
- SavedNoticeRepository/AlertRepository/GuideRepository/NoticeSourceInformationRepository/NoticeCorrectionService: 서비스 제외의 다른 공개 경로 차단.

## 적용·검증
- 4건: 강원혁신B2, 평택소사벌B5, 충북혁신B7, 논산내동2A1 → CLOSE.
- 3건: 기장군 대상기업, 파주시 중소기업체 기관공급, 인천계양A3 어린이집 운영예정자 → EXCLUDE.
- 실제 DB는 전체 7건을 단일 트랜잭션으로 처리. 이전/이후 스냅샷은 .tools/qa/notice-service-disposition, 검수내용·원문·기존 날짜·공식 상태·버전 보존 대조 통과.
- 단위6개 및 통합7개 통과, bootJar 성공. 운영 종료 후 원천 재수집, 제외 신규 수집/중복감사 방지, 개인 근로자/편의시설 과제외 방지, 날짜28일 경계·샘플 제외 회귀 포함.
- 5173 백엔드 재시작 및 health/database UP. 실제 API 기본 목록69→62, 완전42/부분20. 4건 CLOSED·신규준비 불가·종료검색 가능, 3건 상세404·검색제외 확인. 검증 JSON: docs/reviews/2026-11-12-service-disposition/verification.json.

## 기여·한계와 후속
AI가 로컬 코드 수정·검증·운영 결정 적용을 수행했다. 원격 push/배포 및 기관 종료 확인은 수행하지 않았다. 자동 제외는 명시된 모집 주체 표현만 판별하며 모든 비주거 모집의 의미 판별을 보장하지 않는다. 이후 공식 재개가 확인돼도 서비스 종료 결정을 자동 해제하지 않으므로 별도 명시 검토가 필요하다. 부분 안내20건의58항목은 이번 작업에서 변경하지 않았다.

## 발표·포트폴리오 메모
공식 원천 상태와 서비스 운영 판단을 분리하고 재수집·페이지 검색·자동 검수 경계에 동일 결정을 적용한 변경이다. 실제 확인 범위는 위 검사와7건 API 결과이며 실사용자 효과를 측정한 것은 아니다.
