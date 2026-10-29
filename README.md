# 집으로 · jibro-housing

2026-10-29에 새로 생성한 개발 시작용 모노레포입니다. 기존 `jibro-housing-prep`과 별도 프로젝트이며 기존 코드·Git 이력을 복사하지 않았습니다.

GitHub 저장소: [Cho-Mi-Kim/jibro](https://github.com/Cho-Mi-Kim/jibro). `main`은 안정 버전, `develop`은 개발 통합 브랜치로 사용합니다.

## 구성

```text
jibro-housing/
├─ frontend/          # React·TypeScript·Vite / VS Code
├─ backend/           # Spring Boot·JDK 17·Gradle / IntelliJ IDEA
├─ docs/              # 팀 기준·기획·화면설계·개발 안내·검증 기록
├─ scripts/           # Windows 실행 도우미·로컬 DB 초기화
├─ .github/           # PR 템플릿·프론트/백엔드별 CI
├─ compose.yaml       # 로컬 PostgreSQL 전용
├─ .env.example       # 비밀값 없는 환경 변수 양식
└─ .editorconfig
```

프론트·백엔드는 각자의 의존성·실행·빌드·배포 산출물을 관리합니다. 루트 npm workspace나 통합 Gradle 프로젝트는 사용하지 않습니다.

## 처음 실행하는 순서

Windows PowerShell에서 이 README가 있는 **새 저장소 루트**를 작업 위치로 사용합니다.

1. Node.js 24 LTS, JDK 17(팀 기준 Eclipse Temurin), Git, Docker Desktop을 준비합니다. Docker Desktop의 Linux 엔진이 실행 중이어야 합니다.
2. 루트에서 로컬 DB를 초기화합니다.

   ```powershell
   .\scripts\Initialize-Local.ps1
   ```

   없는 경우에만 `.env`에 로컬 전용 임의 비밀번호를 생성합니다. 파일은 Git에서 제외합니다. PostgreSQL은 `127.0.0.1:15432`를 사용하며 `jibro`와 별도 테스트 DB `jibro_test`를 만듭니다.

3. **백엔드용 터미널**에서 실행합니다.

   ```powershell
   .\scripts\Start-Backend.ps1
   ```

4. **프론트엔드용 다른 터미널**에서 실행합니다.

   ```powershell
   .\scripts\Start-Frontend.ps1
   ```

5. [개발 확인 화면](http://127.0.0.1:5173)에서 `API: UP / PostgreSQL: UP`을 확인합니다. 직접 API 확인 주소는 [연결 확인 API](http://127.0.0.1:8080/api/v1/health)입니다.

초기 검증을 위해 백그라운드로 실행해 둔 두 서버는 `scripts/Stop-Preview.ps1`로 종료할 수 있습니다. 이후 위 실행 도우미를 사용한 터미널에서는 Ctrl+C로 각각 종료합니다. DB만 중지하려면 루트에서 `docker compose stop postgres`를 실행하며 볼륨을 삭제하지 않습니다.

이 PC에 준비된 `.tools/`의 Node.js 24가 있으면 실행 도우미가 우선 사용합니다. 이 폴더는 Git에서 제외하므로 다른 팀원은 자신의 Node.js 24·JDK 17을 설치해야 합니다. 시스템 PATH를 영구 변경하지 않습니다. PowerShell 실행 정책이 파일 실행을 막으면 정책을 전역 완화하지 않고 [직접 실행 방법](docs/getting-started.md)을 사용합니다.

Docker를 사용하지 않을 경우 PostgreSQL 17에 위 두 DB를 따로 생성하고 환경 변수를 맞춥니다. RDS나 기존 프로젝트의 DB를 개발 테스트 대상으로 사용하지 않습니다.

## 개별 검사·빌드

프론트엔드: `frontend/`에서 실행합니다.

```powershell
npm ci
npm run ci
```

백엔드: `backend/`에서 실행합니다.

```powershell
.\gradlew.bat clean build
.\gradlew.bat integrationTest
```

`build`는 DB 없이 단위 테스트·컴파일·실행 JAR 생성을 수행합니다. `integrationTest`는 **별도 PostgreSQL 테스트 DB**와 DB 환경 변수가 필요하며 Flyway·JPA validate·Querydsl의 Q 클래스 및 AND/OR 조회를 검증합니다. 검증용 엔티티와 SQL은 test 소스에만 있습니다.

루트 `.env`를 현재 터미널로 읽고 프로젝트 전용 도구를 쓰려면 먼저 다음을 실행합니다.

```powershell
. .\scripts\Use-DevEnvironment.ps1
```

Java 포맷은 IntelliJ 공유 XML과 Spotless `idea()`로 구성했고 이 PC의 IntelliJ IDEA 2025.1.4.1 CLI 적용을 확인했습니다. `IDEA_FORMATTER_PATH`를 본인의 IntelliJ 실행 파일로 지정하고 **실행 중인 IntelliJ를 종료한 뒤** `spotlessCheck` 또는 `spotlessApply`를 실행합니다. 팀 IDE·CLI·CI의 동일 포맷 결과와 버전 고정이 확인되기 전까지 Java 포맷을 `build`·CI의 필수 검사로 연결하지 않았습니다. 포맷 검사는 실행 환경이 없으면 실패하며 성공으로 건너뛰지 않습니다.

## 현재 구현 범위

- React Router·TanStack Query·공통 fetch·CSS Modules와 개발 확인 화면
- ESLint·Prettier·Vitest·React Testing Library, npm 잠금 파일
- Spring MVC·JPA·PostgreSQL·Flyway·OpenFeign Querydsl·Gradle Wrapper
- Spring Security 기본 접근 제한: `GET /api/v1/health`만 공개, 다른 요청은 차단
- Security OAuth2 Client·Resource Server 의존성 준비
- 프론트·백엔드 별도 CI 작업과 테스트 DB

**로그인·JWT 발급/검증·Refresh Token·Google/카카오 로그인·실제 공고/준비 모델은 아직 구현하지 않았습니다.** 현재 Security 설정은 인증 완료 구현이 아니라 초기 접근 차단 정책입니다. 인증 정책을 그대로 구현하는 다음 작업에서 필요한 경로만 개방하고 쿠키·CSRF 정책을 함께 적용합니다. 배포 자동화·AWS 리소스는 구성하지 않았습니다.

## 다음 문서

- [자동 작업 기록·문제 해결·의사결정 목록](docs/history/README.md)
- [프로젝트 문서 전체 안내](docs/README.md)
- [팀 컨벤션](docs/conventions/team-conventions.md)
- [코딩 컨벤션](docs/conventions/coding-conventions.md)
- [기획안](docs/planning/project-proposal.md) · [기획 세부안](docs/planning/project-details.md)
- [화면설계서](docs/design/screen-spec.md)
- [프로젝트 생성부터 개발 환경 설정까지](docs/getting-started.md)
- [저장소·API·배포 운영 원칙](docs/architecture.md)
- [프론트엔드 실행 안내](frontend/README.md)
- [백엔드 실행 안내](backend/README.md)
- [검증 기록·남은 설정](docs/verification.md)
- [실제 설치 버전 기록](docs/versions.md)

## 작업 기록 자동화

Codex는 루트 `AGENTS.md`에 따라 의미 있는 작업을 마치면 변경 이유·해결 과정·검증 결과를 `docs/history/entries/`에 기록합니다. 일반 커밋에서 기록이 누락된 경우에는 변경 파일 사실만 수집하도록 각 팀원이 clone 후 한 번 다음을 실행합니다.

```powershell
node scripts/project-history.mjs install
```

이 저장소의 로컬 Git hook만 설정하며 자동 커밋·push는 하지 않습니다. 판단 근거를 알 수 없는 항목은 ‘보완 필요’로 남깁니다. 설치·기록·부분 stage 보존·발표 활용 방법은 [작업 기록 안내](docs/history/README.md)를 확인합니다.

최초 기준 커밋은 저장소 생성 단계의 예외로 `main`에 반영하고, 같은 커밋에서 `develop`을 시작합니다. 이후 작업은 최신 `develop`에서 `chore/{이슈번호}-common-...` 등 작업 브랜치를 만들어 PR로 진행합니다. 원격 변경·push·배포는 해당 작업에서 승인된 범위에 따라 수행합니다.
