# 프론트엔드 구현 계약 v1

초기 작성일: 2026-10-30. 충돌 구간 대조일: 2026-11-24. 사용자가 미확정 기능 계약의 판단을 위임했다. PR #24·#26의 develop 반영 내용과 PR #31 작업 브랜치의 로컬 개선을 함께 보존했다. PR #31의 develop 병합과 운영 배포는 이번 작업에 포함하지 않는다. 날짜가 명시된 과거 비교 결정은 현행 구현과 구분한다. 구현 결정은 [백엔드 계약](backend-contracts.md), 이번 변경과 검증 범위는 [충돌 정리 기록](../history/entries/2026-11-24-pr31-conflict-resolution.md)을 확인한다. [개발 인수인계](../developer-handoff.md)는 해당 문서의 기준일을 따르는 과거 기록이다.

## 연결·인증

기본은 실제 /api/v1 서버다. 개발용 `npm run dev:demo` 또는 개발 환경의 `VITE_API_MODE=demo`, 별도 정적 공개 시연용 `pages-demo` 빌드에서만 시연 어댑터를 사용한다. 일반 운영 빌드는 이 어댑터를 활성화하지 않는다. 시연 데이터는 가상 공고·메모리 저장이며 새로고침 때 초기화된다. 실제 개인정보를 입력하지 않는다. 서버의 비회원 체험 세션은 이 어댑터와 별개이며 아래 로컬 비회원 체험 계약을 따른다.

Access Token은 메모리, Refresh Token은 서버 HttpOnly 쿠키. GET /auth/csrf → {token}, 변경 요청 X-CSRF-TOKEN. POST /auth/login → {accessToken,user}, POST /auth/refresh → {accessToken}, POST /auth/logout → 204. GET /users/me → User. 401은 재발급 1회만 재시도하며 동시 재발급을 공유한다. 회원가입 POST /auth/signup {name,email,password} → 빈 201 응답 후 로그인한다. 소셜 /oauth2/authorization/google|kakao → /auth/callback으로 복귀하고 me 재조회로 복원한다. 토큰을 URL로 전달하지 않는다. 회원가입 이름 2~30자, 비밀번호 10자 이상·UTF-8 72바이트 이하. 이메일 확인·재설정·탈퇴 및 프로필 변경은 [회원 기능 계약](../contracts/member-features.md)에 따라 구현했다. 기존 계정과 소셜 계정의 명시적 연결·해제는 [계정 연결 계약](../contracts/account-connections.md)에 따라 구현했다. 동일 이메일을 자동 연결하지 않으며 외부 제공자 왕복 검증은 별도다.

계정 확인 링크의 일회용 값은 `/account/confirm#verify=…`, `#reset=…`, `#delete=…`의 fragment로 전달한다. 이는 접근 토큰 전달이 아니며 사용자가 확인을 제출해야 API를 호출한다. 성공 후 fragment를 제거한다. 메일 발송 불가 환경과 Pages 시연에서는 실제 발송이 완료된 것처럼 표시하지 않는다. 비밀번호 변경·재설정·탈퇴 후 재인증하며, 일반 API의 최종 401에 세션 만료 이벤트를 받아 개인 캐시를 비운다.

2026-11-22에는 Google·Kakao 로컬 로그인, 원래 화면 복귀, 새로고침 유지와 로그아웃을 실제로 확인했다. 운영 연결·배포 및 메일 발송 검증은 각 작업 기록의 환경과 날짜를 따른다. 이번 충돌 정리는 외부 서비스 재검증을 포함하지 않는다. 이메일 인증·재설정은 단일 사용·유효기간 토큰과 발송 제한을 사용하며 실제 메일 전달에는 서버 SMTP 설정이 필요하다.

소셜 로그인 시작과 복귀 경로는 `features/auth/socialLogin`에서 관리한다. OAuth 실패 후 `/login?oauthError=...`에 돌아오면 검증한 기존 복귀 경로를 유지하고, 명시된 `returnTo`가 있으면 우선한다. 일반 로그인 진입은 이전 소셜 시도의 경로를 가져오지 않는다. callback은 복귀 경로를 최초 렌더에서 고정하고 세션 복원 성공 후에만 저장 경로를 제거한다. 실패 시 재시도 링크에 같은 안전한 경로를 전달한다. 제공자 설정이 없는 버튼은 활성화하지 않는다.

## 화면 코드 로딩

`app/App.tsx`는 화면과 준비 탭 레이아웃을 모듈 최상위 `React.lazy`로 선언한다. 해당 경로를 실제 표시할 때 필요한 모듈만 불러오며, 로그인·관리자 보호는 지연 화면 바깥에서 먼저 적용한다. `Shell`, 세션·접근성·QueryClient, `NoticeListLayout`의 방문 기록은 화면 로딩 때문에 다시 만들지 않는다. 같은 화면 파일의 복수 export는 하나의 파일을 공유한다.

`app/PageBoundary`는 공통 메뉴 안쪽에서 화면 로딩·실패를 처리한다. 실패 시 명시적인 새로고침과 홈 복귀를 제공하며 자동 새로고침/재시도는 하지 않는다. 다른 경로로 이동할 때 오류 상태만 해제하고 정상 이동에는 경계의 key를 바꾸지 않는다. `presentationStyles.ts`의 CSS 적용 순서는 유지한다. 빌드의 기본 크기 경고를 올리거나 숨기지 않고 실제 산출물 크기를 확인한다.

## 공고

GET /notices?keyword=&region=&type=&status=&sort=active&page=0&size=20. 복수값은 같은 키 반복, 필터 간 AND/같은 필터 OR. 2026-11-08 사용자 요청에 따라 기본 sort=active는 접수 중→접수 예정→기간 미확인→접수 종료·조기 마감·취소 순이며, 같은 그룹에서는 마감일 오름차순·게시일 내림차순·id 오름차순이다. 공식 상태와 확인된 접수 시각을 날짜 필터와 같은 기준으로 판정한다. 날짜가 없다고 접수 중으로 추정하지 않는다. 명시적 sort=deadline은 기존 마감일 오름차순·동률 id 오름차순, newest는 publishedDate 내림차순·동률 id 오름차순을 유지한다. 해당 날짜 미확인은 각 날짜 정렬의 뒤로 둔다. 서버에서 정렬 후 페이지를 나누며 현재 페이지를 브라우저에서 재정렬하지 않는다. 검색 대상 제목·기관·지역. 응답 {items,page,size,totalElements,totalPages}. GET /notices/filters → {regions,types}. GET /notices/:id → Notice. 필터 변경 시 page=0. 잘못된 page/size는 프론트에서 정상 범위로 보정하고 서버도 검증한다.

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
- 자격·선정 요약의 행, 배점표, 원문 대조 설명과 근거 선택은 `housing/qualificationSummary`가 소유한다. 상세와 비교는 같은 `QualificationSummaryDetails`를 표시하고 펼침·탐색만 각각 관리한다. `parseQualificationTables`는 저장된 요약의 표 파싱만 담당하며 화면 문구를 자격 판단에 사용하지 않는다. 비교 API도 응답 시 기존 `withReviewedConditions`를 호출하므로 상세 방문 이력에 따라 검토 원고가 달라지지 않는다. 출처·검수 상태 보호와 비교의 `guideState` 공개 제한은 유지한다.
- `EvidenceLink.tsx`와 `EvidenceLink.module.css`는 공식 근거의 링크 문구·아이콘·확인일자·간격을 공통 관리한다. 확인일자는 링크 아래 표시하며 화면별 스타일 재정의는 두지 않는다. 선택적 `label`은 표시 문구만 바꾸고 연결 위치는 원래 `evidence.label`과 URL로 계산한다. 비교 공고와 이전 기록은 자신의 근거·확인일자를 전달한다. 동일 출처 옆에 별도 `공식 근거 열기` 링크를 중복하지 않는다.
- 과거 비교 결정(2026-11-04): 당시 네 칸 일정 박스에서 사용자가 각 항목의 한 줄 표시를 문서 최소 글자 크기보다 우선하고, 887px 참고 이미지를 393px로 환산한 크기를 요청했다. 그 뒤 세 칸 배치와 별도 마감 행으로 개정했으므로 당시 네 칸 크기는 현행 배치 계약이 아니다. 미제공 날짜를 공란으로 두는 원칙은 유지한다.

### 일정 해석·내보내기 (현행: 2026-11-06)

- `housing/schedules.ts`가 제목 기반 일정 종류, 유효한 한국 날짜·시각, 대표 일정과 신청 기록 대상 선택을 담당한다. `noticeDetailDates`, 준비 날짜 요약, `scheduleTimelineModel`, `calendar`, 신청 기록 팝업이 이를 사용한다. API에 새 일정 종류 필드를 추가한 것은 아니다.
- 대표 발표는 서류 제출 대상자 발표 후보를 먼저 고른 뒤 해당 후보 안에서 확인된 시각을 우선한다. 접수 시작·마감은 같은 날의 기본 접수 일정 후보를 우선한다. 기본 날짜와 다른 날의 세부 일정에서 시각을 가져오지 않으며, 날짜만 있으면 임의 시각을 만들지 않는다.
- 공개 일정의 검수 범위는 [백엔드 공개 일정 계약](backend-contracts.md#공개-일정)을 따른다. `currentPreparationSchedules`는 현재 Notice가 있으면 그 공개 `schedules`를 사용하고, 아직 없으면 AVAILABLE인 준비 스냅샷만 대체 조회한다. 공고 버전이 달라져도 과거 스냅샷은 수정하지 않는다. 타임라인의 개인 완료 표시는 같은 버전에서 일정 ID·제목·날짜까지 일치할 때만 연결한다.
- `calendarItems`는 현재 공개 일정을 내보낸다. 같은 날 기본 접수 일정과 확인된 시각을 가진 동등한 세부 일정은 하나로 합치며 기본 `start`/`end` 식별자는 유지한다. 다른 지역·별도 시각·서류 마감 등 서로 다른 일정은 같은 날이어도 남긴다. 확인된 시각은 ICS에 반영하고 날짜만 있는 항목은 종일 일정으로 유지한다. ICS 생성·문자 이스케이프·줄 접기는 `calendar.ts`, 선택 팝업은 `CalendarExport`의 책임이다.

## 개인 준비

### 신청자 상황 질문과 결과 안내 (2026-11-22)

- 입력 질문은 공고 조건 설명과 역할을 구분한다. `preparation/applicantQuestion`이 입력 제목·도움말을 결정하고 `QuestionPrompt`, `QuestionSteps`, `QuestionInput`이 공유한다. 사실 선택이 명시된 계약만 직접 상황 질문으로 바꾸며 복합 기준 확인을 단일 사실 질문으로 바꾸지 않는다. 참고할 기준·예외·숫자·공식 근거는 기존 `conditionOverview`로 보존한다.
- `housing/questions.applicantQuestions`는 명시적인 단순 안내 확인 중 서류·답변 의존성이 없는 항목만 질문에서 제외한다. 검수 필요·숫자·날짜·기본 YES/NO·연결된 항목은 보존한다. `answerableQuestions`는 여기서 공식 검수 항목을 제외한 실제 입력 목록이다. 입력, 결과 수, 준비 개요, 이어하기 경로와 서버·시연 준비도는 같은 구분을 따른다. 기존 답변과 보관된 스냅샷은 삭제하지 않는다.
- `PersonalizedPreparationGuide`는 내 준비와 일회성 결과에서 실제 선택한 신청 유형·방법·직접/대리 신청과 연결된 서류를 함께 표시한다. 확인형 답변에서 신청 방법을 추론하거나 검수 중·모름·모순을 확정하지 않는다. 질문에서 제외한 참고 안내도 같은 버전의 근거와 함께 제공한다. 서류 필요 여부는 기존 서버 계산값을 사용하고 서류 상세·발급 링크는 기존 공통 표시를 유지한다. 일회성 답변은 기존처럼 페이지 생애에만 보관한다.
- 원래 질문은 있으나 전부 단순 참고 안내로 분리되어 답변할 질문이 없으면 질문 영역은 할 일이 없는 것으로 계산한다. 원래 질문 데이터 자체가 없는 경우의 0점 정책은 유지한다. 이 계산은 답변·읽음·자격 통과 기록을 새로 생성하지 않는다. 현재 준비 목록의 진행률도 얇은 정책 입력만 조회해 서버 공통 계산을 사용하며 보관 당시 스냅샷은 변경하지 않는다.

### 답변에 맞춘 내 준비·일회성 결과·이전 기록·공고 비교 표시 범위 (2026-11-22)

입력 단계의 제목·도움말·선택지는 `preparation/applicantQuestion`에 전체 질문과 현재 답변 문맥을 전달한다. `QuestionPrompt`, `QuestionInput`, `QuestionSteps`가 같은 문맥을 사용하고 저장형/일회성 흐름을 분리 구현하지 않는다. 현재 확인한 포항 원문의 유형별 주택·소득재산 분기는 `pohangApplicantQuestion`이 소유하며 기존 `pohangReviewedEntry`의 원문·reason 확인과 선택지 의미 검증을 공유한다. 청년 본인 주택과 자동차 심사 범위를 혼동하지 않는다. 예외가 있는 MEETS를 단순 무주택 여부로 재해석하지 않고 질문 ID·value·requiresFollowUp을 유지한다.

`updateApplicantAnswer`는 확인한 유형 선택이 바뀌면 이미 답한 주택·소득재산 기준만 UNKNOWN으로 재확인한다. 저장형은 해당 답변 묶음을 기존 revision API에 보내 성공 후 이동하고, 다른 질문에 남은 draft도 같은 규칙으로 재기반한다. 미확인 전용 흐름에는 새로 재확인이 필요한 질문과 유형 변경으로 다시 나타난 질문을 포함한다. 일회성은 메모리 답변에 같은 전환을 적용한다. 새 API/서버 자격 판정·자동 긍정 답변은 추가하지 않는다. 원문 변경·다른 공고·검수 중·유형 미확정에는 기존 일반 질문을 보존하며, 산업근로자의 주택 범위는 혼인 정보 없이 확정하지 않는다.

사용자 후속 결정(2026-11-22): 입력 목록은 `housing/preparationApplicability`의 `applicantInputQuestions`로 공통·선택 유형 질문만 선별하고 기존 `answerableQuestions`로 안내 전용 항목을 구분한다. 유형 판정은 결과 표시 정책과 공유하며, 확실히 다른 유형의 전용 질문을 과거의 모름·불충족 답변 때문에 계속 질문하거나 진행률에 포함하지 않는다. 검수·실제 답변 모순·미확정 부모 유형은 보존한다. 전체 질문·원래 답변은 삭제하지 않으며 타입 복귀 시 해당 질문이 다시 나타난다. 두 입력의 이전/다음·질문 개수는 선택된 목록을 사용하고 일회성 현재 위치는 질문 ID로 유지한다.

여러 공고의 유형 선택지는 `preparation-audience-vocabulary.json`의 명시된 표시명·값과 문구 조합으로 정규화한다. 입력에서는 유일하게 확인된 유형 선택 질문을 첫 항목으로 옮기고 조건 탭의 ‘준비 이어하기’도 같은 순서로 진입한다. 가점·순위 유형·혼인 사실 확인·서로 다른 공급 축은 분리하고, 미확정 선택과 여러 후보가 있으면 범위를 확정하지 않는다. 배타적 유형 제목에만 같은 부모의 비해당 충돌 의존성으로 대상 범위를 넓히며, 신혼/예비신혼·청년/사회초년생 등 공통 어휘의 단방향 겹침을 보존한다. 다른 유형 전용 질문에 직접 연결된 서류도 `visiblePreparationDocuments`가 같은 정책으로 선별한다. 연결되지 않은 공통 서류, 다른 답변 의존성에서 요구되거나 아직 확인되지 않은 서류, 공식 검수와 실제 충돌은 유지하며 원래 필요도·메모·답변을 변경하지 않는다.

- `housing/preparationApplicability`가 네 화면에 표시할 조건·서류·접수 회차의 단일 기준을 소유한다. `visiblePreparationQuestions`, `visiblePreparationDocuments`, `preparationScheduleSelection`을 각각 사용하며 연결 서류가 비해당인지도 같은 내부 판정을 공유한다. 같은 조건·회차 정책의 Java `PreparationApplicability`와 공유 사례 `data/contracts/preparation-applicability.json`을 대조한다. 유형·채널·명확한 서류 안내의 공통 어휘는 `preparation-audience-vocabulary.json`이 소유한다.
- `reviewedPreparationScope`는 현재 공고의 검수 보호 정보와 서류-질문 연결을 저장 답변에 합친 읽기 전용 자료를 만든다. 비교 모델과 현재 일정 계산이 이를 공유하고 기존 질문·답변·서류 기록은 수정하지 않는다. 현재 공고를 사용하는 호출자는 공고·버전·공개 상태를 먼저 확인한다. 이전 기록과 일회성 결과에는 다른 버전의 최신 공고를 합치지 않는다.
- 내 준비 서류 탭·개요·맞춤 참고 안내, 일회성 결과, 이전 기록, 비교의 서류 목록은 `visiblePreparationDocuments` 결과에서 시작한다. 화면별 필수/조건부·추가 확인·공식 검수 그룹과 확정 추천은 별도 표현 책임이며 새로운 제외 기준을 두지 않는다. 시연 준비 평가도 같은 표시 목록을 사용한다. 필요도 생산·저장 허용·서버 진행률 및 보관 당시 진행률을 프런트 표시 함수로 대체하지 않는다.
- 명시적인 비해당 답변, 기존 서류 필요 여부와 연결된 제출 안내, 선택지에 실제 존재하는 동일 분류의 전용 조건만 제외한다. 일반공급/주거약자와 청년/신혼 등 서로 다른 분류를 비교하지 않는다. 기준 불충족은 비해당과 다르다. 미입력·모름·무효 답변·추가 확인·모순·공식 검수 항목과 공통/비교 안내는 보존한다.
- 조건 탭의 수치·결과·참고 안내와 준비 개요는 같은 범위를 사용한다. 답변 수정 화면에서는 원래 질문에 다시 답할 수 있다. 서류는 서버의 REQUIRED/CONDITIONAL/UNKNOWN/NOT_APPLICABLE 구분을 재사용한다. 화면 필터 때문에 답변·서류 메모·일정 완료 기록을 삭제하지 않는다.
- 일회성 `OneTimeCheckResult`도 같은 정책으로 조건 목록과 완료/추가 확인 개수를 계산한다. 같은 버전의 공개 서류 템플릿에서 서류-질문 연결을 구성해 목록과 `PersonalizedPreparationGuide`에 함께 전달한다. 서류 필요도는 기존 결과를 사용하며 비해당만 제외한다. 답변 수정은 원래 질문을 유지하고, 일회성 답변·결과를 저장하거나 공유 캐시에 넣지 않는다.
- `currentPreparationScheduleSource`가 같은 공고 버전의 검수된 일정과 명시적 신청 방법 답변을 대조한다. 공식 `접수 회차 ·` 형식의 전용 접수 일정만 선별하고 혼합 채널·부정 안내·계약·동호 지정·서류 제출은 함부로 제외하지 않는다. 확인된 선택 방식의 시작·마감은 대표 날짜/시각에도 적용한다. 해당 방식의 시작 또는 마감이 없으면 다른 방식의 날짜를 대입하지 않는다. 대표 날짜에 시각이 확인되지 않은 회차가 함께 있으면 대표값은 날짜만 표시하고 개별 회차의 확인된 시각은 보존한다. 타임라인·다음 일정·내 준비 날짜 요약·캘린더는 같은 출처를 사용한다. 최신 공고의 버전/검수 상태가 달라지면 옛 답변으로 새 일정을 숨기지 않는다.
- 현재 준비 진행률·다음 행동도 비해당 항목을 완료 의무로 남기지 않으며 목록과 상세가 최신 상태를 동일하게 확인한다. 공고 자체의 일반 상세는 개인화로 재작성하지 않는다. 시연도 각 공고의 서류·일정을 준비 스냅샷에 보관하고 그 스냅샷 연결 규칙으로 답변을 평가한다.
- 이전 준비 기록의 `HistorySnapshot`은 보관된 질문·답변·서류 연결·필요도·검수 상태만 사용해 같은 표시 정책을 적용한다. 당시 공통 참고 안내도 유지한다. `snapshotPreparationScheduleSource`는 저장 일정만 받아 현재 준비와 같은 내부 계산으로 해당 회차와 대표 날짜·시각을 선택한다. 최신 공고·현재 답변을 조회하지 않으며 보관 원본·당시 진행률·메모·완료값은 재계산하거나 수정하지 않는다. 정보가 부족하거나 미확인인 기록은 함부로 제외하지 않는다.
- 공고 비교·관심 공고 비교는 `useComparisonPreparations`에서 로그인 사용자 목록과 선택한 두 공고를 연결하고 일치하는 준비 상세만 조회한다. 공고 ID·버전·안내 공개 상태를 확인하고 실제 저장 답변이 있을 때만 `personalizeComparison`으로 조건·서류·일정을 선별한다. 서류 템플릿뿐 아니라 요약 항목·생략 ID·개수에도 같은 비해당 범위를 적용한다. 미대응 공개 항목과 미확인·공식 검수 항목은 남긴다. 대표 접수 기간과 세부 회차는 같은 `currentPreparationScheduleSource`를 사용한다.
- 개인화 결과는 표시용 파생값이며 `notice-comparison` 공개 캐시에 저장하지 않는다. `usePreparationRecord`는 사용자별 상세 키를 유지하고 목록보다 낮은 revision의 캐시만 재조회한다. 재조회 실패·서버의 구형 응답은 자동 반복하지 않고 전체 안내와 재시도를 표시한다. 저장 답변이 없거나 최신 공고와 버전이 다르면 전체 안내를 유지한다. 일회성 확인의 답변이나 다른 공고·다른 계정의 답변을 대입하지 않는다.

GET /preparations → Preparation[], GET /preparations/:id → Preparation. POST /preparations {noticeId,version} → Preparation. 같은 회원·공고 중복 요청은 기존 건 반환. PUT /preparations/:id/answers {revision,answers}; PATCH /preparations/:id/documents/:documentId {revision,state,note}; PATCH /preparations/:id/schedules/:scheduleId {revision,recorded}. 변경 응답은 새 Preparation 전체다. revision 불일치 409, 타인 기록은 404, 인증 없음 401. 낙관적 완료 표시 없이 성공 응답 후 캐시 갱신. 모름 UNKNOWN과 미응답(키 없음)을 구분한다. 서류 필요 여부와 준비 상태를 별도 관리한다. 사용자 증빙 파일 업로드 없음.

초기 계산 초안: 답변 확인 30%, 필요한 서류 완료 50%, 공식 일정 확인 기록 20%. 모름은 답변 완료 점수 제외하며 경고 표시. 조건부 해당 여부 미확인은 완료 처리하지 않는다. 원래 항목이 없는 영역은 자동 완료하지 않고 0점이다. 원래 질문·일정이 있지만 안내 역할 분리나 확실한 비해당 제외로 확인 대상이 모두 없어진 경우에는 그 영역을 완료 대상으로 남기지 않는다. 정수 반올림하되 미완료 항목이 남아 있으면 최대 99%로 제한한다. houseStage는 0~19=1,20~39=2,40~69=3,70~99=4,100=5. 이는 준비 작업 기록이며 자격·신청 완료 판정이 아니다. 서버가 progress/houseStage/warnings/nextAction을 반환한다. 현행 계산 책임은 백엔드의 순수 `PreparationRules`이며 `PreparationService`가 저장·동시 수정·기준 갱신을 담당한다. 실제 UI는 계산하지 않는다. 실제 공고 검증 후 계산 기준 버전을 확정한다.

서버가 없는 시연 환경은 `demoPreparationRules`의 순수 계산을 `demo.ts` 요청·저장 어댑터에서 호출한다. Java와 TypeScript 구현은 [공유 기대값 사례](../../data/contracts/preparation-rules.json)를 각각 검증한다. 이 사례는 계산과 상태 전이의 일치 확인용이며 실제 서버의 인증·소유권·revision·DB 통합 검증을 대신하지 않는다. 운영 및 비회원 체험은 서버 응답을 그대로 사용한다.

시연 화면의 요청별 180ms 지연은 `demoDelay.waitForDemoResponse`가 담당한다. App 사용자 흐름 테스트에서는 이 지연 경계만 즉시 완료되도록 대체하고, 실제 시연 어댑터의 인증·저장·검증과 오류/복구 단정은 유지한다. 테스트를 위해 제품의 로딩 표현이나 비동기 조회 대기 제한을 변경하지 않는다.

변경 검토 중에는 기존 기준 버전의 개인 기록을 조회하고 수정은 409로 보류한다. 기존 답변·완료 상태는 보존한다. 다음 행동은 안내를 사용할 수 없으면 공고 변경 확인을 우선하고, 사용할 수 있으면 재확인 서류 → 미응답/추가 확인 답변 → 미완료 서류 → 신청 일정 확인 순서다. 공식 사이트 이동은 완료 기록을 변경하지 않는다.

### 내 준비 개요 표시 모듈 (현행: 2026-11-06)

완료 안내(2026-11-23): `nextAction.completed=true`는 현재 안내가 유효하고 재확인·미응답·공식 확인 항목 없이 기존 준비 계산이 100%인 경우에만 반환한다. 홈과 내 준비는 `NextAction`을 공유하여 초록 체크와 `준비 기록 완료`, 완료한 일정으로의 이동을 표시한다. 완료값 취소·답변 변경·정정 시 다시 서버가 정한 할 일을 표시한다. 항목이 없는 데이터나 다른 유형의 비해당 기록을 임의 완료하지 않으며 공식 접수·선정 완료를 뜻하지 않는다. 기존 응답과 보관 기록에 completed가 없으면 false로 취급한다.

- `PreparationTabLayout`은 내 준비 목록과 개요·조건·서류·일정 화면의 공통 상단을 유지한다. `PreparationHeader`의 준비 건 선택기와 `PreparationNav`는 스크롤 영역 밖에 두고, 아래의 현재 탭만 스크롤·교체한다. 목록/선택은 `useActivePreparation`에 위임하며 개별 건 URL에서는 URL의 ID를 우선한다. 준비 건 변경 시 현재 탭을 유지하고 미저장 변경을 확인한다. 진행률 100%는 **준비 기록 완료**다.
- `PreparationOverview`는 요약, 다음 행동, 여정, 공식 신청·신청 기록 버튼과 계속하기를 조합한다. `PreparationGuidance`는 새 안내 갱신·검수 경고를 담당한다. 홈의 `NextAction`은 자체 CSS 격자로 배치하고, 개요의 다음 행동은 `Overview.module.css`가 배치한다. 두 화면 모두 서버가 반환한 문구·이유·경로를 표시하며 다음 행동 정책을 계산하지 않는다.
- `PreparationOverviewSummary`는 `PreparationIdentity`와 `PreparationDates`로 공고 제목·안내 버전·마감·날짜를 표시하고 `PreparationProgress.House`에 서버 `progress/houseStage`를 전달한다. 준비도 설명은 홈·개요 모두 같은 접기 동작을 사용한다. `features/preparation/PreparationUI.PreparationSummary`는 호출자가 전달한 공고 식별 영역을 조합하며 이전 화면별 fallback 분기를 두지 않는다.
- `/preparations/:id/conditions`는 내 준비의 조건 탭, `/preparations/:id/conditions/result`는 독립적인 내 조건 확인 결과 화면, `/preparations/:id/conditions/edit`는 질문 편집 흐름이다. `preparationTabRoutes`는 URL로 탭을 판단하며 `location.state`로 같은 URL의 화면 종류를 바꾸지 않는다. 복귀 위치 전달은 별도 탐색 상태로 유지한다.
- `Overview.module.css`, `PreparationHeader.module.css`, `PreparationOverviewSummary.module.css`는 내 준비 개요에만 적용한다. 카드 제목 18px·보조/날짜 14px·버튼 16px·페이지 제목 24px을 유지한다. 이미지의 가상 날짜·65%·김 이름은 실제 값으로 대체하며 글자를 줄여 한 화면에 억지로 맞추지 않는다.
- 공식 사이트 링크는 읽기 이동이다. 신청 여부는 개요의 `ApplicationRecordDialog`에서 확인 후 기록한다. 여정의 공식 신청 단계는 `/preparations/:id?application=record`로 같은 팝업을 열고, 닫으면 해당 쿼리를 replace로 지운다. 예전 일정 화면의 `#application` 경로는 사용하지 않는다. 팝업을 여는 것만으로 기록하지 않으며, AVAILABLE인 준비 스냅샷의 신청 기록 대상만 수정한다. 조건·서류·일정·계속하기에는 선택한 준비 ID/서버 다음 행동 경로를 전달한다.

## 관리자·가이드

GET /guides?category=&keyword=&page=0 → {items,page,hasMore,totalPages}. GET /admin/reviews → Review[]. PATCH /admin/reviews/:id {basic,guide,reason,revision}. basic=PASS|HOLD, guide=PASS|HOLD|UNSUPPORTED. 기본 정보 HOLD면 양쪽 공개 금지. 보완 사유 필수. 검수자·시각·이력은 서버가 기록. GET/POST /admin/collections는 실행 목록/실행 요청이며 수집 성공을 공개 완료로 취급하지 않는다. 서버에서 ADMIN 권한 필수.

## UI와 구현 경계

### 모듈·스타일 소유권 (현행: 2026-11-07)

- 의존 방향은 `app → pages → features → shared`다. 인증 컨텍스트·훅은 `features/auth/useSession.ts`, 세션 조회·만료 캐시 정리는 `features/auth/SessionProvider.tsx`가 소유한다. App과 각 화면은 이 기능을 사용하며 feature에서 app의 세션 모듈을 역참조하지 않는다. 삭제한 `app/useSession.ts`, `app/session.tsx`를 호환용 재수출 경로로 남기지 않는다.
- 기존 `shared/ui/HousingUI.tsx`는 제거했다. 공고 날짜·마감·검수 배지는 `features/housing/NoticeFacts.tsx`, 출처는 `EvidenceLink.tsx`, 준비 여정·지표·문맥·요약은 `features/preparation/PreparationUI.tsx`, 준비도 표시는 `PreparationProgress.tsx`가 담당한다. `shared/ui`에는 `Disc`, `Status`, `OfficialLink`, 기본 버튼·접기 등 기능 데이터를 조회하지 않는 공통 표시만 둔다.
- `shared/styles/Product.module.css`는 제거했다. 공통 카드·버튼·텍스트·배치 규칙은 `Primitives.module.css`, 기능 배치는 `Auth`, `NoticeFacts`, `PreparationUI`, `PreparationWorkspace`, `Conditions`, `Documents`, `Schedule`, `Home`, `Guide`, `MyPage`의 기능별 CSS와 `app/Shell.module.css`가 소유한다. `bindClasses`는 공통 클래스와 호출자가 명시한 CSS 모듈만 합치며 모든 기능 스타일을 공통 모듈에 다시 등록하지 않는다.
- 다른 CSS 모듈에서 기존 클래스 식별자가 필요하면 `*ClassNames.module.css`의 빈 선택자 계약을 `@value`로 참조한다. 이 파일에는 스타일 선언을 넣지 않는다. 실제 선언이 있는 모듈을 교차 참조해 CSS가 뒤늦게 재적용되는 것을 피한다. `main.tsx`의 첫 import인 `app/presentationStyles.ts`가 공통·기능 기초 스타일을 컴포넌트별 세부 조정보다 먼저 불러오므로 이 순서를 유지한다.
- `npm run check:boundaries`는 TypeScript 모듈 해석을 사용해 계층 역참조·실행 모듈 순환·strict 설정을 검사한다. 타입 전용 의존과 실행 의존을 구분하며 문자열 동적 import·재수출도 관찰한다. 이 검사와 CSS의 실제 화면 검증은 별개이고 실행 결과는 작업 기록에 남긴다.

React Router 경로는 화면설계서 기준. 서버 상태는 TanStack Query. 쿼리 키에 사용자 ID·공고 ID·검색 URL을 포함한다. 로그아웃 성공 후 개인 캐시 제거. 실패 시 입력을 유지하고 재시도. 404 경로와 인증 필요·권한 부족·빈 결과를 표시한다. 관심 공고, 비교, 가이드 검색, 알림 설정·읽음, 캘린더 내보내기, 보관·복원·메모 연결, 프로필·계정 관리는 [회원 기능 계약](../contracts/member-features.md)에 따라 실제 API 또는 명시적 시연 어댑터를 사용한다. 이전의 ‘준비 중’ 안내만 있는 단계와 구분한다. 외부 메일·푸시 제공 여부는 서버 설정과 브라우저 지원·권한에 따른다.

2026-11-07 외부 연결 보완: 공고 접수 상태·시각은 서버의 `applicationTiming`을 사용하고, 전환 시각에 재조회한다. 시각이 null이면 제목에서 다시 추정하지 않는다. 목록은 빈 OPEN 결과에도 새 접수를 반영하도록 최대 60초 간격으로 갱신한다. 상세 계약은 [접수 시각](../contracts/application-timing.md)을 따른다. 알림 채널은 API·브라우저 구독·화면 상태 훅으로 분리하고 비동기 구독 중 계정 변경 시 이전 작업을 중단한다. 관리자 근거 점검은 [근거 링크 점검](../contracts/evidence-link-checks.md)의 결과를 표시하며 사람 검수를 자동 완료하지 않는다.

SEED 공식 React 수동 설치와 ActionButton 공개 컴포넌트를 사용하며 브랜드 CSS는 제품 레이어에서 적용한다. 기타 UI는 semantic HTML과 제품 전용 CSS를 사용한다. 공식 스니펫 설치를 수행했다고 주장하지 않는다.

근거: https://seed-design.io/llms/react/getting-started/installation/manual.txt 및 https://seed-design.io/llms/react/components/action-button.txt . 상세 DTO의 현재 단일 소스는 frontend/src/features/housing/types.ts이다.


## 실제 연동 보완
- 신청 날짜·게시일은 확인되지 않으면 null이다. 신청 시작·종료일 중 하나라도 불명확하면 status=UNKNOWN, 화면은 일정 확인 필요로 표시한다. 준비 안내 통과에는 질문·서류·일정과 확인된 신청 기간이 필요하다.
- 관리자 원고 편집과 수집 시작 페이지, 서버 샘플 표시, 활성 소셜 제공자 목록 API를 추가했다.
- 갱신 요청에 만료된 접근 토큰을 보내지 않는다. 갱신 후 재시도에서 발생한 업무 충돌은 원래 코드로 보존한다.


서류별 편집 초안은 독립적으로 유지한다. 한 서류를 저장해 전체 revision이 바뀌어도 다른 서류의 미저장 상태·메모를 초기화하지 않는다. 저장 중인 서류의 입력은 잠시 잠그며 성공한 서류의 초안만 비운다.

## 로컬 비회원 체험

- 홈과 로그인·회원가입 화면은 서버의 `/api/v1/meta`에서 체험 제공 여부를 확인한다. `local-sample` 프로필에서만 체험 API가 활성화된다. 사용자가 버튼을 누르면 CSRF를 포함한 `POST /api/v1/auth/trial` 요청으로 TRIAL 권한의 임시 사용자 세션을 시작한다. 체험 추천은 검수된 실제 공고를 사용하며, 실제 공고에서 시작한 복귀 경로를 유지한다. 가상 공고는 별도 정적 시연 빌드에서만 제공한다.
- 체험 기록은 소유자별 서버 데이터다. 같은 브라우저에서 새로고침해도 유지되며 마이에서 체험을 종료하면 삭제된다. 만료는 생성 후 24시간이며 만료 직후 토큰 사용을 차단하고 정리 작업이 남은 기록을 삭제한다.
- 로그인한 일반 계정의 세션을 체험으로 교체하지 않는다. 체험은 관리자 작업, 외부 알림 채널, 이메일 인증과 계정 자격 증명 변경을 사용할 수 없다. 실제 계정 로그인과 정적 시연 빌드는 기존 방식을 사용한다.

## 준비도 벽돌 일러스트 (2026-11-04)

- 현재 경로는 `features/preparation/PreparationUI.PreparationSummary → PreparationProgress.House → shared/ui/BrickHouse`이며 동일한 서버 `progress`·`houseStage`를 전달한다. 프론트엔드는 업무 가중치나 자격 판정을 계산하지 않는다.
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

## 가이드 대량 자료 로딩 (2026-11-11)

GuidePage → features/guide/useGuides → housing/api.guides로 페이지20개씩 조회한다. 화면 내 전체 자료 검색을 제거하고 서버에 category·keyword·page를 보낸다. 현재 페이지만 렌더링하며 다음 페이지는 기존 목록을 교체한다. 검색 입력은250ms 대기, 검색/주제 변경은 첫 페이지 복귀, 선택한 주제 재선택 해제는 보존한다. 체험 요청도 동일한 `{items,page,hasMore,totalPages}` 계약을 따른다.

2026-11-21 가이드 원고는 `data/guides/guide-catalog.json`의 공식 자료 검토 FAQ로 대체했다. 개별 공고의 조건·서류·일정을 일반 FAQ로 복제하지 않는다. `Guide`는 기존 필드에 `scope`와 `additionalEvidence`를 추가한다. `GuideAnswer`가 문단·목록·적용 범위·모든 공식 출처와 확인일을 표시하며 출처는 공통 `EvidenceLink`를 사용한다. body는 HTML이나 실행 가능한 Markdown으로 해석하지 않는다. `demoGuides`도 같은 원고를 읽고 같은 검색·페이지 제한·잘못된 요청 거절을 적용한다.

공식 출처 자동 재확인 결과는 선택 필드 `freshness`로 받는다. `GuideFreshnessNotice`는 `CURRENT`에서 추가 경고를 표시하지 않으며 `RECHECK`·`UNREACHABLE`에서 기존 안내 유지와 재확인 필요를 고정 문구로 설명한다. 마지막 내용 확인과 점검 시도를 구분한다. 서버의 자유 문구나 HTML을 렌더링하지 않는다. `useGuides`는 현재 페이지만 60초 신선도로 캐시하고 창 복귀·재연결 및 전경 5분 간격으로 갱신한다. 갱신 중 펼친 질문은 유지한다. 서버 없는 체험은 정적 원고의 원래 확인일을 사용하며 자동 재확인을 마친 것처럼 표시하지 않는다.

## 목록과 선택한 상세 분리 (2026-11-11)

공고 카드/미리보기는 NoticeListItem을 사용하며 펼칠 때 상세 API를 호출하지 않는다. 실제 상세 이동에서 useNotice가 전체 자료를 받는다. 필터 후보는 60초, 목록과 공고 상세는 15초 동안 재사용하고 수동 새로고침·접수 경계 갱신은 유지한다.

usePreparationList는 사용자별 작은 목록을 홈·내 준비·마이·공고 상세·보관 선택기에 공유한다. useActivePreparation은 선택할 목록 항목만 결정한다. 홈과 내 준비 개요는 usePreparationRecord로 해당 ID 하나를 읽으며, 명시적 경로의 세부 탭도 같은 상세 쿼리를 공유한다. 세부 탭 이동 시 무관한 준비 건을 미리 읽지 않는다. 목록과 상세는 15초 동안 재사용하며 저장 응답은 상세 캐시에 즉시 반영하고 목록을 무효화한다. 시작 명령의 최신 목록 확인, 저장 실패 시 선택 보존, 보관·삭제·복원 무효화, 로그아웃 시 사용자 캐시 제거는 그대로 유지한다. 데모도 목록 응답에서 상세 데이터를 제외해 UI가 전체 목록에 의존하는 회귀를 드러낸다.

## 포항블루밸리 개별 조건 설명 (2026-11-21)

`conditionOverview`의 `conditionHeading`과 검수 상세 선택 경로는 포항의 `pohangConditionDetail`을 공유한다. `ConditionSummary`가 상세·비교·내 준비 결과·이전 기록·일회성 결과의 공고 설명을 표시한다. 입력 화면의 `QuestionPrompt`는 이 설명을 참고 자료로만 사용하며 질문 제목·단계 제목은 `applicantQuestion`으로 구분한다. 포항 상세는 기본 기준 6개와 서류 안내 9개 모듈로 나누며 기존 `StructuredConditionDetail`의 표·목록·순서·근거 렌더링을 사용한다.

질문 ID만으로 설명을 덮지 않는다. 검토한 기존/신규 reason, 공식 PDF URL·정확한 쪽수·제공된 SHA를 확인하며 원문 변경 또는 확인 필요 항목에는 공통 표시로 돌아간다. 저장된 질문·답변·선택지·공식 원문은 표시 함수에서 변경하지 않는다. 새 질문의 답변 의미와 서류 연결 변경은 새 안내 버전으로 게시한다. 다른 공고에 포항의 수치나 예외를 복사하지 않는다.

## 답변 모순 표시 (2026-11-21)

질문에 저장된 `answerDependencies`를 `housing/answerDependencies`에서 평가하고, `questions.needsFollowUp` 및 `conditionAnswerState`에 전체 답변·질문을 전달한다. 내 준비·일회성 결과·이전 기록의 배지, 추가 확인 개수, 진행률, 미확인 질문 이동은 같은 결과를 사용한다. `AnswerConflictNotice`는 입력과 결과에서 동일한 이유를 표시한다. 공식 검수 필요는 사용자 답변 모순보다 우선한다.

모순된 선택을 해도 답변을 자동으로 고치지 않는다. 자동 다음 이동을 멈춰 안내를 읽을 수 있게 하고, 사용자가 ‘다음’을 누르면 해당 답변과 추가 확인 상태로 진행한다. 새 모순 메시지가 나타나면 `QuestionPrompt`가 공통 0.75초 스크롤로 질문 상단을 보여준다. 같은 경고 재렌더에는 이동을 반복하지 않고 입력 초점을 유지한다. 답변 수정 시 첫 미응답 또는 추가 확인 질문으로 돌아간다. 답변을 바로잡으면 경고와 추가 확인 상태를 다시 계산한다. `demoPreparationRules`도 같은 의존 조건으로 필수 서류를 유지한다. 새 규칙이 없는 과거 기록은 당시 의미를 보존한다.

### 가이드 페이지 표시 통일 (2026-11-21)

가이드의 `totalPages`는 분류·검색 후 결과로 계산한다. `hasMore`와 페이지당20개 응답은 유지하며 서버 없는 demo도 같은 계약을 따른다. 공고와 가이드의 페이지 이동은 `shared/ui/Pagination`으로 통일해 이전·현재/전체페이지·다음을 표시한다. 전체 결과를 추가 요청해 페이지 수를 추정하지 않는다.

가이드 이전·다음 클릭 시 새 페이지가 렌더링된 뒤 질문 영역 상단을 가이드 헤더 아래8px로 정렬한다. 전환 동안 영역 높이·기존 여백을 보존하고 해당 영역의 브라우저 스크롤 앵커 추적을 잠시 중지한다. 공통 `animateScroll`의750ms 동작을 사용하고 완료·사용자 중단·이탈 때 임시 공간을 해제한다. 동작 줄이기는 즉시 이동하며 첫 조회·자동 재조회에는 페이지 이동 스크롤을 실행하지 않는다.

공고와 가이드는 `shared/ui/usePageScroll`로 위 전환을 공유한다. 공고는 고정 헤더와 검색 도구 높이를 함께 고려해 첫 공고 위를 정렬한다. 페이지 버튼에서만 이전 카드 선택·현재 복원 상태를 해제하며 상세에서 돌아오는 위치 복원은 유지한다. 가이드 문장은 `GuideAnswer`에서 완결 문장별로 개행하며 기존 원고·숫자·출처·적용 범위를 수정하지 않는다. 같은 줄바꿈은 본문·목록 항목·적용 범위에 적용한다.

자동 검수로 갱신된 가이드도 응답을 렌더링할 때 같은 `GuideAnswer` 규칙을 적용한다. 기존 FAQ의 ID를 유지한 채 본문·목록·적용 범위가 바뀌어도 문장별 줄바꿈을 다시 계산한다. 원고에 미리 줄바꿈을 저장하거나 AI 응답의 줄바꿈 형식에 의존하지 않는다. 화면 복귀·재연결·주기 재조회로 들어온 새 내용도 동일하며, 날짜·시각·수치·예외·출처 링크와 확인일은 응답 그대로 보존한다.

가이드 최초 진입과 주제·검색·페이지 전환 시 질문 카드는 모두 접힌 상태로 시작한다. `GuidePage`는 조회 조건과 질문 ID를 함께 카드 키로 사용해 다른 주제에도 같은 질문이 포함되거나 캐시된 페이지로 돌아오더라도 이전 펼침 상태를 가져오지 않는다. 같은 조건에서 내용만 자동 갱신될 때는 사용자가 펼쳐 둔 상태를 유지한다.

질문 카드는 `details name="guide-questions"`로 묶어 한 번에 하나만 펼친다. 제목·카드 여백·키보드로 새 질문을 열면 이전 질문이 자동으로 접히며, 현재 질문을 다시 닫으면 모두 접힌 상태가 된다. 공식 출처 링크는 펼침 상태를 바꾸지 않는다. 이전 답변이 접히는 동안 카드 위치가 변하므로 `alignQuestion`은 공통 750ms 스크롤의 목표 위치와 필요한 하단 공간을 매 프레임 다시 계산한다.
