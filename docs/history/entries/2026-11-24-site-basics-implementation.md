# 공개 운영 안내·방문 통계·문의 메일 구현

- 기록일: 2026-11-24 (Asia/Seoul)
- 기록 상태: 근거 정리·담당자 검토 전
- 작업 상태: 구현·운영 반영·문의 실제 전달 완료, GA 보고서 수신 검증 대기
- 유형: 개발 / 문제 해결 / 운영
- 담당·검토: AI 구현·검증 보조, 운영 문구·계정 생성·DNS 저장과 테스트 발송은 사용자 확정. 팀 검토자 미확인.
- 관련 커밋: 기준 eb64b60, review/local-updates-20261115. 기존 미커밋 변경 보존, 이번 커밋·push 없음.

## 배경·문제

기본 운영 항목 감사에서 GA·문의 답변 시간·검색 설명·robots.txt·서비스 개인정보처리방침이 없고, 없는 일반 경로도 SPA fallback으로 HTTP200을 반환함을 확인했다. 사용자가 미구현 항목 구현과 구매한 도메인 문의 주소 수신 연결을 요청했다. 이미지 alt와 기초 React404는 이미 있었으므로 존재 여부와 개선을 구분했다.

## 고려한 선택지·결정

- 문의 수신은 유료 메일함·네임서버 이전 대신 무료 ImprovMX의 support 별칭만 기존 Brevo 가입 Gmail로 전달한다. 가입 계정 주소와 전달 수신 주소를 구분한다. 기존 Brevo 발신 SMTP·웹 A·DKIM·DMARC는 보존한다.
- 문의·개인정보 안내를 앱 내부에서만 제공하면 사람 확인과 로그인 실행에 종속되므로 독립 HTML을 빌드한다. 공개 문구의 단일 소유자는 data/site/public-site.json이다.
- Nginx의 별도 수동 경로 목록을 만들지 않고 실제 App.tsx Route 속성에서 허용 패턴을 생성한다. 없는 경로는 맞춤 HTML과 실제404, 유효한 앱 경로는 SPA를 제공한다. 공고 ID 존재 여부는 API가 판단한다.
- GA는 사용자 선택 전 외부 태그를 불러오지 않는 기본 동의 방식이다. 계정 ID·조건 답변·메모·검색어·인증 URL·공고 식별값을 보내지 않고 공개 화면 종류만 수동 page_view로 보낸다. 거절해도 서비스 이용 가능하며 하단에서 변경·철회한다.

## 변경·해결 과정

- frontend/scripts/public-site.mjs: description/canonical/OG, 문의·정책·404 HTML, robots·sitemap, 실제 라우트 기반 app-routes.conf 생성. Vite 개발 서버에서도 공개 문서를 제공한다.
- features/site: 공통 하단 링크, React404, 공개 경로 메타 정보와 개인 화면 noindex. VisitorGate에도 공개 정책 링크를 제공한다.
- features/analytics: 동의 저장180일, 선택 전 태그 미로드, 운영 origin만 허용, 광고 신호 비활성, URL 제거, 개인 경로 전송 중단, 철회 시 분석 쿠키 삭제. 쿠키 최대30일. 공개 측정 ID는 무시되는 production.local 환경 파일에 설정하고 비밀값을 프런트로 전달하지 않는다.
- GA 계정·웹 스트림 등록: 사용자 최종 약관 동의. 향상된 측정 비활성 확인, 사용자/이벤트 보관2개월, 새 활동 시 재설정 끄기, 세부 위치/기기 수집 끄기. 광고 데이터는 코드의 consent/allow_* 설정으로 차단하며 Google Ads 등 제품을 연결하지 않았다.
- 문의 안내는 사용자 확정대로 집으로 운영팀 / 평일09~18시(휴일 제외) / 영업일3일 이내 답변 목표. support@jibro.live로 문의.
- DNS MX 10/20 및 SPF1개를 사용자 승인 후 저장. 4개 권한 DNS 모두 MX2개·단일SPF·기존 Brevo TXT 확인. ImprovMX 기본 전체 주소(*) 별칭을 support로 바꾸고 지정 Gmail로만 전달한다.
- 승인 테스트1통: Brevo SMTP 수락 후 ImprovMX의 ENTERED QUEUE → DELIVERED, Gmail 수신 서버의 2.0.0 OK 확인(01:39:10 KST). 받은편지함/스팸 분류나 사용자 열람까지 확인한 것은 아니다.
- 최종 운영 활성 경로는 /opt/jibro/release-site-basics-google-tag-20261124. 최초 공개 안내 배포 이후 Google 신규 태그 형식 보완을 반영했다. 각 적용에서 258개 산출물 해시·Nginx 구문·운영 설정 검증 후 웹만 교체했다. 기존 backend 컨테이너 ID·DB·사람 확인·OAuth/메일 런타임 보존, 이전 배포 경로 유지.

## 검증·결과

- frontend npm run ci: 서식·lint·계층·원고·공개 문서2개·타입·877개 테스트·빌드 통과한 실행을 확인했다. 이후 통계 연결 보완 중 기존 FollowUpFlow의 첫 동적 일정 화면 조회만 전체 병렬 검사에서 다시 시간 초과했다. 개별6/6와 나머지 같은 흐름은 통과했으며, 화면이 로드된 내용을 검사하는 목적에 맞춰 해당 비동기 조회의 1초 기본 한도만5초로 명시했다. 제품 동작이나 전체 테스트 제한은 바꾸지 않았다. 최종 변경 후 전체 재검증 결과는 아래 최종 확인을 따른다.
- python -m unittest discover -s scripts/ops -p test_*.py: 27개 통과.
- 운영 smoke_web: 공개 문서200, robots text/plain, sitemap XML, 임의 경로/누락 asset404, health/DB UP 및 보안 헤더. 별도로 미확인 방문자의 notices API403 및 사람 확인 활성 유지 확인.
- 실제 Opera: 동의 전 Google 태그0개, 허용 후1개, 거절·설정 다시 열기 확인. 방문 통계 실제 수신 보고서는 최종 확인 중이다.
- 실제 브라우저 크기390px 문의·동의 UI, 360px 정책·404 확인. 문서 가로 넘침 없음, 동의 버튼46px 이상, 하단 탐색과 간격 확인. 실제 휴대전화 확인은 아님.
- 증거는 무시되는 .tools/qa/site-basics-* 기록·스크린샷, 공개 smoke JSON, CI 로그에 있다. 개인 수신 주소·키·쿠키는 저장소 문서에 복사하지 않았다.

## 기여·한계

AI가 기존 코드 확인, 공통 구현, DNS 준비·승인 후 저장, 배포·테스트를 보조했다. 계정 생성·약관은 사용자가 수행했다. 개인정보처리방침은 실제 기능과 설정을 설명하는 운영 문서이며 법적 적합성 인증을 받은 것으로 주장하지 않는다. 검색 결과의 실제 노출·순위는 확인하지 않았다. 일반 없는 경로404와 없는 공고 ID의 SPA/API 처리는 구분한다.

## 발표·포트폴리오 메모

단순 화면404를 실제HTTP404와 분리하고, 공개 문서와 앱 라우트 생성 경로를 통합했다. 선택적 분석은 거절/철회와 개인정보 제외를 부정 검사로 검증했다. 메일은 발송 수락에서 멈추지 않고 Gmail 서버 전달 결과로 검증했다. 개인·팀 성과의 귀속은 담당자 검토 후 사용한다.

## 후속 작업

- GA 실제 수신 보고서 확인과 그 결과 기록.
- Google의 신규 등록 화면이 발급한 GTM 태그와 목적지 측정 ID를 함께 사용하도록 연결을 정리했다. `config` 직접 초기화 경로는 제거하고, 동의 업데이트와 `set` 개인정보 설정을 `js` 이전에 적용한다. 공개 Google 코드의 목적지/태그 ID 대응과 브라우저의 실제 로드까지 확인했다. 실제 집계 확인 전에는 외부 실연동 완료라고 주장하지 않는다.

## 최종 확인

- 최종 태그 형식 및 일정 테스트의 비동기 조회 한도 보완 후 frontend CI 전체877개·공개문서2개·계층10개·타입·서식·lint·빌드 통과. 운영 도구27개도 통과했다. Java 수정·서버 교체는 없었다.
- 운영 브라우저에서 발급된 GTM 태그의 실제 로드 완료와 콘솔 오류 없음 확인. 사용자가 직접 접속·새로고침도 수행했다. Google 실시간 보고서는 최종 조회 시0이므로 실제 데이터 수신 성공은 아직 확인하지 못했다. 초기 반영 지연 여부와 다른 원인은 단정하지 않는다.
- Tag Assistant는 별도 검사 창 연결 시간이 초과되어 수신 성공 근거로 사용하지 않았다. 브라우저 보호 기능을 해제하거나 새 확장 기능을 설치하지 않았다. Windows 개발도구를 열려던 시도도 창 geometry/focus 문제로 검증 근거에 포함하지 않았다.
- 공개 문의·정책·404는 360/390px 브라우저로 확인했고, 실제 휴대전화 및 브라우저 글자 확대 검증은 별도 미확인이다.
- 공식 구현 근거: https://developers.google.com/tag-platform/gtagjs , https://developers.google.com/tag-platform/gtagjs/configure , https://developers.google.com/tag-platform/security/guides/consent?consentmode=basic . 설치와 실제 수신을 구분하는 근거: https://support.google.com/analytics/answer/10201247 .
- 운영자 변경·보유 정책·외부 사업자 변경 시 공개 JSON과 실제 설정을 함께 검토한다. 법적 검토·검색엔진 수집은 별도 확인한다.

## 변경 파일

data/site/public-site.json, frontend/scripts/public-site.*, frontend/vite.config.ts, frontend/src/features/site/*, frontend/src/features/analytics/*, frontend/src/main.tsx, frontend/src/app/{App,Shell}.tsx, frontend/src/features/security/VisitorGate.tsx, frontend/package.json, frontend/.env.example, deploy/{web.Dockerfile,nginx/default.conf.template,README.md}, scripts/ops/smoke_web.py.
