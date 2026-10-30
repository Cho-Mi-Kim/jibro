# 개발 환경 설정 순서

대상: Windows, 새 `jibro-housing` 저장소. 명령을 기존 `jibro-housing-prep`에서 실행하지 않습니다.

최종 수정일: 2026-10-30. 이 문서는 실행 절차를 관리합니다. 작업 전 [팀 컨벤션](conventions/team-conventions.md)·[코딩 컨벤션](conventions/coding-conventions.md)을 확인하고 기능 구현에는 [기획안](planning/project-proposal.md)·[화면설계서](design/screen-spec.md)를 함께 사용합니다. [전체 문서 안내](README.md).

## 1. 사전 도구

| 도구 | 팀 기준 | 확인 명령 |
| --- | --- | --- |
| Node.js | 24 LTS, `.nvmrc` 참고 | `node --version` |
| npm | Node.js 24에 포함된 버전 | `npm --version` |
| Java | Eclipse Temurin JDK 17 | `java -version`, `javac -version` |
| Git | 팀이 사용 가능한 최신 안정 버전 | `git --version` |
| Docker Desktop | Linux 컨테이너 엔진 | `docker info`, `docker compose version` |
| IDE | 백엔드 IntelliJ IDEA, 프론트엔드 VS Code | 각 프로젝트 폴더 열기 |

설치 경로: [Node.js 공식](https://nodejs.org/en/download), [Temurin 공식](https://adoptium.net/temurin/releases/?version=17), [Docker Desktop 공식](https://docs.docker.com/desktop/setup/install/windows-install/).

도구 준비 후 저장소 루트에서 `node scripts/project-history.mjs install`을 한 번 실행해 [작업 기록 hook](history/README.md)을 연결합니다. Git 설정은 clone에 포함되지 않으므로 팀원별로 설치합니다. 기존 hook 설정이 있으면 덮어쓰지 않고 안내합니다.

## 2. 프로젝트 생성 기준 — 이미 수행한 단계

프론트엔드는 루트에서 다음 명령으로 React·TypeScript 템플릿을 만들었습니다. 현재 폴더에 다시 실행하지 않습니다.

```powershell
npm create vite@latest frontend -- --template react-ts --no-interactive
```

그 후 기본 템플릿 린터를 팀 기준 ESLint로 맞추고 React Router·TanStack Query v5·Prettier·Vitest·React Testing Library를 추가했습니다. 팀원은 생성 명령 대신 `frontend/`에서 `npm ci`를 실행합니다. 라이브러리 추가 때만 `npm install`로 잠금 파일을 갱신합니다.

백엔드는 [Spring Initializr](https://start.spring.io/)의 다음 선택으로 생성했습니다.

| 항목 | 선택 |
| --- | --- |
| Project / Language | Gradle Groovy / Java |
| Boot / Java | 4.1.2 안정 버전 / 17 |
| Group / Artifact / Package | `org.jibro` / `backend` / `org.jibro` |
| Packaging | Jar |
| Dependencies | Spring Web(MVC), Validation, Spring Data JPA, PostgreSQL Driver, Flyway, Spring Security, OAuth2 Client, OAuth2 Resource Server |

생성된 Wrapper는 합의한 Gradle 8.14 계열인 **8.14.6**으로 맞췄습니다. OpenFeign Querydsl **7.7**과 annotation processing, Spotless **8.10.4**를 추가했습니다. `com.querydsl` 원본 계열 의존성을 혼용하지 않습니다. 공고 엔티티와 실제 복합 검색, 인증·개인 준비·검수 스키마를 추가했습니다.

공식 근거: [Vite 생성 안내](https://vite.dev/guide/), [Boot 지원 환경](https://docs.spring.io/spring-boot/system-requirements.html), [Spring Data의 Querydsl 안내](https://docs.spring.io/spring-data/jpa/reference/repositories/core-extensions.html).

## 3. VS Code 설정

1. VS Code에서 **`frontend/`**를 엽니다.
2. 추천 확장 Prettier·ESLint·EditorConfig를 설치합니다.
3. `frontend/.vscode/settings.json`의 저장 시 포맷·ESLint 설정을 사용합니다.
4. Node.js 24 터미널에서 `npm ci`, `npm run ci`를 실행합니다.
5. `npm run dev`로 개발 서버를 실행합니다. CSS Modules는 별도 설치 없이 `*.module.css`로 사용합니다.

Prettier는 4칸·세미콜론·싱글 쿼트·LF, ESLint는 코드 검사를 담당합니다. `typecheck`는 `tsc -b`로 검사하고 Vite 빌드와 별도로 실행할 수 있습니다.

## 4. IntelliJ 설정

1. IntelliJ의 Open으로 **`backend/`**를 선택하고 Gradle 프로젝트로 연결합니다.
2. Project SDK·Java language level·Gradle JVM을 JDK 17로 맞춥니다. 팀 배포판 기준은 Temurin입니다.
3. Gradle distribution은 Wrapper, Build and run using·Run tests using은 Gradle로 설정합니다.
4. Settings → Editor → Code Style → Java의 Import Scheme → IntelliJ IDEA code style XML에서 `config/intellij-java-style.xml`을 가져옵니다. 루트 `.editorconfig`도 적용합니다.
5. Gradle을 동기화합니다. 생성된 Q 클래스는 `build/generated/sources/annotationProcessor/` 아래에서 확인하며 커밋하지 않습니다.
6. 실행 구성의 환경 변수에 DB 접속값을 입력합니다. 루트 `.env`는 Spring이 자동으로 읽는 파일이 아닙니다. 실행 도우미가 읽거나 IDE 환경 변수에 직접 넣어야 합니다.

Spotless 포맷은 추가 런타임이 필요합니다. PowerShell에서 본인의 `idea64.exe` 또는 CI의 IntelliJ CLI 경로를 `IDEA_FORMATTER_PATH`에 지정한 뒤, IDE를 종료하고 실행합니다.

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat spotlessApply
```

현재는 IDE·CLI·CI의 같은 버전·출력을 확인하는 단계가 남아 있어 필수 CI 검사로 연결하지 않았습니다. [Spotless IntelliJ 안내](https://github.com/diffplug/spotless/tree/main/plugin-gradle#intellij-idea).

## 5. PostgreSQL 초기화

Docker Desktop의 Linux 엔진을 시작한 뒤 루트에서 실행합니다.

```powershell
.\scripts\Initialize-Local.ps1
```

`.env`가 없는 경우 임의 로컬 DB 비밀번호를 생성하고, JWT_SECRET이 없으면 임의 서명 키를 추가합니다. 기존 비밀번호나 서명 키를 덮어쓰지 않습니다. Docker Compose는 새 프로젝트명 `jibro-housing-dev`와 새 볼륨을 사용합니다. 기존 DB를 초기화하지 않습니다. 바인딩 포트는 충돌을 줄이기 위해 `127.0.0.1:15432`입니다.

- 개발 DB: `jibro`
- 통합 테스트 DB: `jibro_test`
- 계정: `jibro`
- Flyway: 테이블 변경 SQL의 유일한 관리 경로
- JPA: `ddl-auto: validate`

`jibro_test`는 빈 볼륨을 처음 초기화할 때 만듭니다. 이미 이 새 프로젝트의 볼륨을 사용 중인데 테스트 DB만 없다면 DB를 추가하고 기존 볼륨을 지워 해결하지 않습니다. 비밀번호를 나중에 바꿔도 기존 볼륨의 DB 계정 비밀번호는 자동 변경되지 않습니다.

Docker 없이 로컬 PostgreSQL 17을 설치한 경우 두 DB를 개발용 계정 소유로 만들고 `DB_URL`·`TEST_DB_URL`을 실제 주소로 맞춥니다.

## 6. 프론트·백엔드 각각 실행

Docker Desktop을 실행한 뒤, 저장소 루트에서 먼저 DB가 실행 중인지 확인합니다. PC 재시작 등으로 컨테이너가 중지되어 있으면 다음 명령으로 기존 데이터를 유지하면서 시작합니다.

```powershell
docker compose up -d --wait postgres
```

`Start-Backend.ps1`은 DB를 자동으로 시작하지 않습니다. `SQL State: 08001`, `localhost:15432`, `Connection refused`가 함께 나오면 DB 연결부터 확인합니다. 콘솔에서 한글이 깨져 보이는 것만으로 폴더 경로 오류라고 판단하지 않습니다.

가장 간단한 방법은 루트에서 서로 다른 터미널로 실행하는 것입니다.

```powershell
.\scripts\Start-Backend.ps1
```

```powershell
.\scripts\Start-Frontend.ps1
```

도우미 없이 직접 실행할 때는 프론트에서 `npm run dev`, 백엔드에서 DB 환경 변수를 설정하고 `.\gradlew.bat bootRun`을 실행합니다.

| 변수 | 예시·용도 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:15432/jibro` |
| `DB_USERNAME` | `jibro` |
| `DB_PASSWORD` | 로컬 DB에 설정한 비밀번호, Git 제외 |
| `TEST_DB_URL` | `jdbc:postgresql://localhost:15432/jibro_test` |
| `SERVER_PORT` | 기본 `8080` |
| `SERVER_ADDRESS` | 기본 `127.0.0.1`, AWS 설정은 후속 설계 |

Vite의 `/api` 요청은 `http://127.0.0.1:8080`으로 전달합니다. `/api`를 제거하는 rewrite는 사용하지 않습니다. 프론트 fetch는 상대 경로 `/api/v1/health`를 사용하므로 개발 단계에서 전체 CORS 허용 설정이 필요하지 않습니다. Vite proxy는 개발용이며 배포 때 Nginx의 `/api/` 전달 설정을 별도로 구성해야 합니다.

## 7. 확인·검사

1. API 직접 호출: `http://127.0.0.1:8080/api/v1/health`의 `status`·`database`가 `UP`인지 확인합니다.
2. 브라우저: `http://127.0.0.1:5173`에서 같은 응답이 표시되는지 확인합니다.
3. 프론트 `npm run ci`: 포맷·린트·타입·테스트·빌드를 검사합니다.
4. 백엔드 `.\gradlew.bat clean build`: 단위 테스트와 컴파일·JAR 생성을 확인합니다.
5. 백엔드 `.\gradlew.bat integrationTest`: 별도 DB에서 Flyway·JPA validate·Q 클래스 생성·복합 조회를 확인합니다. `build`만으로 이 단계까지 검증했다고 기록하지 않습니다.

GitHub Actions는 PR마다 양쪽 작업을 모두 실행하도록 작성했습니다. 원격 저장소 연결·push·Actions 실제 실행 및 보호 브랜치 설정은 별도 작업입니다. 배포는 자동 수행하지 않습니다.

## 8. 실제 전체 기능 실행

백엔드는 다음처럼 샘플 프로필로 실행하고 프론트엔드는 기존 `Start-Frontend.ps1`로 실행합니다. 프론트엔드의 demo 모드는 사용하지 않습니다.

```powershell
.\scripts\Initialize-Local.ps1
.\scripts\Start-Backend.ps1 -SampleData
# 별도 터미널
.\scripts\Start-Frontend.ps1
```

브라우저 http://127.0.0.1:5173 에서 이메일 회원가입 → 공고 선택 → 조건 확인 → 서류·일정 저장을 확인합니다. 로컬 샘플도 실제 DB에 저장되므로 새로고침 후 복원됩니다. 실제 개인정보는 테스트에 사용하지 않습니다.

`JWT_SECRET`은 필수이며 로컬 초기화가 생성합니다. `AUTH_SECURE_COOKIE=false`는 로컬 HTTP 전용입니다. `FRONTEND_URL`은 OAuth 복귀 주소이며 기본 http://127.0.0.1:5173 입니다. Google·카카오·LH 키는 `.env.example`을 참고합니다. 키가 없으면 해당 외부 연결을 성공으로 표시하지 않습니다.

추가 HTTP 흐름 검사: 환경 도우미를 로드한 뒤 `node scripts/verify-api.mjs`. 샘플 데이터가 있는 localhost만 허용하며 합성 테스트 계정·준비 건이 DB에 남습니다. 이 검사는 외부 OAuth 왕복이나 실제 LH 수집을 검증하지 않습니다.

권한·관리자 원고·공개 상태·수집 범위·후속 작업은 [백엔드 계약](architecture/backend-contracts.md)을 참고합니다. 배포 구성·HTTPS·관리자 승격·외부 키·실제 공고 검수는 별도 단계입니다.
