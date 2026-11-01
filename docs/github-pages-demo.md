# 팀 화면 테스트용 GitHub Pages

공유 주소: https://cho-mi-kim.github.io/jibro/

## 팀원이 테스트하는 방법

1. 링크를 열고 공고 탭에서 `[시연] 청년 주거 준비 예시`를 선택합니다.
2. 조건 확인을 시작하고, 로그인 화면에서는 **시연 사용자로 보기**를 누릅니다. 회원가입이나 실제 계정은 필요하지 않습니다.
3. 질문 답변, 결과 확인, 준비 대상 서류의 완료 표시, 신청 여부 기록을 차례로 체험합니다.
4. 홈에서 준비도와 집 모양 변화, 내 준비 및 마이 화면의 기록을 확인합니다.
5. 휴대폰에서도 같은 링크를 열어 메뉴·문장·버튼이 잘 보이는지 확인합니다.
6. 새로고침하면 시연 계정과 준비 기록이 초기화됩니다. 다시 시작하려면 시연 사용자 버튼을 누릅니다. 초기화 후 이전 준비 상세 주소는 새 기록을 만들기 전에는 유효하지 않습니다.

피드백에는 **화면 주소, 사용 기기, 수행한 순서, 기대했던 결과, 실제 결과**를 적고 필요하면 화면 캡처를 첨부합니다. 실제 개인정보는 입력하지 않습니다.

## 테스트 범위

- 가상 공고의 검색·필터, 준비 안내 제공 여부, 조건 답변, 대상 서류, 일정 기록, 준비도 표시를 확인할 수 있습니다.
- 시연 관리자 버튼은 가상 검수 흐름을 위한 기능입니다. 실제 관리 권한이나 실제 LH 수집을 제공하지 않습니다.
- Google·카카오·이메일 로그인, 실제 공고 수집, 서버·DB 저장, 여러 기기 간 동기화는 이 배포에서 테스트하지 않습니다.
- 자료는 가상 데이터입니다. 실제 주거 지원 신청이나 자격 판단에 사용할 수 없습니다.
- 메모리에서만 작동하므로 사람마다 테스트 기록이 분리됩니다. 새로고침·탭 종료 시 기록이 사라집니다.
- 이 주소는 공개 시연 사이트이며 팀 전용 접근 제한이 없습니다. API 키나 비밀번호는 배포하지 않습니다.

## 개발·배포 방식

- 일반 운영 빌드: `npm --prefix frontend run build` — 기존 서버 API와 BrowserRouter를 유지합니다.
- Pages 전용 빌드: `npm --prefix frontend run build:pages` — `pages-demo` 모드, `/jibro/` 경로, HashRouter, 가상 API를 사용합니다.
- 로컬 확인: Pages 빌드 후 `npm --prefix frontend run preview -- --mode pages-demo --host 127.0.0.1 --port 4173`, `http://127.0.0.1:4173/jibro/#/notices`로 접속합니다.
- Pages 링크의 `#/notices` 같은 부분은 화면 주소입니다. 하위 화면을 직접 공유하거나 새로고침해도 정적 서버의 404를 피합니다.
- 저장소 이름이나 도메인을 바꾸면 `frontend/vite.config.ts`의 Pages `base`와 공유 주소를 함께 변경합니다.
- `.github/workflows/pages.yml`이 main의 프론트엔드 변경 시 검사 → 시연 빌드 → `frontend/dist` 업로드 → 배포 순으로 실행됩니다. Actions에서 수동 실행할 때도 main을 선택합니다.
- GitHub 저장소 Settings → Pages → Build and deployment → Source는 **GitHub Actions**로 설정합니다. 현재 저장소는 이 설정이 이미 활성화되어 있습니다.
- 작업 브랜치별 PR을 develop으로 검토·병합한 뒤 develop → main PR을 검토·병합합니다. CI 성공을 확인한 최신 커밋만 반영합니다.
- 배포 실패 시 Actions의 `Deploy Pages demo` 실행 결과를 확인합니다. 이전 성공한 배포는 유지되며, 수정 후 main에서 다시 실행합니다.

공식 근거: [GitHub Pages 워크플로](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages), [Vite 정적 배포](https://vite.dev/guide/static-deploy).
