package org.jibro.collection.review;

import java.util.*;
import java.time.ZoneId;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.followup.OfficialFollowupModels.Discovery;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.OfficialFileManifest;
import org.jibro.notice.source.NoticeSourceInformationModels.*;

/**
 * Only current, readable files from a verified recruitment may supply a form.
 */
public final class VerifiedPreparationForms {
    public record Block(String number, String title, String recipient, String text, List<String> instructions,
                        Source source, String location) {
    }

    /**
     * A separate numbered blank form is a document instruction, not a second recruitment.
     */
    static List<Block> blocks(Source source) {
        // This established classification is narrower than recognizing a numbered attachment.
        // Recognizing 붙임 does not prove that its additional contract clauses repeat the notice.
        if (source.context().stream().anyMatch(p -> java.util.regex.Pattern.compile("(?m)^\\s*\\[붙임\\s*\\d").matcher(p.text()).find()))
            return List.of();
        String text = source.context().stream().map(Part::text).collect(java.util.stream.Collectors.joining("\n"));
        // A recruitment chapter embedded beside a form must still receive the governing-text check.
        if (java.util.regex.Pattern.compile("(?m)^\\s*(?:[0-9]+[.．]\\s*)?(?:신청자격|입주자격|접수기간|소득기준|자산기준)\\s*[:：]?").matcher(text).find())
            return List.of();
        return numberedBlocks(source);
    }

    static List<Block> numberedBlocks(Source source) {
        var parts = new ArrayList<String>();
        var partStarts = new ArrayList<Integer>();
        int offset = 0;
        for (var part : source.context()) {
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(source, part);
            var explanation = org.jibro.collection.review.local.GuidanceTableProjection.explanation(source, part, ignored -> false);
            if (!reading.valid() || !explanation.complete()) return List.of();
            String body = reading.text(part.text(), 0, part.text().length());
            partStarts.add(offset);
            parts.add(body);
            offset += body.length() + 1;
        }
        String text = String.join("\n", parts);
        var heading = java.util.regex.Pattern.compile("(?m)^\\s*\\[(?:양식|붙임)\\s*(\\d{1,2}(?:-\\d{1,2})?)]\\s*([^\\r\\n]+)").matcher(text);
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        var numbers = new ArrayList<String>();
        var titles = new ArrayList<String>();
        var recipients = new ArrayList<String>();
        while (heading.find()) {
            var label = java.util.regex.Pattern.compile("^(.*?(?:동의서|확인서|위임장|신청서|확약서|각서))\\s*\\((.+(?:제출|신청한\\s*자))\\)\\s*(?:페이지\\s*\\d+)?\\s*$").matcher(heading.group(2).strip());
            if (!label.matches()) return List.of();
            starts.add(heading.start());
            ends.add(heading.end());
            numbers.add(heading.group(1));
            titles.add(label.group(1).strip());
            recipients.add(label.group(2).strip());
        }
        if (starts.isEmpty()) return List.of();
        String preface = text.substring(0, starts.get(0)).strip();
        // Instructions preceding numbered forms must name their writing purpose. Never discard an
        // arbitrary leading qualification paragraph merely because a form appears later.
        if (!preface.isEmpty() && !preface.contains("작성시 유의사항") && !preface.contains("작성 시 유의사항")) return List.of();
        var result = new LinkedHashMap<String, Block>();
        for (int i = 0; i < starts.size(); i++) {
            String body = text.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length()).strip();
            String number = numbers.get(i), title = titles.get(i), recipient = recipients.get(i);
            var old = result.get(number);
            if (old != null && (!key(old.title()).equals(key(title)) || !key(old.recipient()).equals(key(recipient))))
                return List.of();
            String merged = old == null ? body : old.text() + "\n\n" + body;
            int partIndex = 0;
            while (partIndex + 1 < partStarts.size() && partStarts.get(partIndex + 1) <= starts.get(i)) partIndex++;
            String location = old == null ? source.context().get(partIndex).location() : old.location();
            result.put(number, new Block(number, title, recipient, merged, List.of(), source, location));
        }
        var writing = namedWritingInstructions(preface);
        if (writing == null) return List.of();
        for (var block : new ArrayList<>(result.values())) {
            if (!block.text().matches("(?s).*(?:서명|날인|\\(인\\)).*") || !block.text().matches("(?s).*(?:성\\s*명|신청인|신청자명).*"))
                return List.of();
            var instructions = new LinkedHashSet<>(writing.getOrDefault(key(block.title()), List.of()));
            instructions.addAll(writingParagraphs(block.text()));
            result.put(block.number(), new Block(block.number(), block.title(), block.recipient(), block.text(), List.copyOf(instructions), source, block.location()));
        }
        if (writing.keySet().stream().anyMatch(title -> result.values().stream().noneMatch(b -> key(b.title()).equals(title))))
            return List.of();
        return List.copyOf(result.values());
    }

    private static Map<String, List<String>> namedWritingInstructions(String text) {
        var result = new LinkedHashMap<String, List<String>>();
        if (text.isBlank()) return result;
        // The publication header is checked independently by OfficialFormConsistency. A named
        // writing example still belongs to its form; do not discard the trailing preface wholesale.
        text = text.replaceAll("(?m)^\\s*\\[\\s*신청주택\\s*[:：][^\\r\\n]+]\\s*$", "");
        var heading = java.util.regex.Pattern.compile("(?m)^\\s*(?:□\\s*(.+?)\\s*작성\\s*시\\s*유의사항[^\\r\\n]*|\\[([^]\\r\\n]+?)\\s*작성예시])").matcher(text);
        var names = new ArrayList<String>();
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        while (heading.find()) {
            names.add(heading.group(1) == null ? heading.group(2) : heading.group(1));
            starts.add(heading.start());
            ends.add(heading.end());
        }
        if (names.isEmpty()) return null;
        String lead = text.substring(0, starts.get(0)).strip();
        if (!lead.isEmpty() && !lead.matches("(?s)^[•※-]?\\s*작성\\s*시\\s*(?:오류|주의|유의).*")) return null;
        for (int i = 0; i < names.size(); i++) {
            String body = text.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length());
            if (i == 0 && !lead.isEmpty()) body = lead + "\n\n" + body;
            var paragraphs = Arrays.stream(body.split("\\n\\s*\\n")).map(p -> p.strip().replaceAll("\\s+", " ")).filter(p -> !p.isEmpty()).toList();
            result.computeIfAbsent(key(names.get(i)), ignored -> new ArrayList<>()).addAll(paragraphs);
        }
        return result;
    }

    private static List<String> writingParagraphs(String text) {
        return Arrays.stream(text.split("\\n\\s*\\n|\\n(?=\\s*[※*◌•])")).map(p -> p.strip().replaceAll("\\s+", " "))
            .filter(p -> p.matches("^[※*◌•].*")
                || p.matches("^[0-9]+[.)].*(?:서명|날인|기재|작성|첨부|제출).*")
                || p.matches(".*(?:제출|증명|기재).*(?:확인함|확인하였습니다[.]?|동의함)$")).distinct().toList();
    }

    private final OfficialFileManifest manifest;
    private final List<Source> pdfs;
    private final List<Section> standalone;
    private final String checked;

    public VerifiedPreparationForms(Draft draft, Discovery found) {
        checked = found.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        var files = new LinkedHashMap<String, Source>();
        var latest = found.related().stream().filter(r -> "CORRECTION".equals(r.kind())).reduce((a, b) -> b);
        var current = latest.map(r -> r.sources()).orElse(draft.sources());
        if (found.permitsVerifiedItems()) {
            boolean subsequent = found.related().stream().anyMatch(r -> "FOLLOWUP".equals(r.kind()));
            if (!subsequent) current.forEach(s -> add(files, s));
            found.related().stream().filter(r -> !subsequent || "FOLLOWUP".equals(r.kind())).filter(r -> latest.isEmpty() || r.url().equals(latest.get().url())
                || r.relationshipProof().endsWith(latest.get().url())).forEach(r ->
                r.sources().stream().filter(s -> !s.url().equals(r.url())).forEach(s -> add(files, s)));
        }
        standalone = found.hasMirror() ? standalone(files.values(), checked) : List.of();
        pdfs = files.values().stream().filter(s -> s.label().toLowerCase(Locale.ROOT).endsWith(".pdf")).toList();
        manifest = new OfficialFileManifest(checked, files.values().stream().map(s ->
            new OfficialFileManifest.File(s.label(), s.url(), List.of())).toList());
    }

    private static void add(Map<String, Source> files, Source s) {
        if ("EXTRACTED".equals(s.status()) && s.sha256() != null && s.sha256().matches("[0-9a-f]{64}")
            && s.label() != null && s.label().matches("(?i).*[.](?:pdf|hwpx?|xlsx?)") && !s.context().isEmpty()
            && !OcrProvenance.contains(s) && s.context().stream().noneMatch(p -> p.text().isBlank() || p.text().contains("[발췌 길이 제한]")))
            files.putIfAbsent(s.sha256(), s);
    }

    private static List<Section> standalone(java.util.Collection<Source> sources, String day) {
        var forms = new LinkedHashMap<String, Section>();
        var ambiguous = new HashSet<String>();
        for (var source : sources) {
            String name = source.label().replaceFirst("(?i)[.](?:hwpx?|pdf)$", "");
            name = name.replaceFirst("^\\s*\\[(?:양식|서식|붙임|별지)[^\\]]{0,40}\\]\\s*", "")
                .replaceFirst("\\((?:개정|대리인신청시)\\)$", "").strip();
            if (!name.matches(".{1,80}(?:동의서|확인서|위임장|신청서|확약서)") && !name.equals("위임장")) continue;
            if (name.matches(".*(?:샘플|예시|작성례|견본|작성방법).*")) continue;
            String text = String.join("\n", source.context().stream().map(p -> p.text()).toList());
            String compact = key(text).replace("중복선정불가및대기자명부제외사항확인서", "중복선정불가사항확인서");
            String title = key(name);
            if (!compact.contains("서명") && !compact.contains("날인") && !text.replaceAll("\\s+", "").contains("(인)"))
                continue;
            if (!compact.contains(title) && !(title.endsWith("확인서") && title.contains("및")
                && Arrays.stream(title.split("및")).allMatch(t -> source.context().stream().anyMatch(p -> key(p.text()).contains(t)))))
                continue;
            var evidence = new Evidence("공식 서식 · " + name, source.url(), day);
            var section = new Section("official-form-" + UUID.nameUUIDFromBytes(title.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Area.DOCUMENT, name, "해당 서류를 제출할 때 쓰는 공식 서식이에요.", evidence, evidence);
            if (forms.putIfAbsent(title, section) != null) ambiguous.add(title);
        }
        return forms.entrySet().stream().filter(e -> !ambiguous.contains(e.getKey())).map(Map.Entry::getValue).toList();
    }

    static String key(String value) {
        return value.replaceAll("[^가-힣A-Za-z0-9]", "")
            .replace("금융정보등금융신용보험정보제공동의서", "금융정보제공동의서")
            .replace("금융정보등제공동의서", "금융정보제공동의서");
    }

    private DocumentTemplate document(DocumentTemplate doc) {
        var linked = manifest.documents(List.of(doc)).get(0);
        if (linked.issueUrl() != null || !doc.title().matches(".*(?:동의서|신청서|확인서|위임장|확약서|각서)$")) return linked;
        var pages = new LinkedHashSet<String>();
        for (var pdf : pdfs) {
            for (var page : pdf.context()) {
                if (!page.text().contains("서명") && !page.text().contains("날인")) continue;
                if (page.text().lines().filter(line -> !line.isBlank()).limit(6)
                    .noneMatch(line -> key(line).equals(key(doc.title())))) continue;
                String url = EvidenceLocationUrl.forPart(pdf.label(), pdf.url(), page.location());
                if (!url.equals(pdf.url())) pages.add(url);
            }
        }
        if (pages.size() != 1) return doc;
        return new DocumentTemplate(doc.id(), doc.title(), doc.conditionQuestionId(), doc.method(), doc.issuer(),
            pages.iterator().next(), doc.evidence(), doc.conditionAnswerValues(), "공식 공고문 서식");
    }

    public Content content(Content value) {
        var docs = value.documents().stream().map(this::document).toList();
        return new Content(value.summary(), value.evidence(), value.attachments(), value.questions(), docs,
            value.schedules(), value.reviewIssues(), value.applicationClosure());
    }

    /**
     * A signed blank form identifies a usable template, not who is required to submit it.
     */
    static List<Section> embedded(List<Source> files, String day) {
        var pages = new LinkedHashMap<String, Section>();
        var ambiguous = new HashSet<String>();
        for (var pdf : files) {
            if (!pdf.label().contains("공고") || !pdf.label().toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
            for (var part : pdf.context()) {
                String text = part.text();
                if (!text.matches("(?s).*(?:서명|날인|\\(인\\)).*") || !text.matches("(?s).*(?:성\\s*명|신청인|신청자명).*")
                    || text.matches("(?s).*(?:작성\\s*예시|작성\\s*견본|작성\\s*샘플).*")) continue;
                String url = EvidenceLocationUrl.forPart(pdf.label(), pdf.url(), part.location());
                if (url.equals(pdf.url())) continue;
                var heading = text.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(5)
                    .filter(l -> l.length() <= 85 && !l.matches(".*[:：•※▪■].*")
                        && key(l).matches("(?:공공임대주택청약을위한)?(?:.*개인정보.*동의서|금융정보.*동의서|자산보유사실확인서|.*예비신혼부부(?:신청|세대구성)확인서|.*중복선정불가.*확인서|(?:매입임대|국민임대)주택공급신청서|위임장)"))
                    .findFirst();
                if (heading.isEmpty()) continue;
                String title = heading.get().replaceAll("\\s+", " ");
                String key = key(title);
                var evidence = new Evidence(pdf.label() + " · " + part.location(), url, day);
                var item = new Section("form-" + UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    Area.DOCUMENT, title, "이 공고에 첨부된 공식 서식이에요. 해당 서류를 제출할 때 사용하세요.", evidence, evidence);
                var previous = pages.putIfAbsent(key, item);
                if (previous != null && !previous.form().url().equals(url)) ambiguous.add(key);
            }
        }
        return pages.entrySet().stream().filter(e -> !ambiguous.contains(e.getKey())).map(Map.Entry::getValue).toList();
    }

    public List<Section> sections(List<Section> items) {
        if (items.isEmpty()) return items;
        var result = new ArrayList<>(items.stream().map(item -> {
            if (item.area() != Area.DOCUMENT || item.form() != null) return item;
            var doc = document(new DocumentTemplate(item.id(), item.title(), null, item.text(), "", null, item.evidence()));
            return doc.issueUrl() == null ? item : new Section(item.id(), item.area(), item.title(), item.text(), item.evidence(),
                new Evidence(doc.issueLabel(), doc.issueUrl(), checked));
        }).toList());
        for (var form : standalone) {
            if (result.stream().noneMatch(s -> s.form() != null && (Objects.equals(s.form().url(), form.form().url())
                || key(s.title()).equals(key(form.title()))))) result.add(form);
        }
        return List.copyOf(result);
    }
}
