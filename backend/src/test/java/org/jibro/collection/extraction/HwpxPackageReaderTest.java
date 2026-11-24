package org.jibro.collection.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HwpxPackageReaderTest {
    private static final int EXPANDED = 24 * 1024 * 1024;
    private final DocumentTextParser parser = new DocumentTextParser();

    @Test
    void explicitListStartsLevelsAndRestartsFollowManifestOrderAcrossSections() throws Exception {
        var entries = container();
        entries.put("Contents/header.xml", bytes("<head>"
                + numbering("1", 3, "<paraHead level='1' start='3' numFormat='DIGIT'>^1.</paraHead>"
                + "<paraHead level='2' start='2' numFormat='DIGIT'>^1.^2)</paraHead>")
                + numbering("2", 7, "<paraHead level='1' start='7' numFormat='DIGIT'>(^1)</paraHead>")
                + style("1", "1", 0) + style("2", "1", 1) + style("3", "2", 0) + "</head>"));
        entries.put("Contents/section1.xml", bytes("<root>" + numberedParagraph("1", "이어지는 공통 기준")
                + numberedParagraph("2", "하위 수준 재시작") + numberedParagraph("3", "새 목록 기준") + "</root>"));
        entries.put("Contents/section0.xml", bytes("<root>" + numberedParagraph("1", "소득 70%")
                + numberedParagraph("2", "자녀 2명") + numberedParagraph("2", "예외 1명") + "</root>"));
        entries.put("Contents/content.hpf", bytes("<package><manifest>"
                + "<item id='a' href='Contents/section0.xml'/><item id='b' href='Contents/section1.xml'/>"
                + "</manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>"));
        var parts = parser.parse(zip(entries), "application/zip");
        assertEquals("3. 소득 70%\n3.2) 자녀 2명\n3.3) 예외 1명\n", parts.get(0).text());
        assertEquals("4. 이어지는 공통 기준\n4.2) 하위 수준 재시작\n(7) 새 목록 기준\n", parts.get(1).text());
    }

    @Test
    void memoStoryKeepsItsNumbersAndTargetTextWithoutChangingBodyCounter() throws Exception {
        var entries = container();
        entries.put("Contents/header.xml", bytes("<head>" + numbering("1", 1,
                "<paraHead level='1' start='1' numFormat='DIGIT'>^1.</paraHead>") + style("1", "1", 0) + "</head>"));
        entries.put("Contents/section0.xml", bytes("<root>" + numberedParagraph("1", "주택 기준")
                + "<p><run><ctrl><fieldBegin id='memo' type='MEMO'><subList>"
                + numberedParagraph("1", "편집자 검토 메모") + "</subList></fieldBegin></ctrl>"
                + "<t>신청자가 확인할 원래 본문</t><ctrl><fieldEnd beginIDRef='memo'/></ctrl></run></p>"
                + numberedParagraph("1", "필수 서류") + "</root>"));
        String text = parser.parse(zip(entries), "application/zip").get(0).text();
        assertTrue(text.contains("⟦HWPX:MEMO⟧\n1. 편집자 검토 메모\n⟦HWPX:/MEMO⟧\n신청자가 확인할 원래 본문"));
        assertTrue(text.endsWith("2. 필수 서류\n"));
    }

    @Test
    void unverifiedHeaderFooterAndNoteNumberStoriesCannotAdvanceBodyNumbers() throws Exception {
        for (String region : List.of("header", "footer", "footNote", "endNote")) {
            var entries = container();
            entries.put("Contents/header.xml", bytes("<head>" + numbering("1", 1,
                    "<paraHead level='1' start='1' numFormat='DIGIT'>^1.</paraHead>") + style("1", "1", 0)
                    + "<paraPr id='2'><heading type='NONE'/></paraPr></head>"));
            String before = "<root>" + numberedParagraph("1", "본문 공통 기준") + "<" + region + ">";
            String after = "</" + region + ">" + numberedParagraph("1", "본문 예외") + "</root>";
            entries.put("Contents/section0.xml", bytes(before + numberedParagraph("1", "별도 번호 기준") + after));
            var failure = assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
            assertEquals("HWPX 별도 영역 자동번호 확인 필요: " + region, failure.getMessage());
            entries.put("Contents/section0.xml", bytes(before + numberedParagraph("2", "번호 없는 설명") + after));
            String text = parser.parse(zip(entries), "application/zip").get(0).text();
            assertTrue(text.contains("번호 없는 설명"));
            assertTrue(text.endsWith("2. 본문 예외\n"));
        }
    }

    @Test
    void ambiguousNumberStartsTemplatesAndMissingSpineAreNotGuessed() throws Exception {
        for (String definition : List.of(numbering("1", 0, "<paraHead level='1' start='1' numFormat='DIGIT'>^1.</paraHead>"),
                numbering("1", 1, "<paraHead level='1' start='1' numFormat='UNKNOWN'>^1.</paraHead>"),
                numbering("1", 1, "<paraHead level='1' start='1' numFormat='DIGIT'>^2.</paraHead>"))) {
            var entries = container();
            entries.put("Contents/header.xml", bytes("<head>" + definition + style("1", "1", 0) + "</head>"));
            entries.put("Contents/section0.xml", bytes("<root>" + numberedParagraph("1", "조건 150%") + "</root>"));
            assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        }
        var entries = container();
        entries.put("Contents/content.hpf", bytes("<package><manifest><item id='a' href='Contents/section0.xml'/></manifest></package>"));
        entries.put("Contents/section0.xml", xml("본문"));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test
    void actualOfficialNumberedParagraphsAndMemoRemainSourceBound() throws Exception {
        String inje = officialHwpx("lh-inje-69338134.hwpx");
        assertTrue(inje.contains("1. 신분별 입주소득 기준(공공주택 특별법 시행규칙 별표3)"));
        String osan = officialHwpx("lh-osan-68931436.hwpx");
        assertTrue(osan.contains("1. (공통) 재계약을 위하여는"));
        assertTrue(osan.contains("2. (소득완화) 완화된 소득기준을 초과하는 경우에는"));
        String daegu = officialHwpx("lh-daegu-69028523.hwpx");
        int numberedMemo = daegu.indexOf("1. 예비신혼부부 여부 추가");
        assertTrue(numberedMemo >= 0);
        String memo = daegu.substring(daegu.lastIndexOf("⟦HWPX:MEMO⟧", numberedMemo), daegu.indexOf("⟦HWPX:/MEMO⟧", numberedMemo));
        assertTrue(memo.contains("1. 예비신혼부부 여부 추가\n2. 세대원정보 추가"));
    }

    private String officialHwpx(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/" + name)) {
            assertNotNull(input);
            return parser.parse(input.readAllBytes(), "application/zip").stream().map(ExtractionModels.Part::text)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private static String numbering(String id, int start, String levels) {
        return "<numbering id='" + id + "' start='" + start + "'>" + levels + "</numbering>";
    }

    private static String style(String id, String numbering, int level) {
        return "<paraPr id='" + id + "'><heading type='NUMBER' idRef='" + numbering + "' level='" + level + "'/></paraPr>";
    }

    private static String numberedParagraph(String style, String text) {
        return "<p paraPrIDRef='" + style + "'><t>" + text + "</t></p>";
    }

    @Test
    void paragraphStyleKeepsLiteralBulletInBodyAndTable() throws Exception {
        var entries = container();
        entries.put("Contents/header.xml", bytes("<head><bullet id='1' char='※' useImage='0'/>"
                + "<paraPr id='7'><heading type='BULLET' idRef='1' level='0'/></paraPr>"
                + "<paraPr id='8'><heading type='NONE' idRef='0' level='0'/></paraPr></head>"));
        entries.put("Contents/section0.xml", bytes("<root><p paraPrIDRef='7'><t>공통 예외</t></p>"
                + "<tbl rowCnt='1' colCnt='1'><tr><tc><subList><p paraPrIDRef='7'><t>소득 70%</t></p></subList>"
                + "<cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/></tc></tr></tbl>"
                + "<p paraPrIDRef='8'><t>원문 숫자 2명</t></p></root>"));
        int[] budget = {EXPANDED, 300};
        var parts = HwpxPackageReader.readIfHwpx(zip(entries), budget, 200, 1_000_000);
        assertEquals("※공통예외※소득70%원문숫자2명", HwpxStructuredText.withoutStructure(parts.get(0).text()).replaceAll("\\s+", ""));
        assertTrue(parts.get(0).text().contains("col=1 rowspan=1 colspan=1⟧\n※ 소득 70%"));
        assertEquals(EXPANDED - entries.get("mimetype").length - entries.get("Contents/header.xml").length
                - entries.get("Contents/content.hpf").length - entries.get("Contents/section0.xml").length, budget[0]);
    }

    @Test
    void usedUnresolvedBulletCannotSilentlyBecomePlainText() throws Exception {
        for (String definition : List.of("", "<bullet id='1' char='-' useImage='1'/>")) {
            var entries = container();
            entries.put("Contents/header.xml", bytes("<head>" + definition
                    + "<paraPr id='7'><heading type='BULLET' idRef='1' level='0'/></paraPr></head>"));
            entries.put("Contents/section0.xml", bytes("<root><p paraPrIDRef='7'><t>확인이 필요한 목록</t></p></root>"));
            var failure = assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
            assertEquals("HWPX 문단 목록 스타일 확인 필요", failure.getMessage());
            entries.put("Contents/section0.xml", xml("사용하지 않은 스타일은 본문이 아님"));
            assertEquals("사용하지 않은 스타일은 본문이 아님\n", parser.parse(zip(entries), "application/zip").get(0).text());
        }
    }

    @Test
    void undefinedNumberingAndUnsupportedParagraphStylesRequireReview() throws Exception {
        for (String heading : List.of("NUMBER", "OUTLINE", "UNKNOWN")) {
            var entries = container();
            entries.put("Contents/header.xml", bytes("<head><paraPr id='7'><heading type='" + heading
                    + "' idRef='1' level='1'/></paraPr></head>"));
            entries.put("Contents/section0.xml", bytes("<root><p paraPrIDRef='7'><t>두 자녀</t></p></root>"));
            assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
            entries.put("Contents/header.xml", bytes("<head><paraPr id='8'><heading type='NONE'/></paraPr></head>"));
            assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
            entries.remove("Contents/header.xml");
            assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        }
    }

    @Test
    void officialBookletRetainsStyledSourceBulletAlsoPrintedInItsPdf() throws Exception {
        byte[] hwpx, pdf;
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-2026.hwpx")) {
            assertNotNull(input);
            hwpx = input.readAllBytes();
        }
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-two-up.pdf")) {
            assertNotNull(input);
            pdf = input.readAllBytes();
        }
        String body = HwpxStructuredText.withoutStructure(parser.parse(hwpx, "application/zip").get(0).text()).replaceAll("\\s+", "");
        String page = PdfLayoutTextStripper.withoutStructure(parser.parse(pdf, "application/pdf").get(4).text()).replaceAll("\\s+", "");
        assertTrue(body.contains("공공일자리소득노인일자리사업,장애인일자리사업,공공근로등에참여한대가로얻는소득-고용노동부일모아시스템"));
        assertTrue(page.contains("소득-고용노동부일모아시스템"));
    }

    @Test
    void officialBookletInlineArrowsKeepTheirActualFlowCells() throws Exception {
        String text = officialHwpx("lh-multichild-2026.hwpx");
        for (int column : List.of(2, 4, 6, 8, 10)) {
            var cell = java.util.regex.Pattern.compile("⟦HWPX:CELL table=14 row=1 col=" + column
                    + " rowspan=1 colspan=1(?: left=(?:NONE|DRAWN|UNKNOWN) right=(?:NONE|DRAWN|UNKNOWN))?(?: top=(?:NONE|DRAWN|UNKNOWN) bottom=(?:NONE|DRAWN|UNKNOWN))?⟧\\s*→\\s*⟦HWPX:/CELL⟧");
            assertTrue(cell.matcher(text).find(), "신청 절차 표의 화살표 열 " + column);
        }
    }

    @Test
    void officialSangokFlowArrowsUseSourceImageCropCoordinatesAndKeepTwoRowSpans() throws Exception {
        String text = officialHwpx("lh-sangok-69385652.hwpx");
        for (int column : List.of(2, 4, 6, 8)) {
            var cell = java.util.regex.Pattern.compile("⟦HWPX:CELL table=5 row=1 col=" + column
                    + " rowspan=2 colspan=1(?: left=(?:NONE|DRAWN|UNKNOWN) right=(?:NONE|DRAWN|UNKNOWN))?(?: top=(?:NONE|DRAWN|UNKNOWN) bottom=(?:NONE|DRAWN|UNKNOWN))?⟧\\s*→\\s*⟦HWPX:/CELL⟧");
            assertTrue(cell.matcher(text).find(), "입주자 선정절차 표의 화살표 열 " + column);
        }
        assertTrue(text.contains("’26.11.23.(월)"));
        assertTrue(text.contains("’27.4.15.(목)"));
    }

    @Test
    void officialBukguDoubleBordersRetainTheirDeclaredWidthAndSides() throws Exception {
        String text = officialHwpx("lh-bukgu-69296396.hwpx");
        assertTrue(text.contains("CELL table=45 row=1 col=4 rowspan=1 colspan=1 doubleright=0.5mm doublebottom=0.5mm⟧"));
        assertTrue(text.contains("CELL table=45 row=2 col=5 rowspan=1 colspan=1 doubleleft=0.5mm doubletop=0.5mm⟧"));
        assertTrue(text.contains("CELL table=45 row=5 col=4 rowspan=1 colspan=1 doubleright=0.5mm⟧"));
        assertFalse(HwpxStructuredText.withoutStructure(text).contains("doublebottom="));
    }

    @Test
    void officialSangokSignedAmountBordersAndFloatingExplanationMatchPrintedLayout() throws Exception {
        String text = officialHwpx("lh-sangok-69385652.hwpx");
        assertTrue(text.contains("CELL table=8 row=5 col=7 rowspan=1 colspan=1 left=DRAWN right=NONE⟧\n(+)"));
        assertTrue(text.contains("CELL table=8 row=5 col=8 rowspan=1 colspan=1 left=NONE right=DRAWN⟧\n44,000,000"));
        int explanation = text.indexOf("행복주택의 신청자격별 소득·자산기준은");
        int additiveBox = text.indexOf("⟦HWPX:TABLE id=13 ");
        assertTrue(explanation >= 0 && additiveBox > explanation);
        assertTrue(text.indexOf("중복시 합산 적용됩니다.", explanation) < additiveBox);
        assertBeforeTable(text, "(참고) 무허가건물 확인 절차", 45);
        assertBeforeTable(text, "사회보장정보시스템 제공 소득‧재산 항목 설명 및 자료 출처", 47);
        assertBeforeTable(text, "이 공고에 명시되지 않은 사항은", 49);
    }

    private static void assertBeforeTable(String text, String title, int table) {
        int heading = text.indexOf(title), body = text.indexOf("⟦HWPX:TABLE id=" + table + " ");
        assertTrue(heading >= 0 && body > heading, title);
    }

    @Test
    void cellBordersUseExplicitHeaderValuesAndDoNotGuessZoneOverrides() throws Exception {
        var entries = container();
        entries.put("Contents/header.xml", bytes("<head><borderFill id='1'><leftBorder type='SOLID'/><rightBorder type='NONE'/></borderFill>"
                + "<borderFill id='2'><leftBorder type='NONE'/><rightBorder type='SOLID'/></borderFill></head>"));
        String table = "<tbl rowCnt='1' colCnt='2'><tr><tc borderFillIDRef='1'><p><t>(+)</t></p>"
                + "<cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/></tc><tc borderFillIDRef='2'>"
                + "<p><t>44,000,000</t></p><cellAddr rowAddr='0' colAddr='1'/><cellSpan rowSpan='1' colSpan='1'/></tc></tr></tbl>";
        entries.put("Contents/section0.xml", bytes("<root>" + table + "</root>"));
        String text = parser.parse(zip(entries), "application/zip").get(0).text();
        assertTrue(text.contains("col=1 rowspan=1 colspan=1 left=DRAWN right=NONE⟧"));
        assertTrue(text.contains("col=2 rowspan=1 colspan=1 left=NONE right=DRAWN⟧"));
        entries.put("Contents/section0.xml", bytes("<root>" + table.replace("<tr>", "<cellzoneList><cellzone startRowAddr='0' startColAddr='0' endRowAddr='0' endColAddr='0' borderFillIDRef='2'/></cellzoneList><tr>") + "</root>"));
        text = parser.parse(zip(entries), "application/zip").get(0).text();
        assertTrue(text.contains("col=1 rowspan=1 colspan=1⟧"));
        assertTrue(text.contains("col=2 rowspan=1 colspan=1 left=NONE right=DRAWN⟧"));
        entries.remove("Contents/header.xml");
        assertFalse(parser.parse(zip(entries), "application/zip").get(0).text().contains("left="));
    }

    @Test
    void transformedOrFloatingImagesCannotBecomeUntransformedArrows() throws Exception {
        byte[] image;
        String original;
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-2026.hwpx");
             var archive = new ZipInputStream(Objects.requireNonNull(input))) {
            image = null;
            String section = null;
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().equals("BinData/image1.jpg")) image = archive.readAllBytes();
                else if (entry.getName().equals("Contents/section0.xml"))
                    section = new String(archive.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertNotNull(image);
            assertNotNull(section);
            int start = section.indexOf("<hp:pic ");
            original = section.substring(start, section.indexOf("</hp:pic>", start) + "</hp:pic>".length());
        }
        var entries = container();
        entries.put("BinData/image1.jpg", image);
        entries.put("Contents/content.hpf", bytes("<package><manifest><item id='s' href='Contents/section0.xml'/>"
                + "<item id='image1' href='BinData/image1.jpg'/></manifest><spine><itemref idref='s'/></spine></package>"));
        String opening = "<root xmlns:hp='http://www.hancom.co.kr/hwpml/2011/paragraph' xmlns:hc='http://www.hancom.co.kr/hwpml/2011/core'><p><t>신청</t>";
        entries.put("Contents/section0.xml", bytes(opening + original + "<t>심사</t></p></root>"));
        assertEquals("신청→심사\n", parser.parse(zip(entries), "application/zip").get(0).text());
        for (String adjusted : List.of(original.replace("angle=\"0\"", "angle=\"90\""),
                original.replace("horizontal=\"0\"", "horizontal=\"1\""),
                original.replace("left=\"0\"", "left=\"1\""),
                original.replace("treatAsChar=\"1\"", "treatAsChar=\"0\""),
                original.replace("effect=\"REAL_PIC\"", "effect=\"GRAY_SCALE\""))) {
            entries.put("Contents/section0.xml", bytes(opening + adjusted + "<t>심사</t></p></root>"));
            assertEquals("신청심사\n", parser.parse(zip(entries), "application/zip").get(0).text());
        }
    }

    @Test
    void verifiedHwpxReadsEveryBodySectionWithoutInflatingEmbeddedImages() throws Exception {
        var entries = container();
        entries.put("BinData/image.bmp", new byte[EXPANDED + 1]);
        entries.put("Contents/section0.xml", xml("본문 첫 구역"));
        entries.put("Contents/section1.xml", xml("두 번째 구역의 예외"));
        int[] budget = {EXPANDED, 300};
        var parts = HwpxPackageReader.readIfHwpx(zip(entries), budget, 200, 1_000_000);
        assertEquals(List.of("본문 첫 구역\n", "두 번째 구역의 예외\n"), parts.stream().map(ExtractionModels.Part::text).toList());
        assertTrue(parts.get(1).location().contains("section1.xml"));
        assertEquals(300 - entries.size(), budget[1]);
        assertEquals(EXPANDED - entries.get("mimetype").length - entries.get("Contents/section0.xml").length
                - entries.get("Contents/section1.xml").length - entries.get("Contents/content.hpf").length, budget[0]);
    }

    @Test
    void identityAndManifestAreBothRequiredToSkipBinaryExpansion() throws Exception {
        var entries = container();
        entries.put("huge.bin", new byte[EXPANDED + 1]);
        entries.put("Contents/section0.xml", xml("본문"));
        entries.remove("mimetype");
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("mimetype", bytes("application/zip"));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("mimetype", bytes("application/hwp+zip"));
        entries.remove("Contents/content.hpf");
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test
    void bodyXmlEntryCountAndTotalTextKeepTheirOriginalLimits() throws Exception {
        var entries = container();
        entries.put("Contents/section0.xml", new byte[EXPANDED + 1]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("Contents/section0.xml", xml("a".repeat(600_000)));
        entries.put("Contents/section1.xml", xml("b".repeat(400_001)));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("Contents/section0.xml", xml("본문"));
        entries.remove("Contents/section1.xml");
        for (int i = 0; i < 298; i++) entries.put("BinData/image" + i, new byte[0]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test
    void nestedHwpxSharesOuterEntryAndExpandedByteBudget() throws Exception {
        var entries = container();
        entries.put("Contents/section0.xml", xml("공통 예외 조건"));
        byte[] inner = zip(entries);
        var parts = parser.parse(zip(Map.of("notice.hwpx", inner)), "application/zip");
        assertEquals("notice.hwpx · HWPX Contents/section0.xml (쪽 번호 확인 필요)", parts.get(0).location());
        assertThrows(IOException.class, () -> HwpxPackageReader.readIfHwpx(inner, new int[]{1, 300}, 200, 1_000_000));
        assertThrows(IOException.class, () -> HwpxPackageReader.readIfHwpx(inner, new int[]{EXPANDED, 2}, 200, 1_000_000));
    }

    @Test
    void noBodyOrTooManyBodySectionsIsNotSuccessfulExtraction() throws Exception {
        var entries = container();
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        for (int i = 0; i < 201; i++) entries.put("Contents/section" + i + ".xml", xml("구역"));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test
    void corruptSectionChecksumCannotBecomeExtractedText() throws Exception {
        var entries = container();
        entries.put("Contents/section0.xml", xml("검토할 본문"));
        byte[] archive = zip(entries);
        var fields = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        boolean changed = false;
        for (int offset = 0; offset < archive.length - 46; offset++) {
            if (fields.getInt(offset) != 0x02014b50) continue;
            int length = Short.toUnsignedInt(fields.getShort(offset + 28));
            String name = new String(archive, offset + 46, length, StandardCharsets.UTF_8);
            if (name.equals("Contents/section0.xml")) {
                fields.putInt(offset + 16, fields.getInt(offset + 16) ^ 1);
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        assertThrows(IOException.class, () -> parser.parse(archive, "application/zip"));
    }

    private static Map<String, byte[]> container() {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("mimetype", bytes("application/hwp+zip"));
        entries.put("Contents/content.hpf", bytes("<package/>"));
        return entries;
    }

    private static byte[] xml(String text) {
        return bytes("<root><p><t>" + text + "</t></p></root>");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        if (entries.containsKey("Contents/content.hpf") && Arrays.equals(entries.get("Contents/content.hpf"), bytes("<package/>"))) {
            List<String> sections = entries.keySet().stream().filter(name -> name.matches("Contents/section\\d+\\.xml")).toList();
            entries.put("Contents/content.hpf", bytes("<package><manifest>" + sections.stream()
                    .map(name -> "<item id='" + name + "' href='" + name + "'/>").collect(java.util.stream.Collectors.joining())
                    + "</manifest><spine>" + sections.stream().map(name -> "<itemref idref='" + name + "'/>")
                    .collect(java.util.stream.Collectors.joining()) + "</spine></package>"));
        }
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
