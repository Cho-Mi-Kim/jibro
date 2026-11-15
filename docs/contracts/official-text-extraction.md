# 공식 본문·첨부 발췌 초안

> 2026-11-07 사용자 승인으로 [자동 검수 계약](automatic-review.md)을 추가했다. 아래의 수동 검수 전용 설명은 최초 구현 경계이며, 현재는 지원 규칙·근거 검증을 통과하면 자동 공개할 수 있다. 해석 불가/충돌/불완전 문맥은 계속 보류한다. 원문 전체 파싱 문맥은 관리자 발췌 DB에 보존하며, 읽기 실패는 최대2회 재시도한다.

> 2026-11-08 사용자가 남은 스캔 PDF·도면의 읽기 복구를 요청하여 로컬 OCR을 선택 기능으로 추가했다. OCR 읽기와 준비 안내 승인은 분리한다. 이 확장은 기존 텍스트 파싱 제한이나 개인 준비 기록을 변경하지 않는다.

## 범위와 공개 경계

사용자 요청 9번(2026-11-06)에 따라 과거 JIBRO 사이트의 본문·서류·일정 발췌 기능을 현재 저장소에서 새로 구현했다. 기존 사이트 소스는 재사용하지 않는다. 공식 API 목록 수집을 우선하고 공식 상세 페이지의 첨부 링크와 등록된 공식 첨부를 읽는다.

`CollectionItemProcessor`는 발췌 작업만 DB에 등록한다. `NoticeExtractionService`가 별도로 원문을 읽고 `ExtractionStore`에 관리자 초안을 보존한다. 공개 `notice.content`와 개인 준비 스냅샷을 자동으로 변경하지 않는다. 검수자가 출처를 대조하여 원고에 질문·서류·일정을 작성한 뒤 기존 검수 절차로 공개한다.

## 자료와 처리

- 공식 상세 페이지의 텍스트·첨부 링크, PDF 쪽별 텍스트, HWP 5 본문 구역, HWPX 문단, XLS/XLSX 셀과 ZIP 안의 PDF·HWP·HWPX·XLS·XLSX를 지원한다. 엑셀은 시트·행열·병합 위치와 표시 형식을 보존하고 수식 저장값을 명시하며 재계산하지 않는다. JS·매크로·외부 XML을 실행하지 않는다.
- ZIP 파일명은 UTF-8로 먼저 읽고, 파일명 디코딩 실패일 때만 MS949로 재시도한다. 한글 파일명이 있는 기존 공식 서식 ZIP을 위한 호환 처리다. 재시도는 원래의 압축 해제 바이트·항목·문자 예산을 새로 복사해 사용하고, 성공한 시도의 사용량만 반영한다. UTF-8 플래그를 가진 손상 이름, 문서 파싱 실패, 한도 초과를 다른 문자셋으로 우회하지 않는다. HWPX/XLSX 형식 식별도 동일한 파일명 문자셋으로 수행한다.
- 다운로드 주소는 기존 공식 첨부 전송 모듈을 공용으로 사용한다. HTTPS·정확한 호스트·443·공개 IP를 검사하고 리다이렉트·과대 응답·위장 HTML을 거절한다. DNS 사전 검사이며 연결 IP 고정 방식은 아니다.
- 문서 20 MiB/15초, HTML 2 MiB/15초, 일반 텍스트 파싱 8초, 압축 해제 24 MiB/300항목, PDF 200쪽, 텍스트 100만 자 제한. 파서는 요청마다 독립 Java 프로세스를 실행하고 전체 동시 작업을 2개로 제한한다. OCR은 별도 예산과 동시 작업 1개 제한을 추가 적용한다. 시간 초과·잘못된 출력·출력 4.5 MB 초과 시 자식과 그 하위 프로세스를 강제 종료한다. 종료가 확인되기 전에는 다음 작업을 허용하지 않는다.
- 일반 worker JVM의 heap 128 MiB, metaspace 64 MiB, direct memory 16 MiB를 제한한다. OCR worker만 heap을 256 MiB로 사용한다. 이는 JVM 영역별 제한이며 Tesseract를 포함한 전체 프로세스 RSS 상한이나 운영체제 접근 차단을 의미하지 않는다. 서버와 별개의 주소 공간으로 파서 오류·메모리 고갈을 격리하고, 프로세스 환경은 `SystemRoot/WINDIR`(Windows) 및 작업별 임시 경로만 전달한다. Tesseract에는 `OMP_THREAD_LIMIT=1`을 추가한다. DB·JWT·API 키, `JAVA_TOOL_OPTIONS` 등 부모의 환경 설정은 상속하지 않는다.
- worker는 부모가 없어지거나 자체 기한(일반 10초, OCR 305초)이 지나면 종료한다. 호출 측 제한은 각각 8초와 300초다. 정상 종료·실패·타임아웃·호출 취소 후 임시 작업 디렉터리를 정리한다. 운영체제가 서버를 강제 종료한 경우 남을 수 있는 임시 파일은 배포 환경의 임시 저장소 수명·정리 정책을 따른다. 실패한 작업의 다음 시도는 새 프로세스로 실행한다.
- 취소와 실행기 종료가 연속으로 interrupt해도 기존 2초 종료 확인 예산 안에서 정리를 마치고 interrupt 상태를 복원한다. Windows의 종료 직후 작업 디렉터리 핸들 해제 지연에는 소유한 임시 경로 삭제만 최대 1초의 공유 예산으로 재시도한다. 일반 텍스트 실행 8초 제한은 유지하며 접근 거부나 끝내 해제되지 않은 파일 오류를 성공으로 숨기지 않는다.
- 한번에 첨부 3개를 읽은 뒤 남은 파일은 같은 DB 작업을 자동으로 이어 처리한다. 파일 하나를 마칠 때마다 checkpoint로 진행과 실행 시각을 갱신한다. RUNNING 유효 시간은 `ExtractionStore.RUNNING_LEASE`의 10분이며 자동 회수와 명시적 재시도에 같은 기준을 쓴다. 10분을 넘긴 중단 작업은 제한된 재시도 대상으로 회수하고, 회수된 이전 claim token의 늦은 결과는 반영하지 않는다. 같은 공고의 활성 작업은 하나다.
- `CONDITION`·`DOCUMENT`·`SCHEDULE`은 문구 탐색 분류이며 자격 판정·필수 서류 규칙이 아니다. 원문 위치·주소·해시를 남기고 최대 90개/자료, 한 문구 1,200자 발췌 한도를 표시한다.
- 이미지 위주의 PDF와 HWP 내포 이미지는 아래의 로컬 OCR 범위에서 추가로 읽는다. 암호화·배포 제한, 텍스트 미검출, 지원하지 않는 이미지, 한도 초과는 직접 확인 필요로 남긴다. 본문을 읽었지만 분류 키워드가 없는 문서는 EXTRACTED와 빈 excerpts로 보존한다. 키워드 미검출을 읽기 실패나 조건 없음으로 해석하지 않는다. 생성형 AI에 이미지를 보내거나 사용자 증빙을 업로드하는 기능은 이 OCR 경로에 포함하지 않는다.
- 원본 파일은 worker로 전달할 때 `java.io.tmpdir` 아래 무작위 작업 디렉터리에 잠시 기록하며, OCR용 임시 PNG와 함께 worker 종료 확인 후 삭제한다. 파일을 상시 보관하지 않으며 DB에는 발췌 초안과 해시·시각을 저장한다. 개발 검증에서 따로 보존한 원문·렌더 이미지는 Git에서 제외된 `.tools/qa/` 자료이며 서비스의 원본 보관 기능이 아니다. 공식 API의 JSON 응답 보관은 [별도 계약](collection-response-archive.md)이고 PDF 과거 원본 보관 기능과 다르다.

## 로컬 OCR의 읽기·검수 경계

일반 텍스트 파싱이 성공한 뒤 PDF/HWP의 글자가 부족한 부분에 한해 `BoundedDocumentParser → OcrDocumentParser → TesseractOcr`를 실행한다. 기존 native 텍스트는 보존하고 OCR 결과를 별도 `Part`로 덧붙인다. OCR 문자열의 숫자·단어를 추정하여 정정하지 않는다. 외부 OCR·모델 API로 이미지나 판독문을 전송하지 않으며 별도 API 키가 필요 없다.

현재 지원 범위는 PDF의 전체 페이지 렌더와 HWP 5 `BinData`의 JPG/PNG/BMP/TIFF/GIF 이미지다. HWP 내포 이미지에는 파일 위치와 쪽 번호 확인 필요를 남긴다. ZIP 안의 PDF/HWP, HWPX 이미지, 다중 프레임 이미지를 OCR로 복구하는 경로는 포함하지 않는다. 기본 PDF/HWP 텍스트 파싱 자체가 실패한 경우도 이 fallback의 성공으로 간주하지 않는다.

| 항목 | 코드에서 확인한 제한 |
| --- | --- |
| OCR 문서 실행 | 내부 295초에서 미완료 반환, 부모 300초, worker watchdog 305초 |
| 이미지별 Tesseract 실행 | 판독 방식별 최대 45초, 문서의 남은 예산을 넘기지 않음 |
| OCR 동시 실행 | 1개, 전체 파서 동시 실행 2개 안에서 수행 |
| OCR worker heap | 256 MiB; 일반 텍스트 worker는 128 MiB 유지 |
| PDF·HWP 범위 | PDF 전체 40쪽 이하, HWP BinData 최대 40항목 |
| 이미지 크기 | 렌더/축소 후 한 이미지 1,000만 픽셀, 작업 누적 2억 5,000만 픽셀 |
| 출력·입력 | 기존 문서 입력 20 MiB, IPC 4.5 MB, 텍스트 100만 자 제한 유지 |

이 값은 `OcrDocumentParser`, `BoundedDocumentParser`, `DocumentParserWorker`의 구현과 대조한 설정이다. 배포 환경에서의 성능·실제 문서 인식 정확도를 뜻하지 않으며, 이후 한도 변경 시 코드·worker·실행 유효 시간·계약을 함께 갱신한다.

읽힌 내용이 있으면 `Source.status=EXTRACTED`로 기록하지만 **읽기 성공은 준비 안내 PASS가 아니다**. OCR `Part.location`에는 `[OCR · 원문 대조 필요]`를 붙이고 공개 원문 구간의 제목과 출처에도 유지한다. 판독하지 못한 부분은 빈 텍스트와 해당 위치를 남기며 메시지에서 일부 미판독을 알린다.

PDF는 전체 페이지의 PSM1 기본 판독과 PSM6 표 중심 판독, HWP 내포 도면은 PSM3 및 PSM11 희소 글자 판독을 별도 Part로 보존한다. 단일 스캔 이미지는 원래 해상도를 넘어 불필요하게 확대하지 않는다. 두 호출이 정상 종료하고 한 방식에서만 글자를 읽었으면 빈 결과만 생략한다. 두 방식 모두 비거나 오류·시간초과가 발생하면 읽힌 Part와 미판독 표식을 유지하고 전체 완료로 표시하지 않는다. 미판독 자료는 최대2회 재시도 후 PARTIAL로 남긴다. 도면은 공개 상세의 `REFERENCE` 자료로 별도 표시하고 조건·서류·일정 영역에 넣지 않는다. 기본 영역별3구간, 도면10구간·구간6000자 한도는 전체 도면의 의미 해석을 뜻하지 않는다.

OCR을 포함한 기준 문서는 `OCR_SOURCE_REQUIRES_REVIEW`로 자동 안내 승인·공식 일정 확정을 차단한다. `LocalRuleInterpreter`도 해당 자료를 확정 사실로 변환하지 않는다. 일반 원문이 별도로 있어도 OCR 판독 결과 자체를 검수 완료로 간주하지 않는다. 명시적 보조 평면도·배치도·위치도는 기존 범위 정책에 따라 규칙 해석에서 제외하되 공식 첨부 링크를 보존한다. 제목에 정정·조건·제출 등의 기준 성격이 있으면 도면이라는 이유만으로 제외하지 않는다.

도면의 치수·면적·방향·배치는 OCR 글자만으로 확인할 수 없다. 기존 공식 첨부 링크로 도면 이미지와 대조해야 한다. 조건·서류·일정 키워드가 없는 도면을 임의의 개인 조건이나 제출 서류로 분류하지 않는다. 공개 원문 구간은 읽기 보조이며 준비 시작·개인별 질문을 활성화하는 경로가 아니다.

명시적 원문 대조에서 `AttachmentFingerprintClient.fetchForSourceReview`는 PNG/JPEG의 파일 시그니처와 원본 SHA-256을 확인할 수 있다. 이는 도면 원본의 변경 여부를 확인하기 위한 경로이며 일반 `fetch`/`download`의 문서 전용 형식 제한을 넓히지 않는다. 이미지 파싱·OCR·준비 안내 승인을 수행하지 않는다. 실제 시각 대조 기록은 `REVIEWED` 상태와 해당 위치·설명을 남기고 자동 추출 성공인 `EXTRACTED`로 위장하지 않는다. 공식 호스트·공개 IP·리다이렉트 거절·위장 HTML 거절과 기존 명시 검토의 100 MiB/60초 제한을 유지한다.

## worker 배포 계약

- `documentParserJar`는 파서·OCR 호출 코드와 필요한 PDFBox·POI 의존성을 담은 실행 jar를 만든다. `bootJar`의 `BOOT-INF/classes/worker/document-parser-worker.jar`에 포함한다. 일반 텍스트 파싱에는 별도 설치가 필요 없지만 OCR에는 Tesseract 실행 파일과 언어 모델을 별도로 설치해야 한다. 앱 실행 중 자동 다운로드하지 않는다.
- `bootRun`과 테스트는 Gradle이 만든 같은 worker jar를 사용한다. 두 실행 경로 모두 worker 빌드에 의존한다. 운영 jar는 내장 worker를 작업 디렉터리로 복사해 절대 경로의 `java.home/bin/java`로 실행한다.
- JRE 17이 필요하다. 읽기 전용 파일시스템에서는 `java.io.tmpdir`(배포 기본 `/tmp`)에 worker jar와 최대 20 MiB 입력을 쓸 수 있어야 한다. 실행 계정은 비관리자이며 writable 임시 저장소를 별도로 제공한다. 작업마다 jar를 복사하므로 이 공간의 여유 용량도 확인한다.
- 제한된 버전·길이 검증을 가진 바이너리 IPC만 읽으며 worker의 예외·stderr·문서 본문을 로그에 출력하지 않는다. Spring·DB·HTTP 수집 기능은 worker에 넣지 않는다.

### 선택 설정과 이 컴퓨터의 설치 확인

`JIBRO_OCR_EXECUTABLE`은 Tesseract 실행 파일, `JIBRO_OCR_DATA_PATH`는 `kor.traineddata`, `eng.traineddata`, `osd.traineddata`가 함께 있는 디렉터리의 **절대 경로**다. 두 설정이 모두 없으면 OCR을 사용하지 않는다. 한쪽만 있거나 파일이 없으면 판독 실패로 남기며 일반 텍스트 결과를 검수 완료로 승격하지 않는다. Java 시스템 속성 `jibro.ocr.executable`, `jibro.ocr.data-path`를 쓰는 경우 환경변수보다 우선한다. 실행 파일을 PATH에서 임의 탐색하지 않는다.

이 checkout의 `.env` 예시는 다음과 같다. 로컬 환경 로더 `scripts/Use-DevEnvironment.ps1`는 명시적 설정이 없으면 설치 경로와 세 모델 파일의 존재를 확인해 같은 경로를 자동 연결한다. 다른 컴퓨터·운영 서비스는 실제 설치 위치를 지정하고 서버 프로세스에 전달해야 한다.

```dotenv
JIBRO_OCR_EXECUTABLE=C:/Program Files/Tesseract-OCR/tesseract.exe
JIBRO_OCR_DATA_PATH=C:/Users/Administrator/Documents/jibro/.tools/tessdata-fast
```

2026-11-08 이 컴퓨터에서 `tesseract --version`이 `v5.5.3.20260724`, 해당 모델 경로의 `--list-langs`가 `eng`, `kor`, `osd`를 반환함을 확인했다. 설치 파일은 [Tesseract 공식 5.5.3 release](https://github.com/tesseract-ocr/tesseract/releases/tag/5.5.3)의 `tesseract-ocr-w64-setup-5.5.3.20260724.exe`이며, 다운로드 기록과 파일의 SHA-256을 대조했다.

| 파일 | SHA-256 |
| --- | --- |
| Windows 설치 파일 | `bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4` |
| `eng.traineddata` | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| `kor.traineddata` | `6b85e11d9bbf07863b97b3523b1b112844c43e713df8b66418a081fd1060b3b2` |
| `osd.traineddata` | `9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff` |

세 모델은 [공식 tessdata_fast의 고정 revision](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd) `87416418657359cb625c412a48b6e1d6d41c29bd`에서 받았다. 로컬 기록은 `.tools/ocr-install/download.json`, `.tools/ocr-install/fast-models.json`이며 설치 파일·모델은 Git에 올리지 않는다. 모델을 교체할 때는 버전과 SHA를 다시 기록하고 기존 수치와 혼용하지 않는다.

같은 표에서 best와 fast를 비교했다. best는 25쪽 공고의 두 방식 판독을 시간 한도 내에 끝내지 못했으며, fast는 244.968초에 끝냈다. fast에서도 날짜와 주요 서류 항목을 읽었으나 철자·숫자 오독 가능성이 남는다. 이 선택은 제한 시간 내 원문 열람을 제공하기 위한 것이며 정확도 동등성이나 자동 검수 승인을 뜻하지 않는다. best 비교 모델은 `.tools/tessdata`에 별도로 보존한다.

### Linux 설치와 운영 배포

Tesseract 공식 문서는 엔진과 언어 모델을 별도 설치하도록 안내한다. Ubuntu/Debian 계열에서는 다음처럼 엔진과 한국어·영어 패키지를 설치하고 실제 버전·경로를 확인한다. 이 명령은 설치 예시이며 이 작업에서 Linux에 실행한 결과가 아니다. [공식 설치 안내](https://tesseract-ocr.github.io/tessdoc/Installation.html)

```sh
sudo apt-get update
sudo apt-get install --no-install-recommends tesseract-ocr tesseract-ocr-kor tesseract-ocr-eng tesseract-ocr-osd
command -v tesseract
tesseract --version
tesseract --list-langs
```

배포판 패키지 버전·기본 모델은 Windows에서 확인한 버전과 다를 수 있다. 같은 `tessdata_fast` 모델로 검증하려면 위 revision의 세 파일을 별도 읽기 전용 디렉터리(예: `/opt/jibro/tessdata`)에 배치하고 표의 SHA와 대조한다. 그 디렉터리를 `JIBRO_OCR_DATA_PATH`에 지정하고 설치 확인 결과의 실행 파일 경로를 `JIBRO_OCR_EXECUTABLE`에 지정한다. 모델 형식과 라이선스는 [해당 revision의 공식 README](https://github.com/tesseract-ocr/tessdata_fast/blob/87416418657359cb625c412a48b6e1d6d41c29bd/README.md)를 따른다.

현재 `deploy/backend.Dockerfile`에는 Tesseract와 언어 모델이 **포함되어 있지 않다**. 컨테이너에서 OCR을 사용하려면 엔진 설치·모델 제공·두 환경변수 전달을 별도로 준비하고, 비관리자 실행 계정이 실행 파일과 모델을 읽고 작업용 임시 디렉터리에 쓸 수 있는지 검증해야 한다. JVM 제한과 별도로 Tesseract 자식 프로세스까지 포함한 컨테이너 메모리·CPU 예산을 검증한다. Windows 설치 확인을 Linux/컨테이너 배포 검증으로 간주하지 않는다.

## 관리자 경로

`/admin`의 공고 검수 항목 → **공식 원문 발췌 초안**. 본문·첨부 발췌, 진행 상태, 자료별 발췌/원문 열기/복사, 실패 자료 재발췌를 제공한다. 관리자 권한이 아닌 사용자는 발췌 API에 접근할 수 없다.

확인한 첨부 목록을 반영하면 기존 원고 저장과 같이 버전이 증가하고 공개가 보류된다. 새로운 첨부가 없다면 공개 상태를 변경하지 않는다. 발췌 중 공고 revision이 바뀌면 `SUPERSEDED`로 남기고 이전 발췌를 보존하며 새 원고에 적용하지 않는다. 편집 중 원고와 발췌 반영을 동시에 처리하지 않도록 UI도 제한한다.

## 형식 구현 근거

- [Apache PDFBox 3.0.8](https://pdfbox.apache.org/download.cgi)
- [Apache POI 6.0.0](https://poi.apache.org/download.html), [POIFS 사용법](https://poi.apache.org/components/poifs/how-to.html)
- [jsoup 1.24.1](https://jsoup.org/download)
- [한컴 공개 문서 형식](https://www.hancom.com/support/downloadCenter/hwpOwpml)

이 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.

## 실제 확인과 제한

2026-11-06 LH 공개 문서를 직접 읽어 상세 페이지 5개 첨부 링크, PDF 11쪽(14,864자), HWP 6,266자, HWPX 4,443자의 발췌를 확인했다. 모든 유형·기관의 문서 정확도를 검증한 것은 아니다. 관리자 원문 검수는 계속 필요하다.

2026-11-07 이 컴퓨터에서 LH API 키 등록과 실제 16건 수집을 확인했다. 기존 공고의 관리자 발췌는 키 없이 공식 원문을 읽는다. 운영 배포·상시 서버·SMTP 연결은 별도다.
