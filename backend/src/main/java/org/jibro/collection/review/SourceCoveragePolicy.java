package org.jibro.collection.review;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;

/**
 * Distinguishes recruitment evidence from metadata and explicitly ancillary attachments.
 */
public final class SourceCoveragePolicy {
    private SourceCoveragePolicy() {
    }

    public record Coverage(Draft draft, Content original, List<Source> scheduleSources,
                           List<VerifiedPreparationForms.Block> formBlocks, List<String> issues) {
        public boolean ready() {
            return issues.isEmpty();
        }
    }

    private static final Pattern AUXILIARY = Pattern.compile("평면도|배치도|위치도");
    private static final Pattern GOVERNING = Pattern.compile("공고|정정|조건|자격|소득|자산|예외|추가|수정|변경|제출");
    private static final Pattern INTAKE_ANCILLARY = Pattern.compile(
        "(?:(?:팸플릿|팜플렛|리플릿)[_-].+|[가-힣A-Za-z0-9()·_-]+(?:팸플릿|팜플렛|리플릿))\\.pdf|[가-힣A-Za-z0-9-]+_\\d{1,3}[A-Za-z]?형\\.(?:jpg|jpeg|png)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVENTORY_PAGE = Pattern.compile("(?m)^\\h*(\\d{1,3})\\h*-\\h*(\\d{1,3})\\h*$");
    private static final Map<String, Pattern> INVENTORY_FIELDS = Map.ofEntries(
        Map.entry("순번", Pattern.compile("[1-9][0-9]*")),
        Map.entry("지역본부", Pattern.compile("[가-힣]+지역본부")),
        Map.entry("관리부서", Pattern.compile("[가-힣]+(?:주거복지지사|주거복지사업처|주거복지센터)(?:\\([가-힣]+\\))?")),
        Map.entry("지역", Pattern.compile("[가-힣]+(?:시|도|군|구)")),
        Map.entry("명", Pattern.compile("매입(?:다가구|다세대|연립|주택)\\([가-힣]+\\)")),
        Map.entry("주소", Pattern.compile("[가-힣]+(?:시|군|구)[가-힣0-9]+(?:로|길)[0-9]+(?:-[0-9]+)?\\([가-힣0-9]+\\)[가-힣A-Za-z0-9·()_-]*")),
        Map.entry("동", Pattern.compile("[A-Za-z0-9]*")), Map.entry("호", Pattern.compile("[A-Za-z0-9]+")),
        Map.entry("주택형", Pattern.compile("[0-9]+형")),
        Map.entry("전용면적", Pattern.compile("[0-9]+(?:\\.[0-9]+)?")),
        Map.entry("주거공용면적", Pattern.compile("[0-9]+(?:\\.[0-9]+)?")),
        Map.entry("면적계", Pattern.compile("[0-9]+(?:\\.[0-9]+)?")),
        Map.entry("방수", Pattern.compile("[0-9]+")), Map.entry("층수", Pattern.compile("[0-9]+층")),
        Map.entry("승강기유무", Pattern.compile("[YN]")),
        Map.entry("주택유형", Pattern.compile("다가구주택|다세대주택|연립주택|아파트|오피스텔")),
        Map.entry("임대보증금", Pattern.compile("[0-9]+(?:,[0-9]{3})*")),
        Map.entry("월임대료", Pattern.compile("[0-9]+(?:,[0-9]{3})*")),
        Map.entry("무보증월세여부", Pattern.compile("[YN]")),
        Map.entry("무보증전환임대료", Pattern.compile("[0-9]+(?:,[0-9]{3})*")));
    private static final Pattern INVENTORY_OUTSIDE = Pattern.compile(
        "(?:[가-힣]+임대주택)?(?:입주자모집대상주택|공급대상주택목록)\\(공고일:[0-9]{4}\\.[0-9]{1,2}\\.[0-9]{1,2}\\.?\\)"
            + "(?:\\*주택개보수완료여부등에따라공급호수및대상변경될수있으며,계약시점에따라임대조건이변경될수있습니다\\.)?"
            + "(?:\\*주택관련사항은지역별담당관리부서로문의하시기바랍니다\\.\\(공고문상문의처참조\\))?"
            + "(?:#{2,}[0-9]+(?:,[0-9]{3})*)?(?:거실포함방갯수)?[0-9]+-[0-9]+");

    public static Coverage assess(Draft draft, Content original) {
        return assess(draft, original, false);
    }

    /**
     * Only fully located differences of the same exact rule can reach scoped compilation.
     */
    public static Coverage assessScoped(Draft draft, Content original) {
        return assess(draft, original, true);
    }

    private static Coverage assess(Draft draft, Content original, boolean scoped) {
        return assess(draft, original, scoped, false);
    }

    static Coverage assessIntake(Draft draft, Content original) {
        return assess(draft, original, true, true);
    }

    private static Coverage assess(Draft draft, Content original, boolean scoped, boolean intake) {
        var issues = new ArrayList<String>();
        if (draft == null || draft.checkedAt() == null || !Set.of("READY", "PARTIAL").contains(draft.status()))
            return new Coverage(draft, original, List.of(), List.of(), List.of("SOURCE_NOT_FINISHED"));
        var groups = new LinkedHashMap<String, List<Source>>();
        var excluded = new HashSet<String>();
        var scheduleSources = new ArrayList<Source>();
        var formBlocks = new ArrayList<VerifiedPreparationForms.Block>();
        var sourceUrls = new HashSet<String>();
        draft.sources().forEach(s -> sourceUrls.add(EvidenceLocationUrl.base(s.url())));
        if (!sourceUrls.contains(EvidenceLocationUrl.base(original.evidence().url())) || original.attachments().stream().anyMatch(a -> !sourceUrls.contains(EvidenceLocationUrl.base(a.url()))))
            issues.add("SOURCE_COVERAGE_INCOMPLETE");
        for (Source source : draft.sources()) {
            if (complete(source)) source = org.jibro.collection.review.local.DocumentContextReader.normative(source);
            String label = source.label().replaceAll("\\s+", "");
            if (OfficialListingMetadata.onlyFields(source) || OfficialListingMetadata.repeatedGuidance(source, draft.sources())) {
                excluded.add(source.url());
                // Metadata is not a second qualification document, but its dates remain a source
                // for cross-document schedule conflict checks and final evidence provenance.
                scheduleSources.add(source);
                continue;
            }
            if (!GOVERNING.matcher(label).find() && (AUXILIARY.matcher(label).find()
                || intake && INTAKE_ANCILLARY.matcher(label).matches() && !label.matches(".*(?:접수|모집|신청|일정|기한|마감).*"))) {
                excluded.add(source.url());
                continue;
            }
            if (org.jibro.collection.extraction.OcrProvenance.contains(source))
                issues.add(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
            if (complete(source) && !org.jibro.collection.extraction.OcrProvenance.contains(source)) {
                var blocks = VerifiedPreparationForms.blocks(source);
                if (!blocks.isEmpty()) {
                    formBlocks.addAll(blocks);
                    excluded.add(source.url());
                    scheduleSources.add(source);
                    continue;
                }
                if (facilities(source) || housingInventory(source)) {
                    excluded.add(source.url());
                    continue;
                }
            }
            groups.computeIfAbsent(stem(label), ignored -> new ArrayList<>()).add(source);
        }
        var selected = new ArrayList<Source>();
        for (var variants : groups.values()) {
            // A missing unknown/authoritative representation is not proof of equivalent contents.
            if (variants.stream().anyMatch(s -> !complete(s))) {
                variants.stream().filter(s -> !complete(s))
                    .forEach(s -> issues.add("AUTHORITATIVE_SOURCE_INCOMPLETE:" + s.label()));
                continue;
            }
            Source canonical = variants.stream().filter(s -> s.label().toLowerCase(Locale.ROOT).endsWith(".pdf")).findFirst().orElse(variants.get(0));
            boolean differs = variants.stream().anyMatch(s -> !DocumentVariantComparison.equivalent(canonical, s)
                && !(scoped && OfficialGuidanceRows.sameItems(canonical, s, true)));
            boolean scopeable = differs && scoped && variants.stream()
                .allMatch(s -> sameRuleContext(canonical, s) || OfficialGuidanceRows.sameItems(canonical, s, false));
            if (scopeable) selected.addAll(variants);
            else {
                if (differs) issues.add("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:" + canonical.label());
                selected.add(canonical);
            }
            scheduleSources.addAll(variants);
            if (!scopeable) for (Source variant : variants) if (variant != canonical) excluded.add(variant.url());
        }
        // Different filenames are common for an exported PDF and its editable original.
        // Collapse only a proven complete equivalent, never a guessed filename/correction suffix.
        for (int i = 0; i < selected.size(); i++) {
            Source first = selected.get(i);
            for (int j = selected.size() - 1; j > i; j--) {
                Source second = selected.get(j);
                if (DocumentVariantComparison.equivalent(first, second)) {
                    if (!first.label().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        && second.label().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        excluded.add(first.url());
                        first = second;
                        selected.set(i, first);
                    } else excluded.add(second.url());
                    selected.remove(j);
                }
            }
        }
        if (selected.isEmpty()) issues.add("RECRUITMENT_DOCUMENT_REQUIRED");
        var retainedAttachments = original.attachments().stream().filter(a -> !excluded.contains(a.url())).toList();
        Content selectedOriginal = new Content(original.summary(), original.evidence(), retainedAttachments, original.questions(), original.documents(), original.schedules(), original.reviewIssues());
        // The top-level evidence remains the official listing; compilers use the selected fetched documents for rules.
        if (excluded.contains(original.evidence().url()) && !selected.isEmpty()) {
            Source first = selected.get(0);
            selectedOriginal = new Content(original.summary(), new Evidence(first.label(), first.url(), draft.checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString()),
                retainedAttachments, original.questions(), original.documents(), original.schedules(), original.reviewIssues());
        }
        return new Coverage(new Draft(draft.id(), draft.noticeId(), draft.revision(), issues.isEmpty() ? "READY" : "PARTIAL", draft.checkedAt(), draft.message(), List.copyOf(selected)), selectedOriginal, List.copyOf(scheduleSources), List.copyOf(formBlocks), List.copyOf(issues));
    }

    private static boolean housingInventory(Source source) {
        if (!source.excerpts().isEmpty()) return false;
        int nextUnit = 1;
        for (int index = 0; index < source.context().size(); index++) {
            Part part = source.context().get(index);
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(source, part);
            if (!reading.valid() || reading.tables().size() != 1) return false;
            var table = reading.tables().get(0);
            var grid = table.grid();
            if (grid == null || table.rows() < 2) return false;
            int firstUnit = 0;
            while (firstUnit < grid.length && !inventoryCell(part, reading, grid[firstUnit][0]).matches("[1-9][0-9]*"))
                firstUnit++;
            if (firstUnit == 0 || firstUnit == grid.length) return false;
            var fields = new ArrayList<Pattern>();
            var names = new HashSet<String>();
            for (int c = 0; c < table.columns(); c++) {
                var heading = grid[firstUnit - 1][c];
                String name = inventoryCell(part, reading, heading);
                if (heading.position().columnSpan() != 1 || !names.add(name) || !INVENTORY_FIELDS.containsKey(name))
                    return false;
                fields.add(INVENTORY_FIELDS.get(name));
            }
            if (!names.containsAll(Set.of("순번", "주소", "동", "호", "주택형", "전용면적", "주거공용면적", "면적계", "임대보증금", "월임대료")))
                return false;
            for (var cell : table.cells()) {
                if (cell.position().row() >= firstUnit + 1) continue;
                String value = inventoryCell(part, reading, cell);
                if (!names.contains(value) && !Set.of("지자체", "주택정보", "임대조건").contains(value)) return false;
            }
            for (int r = firstUnit; r < grid.length; r++) {
                if (!inventoryCell(part, reading, grid[r][0]).equals(Integer.toString(nextUnit++))) return false;
                for (int c = 0; c < table.columns(); c++) {
                    var cell = grid[r][c];
                    if (cell.position().rowSpan() != 1 || cell.position().columnSpan() != 1
                        || !fields.get(c).matcher(inventoryCell(part, reading, cell)).matches()) return false;
                }
            }
            String outside = reading.text(part.text(), 0, table.start()) + reading.text(part.text(), table.end(), part.text().length());
            if (!INVENTORY_OUTSIDE.matcher(outside.replaceAll("[\\s\\p{Zs}]+", "")).matches()) return false;
            // Printed total-page numbering accounts for every page. The complete grid and all
            // surrounding prose establish the file's inventory role; its amounts are not rules.
            var page = INVENTORY_PAGE.matcher(outside);
            if (!page.find() || Integer.parseInt(page.group(1)) != source.context().size()
                || Integer.parseInt(page.group(2)) != index + 1 || page.find()) return false;
        }
        return true;
    }

    private static String inventoryCell(Part part, org.jibro.collection.review.local.DocumentContextReader.Reading reading,
                                        org.jibro.collection.review.local.DocumentContextReader.BoundedCell cell) {
        return reading.text(part.text(), cell.start(), cell.end()).replaceAll("[\\s\\p{Zs}]+", "");
    }

    private static boolean facilities(Source source) {
        if (!INTAKE_ANCILLARY.matcher(source.label().replaceAll("\\s+", "")).matches()) return false;
        String text = source.context().stream().map(p -> org.jibro.collection.review.local.DocumentContextReader.read(source, p)
            .text(p.text(), 0, p.text().length())).reduce("", (a, b) -> a + "\n" + b).replaceAll("[\\s\\p{Zs}]+", "");
        // A property brochure is retained as an attachment, not used as this recruitment's capacity,
        // qualification or rent. It needs positive facility structure as well as no recruitment claims.
        return text.contains("주택면적") && text.contains("단지배치도") && text.contains("주요마감재시설내역")
            && !Pattern.compile("신청자격|입주자격|신청기간|접수기간|모집일정|제출서류|구비서류|선정순위|무주택|세대구성원|혼인기간|소득|총자산|임대보증금|월임대료|[0-9]+세(?:이상|이하|미만|초과)|(?:신청|접수|청약)(?:가능|불가|제한|마감)").matcher(text).find();
    }

    private static boolean complete(Source source) {
        return "EXTRACTED".equals(source.status()) && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
            && source.context() != null && !source.context().isEmpty() && source.context().size() <= 200
            && source.context().stream().allMatch(part -> part != null && part.location() != null && !part.location().isBlank()
            && part.text() != null && !part.text().replaceAll("[\\s\\p{Zs}]+", "").isEmpty()
            && !part.text().contains("[발췌 길이 제한]"))
            && source.context().stream().mapToLong(part -> part.text().length()).sum() <= 1_000_000;
    }

    private static boolean sameRuleContext(Source first, Source second) {
        return ruleContext(first).equals(ruleContext(second));
    }

    private record RuleContext(String kind, String key, String text) {
    }

    private static List<RuleContext> ruleContext(Source source) {
        var context = new ArrayList<RuleContext>();
        for (Part part : source.context())
            for (String line : part.text().split("\\R")) {
                String compact = line.replaceAll("[\\s\\p{Zs}]+", "");
                if (compact.isEmpty()) continue;
                var rule = GuidanceRuleGrammar.parse(line.trim());
                // Preserve order, occurrence count and every surrounding clause. A newly added rule,
                // missing rule or changed subject/exception is not merely a different rule value.
                context.add(rule == null ? new RuleContext(null, null, compact) : new RuleContext(rule.kind(), rule.key(), null));
            }
        return context;
    }

    private static String stem(String label) {
        return label.toLowerCase(Locale.ROOT).replaceFirst("\\.(pdf|hwpx|hwp|owpml)$", "");
    }
}
