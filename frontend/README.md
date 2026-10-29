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

홈·공고 검색/상세·조건 입력/결과·내 준비/개요·서류·일정·가이드·마이·로그인/가입/콜백·관리자 검수 화면을 구성했습니다. 연결 확인 화면은 `/development`입니다. SEED Design 3.1.0의 ActionButton과 CSS Modules를 사용합니다.

## 서버 없이 화면 흐름 확인

```powershell
npm run dev:demo -- --port 5174
```

개발 시연 배너가 표시되고 가상 공고 4개와 메모리 어댑터로 작동합니다. 로그인 화면에서 시연 사용자 또는 관리자를 선택하세요. 실제 개인정보를 입력하지 마세요. 새로고침하면 시연 기록이 초기화됩니다. 서버 오류에 따라 자동으로 데모로 전환하지 않으며 운영 빌드에서는 데모를 활성화하지 않습니다.

## 백엔드 연결 시

`npm run dev`는 실제 `/api/v1` 서버를 사용합니다. 업무 API가 아직 없으므로 실제 모드에서는 서버 오류가 표시됩니다. [기능별 계약](../docs/architecture/frontend-contracts.md)의 경로·DTO·쿠키/CSRF·버전 충돌 응답을 구현한 뒤 연동해야 합니다. Google·카카오 진입과 콜백 화면은 구현했지만 제공자 앱 등록·서버 인증 설정은 필요합니다. `/oauth2`·`/login/oauth2`도 개발 proxy로 백엔드에 전달합니다.

## 검증 범위

`npm run ci`로 포맷·린트·타입·사용자 흐름·전송 계약·시연 상태 검사와 운영 빌드를 수행합니다. 테스트의 시연 어댑터는 실제 권한·DB·외부 공고 수집 검증을 대신하지 않습니다. 질문과 서류의 미저장 변경은 링크 이동·새로고침 전에 확인하며 브라우저 뒤로가기의 전면 차단은 아직 구현하지 않았습니다.
