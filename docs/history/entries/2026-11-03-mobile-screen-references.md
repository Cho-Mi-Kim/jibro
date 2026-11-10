# 모바일 화면 10종과 로컬 브라우저 검증

- 기록일: 2026-11-03 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 로컬 구현·브라우저 검증 완료
- 유형: 프론트엔드 / UI 리팩터링 / 접근성 검증
- 기준: `origin/develop`의 `ebccaa7`에서 만든 `feature/local-mobile-screens-20261103`
- 담당·검토: Codex가 사용자의 이미지와 이번 지시를 기준으로 수정·검증했다. 별도 사람 검토자는 미확인.

## 배경과 선택

사용자가 첨부한 이미지 10장은 화면의 구성과 정보 순서를 정하는 참고 자료다. 제품 판단에는 이 저장소의 `docs/design/screen-spec.md`, `docs/architecture/frontend-contracts.md`, `docs/contracts/question-options-and-documents.md`를 사용했다. 사용자 선택으로 주 메뉴는 768px부터 상단에 표시하고 제목과 본문에는 화면설계서의 짙은 회색을 적용했다. 태블릿 콘텐츠와 필터는 공간을 고려해 기존 단일 열 구성을 사용할 수 있다.

## 기능과 코드 연결

| 화면 | 변경 파일 | 책임 |
| --- | --- | --- |
| 진입점·공통 표시 | `frontend/index.html`, `frontend/src/app/App.tsx`, `frontend/src/shared/styles/global.css`, `frontend/src/shared/styles/Product.module.css` | 안전 영역, 실제 경로 등록, 색상·글자 확대·내비게이션 크기 |
| 홈·가이드·마이 | `frontend/src/pages/HomePage.tsx`, `frontend/src/pages/GuidePage.tsx`, `frontend/src/pages/MyPage.tsx`, `frontend/src/pages/AccountPages.tsx` | 홈 대표 건, 주제와 FAQ, 마이 메뉴, 인증 화면 분리. 이전 `frontend/src/pages/HomeGuidePages.tsx`는 두 페이지로 분리 |
| 공고 목록·상세 | `frontend/src/pages/NoticePages.tsx`, `frontend/src/pages/NoticeDetailPage.tsx`, `frontend/src/features/housing/NoticeCard.tsx`, `frontend/src/features/housing/noticeLabels.ts` | 결과·조회 상태, 실제 날짜·공식 근거, 준비 시작, 목록 필터·카드 위치로 복귀 |
| 필터·공식 원문 공유 | `frontend/src/features/housing/NoticeFilters.tsx`, `frontend/src/features/housing/NoticeFilters.module.css`, `frontend/src/features/housing/ShareNotice.tsx`, `frontend/src/features/housing/ShareNotice.module.css` | 모바일 초안·적용·닫기, 데스크톱 즉시 적용, 공식 HTTPS 주소 표시·복사 |
| 내 준비 | `frontend/src/pages/PreparationPages.tsx`, `frontend/src/features/preparation/Overview.module.css`, `frontend/src/features/preparation/PreparationNav.tsx`, `frontend/src/features/preparation/usePreparation.ts` | 대표 건·탭·CTA, 계정별 조회·저장 캐시와 새 버전 안내 |
| 질문·결과 | `frontend/src/pages/ConditionsPage.tsx`, `frontend/src/features/preparation/QuestionSteps.tsx`, `frontend/src/features/preparation/QuestionSteps.module.css`, `frontend/src/features/preparation/ConditionResult.tsx` | 실제 질문 수·선택지·진행 단계, 추가 확인 카드에서 해당 질문으로 복귀 |
| 서류·일정 | `frontend/src/pages/DocumentsPage.tsx`, `frontend/src/features/preparation/DocumentEditor.tsx`, `frontend/src/pages/SchedulePage.tsx` | 준비 대상·추가 확인 분리, 메모·저장·재시도, 공식 날짜와 개인 기록 분리 |
| 공통 UI·검사 | `frontend/src/shared/ui/HousingUI.tsx`, `frontend/src/shared/ui/ui.tsx`, `frontend/src/shared/ui/Icon.tsx`, `frontend/src/shared/ui/housingHelpers.ts`, `frontend/src/shared/ui/housingHelpers.test.ts`, `frontend/src/app/App.test.tsx` | 기존 SEED 버튼·아이콘·벽돌집·준비 요약 재사용, 공식 일정 선택과 질문 복귀 회귀 검사 |

## 검증과 한계

- 프론트엔드 CI의 포맷·린트·타입 검사, 테스트 27개, 일반 빌드 및 별도 Pages 시연 빌드 성공. 패키지와 잠금 파일의 Git 변경 없음.
- 독립된 Chrome 시연 환경에서 홈·공고·상세·조건·결과·준비·서류·일정·가이드·마이 10종을 360, 390, 768, 1280px로 검사했다. 360px에서 기본 글자 200%로 다시 검사했다. 50개 조합에서 문서 가로 넘침과 페이지 오류가 없었다.
- 기본 배율의 각 너비에서 주 메뉴 위치와 집중 화면 메뉴 숨김, 마지막 조작 요소와 고정 메뉴 겹침을 검사했다. 화면에 나타난 활성 링크·버튼·입력·요약·선택 컨트롤은 레이블을 포함한 영역으로 측정했다. 360·390·768·1280px에서 검사 대상 중 너비 또는 높이 44px 미만이 없었다.
- 실제 로컬 서비스에서 제목 색 `rgb(38, 38, 38)`, 서버·DB `UP`, 서울 필터 → 실제 공고 상세 → 공식 공유 주소 → 목록 필터 복귀를 확인했다. 시연 환경에서 필터 닫기·적용·해제와 관심 공고 안내창을 검사했다. 준비 화면 검증에는 기존 시연 계정을 사용했다.
- 준비도, 사용자 이름, 공고 날짜·출처·질문 수는 해당 데이터로 표시한다. 서버의 답변 상태를 신청 자격 판정으로 확대하지 않았다. 공식 가이드 자료나 로그인 수단이 응답에 없을 때 목업 값으로 대체하지 않는다.
- 브라우저 크기 검증이며 Android·iOS 실기기 확인은 하지 않았다. 백엔드 코드는 그대로이며 이번 프론트엔드 변경에서 백엔드 전체 테스트·외부 OAuth·실시간 LH 수집은 재검증하지 않았다.

## 재확인

실행 중인 로컬 서비스의 `/notices`에서 공고를 확인할 수 있다. 로컬 캡처와 검사 내역은 Git에서 제외한 `.tools/mobile-qa/after/`에 있다. 이미지의 ‘입력 기준 충족’이라는 분류까지 도입하려면 조건별 판정 근거와 서버 응답 계약을 별도로 정해야 한다. 원격 커밋·PR·배포는 수행하지 않았다.
