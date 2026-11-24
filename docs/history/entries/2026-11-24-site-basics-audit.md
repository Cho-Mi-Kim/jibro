# 사이트 기본 운영 항목 점검

사용자가 Google Analytics, 문의 답변 시간, 검색결과 설명, 맞춤형404, 이미지 대체 텍스트, robots.txt, 개인정보처리방침의 존재 여부를 질문했다. 구현 요청으로 확대하지 않고 현재 코드와 jibro.live의 공개 응답만 읽었다.

## 확인 결과
- Google Analytics: 프런트/서버/배포 설정에서 추적 스크립트와 연동 코드를 찾지 못했다. 운영 초기 HTML에도 추적 스크립트 없음.
- 서비스 문의 답변 시간: 집으로 자체 문의의 답변 예상 시간·운영 시간 안내를 찾지 못했다. 가이드의 공식 기관 문의 안내와 구분했다.
- 검색결과 설명: frontend/index.html에는 제목만 있고 description/og:description 없음. 운영 초기 HTML에서도 설명 메타 태그 없음.
- 404: App.tsx의 wildcard 경로에 찾을 수 없음/홈 복귀 안내는 있다. Nginx SPA fallback으로 없는 일반 경로도 HTTP200을 반환한다.
- 대체 텍스트: AgencyMark 로고의 alt와 pdf-viewer.html의 공고문 페이지 alt 확인. 공통 장식 Icon은 aria-hidden. 전체 접근성 준수 인증으로 확대하지 않는다.
- robots.txt: 파일 없음. 운영 /robots.txt는 HTTP200 text/html 및 앱 root를 반환한다. sitemap.xml도 동일한 HTML fallback이다.
- 개인정보처리방침: 전용 경로·문구·링크를 찾지 못했다. /privacy도 앱 HTML fallback이므로 정책 페이지 존재의 증거가 아니다. 공고 첨부의 개인정보 동의서와 서비스 정책은 구분했다.

## 검증·범위
관련 코드 문자열·정적 파일·라우터·Nginx 설정을 확인하고, 운영 /, /robots.txt, /sitemap.xml, /privacy, 임의 미존재 경로의 상태/콘텐츠 유형/초기 HTML을 확인했다. 계정·추적·DNS·코드·운영 설정은 변경하지 않았다. 자동 테스트나 브라우저 접근성 전수 검사는 실행하지 않았다. 개인정보처리방침의 법적 적합성 검토나 검색엔진 실제 노출 검증을 한 것으로 기록하지 않는다. AI가 조회·정리를 보조했다.
