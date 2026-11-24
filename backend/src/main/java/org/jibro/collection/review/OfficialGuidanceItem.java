package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

/**
 * Explicit subject/value fields. The entire value, including exceptions, belongs to the item.
 */
final class OfficialGuidanceItem {
    // Only a single, explicitly named applicant category can scope a qualification field.
    private static final Map<String, String> APPLICANTS = applicants();
    private static final String APPLICANT = APPLICANTS.keySet().stream()
        .map(name -> Pattern.quote(name).replace(" ", "\\E\\s*\\Q"))
        .collect(Collectors.joining("|", "(?:", ")"));
    private static final String APPLICANT_LABEL = APPLICANT + "(?:\\s*계층)?";
    private static final String CONDITION = "(?:주택\\s*요건|신청\\s*자격|입주\\s*자격|거주\\s*(?:요건|기준)|무주택\\s*(?:요건|기준)|"
        + "소득\\s*(?:요건|기준)|(?:총)?자산\\s*(?:요건|기준)|자동차\\s*(?:요건|기준)|연령\\s*(?:요건|기준)|"
        + "세대\\s*구성\\s*(?:요건|기준)|선정\\s*(?:순위|기준)|신청\\s*기준\\s*[①-⑳]|[1-9]\\s*순위\\s*(?:신청\\s*)?자격)";
    private static final String APPLICANT_GROUP = APPLICANT_LABEL + "(?:\\s*[·,/]\\s*" + APPLICANT_LABEL + ")*";
    private static final Pattern SCORE = Pattern.compile("^(배점 항목 · [^:：\\r\\n]{2,200})\\s*[:：]\\s*(.{2,1000})$");
    private static final Pattern DOCUMENT_GROUP = Pattern.compile("^(서류 안내 · [^:：\\r\\n]{2,175})\\s*[:：]\\s*(.{2,1000})$");
    private static final Pattern FIELD = Pattern.compile("^((?:" + APPLICANT_GROUP + "\\s*)?" + CONDITION + ")\\s*[:：]\\s*(.{2,1000})$");
    private static final Pattern APPLICANT_FIELD = Pattern.compile("^(" + APPLICANT + ")(?:\\s*계층)?\\s*(?:" + CONDITION + ")$");
    private static final Pattern APPLICANT_SECTION = Pattern.compile("^(?:표 안내 ·\\s*/\\s*)?\\d{1,2}-\\d{1,2}\\.\\s*(.+)$");
    private static final Pattern DOCUMENT = Pattern.compile("^([가-힣A-Za-z0-9·∙ㆍ･() /-]{2,80}?(?:등본|초본|증명서|동의서|확인서|신청서|계약서|등록증|증명|위임장|확약서|각서)(?:\\s*\\((?:상세|전부표기(?:,\\s*상세)?)\\)|\\s*사본)?)\\s+제출\\s*대상\\s*[:：]\\s*(.{2,1000})$");
    private static final Pattern REFERENCE = Pattern.compile("상동|위와\\s*같|아래와\\s*같|전술|후술|[〃]|정정\\s*[전후]\\s*[:：]|변경\\s*[전후]\\s*[:：]");

    private static final Pattern PENDING = Pattern.compile("^(?:미정|추후\\s*(?:안내|공지|확정)|별도\\s*(?:안내|공지))(?:\\s*예정)?(?:입니다)?[.。]?$");
    private static final Pattern AGENCY = Pattern.compile("^(?:(?:모집기관|관리사무소|공급기관|담당기관)(?:에|으로|의)?\\s*)"
        + "(?:개별\\s*)?(?:문의|확인)(?:가?\\s*필요(?:합니다)?|해\\s*주세요|하시기\\s*바랍니다|하세요)?[.。]?$");

    static boolean exempt(GuidanceRuleGrammar.Rule rule) {
        String audience = rule.value().strip();
        return rule.kind().equals("DOCUMENT_ITEM") && audience.matches(
            "(?:(?:신청자\\s*)?(?:전원|모두|전체)\\s*)?(?:제출\\s*(?:불필요|면제)|제출하지\\s*않습니다|제출할\\s*필요가\\s*없습니다)[.。]?");
    }

    static boolean commonRecipient(GuidanceRuleGrammar.Rule rule) {
        return rule.kind().equals("DOCUMENT_ITEM") && rule.value().split(" · (?:발급처|안내): ", 2)[0]
            .replaceAll("\\s+", "").matches("모든신청자|신청자전원|전체신청자");
    }

    static boolean needsAgency(GuidanceRuleGrammar.Rule rule) {
        return rule.key().startsWith("field-") && AGENCY.matcher(rule.value()).matches();
    }

    private OfficialGuidanceItem() {
    }

    static String applicantType(String title) {
        var match = APPLICANT_FIELD.matcher(title.strip());
        if (!match.matches()) return null;
        String name = match.group(1).replaceAll("\\s+", "");
        return APPLICANTS.entrySet().stream().filter(entry -> entry.getKey().replace(" ", "").equals(name))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    static List<String> applicantTypes(String title) {
        String single = applicantType(title);
        if (single != null) return List.of(single);
        String names = title.replaceFirst("\\s*" + CONDITION + "$", "");
        if (names.equals(title)) return List.of();
        var result = new ArrayList<String>();
        for (String name : names.split("\\s*[·,/]\\s*")) {
            String type = applicantType(name.strip() + " 신청자격");
            if (type == null) return List.of();
            if (!result.contains(type)) result.add(type);
        }
        return List.copyOf(result);
    }

    static String applicantSection(String line) {
        var heading = APPLICANT_SECTION.matcher(line.strip());
        if (!heading.matches()) return null;
        String name = heading.group(1);
        return name.endsWith("계층") || applicantType(name + " 신청자격") != null ? name : null;
    }

    static Set<String> missingApplicantTypes(List<String> sections, List<String> criterionTitles) {
        var declared = new java.util.LinkedHashSet<String>();
        for (String text : sections)
            for (String line : text.lines().toList()) {
                if (line.strip().matches("[1-9]\\s*순위\\s*자격\\s*[:：]\\s*[가-하]\\..*"))
                    declared.add("UNRECOGNIZED:순위별 신청 대상");
                String heading = applicantSection(line);
                if (heading == null) continue;
                var types = applicantTypes(heading + " 신청자격");
                if (types.isEmpty()) declared.add("UNRECOGNIZED:" + heading);
                else declared.addAll(types);
            }
        for (String title : criterionTitles)
            if (title.matches(".*(?:신청|입주)\\s*자격$")) {
                String type = applicantType(title);
                if (type != null) declared.remove(type);
            }
        return java.util.Collections.unmodifiableSet(declared);
    }

    static String documentApplicant(String recipient) {
        String name = recipient.replaceFirst("\\s+혼인관계\\s*증명서\\s*신청시,\\s*입주시\\s*총\\s*\\d+회\\s*제출$", "").replaceAll("\\s+", "");
        return applicantType(name + " 신청자격") == null ? null : name;
    }

    /**
     * A named legal category in one complete, lettered rank row; the full row remains its criterion.
     */
    static String rankedApplicant(String rank, String value) {
        if (!rank.replaceAll("\\s+", "").matches("[1-9]순위") || !value.strip().matches("(?s)^[가-하]\\..+")) return null;
        String text = value.replaceAll("\\s+", "");
        var names = new ArrayList<String>();
        if (text.contains("생계급여수급자") && text.contains("의료급여수급자")) names.add("생계·의료급여 수급자");
        if (text.contains("국가유공자또는그유족") && text.contains("보훈보상대상자")) names.add("국가유공자 등");
        if (text.contains("등록한일본군위안부피해자")) names.add("등록 일본군위안부 피해자");
        if (text.contains("지원대상한부모가족")) names.add("한부모가족");
        if (text.contains("북한이탈주민으로서")) names.add("북한이탈주민");
        if (text.contains("장애인등록증이교부된사람")) names.add("장애인");
        if (text.contains("직계존속") && text.contains("부양") && text.contains("소득인정액이하인사람")) names.add("고령 직계존속 부양자");
        if (text.contains("아동복지시설에서퇴소") && text.contains("시설의장이추천")) names.add("아동복지시설 퇴소자");
        if (text.contains("65세이상인사람으로서") && text.contains("수급권자") && text.contains("차상위계층")) names.add("고령 수급권자·차상위계층");
        return names.size() == 1 ? names.get(0) : null;
    }

    record NumberedFields(List<String> statements, boolean complete, String group, List<String> members) {
        NumberedFields(List<String> statements, boolean complete) {
            this(statements, complete, null, List.of());
        }
    }

    private static final NumberedFields NO_NUMBERED_FIELDS = new NumberedFields(List.of(), false);

    static List<String> standaloneApplicantFields(String heading, String text) {
        // This category is defined by a complete benefit-status sentence, not by an inferred
        // age/income threshold. Household definitions and subsequent proof clauses stay as questions.
        if (!"HOUSING_BENEFIT".equals(applicantType(heading + " 신청자격"))) return List.of();
        var lines = text.lines().map(String::strip).filter(line -> line.matches(
                "입주자\\s*모집\\s*공고일\\([^\\n)]+\\)\\s*현재\\s*무주택세대구성원인\\s*주거급여\\s*수급자[.。]?"))
            .toList();
        if (lines.size() != 1) return List.of();
        String statement = heading + " 신청자격: " + lines.get(0);
        return parse(statement) == null ? List.of() : List.of(statement);
    }

    /**
     * Identity clauses and their explicitly enumerated requirements in the same official section.
     */
    static NumberedFields numberedApplicantFields(String text) {
        String normalized = text.replace('➀', '①').replace('➁', '②').replace('➂', '③')
            .replace('➃', '④').replace('➄', '⑤').replace('➅', '⑥');
        var identity = Pattern.compile("(?m)^\\s*([①-⑳]-[㉮-㉻])\\s*\\(([^)]+)\\)\\s*(.+)$").matcher(normalized);
        var identities = new LinkedHashMap<String, String>();
        var fields = new ArrayList<String>();
        int firstIdentity = -1, lastIdentity = -1;
        while (identity.find()) {
            if (firstIdentity < 0) firstIdentity = identity.start();
            lastIdentity = identity.end();
            String name = identity.group(2).strip();
            if (applicantType(name + " 신청자격") == null || identities.putIfAbsent(identity.group(1), name) != null)
                return NO_NUMBERED_FIELDS;
            // A wrapped clause ends at the next numbered requirement, not at a PDF line break.
            int end = normalized.length();
            var next = Pattern.compile("(?m)^\\s*(?:[①-⑳](?:-[㉮-㉻])?\\s|[■《])").matcher(normalized);
            if (next.find(identity.end())) end = next.start();
            String value = (identity.group(3) + normalized.substring(identity.end(), end)).replaceAll("\\s+", " ").strip();
            String statement = name + " 신청자격: " + value;
            if (parse(statement) == null) return NO_NUMBERED_FIELDS;
            fields.add(statement);
        }
        if (identities.size() < 2) return NO_NUMBERED_FIELDS;
        var scopes = new LinkedHashMap<Character, List<String>>();
        String compact = normalized.replaceAll("\\s+", "");
        for (var entry : identities.entrySet()) {
            var scope = Pattern.compile(Pattern.quote(entry.getValue()) + "(?:인경우|의경우|은|는)" + Pattern.quote(entry.getKey())
                + "[와과]([②-⑳])[～~∼–-]([②-⑳])").matcher(compact);
            if (!scope.find()) return NO_NUMBERED_FIELDS;
            char first = scope.group(1).charAt(0), last = scope.group(2).charAt(0);
            if (first > last || scope.find()) return NO_NUMBERED_FIELDS;
            for (char number = first; number <= last; number++)
                scopes.computeIfAbsent(number, ignored -> new ArrayList<>()).add(entry.getValue());
        }
        int endOfGroup = normalized.length();
        var boundary = Pattern.compile("(?m)^\\s*[■《]").matcher(normalized);
        if (boundary.find(lastIdentity)) endOfGroup = boundary.start();
        var clause = Pattern.compile("(?m)^\\s*([②-⑳])\\s+(.+)$").matcher(normalized).region(firstIdentity, endOfGroup);
        var seen = new java.util.HashSet<Character>();
        boolean complete = true;
        while (clause.find()) {
            char number = clause.group(1).charAt(0);
            if (!scopes.containsKey(number) || !seen.add(number)) return NO_NUMBERED_FIELDS;
            int end = normalized.length();
            var next = Pattern.compile("(?m)^\\s*(?:[①-⑳](?:-[㉮-㉻])?\\s|[■《])").matcher(normalized);
            if (next.find(clause.end())) end = next.start();
            String value = (clause.group(2) + normalized.substring(clause.end(), end)).replaceAll("\\s+", " ").strip();
            String statement = String.join("·", scopes.get(number)) + " 신청 기준 " + number + ": " + value;
            // Long tables remain intact in the section reference; they are never shortened to a bare limit.
            if (parse(statement) != null) fields.add(statement);
            else complete = false;
        }
        if (!seen.containsAll(scopes.keySet())) return NO_NUMBERED_FIELDS;
        var headings = normalized.lines().map(OfficialGuidanceItem::applicantSection).filter(Objects::nonNull).distinct().toList();
        var members = identities.values().stream().map(name -> applicantType(name + " 신청자격")).distinct().toList();
        return new NumberedFields(List.copyOf(fields), complete, headings.size() == 1 ? headings.get(0) : null, members);
    }

    private static Map<String, String> applicants() {
        var supported = Set.of("STUDENT", "JOBSEEKER", "YOUTH", "EARLY_CAREER", "NEWLYWED", "PREMARITAL",
            "SINGLE_PARENT", "ELDER", "INDUSTRIAL", "HOUSING_BENEFIT", "LIVELIHOOD_MEDICAL_BENEFIT",
            "VETERAN", "REGISTERED_VICTIM", "DEFECTOR", "DISABLED", "SUPPORT_ELDER", "CARE_LEAVER", "ELDER_BENEFIT");
        try (var stream = OfficialGuidanceItem.class.getResourceAsStream("/preparation/preparation-audience-vocabulary.json")) {
            if (stream == null) throw new IllegalStateException("Missing preparation audience vocabulary");
            var types = new ObjectMapper().readTree(stream).get("types");
            var result = new LinkedHashMap<String, String>();
            for (String type : supported)
                for (var name : types.get(type)) result.put(name.asString(), type);
            return java.util.Collections.unmodifiableMap(result);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read preparation audience vocabulary", exception);
        }
    }

    static GuidanceRuleGrammar.Rule parse(String text) {
        var field = FIELD.matcher(text);
        if (!field.matches()) field = SCORE.matcher(text);
        boolean condition = field.matches();
        if (!condition) {
            field = DOCUMENT.matcher(text);
            if (!field.matches()) field = DOCUMENT_GROUP.matcher(text);
            if (!field.matches()) return null;
        }
        String title = field.group(1).strip(), detail = field.group(2).strip();
        if (PENDING.matcher(detail).matches() || REFERENCE.matcher(detail).find() || detail.matches("^(?:별도|미정|추후|별첨|공고문|첨부).*확인[. ]*$"))
            return null;
        String kind = condition ? "CONDITION" : "DOCUMENT_ITEM";
        String id = UUID.nameUUIDFromBytes(title.replace(" ", "").getBytes(StandardCharsets.UTF_8)).toString();
        return new GuidanceRuleGrammar.Rule(kind, "field-" + id, detail, title, List.of());
    }
}
