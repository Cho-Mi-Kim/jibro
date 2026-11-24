package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.local.DocumentContextReader;
import org.jibro.notice.HousingModels.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete form syntax, semantic purpose and source bindings; not a notice approval decision.
 */
final class SupplementaryRules {
    enum Stage {APPLICATION, SELECTION, CONTRACT, PRE_ENTRY, ENTRY, RESIDENCE, RENEWAL, DATA_PROCESSING}

    enum Role {
        RENEWAL_REFERENCE, WAITLIST, CONSENT, POST_VERIFICATION, APPLICATION_FORM, RETURN_STUDY,
        PROXY_AUTHORITY, ACCESSIBILITY_REQUEST
    }

    enum Topic {
        RENEWAL, WAITLIST, DEPOSIT, MOVE_PLAN, FORM_DECLARATION, RETURN_STUDY, PROXY,
        PRIVACY_BASIC, PRIVACY_IDENTIFIER, PRIVACY_SENSITIVE, PRIVACY_THIRD_PARTY, PRIVACY_MARKETING, PRIVACY_DELETION, ACCESSIBILITY
    }

    enum Effect {
        REFERENCE_TO_NOTICE_AND_LAW, VACATE_IF_NOT_RENEWABLE, PREMIUM_MAY_APPLY, RENEWAL_MAY_END,
        NO_DUPLICATE_SAME_TYPE, REMOVE_ALL_CONSTRUCTION_WAITLISTS, EXCLUDE_NOTICE_BEFORE_CONTRACT,
        CANCEL_WITHOUT_PENALTY_OR_INTEREST, MISSING_PROOF_PENALTY, WITHDRAWAL_PENALTY, PLAN_AFTER_FINAL_APPROVAL,
        MANDATORY_CONSENT, GUARDIAN_SIGNATURE, COLLECT_IDENTIFIER_UNDER_LAW, OPTIONAL_SENSITIVE_CONSENT,
        DISABILITY_PRIORITY_SCORE_FACILITY_EXCLUDED, OPTIONAL_MARKETING_CONSENT, MARKETING_REFUSAL_NO_HOUSING_SERVICE_LIMIT,
        DELETE_WITH_LEGAL_RETENTION_EXCEPTION, RETENTION_PERIOD, APPLICATION_OR_CONTRACT_MAY_BE_REFUSED,
        EARLY_MOVE_LOSS_CLAIM_RESTRICTION, PROXY_PARTIES_RESOLVE_DISPUTES,
        DECLARATION_SCOPE_REQUIRES_COMPARISON, RETURN_BY_NEXT_SEMESTER,
        RETURN_PROOF_WITHIN_MONTHS, CANCEL_IF_RETURN_PROOF_MISSING, AUTHORIZE_PROXY, PROXY_PROOFS,
        ACCESSIBILITY_ON_REQUEST, MASK_RESIDENT_ID_SUFFIX
    }

    enum Condition {
        NOTICE_AND_LAW, STAY_PERMITTED_AND_INCOME_ASSETS_EXCEEDED, ACQUIRES_HOME,
        ACTUAL_ENTRY_OR_FULL_DEPOSIT_AFTER_DESIGNATED_PERIOD, DISQUALIFIED, PROOFS_MISSING, VOLUNTARY_WITHDRAWAL,
        FINAL_ENTRY_APPROVAL, BEFORE_FINAL_ENTRY_APPROVAL, NOT_QUALIFICATION_DEFECT, FORM_LISTED_SIGNERS, DECLINES_REQUIRED_CONSENT, DECLINES_SENSITIVE_CONSENT,
        DECLINES_MARKETING_CONSENT, LEGAL_RETENTION_EXCEPTION, VISIT_APPLICATION, RETURNING_STUDENT,
        USES_THIS_PROXY_FORM, INITIAL_TENANT_SELF_OR_DEPENDENT_VULNERABLE_AND_HEAD_REQUESTS, ACCESSIBILITY_PROOF_ONLY
    }

    record Scope(Stage stage, String subject, List<String> applicantTypes, Set<Condition> conditions) {
    }

    record Claim(Scope scope, Topic topic, Effect effect, Map<String, String> operands) {
    }

    record FormRule(Role role, List<Claim> claims, VerifiedPreparationForms.Block block,
                    List<Integer> signingYears, List<String> recipientOffices) {
    }

    record Reading(List<FormRule> rules, List<String> blockers) {
        boolean complete() {
            return blockers.isEmpty() && !rules.isEmpty();
        }
    }

    record Identity(Source source, String title, String site, LocalDate publication, String office) {
    }

    record Context(Identity primary, List<FormRule> forms, Map<Source, SupplyInventory.Reading> inventories,
                   List<SiteInstructions.Reading> sites, List<ListingInstructions.Reading> listings,
                   Set<String> supplementalUrls, List<String> blockers) {
        boolean active() {
            return !supplementalUrls.isEmpty();
        }
    }

    record Position(int row, int column, int rowSpan, int columnSpan) {
    }

    record Layout(int rows, int columns, int parent, List<Position> cells) {
    }

    record CellPath(int table, Position cell) {
    }

    record Node(List<CellPath> cells, String syntax) {
    }

    record Structure(Map<String, List<Layout>> layouts, Map<String, List<Node>> nodes) {
    }

    private record Schema(Role role, String title, String recipient, List<Node> nodes, List<Layout> tables) {
    }

    private record Schemas(String basis, List<Schema> schemas) {
    }

    private record PrimaryRegion(String role, String begin, String end, List<String> clauses) {
    }

    private record PrimaryGrammar(String basis, List<PrimaryRegion> regions, String outsideDomain,
                                  List<String> outsideClauses) {
    }

    private static final PrimaryGrammar PRIMARY = primaryGrammar();
    private static final List<Schema> SCHEMAS = schemas();
    private static final Pattern SLOT = Pattern.compile("\\{\\{([a-zA-Z]+)}}");

    private SupplementaryRules() {
    }

    static Identity identity(Source source) {
        if (source.context().isEmpty()) return null;
        var part = source.context().get(0);
        var first = DocumentContextReader.read(source, part);
        if (!first.valid()) return null;
        String firstPage = first.text(part.text(), 0, part.text().length());
        String title = firstPage.lines().map(SupplementaryRules::compact)
            .filter(s -> s.contains("행복주택") && s.endsWith("모집")).findFirst().orElse("");
        var entity = Pattern.compile("^(.+?)(?:[0-9]+(?:[·,][0-9]+)*단지)?행복주택.+$").matcher(title);
        var date = Pattern.compile("입주자모집공고일\\[(20[0-9]{2})[.]([0-9]{1,2})[.]([0-9]{1,2})[.]").matcher(compact(firstPage));
        if (!entity.matches() || !date.find()) return null;
        LocalDate publication;
        try {
            publication = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
        var offices = Pattern.compile("한국토지주택공사([가-힣]{2,12})지역본부").matcher(text(source)).results()
            .map(m -> m.group(1)).distinct().toList();
        return new Identity(source, title, entity.group(1), publication, offices.size() == 1 ? offices.get(0) : null);
    }

    /**
     * Proposed source roles remain provisional until their claims are compared and materialized.
     */
    static Context context(Draft draft) {
        var primaries = draft.sources().stream().map(SupplementaryRules::identity).filter(Objects::nonNull).toList();
        if (primaries.size() != 1)
            return new Context(null, List.of(), Map.of(), List.of(), List.of(), Set.of(), List.of());
        var primary = primaries.get(0);
        var forms = new ArrayList<FormRule>();
        var inventories = new LinkedHashMap<Source, SupplyInventory.Reading>();
        var sites = new ArrayList<SiteInstructions.Reading>();
        var listings = new ArrayList<ListingInstructions.Reading>();
        var urls = new LinkedHashSet<String>();
        var blockers = new LinkedHashSet<String>();
        for (var source : draft.sources()) {
            if (source == primary.source()) continue;
            if (compact(source.label()).equals("공식상세본문")) {
                var read = ListingInstructions.read(source, primary);
                if (read.complete()) {
                    listings.add(read);
                    urls.add(source.url());
                } else blockers.addAll(read.blockers());
            } else if (text(source).startsWith("[별첨") && text(source).contains("단지별특성")) {
                var read = SiteInstructions.read(source, primary);
                if (read.complete()) {
                    sites.add(read);
                    urls.add(source.url());
                } else blockers.addAll(read.blockers());
            } else if (source.context().stream().anyMatch(p -> Pattern.compile("(?m)^\\s*\\[붙임\\s*[0-9]").matcher(p.text()).find())) {
                var read = readForms(source, primary.source(), primary.publication());
                if (read.complete()) {
                    forms.addAll(read.rules());
                    urls.add(source.url());
                } else blockers.addAll(read.blockers());
            } else if (source.context().stream().anyMatch(p -> p.location().startsWith("Excel 워크시트") && compact(p.text()).contains("공급공고관련공실현황"))) {
                var read = SupplyInventory.read(source, primary.source());
                if (read.complete()) {
                    inventories.put(source, read);
                    urls.add(source.url());
                } else blockers.addAll(read.blockers());
            }
        }
        if (!urls.isEmpty()) blockers.addAll(primaryBlocks(primary));
        return new Context(primary, List.copyOf(forms), Collections.unmodifiableMap(inventories), List.copyOf(sites), List.copyOf(listings), Set.copyOf(urls), List.copyOf(blockers));
    }

    /**
     * A complete domain block includes its unnumbered exceptions, tables and following sentences.
     */
    private static List<String> primaryBlocks(Identity primary) {
        var text = new StringBuilder();
        for (var part : primary.source().context()) {
            var read = DocumentContextReader.read(primary.source(), part);
            String body = read.text(part.text(), 0, part.text().length());
            var page = Pattern.compile("공고문 ([0-9]+)쪽").matcher(part.location());
            if (page.matches()) body = body.replaceAll("(?m)^\\s*-\\s*" + page.group(1) + "\\s*-\\s*$", "");
            text.append(body);
        }
        String body = compact(text.toString());
        var blockers = new ArrayList<String>();
        var covered = new ArrayList<int[]>();
        for (var region : PRIMARY.regions()) {
            int start = body.indexOf(region.begin());
            int end = start < 0 ? -1 : body.indexOf(region.end(), start + region.begin().length());
            if (start < 0 || end < 0 || body.indexOf(region.begin(), start + region.begin().length()) >= 0) {
                blockers.add("SUPPLEMENTARY_PRIMARY_SCOPE_MISSING:" + region.role());
                continue;
            }
            covered.add(new int[]{start, end});
            String actual = body.substring(start + region.begin().length(), end);
            var units = Arrays.stream(actual.split("(?=[•▪※])")).filter(v -> !v.isEmpty()).toList();
            boolean matched = units.size() == region.clauses().size();
            if (matched) for (int i = 0; i < units.size(); i++) {
                if (!primarySyntax(region.clauses().get(i), primary).matcher(units.get(i)).matches()) {
                    matched = false;
                    break;
                }
            }
            if (!matched) blockers.add("SUPPLEMENTARY_PRIMARY_BUNDLE_UNPARSED:" + region.role());
        }
        // A later correction may add the same domain outside an earlier section. Read the
        // complete remaining paragraph, including pronouns/exception continuations, rather
        // than counting agreement inside a now-stale section boundary.
        var remaining = new StringBuilder(body);
        covered.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (var range : covered) remaining.replace(range[0], range[1], "¶");
        for (String paragraph : remaining.toString().split("(?=[•▪※￭])|¶")) {
            if (paragraph.isEmpty()) continue;
            boolean known = PRIMARY.outsideClauses().stream().anyMatch(syntax -> primarySyntax(syntax, primary).matcher(paragraph).matches());
            if (!known) blockers.add("SUPPLEMENTARY_PRIMARY_OUTSIDE_CLAUSE_UNPARSED");
        }
        return List.copyOf(blockers);
    }

    private static Pattern primarySyntax(String syntax, Identity primary) {
        String scoped = syntax.replace("{{site}}", primary.site());
        String expression = Arrays.stream(scoped.split(Pattern.quote("{{publication}}"), -1)).map(Pattern::quote)
            .collect(java.util.stream.Collectors.joining(Pattern.quote(primary.publication().toString().replace('-', '.')) + "[.]?"));
        return Pattern.compile(expression);
    }

    private static PrimaryGrammar primaryGrammar() {
        try (var in = SupplementaryRules.class.getResourceAsStream("/review/primary-bundle-syntax.json")) {
            if (in == null) throw new IllegalStateException("Missing primary bundle grammar");
            return new ObjectMapper().readValue(in, PrimaryGrammar.class);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static Reading readForms(Source source, Source primary, LocalDate publication) {
        var blocks = VerifiedPreparationForms.numberedBlocks(source);
        if (blocks.isEmpty()) return new Reading(List.of(), List.of("SUPPLEMENTARY_FORM_STRUCTURE_UNVERIFIED"));
        var rules = new ArrayList<FormRule>();
        var blockers = new LinkedHashSet<String>();
        var recruitment = identity(primary);
        if (recruitment == null || !recruitment.publication().equals(publication))
            return new Reading(List.of(), List.of("SUPPLEMENTARY_PRIMARY_IDENTITY_UNREAD"));
        String title = recruitment.title(), site = recruitment.site();
        var structure = structure(source);
        for (var block : blocks) {
            var schema = SCHEMAS.stream().filter(s -> s.title().equals(compact(block.title()))
                && s.recipient().equals(compact(block.recipient()))).findFirst();
            if (schema.isEmpty()) {
                blockers.add("SUPPLEMENTARY_FORM_BUNDLE_UNSUPPORTED:" + block.number());
                continue;
            }
            var syntax = schema.get();
            if (!syntax.tables().equals(structure.layouts().get(block.number()))) {
                blockers.add("SUPPLEMENTARY_FORM_TABLE_SCOPE_UNVERIFIED:" + block.number());
                continue;
            }
            var actualNodes = structure.nodes().get(block.number());
            if (actualNodes == null || actualNodes.size() != syntax.nodes().size()) {
                blockers.add("SUPPLEMENTARY_FORM_BUNDLE_UNPARSED:" + block.number());
                continue;
            }
            var years = new ArrayList<Integer>();
            var offices = new ArrayList<String>();
            boolean identity = true, recognized = true;
            for (int n = 0; n < actualNodes.size(); n++) {
                Node expected = syntax.nodes().get(n), actual = actualNodes.get(n);
                if (!expected.cells().equals(actual.cells())) {
                    recognized = false;
                    break;
                }
                var pattern = new StringBuilder();
                var slots = new ArrayList<String>();
                var matcher = SLOT.matcher(expected.syntax());
                int end = 0;
                while (matcher.find()) {
                    pattern.append(Pattern.quote(expected.syntax().substring(end, matcher.start())));
                    String slot = matcher.group(1);
                    slots.add(slot);
                    pattern.append(switch (slot) {
                        case "vehicleAmount" -> "([0-9]+(?:,[0-9]{3})*)";
                        case "returnMonths" -> "([1-9][0-9]?)";
                        case "complex" -> "([가-힣A-Za-z0-9·,._()\\-]{1,150})";
                        case "number" -> "([0-9]{1,2}(?:-[0-9]{1,2})?)";
                        case "publication" -> "((?:20)?[0-9]{2}[.][0-9]{1,2}[.][0-9]{1,2}[.]?)";
                        case "signYear" -> "(20[0-9]{2})";
                        case "office" -> "([가-힣]{2,12})";
                        case "site", "recruitment" -> "([가-힣A-Za-z0-9·,._()\\-]{1,150})";
                        default -> throw new IllegalStateException("Unknown form grammar slot: " + slot);
                    });
                    end = matcher.end();
                }
                pattern.append(Pattern.quote(expected.syntax().substring(end)));
                var match = Pattern.compile(pattern.toString()).matcher(actual.syntax());
                if (!match.matches()) {
                    recognized = false;
                    break;
                }
                for (int i = 0; i < slots.size(); i++) {
                    String value = match.group(i + 1);
                    switch (slots.get(i)) {
                        case "complex" ->
                            identity &= SupplyInventory.combinations(primary).keySet().stream().filter(c -> c.endsWith(value)).count() == 1;
                        case "vehicleAmount",
                             "returnMonths" -> { /* Captured from the full clause by the typed action below. */ }
                        case "number" -> identity &= value.equals(block.number());
                        case "publication" -> {
                            String[] fields = value.split("[.]");
                            try {
                                int year = Integer.parseInt(fields[0]);
                                if (year < 100) year += 2000;
                                identity &= publication != null && publication.equals(LocalDate.of(year, Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
                            } catch (RuntimeException invalid) {
                                identity = false;
                            }
                        }
                        case "signYear" -> years.add(Integer.parseInt(value));
                        case "office" -> offices.add(value);
                        case "site" -> identity &= !site.isEmpty() && value.equals(site);
                        case "recruitment" -> identity &= listPunctuation(title).equals(listPunctuation(value + "모집"));
                        default -> throw new IllegalStateException("Unbound form grammar slot");
                    }
                }
            }
            if (!recognized) {
                blockers.add("SUPPLEMENTARY_FORM_BUNDLE_UNPARSED:" + block.number());
                continue;
            }
            if (!identity) {
                blockers.add("SUPPLEMENTARY_FORM_RECRUITMENT_MISMATCH:" + block.number());
                continue;
            }
            var claims = actions(syntax.role(), block.text());
            rules.add(new FormRule(syntax.role(), claims, block, List.copyOf(years), List.copyOf(offices)));
        }
        return new Reading(List.copyOf(rules), List.copyOf(blockers));
    }

    private static List<Claim> actions(Role role, String source) {
        String text = compact(source);
        var result = new ArrayList<Claim>();
        switch (role) {
            case RENEWAL_REFERENCE -> {
                result.add(claim(Stage.RENEWAL, "입주자", Topic.RENEWAL, Effect.REFERENCE_TO_NOTICE_AND_LAW, Condition.NOTICE_AND_LAW));
                result.add(claim(Stage.RENEWAL, "입주자", Topic.RENEWAL, Effect.VACATE_IF_NOT_RENEWABLE, Condition.NOTICE_AND_LAW));
                result.add(claim(Stage.RENEWAL, "입주자", Topic.RENEWAL, Effect.PREMIUM_MAY_APPLY, Condition.STAY_PERMITTED_AND_INCOME_ASSETS_EXCEEDED, Condition.NOTICE_AND_LAW));
                result.add(claim(Stage.RENEWAL, "입주자", Topic.RENEWAL, Effect.RENEWAL_MAY_END, Condition.ACQUIRES_HOME));
            }
            case WAITLIST -> {
                result.add(claim(Stage.SELECTION, "공공주택사업자의 명부 관리", Topic.WAITLIST, Effect.NO_DUPLICATE_SAME_TYPE));
                result.add(claim(Stage.ENTRY, "예비입주자", Topic.WAITLIST, Effect.REMOVE_ALL_CONSTRUCTION_WAITLISTS, Condition.ACTUAL_ENTRY_OR_FULL_DEPOSIT_AFTER_DESIGNATED_PERIOD));
                result.add(claim(Stage.CONTRACT, "예비입주자", Topic.WAITLIST, Effect.EXCLUDE_NOTICE_BEFORE_CONTRACT));
            }
            case POST_VERIFICATION -> {
                result.add(claim(Stage.CONTRACT, "계약자", Topic.DEPOSIT, Effect.CANCEL_WITHOUT_PENALTY_OR_INTEREST, Condition.DISQUALIFIED));
                result.add(claim(Stage.CONTRACT, "계약자", Topic.DEPOSIT, Effect.MISSING_PROOF_PENALTY, Condition.NOT_QUALIFICATION_DEFECT, Condition.PROOFS_MISSING));
                result.add(claim(Stage.CONTRACT, "계약자", Topic.DEPOSIT, Effect.WITHDRAWAL_PENALTY, Condition.NOT_QUALIFICATION_DEFECT, Condition.VOLUNTARY_WITHDRAWAL));
                result.add(claim(Stage.PRE_ENTRY, "계약자", Topic.MOVE_PLAN, Effect.PLAN_AFTER_FINAL_APPROVAL, Condition.FINAL_ENTRY_APPROVAL));
                result.add(claim(Stage.PRE_ENTRY, "계약자", Topic.MOVE_PLAN, Effect.EARLY_MOVE_LOSS_CLAIM_RESTRICTION, Condition.BEFORE_FINAL_ENTRY_APPROVAL));
            }
            case CONSENT -> {
                result.add(claim(Stage.DATA_PROCESSING, "동의서 기재자", Topic.PRIVACY_BASIC, Effect.MANDATORY_CONSENT, Condition.DECLINES_REQUIRED_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의서 기재자", Topic.PRIVACY_THIRD_PARTY, Effect.MANDATORY_CONSENT, Condition.DECLINES_REQUIRED_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의 거절자", Topic.PRIVACY_BASIC, Effect.APPLICATION_OR_CONTRACT_MAY_BE_REFUSED, Condition.DECLINES_REQUIRED_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의 거절자", Topic.PRIVACY_THIRD_PARTY, Effect.APPLICATION_OR_CONTRACT_MAY_BE_REFUSED, Condition.DECLINES_REQUIRED_CONSENT));
                String age = quantity(text, "만([0-9]+)세미만의세대원은보호자");
                result.add(new Claim(new Scope(Stage.DATA_PROCESSING, "동의서 기재자", List.of(), Set.of(Condition.FORM_LISTED_SIGNERS)),
                    Topic.PRIVACY_BASIC, Effect.GUARDIAN_SIGNATURE, Map.of("belowAge", age)));
                result.add(claim(Stage.DATA_PROCESSING, "신청자와 해당 세대원", Topic.PRIVACY_IDENTIFIER, Effect.COLLECT_IDENTIFIER_UNDER_LAW));
                result.add(claim(Stage.DATA_PROCESSING, "동의서 기재자", Topic.PRIVACY_SENSITIVE, Effect.OPTIONAL_SENSITIVE_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의 거절자", Topic.PRIVACY_SENSITIVE, Effect.DISABILITY_PRIORITY_SCORE_FACILITY_EXCLUDED, Condition.DECLINES_SENSITIVE_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의서 기재자", Topic.PRIVACY_MARKETING, Effect.OPTIONAL_MARKETING_CONSENT));
                result.add(claim(Stage.DATA_PROCESSING, "동의 거절자", Topic.PRIVACY_MARKETING, Effect.MARKETING_REFUSAL_NO_HOUSING_SERVICE_LIMIT, Condition.DECLINES_MARKETING_CONSENT));
                result.add(new Claim(new Scope(Stage.DATA_PROCESSING, "수집 개인정보", List.of(), Set.of(Condition.LEGAL_RETENTION_EXCEPTION)),
                    Topic.PRIVACY_DELETION, Effect.DELETE_WITH_LEGAL_RETENTION_EXCEPTION, Map.of("days", quantity(text, "지체없이\\(([0-9]+)일이내\\)"))));
                retention(result, Topic.PRIVACY_BASIC, section(text, "2.개인정보수집·이용안내", "※주민(외국인)등록번호수집·이용안내"), false);
                retention(result, Topic.PRIVACY_IDENTIFIER, section(text, "※주민(외국인)등록번호수집·이용안내", "※개인정보의파기절차및방법"), false);
                retention(result, Topic.PRIVACY_SENSITIVE, section(text, "3.민감정보수집·이용안내", "4.개인정보의제3자제공동의여부"), true);
                retention(result, Topic.PRIVACY_THIRD_PARTY, section(text, "4.개인정보의제3자제공동의여부", "5.공공주택관련정보제공을위한개인정보수집·이용안내"), false);
                retention(result, Topic.PRIVACY_MARKETING, text.substring(text.indexOf("5.공공주택관련정보제공을위한개인정보수집·이용안내")), false);
            }
            case APPLICATION_FORM ->
                result.add(new Claim(new Scope(Stage.APPLICATION, "현장 신청자의 서약", List.of(), Set.of(Condition.VISIT_APPLICATION)), Topic.FORM_DECLARATION,
                    Effect.DECLARATION_SCOPE_REQUIRES_COMPARISON, Map.of("vehicleAmount", quantity(text, "차량가액\\(([0-9,]+)만원이하"))));
            case RETURN_STUDY -> {
                var scope = new Scope(Stage.ENTRY, "복학 예정 대학생", List.of("STUDENT"), Set.of(Condition.RETURNING_STUDENT));
                result.add(new Claim(scope, Topic.RETURN_STUDY, Effect.RETURN_BY_NEXT_SEMESTER, Map.of()));
                result.add(new Claim(scope, Topic.RETURN_STUDY, Effect.RETURN_PROOF_WITHIN_MONTHS, Map.of("months", quantity(text, "복학후([0-9]+)개월이내"))));
                result.add(new Claim(scope, Topic.RETURN_STUDY, Effect.CANCEL_IF_RETURN_PROOF_MISSING, Map.of()));
            }
            case PROXY_AUTHORITY -> {
                result.add(claim(Stage.APPLICATION, "위임인과 수임인", Topic.PROXY, Effect.AUTHORIZE_PROXY, Condition.USES_THIS_PROXY_FORM));
                result.add(claim(Stage.CONTRACT, "위임인과 수임인", Topic.PROXY, Effect.AUTHORIZE_PROXY, Condition.USES_THIS_PROXY_FORM));
                result.add(claim(Stage.APPLICATION, "위임인과 수임인", Topic.PROXY, Effect.PROXY_PROOFS, Condition.USES_THIS_PROXY_FORM));
                result.add(claim(Stage.CONTRACT, "위임인과 수임인", Topic.PROXY, Effect.PROXY_PROOFS, Condition.USES_THIS_PROXY_FORM));
                result.add(claim(Stage.APPLICATION, "위임인과 수임인", Topic.PROXY, Effect.PROXY_PARTIES_RESOLVE_DISPUTES, Condition.USES_THIS_PROXY_FORM));
                result.add(claim(Stage.CONTRACT, "위임인과 수임인", Topic.PROXY, Effect.PROXY_PARTIES_RESOLVE_DISPUTES, Condition.USES_THIS_PROXY_FORM));
            }
            case ACCESSIBILITY_REQUEST -> {
                result.add(claim(Stage.ENTRY, "최초 입주자 본인 또는 부양가족", Topic.ACCESSIBILITY, Effect.ACCESSIBILITY_ON_REQUEST,
                    Condition.INITIAL_TENANT_SELF_OR_DEPENDENT_VULNERABLE_AND_HEAD_REQUESTS));
                result.add(new Claim(new Scope(Stage.ENTRY, "편의시설 제출서류", List.of(), Set.of(Condition.ACCESSIBILITY_PROOF_ONLY)),
                    Topic.ACCESSIBILITY, Effect.MASK_RESIDENT_ID_SUFFIX, Map.of("digits", quantity(text, "주민번호뒤([0-9]+)자리는삭제"))));
            }
        }
        return List.copyOf(result);
    }

    private static Claim claim(Stage stage, String subject, Topic topic, Effect effect, Condition... conditions) {
        return new Claim(new Scope(stage, subject, List.of(), Set.of(conditions)), topic, effect, Map.of());
    }

    private static String quantity(String source, String pattern) {
        var match = Pattern.compile(pattern).matcher(source);
        if (!match.find()) throw new IllegalStateException("Registered form grammar has an unbound semantic operand");
        return match.group(1);
    }

    private static String section(String text, String begin, String end) {
        int first = text.indexOf(begin), last = text.indexOf(end, first + begin.length());
        if (first < 0 || last < 0) throw new IllegalStateException("Registered form grammar has an unbound purpose");
        return text.substring(first, last);
    }

    private static void retention(List<Claim> claims, Topic topic, String text, boolean permanentContractor) {
        var values = new LinkedHashMap<String, String>();
        values.put("applicantMonths", quantity(text, "(?:신청자는|청약신청일로부터)([0-9]+)개월"));
        values.put("selectedYears", quantity(text, "당첨\\(예비\\)자는([0-9]+)년"));
        if (permanentContractor) values.put("contractor", "영구");
        claims.add(new Claim(new Scope(Stage.DATA_PROCESSING, "해당 목적의 정보주체", List.of(), Set.of()), topic, Effect.RETENTION_PERIOD, Map.copyOf(values)));
    }

    /**
     * Coordinates are attached to the original cell order, so reassigning a cell changes its meaning.
     */
    static Structure structure(Source source) {
        var bodies = new ArrayList<String>();
        var offsets = new ArrayList<Integer>();
        int offset = 0;
        for (var part : source.context()) {
            offsets.add(offset);
            bodies.add(part.text());
            offset += part.text().length() + 1;
        }
        String joined = String.join("\n", bodies);
        var headings = Pattern.compile("(?m)^\\s*\\[(?:붙임|양식)\\s*([0-9]{1,2}(?:-[0-9]{1,2})?)]").matcher(joined);
        var starts = new ArrayList<Integer>();
        var numbers = new ArrayList<String>();
        var result = new LinkedHashMap<String, List<Layout>>();
        var localIndices = new HashMap<Integer, Integer>();
        var tableOwners = new HashMap<String, Map<Integer, Integer>>();
        var nodes = new LinkedHashMap<String, List<Node>>();
        while (headings.find()) {
            starts.add(headings.start());
            numbers.add(headings.group(1));
            result.putIfAbsent(headings.group(1), new ArrayList<>());
            nodes.putIfAbsent(headings.group(1), new ArrayList<>());
        }
        for (int p = 0; p < source.context().size(); p++) {
            var reading = DocumentContextReader.read(source, source.context().get(p));
            if (!reading.valid()) return new Structure(Map.of(), Map.of());
            var tables = reading.tables().stream().sorted(Comparator.comparingInt(DocumentContextReader.Table::start)).toList();
            for (int t = 0; t < tables.size(); t++) {
                var table = tables.get(t);
                int start = offsets.get(p) + table.start(), end = offsets.get(p) + table.end();
                int h = -1;
                for (int i = 0; i < starts.size() && starts.get(i) <= start; i++) h = i;
                var contained = new LinkedHashSet<String>();
                for (int i = 0; i < starts.size(); i++)
                    if (starts.get(i) >= start && starts.get(i) < end) contained.add(numbers.get(i));
                if (contained.size() > 1 || contained.isEmpty() && h < 0 || table.grid() == null)
                    return new Structure(Map.of(), Map.of());
                String number = contained.isEmpty() ? numbers.get(h) : contained.iterator().next();
                int parent = -1;
                for (int k = 0; k < t; k++)
                    if (tables.get(k).start() < table.start() && tables.get(k).end() > table.end())
                        parent = localIndices.getOrDefault(offsets.get(p) + tables.get(k).start(), -1);
                var positions = table.cells().stream().sorted(Comparator.comparingInt(DocumentContextReader.BoundedCell::start))
                    .map(c -> new Position(c.position().row(), c.position().column(), c.position().rowSpan(), c.position().columnSpan())).toList();
                localIndices.put(start, result.get(number).size());
                tableOwners.computeIfAbsent(p + ":" + number, ignored -> new HashMap<>()).put(table.id(), result.get(number).size());
                result.get(number).add(new Layout(table.rows(), table.columns(), parent, positions));
            }
            for (var segment : reading.segments()) {
                String value = compact(reading.text(source.context().get(p).text(), segment.start(), segment.end()));
                if (value.isEmpty()) continue;
                int start = offsets.get(p) + segment.start(), end = offsets.get(p) + segment.end();
                int h = -1;
                for (int i = 0; i < starts.size() && starts.get(i) <= start; i++) h = i;
                var contained = new LinkedHashSet<String>();
                for (int i = 0; i < starts.size(); i++)
                    if (starts.get(i) >= start && starts.get(i) < end) contained.add(numbers.get(i));
                if (contained.size() > 1 || contained.isEmpty() && h < 0) return new Structure(Map.of(), Map.of());
                String number = contained.isEmpty() ? numbers.get(h) : contained.iterator().next();
                var path = new ArrayList<CellPath>();
                if (segment.context() != null) for (var cell : segment.context().cells()) {
                    Integer table = tableOwners.getOrDefault(p + ":" + number, Map.of()).get(cell.table());
                    if (table == null || !cell.known()) return new Structure(Map.of(), Map.of());
                    path.add(new CellPath(table, new Position(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan())));
                }
                nodes.get(number).add(new Node(List.copyOf(path), value));
            }
        }
        return new Structure(result, nodes);
    }

    static String text(Source source) {
        var result = new StringBuilder();
        for (var part : source.context()) {
            var reading = DocumentContextReader.read(source, part);
            if (!reading.valid()) return "";
            result.append(reading.text(part.text(), 0, part.text().length()));
        }
        return compact(result.toString());
    }

    static String compact(String value) {
        return value.replaceAll("[\\s\\p{Zs}]+", "");
    }

    private static String listPunctuation(String value) {
        return value.replaceAll("(?<=[0-9]),(?=[0-9](?![0-9]))", "·");
    }

    private static List<Schema> schemas() {
        try (var in = SupplementaryRules.class.getResourceAsStream("/review/form-bundle-syntax.json")) {
            if (in == null) throw new IllegalStateException("Missing form bundle grammar");
            return new ObjectMapper().readValue(in, Schemas.class).schemas();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
