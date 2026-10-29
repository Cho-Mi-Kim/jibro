# 프론트엔드 구현 계약 v1

작성일: 2026-10-30. 사용자가 미확정 기능 계약의 판단을 위임했다. 이 문서는 프론트엔드가 채택한 백엔드 구현 대상 계약이며 실제 서버 구현 완료를 뜻하지 않는다.

## 연결·인증

기본은 실제 /api/v1 서버다. 개발 때만 VITE_API_MODE=demo로 명시적으로 시연 어댑터를 사용한다. 시연 데이터는 가상 공고·메모리 저장이며 새로고침 때 초기화된다. 실제 개인정보를 입력하지 않는다. 운영 빌드에는 데모 분기를 활성화하지 않는다.

Access Token은 메모리, Refresh Token은 서버 HttpOnly 쿠키. GET /auth/csrf → {token}, 변경 요청 X-CSRF-TOKEN. POST /auth/login → {accessToken,user}, POST /auth/refresh → {accessToken}, POST /auth/logout → 204. GET /users/me → User. 401은 재발급 1회만 재시도하며 동시 재발급을 공유한다. 회원가입 POST /auth/signup {name,email,password} → 204 후 로그인한다. 소셜 /oauth2/authorization/google|kakao → /auth/callback으로 복귀하고 me 재조회로 복원한다. 토큰을 URL로 전달하지 않는다. 회원가입 이름 2~30자, 비밀번호 10~72자. 소셜 앱·이메일 검증·재설정 서버는 후속 구현이다.

## 공고

GET /notices?keyword=&region=&type=&status=&sort=deadline&page=0&size=20. 복수값은 같은 키 반복, 필터 간 AND/같은 필터 OR. sort=deadline은 마감일 오름차순·동률 id 오름차순, newest는 공고 식별자 내림차순을 임시 계약으로 사용(서버 공고일 필드 도입 시 변경). 날짜 미확인은 뒤로. 검색 대상 제목·기관·지역. 응답 {items,page,size,totalElements,totalPages}. GET /notices/filters → {regions,types}. GET /notices/:id → Notice. 필터 변경 시 page=0. 잘못된 page/size는 프론트에서 정상 범위로 보정하고 서버도 검증한다.

기본 정보 공개와 guideState=AVAILABLE|REVIEWING|UNSUPPORTED|CHANGE_REVIEW는 독립적이다. canStart는 서버가 결정한다. 미검수 질문·서류는 응답에서 제외한다. 공식 원문은 HTTPS만 연결하고 주소가 없으면 확인 필요를 표시한다.

## 개인 준비

GET /preparations → Preparation[], GET /preparations/:id → Preparation. POST /preparations {noticeId,version} → Preparation. 같은 회원·공고 중복 요청은 기존 건 반환. PUT /preparations/:id/answers {revision,answers}; PATCH /preparations/:id/documents/:documentId {revision,state,note}; PATCH /preparations/:id/schedules/:scheduleId {revision,recorded}. 변경 응답은 새 Preparation 전체다. revision 불일치 409, 타인 기록은 404, 인증 없음 401. 낙관적 완료 표시 없이 성공 응답 후 캐시 갱신. 모름 UNKNOWN과 미응답(키 없음)을 구분한다. 서류 필요 여부와 준비 상태를 별도 관리한다. 사용자 증빙 파일 업로드 없음.

초기 계산 초안: 답변 확인 30%, 필요한 서류 완료 50%, 공식 일정 확인 기록 20%. 모름은 답변 완료 점수 제외하며 경고 표시. 조건부 해당 여부 미확인은 완료 처리하지 않는다. 평가 항목이 0개인 영역은 자동 완료하지 않고 0점. 정수 반올림. houseStage는 0~19=1,20~39=2,40~69=3,70~99=4,100=5. 이는 준비 작업 기록이며 자격·신청 완료 판정이 아니다. 서버가 progress/houseStage/warnings/nextAction을 반환한다. 계산 코드는 개발용 시연 어댑터에만 있으며 실제 UI는 계산하지 않는다. 실제 공고 검증 후 계산 기준 버전을 확정한다.

변경 검토 중에는 기존 기준 버전으로 조회하고 수정은 409로 보류한다. 기존 답변·완료 상태는 보존한다. now action 우선순위: 재확인 → 미응답/모름 → 미완료 서류 → 미기록 일정. 공식 사이트 이동은 완료 기록을 변경하지 않는다.

## 관리자·가이드

GET /guides → Guide[]. GET /admin/reviews → Review[]. PATCH /admin/reviews/:id {basic,guide,reason,revision}. basic=PASS|HOLD, guide=PASS|HOLD|UNSUPPORTED. 기본 정보 HOLD면 양쪽 공개 금지. 보완 사유 필수. 검수자·시각·이력은 서버가 기록. GET/POST /admin/collections는 실행 목록/실행 요청이며 수집 성공을 공개 완료로 취급하지 않는다. 서버에서 ADMIN 권한 필수.

## UI와 구현 경계

React Router 경로는 화면설계서 기준. 서버 상태는 TanStack Query. 쿼리 키에 사용자 ID·공고 ID·검색 URL을 포함한다. 로그아웃 성공 후 개인 캐시 제거. 실패 시 입력을 유지하고 재시도. 404 경로와 인증 필요·권한 부족·빈 결과를 표시한다. 후순위 알림·캘린더·즐겨찾기는 실제 변경 없는 접근 가능한 dialog다.

SEED 공식 React 수동 설치와 ActionButton 공개 컴포넌트를 사용하며 브랜드 CSS는 제품 레이어에서 적용한다. 기타 UI는 semantic HTML과 제품 전용 CSS를 사용한다. 공식 스니펫 설치를 수행했다고 주장하지 않는다.

근거: https://seed-design.io/llms/react/getting-started/installation/manual.txt 및 https://seed-design.io/llms/react/components/action-button.txt . 상세 DTO의 현재 단일 소스는 frontend/src/features/housing/types.ts이다.

