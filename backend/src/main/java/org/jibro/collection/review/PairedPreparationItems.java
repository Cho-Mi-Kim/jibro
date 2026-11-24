package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.attachments.HousingGraphic;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.local.GuidanceTableProjection;
import org.jibro.collection.review.local.DocumentContextReader;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.source.NoticeSourceInformationModels.Area;
import org.jibro.notice.source.NoticeSourceInformationModels.Section;

/**
 * Public, source-bound instructions for a single recruitment, independently of a full personal checklist.
 */
public final class PairedPreparationItems {
    private static final Pattern LAYOUT = Pattern.compile("[\\s\\p{Zs}\\u00ad]+");
    private static final Pattern LEAD = Pattern.compile("^(?:[■▪•]\\s*)?(?:(?:입주자\\s*)?모집\\s*공고일|신청일).{0,100}(?:현재|기준).{0,150}무주택.*");
    private static final Pattern DOC_START = Pattern.compile("^(?:[0-9]{1,2}[.．]?|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+|■)(?:당첨자발표및서류제출|공통제출서류|기본서류|추가제출서류|제출서류|신청서류|구비서류).*");
    private static final Pattern DOC_END = Pattern.compile("^(?:[0-9]{1,2}[.．]?|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+|■)(?:당첨발표|당첨자발표|예비입주자발표|신청시확인사항|유의사항|계약안내|분양전환|편의시설|입주안내).*");

    private record Page(String text, List<String> lines, String documents, Evidence evidence) {
    }

    private PairedPreparationItems() {
    }

    public static List<Section> read(Draft raw, String detailUrl) {
        if (raw == null || raw.checkedAt() == null || raw.sources() == null || raw.status() == null || !Set.of("READY", "PARTIAL").contains(raw.status()) || raw.sources().size() > 51)
            return List.of();
        for (var s : raw.sources()) {
            if (s == null || s.label() == null || s.url() == null || !s.url().matches("https://(?:apply\\.lh|www\\.lh)\\.or\\.kr/[^\\s]+") || s.sha256() == null || !s.sha256().matches("[0-9a-f]{64}") || OcrProvenance.contains(s))
                return List.of();
            if (HousingGraphic.matches(s.label(), s.url())) continue;
            if (!"EXTRACTED".equals(s.status()) || s.context() == null || s.context().isEmpty() || s.context().size() > 200
                    || s.context().stream().anyMatch(p -> p == null || p.location() == null || p.text() == null)
                    || s.context().stream().mapToLong(p -> p.text().length()).sum() > 1_000_000 || s.context().stream()
                    .anyMatch(p -> p.text() == null || p.text().isBlank() || p.text().contains("[발췌 길이 제한]")))
                return List.of();
        }
        var originals = raw.sources().stream().filter(s -> GuidanceTableProjection.structuredLabel(s.label()) && s.label().contains("공고")).toList();
        if (originals.size() != 1) return List.of();
        var original = originals.get(0);
        String stem = original.label().replaceFirst("(?i)\\.(?:hwpx|owpml)$", "");
        var pdfs = raw.sources().stream().filter(s -> s.label().equalsIgnoreCase(stem + ".pdf")).toList();
        if (pdfs.size() != 1) return List.of();
        var pdf = pdfs.get(0);
        // Other readable forms and the supply inventory are supporting material. An unpaired
        // governing recruitment or amendment must go through the full comparison.
        for (var s : raw.sources()) {
            if (s == original || s == pdf || s.url().equals(detailUrl) || HousingGraphic.matches(s.label(), s.url()))
                continue;
            if (!s.label().matches("(?i).*(?:양식|동의서|Q\\s*(?:[&＆]|[nN])\\s*A|공급주택보유목록).*(?:hwpx|pdf|xlsx|hwp)$")
                    || s.label().matches(".*(?:정정|수정|변경).*")) return List.of();
        }
        return readPair(raw.checkedAt(), original, pdf);
    }

    static List<Section> readPair(java.time.Instant checkedAt, Source original, Source pdf) {
        original = DocumentContextReader.normative(original);
        pdf = DocumentContextReader.normative(pdf);
        String day = checkedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        var pages = new ArrayList<Page>();
        boolean pdfVisitingOnly = OfficialGuidanceRows.visitingOnly(pdf);
        boolean originalVisitingOnly = OfficialGuidanceRows.visitingOnly(original);
        boolean inDocuments = false;
        for (Part part : pdf.context()) {
            if (!part.location().matches("공고문 [1-9][0-9]*쪽")) return List.of();
            String projection = GuidanceTableProjection.project(pdf, part,
                    (headers, values, heading) -> OfficialGuidanceRows.row(headers, values, heading, pdfVisitingOnly));
            var projectedPart = new Part(part.location(), projection);
            var reading = DocumentContextReader.read(pdf, projectedPart);
            if (!reading.valid()) return List.of();
            String text = reading.text(projection, 0, projection.length());
            var lines = text.lines().map(PairedPreparationItems::compact).filter(l -> !l.isEmpty()).toList();
            var documents = new StringBuilder();
            for (String line : lines) {
                if (line.length() <= 100 && DOC_START.matcher(line).matches()) inDocuments = true;
                else if (line.length() <= 100 && DOC_END.matcher(line).matches()) inDocuments = false;
                if (inDocuments) documents.append(line);
            }
            String pageUrl = EvidenceLocationUrl.forSource(pdf, part.location(), null);
            if (pageUrl.equals(pdf.url())) return List.of();
            pages.add(new Page(compact(text), lines, documents.toString(), new Evidence(pdf.label() + " · " + part.location(), pageUrl, day)));
        }
        var items = new LinkedHashMap<String, Section>();
        var ambiguous = new HashSet<String>();
        for (Part part : original.context()) {
            for (String paragraph : part.text().lines().map(String::strip).toList()) {
                if (paragraph.length() > 1450) continue;
                String title = documentInstruction(paragraph);
                if (title == null) continue;
                String text = paragraph.replaceFirst("^[■▪•※-]\\s*", "");
                var witnesses = pages.stream().filter(page -> page.documents().contains(compact(text))).toList();
                if (witnesses.size() == 1)
                    add(items, ambiguous, Area.DOCUMENT, title, text, witnesses.get(0).evidence());
            }
            String projected = GuidanceTableProjection.project(original, part,
                    (headers, values, heading) -> OfficialGuidanceRows.row(headers, values, heading, originalVisitingOnly));
            for (String rawLine : projected.lines().toList()) {
                String line = rawLine.strip();
                if (line.length() < 12 || line.length() > 1450 || line.startsWith("⟦")) continue;
                var rule = OfficialGuidanceItem.parse(line);
                if (rule != null) {
                    if (OfficialGuidanceItem.needsAgency(rule)) continue;
                    boolean document = rule.kind().equals("DOCUMENT_ITEM");
                    var evidence = pages.stream().filter(p -> (!document || p.documents().contains(compact(rule.title()))) && (supports(p, rule) || p.lines().contains(compact(line)) && (!document || p.documents().contains(compact(line))))).toList();
                    if (evidence.size() == 1) add(items, ambiguous, document ? Area.DOCUMENT : Area.CONDITION,
                            rule.title(), rule.value(), evidence.get(0).evidence());
                    else if (document && evidence.isEmpty()) addIssuance(items, ambiguous, pages, rule);
                } else if (LEAD.matcher(line).matches()) {
                    String text = line.replaceFirst("^[■▪•]\\s*", "");
                    var evidence = pages.stream().filter(p -> p.text().contains(compact(text))).toList();
                    if (evidence.size() == 1) {
                        add(items, ambiguous, Area.CONDITION, "공고일 기준 신청 자격", text, evidence.get(0).evidence());
                        qualificationNotes(part, pages, pages.indexOf(evidence.get(0)), items, ambiguous);
                    }
                }
            }
        }
        return items.values().stream().filter(s -> !ambiguous.contains(s.id())).limit(100).toList();
    }

    private static void qualificationNotes(Part part, List<Page> pages, int first, Map<String, Section> items, Set<String> ambiguous) {
        var lines = part.text().lines().map(String::strip).toList();
        for (int i = 0; i < lines.size(); i++) {
            String note = lines.get(i);
            String title;
            if (note.matches(".*(?:경우|자는)\\s*미성년자도\\s*(?:공급\\s*)?신청\\s*가능.*")) {
                title = "미성년 신청 예외";
                var criteria = new ArrayList<String>();
                criteria.add(note);
                int next = i + 1;
                while (next < lines.size() && lines.get(next).startsWith("-")) criteria.add(lines.get(next++));
                if (criteria.size() < 2) continue;
                note = String.join("\n", criteria);
            } else if (note.matches("※\\s*부부.{0,100}각각\\s*청약.*")) title = "부부 중복 신청";
            else if (note.matches("※\\s*배우자가\\s*국내\\s*거주하지\\s*않는.*신청.*")) title = "배우자 거주·등록 요건";
            else if (note.matches("※\\s*청약저축\\s*가입여부.*신청\\s*가능.*")) title = "청약저축·소득·자산 기준";
            else if (note.matches("•\\s*예비입주자\\s*선정은.*추첨.*")) title = "예비입주자 선정 방법";
            else if (note.matches("☞\\s*\\(모집권역\\s*제한\\s*완화\\).*신청\\s*가능.*")) title = "거주 지역 기준";
            else continue;
            if (note.length() > 1450) continue;
            String exact = compact(note);
            var matches = pages.stream().filter(page -> page.text().contains(exact)).toList();
            if (matches.size() == 1 && pages.indexOf(matches.get(0)) >= first && pages.indexOf(matches.get(0)) <= first + 2)
                add(items, ambiguous, Area.CONDITION, title, note.replaceFirst("^[※•☞]\\s*", ""), matches.get(0).evidence());
        }
    }

    private static final Pattern NAMED_DOCUMENT = Pattern.compile("주민등록(?:표)?\\s*(?:등본|초본)|가족관계증명서|외국인등록증|혼인관계증명서|금융정보\\s*등?\\s*제공\\s*동의서");

    private static String documentInstruction(String paragraph) {
        if (!(paragraph.startsWith("■") || paragraph.startsWith("•") || paragraph.startsWith("※") || paragraph.startsWith("-")))
            return null;
        var names = NAMED_DOCUMENT.matcher(paragraph).results().map(m -> m.group().replace(" ", "")).distinct().toList();
        if (names.isEmpty() || !paragraph.matches(".*(?:제출|발급|지참).*")
                || !paragraph.matches(".*(?:신청자|배우자|세대원|가구원|세대구성원|발급 시|발급시).*")
                || !paragraph.matches(".*(?:[.。]|합니다|있습니다|있음|제출|지참|발급|기재|제출\\s*\\([^()]+\\))$")) return null;
        return String.join("·", names);
    }

    // Check just the issuance instruction when the category or copies are in a vertically merged cell.
    private static void addIssuance(Map<String, Section> items, Set<String> ambiguous, List<Page> pages, GuidanceRuleGrammar.Rule rule) {
        for (String field : rule.value().split(" · ")) {
            if (!field.matches("^(?:비고|유의사항): .*")) continue;
            String note = field.substring(field.indexOf(':') + 1).strip();
            if (!note.contains("비치") && !note.contains("발급")) continue;
            var matches = pages.stream().filter(page -> interleavedRow(page, compact(rule.title()), compact(note))).toList();
            if (matches.size() == 1)
                add(items, ambiguous, Area.DOCUMENT, rule.title() + " 작성·발급 방법", note, matches.get(0).evidence());
        }
    }

    // Consume every character in both cells in column order across consecutive physical PDF lines.
    private static boolean interleavedRow(Page page, String left, String right) {
        if (left.isEmpty() || right.length() < 8 || right.length() > 800 || page.documents().isEmpty()) return false;
        for (int start = 0; start < page.lines().size(); start++) {
            var positions = Set.of(0);
            var span = new StringBuilder();
            for (int j = start; j < page.lines().size() && j < start + 20 && !positions.isEmpty(); j++) {
                String line = page.lines().get(j);
                int consumed = span.length();
                if (consumed + line.length() > left.length() + right.length()) break;
                span.append(line);
                var next = new HashSet<Integer>();
                for (int pos : positions) {
                    int notePos = consumed - pos;
                    for (int n = 0; n <= Math.min(left.length() - pos, line.length()); n++) {
                        if (!line.regionMatches(0, left, pos, n)) break;
                        if (right.regionMatches(notePos, line, n, line.length() - n)) next.add(pos + n);
                    }
                }
                if (span.length() == left.length() + right.length() && next.contains(left.length())
                        && page.documents().contains(span)) return true;
                positions = next;
            }
        }
        return false;
    }

    private static boolean supports(Page page, GuidanceRuleGrammar.Rule rule) {
        String title = compact(rule.title().replaceFirst("^배점 항목 · ", "").replaceFirst(" 자격$", ""));
        var values = Arrays.stream(rule.value().split(" · ")).map(s -> compact(s.replaceFirst(
                "^(?:구분|유의사항|비고|내용|MyMy서비스이용|부수|발급처|임대조건|배점):\\s*", ""))).toList();
        if (values.isEmpty() || values.stream().noneMatch(s -> s.length() >= 8)) return false;
        String row = title + String.join("", values);
        // Require the row's subject and first value to start together on the same physical PDF line.
        // Finding a document and a different row's eligibility note somewhere on one page is insufficient.
        for (int i = 0; i < page.lines().size(); i++) {
            String line = page.lines().get(i);
            if (!line.startsWith(title) || line.length() <= title.length() || !row.startsWith(line)) continue;
            StringBuilder joined = new StringBuilder(line);
            for (int j = i + 1; joined.length() < row.length() && j < page.lines().size(); j++)
                joined.append(page.lines().get(j));
            if (row.contentEquals(joined)) return true;
        }
        return false;
    }

    private static void add(Map<String, Section> items, Set<String> ambiguous, Area area, String title, String text, Evidence evidence) {
        String id = "paired-" + UUID.nameUUIDFromBytes((area + "/" + compact(title)).getBytes(StandardCharsets.UTF_8));
        Section incoming = new Section(id, area, title, text, evidence);
        var previous = items.putIfAbsent(id, incoming);
        if (previous != null && !previous.equals(incoming)) ambiguous.add(id);
    }

    private static String compact(String text) {
        return LAYOUT.matcher(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)).replaceAll("");
    }
}
