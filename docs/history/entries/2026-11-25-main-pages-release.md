# main 승격 검토 중 발견한 Pages 내부 링크 수정

- 기록일: 2026-11-25 (Asia/Seoul)
- 기록 상태: 실행 근거 정리
- 작업 상태: Pages 링크 수정 후 검증 진행. 최신 CI와 실제 배포 결과는 [main 반영 PR #32](https://github.com/Cho-Mi-Kim/jibro/pull/32)의 후속 코멘트에서 확인한다.
- 유형: 문제 해결·배포·협업
- 담당·검토: 사용자가 develop → main PR·검토·병합과 Pages 배포 확인을 요청했다. Codex가 검토·수정·검사를 수행했다.

## 배경·문제

develop `99e7fe3`의 변경을 main으로 반영하는 PR #32를 생성했다. #31의 검토·수정 내용과 #30의 배포 기록을 포함하며 초기 PR CI 5개는 모두 통과했다.

Pages 배포 경계를 추가 확인하던 중 `public-site.mjs`가 생성하는 문의·개인정보·404 페이지의 홈·공고·문서 링크가 루트 경로를 사용함을 확인했다. Pages는 `/jibro/` 하위 경로와 HashRouter를 사용하므로 이 링크로 앱을 벗어나면 잘못된 주소로 이동한다. 통계 설정 패널의 개인정보 링크도 `/privacy`로 고정되어 있었다. 기존 자동 검사에는 이 하위 경로 동작을 검증하는 사례가 없었다.

## 고려한 선택지·결정

CI 통과만으로 main을 병합하지 않고 `fix/pages-public-links-20261125` 브랜치에서 수정한다. 사용자 지침에 따라 수정 브랜치의 PR을 develop에 먼저 병합한 후, 갱신된 develop → main PR의 CI를 다시 확인한다. 실제 서버의 일반 경로와 Pages의 정적 문서·해시 경로를 구분하며 외부 링크·공식 자료 주소는 바꾸지 않는다.

## 변경·해결 과정

- Vite의 `pages-demo` 모드·base를 공개 문서 생성기에 전달한다. 생성한 HTML 중 확인된 내부 링크만 `/jibro/` 아래로 변환한다.
- 홈·공고·통계 설정은 HashRouter 주소로, 문의·개인정보는 `.html` 파일 주소로 연결한다. 404 화면의 복귀 버튼도 같은 정책을 사용한다.
- `sitePageHref`를 공통으로 사용해 앱 하단과 통계 설정의 정적 문서 링크를 일치시킨다.
- 문서 생성 plugin의 실제 출력과 앱의 통계 설정 재열기 동작에 회귀 검사를 추가한다. 운영 모드의 일반 경로와 외부 메일 주소가 유지되는지도 확인한다.
- 전체 검사에서 `FollowUpFlow` 첫 화면이 지연 로딩 중인 상태로 기본 1초 버튼 대기를 넘긴 실패를 관찰했다. 같은 테스트의 다음 일정 화면과 동일하게 첫 화면 버튼 대기를 최대 5초로 지정했다. 실제 신청 기록·양방향 공유 단정과 제품 로딩 코드는 유지하며 전역 제한은 바꾸지 않는다. 실패 당시 DOM과 로그는 `.tools/review/pages-first-flow-failure.log`에 보존했다.
- 운영 서버·DB·OAuth 설정이나 배포 권한은 변경하지 않는다.

## 검증·결과

- 변경 파일 Prettier 적용 및 diff 검사 완료.
- 프론트 전체 `npm run ci`와 `npm run build:pages` 결과는 커밋 전 확인한다.
- 최초 추가 테스트의 미지원 `exact` 옵션 타입 오류를 바로잡았다. 이후 정적 문서 6개 검사는 통과했고, 화면 테스트 888개 중 첫 지연 로딩 대기 1개가 실패했다. 위 대기 보완 후 전체 검사를 재실행하며 최종 결과를 PR 코멘트에 남긴다.
- PR #32의 최초 CI는 [run 45460935072](https://github.com/Cho-Mi-Kim/jibro/actions/runs/45460935072) 5개 성공. 수정 반영 후 최신 head의 CI가 다시 필요하다.
- 배포 전 기존 Pages 홈 접속과 시연 모드 배너를 확인했다. 이는 새 버전 배포 성공의 근거가 아니며, 최종 배포 커밋·workflow·브라우저 검증은 PR 후속 코멘트에 별도로 남긴다.

## 기여·한계

코드·자동 검사·브라우저 확인은 Codex가 수행한다. 실제 외부 로그인·서버 저장·운영 배포 검증으로 해석하지 않는다. 기존 작업 폴더의 포트폴리오·포스터 미커밋 문서를 건드리지 않고 별도 worktree를 사용했다.

## 발표·포트폴리오 메모

CI 성공과 배포 경로 검토를 함께 사용해 정적 시연 환경에서만 발생하는 링크 오류를 발견하고, 운영 경로를 유지하면서 환경별 이동을 검증했다. 사용자 직접 구현 실적으로 단정하지 않는다.

## 후속 작업

- 수정 PR → main 반영 PR 순으로 최신 CI와 변경을 확인하고 병합한다.
- 해당 main 커밋의 Pages 배포 성공과 실제 홈·공고·준비 흐름, 안내 문서 복귀·새로고침을 확인한다.

## 변경 파일

- `frontend/scripts/public-site.mjs`, `frontend/scripts/public-site.node-tests.mjs`
- `frontend/src/features/site/siteLinks.ts`, `frontend/src/features/site/SiteFooter.tsx`
- `frontend/src/features/analytics/AnalyticsConsent.tsx`, `frontend/src/features/analytics/AnalyticsConsent.test.tsx`
- `frontend/src/app/FollowUpFlow.test.tsx` — 첫 지연 로딩 화면의 제한된 대기
- `docs/history/entries/2026-11-25-main-pages-release.md`, `docs/history/README.md`
