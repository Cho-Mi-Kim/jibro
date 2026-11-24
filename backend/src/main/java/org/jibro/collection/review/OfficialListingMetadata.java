package org.jibro.collection.review;

import java.util.*;

import org.jibro.collection.review.local.DocumentContextReader;

import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;

/**
 * Recognizes only complete listing fields, never qualification prose or attachment filenames.
 */
final class OfficialListingMetadata {
    private static final String DATE = "20\\d{2}[.년/-]\\d{1,2}[.월/-]\\d{1,2}[.일]?";
    private static final String RANGE = "(?:" + DATE + ")?[~∼〜～–—-](?:" + DATE + ")?";
    private static final String DIFFERING_SCHEDULES = "입주자격에따라신청접수,선정결과발표및계약체결일정이상이하오니반드시입주자모집공고문을확인하여주시기바랍니다.";
    private static final Pattern FIELD = Pattern.compile(
            "공고상태(?:공고중|접수중|접수마감|접수종료|접수예정|공고종료|정정공고|공고취소)"
                    + "|유형(?:국민임대|영구임대|행복주택|공공임대|공공임대\\(50년\\)|매입임대|전세임대|통합공공임대)"
                    + "|공고일:?" + DATE
                    + "|(?:접수기간|서류접수기간|서류제출기간|계약기간):?(?:" + RANGE + "|" + DATE + ")"
                    + "|(?:서류제출대상자발표일|당첨자발표일|결과발표일):?" + DATE);

    private OfficialListingMetadata() {
    }

    static boolean displayPeriod(List<Source> sources, Evidence evidence) {
        return sources.stream().filter(s -> "공식상세본문".equals(compact(s.label()))
                        && s.url().equals(EvidenceLocationUrl.base(evidence.url()))).flatMap(s -> s.context().stream())
                .anyMatch(part -> part.location().equals(evidence.label()) && compact(part.text()).startsWith("접수기간")
                        && FIELD.matcher(compact(part.text())).matches());
    }

    static boolean representativeAnnouncement(List<Source> sources, Evidence evidence) {
        return sources.stream().filter(source -> source.url().equals(EvidenceLocationUrl.base(evidence.url())))
                .anyMatch(source -> source.context().stream().anyMatch(part -> part.location().equals(evidence.label())
                        && compact(part.text()).matches("(?:당첨자발표일|결과발표일):?" + DATE))
                        && source.context().stream().anyMatch(part -> compact(part.text()).equals(DIFFERING_SCHEDULES))
                        && repeatedGuidance(source, sources));
    }

    static boolean onlyFields(Source source) {
        if (!"공식상세본문".equals(compact(source.label()))
                || !Set.of("EXTRACTED", "EMPTY").contains(source.status())
                || source.sha256() == null || source.context().isEmpty()) return false;
        // An excerpt not accounted for by the field grammar must not disappear with its container.
        return source.context().stream().allMatch(part -> part != null && FIELD.matcher(compact(part.text())).matches())
                && source.excerpts().stream().allMatch(excerpt -> excerpt != null
                && FIELD.matcher(compact(excerpt.text())).matches());
    }

    /**
     * A listing may repeat guidance; only text found intact in a readable recruitment is redundant.
     */
    static boolean repeatedGuidance(Source listing, List<Source> sources) {
        if (!"공식상세본문".equals(compact(listing.label())) || !"EXTRACTED".equals(listing.status())
                || listing.sha256() == null || !listing.sha256().matches("[a-fA-F0-9]{64}") || listing.context().isEmpty()
                || org.jibro.collection.extraction.OcrProvenance.contains(listing)) return false;
        var readable = sources.stream().filter(source -> !source.url().equals(listing.url()) && "EXTRACTED".equals(source.status())
                && !"공식상세본문".equals(compact(source.label()))
                && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
                && !org.jibro.collection.extraction.OcrProvenance.contains(source)).toList();
        var bodies = readable.stream().flatMap(source -> source.context().stream().map(part -> {
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(source, part);
            return reading.valid() && !part.text().contains("[발췌 길이 제한]")
                    ? reading.text(part.text(), 0, part.text().length()) : "";
        })).flatMap(OfficialListingMetadata::paragraphs).map(OfficialListingMetadata::comparisonText).toList();
        if (bodies.isEmpty()) return false;
        var listingRanks = listingRanks(listing);
        var matching = listingRanks.isEmpty() ? List.<Source>of() : readable.stream()
                .filter(source -> rankTables(source).stream().anyMatch(listingRanks::equals)).toList();
        java.util.function.Predicate<String> verified = text -> listingField(text, matching, readable)
                || paragraphs(text).allMatch(paragraph -> FIELD.matcher(compact(paragraph)).matches()
                || bodies.stream().anyMatch(body -> body.equals(comparisonText(paragraph))));
        if (!listing.context().stream().allMatch(part -> verified.test(part.text()))) return false;
        // Classifier excerpts are lines of the complete row already verified with its parent
        // rank and all sibling criteria. A detached/moved excerpt is never new proof of scope.
        return listing.excerpts().stream().allMatch(excerpt -> verified.test(excerpt.text())
                || listing.url().equals(excerpt.url()) && listing.context().stream().anyMatch(part ->
                part.location().equals(excerpt.location()) && part.text().lines()
                        .anyMatch(line -> comparisonText(line).equals(comparisonText(excerpt.text())))));

    }

    private record RankRule(String priority, String criterion) {
    }

    private static final Pattern RANK_ROW = Pattern.compile("(?s)^구분\\s*:\\s*([1-9]순위)\\s*세부자격요건\\s*:\\s*(.+)$");
    private static final Pattern COMMON_ROW = Pattern.compile("구분:공통신청자격세부자격요건:(입주자모집공고일\\(20[0-9.]+\\)현재무주택세대구성원으로서,)아래의순위별자격요건에해당하는자");

    private static Map<String, List<RankRule>> listingRanks(Source source) {
        var result = new LinkedHashMap<String, List<RankRule>>();
        for (var part : source.context()) {
            var row = RANK_ROW.matcher(part.text().strip());
            if (!row.matches()) continue;
            var rules = new ArrayList<RankRule>();
            String body = row.group(2).strip();
            if (!body.startsWith("(")) rules.add(new RankRule("", comparisonText(body)));
            else for (String line : body.split("\\R(?=\\((?:우선|일반)\\))")) {
                var typed = Pattern.compile("(?s)^\\((우선|일반)\\)(.+)$").matcher(line.strip());
                if (!typed.matches()) return Map.of();
                rules.add(new RankRule(typed.group(1), comparisonText(typed.group(2))));
            }
            if (rules.isEmpty() || result.put(row.group(1), List.copyOf(rules)) != null) return Map.of();
        }
        return result;
    }

    private static List<Map<String, List<RankRule>>> rankTables(Source source) {
        var result = new ArrayList<Map<String, List<RankRule>>>();
        for (var part : source.context()) {
            var reading = DocumentContextReader.read(source, part);
            if (!reading.valid() || part.text().contains("[발췌 길이 제한]")) continue;
            for (var table : reading.tables()) {
                var grid = table.grid();
                if (grid == null || table.columns() != 4 || table.rows() < 2) continue;
                if (!cellText(reading, part.text(), grid[0][0]).equals("유형") || grid[0][0] != grid[0][1]
                        || !cellText(reading, part.text(), grid[0][2]).equals("대상")
                        || !cellText(reading, part.text(), grid[0][3]).equals("세부자격요건")) continue;
                var ranks = new LinkedHashMap<String, List<RankRule>>();
                boolean complete = true;
                for (int row = 1; row < table.rows(); row++) {
                    String rank = cellText(reading, part.text(), grid[row][0]);
                    String priority = grid[row][0] == grid[row][1] ? "" : cellText(reading, part.text(), grid[row][1]);
                    String criterion = cellText(reading, part.text(), grid[row][3]);
                    if (!rank.matches("[1-9]순위") || !Set.of("", "우선", "일반").contains(priority)
                            || criterion.isBlank() || grid[row][3].position().row() != row + 1
                            || grid[row][3].position().rowSpan() != 1 || grid[row][3].position().columnSpan() != 1) {
                        complete = false;
                        break;
                    }
                    ranks.computeIfAbsent(rank, ignored -> new ArrayList<>()).add(new RankRule(priority, criterion));
                }
                if (complete) result.add(ranks);
            }
        }
        return result;
    }

    private static String cellText(DocumentContextReader.Reading reading, String text, DocumentContextReader.BoundedCell cell) {
        return comparisonText(reading.text(text, cell.start(), cell.end()));
    }

    private static boolean listingField(String text, List<Source> matchingRanks, List<Source> readable) {
        String field = compact(text);
        // Empty LH form controls contain no application assertion. The operating-period field is
        // attached to an empty telephone control, not the application's intake period.
        if (field.matches("(?:접수기간|소재지):") || field.equals("안내사항")
                || field.matches("전화번호::운영기간:" + DATE + "[~∼〜～–—-]" + DATE)) return true;
        if (field.startsWith("문의처") && field.substring(3).matches("LH콜센터[0-9]{3,4}-[0-9]{4}")) {
            String contact = field.substring(3);
            return readable.stream().anyMatch(source -> source.context().stream().anyMatch(part -> {
                var reading = DocumentContextReader.read(source, part);
                return reading.valid() && reading.tables().stream().flatMap(table -> table.cells().stream())
                        .anyMatch(cell -> cellText(reading, part.text(), cell).equals(contact));
            }));
        }
        if (matchingRanks.isEmpty()) return false;
        if (field.equals("구분:세부자격요건") || RANK_ROW.matcher(text.strip()).matches()) return true;
        var common = COMMON_ROW.matcher(field);
        if (common.matches()) {
            String expected = "입주자격:" + common.group(1) + "아래의세부자격요건및소득기준을충족하는자";
            return matchingRanks.stream().anyMatch(source -> outsideTable(source).stream().anyMatch(body ->
                    Pattern.compile("(?:^|[■□])" + Pattern.quote(expected) + "(?=[“※■□]|$)").matcher(body).find()));
        }
        if (field.equals(DIFFERING_SCHEDULES))
            return matchingRanks.stream().anyMatch(source -> outsideTable(source).stream().anyMatch(body ->
                    Pattern.compile("(?:^|[▪■□])신청순위별접수,선정결과발표,계약체결의일정이상이하오니유의하여주시기바랍니다\\.(?=[■□]|$)").matcher(body).find()));
        return false;
    }

    private static List<String> outsideTable(Source source) {
        return source.context().stream().flatMap(part -> {
            var reading = DocumentContextReader.read(source, part);
            if (!reading.valid()) return java.util.stream.Stream.<String>empty();
            var prose = reading.segments().stream().filter(segment -> segment.context() == null || segment.context().cells().isEmpty())
                    .map(segment -> compact(part.text().substring(segment.start(), segment.end())));
            // A complete single-cell notice box carries the same whole statement as unboxed prose.
            // Multi-column tables and nested cells still require their own typed scope comparison.
            var boxes = reading.tables().stream().filter(table -> table.rows() == 1 && table.columns() == 1
                            && table.grid() != null && reading.tables().stream().noneMatch(other ->
                            other.start() > table.start() && other.end() < table.end()
                                    || other.start() < table.start() && other.end() > table.end()))
                    .map(table -> cellText(reading, part.text(), table.cells().get(0)));
            return java.util.stream.Stream.concat(prose, boxes);
        }).toList();
    }

    private static java.util.stream.Stream<String> paragraphs(String text) {
        return java.util.Arrays.stream(text.split("\\n\\s*\\n|\\n(?=\\s*[○•※-])"))
                .map(String::strip).filter(part -> !part.isEmpty());
    }

    private static String comparisonText(String text) {
        // Phone and list decorations carry no value; dates, operators, negation and limits remain.
        return compact(text.replaceAll("(?m)^\\s*[○•※-]\\s*", "").replace("☎", ""));
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
