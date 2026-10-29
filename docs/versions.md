# 초기 설정 버전 기록

확인일: 2026-10-29. 프론트엔드의 최종 해석 버전은 package-lock.json을 기준으로 합니다.

| 항목 | 버전 |
| --- | --- |
| Node.js | v24.22.2 |
| npm | 11.19.1 |
| Spring Boot | 4.1.2 |
| Java Toolchain | 17 |
| Gradle Wrapper | 8.14.6 |
| Querydsl (OpenFeign) | 7.7 |
| Spotless | 8.10.4 |
| 로컬 PostgreSQL 이미지 | 17.6 |
| 로컬 IntelliJ CLI 검증 | 2025.1.4.1 |
| react | 19.3.1 |
| react-dom | 19.3.1 |
| react-router | 8.5.1 |
| @tanstack/react-query | 5.105.0 |
| typescript | 5.9.3 |
| vite | 8.3.5 |
| vitest | 5.0.5 |
| eslint | 10.13.0 |
| prettier | 3.9.10 |

Spring Boot가 관리하는 의존성은 임의로 별도 버전을 덮어쓰지 않습니다. Gradle·Node·IntelliJ 버전을 올릴 때는 팀과 CI의 호환성을 함께 확인합니다.
