package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.review.followup.OfficialFollowupModels.Related;

/**
 * Reads only complete, explicitly labelled rows. Unknown columns and continuation rows retain their raw text.
 */
final class OfficialGuidanceRows {
    private static final Set<String> ITEMS = Set.of("항목", "구분", "자격항목");
    private static final Set<String> VALUES = Set.of("자격기준", "신청자격", "세부기준", "내용");
    private static final Set<String> APPLICANTS = Set.of("구분", "계층", "유형", "공급대상", "신청유형");
    private static final Set<String> ELIGIBILITY = Set.of("신청자격", "입주자격", "자격요건");
    private static final Set<String> DOCUMENTS = Set.of("서류명", "구비서류", "제출서류");
    private static final Set<String> AUDIENCES = Set.of("제출대상", "제출대상자", "대상자", "대상(해당)자");

    private OfficialGuidanceRows() {
    }

    static Draft read(Draft draft) {
        if (draft == null || draft.sources() == null || draft.sources().size() > 51) return draft;
        return new Draft(draft.id(), draft.noticeId(), draft.revision(), draft.status(), draft.checkedAt(), draft.message(),
            draft.sources().stream().map(OfficialGuidanceRows::read).toList());
    }

    static Related read(Related related) {
        return new Related(related.kind(), related.url(), related.title(), related.publishedDate(), related.relationshipProof(),
            related.sources().stream().map(OfficialGuidanceRows::read).toList(), related.supersedesUrl());
    }

    static final String ROW_EVIDENCE = "VERIFIED_HWPX_GUIDANCE_ROW";

    static Source read(Source raw) {
        if (!"EXTRACTED".equals(raw.status()) || raw.label() == null || raw.context().size() > 200
            || raw.context().stream().anyMatch(p -> p.text() == null || p.location() == null || p.text().contains("[발췌 길이 제한]"))
            || raw.context().stream().mapToLong(p -> p.text().length()).sum() > 1_000_000) return raw;
        var source = org.jibro.collection.review.local.DocumentContextReader.normative(raw);
        boolean visitingOnly = visitingOnly(source);
        var excerpts = new ArrayList<>(source.excerpts() == null ? List.<Excerpt>of() : source.excerpts());
        var parts = source.context().stream().map(part -> {
            String text = org.jibro.collection.review.local.GuidanceTableProjection.project(source, part,
                (headers, values, heading) -> row(headers, values, heading, visitingOnly));
            if (source.label() != null && source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
                text = pdf(text, visitingOnly);
            String value = html(text, visitingOnly);
            String location = part.location();
            if (!part.text().equals(text) && !org.jibro.collection.review.local.DocumentContextReader.read(source, part).tables().isEmpty()) {
                location += " · 공식 표";
                addAnchors(source, part, text, location, excerpts);
            }
            return new Part(location, value == null ? text : value);
        }).toList();
        return new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), excerpts, parts);
    }

    static boolean visitingOnly(Source source) {
        String prose = source.context().stream().flatMap(part -> {
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(source, part);
            if (!reading.valid()) return java.util.stream.Stream.<String>empty();
            return reading.segments().stream().filter(segment -> segment.context() == null || segment.context().cells().isEmpty())
                .map(segment -> part.text().substring(segment.start(), segment.end()));
        }).collect(java.util.stream.Collectors.joining("\n"));
        // The independent application instruction must apply to the entire notice, not an elder,
        // disabled-person or other conditional audience embedded in prose or a table cell.
        boolean exclusive = java.util.regex.Pattern.compile("(?m)^\\s*[▪•]?\\s*금회\\s*공고는\\s*방문신청만\\s*가능하며,\\s*인터넷\\s*청약\\s*접수는\\s*불가합니다\\.\\s*$")
            .matcher(prose).find();
        return exclusive && !java.util.regex.Pattern.compile("(?:인터넷|온라인|모바일)(?:청약)?(?:접수|신청)(?:도|는|이)?가능")
            .matcher(prose.replaceAll("\\s+", "")).find();
    }

    private static void addAnchors(Source source, Part original, String projection, String location, List<Excerpt> excerpts) {
        // An anchor must exist in the original extraction that the viewer opens, never only in the projection.
        for (String line : projection.lines().toList()) {
            String statement = line.replaceFirst("^(?:정정|변경) [전후]: ", "").strip();
            var rule = OfficialGuidanceItem.parse(statement);
            if (rule == null) continue;
            for (String phrase : rule.value().split(" · ")) {
                if (phrase.length() < 8) continue;
                String url = EvidenceLocationUrl.forSource(source, original.location(), phrase);
                if (url.equals(source.url())) continue;
                excerpts.add(new Excerpt(ROW_EVIDENCE, statement, location, url));
                break;
            }
        }
    }

    private static String pdf(String text, boolean visitingOnly) {
        // Without explicit column boundaries a flattened PDF cannot prove which value belongs to which heading.
        if (!text.contains("\t") && !text.contains("|") && !text.contains("  ")) return text;
        var lines = text.lines().toList();
        var output = new ArrayList<String>();
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String header = lines.get(i);
            String delimiter = header.contains("|") ? "\\s*\\|\\s*" : header.contains("\t") ? "\\t+" : " {2,}";
            var labels = List.of(header.strip().split(delimiter, -1));
            if (labels.size() < 2 || labels.size() > 6 || i + 1 >= lines.size()) {
                output.add(header);
                continue;
            }
            var parsed = new ArrayList<String>();
            while (i + 1 < lines.size()) {
                var cells = List.of(lines.get(i + 1).strip().split(delimiter, -1));
                String statement = row(labels, cells, org.jibro.collection.review.local.GuidanceTableProjection.sectionHeading(String.join("\n", lines.subList(0, i))), visitingOnly);
                if (statement == null) break;
                parsed.add(statement);
                i++;
            }
            if (parsed.isEmpty()) output.add(header);
            else {
                output.addAll(parsed);
                changed = true;
            }
        }
        return changed ? String.join("\n", output) : text;
    }

    private static String html(String text, boolean visitingOnly) {
        var lines = text.strip().lines().limit(7).toList();
        if (lines.size() < 2 || lines.size() > 6) return null;
        var headings = new ArrayList<String>();
        var values = new ArrayList<String>();
        for (String line : lines) {
            var fields = line.split("\\s*[:：]\\s*", 2);
            if (fields.length != 2) return null;
            headings.add(fields[0]);
            values.add(fields[1]);
        }
        return row(headings, values, null, visitingOnly);
    }

    static String row(List<String> headers, List<String> values, String sectionHeading, boolean visitingOnly) {
        var names = headers.stream().map(s -> s.replaceAll("\\s+", "")).toList();
        if (names.size() != values.size()) return null;
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).isBlank() && !Set.of("발급처", "발급기관").contains(names.get(i))) return null;
        if (names.size() == 2 && APPLICANTS.contains(names.get(0)) && ELIGIBILITY.contains(names.get(1))) {
            String title = values.get(0).strip() + " 신청자격";
            if (OfficialGuidanceItem.applicantType(title) != null) return statement(title, values.get(1));
        }
        if (names.equals(List.of("순위", "입주자격")) || names.equals(List.of("순위", "신청자격"))
            || names.equals(List.of("구분", "입주자격"))) {
            String applicant = OfficialGuidanceItem.rankedApplicant(values.get(0), values.get(1));
            return statement(applicant == null ? values.get(0) + " 자격" : applicant + " 신청자격",
                applicant == null ? values.get(1) : values.get(0) + " · " + values.get(1));
        }
        if (names.equals(List.of("배점항목", "배점기준")))
            return statement("배점 항목 · " + values.get(0), values.get(1));
        if (names.equals(List.of("구분", "순위결정방법")))
            return statement(values.get(0) + " 자격", values.get(1));
        if (names.equals(List.of("구분", "입주자격완화내용"))) {
            String criterion = values.get(0).replaceAll("\\s+", "");
            if (criterion.equals("주택요건")) criterion = "주택 요건";
            return statement(criterion, values.get(1));
        }
        if (names.equals(List.of("구분", "구분", "구분", "배점", "배점기준"))
            || names.equals(List.of("구분", "구분", "배점", "배점기준(대상자)"))) {
            var context = values.subList(0, values.size() - 2).stream().distinct().toList();
            return statement("배점 항목 · " + String.join(" / ", context), "배점 " + values.get(values.size() - 2) + " · " + values.get(values.size() - 1));
        }
        if (names.equals(List.of("평가항목", "평가요소", "배점")))
            return statement("배점 항목 · " + values.get(0), values.get(1) + " · 배점: " + values.get(2));
        if (names.equals(List.of("순위", "입주자격", "임대조건")))
            return statement(values.get(0) + " 자격", values.get(1) + " · 임대조건: " + values.get(2));
        boolean common = sectionHeading != null && sectionHeading.matches("공통\\s*(?:제출\\s*)?서류(?:\\s*\\([^)]*\\))?");
        String document = documentRow(names, values, common, visitingOnly);
        if (document != null) return document;
        if (names.size() == 2 && ITEMS.contains(names.get(0)) && VALUES.contains(names.get(1)))
            return statement(values.get(0), values.get(1));
        if (names.size() == 3 && ITEMS.contains(names.get(0))
            && (names.subList(1, 3).equals(List.of("정정전", "정정후")) || names.subList(1, 3).equals(List.of("변경전", "변경후")))) {
            String before = statement(values.get(0), values.get(1)), after = statement(values.get(0), values.get(2));
            if (before != null && after != null) return "정정 전: " + before + "\n정정 후: " + after;
        }
        return null;
    }

    static boolean limitedSubmission(String text) {
        String line = text.replaceAll("\\s+", " ");
        // Refusing an application when consent is missing is the opposite of a submission waiver.
        // Remove only this complete warning for classification, never from the displayed instructions.
        line = line.replaceAll("제출하지\\s*않(?:는|을)\\s*경우\\s*(?:임대주택\\s*입주자\\s*(?:모집\\s*)?)?신청[·∙ㆍ･/]?접수가\\s*거부(?:됨|됩니다)", "");
        return line.matches(".*(?:만|한하여|한해)\\s*제출.*")
            || line.matches(".*제출(?:을|이|은)?\\s*(?:생략|면제|불필요|불요).*")
            || line.matches(".*제출(?:하지\\s*않|할\\s*필요(?:가|는)?\\s*없|\\s*필요(?:가|는)?\\s*없).*");
    }

    private static String documentRow(List<String> names, List<String> values, boolean common, boolean visitingOnly) {
        if (names.equals(List.of("공급대상", "공급대상", "제출서류", "발급처"))) {
            String recipient = values.get(1).strip(), instructions = values.get(2).strip();
            if (recipient.equals("공통") || recipient.equals("해당자만 제출"))
                recipient = values.get(0).strip() + " " + recipient;
            var subgroup = java.util.regex.Pattern.compile("^\\[([^]\\r\\n]{1,90})]\\s*(.+)$").matcher(instructions);
            if (subgroup.matches()) recipient += " 중 " + subgroup.group(1);
            String label = recipient + " 제출서류";
            // Preserve alternative proofs and later submission deadlines as one named bundle.
            // The outer merged cell alone never makes these documents common to all applicants.
            String detail = recipient + " · 안내: " + instructions
                + (values.get(3).isBlank() ? "" : " · 발급처: " + values.get(3));
            return statement("서류 안내 · " + label, detail);
        }
        if (names.equals(List.of("공급대상", "공급대상", "제출서류", "MyMy서비스이용", "발급처"))) {
            // The merged outer category does not determine a recipient. Only the specific inner cell does.
            String recipient = OfficialGuidanceItem.documentApplicant(values.get(1));
            if (recipient == null) return null;
            String body = values.get(2).strip().replaceFirst("^[·•]\\s*", "")
                .replaceFirst("^\\(신청시\\s*제출\\)\\s*", "");
            var document = java.util.regex.Pattern.compile("^(?:공급신청자의\\s*)?([가-힣A-Za-z0-9·() /-]{2,75}?(?:증명서|확인서|동의서|신청서))(?:\\s*\\((?:공사양식|※).*)?$").matcher(body);
            if (!document.matches()) return null;
            String title = document.group(1).strip();
            if (!title.replace(" ", "").contains(recipient)) title = recipient + " " + title;
            return statement(title + " 제출 대상", recipient + " · 안내: " + values.get(1) + " · " + values.get(2)
                + " · MyMy서비스이용: " + values.get(3) + " · 발급처: " + values.get(4));
        }
        if (names.equals(List.of("제출서류", "MyMy서비스이용가능여부", "비고"))
            && values.get(0).matches("^\\[필수제출\\s*\\d+].*")) {
            // A mandatory marker does not resolve whether a limitation or exemption concerns this
            // document or a supporting proof. Keep the complete row when that scope is ambiguous.
            String instructions = String.join(" ", values);
            if (!common || limitedSubmission(instructions)) return null;
            String body = values.get(0).replaceFirst("^\\[필수제출\\s*\\d+]\\s*", "");
            var document = java.util.regex.Pattern.compile("^(.{2,80}?(?:등본|초본|증명서|동의서|확인서))(?:\\s|\\(|★|$).*").matcher(body);
            if (!document.matches()) return null;
            // The mandatory marker applies to this main document; extra copies and supporting proofs remain in the full instructions.
            return statement(document.group(1).strip() + " 제출 대상", "모든 신청자 · 안내: " + values.get(0)
                + " · " + values.get(2) + " · MyMy서비스이용: " + values.get(1));
        }
        if (names.equals(List.of("구분", "구비서류")) || names.equals(List.of("대리인신청시", "구비서류"))) {
            // A channel-specific list can include alternatives, identification and a power of attorney.
            // Keep it together rather than interpreting each noun as a universal obligation.
            if (!values.get(0).matches(".*(?:본인|배우자|대리인|신청할 때|신청 시).*")) return null;
            return statement("서류 안내 · " + values.get(0) + " · 구비서류", values.get(1));
        }
        if (names.equals(List.of("구분", "제출서류", "내용", "발급기관")) && common
            && values.get(0).replaceAll("\\s+", "").equals("필수")) {
            boolean visitForm = values.get(2).replaceAll("\\s+", "").equals("•방문신청시수기작성(양식은신청장소에비치)");
            if (commonDocumentInstruction(values.get(1), values.get(2)) || visitForm) {
                String audience = visitForm && !visitingOnly ? "방문신청자" : "모든 신청자";
                return statement(values.get(1) + " 제출 대상", audience + " · 안내: " + values.get(2) + " · 발급처: " + values.get(3));
            }
        }
        boolean notes = names.equals(List.of("구분", "제출서류", "유의사항"))
            || names.equals(List.of("구분", "제출서류", "내용", "발급기관"))
            || names.equals(List.of("구비서류", "구비서류", "비고", "부수"))
            || names.equals(List.of("구비서류", "구비서류", "비고", "MyMy서비스이용", "부수"))
            || names.equals(List.of("제출서류", "내용", "MyMy서비스이용", "발급처"));
        int name = -1, issuer = -1;
        var detail = new ArrayList<String>();
        boolean audience = false;
        for (int i = 0; i < names.size(); i++) {
            String header = names.get(i);
            if (DOCUMENTS.contains(header)) {
                // A merged 구비서류 heading also covers the category to the left of the document name.
                if (name >= 0) {
                    if (!header.equals("구비서류") || !names.get(name).equals(header) || i != name + 1) return null;
                    detail.add("구분: " + values.get(name));
                }
                name = i;
            } else if (notes && Set.of("구분", "유의사항", "비고", "내용").contains(header)) {
                detail.add(header + ": " + values.get(i));
                audience = true;
            } else if (AUDIENCES.contains(header) || header.equals("제출대상및유의사항")) {
                detail.add(values.get(i));
                audience = true;
            } else if (Set.of("발급처", "발급기관").contains(header) && issuer < 0) issuer = i;
            else if (Set.of("MyMy서비스이용", "MyMy서비스", "부수", "비고", "발급방법").contains(header))
                detail.add(header + ": " + values.get(i));
            else return null;
        }
        if (name < 0 || !audience) return null;
        if (issuer >= 0 && !values.get(issuer).isBlank()) detail.add("발급처: " + values.get(issuer));
        String title = values.get(name).strip().replaceFirst("[,，]$", "").strip();
        String value = String.join(" · ", detail);
        String known = statement(title + " 제출 대상", value);
        if (known != null) return known;
        // Compound proofs and explicit alternatives have meaning only with their full recipient column.
        if ((!notes && names.stream().noneMatch(AUDIENCES::contains)) || title.length() > 160 || !title.matches(".*(?:증명|확인서|등본|초본|등록증|신분증|동의서|소견서|진단서|계약서|신청서|사본).*"))
            return null;
        return statement("서류 안내 · " + title, value);
    }

    private static boolean commonDocumentInstruction(String title, String instructions) {
        // A merged 필수 cell alone is insufficient: the same list can contain conditional family
        // proofs. Resolve only explicit instructions about this main document, retaining all
        // additional copies, signing ages and exemptions in its full method.
        if (limitedSubmission(title + " " + instructions)) return false;
        String first = instructions.strip().replaceFirst("^[•·]\\s*", "").split("\\s*[•*]\\s*", 2)[0].replaceAll("\\s+", "");
        String ownDocument = title.replaceAll("\\s+", "").replaceFirst("^신청자의", "");
        return first.equals("신청자본인" + ownDocument + "제출")
            || first.matches("\\(동의방법\\)[가-힣A-Za-z0-9]+\\(https?://[^()]+\\)에서동의서를내려받은후,?내용을확인하고세대구성원전원이서명");
    }

    /**
     * Compare only consecutive, uniquely named fields within identical surrounding text.
     */
    static boolean sameItems(Source left, Source right, boolean compareValues) {
        var first = sequence(read(left), compareValues);
        return first != null && first.equals(sequence(read(right), compareValues));
    }

    private static List<Object> sequence(Source source, boolean compareValues) {
        var result = new ArrayList<Object>();
        var fields = new java.util.TreeMap<String, String>();
        var context = new StringBuilder();
        boolean found = false;
        for (var part : source.context())
            for (String raw : part.text().lines().toList()) {
                String line = raw.strip();
                if (line.isEmpty() || line.matches("-\\s*\\d{1,3}\\s*-")) continue;
                var rule = OfficialGuidanceItem.parse(line);
                if (rule != null) {
                    found = true;
                    if (!context.isEmpty()) {
                        result.add(context.toString());
                        context.setLength(0);
                    }
                    if (fields.putIfAbsent(rule.kind() + ":" + rule.key(), compareValues ? compact(rule.value()) : "") != null)
                        return null;
                } else {
                    if (!fields.isEmpty()) result.add(java.util.Map.copyOf(fields));
                    fields.clear();
                    context.append(compact(line));
                }
            }
        if (!fields.isEmpty()) result.add(java.util.Map.copyOf(fields));
        if (!context.isEmpty()) result.add(context.toString());
        return found ? result : null;
    }

    private static String compact(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replaceAll("[\\s\\p{Zs}]+", "");
    }

    static boolean header(String line) {
        var cells = Arrays.stream(line.split("\\s+[:：]\\s+", -1)).map(s -> s.replaceAll("\\s+", "")).toList();
        if (cells.stream().anyMatch(DOCUMENTS::contains) && documentRow(cells, java.util.Collections.nCopies(cells.size(), "가족관계증명서"), false, false) != null)
            return true;
        if (cells.equals(List.of("배점항목", "배점기준"))) return true;
        if (cells.equals(List.of("구분", "유형", "세부자격요건"))) return true;
        if (cells.equals(List.of("순위", "입주자격")) || cells.equals(List.of("순위", "신청자격"))) return true;
        if (cells.size() == 2 && APPLICANTS.contains(cells.get(0)) && ELIGIBILITY.contains(cells.get(1))) return true;
        return cells.size() == 2 && (ITEMS.contains(cells.get(0)) && VALUES.contains(cells.get(1))
            || DOCUMENTS.contains(cells.get(0)) && AUDIENCES.contains(cells.get(1)))
            || cells.size() == 3 && ITEMS.contains(cells.get(0)) && (cells.subList(1, 3).equals(List.of("정정전", "정정후"))
            || cells.subList(1, 3).equals(List.of("변경전", "변경후")));
    }

    private static String statement(String subject, String value) {
        String text = subject.strip() + ": " + value.strip();
        return OfficialGuidanceItem.parse(text) == null ? null : text;
    }
}
