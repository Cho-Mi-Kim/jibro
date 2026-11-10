# 프론트엔드

React·TypeScript·Vite, React Router, TanStack Query, CSS Modules, SEED Design을 사용합니다. VS Code에서 이 폴더를 엽니다. 최종 수정일: 2026-11-10.

[공개 시연](https://cho-mi-kim.github.io/jibro/) · [팀원 테스트 안내](../docs/github-pages-demo.md) · [개발 환경 설정](../docs/getting-started.md) · [코딩 컨벤션](../docs/conventions/coding-conventions.md)

## 실행 방식

Node.js 24를 준비한 뒤 **frontend 폴더**에서 `npm ci`로 의존성을 설치합니다.

| 목적                | 명령                              | 주소·데이터                                       |
| ------------------- | --------------------------------- | ------------------------------------------------- |
| 실제 백엔드와 개발  | `npm run dev`                     | `http://127.0.0.1:5173` · 서버 API·DB             |
| 서버 없이 개발 시연 | `npm run dev:demo -- --port 5174` | `http://127.0.0.1:5174` · 가상 데이터·메모리 저장 |
| 일반 배포 산출물    | `npm run build`                   | `dist/` · 실제 서버 API 사용                      |
| Pages 시연 산출물   | `npm run build:pages`             | `dist/` · 가상 API·해시 라우팅·`/jibro/` 경로     |

각 빌드는 같은 `dist/`를 갱신합니다. 시연 데이터는 새로고침 시 초기화되며 서버 오류가 발생했다고 자동 시연 모드로 전환하지 않습니다. 일반 운영 빌드에서는 시연이 활성화되지 않고, 전용 `pages-demo` 빌드에서만 허용됩니다.

시연 모드에서는 Google·카카오 로그인 버튼이 숨겨집니다. 로그인 버튼과 실제 인증 흐름은 서버 모드의 `/login`에서 확인합니다. 5173의 서버 모드와 5174의 시연 모드를 동시에 실행할 수 있으며 로그인·기록은 공유되지 않습니다. 환경 변수에 `VITE_API_MODE=demo`를 직접 지정했다면 실제 서버 모드로 전환할 때 제거하거나 `server`로 변경한 뒤 재시작합니다. 루트 기준 명령과 종료 방법은 [실행 모드 안내](../docs/getting-started.md#6-실행-모드-선택과-프론트백엔드-실행)를 따릅니다.

### 실제 백엔드 연결

루트에서 `Initialize-Local.ps1`로 DB를 준비하고 `Start-Backend.ps1`로 서버를 실행합니다. 샘플 공고로 실제 DB 저장을 시험하려면 `Start-Backend.ps1 -SampleData`를 사용합니다. 이때 프론트엔드는 `dev:demo`가 아닌 `npm run dev`로 실행합니다.

가입·로그인·공고·개인 준비·관리자 검수는 서버 API에 연결되어 있습니다. Google·카카오·LH에는 제공자 키 설정이 필요합니다. [백엔드 계약](../docs/architecture/backend-contracts.md), [외부 연동 검증 기록](../docs/history/entries/2026-10-31-external-integration-verification.md)을 참고하세요.

`/api`·`/oauth2`·`/login/oauth2`는 개발용 Vite proxy가 `http://127.0.0.1:8080`으로 전달합니다. 이 설정은 Vite preview나 운영 서버의 프록시를 대신하지 않습니다. 홈은 `/`, 연결 진단 화면은 `/development`입니다. 공개 Pages 시연에는 서버 진단 화면을 제공하지 않습니다.

이 PC의 프로젝트 전용 Node.js와 루트 환경 변수를 사용하려면 루트에서 `scripts/Start-Frontend.ps1`을 실행할 수 있습니다.

### Pages 시연 미리보기

```powershell
npm run build:pages
npm run preview -- --mode pages-demo --host 127.0.0.1 --port 4173
```

`http://127.0.0.1:4173/jibro/#/notices`에서 확인합니다. preview에도 `--mode pages-demo`를 지정해야 빌드와 경로가 일치합니다. 공개 시연은 개인정보 입력 없이 시연 사용자로 시작하며 실제 인증·수집·서버 저장을 제공하지 않습니다.

main의 프론트엔드·배포 설정 변경 시 [Pages 워크플로](../.github/workflows/pages.yml)가 검사 후 시연을 배포합니다. 배포 기준과 팀원 테스트 절차는 [Pages 안내](../docs/github-pages-demo.md)를 따릅니다.

## 검사와 개발 규칙

```powershell
npm run format
npm run format:check
npm run lint
npm run typecheck
npm run test:run
npm run build
npm run ci
```

`ci`는 포맷·린트·타입·테스트·일반 빌드를 순서대로 수행합니다. PR에서는 `build:pages`도 별도로 검사합니다. `format`은 파일을 수정하고 `format:check`는 검사만 합니다.

Prettier는 4칸·세미콜론·싱글 쿼트·LF를 적용합니다. 제품 화면의 공통 요소는 SEED Design과 CSS Modules를 사용합니다. 폴더 구성과 API 계약은 [코딩 컨벤션](../docs/conventions/coding-conventions.md)·[프론트엔드 계약](../docs/architecture/frontend-contracts.md)·[질문·서류 계약](../docs/contracts/question-options-and-documents.md)을 확인하세요.

프론트엔드 테스트는 실제 OAuth 제공자, DB 권한, LH 외부 API 검증을 대신하지 않습니다. 질문과 서류의 미저장 변경은 링크 이동·새로고침 전에 확인하며, 브라우저 뒤로가기의 전면 차단은 아직 구현하지 않았습니다.
