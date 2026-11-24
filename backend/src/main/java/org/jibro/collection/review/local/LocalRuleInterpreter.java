package org.jibro.collection.review.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;

import static org.jibro.collection.review.local.LocalRuleBook.*;

/**
 * Reads scoped literal facts locally and retains the original spans needed to review the remainder.
 */
public final class LocalRuleInterpreter {
    public static final String POLICY = "local-rules-v2-hwpx-context";
    private static final int MAX_BLOCK = 2400;
    private static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final Pattern LINE = Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern BOUNDARY = Pattern.compile("^\\s*(?:[■□◆▪●•①-⑳]|[0-9]{1,2}\\.\\s|[가-하][.)]\\s|[-*※]\\s)");
    private static final Pattern HEADING = Pattern.compile("^\\s*(?:[■□◆]\\s*|\\d{1,2}\\.\\s*)(.{2,70})\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*(\\d{1,2})\\.\\s*(.+)$");
    private static final Pattern PAGE = Pattern.compile("(?:공고문\\s*)?(\\d{1,3})\\s*(?:쪽|[Pp](?![A-Za-z]))");
    private static final Pattern REFERENCE_SECTION = Pattern.compile("(\\d{1,2})\\s*\\.\\s*(?:신청자격|신청\\s*자격|모집일정|모집\\s*일정|제출서류|제출\\s*서류|선정방법|선정\\s*방법).{0,20}?(?:참조|확인)");
    private static final Pattern APPENDIX = Pattern.compile("(?:별표|별지)(?:\\s*(?:제\\s*)?[0-9]+(?:\\s*호)?(?:\\s*의\\s*[0-9]+)?)?");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*([%％]|퍼센트|억\\s*원|만\\s*원|천\\s*원|원|세|년|개월|㎡|제곱미터|명|인|대)(?:\\s*(이상|이하|미만|초과|이내))?");
    private static final Pattern SUBJECT = Pattern.compile("세대구성원(?:\\s*전원)?|무주택세대구성원|신청자(?:\\s*부모와\\s*본인)?|본인|배우자|부\\s*또는\\s*모|부모|대학생|취업준비생|미성년\\s*세대주|미성년자|성년자");
    private static final Pattern SCOPE = Pattern.compile("(?:[0-9]+\\s*(?:㎡|제곱미터)\\s*(?:미만|이상|이하|초과))|(?:[0-9]+\\s*인\\s*(?:가구|인 경우))|세대구성원\\s*전원|개별\\s*자동차|비영업용\\s*승용차|(?:거주지역|순위|배점).{0,30}(?:관계없이|무관)");
    private static final Map<Topic, Pattern> TOPICS = topics();

    private LocalRuleInterpreter() {
    }

    private record Span(int start, int end) {
    }

    private record Pending(Clause clause, boolean heading, boolean continuation,
                           DocumentContextReader.Context context) {
    }

    public static LocalRuleBook interpret(List<Source> sources) {
        var pending = new ArrayList<Pending>();
        var issues = new ArrayList<Unresolved>();
        int characters = 0;
        if (sources == null || sources.isEmpty())
            return new LocalRuleBook(POLICY, List.of(), List.of(), List.of(new Unresolved("SOURCE_MISSING", List.of())), 0);
        for (Source source : sources) {
            if (org.jibro.collection.extraction.OcrProvenance.contains(source)) {
                issues.add(new Unresolved(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED, List.of()));
                continue;
            }
            if (source == null || !"EXTRACTED".equals(source.status()) || source.sha256() == null || source.context().isEmpty()) {
                issues.add(new Unresolved("SOURCE_UNREADABLE", List.of()));
                continue;
            }
            long length = source.context().stream().filter(Objects::nonNull).filter(p -> p.text() != null).mapToLong(p -> p.text().length()).sum();
            if (length > MAX_SOURCE_CHARS || source.context().size() > 1000) {
                issues.add(new Unresolved("SOURCE_LIMIT", List.of()));
                continue;
            }
            String heading = "";
            for (Part part : source.context()) {
                if (part == null || part.location() == null || part.text() == null || part.text().isBlank()) {
                    issues.add(new Unresolved("SOURCE_CONTEXT_MISSING", List.of()));
                    continue;
                }
                characters += part.text().length();
                var reading = DocumentContextReader.read(source, part);
                if (!reading.valid()) {
                    issues.add(new Unresolved("DOCUMENT_STRUCTURE_INCOMPLETE", List.of()));
                    continue;
                }
                for (var segment : reading.segments()) {
                    if (segment.context() != null && segment.context().regions().contains("MEMO")) continue;
                    int lastEnd = -1;
                    for (Span relative : spans(part.text().substring(segment.start(), segment.end()))) {
                        Span span = new Span(segment.start() + relative.start(), segment.start() + relative.end());
                        String quote = part.text().substring(span.start(), span.end());
                        String firstLine = quote.lines().filter(s -> !s.isBlank()).findFirst().orElse("").strip();
                        boolean isHeading = isHeading(firstLine);
                        if (isHeading) heading = firstLine;
                        var classified = classify(quote);
                        if (classified.equals(List.of(Topic.OTHER)) && !heading.isBlank()) {
                            var inherited = classify(heading);
                            if (!inherited.equals(List.of(Topic.OTHER))) classified = inherited;
                        }
                        boolean continuation = lastEnd >= 0 && !BOUNDARY.matcher(firstLine).find()
                                && !part.text().substring(lastEnd, span.start()).contains("\n\n");
                        String id = id("clause", source.url() + "\n" + source.sha256() + "\n" + part.location() + "\n" + span.start() + ":" + span.end() + "\n" + quote);
                        String contextHeading = segment.context() == null ? "" : segment.context().description();
                        String annotatedHeading = contextHeading.isBlank() ? heading : heading.isBlank() ? contextHeading : heading + " · " + contextHeading;
                        pending.add(new Pending(new Clause(id, source.url(), source.sha256(), part.location(), span.start(), span.end(), quote, annotatedHeading,
                                classified, matches(SUBJECT, quote), matches(SCOPE, quote), markers(quote), quantities(quote, span.start()), references(quote), List.of()), isHeading, continuation, segment.context()));
                        lastEnd = span.end();
                    }
                }
            }
        }
        var clauses = relate(pending, issues);
        var facts = new ArrayList<Fact>();
        for (Clause clause : clauses) interpretClause(clause, clauses, facts);
        var factClauses = new HashSet<String>();
        facts.forEach(f -> factClauses.addAll(f.clauseIds()));
        for (Clause clause : clauses) {
            // A recognized operand cannot certify the remainder of a paragraph containing it.
            if (relevant(clause) && !fullyRecognized(clause)) {
                String code = factClauses.contains(clause.id()) ? "RULE_COMPLETENESS_REVIEW"
                        : clause.markers().contains(Marker.EXCEPTION) ? "EXCEPTION_SCOPE_UNRESOLVED"
                        : !clause.quantities().isEmpty() ? "NUMERIC_SCOPE_UNRESOLVED" : "LOCAL_SCOPE_UNRESOLVED";
                issues.add(new Unresolved(code, List.of(clause.id())));
            }
            if (clause.markers().contains(Marker.EXCEPTION) && clause.relatedClauseIds().isEmpty())
                issues.add(new Unresolved("EXCEPTION_PARENT_UNRESOLVED", List.of(clause.id())));
        }
        return new LocalRuleBook(POLICY, clauses, facts, distinct(issues), characters);
    }

    private static List<Span> spans(String text) {
        var result = new ArrayList<Span>();
        var line = LINE.matcher(text);
        int start = -1, end = -1;
        while (line.find()) {
            if (line.start() == line.end()) break;
            String value = line.group();
            if (value.isBlank()) {
                if (start >= 0) result.add(new Span(start, end));
                start = -1;
                end = -1;
                continue;
            }
            if (start >= 0 && (BOUNDARY.matcher(value).find() || line.end() - start > MAX_BLOCK)) {
                result.add(new Span(start, end));
                start = -1;
            }
            if (start < 0) start = line.start();
            end = line.end();
        }
        if (start >= 0) result.add(new Span(start, end));
        return result;
    }

    private static boolean isHeading(String line) {
        return HEADING.matcher(line).matches() && !line.matches(".*(?:합니다|됩니다|습니다|있습니다|없습니다)[.!]$");
    }

    private static List<Clause> relate(List<Pending> pending, List<Unresolved> issues) {
        var related = new LinkedHashMap<String, LinkedHashSet<String>>();
        pending.forEach(p -> related.put(p.clause().id(), new LinkedHashSet<>()));
        var pageIndex = new HashMap<String, List<String>>();
        var sectionIndex = new HashMap<String, List<String>>();
        String previousUrl = null, parentHeading = null, precedingRule = null;
        Clause previous = null;
        DocumentContextReader.Context previousContext = null;
        for (Pending item : pending) {
            Clause c = item.clause();
            String sourceKey = c.sourceUrl() + "\n" + c.sourceSha256();
            if (!Objects.equals(previousUrl, sourceKey) || !DocumentContextReader.sameFlow(previousContext, item.context())
                    || item.context() != null && previous != null && !previous.location().equals(c.location())) {
                parentHeading = null;
                precedingRule = null;
                previous = null;
            }
            var page = PAGE.matcher(c.location());
            if (page.find())
                pageIndex.computeIfAbsent(sourceKey + ":page:" + page.group(1), ignored -> new ArrayList<>()).add(c.id());
            var section = SECTION.matcher(c.heading());
            if (section.matches())
                sectionIndex.computeIfAbsent(sourceKey + ":section:" + section.group(1), ignored -> new ArrayList<>()).add(c.id());
            if (item.heading()) {
                parentHeading = c.id();
                precedingRule = c.id();
            } else if (parentHeading != null && relevant(c)) related.get(c.id()).add(parentHeading);
            if (previous != null && (item.continuation() || c.markers().contains(Marker.EXCEPTION)
                    || previous.markers().contains(Marker.ANY_OF) && c.quote().strip().startsWith("-"))) {
                related.get(c.id()).add(previous.id());
                related.get(previous.id()).add(c.id());
            }
            if (c.markers().contains(Marker.EXCEPTION) && precedingRule != null && !precedingRule.equals(c.id())) {
                related.get(c.id()).add(precedingRule);
                related.get(precedingRule).add(c.id());
            } else if (relevant(c) && !c.markers().contains(Marker.EXCEPTION)) precedingRule = c.id();
            previousUrl = sourceKey;
            previous = c;
            previousContext = item.context();
        }
        var structural = DocumentContextReader.relate(pending.stream().map(item -> {
            Clause clause = item.clause();
            return new DocumentContextReader.Occurrence(clause.id(), clause.sourceUrl() + "\n" + clause.sourceSha256() + "\n" + clause.location(), item.context());
        }).toList());
        structural.related().forEach((id, links) -> related.get(id).addAll(links));
        structural.incomplete().forEach(id -> issues.add(new Unresolved("DOCUMENT_TABLE_CONTEXT_INCOMPLETE", List.of(id))));
        for (Pending item : pending) {
            var c = item.clause();
            for (String ref : c.references()) {
                String key = c.sourceUrl() + "\n" + c.sourceSha256() + ":" + ref;
                List<String> targets = ref.startsWith("page:") ? pageIndex.get(key) : sectionIndex.get(key);
                if (targets == null || targets.isEmpty())
                    issues.add(new Unresolved("REFERENCE_TARGET_MISSING", List.of(c.id())));
                else for (String target : targets) if (!target.equals(c.id())) related.get(c.id()).add(target);
            }
        }
        return pending.stream().map(p -> {
            var c = p.clause();
            return new Clause(c.id(), c.sourceUrl(), c.sourceSha256(), c.location(), c.start(), c.end(), c.quote(), c.heading(), c.topics(), c.subjects(),
                    c.scopes(), c.markers(), c.quantities(), c.references(), List.copyOf(related.get(c.id())));
        }).toList();
    }

    private static void interpretClause(Clause c, List<Clause> all, List<Fact> facts) {
        String t = compact(c.quote());
        boolean renewal = t.contains("재계약") || t.contains("갱신계약");
        if (!renewal && Pattern.compile("소득요건(?:은)?배제(?:합니다|됨|함)?(?:(?![가-힣])|(?=다만|단,))").matcher(t).find())
            add(facts, c, "INCOME_REQUIREMENT_WAIVED", Topic.INCOME, "해당 조항 적용 대상", Map.of("requirement", "income", "effect", "waived", "scope", "clauseOnly"));
        if (!renewal && Pattern.compile("총자산요건(?:은)?배제(?:합니다|됨|함)?(?:(?![가-힣])|(?=다만|단,))").matcher(t).find())
            add(facts, c, "TOTAL_ASSET_REQUIREMENT_WAIVED", Topic.ASSETS, "해당 조항 적용 대상", Map.of("requirement", "totalAssets", "effect", "waived", "scope", "clauseOnly"));
        if (t.contains("자동차") && Pattern.compile("완화요건미적용(?!하지|할수없|대상이아니)").matcher(t).find())
            add(facts, c, "VEHICLE_NOT_WAIVED", Topic.VEHICLE, subject(c), Map.of("requirement", "vehicle", "effect", "notWaived"));
        var vehicle = Pattern.compile("개별자동차가액[()]*([0-9,]+)[()]*만원이하").matcher(t);
        if (vehicle.find() && t.contains("세대구성원전원"))
            add(facts, c, "HOUSEHOLD_INDIVIDUAL_VEHICLE_LIMIT", Topic.VEHICLE, "세대구성원 전원", Map.of("amount", vehicle.group(1).replace(",", ""), "unit", "만원", "comparison", "LTE", "valuation", "individualVehicle", "exceptions", "separateEvidenceRequired"));
        if (Pattern.compile("비영업용승용(?:자동차|차)에한하여산출|시행규칙.{0,30}정한비영업용승용(?:자동차|차)").matcher(t).find())
            add(facts, c, "VEHICLE_CLASS_SCOPE", Topic.VEHICLE, subject(c), Map.of("vehicleClass", "nonCommercialPassenger"));
        if (inSentence(t, s -> s.contains("자동차")
                && Pattern.compile("2대이상.{0,180}(?:높은|최고)|(?:높은|최고).{0,180}2대이상").matcher(s).find()
                && assertedPredicate(s, "(?:적용|산정|반영|산출)")))
            add(facts, c, "VEHICLE_MAXIMUM_VALUATION", Topic.VEHICLE, subject(c), Map.of("aggregation", "maximum", "minimumCount", "2"));
        if (inSentence(t, s -> s.contains("저공해") && s.contains("보조금")
                && (assertedPredicate(s, "(?:제외|공제|차감)") || Pattern.compile("(?:제외|공제|차감)한금액").matcher(s).find())))
            add(facts, c, "VEHICLE_SUBSIDY_DEDUCTION", Topic.VEHICLE, subject(c), Map.of("deduction", "governmentSubsidy", "appliesTo", "lowEmissionVehicle"));
        if (inSentence(t, s -> s.contains("자동차") && s.contains("장애인")
                && (assertedPredicate(s, "(?:제외|미포함)") || Pattern.compile("포함하지않(?:습니다|는다|음)(?=$|[.,;:)])").matcher(s).find())))
            add(facts, c, "VEHICLE_DISABILITY_EXCLUSION", Topic.VEHICLE, subject(c), Map.of("effect", "exclusion", "definition", "sourceDefinedVehicleOnly"));

        var age = Pattern.compile("미성년자[（(]?(?:만)?([0-9]{1,3})세미만[）)]?.{0,40}(?:신청할수없|신청불가)").matcher(t);
        if (age.find()) {
            var support = new LinkedHashSet<String>();
            support.add(c.id());
            c.relatedClauseIds().stream().filter(id -> all.stream().anyMatch(other -> other.id().equals(id) && (other.markers().contains(Marker.EXCEPTION) || other.quote().contains("미성년")))).forEach(support::add);
            facts.add(new Fact(id("fact", c.id() + "ADULT_WITH_EXCEPTIONS"), "ADULT_AGE_RESTRICTION", Topic.APPLICANT, "신청자",
                    Map.of("minimumAge", age.group(1), "unit", "세", "exceptions", "sourceDefinedMinorExceptions", "finalDecision", "notEvaluated"), List.copyOf(support)));
        }
        var range = Pattern.compile("(?:만)?([0-9]{1,3})세이상([0-9]{1,3})세이하").matcher(t);
        if (range.find() && Integer.parseInt(range.group(1)) <= Integer.parseInt(range.group(2)))
            add(facts, c, "EXPLICIT_AGE_RANGE", Topic.APPLICANT, subject(c), Map.of("min", range.group(1), "max", range.group(2), "unit", "세"));
        var transfer = Pattern.compile("불법양도[·ㆍ/]?전대.{0,80}?([0-9]+)년이경과되지.{0,100}?(?:선정불가|신청불가)").matcher(t);
        if (transfer.find() && (t.contains("세대구성원") || c.subjects().contains("신청자")))
            add(facts, c, "UNLAWFUL_TRANSFER_RESTRICTION", Topic.APPLICANT, t.contains("세대구성원") ? "신청자의 세대구성원" : "신청자",
                    Map.of("years", transfer.group(1), "comparison", "notElapsed", "effect", "selectionExcluded"));
        if (assertedPredicate(t, "혼인중이아닐것") || assertedPredicate(t, "신청자는미혼이어야"))
            add(facts, c, "UNMARRIED_REQUIREMENT", Topic.APPLICANT, subject(c), Map.of("maritalStatus", "unmarried"));
        if (inSentence(t, s -> s.contains("수료") && s.contains("졸업") && assertedPredicate(s, "재학생으로간주")))
            add(facts, c, "STUDENT_ENROLMENT_EQUIVALENCE", Topic.APPLICANT, "수료 후 아직 졸업하지 않은 사람", Map.of("treatAs", "enrolledStudent", "scope", "educationStatusOnly"));
        var education = Pattern.compile("(?:졸업|중퇴).{0,30}?([0-9]+)년이내").matcher(t);
        if (t.contains("취업준비생") && education.find())
            add(facts, c, "JOBSEEKER_EDUCATION_WINDOW", Topic.APPLICANT, "취업준비생", Map.of("years", education.group(1), "events", "graduationOrWithdrawal", "comparison", "within"));
        if ((t.contains("선착순") || t.contains("신청접수순번대로")) && Pattern.compile("(?:선정하[되며고]|선정합니다|접수합니다)").matcher(t).find()
                && Pattern.compile("(?:순위|배점).{0,60}(?:관계없이|상관없이|무관)|(?:관계없이|상관없이|무관).{0,60}(?:순위|배점)").matcher(t).find())
            add(facts, c, "SELECTION_FIRST_COME_WITHOUT_RANK", Topic.RANK, "해당 조항 적용 대상", Map.of("selection", "firstCome", "rankAndScore", "notUsed", "eligibility", "separatelyRequired"));
        else if (Pattern.compile("선착순(?:현장접수로만진행|으로.{0,40}접수(?:합니다|할예정|를진행)|접수로.{0,20}진행)").matcher(t).find())
            add(facts, c, "FIRST_COME_PROCEDURE", Topic.RANK, subject(c), Map.of("procedure", "firstCome", "selectionPriority", "notInferred"));
        var rank = Pattern.compile("([1-9])순위").matcher(t);
        var tiers = new TreeSet<String>();
        while (rank.find()) tiers.add(rank.group(1));
        if (!tiers.isEmpty() && (t.contains("거주") || t.contains("주소") || t.contains("청약통장")) && !t.contains("접수기간"))
            add(facts, c, "RANK_TIER_CRITERIA", Topic.RANK, subject(c), Map.of("tiers", String.join(",", tiers), "scope", String.join(" / ", c.scopes()), "selectionResult", "notEvaluated", "rowAssociation", "sourceReviewRequired"));
        // The amount must immediately follow the income reference, never a later surcharge-table cell.
        var amount = Pattern.compile("월평균소득(?:의)?([0-9]+)(?:%|퍼센트)이하").matcher(t);
        if (amount.find())
            add(facts, c, "INCOME_PERCENT_OF_REFERENCE", Topic.INCOME, subject(c), Map.of("percent", amount.group(1), "comparison", "LTE", "base", "sourceDefinedReference", "householdAdjustments", "separateEvidenceRequired"));
    }

    private static void add(List<Fact> result, Clause c, String type, Topic topic, String subject, Map<String, String> values) {
        result.add(new Fact(id("fact", c.id() + type), type, topic, subject, values, List.of(c.id())));
    }

    /**
     * Match an asserted local predicate, not the same verb inside a negated or hypothetical phrase.
     */
    private static boolean assertedPredicate(String text, String stem) {
        return Pattern.compile(stem + "(?:합니다|한다|함|됩니다|된다|됨)?(?=$|[.,;:)]|다만|단,)").matcher(text).find();
    }

    private static boolean inSentence(String text, java.util.function.Predicate<String> predicate) {
        return Arrays.stream(text.split("[.!?;。]")).anyMatch(predicate);
    }

    private static String subject(Clause c) {
        return c.subjects().isEmpty() ? "원문 적용 대상 확인 필요" : String.join(" · ", c.subjects());
    }

    private static boolean relevant(Clause c) {
        return c.topics().stream().anyMatch(t -> t != Topic.OTHER && t != Topic.SCHEDULE);
    }

    private static boolean fullyRecognized(Clause c) {
        String t = compact(c.quote()).replaceFirst("^[▪■□◆●*①-⑳-]+", "");
        return t.matches("(?:소득요건)?소득요건(?:은)?배제[.!]?")
                || t.matches("(?:총자산요건)?총자산요건(?:은)?배제[.!]?")
                || t.matches("혼인중이아닐것[.!]?")
                || t.matches("신청자는미혼이어야합니다[.!]")
                || t.matches("모든학기수료를완료하였으나아직졸업을하지않은경우재학생으로간주[.!]?");
    }

    private static List<Topic> classify(String value) {
        var topics = new ArrayList<Topic>();
        TOPICS.forEach((topic, pattern) -> {
            if (pattern.matcher(value).find()) topics.add(topic);
        });
        return topics.isEmpty() ? List.of(Topic.OTHER) : List.copyOf(topics);
    }

    private static Map<Topic, Pattern> topics() {
        var result = new EnumMap<Topic, Pattern>(Topic.class);
        result.put(Topic.APPLICANT, Pattern.compile("신청\\s*자격|성년|미성년|대학생|취업준비생|혼인|무효|불법양도|신청자"));
        result.put(Topic.HOUSEHOLD, Pattern.compile("세대\\s*구성|가구원|부양|배우자|직계|부모|세대주|주민등록표"));
        result.put(Topic.HOUSING, Pattern.compile("무주택|주택\\s*소유|소형[·ㆍ]저가|분양권|주거전용면적"));
        result.put(Topic.INCOME, Pattern.compile("소득|퍼센트|도시근로자"));
        result.put(Topic.ASSETS, Pattern.compile("자산|부동산|금융자산|부채"));
        result.put(Topic.VEHICLE, Pattern.compile("자동차|승용차|보철용|저공해"));
        result.put(Topic.RANK, Pattern.compile("순위|배점|추첨|선착순|선정\\s*방법"));
        result.put(Topic.DOCUMENT, Pattern.compile("제출\\s*서류|증명서|확약서|동의서|발급|구비\\s*서류|증빙"));
        result.put(Topic.SCHEDULE, Pattern.compile("접수|마감|발표|모집\\s*일정|신청\\s*기간|계약\\s*기간"));
        result.put(Topic.EXCEPTION, Pattern.compile("예외|다만|단,|제외|간주|완화|미적용|대신|해당하는\\s*경우"));
        return Collections.unmodifiableMap(result);
    }

    private static List<Marker> markers(String quote) {
        var result = new ArrayList<Marker>();
        if (Pattern.compile("모두|전원|동시에|및|\\s와\\s|\\s과\\s").matcher(quote).find()) result.add(Marker.ALL_OF);
        if (Pattern.compile("또는|어느\\s*하나|중\\s*하나|이거나").matcher(quote).find()) result.add(Marker.ANY_OF);
        if (Pattern.compile("예외|다만|단,|제외|간주|대신|미적용").matcher(quote).find()) result.add(Marker.EXCEPTION);
        if (Pattern.compile("요건\\s*배제|완화").matcher(quote).find()) result.add(Marker.WAIVER);
        if (Pattern.compile("불가|않|없|아니|미적용|미포함|제외").matcher(quote).find()) result.add(Marker.NEGATION);
        if (Pattern.compile("참조|확인|[0-9]\\s*쪽|[0-9]\\s*[Pp](?![A-Za-z])|별지|별표").matcher(quote).find())
            result.add(Marker.REFERENCE);
        return List.copyOf(result);
    }

    private static List<String> references(String quote) {
        var result = new LinkedHashSet<String>();
        var pages = PAGE.matcher(quote);
        while (pages.find()) result.add("page:" + pages.group(1));
        var sections = REFERENCE_SECTION.matcher(quote);
        while (sections.find()) result.add("section:" + sections.group(1));
        // Appendix headings/layout are not resolved yet. Keep an explicit missing dependency so
        // a linked heading or another review issue cannot make the absent appendix look complete.
        var appendices = APPENDIX.matcher(quote);
        while (appendices.find()) result.add("appendix:" + compact(appendices.group()));
        return List.copyOf(result);
    }

    private static List<Quantity> quantities(String text, int offset) {
        var result = new ArrayList<Quantity>();
        var matcher = NUMBER.matcher(text);
        while (matcher.find())
            result.add(new Quantity(matcher.group(), matcher.group(1).replace(",", ""), compact(matcher.group(2)),
                    matcher.group(3) == null ? "UNSPECIFIED" : matcher.group(3), offset + matcher.start(), offset + matcher.end()));
        return List.copyOf(result);
    }

    private static List<String> matches(Pattern pattern, String text) {
        var result = new LinkedHashSet<String>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group().strip());
        return List.copyOf(result);
    }

    private static String compact(String value) {
        return value.replaceAll("[\\s\\p{Zs}]+", "");
    }

    private static List<Unresolved> distinct(List<Unresolved> issues) {
        return issues.stream().distinct().toList();
    }

    private static String id(String prefix, String value) {
        try {
            return prefix + "-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
