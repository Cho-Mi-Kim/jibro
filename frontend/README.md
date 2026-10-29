# 프론트엔드

React·TypeScript·Vite, React Router, TanStack Query v5, CSS Modules를 사용합니다. VS Code에서 이 폴더를 엽니다.

실행 절차는 이 안내와 [개발 시작 안내](../docs/getting-started.md), 기술 규칙·폴더 예시는 [코딩 컨벤션](../docs/conventions/coding-conventions.md), 제품 화면은 [화면설계서](../docs/design/screen-spec.md)를 기준으로 확인합니다. [문서 전체 안내](../docs/README.md).

Node.js 24에서 이 폴더를 작업 위치로 사용합니다.

```powershell
npm ci
npm run dev
```

이 PC의 프로젝트 전용 Node.js를 사용하려면 루트의 `scripts/Start-Frontend.ps1`을 실행합니다. 개발 주소는 `http://127.0.0.1:5173`입니다. `/api`는 Vite proxy로 로컬 백엔드 8080에 전달합니다. 해당 설정은 개발용이며 Vite preview·운영 서버의 API proxy를 대신하지 않습니다.

```powershell
npm run format
npm run format:check
npm run lint
npm run typecheck
npm run test:run
npm run build
npm run ci
```

`ci`는 포맷·린트·타입·테스트·빌드를 순서대로 수행합니다. 결과는 `dist/`이며 Git에는 포함하지 않습니다. `.vscode/`의 추천 확장·공통 설정과 프로젝트 Prettier·ESLint를 사용합니다.

필요한 `app`·`pages`·`features`·`shared`부터 생성했습니다. 현재 페이지는 실제 API·DB 연결 확인용이며 제품 화면 구현이 아닙니다. 인증 Context·도메인 화면·SEED 컴포넌트 연동은 해당 기능 구현 때 공식 문서와 화면설계서를 확인해 추가합니다.
