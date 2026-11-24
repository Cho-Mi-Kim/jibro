package org.jibro.collection.review;

import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.local.GuidanceTableProjection;
import org.jibro.collection.review.local.DocumentContextReader;
import org.jibro.notice.HousingModels.*;

/**
 * Proves an equivalent preparation context before rebinding an already reviewed paraphrase.
 */
public final class VerifiedReissueFiles {
    private final Map<String, Source> files;
    private final Map<String, Source> previous;
    private final String checked;

    private VerifiedReissueFiles(Map<String, Source> files, Map<String, Source> previous, Draft current) {
        this.previous = previous;
        this.files = files;
        this.checked = current.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
    }

    public static Optional<VerifiedReissueFiles> compare(Draft before, Draft current, String latestDetail) {
        if (before == null || current == null || current.checkedAt() == null || before.sources().isEmpty() || before.sources().size() > 51
                || before.sources().size() != current.sources().size()) return Optional.empty();
        var originals = index(before);
        var latest = index(current);
        if (originals.isEmpty() || !originals.keySet().equals(latest.keySet())) return Optional.empty();
        var mapping = new HashMap<String, Source>();
        var changedStems = new HashSet<String>();
        boolean detail = false;
        for (var name : originals.keySet()) {
            var a = originals.get(name);
            var b = latest.get(name);
            if (mapping.put(a.url(), b) != null) return Optional.empty();
            if (b.url().equals(latestDetail)) {
                if (!complete(a) || !complete(b) || !"공식 상세 본문".equals(name) || !detail(a).equals(detail(b)))
                    return Optional.empty();
                detail = true;
            } else if (!a.sha256().equals(b.sha256())) {
                if (!name.contains("공고") || !complete(a) || !complete(b) || a.context().size() != b.context().size())
                    return Optional.empty();
                boolean pdf = name.toLowerCase(Locale.ROOT).endsWith(".pdf");
                if (!pdf && !GuidanceTableProjection.structuredLabel(name)) return Optional.empty();
                for (int i = 0; i < a.context().size(); i++) {
                    Part old = a.context().get(i), now = b.context().get(i);
                    if (!old.location().equals(now.location())) return Optional.empty();
                    if (lines(old.text()).equals(lines(now.text()))) continue;
                    if (!(pdf ? sameSchedulePage(old.text(), now.text()) : sameScheduleCells(a, old, b, now)))
                        return Optional.empty();

                }
                changedStems.add(name.substring(0, name.lastIndexOf('.')));
            }
        }
        if (!detail || changedStems.size() > 1) return Optional.empty();
        for (String stem : changedStems) {
            Source pdf = latest.get(stem + ".pdf"), structured = latest.get(stem + ".hwpx");
            if (pdf == null || structured == null || !complete(pdf) || !complete(structured)
                    || structured.context().stream().noneMatch(part -> part.text().contains("⟦HWPX:TABLE"))
                    || originals.get(pdf.label()).sha256().equals(pdf.sha256()) != originals.get(structured.label()).sha256().equals(structured.sha256()))
                return Optional.empty();
        }
        return Optional.of(new VerifiedReissueFiles(mapping, originals, current));
    }

    private static Map<String, Source> index(Draft draft) {
        var map = new HashMap<String, Source>();
        for (var s : draft.sources())
            if (s == null || s.label() == null || s.url() == null || !s.url().startsWith("https://apply.lh.or.kr/")
                    || s.sha256() == null || !s.sha256().matches("[0-9a-f]{64}") || map.put(s.label(), s) != null)
                return Map.of();
        return map;
    }

    private static boolean complete(Source s) {
        return "EXTRACTED".equals(s.status()) && !OcrProvenance.contains(s) && !s.context().isEmpty() && s.context().size() <= 200
                && s.context().stream().allMatch(p -> p.location() != null && p.text() != null && !p.text().isBlank() && !p.text().contains("[발췌 길이 제한]"))
                && s.context().stream().mapToLong(p -> p.text().length()).sum() <= 1_000_000;
    }

    private static List<String> detail(Source s) {
        return s.context().stream().map(p -> lines(p.text())).filter(t -> !t.matches("공고문\\s*\\d{1,3}\\s*(?:쪽|페이지|페이서)\\s*(?:주택)?형별\\s*표기\\s*(?:수정|정정)")).toList();
    }

    private static String lines(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", " ").strip();
    }

    // Only the numeric housing-type column of the district intake timetable can move across ranks.
    // Every other cell, label, clause, and numeric type in the HWPX must be accounted for.
    private static final String HEADING = "■ 주택단지별 모집일정";

    private static String scheduleCells(Source source, Part part) {
        String text = part.text();
        int at = text.indexOf(HEADING);
        if (at < 0 || at != text.lastIndexOf(HEADING)) return null;
        var reading = DocumentContextReader.read(source, part);
        if (!reading.valid()) return null;
        int headerEnd = text.indexOf("⟦HWPX:/TABLE⟧", at);
        if (headerEnd < at || headerEnd - at > 2500) return null;
        var header = reading.tables().stream().filter(t -> t.end() >= headerEnd
                && t.end() <= headerEnd + "⟦HWPX:/TABLE⟧".length() + 2).findFirst().orElse(null);
        if (header == null || header.grid() == null) return null;
        String heading = reading.text(text, at, header.end());
        if (!heading.contains("단지명") || !heading.contains("신청접수") || !heading.contains("신청순위")
                || header.cells().stream().noneMatch(c -> c.position().column() == 2
                && c.position().rowSpan() == 1 && c.position().columnSpan() == 1
                && reading.text(text, c.start(), c.end()).strip().equals("주택형"))) return null;
        var table = reading.tables().stream().filter(t -> t.start() >= header.end()).findFirst().orElse(null);
        if (table == null || table.grid() == null || table.end() - table.start() > 80_000
                || !text.substring(header.end(), table.start()).isBlank()
                || reading.tables().stream().anyMatch(t -> t.start() > table.start() && t.end() < table.end()))
            return null;
        var tokens = new ArrayList<String>();
        var cells = new ArrayList<DocumentContextReader.BoundedCell>();
        for (var cell : table.cells()) {
            if (cell.position().column() != 2 || cell.position().columnSpan() != 1) continue;
            String value = reading.text(text, cell.start(), cell.end()).strip();
            if (!value.matches("(?:[0-9]{1,3}\\s*)+")) continue;
            tokens.addAll(List.of(value.split("\\s+")));
            cells.add(cell);
        }
        if (tokens.isEmpty()) return null;
        Collections.sort(tokens);
        var canonical = new StringBuilder(text);
        cells.sort(Comparator.comparingInt(DocumentContextReader.BoundedCell::start).reversed());
        for (var cell : cells) {
            int start = text.lastIndexOf("⟦HWPX:CELL", cell.start());
            int end = cell.end() + "⟦HWPX:/CELL⟧".length();
            canonical.delete(start, end);
        }
        return lines(canonical + tokens.toString());
    }

    private static boolean sameScheduleCells(Source a, Part old, Source b, Part now) {
        String left = scheduleCells(a, old);
        return left != null && left.equals(scheduleCells(b, now));
    }

    private static boolean sameSchedulePage(String a, String b) {
        String x = lines(a), y = lines(b);
        int i = x.indexOf(HEADING), j = y.indexOf(HEADING);
        if (i < 0 || j < 0 || !x.substring(0, i).equals(y.substring(0, j))) return false;
        // PDF extraction order can change inside the same printed timetable. The structured
        // counterpart separately proves that only the housing-type column changed.
        return Arrays.stream(x.substring(i).split(" ")).sorted().toList().equals(Arrays.stream(y.substring(j).split(" ")).sorted().toList());
    }

    public Optional<Content> restore(Content guide, Content latest, List<ScheduleTemplate> schedule) {
        if (!guide.reviewIssues().isEmpty() || guide.questions().isEmpty() || guide.documents().isEmpty() || guide.applicationClosure() != null)
            return Optional.empty();
        var qs = new ArrayList<Question>();
        var docs = new ArrayList<DocumentTemplate>();
        for (var q : guide.questions()) {
            var evidence = current(q.evidence());
            if (evidence == null || q.reviewRequired()) return Optional.empty();
            qs.add(new Question(q.id(), q.title(), q.reason(), evidence, q.options(), q.inputKind(), q.constraints(), q.reviewRequired(), q.answerDependencies(), q.applicability()));
        }
        for (var d : guide.documents()) {
            var evidence = current(d.evidence());
            if (evidence == null) return Optional.empty();
            String issue = d.issueUrl();
            if (issue != null && issue.contains("lhFile.do")) {
                var rebound = current(new Evidence(d.title(), issue, checked));
                if (rebound == null) return Optional.empty();
                issue = rebound.url();
            }
            docs.add(new DocumentTemplate(d.id(), d.title(), d.conditionQuestionId(), d.method(), d.issuer(), issue, evidence, d.conditionAnswerValues(), d.issueLabel()));
        }
        return Optional.of(new Content(latest.summary(), latest.evidence(), latest.attachments(), qs, docs, schedule, latest.reviewIssues()));
    }

    private static boolean unchangedTable(String label, Source old, Source now) {
        if (label == null || !GuidanceTableProjection.structuredLabel(now.label())) return false;
        var id = Pattern.compile(".*표\\s+(\\d{1,4})$").matcher(label);
        if (!id.matches()) return false;
        var pattern = Pattern.compile("⟦HWPX:TABLE id=" + id.group(1) + " [^⟧]+⟧.*?⟦HWPX:/TABLE⟧", Pattern.DOTALL);
        var a = old.context().stream().flatMap(part -> pattern.matcher(part.text()).results()).map(m -> m.group()).toList();
        var b = now.context().stream().flatMap(part -> pattern.matcher(part.text()).results()).map(m -> m.group()).toList();
        return a.size() == 1 && a.equals(b);
    }

    Evidence current(Evidence old) {
        if (old == null || old.url() == null) return null;
        String[] url = old.url().split("#", 2);
        var file = files.get(url[0]);
        if (file == null || file.label().equals("공식 상세 본문")) return null;
        Source before = previous.get(file.label());
        if (url.length == 1) {
            if (!before.sha256().equals(file.sha256()) && !unchangedTable(old.label(), before, file)) return null;
            return new Evidence(old.label(), file.url(), checked);
        }
        if (url[1].startsWith("doc2=")) {
            for (var part : before.context()) {
                if (!EvidenceLocationUrl.matchesPart(before, part, old.url())) continue;
                String rebound = file.url() + "#" + url[1].replaceFirst("^doc2=" + before.sha256() + "\\.", "doc2=" + file.sha256() + ".");
                if (file.context().stream().anyMatch(now -> part.location().equals(now.location()) && part.text().equals(now.text())
                        && EvidenceLocationUrl.matchesPart(file, now, rebound)))
                    return new Evidence(old.label(), rebound, checked);
            }
            return null;
        }
        var page = Pattern.compile("page=([1-9][0-9]{0,3})(?:&sha256=[0-9a-f]{64})?").matcher(url[1]);
        if (!page.matches() || url[1].contains("&sha256=") && !url[1].endsWith("&sha256=" + before.sha256()))
            return null;
        String location = "공고문 " + page.group(1) + "쪽";
        if (file.context().stream().noneMatch(p -> location.equals(p.location()) && before.context().stream().anyMatch(v -> location.equals(v.location()) && lines(v.text()).equals(lines(p.text())))))
            return null;
        return new Evidence(old.label(), EvidenceLocationUrl.forSource(file, location, null), checked);
    }
}

