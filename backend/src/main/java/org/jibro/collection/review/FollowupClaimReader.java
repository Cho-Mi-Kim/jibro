package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.followup.OfficialFollowupModels.Related;
import org.jibro.notice.HousingModels.Evidence;

/**
 * Closed amendment syntax and explicit announcement fields; unknown governing prose remains a blocker.
 */
final class FollowupClaimReader {
    record Value(String field, String statement, boolean uncertain) {
    }

    record Change(Value value, String before, Source source, String location, String raw, LocalDate published,
                  String relationshipProof) {
    }

    record UnresolvedClause(Source source, String location, String statement) {
    }

    record Reading(List<Change> changes, List<String> blockers, List<UnresolvedClause> governingClauses) {
    }

    private static final Pattern PREFIX = Pattern.compile("^(?:변경|정정)\\s*(전|후)\\s*[:：]\\s*(.+)$");
    private static final Pattern INTAKE = Pattern.compile("^((?:신청|청약|접수)\\s*(?:접수\\s*)?)(기간|시작|마감)\\s*[:：]\\s*(20\\d{2}[.년/-][\\d.월일/:()월화수목금토\\s~∼～–-]+)$");
    private static final Pattern EVENT = Pattern.compile("^(?:예비입주자\\s*)?(서류\\s*제출\\s*대상자|서류\\s*대상자|대상자|예비당첨자|당첨자|결과)\\s*발표(?:일)?(?:가|는|은)?\\s*[:：]?\\s*(.+)$");
    private static final Pattern DATE = Pattern.compile("^(20\\d{2})[.년/-]\\s*(\\d{1,2})[.월/-]\\s*(\\d{1,2})[.일]?\\s*(?:\\([월화수목금토일](?:요일)?\\))?\\s*(?:(\\d{1,2}):(\\d{2})\\s*)?(이후)?\\s*(.*)$");
    private static final Set<String> HEADINGS = Set.of("정정 내용", "변경 내용", "신청 자격", "제출 서류", "공급 일정", "신청 일정", "향후 일정", "일정 안내", "내용", "첨부파일이 없습니다.");
    private static final Pattern GOVERNING = Pattern.compile("자격|소득|자산|예외|세대|기준|요건|신청기간|접수기간|기한|마감|계약|납부|제출|발표|당첨|서류|일정");
    private static final Pattern EFFECT = Pattern.compile("변경|연기|확정|미정|예정|취소|중단|연장|단축|정정");
    private static final Pattern DATE_REFERENCE = Pattern.compile("(?:20\\d{2}|[‘’'`]\\d{2})[.년/-]\\s*\\d{1,2}[.월/-]\\s*\\d{1,2}");

    private FollowupClaimReader() {
    }

    static Reading read(Related related, List<Source> originals) {
        var changes = new ArrayList<Change>();
        var blockers = new LinkedHashSet<String>();
        var governingClauses = new ArrayList<UnresolvedClause>();
        for (Source source : related.sources()) {
            var items = OfficialReissuedItems.changes(originals, source, related);
            if (items.isPresent()) {
                changes.addAll(items.get());
                continue;
            }
            for (Part part : source.context()) {
                String before = null;
                for (String raw : part.text().split("\\R")) {
                    String line = clean(raw);
                    if (line.isBlank() || OfficialGuidanceRows.header(line) || HEADINGS.contains(line) || line.equals(clean(related.title())) && !governingTitle(line)
                        || nonGoverning(line, part.location(), related)) continue;
                    var pair = PREFIX.matcher(line);
                    if (pair.matches()) {
                        if (pair.group(1).equals("전")) {
                            if (before != null) blockers.add("FOLLOWUP_AMENDMENT_PAIR_INCOMPLETE");
                            before = pair.group(2);
                            continue;
                        }
                        Value old = before == null ? null : value(before);
                        Value replacement = value(pair.group(2));
                        if (old == null || replacement == null || !old.field().equals(replacement.field()) || replacement.uncertain())
                            blockers.add("FOLLOWUP_AMENDMENT_SCOPE_UNSUPPORTED");
                        else changes.add(new Change(replacement, clean(before), source, part.location(), raw,
                            related.publishedDate(), related.relationshipProof()));
                        before = null;
                        continue;
                    }
                    if (before != null) {
                        blockers.add("FOLLOWUP_AMENDMENT_PAIR_INCOMPLETE");
                        before = null;
                    }
                    Value event = event(line);
                    if (event != null && related.kind().equals("FOLLOWUP")) {
                        changes.add(new Change(event, null, source, part.location(), raw, related.publishedDate(), related.relationshipProof()));
                    } else {
                        // No blanket exemption for a whole 'follow-up' document: a new exception or unknown
                        // instruction must not disappear merely because another line has a usable date.
                        blockers.add("FOLLOWUP_CLAUSE_UNSUPPORTED");
                        if (GOVERNING.matcher(line).find() || value(line) != null)
                            governingClauses.add(new UnresolvedClause(source, part.location(), line));
                    }
                }
                if (before != null) blockers.add("FOLLOWUP_AMENDMENT_PAIR_INCOMPLETE");
            }
        }
        if (changes.isEmpty()) blockers.add("FOLLOWUP_CHANGE_NOT_IDENTIFIED");
        return new Reading(List.copyOf(changes), List.copyOf(blockers), List.copyOf(governingClauses));
    }

    static Reading agency(Related related, List<Source> primary) {
        var changes = new ArrayList<Change>();
        var blockers = new LinkedHashSet<String>();
        for (var source : related.sources()) {
            if (primary.stream().anyMatch(old -> source.sha256().equals(old.sha256()))) continue;
            for (var part : source.context()) {
                if (part.location().contains("숨김")) continue;
                String before = null;
                for (String raw : part.text().lines().toList()) {
                    String line = clean(raw);
                    if (line.isBlank()) continue;
                    if (line.matches("^[\\[(（]?(?:변경|정정)\\s*[전후][\\])）]?[:：]?$")) {
                        blockers.add("AGENCY_AMENDMENT_PAIR_INCOMPLETE");
                        before = null;
                        continue;
                    }
                    var pair = PREFIX.matcher(line);
                    if (pair.matches() && pair.group(1).equals("전")) {
                        if (before != null) blockers.add("AGENCY_AMENDMENT_PAIR_INCOMPLETE");
                        before = pair.group(2);
                        continue;
                    }
                    String statement = pair.matches() ? pair.group(2) : line;
                    var claim = value(statement);
                    if (pair.matches() || before != null) {
                        var old = before == null ? null : value(before);
                        if (pair.matches() && old != null && claim != null && old.field().equals(claim.field())) {
                            if (!claim.field().startsWith("SCHEDULE:"))
                                changes.add(new Change(claim, before, source, part.location(), raw, related.publishedDate(), related.relationshipProof()));
                        } else blockers.add("AGENCY_AMENDMENT_PAIR_INCOMPLETE");
                        before = null;
                    } else if (claim != null && !claim.field().startsWith("SCHEDULE:")) {
                        changes.add(new Change(claim, null, source, part.location(), raw, related.publishedDate(), related.relationshipProof()));
                    } else if (GOVERNING.matcher(line).find()
                        && (line.replace("행정정보", "").replaceFirst("신청서 내용의 취소 또는 정정이 불가능", "").contains("정정") || line.matches(".*(?:자격|소득|요건|대상|필수\\s*서류|제출\\s*서류).{0,30}(?:변경합니다|변경됩니다|면제합니다|제외합니다|추가합니다|완화합니다).*")) && !line.equals(clean(related.title()))) {
                        blockers.add("AGENCY_AMENDMENT_SCOPE_UNREAD");
                    }
                }
                if (before != null) blockers.add("AGENCY_AMENDMENT_PAIR_INCOMPLETE");
            }
        }
        return new Reading(List.copyOf(changes), List.copyOf(blockers), List.of());
    }

    static Value value(String raw) {
        String line = clean(raw);
        var channel = ChannelAmendment.parse(line);
        if (channel != null) return new Value("SCHEDULE:CHANNEL:" + channel.method(), line, false);
        if (IntakeExplanation.matches(line)) return new Value("SCHEDULE:APPLICATION", line, false);
        var intake = INTAKE.matcher(line);
        if (intake.matches()) {
            String field = switch (intake.group(2)) {
                case "시작" -> "SCHEDULE:START";
                case "마감" -> "SCHEDULE:END";
                default -> "SCHEDULE:APPLICATION";
            };
            return new Value(field, line, false);
        }
        var rule = GuidanceRuleGrammar.parse(line);
        if (rule != null) {
            String key = rule.kind().equals("SCHEDULE") ? switch (rule.key()) {
                case "announcement" -> "SCHEDULE:RESULT";
                case "documents-due" -> "SCHEDULE:DOCUMENTS";
                default -> "SCHEDULE:APPLICATION";
            } : rule.kind() + ":" + rule.key();
            return new Value(key, line, false);
        }
        return event(line);
    }

    static Value event(String raw) {
        String line = clean(raw).replaceFirst("^(?:확정\\s*일정|변경\\s*일정)\\s*[:：]\\s*", "");
        var event = EVENT.matcher(line);
        if (!event.matches()) return null;
        boolean selection = event.group(1).contains("대상자");
        String field = "SCHEDULE:" + (selection ? "SELECTION" : "RESULT");
        String title = selection ? "서류 제출 대상자 발표" : "결과 발표";
        String value = event.group(2).strip();
        var date = DATE.matcher(value);
        if (date.matches()) {
            String suffix = date.group(7).strip();
            if (!suffix.matches("(?:[.。]|(?:로|으로)?\\s*(?:변경|연기|확정)(?:되었습니다|합니다|됩니다|되었음|됨)?[.。]?)?")) {
                if (suffix.matches("(?:예정|잠정)(?:입니다)?[.。]?")) return new Value(field, title + ": 미정", true);
                return null;
            }
            try {
                LocalDate day = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
                String time = date.group(4) == null ? "" : " " + LocalTime.of(Integer.parseInt(date.group(4)), Integer.parseInt(date.group(5)));
                return new Value(field, title + ": " + day.toString().replace('-', '.') + time + (date.group(6) == null ? "" : " 이후"), false);
            } catch (java.time.DateTimeException invalid) {
                return null;
            }
        }
        if (value.matches("(?:미정|추후(?:\\s*공지)?|별도\\s*안내|잠정|연기)(?:입니다|되었습니다|합니다|됩니다)?[.。]?"))
            return new Value(field, title + ": 미정", true);
        return null;
    }

    static String clean(String text) {
        return text.replaceAll("[\\p{Zs}\\t]+", " ").strip().replaceFirst("^[•▪■*－-]\\s*", "").strip();
    }

    private static boolean nonGoverning(String line, String location, Related related) {
        // Only a provenance-labelled metadata part with a complete field shape is non-governing.
        if (location.equals("공식 후속 공지 게시 정보") && line.matches(
            "(?:유형\\s*[:：]?\\s*(?:행복주택|국민임대|영구임대|공공임대|매입임대|전세임대|공공분양)\\s*)?"
                + "(?:담당부서\\s*[:：]?\\s*[가-힣0-9· -]{1,60}?\\s*)?게시일\\s*[:：]?\\s*20\\d{2}[.-]\\d{1,2}[.-]\\d{1,2}"))
            return !Pattern.compile("자격|소득|자산|예외|완화|기준|마감|기한").matcher(line).find();
        if (line.matches("감사합니다[.。]?|끝[.。]?|고객님(?:들)?의? 많은 양해를 부탁드립니다[.。]?")) return true;
        boolean governing = Pattern.compile("자격|소득|자산|예외|세대|기준|요건|신청기간|접수기간|기한|마감|계약|납부|제출").matcher(line).find();
        if (!governing && line.matches("(?:문의(?:처|사항)?|연락처)\\s*[:：]\\s*(?:LH\\s*)?[가-힣 ·()0-9-]{1,100}")) return true;
        // A sentence used by Discovery's exact recruitment relationship proof is identity context,
        // not a new rule. Never exempt a larger paragraph that also contains governing instructions.
        if (!governing && !EFFECT.matcher(line).find() && DATE_REFERENCE.matcher(line).results().count() == 1
            && related.relationshipProof().replaceAll("\\s+", " ").contains(line)
            && line.matches(".*(?:20\\d{2}|[‘’'`]\\d{2})[.년/-].*공고.*(?:관련하여[,，]?|안내드립니다[.。]?|알려드립니다[.。]?)"))
            return true;
        String contact = line.replaceFirst("^\\(\\*?\\s*", "").replaceFirst("\\)$", "");
        return contact.matches("LH청약(?:센터|플러스)\\(apply\\.lh\\.or\\.kr\\) 및 ARS\\(\\d{2,4}-\\d{3,4}\\)으로 확인,\\s*당첨자만 개별 문자 통보");
    }

    private static boolean governingTitle(String line) {
        return value(line) != null || GOVERNING.matcher(line).find() && EFFECT.matcher(line).find();
    }
}
