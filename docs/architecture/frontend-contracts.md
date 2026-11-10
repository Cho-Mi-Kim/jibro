# 프론트엔드 구현 계약 v1

초기 작성일: 2026-10-30. 최종 대조일: 2026-11-10. 사용자가 미확정 기능 계약의 판단을 위임했다. PR #24 (`e288a99`)의 구현을 기준으로 하며 아직 develop/main에 병합된 상태는 아니다. 날짜가 명시된 과거 비교 결정은 현행 구현과 구분한다. 구현 결정과 외부 연결 제약은 [백엔드 계약](backend-contracts.md)을 함께 확인한다.

## 연결·인증

기본은 실제 /api/v1 서버다. 개발용 `npm run dev:demo` 또는 개발 환경의 `VITE_API_MODE=demo`, 별도 정적 공개 시연용 `pages-demo` 빌드에서만 시연 어댑터를 사용한다. 일반 운영 빌드는 이 어댑터를 활성화하지 않는다. 시연 데이터는 가상 공고·메모리 저장이며 새로고침 때 초기화된다. 실제 개인정보를 입력하지 않는다. 서버의 비회원 체험 세션은 이 어댑터와 별개이며 아래 로컬 비회원 체험 계약을 따른다.

Access Token은 메모리, Refresh Token은 서버 HttpOnly 쿠키. GET /auth/csrf → {token}, 변경 요청 X-CSRF-TOKEN. POST /auth/login → {accessToken,user}, POST /auth/refresh → {accessToken}, POST /auth/logout → 204. GET /users/me → User. 401은 재발급 1회만 재시도하며 동시 재발급을 공유한다. 회원가입 POST /auth/signup {name,email,password} → 빈 201 응답 후 로그인한다. 소셜 /oauth2/authorization/google|kakao → /auth/callback으로 복귀하고 me 재조회로 복원한다. 토큰을 URL로 전달하지 않는다. 회원가입 이름 2~30자, 비밀번호 10자 이상·UTF-8 72바이트 이하. 소셜 로그인은 구현되어 있고 과거 로컬 실제 연동 확인 기록이 있지만 현재 브랜치·운영 환경에서의 재검증과 구분한다. 이메일 확인·재설정·탈퇴 및 프로필 변경은 [회원 기능 계약](../contracts/member-features.md)에 따라 구현했다. 기존 계정과 소셜 계정 연결·해제는 미구현이다.

계정 확인 링크의 일회용 값은 `/account/confirm#verify=…`, `#reset=…`, `#delete=…`의 fragment로 전달한다. 이는 접근 토큰 전달이 아니며 사용자가 확인을 제출해야 API를 호출한다. 성공 후 fragment를 제거한다. 메일 발송 불가 환경과 Pages 시연에서는 실제 발송이 완료된 것처럼 표시하지 않는다. 비밀번호 변경·재설정·탈퇴 후 재인증하며, 일반 API의 최종 401에 세션 만료 이벤트를 받아 개인 캐시를 비운다.

## 공고

GET /notices?keyword=&region=&type=&status=&sort=deadline&page=0&size=20. 복수값은 같은 키 반복, 필터 간 AND/같은 필터 OR. sort=deadline은 마감일 오름차순·동률 id 오름차순, newest는 publishedDate 내림차순·동률 id 오름차순. 날짜 미확인은 뒤로. 검색 대상 제목·기관·지역. 응답 {items,page,size,totalElements,totalPages}. GET /notices/filters → {regions,types}. GET /notices/:id → Notice. 필터 변경 시 page=0. 잘못된 page/size는 프론트에서 정상 범위로 보정하고 서버도 검증한다.

기본 정보 공개와 guideState=AVAILABLE|REVIEWING|UNSUPPORTED|CHANGE_REVIEW는 독립적이다. canStart는 서버가 결정한다. 미검수 질문·서류는 응답에서 제외한다. 공식 원문은 HTTPS만 연결하고 주소가 없으면 확인 필요를 표시한다.

### 공고 목록 표시 모듈 (현행: 2026-11-06)

- `NoticePages.NoticesPage`는 검색 URL·정렬·페이지와 목록 복귀를 관리한다. `NoticeList.module.css`는 해당 페이지의 배치만 담당한다.
- `NoticeFilters`는 모바일 필터의 임시 선택을 적용 시점에 URL로 반영한다. 닫기·취소는 적용된 조건을 바꾸지 않는다. 1024px부터 필터를 사이드바로 표시하며, 주 메뉴의 768px 전환과는 별도다.
- `NoticeCard`는 상태·제목·기관·지역·유형과 관심 저장·미리보기를 조합한다. 제목 버튼으로 카드를 펼치고 북마크는 별도로 조작한다. 카드 스타일은 `NoticeCard.module.css`에 한정한다.
- `NoticeCardDeadline`은 펼친 카드에서 마감 경과 시 `신청 마감`, 그 외에는 `신청 기한`과 남은 기한을 표시한다. 공고일·접수기간을 나열하던 `NoticeCardDates`와 접힌 카드의 날짜 행은 제거했다. 상세 일정 정보는 `NoticePreview` 및 공고 상세 화면이 담당한다.
- 참고 이미지의 고정 공고 수·가상 날짜·준비 중 버튼은 복제하지 않는다. 제목/입력/보조 글자와 색상 대비는 화면설계서 3절을 우선하고, 실제 비교·관심 저장 기능과 정렬·필터 칩을 유지한다.
- 정렬은 결과 도구 행에 둔다. 접힌 카드 제목의 시각적 줄 수 제한과 별개로 접근성 이름·펼친 카드·상세는 전체 제목을 유지한다. 별도의 우측 화살표나 선택 공고 홈 링크는 두지 않는다.
- 과거 비교 결정(2026-11-04): 사용자가 393×852 두 안을 비교한 뒤 GitHub 문서 기준 A안을 선택했다. 당시 카드 제목 18px·보조/날짜 14px·입력/버튼 16px을 기준으로 비교용 작은 글자 분기·변수와 임시 비교 HTML을 제거했다. 이후 사용자 요청에 따른 개별 크기·배치는 현재 모듈 CSS를 따른다. 이전 비교 URL의 appearance는 API 쿼리에서 제외한다.

### 공고 상세 표시 모듈 (현행: 2026-11-06)

- `NoticeDetailPage`는 공고 조회, 로딩/오류, 목록 검색·카드 위치 복귀, URL 탭 상태와 키보드 탐색을 담당한다. `NoticeDetailOverview`, `NoticeDetailContent`, `NoticeDetailActions`가 정보 카드·본문·행동을 분리하고 `NoticeDetail.module.css`가 화면 범위의 스타일을 담당한다.
- `noticeDetailDates`는 API 기본 날짜와 공통 일정 선택기를 사용한다. 미확인 날짜·확인일·문서 쪽수는 생성하지 않는다. 현재 일정 요약은 접수 기간·결과 발표·서류 마감 세 칸이며 신청 마감/D-day는 위쪽 별도 행에 둔다. 발표·서류 날짜가 없으면 값만 공란으로 두고 칸은 유지한다. 준비 안내 상태로 공개 공식 일정을 숨기지 않는다.
- 요약 첫 문장과 펼침으로 전문을 보존한다. 개인 조건·서류 안내는 AVAILABLE일 때만 제공한다. 공개 일정·날짜 요약·캘린더는 이 제한과 분리하며 아래 일정 계약을 따른다.
- 원문·첨부·비교·수정 전후 비교를 유지하며 부가 기능은 `공고 자료와 비교`에 모은다. 선택 공고 홈 링크는 제거했다. `startPath`는 명시적으로 주요 버튼을 클릭할 때만 호출하며 조회/렌더링은 준비 시작 의도를 만들지 않는다. 시작 가능 여부는 서버 `canStart`를 따른다.
- `EvidenceLink`는 출처 위치 자체를 공식 근거 링크로 표시하고 선택적 `className`으로 화면별 배치를 받는다. 동일 출처 옆에 별도 `공식 근거 열기` 링크를 중복하지 않는다.
- 과거 비교 결정(2026-11-04): 당시 네 칸 일정 박스에서 사용자가 각 항목의 한 줄 표시를 문서 최소 글자 크기보다 우선하고, 887px 참고 이미지를 393px로 환산한 크기를 요청했다. 그 뒤 세 칸 배치와 별도 마감 행으로 개정했으므로 당시 네 칸 크기는 현행 배치 계약이 아니다. 미제공 날짜를 공란으로 두는 원칙은 유지한다.

### 일정 해석·내보내기 (현행: 2026-11-06)

- `housing/schedules.ts`가 제목 기반 일정 종류, 유효한 한국 날짜·시각, 대표 일정과 신청 기록 대상 선택을 담당한다. `noticeDetailDates`, 준비 날짜 요약, `scheduleTimelineModel`, `calendar`, 신청 기록 팝업이 이를 사용한다. API에 새 일정 종류 필드를 추가한 것은 아니다.
- 대표 발표는 서류 제출 대상자 발표 후보를 먼저 고른 뒤 해당 후보 안에서 확인된 시각을 우선한다. 접수 시작·마감은 같은 날의 기본 접수 일정 후보를 우선한다. 기본 날짜와 다른 날의 세부 일정에서 시각을 가져오지 않으며, 날짜만 있으면 임의 시각을 만들지 않는다.
- 공개 일정의 검수 범위는 [백엔드 공개 일정 계약](backend-contracts.md#공개-일정)을 따른다. `currentPreparationSchedules`는 현재 Notice가 있으면 그 공개 `schedules`를 사용하고, 아직 없으면 AVAILABLE인 준비 스냅샷만 대체 조회한다. 공고 버전이 달라져도 과거 스냅샷은 수정하지 않는다. 타임라인의 개인 완료 표시는 같은 버전에서 일정 ID·제목·날짜까지 일치할 때만 연결한다.
- `calendarItems`는 현재 공개 일정을 내보낸다. 같은 날 기본 접수 일정과 확인된 시각을 가진 동등한 세부 일정은 하나로 합치며 기본 `start`/`end` 식별자는 유지한다. 다른 지역·별도 시각·서류 마감 등 서로 다른 일정은 같은 날이어도 남긴다. 확인된 시각은 ICS에 반영하고 날짜만 있는 항목은 종일 일정으로 유지한다. ICS 생성·문자 이스케이프·줄 접기는 `calendar.ts`, 선택 팝업은 `CalendarExport`의 책임이다.

## 개인 준비

GET /preparations → Preparation[], GET /preparations/:id → Preparation. POST /preparations {noticeId,version} → Preparation. 같은 회원·공고 중복 요청은 기존 건 반환. PUT /preparations/:id/answers {revision,answers}; PATCH /preparations/:id/documents/:documentId {revision,state,note}; PATCH /preparations/:id/schedules/:scheduleId {revision,recorded}. 변경 응답은 새 Preparation 전체다. revision 불일치 409, 타인 기록은 404, 인증 없음 401. 낙관적 완료 표시 없이 성공 응답 후 캐시 갱신. 모름 UNKNOWN과 미응답(키 없음)을 구분한다. 서류 필요 여부와 준비 상태를 별도 관리한다. 사용자 증빙 파일 업로드 없음.

초기 계산 초안: 답변 확인 30%, 필요한 서류 완료 50%, 공식 일정 확인 기록 20%. 모름은 답변 완료 점수 제외하며 경고 표시. 조건부 해당 여부 미확인은 완료 처리하지 않는다. 평가 항목이 0개인 영역은 자동 완료하지 않고 0점. 정수 반올림하되 미완료 항목이 남아 있으면 최대 99%로 제한한다. houseStage는 0~19=1,20~39=2,40~69=3,70~99=4,100=5. 이는 준비 작업 기록이며 자격·신청 완료 판정이 아니다. 서버가 progress/houseStage/warnings/nextAction을 반환한다. 현행 계산 책임은 백엔드의 순수 `PreparationRules`이며 `PreparationService`가 저장·동시 수정·기준 갱신을 담당한다. 실제 UI는 계산하지 않는다. 실제 공고 검증 후 계산 기준 버전을 확정한다.

서버가 없는 시연 환경은 `demoPreparationRules`의 순수 계산을 `demo.ts` 요청·저장 어댑터에서 호출한다. Java와 TypeScript 구현은 [공유 기대값 사례](../../data/contracts/preparation-rules.json)를 각각 검증한다. 이 사례는 계산과 상태 전이의 일치 확인용이며 실제 서버의 인증·소유권·revision·DB 통합 검증을 대신하지 않는다. 운영 및 비회원 체험은 서버 응답을 그대로 사용한다.

변경 검토 중에는 기존 기준 버전의 개인 기록을 조회하고 수정은 409로 보류한다. 기존 답변·완료 상태는 보존한다. 다음 행동은 안내를 사용할 수 없으면 공고 변경 확인을 우선하고, 사용할 수 있으면 재확인 서류 → 미응답/추가 확인 답변 → 미완료 서류 → 신청 일정 확인 순서다. 공식 사이트 이동은 완료 기록을 변경하지 않는다.

### 내 준비 개요 표시 모듈 (현행: 2026-11-06)

- `PreparationTabLayout`은 내 준비 목록과 개요·조건·서류·일정 화면의 공통 상단을 유지한다. `PreparationHeader`의 준비 건 선택기와 `PreparationNav`는 스크롤 영역 밖에 두고, 아래의 현재 탭만 스크롤·교체한다. 목록/선택은 `useActivePreparation`에 위임하며 개별 건 URL에서는 URL의 ID를 우선한다. 준비 건 변경 시 현재 탭을 유지하고 미저장 변경을 확인한다. 진행률 100%는 **준비 기록 완료**다.
- `PreparationOverview`는 요약, 다음 행동, 여정, 공식 신청·신청 기록 버튼과 계속하기를 조합한다. `PreparationGuidance`는 새 안내 갱신·검수 경고를 담당한다. 홈의 `NextAction`은 자체 CSS 격자로 배치하고, 개요의 다음 행동은 `Overview.module.css`가 배치한다. 두 화면 모두 서버가 반환한 문구·이유·경로를 표시하며 다음 행동 정책을 계산하지 않는다.
- `PreparationOverviewSummary`는 `PreparationIdentity`와 `PreparationDates`로 공고 제목·안내 버전·마감·날짜를 표시하고 공통 `House`에 서버 `progress/houseStage`를 전달한다. 준비도 설명은 홈·개요 모두 같은 접기 동작을 사용한다. `HousingUI.PreparationSummary`는 호출자가 전달한 공고 식별 영역을 조합하며 이전 화면별 fallback 분기를 두지 않는다.
- `/preparations/:id/conditions`는 내 준비의 조건 탭, `/preparations/:id/conditions/result`는 독립적인 내 조건 확인 결과 화면, `/preparations/:id/conditions/edit`는 질문 편집 흐름이다. `preparationTabRoutes`는 URL로 탭을 판단하며 `location.state`로 같은 URL의 화면 종류를 바꾸지 않는다. 복귀 위치 전달은 별도 탐색 상태로 유지한다.
- `Overview.module.css`, `PreparationHeader.module.css`, `PreparationOverviewSummary.module.css`는 내 준비 개요에만 적용한다. 카드 제목 18px·보조/날짜 14px·버튼 16px·페이지 제목 24px을 유지한다. 이미지의 가상 날짜·65%·김 이름은 실제 값으로 대체하며 글자를 줄여 한 화면에 억지로 맞추지 않는다.
- 공식 사이트 링크는 읽기 이동이다. 신청 여부는 개요의 `ApplicationRecordDialog`에서 확인 후 기록한다. 여정의 공식 신청 단계는 `/preparations/:id?application=record`로 같은 팝업을 열고, 닫으면 해당 쿼리를 replace로 지운다. 예전 일정 화면의 `#application` 경로는 사용하지 않는다. 팝업을 여는 것만으로 기록하지 않으며, AVAILABLE인 준비 스냅샷의 신청 기록 대상만 수정한다. 조건·서류·일정·계속하기에는 선택한 준비 ID/서버 다음 행동 경로를 전달한다.

## 관리자·가이드

GET /guides → Guide[]. GET /admin/reviews → Review[]. PATCH /admin/reviews/:id {basic,guide,reason,revision}. basic=PASS|HOLD, guide=PASS|HOLD|UNSUPPORTED. 기본 정보 HOLD면 양쪽 공개 금지. 보완 사유 필수. 검수자·시각·이력은 서버가 기록. GET/POST /admin/collections는 실행 목록/실행 요청이며 수집 성공을 공개 완료로 취급하지 않는다. 서버에서 ADMIN 권한 필수.

## UI와 구현 경계

React Router 경로는 화면설계서 기준. 서버 상태는 TanStack Query. 쿼리 키에 사용자 ID·공고 ID·검색 URL을 포함한다. 로그아웃 성공 후 개인 캐시 제거. 실패 시 입력을 유지하고 재시도. 404 경로와 인증 필요·권한 부족·빈 결과를 표시한다. 관심 공고, 비교, 가이드 검색, 알림 설정·읽음, 캘린더 내보내기, 보관·복원·메모 연결, 프로필·계정 관리는 [회원 기능 계약](../contracts/member-features.md)에 따라 실제 API 또는 명시적 시연 어댑터를 사용한다. 이전의 ‘준비 중’ 안내만 있는 단계와 구분한다. 외부 메일·푸시 제공 여부는 서버 설정과 브라우저 지원·권한에 따른다.

SEED 공식 React 수동 설치와 ActionButton 공개 컴포넌트를 사용하며 브랜드 CSS는 제품 레이어에서 적용한다. 기타 UI는 semantic HTML과 제품 전용 CSS를 사용한다. 공식 스니펫 설치를 수행했다고 주장하지 않는다.

근거: https://seed-design.io/llms/react/getting-started/installation/manual.txt 및 https://seed-design.io/llms/react/components/action-button.txt . 상세 DTO의 현재 단일 소스는 frontend/src/features/housing/types.ts이다.


## 실제 연동 보완
- 신청 날짜·게시일은 확인되지 않으면 null이다. 신청 시작·종료일 중 하나라도 불명확하면 status=UNKNOWN, 화면은 일정 확인 필요로 표시한다. 준비 안내 통과에는 질문·서류·일정과 확인된 신청 기간이 필요하다.
- 관리자 원고 편집과 수집 시작 페이지, 서버 샘플 표시, 활성 소셜 제공자 목록 API를 추가했다.
- 갱신 요청에 만료된 접근 토큰을 보내지 않는다. 갱신 후 재시도에서 발생한 업무 충돌은 원래 코드로 보존한다.


서류별 편집 초안은 독립적으로 유지한다. 한 서류를 저장해 전체 revision이 바뀌어도 다른 서류의 미저장 상태·메모를 초기화하지 않는다. 저장 중인 서류의 입력은 잠시 잠그며 성공한 서류의 초안만 비운다.

## 로컬 비회원 체험

- 홈과 로그인·회원가입 화면은 서버의 `/api/v1/meta`에서 체험 제공 여부를 확인한다. `local-sample` 프로필에서만 체험 API가 활성화된다. 사용자가 버튼을 누르면 CSRF를 포함한 `POST /api/v1/auth/trial` 요청으로 TRIAL 권한의 임시 사용자 세션을 시작한다. 예시 공고 보기와 실제 공고에서 시작한 복귀 경로가 모두 동작한다.
- 체험 기록은 소유자별 서버 데이터다. 같은 브라우저에서 새로고침해도 유지되며 마이에서 체험을 종료하면 삭제된다. 만료는 생성 후 24시간이며 만료 직후 토큰 사용을 차단하고 정리 작업이 남은 기록을 삭제한다.
- 로그인한 일반 계정의 세션을 체험으로 교체하지 않는다. 체험은 관리자 작업, 외부 알림 채널, 이메일 인증과 계정 자격 증명 변경을 사용할 수 없다. 실제 계정 로그인과 정적 시연 빌드는 기존 방식을 사용한다.

## 준비도 벽돌 일러스트 (2026-11-04)

- `HousingUI.PreparationSummary → ui.House → BrickHouse`가 동일한 서버 `progress`·`houseStage`를 전달한다. 프론트엔드는 업무 가중치나 자격 판정을 계산하지 않는다.
- `brickHouseLayout.ts`는 27개 벽돌의 좌표·색조만 정의한다. `BrickHouse`는 서버 진행률에 비례한 개수(내림)를 아래부터 표시한다. 0%는 윤곽, 100%는 전체 벽돌이며 기록을 되돌려 진행률이 줄면 표시 개수도 줄어든다. 지붕 강조는 서버 집 단계 3 이상이다. 시간 경과·벌점으로 집이 줄지는 않는다.
- 참고 이미지의 목재 지붕·벽돌·잔디·여분 자재를 벡터로 재현했다. PNG 원본을 직접 변형한 것이 아니다. `BrickHouse.module.css`는 짧은 투명도 전환과 동작 줄이기 설정을 담당하며 SVG 식별자는 인스턴스마다 독립적이다.
- 숫자가 아닌 준비도는 일러스트 대체 설명에서 확인 중으로 표시한다. 공고 제목과 준비도 일러스트의 책임은 분리하며 표시 영역의 제목을 일러스트 내부에서 가공하지 않는다.

## 공고 카드 펼침 (2026-11-05)

- 사용자 요청에 따라 공고 제목/카드 면을 누르면 같은 목록에서 미리보기를 열고, 다시 누르면 닫는다. 기존 제목 즉시 상세 이동 대신 펼친 카드의 명시적 상세 링크를 사용한다.
- `NoticesPage`가 검색 조건과 카드 ID별 단일 펼침 상태를 관리한다. `NoticeCard`의 네이티브 제목 버튼은 카드 면을 덮는 클릭 영역과 키보드 동작을 제공한다. 북마크와 펼친 영역의 링크는 별도 상호작용 계층에 둔다.
- 카드가 펼쳐진 후 `NoticeCard`가 상단 스크롤을 담당한다. `NoticePreview`의 비동기 조회 완료 시 추가 스크롤은 제거해 위치 재이동을 막는다. 마지막 카드에도 상단 이동 공간을 확보한다.
- `NoticePreview`는 공개 조회, 마감일·지역·유형 표시와 명시적 상세 진입을 담당한다. 별도의 공개 일정·선택 공고 홈 링크는 제거했다. 큰 집 그림은 화면설계서의 목록 일러스트 원칙에 따라 추가하지 않는다.

- 2026-11-05 후속: `NoticeCard → useNoticeCardScroll`이 모든 펼침 카드에 필요한 스크롤 여유를 계산한다. `NoticesPage`의 data-notice-list 및 NoticeList.module.css의 --notice-scroll-space가 레이아웃 적용점이다. 고정된 마지막 카드 margin 방식은 제거했다. ResizeObserver는 미리보기 로딩/글자 크기 변화의 공간만 갱신하며 재스크롤하지 않는다. 닫기 시 observer/frame/resize listener 및 공간을 정리한다.
- 모바일 고정 검색 영역: NoticePages가 검색·NoticeFilters·NoticeSort·공고 수를 data-notice-controls로 묶는다. useNoticeToolbarOffset은 Shell 로고 바 높이 측정만 담당하며, useNoticeCardScroll은 이 영역까지 포함해 카드 펼침 위치를 결정한다. 1024px 이상은 display:contents로 기존 좌측 필터 배치를 유지한다.
- 모바일 공고 펼침 중 검색 영역: `useNoticeToolbarReveal`은 현재 펼침의 동일성을 기준으로 검색 영역을 접고, 위 방향 휠·터치 스크롤 및 PageUp/Home/위 화살표·역방향 키보드 탐색에서 복원한다. 카드의 자동 위치 이동은 복원 신호로 사용하지 않는다. 다른 카드 선택은 다시 접고, 전체 닫기·검색 조건 변경은 복원한다. CSS는 1024px 미만에만 접기를 적용하며, 접힌 영역은 높이·빈 여백·탭 순서에 남지 않는다. 카드 스크롤 훅은 접힌 영역 높이 0을 기존 측정 경로에서 처리한다.
- `NoticeList.module.css`의 controls → controlsClip → controlsContent가 높이/투명도 전환과 내용 배치를 분리한다. desktop에서는 모두 display:contents로 기존 격자 배치를 유지한다. `useNoticeCardScroll`은 접힐 높이를 미리 예약 공간에 반영해 애니메이션 중 브라우저가 스크롤 위치를 강제로 줄이는 현상을 방지한다. reduced-motion에서는 전환을 생략한다.
- 스크롤 방향 후속: 펼친 공고가 있는 동안 `useNoticeToolbarReveal`은 위 방향에서 복원, 아래 방향에서 다시 접는다. touch 기준점은 방향 반영 시 갱신해 같은 제스처의 반전도 처리한다. 입력 필드·열린 dialog/details 내부 조작과 컨트롤의 Space 활성화는 제외한다.
