# 서류 발급처와 공식 양식 링크 공통 표시

다섯 화면의 DocumentCard가 서류 요약의 links를 DocumentIssuanceLinks에 전달한다. 발급처 및 양식 주소·안내 문구는 onlineIssuanceLinks와 documentSummary에서 계산하고, DocumentIssuanceLink와 해당 CSS 모듈에서 실제 링크와 아이콘, 주석을 표시한다. 검수 중 확인한 서식도 같은 링크를 사용한다. 이전 기록은 선택 버전에 저장된 주소만 표시한다.

AI 보조로 구현. review/local-updates-20261115, eb64b60 기준. 전체 프런트 테스트 505개, 빌드·린트·계층·서식 검사 통과. 두 저장 버전의 같은 서류에서 각 버전의 양식 주소를 검증했다. Chrome 공통 카드 미리보기 360·390·768px에서 키보드 펼침, 양식 링크 표시 및 가로 넘침을 확인했다. 외부 발급 사이트 접속 및 실제 기기 검증은 수행하지 않았다.
