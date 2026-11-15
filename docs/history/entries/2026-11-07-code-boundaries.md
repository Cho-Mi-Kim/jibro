# 화면·기능을 보존한 구현 경계 정리

- 기록일: 2026-11-07 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 해결
- 유형: 개발 / 리팩토링 / 문제 해결
- 담당·검토: Codex가 구현·자동 검사·브라우저 비교 및 분담 검토를 수행했다. 팀 담당자의 코드 리뷰는 미확인이다.
- 관련 이슈·PR·커밋: 새 커밋·PR·원격 게시 없음. 로컬 작업 브랜치 `refactor/code-boundaries-20261107`, 시작 HEAD `e288a993a9a828a798d44eea165c0f276ab34a60`.

## 배경·문제

사용자는 GitHub 구현 규칙 점검 중 자신의 의도적인 화면·기능 변경을 제외하고 남은 네 항목을 보완하도록 승인했다. 대상은 TypeScript strict, 프런트 참조 방향, 공통 파일에 섞인 화면 CSS, Service/Controller의 직접 DB 실행이었다. 이전 미커밋 기능 변경을 포함한 현재 작업 트리를 기준으로 했으며, 원격에 올라간 과거 상태로 되돌리지 않았다.

## 고려한 선택지

- 옛 경로의 재export는 소비자 변경을 줄이지만 중복 진입점을 남기므로 제거했다.
- SQL을 받는 범용 중계 Repository는 책임을 분리하지 못하므로 사용하지 않았다. 조회·저장·잠금의 이름과 타입을 갖는 메서드로 옮겼다.
- JDBC 잠금을 JPA 엔티티/스키마 변경으로 대체하는 방안은 이번 동작 보존 범위에 맞지 않았다.
- CSS 모듈 간 `@value`로 클래스만 가져오는 초기 분리는 Vite가 해당 스타일 본문까지 재삽입해 기존 우선순위를 바꾸었다. 전역 이름이나 cascade layer로 전체 규칙의 의미를 바꾸는 대신 빈 클래스 이름 계약을 분리했다.

## 결정·이유

업무 규칙·API·DB 스키마·사용자 지정 UI는 유지하고 책임과 참조 방향만 바꾼다. 트랜잭션·소유권·리비전·행 잠금은 Service에 남고, 실제 SQL/Querydsl 실행은 도메인 Repository로 이동한다. 클래스 이름을 공유하는 CSS는 스타일 선언이 없는 `ClassNames.module.css`만 참조하며, 앱 조합 계층이 기반 스타일을 개별 컴포넌트 스타일보다 먼저 불러온다.

## 변경·해결 과정

- app/node TS 설정에 `strict: true`를 적용했다. 인증 Context와 hook은 `features/auth`, 공고 전용 표시/근거는 `features/housing`, 준비 표시/진행도는 `features/preparation`으로 옮겼다. shared에는 일반 UI·통신·탐색 도구를 남겼다.
- 공통 transport가 시연 기능을 동적으로 참조하던 경로는 `app/configureTransport`의 어댑터 주입으로 바꿨다. 통합 테스트도 실제 조합 계층으로 이동했다.
- `Product.module.css`를 제거하고 공통 Primitives·Shell·인증·홈·가이드·마이·공고·준비/조건/서류/일정 스타일로 분리했다. `bindClasses`는 호출자가 전달한 기능 스타일만 결합한다. 공유 선택자의 이름 계약과 `app/presentationStyles`의 선행 로딩으로 기존 우선순위를 보존한다.
- `@value button`이 HTML `button` 선택자까지 치환하는 문제가 실제 목록 비교에서 확인되어 클래스 이름을 `primary-button`으로 바꿨다. CSS 선언 키워드/태그와 이름이 충돌하는 경우도 검사한다.
- 인증·계정·세션·공고·가이드·준비·이력·삭제·관심공고·경험·알림·발송·health 저장 경계를 분리했다. 기존 수집 Store에 필요한 조회를 추가하고 중복 저장 계층은 만들지 않았다.
- 수집 Store를 `@Repository`로 표기한 초기 변경은 Spring이 기존 `LeaseLost`/`IllegalArgumentException`을 다른 예외로 감싸는 회귀를 일으켰다. 해당 Store는 원래 `@Component`를 유지하고 이유를 기록했다. 테스트 기대값은 바꾸지 않았다.
- OAuth 해제 경합 테스트는 옛 JDBC 오버로드 대신 새 Repository의 실제 identity 조회 직후에서 멈추도록 바꿨다. 해제 commit 뒤 로그인 재개, 401, 새 refresh 0개 검증은 보존했다.
- 실행 중이던 이전 JAR의 공고/가이드 요청이 멈추는 현상을 확인했다. DB 잠금이나 연결 고갈은 관찰되지 않았으며 실행 중 빌드 산출물이 교체된 상태였다. 정확한 JVM 예외는 확보하지 못했다. 수정본 재시작 후 정상 응답을 확인했고, 로컬 실행 도우미는 빌드 JAR을 별도 runtime 경로에 복사해 실행하도록 바꿨다. 이 도우미와 진단 파일은 `.tools`의 로컬 파일이다.

## 검증·결과

- `npm --prefix frontend run ci`: 포맷·ESLint·strict 타입·계층 검사·테스트·프로덕션 빌드 통과. Vitest 33개 파일 **194개**, 경계 검사 자체 테스트 **10개** 통과.
- 경계 검사: TS **168개**, CSS **60개**의 역참조·실행 순환·빈 선택자 계약 검사 통과. 기존 모듈 경로 재export/참조 없음.
- `./backend/gradlew.bat -p backend test integrationTest bootJar --console=plain`: 단위 **48개**, PostgreSQL 통합 **103개**, 실패·오류·skip 0. 전체 자동 검사 합계 **355개**.
- 초기 통합 실패와 위 수정 후의 재실행 결과를 구분했다. 인증 경합 2개, 수집 관련 10개 단독 검사 후 최종 전체 검사를 통과했다.
- 기존 CSS 선택자/미디어 조건/선언 497개를 분리본과 대조했다. 클래스 명칭 변경 외에 디자인 값을 변경하지 않았다. Vite 변환에서 빈 이름 계약의 본문이 비어 있고 가져온 173개 이름의 해시가 일치함을 독립 확인했다.
- 변경 전 코드를 별도 5175에서 잠시 실행해 동일 데이터로 비교했다. 393×852에서 홈(123개 요소), 내 준비 개요/조건/서류/서류 펼침/일정, 마이, 공고 목록/상세, 실제 가이드 목록의 위치·크기·측정한 글꼴/여백/색상 차이 0을 확인했다. 중간 실패 캡처와 최종 통과 캡처는 `.tools/qa/code-boundaries`에 분리했다.
- 360px에서 가로 넘침 없음, 기본 하단 메뉴 터치 높이 44px 이상 확인. 아주 큰 글자(루트 20px)에서도 가로 넘침 없고 메뉴 높이 55px. 768px에서는 상단 메뉴만 표시됨을 확인했다. 접근성 설정과 임시 브라우저 크기는 원상 복원했다. 실제 모바일 기기 검사는 하지 않았다.
- 수정본 5173과 백엔드를 재시작하고 health/공고 목록/가이드 HTTP 200, 공고 3건 로딩을 확인했다. 비교용 5175 서버는 검증 후 종료했다.
- Vite의 큰 JS chunk 안내와 Gradle의 향후 버전 deprecation 안내는 남아 있다. 이 작업의 성공은 운영 배포·외부 제공자 연결·성능 최적화 완료를 뜻하지 않는다.

## 기여·한계

AI 보조로 구현과 분담 리뷰를 수행했으며 사용자·팀원의 직접 작성 기여를 추정하지 않았다. 기존 비회원 기록·준비 답변·DB 자료를 삭제하지 않았다. 준비도 계산과 공식 신청의 구분, 사용자 확정 UI 변경은 유지했다. 검증은 로컬 환경이며 원격 CI나 팀 리뷰 결과가 아니다.

## 발표·포트폴리오 메모

반복 개선 과정에서 섞인 참조·스타일·저장 책임을 분리하고, 테스트 통과뿐 아니라 변경 전후 화면과 오류 계약을 비교했다. 실제 비교에서 발견한 CSS 치환·적용 순서와 예외 변환 회귀를 수정하고 자동 경계 검사를 추가한 사례다.

## 후속 작업

- 사용자가 원격 업로드를 요청하면 이번 로컬 변경과 선행 미커밋 기능 변경을 검토 가능한 범위로 구분해 게시한다.
- 새 화면은 컴포넌트 CSS 모듈에 추가하고 빈 이름 계약에는 스타일 선언을 넣지 않는다. 이름 계약을 편집한 개발 세션에서는 Vite의 기존 변환 캐시가 남지 않도록 개발 서버를 다시 시작해 확인한다.
- 큰 번들 분할과 실제 기기 검증은 별도 작업으로 진행할 수 있다.

## 변경 파일

- `frontend/tsconfig.*.json`, `frontend/scripts/check-*.mjs`, `frontend/src/app/{configureTransport,presentationStyles}.ts`
- `frontend/src/shared/{api,styles,ui}`, `frontend/src/features/{auth,home,housing,preparation,member,guide}`, 관련 소비자·테스트
- `backend/src/main/java/org/jibro/{auth,notice,preparation,member,collection,global}`, `backend/src/test/java/org/jibro/{architecture,auth,global}`
- `docs/architecture/{frontend-contracts,backend-contracts}.md`, 작업 기록 목록 및 연결 프로젝트의 의미/생성 지도
