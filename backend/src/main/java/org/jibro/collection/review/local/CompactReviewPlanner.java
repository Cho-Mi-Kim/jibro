package org.jibro.collection.review.local;

import java.net.URI;
import java.util.*;
import org.jibro.collection.review.local.CompactReviewPacket.*;
import static org.jibro.collection.review.local.LocalRuleBook.*;

/** Selects complete dependency groups, never a shortened prefix of an official condition. */
public final class CompactReviewPlanner {
    public static final int MAX_PACKET_CHARACTERS = 6000;
    public static final int MAX_PACKETS_PER_PASS = 3;
    public static final int MAX_ISSUES_PER_PACKET = 32;
    private static final int MAX_CLAUSES_PER_ISSUE = 64;
    public static final String POLICY = "local-compact-v2";
    private static final int MAX_CLAUSES = 10_000;
    private static final int MAX_ISSUES = 2000;

    private CompactReviewPlanner() {}

    public record Blocked(String issueId, String code, List<String> clauseIds, String reason, int requiredCharacters) {
        public Blocked { clauseIds = List.copyOf(clauseIds); }
    }
    public record Metrics(int rawCharacters, int selectedCharacters, int transmittedCharacters,
                          int candidatePacketCount, int selectedPacketCount, int completedPacketCount,
                          int deferredPacketCount, int selectedIssueCount, int blockedIssueCount) {}
    public record Plan(List<CompactReviewPacket> packets, List<Blocked> blocked, List<String> deferredPacketIds, Metrics metrics) {
        public Plan {
            packets = List.copyOf(packets);
            blocked = List.copyOf(blocked);
            deferredPacketIds = List.copyOf(deferredPacketIds);
        }
    }
    private record Group(Issue issue, Set<String> clauseIds, int priority) {}
    private record Closure(Set<String> ids, String failure) {}

    public static Plan plan(LocalRuleBook book) { return plan(book, Set.of()); }

    /** Completed packet IDs permit a bounded next pass without repeatedly sending earlier packets. */
    public static Plan plan(LocalRuleBook book, Set<String> completedPacketIds) {
        Objects.requireNonNull(book); Objects.requireNonNull(completedPacketIds);
        var blocked = new ArrayList<Blocked>();
        if (book.clauses().size() > MAX_CLAUSES || book.unresolved().size() > MAX_ISSUES) {
            blocked.add(new Blocked("book", "LOCAL_INDEX_LIMIT", List.of(), "LOCAL_INDEX_LIMIT", 0));
            return result(book, List.of(), blocked, List.of(), 0, 0);
        }
        Map<String, Clause> clauses = new TreeMap<>();
        boolean duplicateId = false;
        for (Clause clause : book.clauses()) {
            var previous = clauses.putIfAbsent(clause.id(), clause);
            if (previous != null && !previous.equals(clause)) duplicateId = true;
        }
        if (duplicateId) {
            blocked.add(new Blocked("book", "LOCAL_ID_CONFLICT", List.of(), "LOCAL_ID_CONFLICT", 0));
            return result(book, List.of(), blocked, List.of(), 0, 0);
        }
        var aliases = clauseAliases(clauses.keySet());
        Map<String, List<Fact>> factsByClause = new HashMap<>();
        for (Fact fact : book.facts()) for (String id : fact.clauseIds())
            factsByClause.computeIfAbsent(id, ignored -> new ArrayList<>()).add(fact);
        var incompleteClauses = new HashSet<String>();
        book.unresolved().stream().filter(issue -> incomplete(issue.code()))
            .forEach(issue -> incompleteClauses.addAll(issue.clauseIds()));

        List<Group> groups = new ArrayList<>();
        Set<String> seenIssues = new HashSet<>();
        Set<String> seenContexts = new HashSet<>();
        for (Unresolved issue : book.unresolved()) {
            var seeds = new TreeSet<>(issue.clauseIds());
            String id = "issue-" + CompactReviewPacket.digest(issue.code() + "\n" + String.join("\n", seeds));
            if (!seenIssues.add(id)) continue;
            // This ledger entry stays attached to the full local rule book. No dependency walk or
            // provider payload is needed until a specific review question has been identified.
            if (Set.of("LOCAL_SCOPE_UNRESOLVED", "NUMERIC_SCOPE_UNRESOLVED").contains(issue.code())) {
                blocked.add(new Blocked(id, issue.code(), List.copyOf(seeds), "LOCAL_REVIEW_PENDING", 0));
                continue;
            }
            var closure = close(seeds, clauses, factsByClause, incompleteClauses);
            String failure = closure.failure();
            if (incomplete(issue.code())) failure = "INCOMPLETE_LOCAL_CONTEXT";
            if (failure == null && closure.ids().stream().map(clauses::get).noneMatch(CompactReviewPlanner::substantive))
                failure = "LOCAL_CONTEXT_UNCLASSIFIED";
            if (failure == null && closure.ids().size() > MAX_CLAUSES_PER_ISSUE) failure = "DEPENDENCY_COUNT_LIMIT";
            if (failure != null) {
                blocked.add(new Blocked(id, issue.code(), List.copyOf(closure.ids()), failure, 0));
                continue;
            }
            // Different seed paragraphs can lead to the identical complete review question.
            // Reuse that question only when both code and every source-bound clause match.
            String contextKey = issue.code() + "\n" + String.join("\n", closure.ids());
            if (!seenContexts.add(contextKey)) continue;
            var scopedIssue = new Issue("issue-" + CompactReviewPacket.digest(contextKey), issue.code(), List.copyOf(closure.ids()));
            var group = new Group(scopedIssue, closure.ids(), priority(issue.code(), closure.ids(), clauses));
            var candidate = packet(book, List.of(group), clauses, aliases);
            int required = candidate.transmittedCharacters();
            if (required > MAX_PACKET_CHARACTERS) {
                blocked.add(new Blocked(id, issue.code(), List.copyOf(closure.ids()), "CONTEXT_EXCEEDS_PACKET_LIMIT", required));
                continue;
            }
            groups.add(group);
        }
        groups.sort(Comparator.comparingInt(Group::priority).reversed().thenComparing(g -> g.issue().id()));

        // Shared headings may appear in separate packets; connecting every heading consumer would
        // turn a short exception review into an entire document. Each issue's own closure stays whole.
        List<List<Group>> bins = new ArrayList<>();
        for (Group group : groups) {
            boolean added = false;
            for (List<Group> bin : bins) {
                var combined = new ArrayList<>(bin); combined.add(group);
                if (combined.size() <= MAX_ISSUES_PER_PACKET && packet(book, combined, clauses, aliases).transmittedCharacters() <= MAX_PACKET_CHARACTERS) {
                    bin.add(group); added = true; break;
                }
            }
            if (!added) bins.add(new ArrayList<>(List.of(group)));
        }
        var selected = new ArrayList<CompactReviewPacket>();
        var deferred = new ArrayList<String>();
        int completed = 0;
        for (var bin : bins) {
            var packet = packet(book, bin, clauses, aliases);
            if (completedPacketIds.contains(packet.id())) { completed++; continue; }
            if (selected.size() < MAX_PACKETS_PER_PASS) selected.add(packet);
            else deferred.add(packet.id());
        }
        return result(book, selected, blocked, deferred, bins.size(), completed);
    }

    private static Closure close(Set<String> seeds, Map<String, Clause> clauses, Map<String, List<Fact>> factsByClause,
                                 Set<String> incompleteClauses) {
        if (seeds.isEmpty()) return new Closure(Set.of(), "NO_EVIDENCE_CONTEXT");
        var ids = new TreeSet<String>();
        var pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!ids.add(id)) continue;
            // An unrelated heading or a second issue must not hide a known missing page or exception.
            if (incompleteClauses.contains(id)) return new Closure(ids, "INCOMPLETE_LOCAL_CONTEXT");
            Clause clause = clauses.get(id);
            if (clause == null) return new Closure(ids, "DEPENDENCY_MISSING");
            if (!valid(clause)) return new Closure(ids, "EVIDENCE_INVALID");
            if (!clause.references().isEmpty() && clause.relatedClauseIds().isEmpty())
                return new Closure(ids, "REFERENCE_CONTEXT_MISSING");
            pending.addAll(clause.relatedClauseIds());
            for (Fact fact : factsByClause.getOrDefault(id, List.of())) pending.addAll(fact.clauseIds());
        }
        return new Closure(ids, null);
    }

    private static boolean incomplete(String code) {
        return code.contains("MISSING") || code.contains("INCOMPLETE") || code.contains("UNREADABLE")
            || code.contains("SOURCE_FAILED") || code.equals("EXCEPTION_PARENT_UNRESOLVED");
    }

    private static boolean substantive(Clause clause) {
        return clause.topics().stream().anyMatch(topic -> topic != Topic.OTHER)
            || !clause.markers().isEmpty() || !clause.quantities().isEmpty();
    }

    private static int priority(String code, Set<String> ids, Map<String, Clause> clauses) {
        int result = code.contains("CONFLICT") ? 100 : code.contains("TABLE") ? 70 : code.contains("SCOPE") ? 60 : 10;
        for (String id : ids) {
            var clause = clauses.get(id);
            if (clause.markers().contains(Marker.EXCEPTION) || clause.markers().contains(Marker.WAIVER)) result += 10;
            if (clause.markers().contains(Marker.NEGATION)) result += 5;
        }
        return result;
    }

    private static CompactReviewPacket packet(LocalRuleBook book, List<Group> groups, Map<String, Clause> clauses, Map<String, String> aliases) {
        var ids = new TreeSet<String>();
        groups.forEach(group -> ids.addAll(group.clauseIds()));
        var sourceKeys = new TreeMap<String, SourceRef>();
        for (String id : ids) {
            Clause clause = clauses.get(id);
            String key = clause.sourceUrl() + "\n" + clause.sourceSha256();
            sourceKeys.putIfAbsent(key, new SourceRef("", clause.sourceUrl(), clause.sourceSha256()));
        }
        int index = 0;
        for (var entry : sourceKeys.entrySet()) {
            SourceRef source = entry.getValue();
            entry.setValue(new SourceRef("s" + ++index, source.url(), source.sha256()));
        }
        var evidence = new ArrayList<EvidenceClause>();
        for (String id : ids) {
            Clause c = clauses.get(id);
            var source = sourceKeys.get(c.sourceUrl() + "\n" + c.sourceSha256());
            evidence.add(new EvidenceClause(aliases.get(c.id()), source.id(), c.location(), c.start(), c.end(), c.quote(), c.heading(),
                c.topics(), c.subjects(), c.scopes(), c.markers(), c.quantities(), c.references(),
                c.relatedClauseIds().stream().map(aliases::get).toList()));
        }
        var facts = book.facts().stream().filter(fact -> !Collections.disjoint(fact.clauseIds(), ids))
            .sorted(Comparator.comparing(Fact::id)).map(fact -> new Fact(fact.id(), fact.type(), fact.topic(), fact.subject(),
                fact.values(), fact.clauseIds().stream().map(aliases::get).toList())).toList();
        var issues = groups.stream().map(Group::issue).sorted(Comparator.comparing(Issue::id))
            .map(issue -> new Issue(issue.id(), issue.code(), issue.clauseIds().stream().map(aliases::get).toList())).toList();
        String policy = POLICY + "/" + book.policy();
        var unsigned = new CompactReviewPacket("", policy, List.copyOf(sourceKeys.values()), evidence, facts, issues);
        String id = "packet-" + CompactReviewPacket.digest(unsigned.payloadJson());
        return new CompactReviewPacket(id, policy, unsigned.sources(), evidence, facts, issues);
    }

    private static Map<String, String> clauseAliases(Set<String> ids) {
        var aliases = new HashMap<String, String>();
        var used = new HashSet<String>();
        for (String id : ids) {
            String shortId = id.matches("clause-[a-f0-9]{24,64}") ? "c" + id.substring(7, 23) : id;
            // Retain the full ID if a compact alias could be ambiguous in this book.
            if (ids.contains(shortId) && !shortId.equals(id) || !used.add(shortId)) shortId = id;
            aliases.put(id, shortId);
        }
        return aliases;
    }

    private static boolean valid(Clause clause) {
        if (clause.id() == null || clause.id().isBlank() || clause.quote() == null || clause.quote().isBlank()
            || clause.start() < 0 || clause.end() - clause.start() != clause.quote().length()
            || clause.location() == null || clause.location().isBlank() || clause.location().length() > 300
            || clause.sourceSha256() == null || !clause.sourceSha256().matches("[a-fA-F0-9]{64}")) return false;
        try {
            String value = clause.sourceUrl();
            if (value == null || value.length() > 2000
                || value.matches("(?is).*[?&](?:service.?key|api.?key|token|access.?token|password|secret)=.*")) return false;
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && Set.of("apply.lh.or.kr", "www.lh.or.kr").contains(uri.getHost())
                && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException invalid) { return false; }
    }

    private static Plan result(LocalRuleBook book, List<CompactReviewPacket> packets, List<Blocked> blocked,
                               List<String> deferred, int candidateCount, int completedCount) {
        var unique = new HashMap<String, Integer>();
        packets.forEach(packet -> packet.clauses().forEach(clause -> unique.putIfAbsent(clause.id(), clause.quote().length())));
        var metrics = new Metrics(book.sourceCharacters(), unique.values().stream().mapToInt(Integer::intValue).sum(),
            packets.stream().mapToInt(CompactReviewPacket::transmittedCharacters).sum(), candidateCount, packets.size(),
            completedCount, deferred.size(), packets.stream().mapToInt(packet -> packet.issues().size()).sum(), blocked.size());
        return new Plan(packets, blocked, deferred, metrics);
    }
}
