# 집으로 · 청년 주거 지원 준비 노트

청년 주거 지원 공고를 찾고, 공고별 조건과 제출서류·신청 일정을 확인하며 준비 과정을 기록하는 반응형 웹 서비스입니다. 준비도(%)와 벽돌집 건축 표현으로 준비 작업의 진행 상황을 보여줍니다.

**[화면 테스트용 시연 바로가기](https://cho-mi-kim.github.io/jibro/)** · [팀원 테스트 안내](docs/github-pages-demo.md) · [프로젝트 문서](docs/README.md)

최종 수정일: 2026-11-01

## 설치 없이 체험하기

1. [시연 사이트](https://cho-mi-kim.github.io/jibro/)의 **공고**에서 `[시연] 청년 주거 준비 예시`를 선택합니다.
2. **내 조건 확인하기 → 시연 사용자로 보기**를 누릅니다. 실제 회원가입은 필요하지 않습니다.
3. 질문 답변 → 준비 대상 서류 확인·완료 표시 → 신청 여부 기록 → 홈의 준비도 변화를 확인합니다.

공개 시연은 가상 데이터로 작동하며 실제 Google·카카오 로그인, LH 수집, 서버 저장을 제공하지 않습니다. 새로고침하거나 탭을 닫으면 시연 계정과 기록이 초기화됩니다. 실제 신청이나 자격 판단에 사용하지 않습니다. 피드백 작성 방법은 [팀원 테스트 안내](docs/github-pages-demo.md)를 참고하세요.

## 주요 기능과 구현 상태

| 영역        | 현재 구현                                                                                     |
| ----------- | --------------------------------------------------------------------------------------------- |
| 공고 찾기   | 키워드·지역·지원 유형·접수 상태 복합 검색, 정렬·페이지 이동, 공식 원문과 공고 상세            |
| 준비 안내   | 공고 정보와 안내 동시 검수, 준비 안내 제공·검수·미제공·변경 검토 상태 구분                    |
| 조건 확인   | 질문별 선택지, 답변 저장·수정, 추가 확인 항목과 근거 표시                                     |
| 서류 준비   | 답변에 따른 준비 대상 서류 표시, 해당 여부 미확인 구분, 준비 상태·메모 기록, 검증된 발급 링크 |
| 일정·진행률 | 공식 일정과 본인의 신청 여부 기록, 준비도 계산, 단계별 벽돌집 시각화                          |
| 계정        | 이메일·비밀번호 가입/로그인, Google·카카오 OAuth, JWT·갱신 토큰, 로그인 유지·로그아웃         |
| 관리자      | LH 목록 수집·정규화, 공고 구조화 원고 편집, 검수와 공개 상태 관리                             |
| 화면        | 홈·공고·내 준비·가이드·마이의 모바일/PC 반응형 UI                                             |

준비도는 **기록한 준비 작업의 진행률**이며 신청 자격이나 선정 가능성이 아닙니다. 실제 신청은 운영기관 공식 사이트에서 진행합니다. 주민등록번호와 증빙 원본은 저장하지 않습니다.

실제 서버 연동 기능과 공개 시연은 별개입니다. Google·카카오·LH는 실행 환경별 외부 키 설정이 필요합니다. 기존 로컬 연동 확인 범위와 Google Chrome 계정 선택 정체 등 남은 사항은 [외부 연동 검증 기록](docs/history/entries/2026-10-31-external-integration-verification.md)을 확인하세요.

관심 공고·알림·캘린더 내보내기·계정 관리 일부는 화면에서 준비 중으로 표시합니다. 이메일 소유 확인·비밀번호 재설정, 예약 수집, AWS 운영 배포는 후속 작업입니다. 공개 시연 배포 완료가 실제 서비스 운영 배포 완료를 뜻하지 않습니다.

## 기술 구성

| 구분       | 사용 기술                                                                                |
| ---------- | ---------------------------------------------------------------------------------------- |
| 프론트엔드 | React 19 · TypeScript · Vite · React Router · TanStack Query · CSS Modules · SEED Design |
| 백엔드     | Java 17 · Spring Boot 4.1.2 · Spring MVC · Spring Security · OAuth2 · JWT                |
| 데이터     | PostgreSQL 17 · Spring Data JPA · OpenFeign Querydsl 7.7 · Flyway                        |
| 개발·검사  | Node.js 24 · npm · Gradle Wrapper · ESLint · Prettier · Vitest/Testing Library · JUnit   |
| 환경·배포  | VS Code / IntelliJ IDEA · Docker Compose(로컬 DB) · GitHub Actions · GitHub Pages(시연)  |

정확한 버전은 [프론트엔드 설정](frontend/package.json)·잠금 파일과 [백엔드 설정](backend/build.gradle)·Wrapper를 기준으로 확인합니다. Java 포맷은 공유 IntelliJ 설정·Spotless를 사용하며, 동일 CLI 환경 고정 전까지 필수 CI 검사에는 연결하지 않았습니다.

## 저장소 구조

```text
jibro-housing/
├─ frontend/          # React·TypeScript / VS Code
├─ backend/           # Spring Boot·Java 17·Gradle / IntelliJ IDEA
├─ data/notices/      # 공식 공고 구조화 자료·검수 입력 자료
├─ docs/              # 기획·화면설계·계약·개발 안내·작업 기록
├─ scripts/           # 실행·DB 준비·공고 입력·작업 기록 도구
├─ .github/           # PR 템플릿·CI·Pages 시연 배포
├─ compose.yaml       # 로컬 PostgreSQL
└─ .env.example       # 비밀값 없는 환경 변수 양식
```

프론트엔드와 백엔드의 의존성·실행·빌드·배포 산출물은 분리합니다. 루트 npm workspace나 통합 Gradle 프로젝트는 사용하지 않습니다. 기존 `jibro-housing-prep`과는 별도 저장소입니다.

## 로컬 실행

### 서버 없이 화면만 실행

Node.js 24를 준비하고 저장소 루트에서 실행합니다.

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev:demo -- --port 5174
```

[로컬 시연](http://127.0.0.1:5174)에서 시연 사용자를 선택합니다. Docker·Java·외부 키는 필요하지 않습니다. 데이터는 메모리에서만 유지됩니다.

### 실제 백엔드·DB와 함께 실행

Windows PowerShell 기준입니다. Node.js 24, JDK 17(팀 배포판 기준 Eclipse Temurin), Git, Docker Desktop의 Linux 엔진을 준비합니다. 아래 명령은 저장소 루트에서 실행합니다.

1. 처음에는 로컬 환경 변수와 DB를 준비합니다.

    ```powershell
    .\scripts\Initialize-Local.ps1
    ```

    `.env`가 없으면 로컬 전용 비밀번호를 만들고, JWT 서명 키가 없으면 추가합니다. 기존 값은 보존하며 `.env`는 커밋하지 않습니다. DB는 `127.0.0.1:15432`, 개발 DB는 `jibro`, 별도 통합 테스트 DB는 `jibro_test`입니다.

2. PC 재시작 등으로 DB가 중지되었다면 먼저 다시 시작합니다.

    ```powershell
    docker compose up -d --wait postgres
    ```

3. 백엔드 터미널에서 실행합니다.

    ```powershell
    .\scripts\Start-Backend.ps1
    ```

    처음에 공고가 없다면 로컬 샘플 공고를 넣어 실제 DB 저장을 확인할 수 있습니다. 위 명령 대신 `.\scripts\Start-Backend.ps1 -SampleData`를 사용합니다. 샘플 프로필은 로컬 개발용입니다.

4. 다른 터미널에서 프론트엔드를 실행합니다.

    ```powershell
    .\scripts\Start-Frontend.ps1
    ```

5. [서비스 홈](http://127.0.0.1:5173/)을 엽니다. API·DB 상태는 [개발 연결 확인 화면](http://127.0.0.1:5173/development) 또는 [연결 확인 API](http://127.0.0.1:8080/api/v1/health)에서 확인합니다.

Google·카카오 로그인과 LH 수집에는 본인 실행 환경의 키를 루트 `.env`에 설정해야 합니다. [외부 연동 설정을 포함한 실행 안내](docs/getting-started.md)와 [백엔드 계약](docs/architecture/backend-contracts.md)을 참고하세요. 키가 없으면 해당 외부 기능을 사용할 수 없습니다.

터미널 서버는 각각 Ctrl+C로 종료하고, DB는 `docker compose stop postgres`로 중지합니다. `.tools/`의 프로젝트 전용 도구는 Git에서 제외되므로 다른 팀원은 자신의 도구를 설치해야 합니다. Docker 없이 실행하거나 IDE에서 직접 실행하는 방법은 [개발 시작 안내](docs/getting-started.md)를 확인하세요.

## 검사·빌드

저장소 루트에서 실행합니다. 백엔드 통합 테스트는 별도 `jibro_test` DB가 실행 중이어야 합니다.

```powershell
# 루트 .env와 프로젝트 도구를 현재 터미널에 적용
. .\scripts\Use-DevEnvironment.ps1

# 프론트엔드: 포맷·린트·타입·테스트·일반 운영 빌드
npm --prefix frontend ci
npm --prefix frontend run ci

# Pages 전용 정적 시연 빌드
npm --prefix frontend run build:pages

# 백엔드: 단위 테스트·빌드, 별도 DB 통합 테스트
Push-Location backend
.\gradlew.bat clean build
.\gradlew.bat integrationTest
Pop-Location
```

일반 `build`와 `build:pages`는 목적이 다릅니다. 일반 프론트엔드 빌드는 서버 API를 사용하며, Pages 빌드는 `/jibro/` 경로·해시 라우팅·가상 API를 사용합니다. 미리보기 명령은 [프론트엔드 README](frontend/README.md)에 정리했습니다.

PR의 CI는 작업 기록 도구, 프론트엔드 검사·두 빌드, 백엔드 빌드·PostgreSQL 통합 테스트를 수행합니다. main의 프론트엔드 또는 Pages 워크플로 변경 시 [Pages 배포](.github/workflows/pages.yml)가 실행됩니다. 일반 서버 API·DB의 AWS 배포는 이 워크플로에 포함하지 않습니다.

## 협업·작업 기록

- `main`: 안정 버전 및 시연 배포 기준 / `develop`: 개발 통합 브랜치.
- 기능·수정 단위 브랜치에서 작업하며 **작업 브랜치마다 별도 PR**을 만듭니다. 의존 PR은 선행 PR부터 검토·병합하고 최신 CI를 확인합니다.
- 변경 이유·설계 판단·문제 해결·검증 결과는 [작업 기록](docs/history/README.md)에 남깁니다. 계획과 실제 검증 결과를 구분합니다.
- clone 후 `node scripts/project-history.mjs install`로 로컬 기록 hook을 설치합니다. 자동 커밋·push는 하지 않습니다.

## 문서 바로가기

- [팀원 시연·피드백 안내](docs/github-pages-demo.md)
- [전체 문서 목록](docs/README.md) · [작업·의사결정 기록](docs/history/README.md)
- [팀 컨벤션](docs/conventions/team-conventions.md) · [코딩 컨벤션](docs/conventions/coding-conventions.md)
- [기획안](docs/planning/project-proposal.md) · [화면설계서](docs/design/screen-spec.md)
- [개발 환경 설정](docs/getting-started.md) · [아키텍처](docs/architecture.md)
- [프론트엔드 계약](docs/architecture/frontend-contracts.md) · [백엔드 계약](docs/architecture/backend-contracts.md) · [질문·서류 계약](docs/contracts/question-options-and-documents.md)
- [프론트엔드 README](frontend/README.md) · [백엔드 README](backend/README.md)
- [공고 자료·반영 절차](data/notices/README.md) · [검증 기록](docs/verification.md) · [버전 기록](docs/versions.md)
