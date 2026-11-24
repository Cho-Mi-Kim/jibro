package org.jibro.collection.review;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.followup.OfficialFollowupModels.*;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.source.NoticeSourceInformationModels.Area;
import org.jibro.notice.source.NoticeSourceInformationModels.Section;

import static org.jibro.collection.review.FollowupClaimReader.*;

/**
 * Pure comparison of fetched, related official sources. It neither discovers relations nor publishes.
 */
public final class OfficialFollowupComparison {
    public record Resolution(String field, String before, String after, List<Evidence> previousEvidence,
                             Evidence replacementEvidence, String relationshipProof) {
        public Resolution {
            previousEvidence = List.copyOf(previousEvidence);
        }
    }

    public record Invalidation(String code, String statement, Evidence evidence, String relationshipProof) {
    }

    public record Result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions,
                         List<String> blockers,
                         List<Invalidation> invalidations, ApplicationPeriodCompiler.Result intake) {
        public Result {
            resolutions = List.copyOf(resolutions);
            blockers = List.copyOf(blockers);
            invalidations = List.copyOf(invalidations);
        }

        public Result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, List<String> blockers) {
            this(assessment, resolutions, blockers, List.of(), null);
        }

        public boolean invalidatesPublishedGuide() {
            return !invalidations.isEmpty() || resolutions.stream().anyMatch(resolution -> Arrays.stream(resolution.before().split("\\R"))
                    .anyMatch(before -> !equivalent(before, resolution.after())));
        }
    }

    private record Occurrence(Source source, String location, String line, Value value) {
    }

    private OfficialFollowupComparison() {
    }

    /**
     * Independently confirmed items may be displayed while a differently formatted attachment awaits review.
     */
    public static List<Section> corroboratedItems(Draft draft, Content original, Discovery discovery) {
        var followups = discovery.related().stream().filter(r -> "FOLLOWUP".equals(r.kind())).toList();
        if (!followups.isEmpty() && discovery.permitsVerifiedItems() && discovery.hasMirror()) {
            var baseline = new Discovery(discovery.checkedAt(), discovery.related().stream()
                    .filter(r -> !"FOLLOWUP".equals(r.kind())).toList(), discovery.blockers());
            var items = new ArrayList<>(corroboratedItems(draft, original, baseline));
            var changes = new LinkedHashMap<String, List<Change>>();
            for (var post : followups) {
                if (!official(post.url()) || post.relationshipProof() == null || post.relationshipProof().isBlank()
                        || post.publishedDate() == null || post.publishedDate().isAfter(discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
                        || post.sources().stream().noneMatch(s -> s.url().equals(post.url()))
                        || post.sources().stream().anyMatch(s -> !complete(s, null) || OcrProvenance.contains(s)))
                    return List.of();
                var reading = FollowupClaimReader.read(OfficialGuidanceRows.read(post), draft.sources());
                if (!reading.blockers().isEmpty()) return List.of();
                for (var change : reading.changes()) {
                    if (change.value().field().startsWith("SCHEDULE:")) continue;
                    var rule = OfficialGuidanceItem.parse(change.value().statement());
                    if (rule == null || change.before() == null) return List.of();
                    changes.computeIfAbsent(rule.title(), ignored -> new ArrayList<>()).add(change);
                }
            }
            String checked = discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            for (var entry : changes.entrySet()) {
                var previous = items.stream().filter(s -> s.title().equals(entry.getKey())).toList();
                items.removeAll(previous);
                if (previous.size() != 1 || entry.getValue().size() != 1) continue;
                var change = entry.getValue().get(0);
                var old = OfficialGuidanceItem.parse(change.before());
                var current = OfficialGuidanceItem.parse(change.value().statement());
                if (old != null && !OfficialGuidanceItem.needsAgency(current) && equivalent(previous.get(0).text(), old.value()))
                    items.add(new Section(previous.get(0).id(), previous.get(0).area(), current.title(), current.value(),
                            evidence(change.source(), change.location(), change.value().statement(), checked)));
            }
            return List.copyOf(items);
        }
        var corrections = discovery.related().stream().filter(r -> "CORRECTION".equals(r.kind())).toList();
        if (!corrections.isEmpty() && discovery.permitsVerifiedItems()) {
            String parent = original.evidence().url();
            java.time.LocalDate last = null;
            for (var correction : corrections) {
                if (!Objects.equals(correction.supersedesUrl(), parent) || !official(correction.url())
                        || correction.publishedDate() == null || last != null && correction.publishedDate().isBefore(last))
                    return List.of();
                parent = correction.url();
                last = correction.publishedDate();
            }
            var current = corrections.get(corrections.size() - 1);
            var mirrors = discovery.related().stream().filter(r -> !"CORRECTION".equals(r.kind())).toList();
            if (mirrors.isEmpty() || mirrors.stream().anyMatch(r -> !"AGENCY".equals(r.kind())
                    || !r.relationshipProof().endsWith(current.url()) || !current.publishedDate().equals(r.publishedDate())))
                return List.of();
            var edition = new Draft(draft.id(), draft.noticeId(), draft.revision(), "PARTIAL", draft.checkedAt(), "", current.sources());
            var content = new Content(original.summary(), new Evidence(current.title(), current.url(), current.publishedDate().toString()),
                    List.of(), List.of(), List.of(), List.of());
            return corroboratedItems(edition, content, new Discovery(discovery.checkedAt(), mirrors, List.of()));
        }
        if (!discovery.permitsVerifiedItems() || draft.checkedAt() == null || discovery.checkedAt() == null
                || discovery.checkedAt().isBefore(draft.checkedAt()) || discovery.related().isEmpty() || discovery.related().size() > 10
                || discovery.related().stream().anyMatch(r -> !"AGENCY".equals(r.kind()))) return List.of();
        var checkDate = discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var primary = OfficialGuidanceRows.read(draft);
        var input = occurrences(primary);
        var agency = new ArrayList<Change>();
        var hashes = new HashSet<String>();
        var mirrors = new ArrayList<Source>();
        for (var related : discovery.related()) {
            if (!agencyUrl(related.url(), related.url()) || related.relationshipProof() == null || related.relationshipProof().isBlank())
                return List.of();
            if (related.publishedDate() == null || related.publishedDate().isAfter(checkDate) || related.sources().size() > 51
                    || related.sources().stream().noneMatch(s -> s.url().equals(related.url()) && complete(s, related.url()))
                    || related.sources().stream().anyMatch(s -> !agencyUrl(related.url(), s.url()))) return List.of();
            var readable = related.sources().stream().filter(s -> complete(s, related.url()) && !OcrProvenance.contains(s)).toList();
            readable.forEach(s -> hashes.add(s.sha256()));
            if (related.url().matches("https://www[.]myhome[.]go[.]kr/hws/portal/sch/selectRsdtRcritNtcDetailView[.]do[?]pblancId=[0-9]{1,12}"))
                readable.stream().filter(s -> !s.url().equals(related.url())).forEach(mirrors::add);
            var verified = new Related(related.kind(), related.url(), related.title(), related.publishedDate(), related.relationshipProof(), readable);
            var reading = FollowupClaimReader.agency(OfficialGuidanceRows.read(verified), List.of());
            if (!reading.blockers().isEmpty()) return List.of();
            agency.addAll(reading.changes());
        }
        String checked = discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        var confirmed = new ArrayList<Section>();
        if (!mirrors.isEmpty() && agency.stream().noneMatch(c -> c.before() != null)
                && discovery.related().stream().allMatch(r -> r.sources().stream()
                .filter(s -> !s.url().equals(r.url()) && complete(s, r.url())).allMatch(s -> mirrors.stream()
                        .anyMatch(m -> m.sha256().equals(s.sha256())) || draft.sources().stream().anyMatch(p -> Objects.equals(p.sha256(), s.sha256()))))) {
            confirmed.addAll(OfficialMirrorInstructions.read(mirrors, draft.sources(), checked));
        }
        var pairedItems = new ArrayList<>(PairedPreparationItems.read(draft, original.evidence().url()));
        if (pairedItems.isEmpty()) {
            // Join alternate representations by the whole governing filename, then by each actual rule on a specific PDF page.
            for (var pdf : mirrors) {
                if (!pdf.label().toLowerCase(Locale.ROOT).endsWith(".pdf") || !pdf.label().contains("공고")) continue;
                String stem = pdf.label().replaceFirst("^[{]공고문[(]PDF[)][}]_?", "").replaceFirst("(?i)[.]pdf$", "");
                var matching = draft.sources().stream().filter(s -> complete(s, null) && !OcrProvenance.contains(s)
                        && s.label().equalsIgnoreCase(stem + ".hwpx")).toList();
                if (matching.size() == 1 && mirrors.stream().filter(s -> s.label().equalsIgnoreCase(pdf.label())).count() == 1)
                    pairedItems.addAll(PairedPreparationItems.readPair(draft.checkedAt(), matching.get(0), pdf));
            }
        }
        for (var section : pairedItems) {
            var sameTitle = agency.stream().filter(c -> {
                var rule = GuidanceRuleGrammar.parse(c.value().statement());
                return rule != null && rule.title().replace(" ", "").equals(section.title().replace(" ", ""));
            }).toList();
            boolean identicalFile = primary.sources().stream().anyMatch(s -> complete(s, null) && !OcrProvenance.contains(s) && hashes.contains(s.sha256()) && s.url().equals(EvidenceLocationUrl.base(section.evidence().url())));
            boolean identicalField = !sameTitle.isEmpty() && sameTitle.stream().allMatch(c -> c.before() == null
                    && input.stream().anyMatch(o -> complete(o.source(), null) && !OcrProvenance.contains(o.source())
                    && o.value().field().equals(c.value().field()) && equivalent(o.line(), c.value().statement()))
                    && input.stream().noneMatch(o -> o.value().field().equals(c.value().field()) && !equivalent(o.line(), c.value().statement()))
                    && section.text().replaceAll("\\s+", "").contains(GuidanceRuleGrammar.parse(c.value().statement()).value().replaceAll("\\s+", "")));
            boolean mirrorPair = mirrors.stream().anyMatch(s -> s.url().equals(EvidenceLocationUrl.base(section.evidence().url())));
            if (sameTitle.isEmpty() ? identicalFile || mirrorPair : identicalField || mirrorPair && sameTitle.stream().allMatch(c ->
                    c.before() == null && section.text().equals(GuidanceRuleGrammar.parse(c.value().statement()).value())))
                confirmed.add(section);
        }
        for (var claim : agency) {
            var rule = OfficialGuidanceItem.parse(claim.value().statement());
            if (rule == null || OfficialGuidanceItem.needsAgency(rule) || claim.before() != null) continue;
            var area = rule.kind().equals("DOCUMENT_ITEM") ? Area.DOCUMENT
                    : Area.CONDITION;
            if (confirmed.stream().anyMatch(item -> item.area() == area && item.title().equals(rule.title()))) continue;
            var matching = input.stream().filter(field -> field.value().field().equals(claim.value().field())).toList();
            boolean mirrorOriginal = mirrors.stream().anyMatch(s -> s.url().equals(claim.source().url()) && s.label().contains("공고"))
                    && rule.value().matches("(?s).*(?:[.]|이하|이상|해당자|모든 신청자|신청자 전원|예비신혼부부)$");
            if ((!mirrorOriginal && matching.stream().noneMatch(o -> complete(o.source(), null) && !OcrProvenance.contains(o.source()))) || matching.stream().anyMatch(field -> !equivalent(field.line(), claim.value().statement()))
                    || agency.stream().anyMatch(other -> other.value().field().equals(claim.value().field())
                    && (other.before() != null || !equivalent(other.value().statement(), claim.value().statement()))))
                continue;
            confirmed.add(new Section("agency-" + rule.key(), area, rule.title(), rule.value(),
                    evidence(claim.source(), claim.location(), claim.value().statement(), checked)));
        }
        if (agency.stream().noneMatch(c -> c.before() != null)) {
            for (var form : VerifiedPreparationForms.embedded(mirrors, checked)) {
                if (confirmed.stream().noneMatch(s -> s.area() == Area.DOCUMENT && s.title().equals(form.title())))
                    confirmed.add(form);
            }
        }
        return List.copyOf(confirmed);
    }

    /**
     * Source-identical republications can still use the independent PDF/HWPX paired-item path.
     */
    public static boolean repeatsPrimary(Draft primary, Discovery discovery) {
        if (discovery.related().isEmpty()) return true;
        if (discovery.related().stream().anyMatch(r -> !"AGENCY".equals(r.kind()))) return false;
        var hashes = primary.sources().stream().map(Source::sha256).collect(java.util.stream.Collectors.toSet());
        var fields = occurrences(OfficialGuidanceRows.read(primary));
        for (var related : discovery.related()) {
            if (related.sources().stream().anyMatch(s -> !complete(s, related.url()) || OcrProvenance.contains(s)
                    || !s.url().equals(related.url()) && !hashes.contains(s.sha256()))) return false;
            var reading = FollowupClaimReader.agency(OfficialGuidanceRows.read(related), primary.sources());
            if (!reading.blockers().isEmpty() || reading.changes().stream().anyMatch(change -> change.before() != null
                    || fields.stream().noneMatch(field -> equivalent(field.line(), change.value().statement()))))
                return false;
        }
        return true;
    }

    public static Result compare(Draft primary, Content original, Discovery discovery) {
        return compare(primary, original, discovery, List.of());
    }

    public static Result compare(Draft primary, Content original, Discovery discovery, List<IntakeChannelDates.Window> windows) {
        Objects.requireNonNull(primary);
        Objects.requireNonNull(original);
        Objects.requireNonNull(discovery);
        var blockers = new LinkedHashSet<>(discovery.blockers());
        var baseline = GuidanceReviewCompiler.assess(primary, original, null);
        if (discovery.related().isEmpty()) return result(baseline, List.of(), blockers);
        if (discovery.checkedAt() == null || primary.checkedAt() == null
                || discovery.checkedAt().isBefore(primary.checkedAt()) || discovery.related().size() > 10) {
            blockers.add("FOLLOWUP_DISCOVERY_INVALID");
            return result(baseline, List.of(), blockers);
        }
        String checked = discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        var coverage = SourceCoveragePolicy.assessScoped(primary, original);
        // Full original coverage remains mandatory. A good follow-up cannot certify an unread primary page.
        if (!repeatsPrimary(primary, discovery)) blockers.addAll(coverage.issues());
        var sourceHashes = new LinkedHashMap<String, String>();
        primary.sources().forEach(source -> sourceHashes.put(source.url(), source.sha256()));
        var readings = new ArrayList<Change>();
        var invalidations = new ArrayList<Invalidation>();
        var comparedPrimary = OfficialGuidanceRows.read(primary);
        var corrections = new LinkedHashMap<String, Related>();
        var correctionOwner = new HashMap<Change, Related>();
        var agencySources = new ArrayList<Source>();
        var agencyForms = new ArrayList<Source>();
        var observations = new ArrayList<Change>();
        for (Related rawRelated : discovery.related()) {
            Related related = OfficialGuidanceRows.read(rawRelated);
            boolean agency = "AGENCY".equals(related.kind());
            if (!(agency ? agencyUrl(related.url(), related.url()) : Set.of("CORRECTION", "FOLLOWUP").contains(related.kind()) && official(related.url()))
                    || related.relationshipProof() == null || related.relationshipProof().isBlank()
                    || related.publishedDate() == null && (related.kind().equals("FOLLOWUP") || agency)
                    || related.publishedDate() != null && related.publishedDate().isAfter(LocalDate.parse(checked))
                    || related.sources().isEmpty() || related.sources().size() > 51
                    || related.sources().stream().noneMatch(source -> source.url().equals(related.url()))) {
                blockers.add("FOLLOWUP_RELATION_UNVERIFIED");
                continue;
            }
            boolean valid = true;
            for (Source source : related.sources()) {
                if (!complete(source, agency ? related.url() : null)) {
                    blockers.add("FOLLOWUP_SOURCE_INCOMPLETE");
                    valid = false;
                    continue;
                }
                if (OcrProvenance.contains(source)) {
                    blockers.add(OcrProvenance.REVIEW_REQUIRED);
                    valid = false;
                }
                String previous = sourceHashes.putIfAbsent(source.url(), source.sha256());
                if (previous != null && !previous.equals(source.sha256())) {
                    blockers.add("FOLLOWUP_SOURCE_IDENTITY_CHANGED");
                    valid = false;
                }
            }
            if (!valid) continue;
            if (agency) {
                agencySources.addAll(related.sources());
                related.sources().stream().filter(source -> !source.url().equals(related.url())
                        && comparedPrimary.sources().stream().noneMatch(old -> source.sha256().equals(old.sha256()))).forEach(agencyForms::add);
                var reading = FollowupClaimReader.agency(related, comparedPrimary.sources());
                blockers.addAll(reading.blockers());
                for (var change : reading.changes()) {
                    if (change.before() == null) observations.add(change);
                    else readings.add(change);
                }
                continue;
            }
            var previous = corrections.get(related.supersedesUrl());
            if (related.supersedesUrl() != null && (!related.kind().equals("CORRECTION") || !official(related.supersedesUrl())
                    || !related.supersedesUrl().equals(original.evidence().url()) && previous == null
                    || previous != null && previous.publishedDate() != null && related.publishedDate() != null
                    && related.publishedDate().isBefore(previous.publishedDate()))) {
                blockers.add("FOLLOWUP_CORRECTION_CHAIN_UNVERIFIED");
                continue;
            }
            var reading = FollowupClaimReader.read(related, previous == null ? comparedPrimary.sources() : previous.sources());
            if (related.kind().equals("CORRECTION")) {
                corrections.put(related.url(), related);
                reading.changes().forEach(c -> correctionOwner.put(c, related));
            }
            blockers.addAll(reading.blockers());
            readings.addAll(reading.changes());
            if (related.kind().equals("CORRECTION")) {
                // A verified complete replacement can make the old guide stale even when its new
                // governing clause is outside the closed compiler grammar. This is review work,
                // not an invented field-level conflict or an assertion that the new clause is parsed.
                reading.governingClauses().stream().filter(clause -> !presentIn(comparedPrimary, clause.statement()))
                        .forEach(clause -> invalidations.add(new Invalidation("FOLLOWUP_CORRECTION_REQUIRES_RECOMPILATION",
                                clause.statement(), evidence(clause.source(), clause.location(), clause.statement(), checked), related.relationshipProof())));
            }
        }
        if (readings.size() + observations.size() > 300) {
            blockers.add("FOLLOWUP_CLAIM_LIMIT");
            return result(baseline, List.of(), blockers, invalidations);
        }
        // Discovery delivers verified parent links before their children. An undated linked reissue
        // inherits the preceding publication date for ordering; this does not invent a published date.
        var order = new HashMap<String, Integer>();
        var sortDates = new HashMap<String, LocalDate>();
        for (Related correction : corrections.values()) {
            order.put(correction.url(), order.getOrDefault(correction.supersedesUrl(), -1) + 1);
            sortDates.put(correction.url(), correction.publishedDate() == null ? sortDates.get(correction.supersedesUrl()) : correction.publishedDate());
        }
        readings.sort(Comparator.comparing((Change c) -> correctionOwner.containsKey(c)
                        ? sortDates.get(correctionOwner.get(c).url()) : c.published(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(c -> correctionOwner.containsKey(c) ? order.get(correctionOwner.get(c).url()) : 0)
                .thenComparing(change -> change.source().url()));
        if (!readings.isEmpty() && readings.stream().allMatch(c -> c.value().field().startsWith("SCHEDULE:CHANNEL:"))
                && invalidations.isEmpty() && blockers.stream().allMatch(b -> b.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:"))) {
            var coverageIntake = SourceCoveragePolicy.assessIntake(primary, original);
            if (coverageIntake.issues().stream().allMatch(b -> b.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:"))
                    && readings.stream().allMatch(c -> c.published() != null && c.before() != null)) {
                var amendments = readings.stream().map(c -> new ChannelAmendment(ChannelAmendment.parse(c.before()).method(),
                        ChannelAmendment.parse(c.before()), ChannelAmendment.parse(c.value().statement()), c.published(),
                        evidence(c.source(), c.location(), c.value().statement(), checked))).distinct().toList();
                var intake = AutomaticScheduleCompiler.reconcile(coverageIntake.scheduleSources(), LocalDate.parse(checked), windows, amendments);
                if (intake != null) {
                    var latest = amendments.stream().map(ChannelAmendment::published).max(LocalDate::compareTo).orElseThrow();
                    var currentSources = readings.stream().filter(c -> latest.equals(c.published()))
                            .map(Change::source).distinct().toList();
                    var visitIssues = new ArrayList<>(intake.reviewIssues());
                    var visits = VisitReception.enrich(intake.schedules(), Set.of(), currentSources, LocalDate.parse(checked), intake.end(), visitIssues);
                    intake = new ApplicationPeriodCompiler.Result(intake.start(), intake.end(), visits, intake.issues(), List.copyOf(visitIssues));
                    var dateOnly = result(baseline, List.of(), Set.of("CHANNEL_INTAKE_REVIEW"));
                    return new Result(dateOnly.assessment(), List.of(), dateOnly.blockers(), List.of(), intake);
                }
            }
        }
        var occurrences = occurrences(comparedPrimary);
        var removed = new LinkedHashSet<Occurrence>();
        var active = new ArrayList<Change>();
        var resolutions = new ArrayList<Resolution>();
        boolean scopedApplication = primary.sources().stream().map(ScheduleSourceReader::read)
                .anyMatch(reading -> !reading.scoped().isEmpty() || !reading.unresolvedScopes().isEmpty());
        for (Change change : readings) {
            String field = change.value().field();
            if (field.startsWith("SCHEDULE:") && scopedApplication) {
                blockers.add("FOLLOWUP_EVENT_SCOPE_UNSUPPORTED");
                continue;
            }
            var matching = occurrences.stream().filter(old -> old.value().field().equals(field)
                    && (change.before() == null || equivalent(old.line(), change.before()))).toList();
            var preceding = active.stream().filter(old -> old.value().field().equals(field)
                    && (old.published() != null && change.published() != null && old.published().isBefore(change.published())
                    || correctionOwner.containsKey(change) && correctionOwner.containsKey(old)
                    && correctionOwner.get(old).url().equals(correctionOwner.get(change).supersedesUrl()))
                    && (change.before() != null ? equivalent(old.value().statement(), change.before())
                    : change.value().uncertain() || old.value().uncertain())).toList();
            if (matching.isEmpty() && preceding.isEmpty()) {
                blockers.add("FOLLOWUP_CHANGED_FIELD_NOT_FOUND:" + field);
                continue;
            }
            // Only an explicit old value, or an explicit later unresolved→confirmed transition,
            // replaces an already encountered follow-up. Newer timestamps alone do not win conflicts.
            active.removeAll(preceding);
            removed.addAll(matching);
            active.add(change);
            List<Evidence> prior = new ArrayList<>();
            matching.forEach(old -> prior.add(evidence(old.source(), old.location(), old.line(), checked)));
            preceding.forEach(old -> prior.add(evidence(old.source(), old.location(), old.value().statement(), checked)));
            String before = matching.stream().map(Occurrence::line).distinct().reduce((a, b) -> a + "\n" + b)
                    .orElseGet(() -> preceding.stream().map(old -> old.value().statement()).distinct().reduce((a, b) -> a + "\n" + b).orElse(""));
            resolutions.add(new Resolution(field, before, change.value().statement(), prior.stream().distinct().toList(),
                    evidence(change.source(), change.location(), change.value().statement(), checked), change.relationshipProof()));
        }
        // An independently stated value is evidence, not permission to erase a conflicting primary rule.
        for (Change observation : observations) {
            boolean superseded = removed.stream().anyMatch(old -> equivalent(old.line(), observation.value().statement()))
                    && active.stream().anyMatch(change -> change.value().field().equals(observation.value().field())
                    && change.published() != null && observation.published().isBefore(change.published()));
            if (superseded) continue;
            var observed = GuidanceRuleGrammar.parse(observation.value().statement());
            if (!OfficialGuidanceItem.needsAgency(observed)) {
                occurrences.stream().filter(old -> old.value().field().equals(observation.value().field()))
                        .filter(old -> OfficialGuidanceItem.needsAgency(GuidanceRuleGrammar.parse(old.line()))).forEach(removed::add);
            }
            active.add(observation);
        }
        if (active.isEmpty()) {
            var current = agencyForms.isEmpty() ? baseline : GuidanceReviewCompiler.assess(primary, original, null, Set.of(), List.of(), agencyForms);
            if (corrections.isEmpty() && blockers.isEmpty())
                current = AgencyProxyForms.apply(current, primary.sources(), agencySources, checked);
            return result(current, resolutions, blockers, invalidations);
        }

        // These are ephemeral claim projections. Raw sources and their SHA/context in Discovery/Draft
        // are untouched. Removing a recognized superseded line never removes its neighbouring exception.
        var supersededRules = new LinkedHashSet<String>();
        removed.stream().filter(old -> !old.value().field().startsWith("SCHEDULE:")).forEach(old -> supersededRules.add(old.line()));
        var ruleReplacements = active.stream().filter(change -> !change.value().field().startsWith("SCHEDULE:"))
                .map(change -> new AutomaticGuidanceCompiler.LocatedRule(GuidanceRuleGrammar.parse(change.value().statement()),
                        evidence(change.source(), change.location(), change.value().statement(), checked), change.value().statement())).toList();
        var scheduleSources = project(coverage.scheduleSources().stream().map(OfficialGuidanceRows::read).toList(), removed, true);
        scheduleSources.addAll(replacements(active, true));
        var assessment = GuidanceReviewCompiler.assess(primary, original, scheduleSources, supersededRules, ruleReplacements, agencyForms);
        if (corrections.isEmpty() && blockers.isEmpty())
            assessment = AgencyProxyForms.apply(assessment, primary.sources(), agencySources, checked);
        if (assessment.candidate() != null) {
            Content candidate = assessment.candidate();
            var attachments = new LinkedHashMap<String, Evidence>();
            candidate.attachments().forEach(item -> attachments.put(item.url(), item));
            discovery.related().forEach(related -> related.sources().forEach(source ->
                    attachments.putIfAbsent(source.url(), evidence(source, "후속 공식 자료", "", checked))));
            // '이후' is a lower bound, not a promise of publication at that exact minute.
            var schedules = candidate.schedules().stream().map(schedule -> {
                boolean after = active.stream().anyMatch(change -> change.source().url().equals(EvidenceLocationUrl.base(schedule.evidence().url()))
                        && change.value().statement().endsWith(" 이후") && matchesSchedule(change.value().field(), schedule.id()));
                return after ? new ScheduleTemplate(schedule.id(), schedule.title() + " (이후)", schedule.date(), schedule.evidence()) : schedule;
            }).toList();
            candidate = new Content(candidate.summary(), candidate.evidence(), List.copyOf(attachments.values()), candidate.questions(),
                    candidate.documents(), schedules, candidate.reviewIssues());
            assessment = new GuidanceReviewCompiler.Assessment(assessment.profile(), assessment.ready(), candidate,
                    assessment.issues(), assessment.findings(), assessment.startDate(), assessment.endDate());
        }
        var compared = result(assessment, resolutions, blockers, invalidations);
        if (!blockers.isEmpty() || !invalidations.isEmpty() || active.stream().noneMatch(change ->
                Set.of("SCHEDULE:APPLICATION", "SCHEDULE:START", "SCHEDULE:END").contains(change.value().field())))
            return compared;
        var intake = ApplicationPeriodCompiler.fromVerifiedSources(scheduleSources, LocalDate.parse(checked));
        return new Result(compared.assessment(), resolutions, compared.blockers(), invalidations, intake.ready() ? intake : null);
    }

    private static Result result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, Set<String> blockers) {
        return result(assessment, resolutions, blockers, List.of());
    }

    private static Result result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, Set<String> blockers,
                                 List<Invalidation> invalidations) {
        var all = new LinkedHashSet<>(assessment.issues());
        all.addAll(blockers);
        var checked = new GuidanceReviewCompiler.Assessment(assessment.profile(), assessment.ready() && blockers.isEmpty(),
                assessment.candidate(), List.copyOf(all), assessment.findings(), assessment.startDate(), assessment.endDate());
        return new Result(checked, resolutions, List.copyOf(blockers), invalidations, null);
    }

    private static boolean presentIn(Draft primary, String statement) {
        return primary.sources().stream().flatMap(source -> source.context().stream())
                .filter(part -> part != null && part.text() != null).flatMap(part -> Arrays.stream(part.text().split("\\R")))
                .anyMatch(line -> equivalent(line, statement));
    }

    private static List<Occurrence> occurrences(Draft draft) {
        var result = new ArrayList<Occurrence>();
        for (Source source : draft.sources())
            for (Part part : source.context())
                for (String raw : part.text().split("\\R")) {
                    String line = clean(raw);
                    Value value = value(line);
                    if (value != null) result.add(new Occurrence(source, part.location(), line, value));
                }
        return result;
    }

    private static ArrayList<Source> project(List<Source> sources, Set<Occurrence> removed, boolean schedule) {
        var result = new ArrayList<Source>();
        for (Source source : sources) {
            var parts = source.context().stream().map(part -> {
                String retained = Arrays.stream(part.text().split("\\R")).filter(line -> removed.stream().noneMatch(old ->
                        old.source().url().equals(source.url()) && old.location().equals(part.location()) && old.line().equals(clean(line))
                                && old.value().field().startsWith("SCHEDULE:") == schedule)).reduce((a, b) -> a + "\n" + b).orElse("");
                return new Part(part.location(), retained.isBlank() ? (schedule ? "공급 일정" : "신청 자격") : retained);
            }).toList();
            result.add(new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), List.of(), parts));
        }
        return result;
    }

    private static List<Source> replacements(List<Change> active, boolean schedule) {
        var groups = new LinkedHashMap<String, List<Change>>();
        for (Change change : active)
            if (change.value().field().startsWith("SCHEDULE:") == schedule)
                groups.computeIfAbsent(change.source().url(), ignored -> new ArrayList<>()).add(change);
        return groups.values().stream().map(changes -> {
            Source source = changes.get(0).source();
            return new Source(source.label(), source.url(), source.sha256(), "EXTRACTED", source.message(), List.of(),
                    changes.stream().map(change -> new Part(change.location(), change.value().statement())).distinct().toList());
        }).toList();
    }

    private static boolean equivalent(String first, String second) {
        if (clean(first).equals(clean(second))) return true;
        var a = GuidanceRuleGrammar.parse(clean(first));
        var b = GuidanceRuleGrammar.parse(clean(second));
        if (a != null && b != null)
            return a.kind().equals(b.kind()) && a.key().equals(b.key()) && a.value().equals(b.value());
        var left = FollowupClaimReader.event(first);
        var right = FollowupClaimReader.event(second);
        return left != null && left.equals(right);
    }

    private static boolean matchesSchedule(String field, String id) {
        return field.equals("SCHEDULE:RESULT") && id.equals("auto-result")
                || field.equals("SCHEDULE:SELECTION") && id.equals("auto-document-selection");
    }

    private static Evidence evidence(Source source, String location, String anchor, String checked) {
        return new Evidence(source.label() + " · " + location, EvidenceLocationUrl.forExcerpt(source, location, anchor), checked);
    }

    private static boolean complete(Source source, String agency) {
        return source != null && (agency == null ? official(source.url()) : agencyUrl(agency, source.url())) && source.label() != null && !source.label().isBlank()
                && "EXTRACTED".equals(source.status()) && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
                && !source.context().isEmpty() && source.context().size() <= 200
                && source.context().stream().allMatch(part -> part != null && part.location() != null && !part.location().isBlank()
                && part.text() != null && !part.text().isBlank() && !part.text().contains("[발췌 길이 제한]"))
                && source.context().stream().mapToLong(part -> part.text().length()).sum() <= 1_000_000;
    }

    private static boolean agencyUrl(String parent, String url) {
        try {
            URI article = URI.create(parent), source = URI.create(url);
            String host = article.getHost();
            return org.jibro.collection.review.followup.OfficialFollowupModels.agencyHost(host)
                    && host.equals(source.getHost()) && "https".equals(source.getScheme())
                    && source.getUserInfo() == null && source.getPort() == -1 && source.getFragment() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean official(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "apply.lh.or.kr".equals(uri.getHost()) && uri.getUserInfo() == null
                    && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
