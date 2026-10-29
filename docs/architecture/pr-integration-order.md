# 미병합 작업 PR과 통합 순서

기준일: 2026-10-30. 사용자 요청에 따라 모든 미병합 작업을 PR로 등록했다. 이번 작업에서는 병합하지 않았다.

| 순서 | PR | 작업 브랜치 | 현재 대상 브랜치 |
| --- | --- | --- | --- |
| 1 | [#3 구현 준비 문서](https://github.com/Cho-Mi-Kim/jibro/pull/3) | chore/docs-implementation-readiness | develop |
| 2 | [#4 프론트 기반](https://github.com/Cho-Mi-Kim/jibro/pull/4) | feature/frontend-foundation | chore/docs-implementation-readiness |
| 3 | [#5 사용자 화면 흐름](https://github.com/Cho-Mi-Kim/jibro/pull/5) | feature/frontend-user-flows | feature/frontend-foundation |
| 4 | [#6 프론트 시연·검증](https://github.com/Cho-Mi-Kim/jibro/pull/6) | feature/frontend-polish | feature/frontend-user-flows |
| 5 | [#7 인증](https://github.com/Cho-Mi-Kim/jibro/pull/7) | feature/backend-auth | feature/frontend-polish |
| 6 | [#8 공고·개인 준비·검수](https://github.com/Cho-Mi-Kim/jibro/pull/8) | feature/backend-housing | feature/backend-auth |
| 7 | [#9 공식 수집](https://github.com/Cho-Mi-Kim/jibro/pull/9) | feature/backend-collection | feature/backend-housing |
| 8 | [#10 실제 연동·최종 보완](https://github.com/Cho-Mi-Kim/jibro/pull/10) | feature/fullstack-integration | feature/backend-collection |

각 PR이 해당 작업의 차이만 보여주도록 앞 브랜치를 대상으로 삼았다. 앞 PR을 develop에 병합한 뒤 다음 PR의 base를 develop으로 바꾸어 검토·병합한다. 중간 브랜치에는 후속 보완이 포함되지 않으므로 전체 기능 확인은 마지막 브랜치를 사용한다. 팀의 merge commit·브랜치 보존 원칙을 따른다. 대상 변경 후 GitHub 검사와 충돌 상태를 다시 확인한다.

main은 Git 이력상 develop의 조상이 아니지만 이번 점검에서 파일 차이가 없었다. 별도 미반영 작업이 없어 빈 PR은 생성하지 않았다. 이전 작업 기록 자동화 PR #1·#2는 이미 병합된 이력이다.

로컬 통합 검증은 [검증 기록](../verification.md), 판단과 구현 한계는 [백엔드 계약](backend-contracts.md), 변경 과정은 [작업 기록](../history/entries/2026-10-30-backend-integration.md)을 확인한다. 원격 CI의 최신 상태는 각 PR의 Checks가 기준이다. 최종 연동 PR부터는 feature 대상 PR에도 검사가 실행되도록 CI 조건을 확장했다.
