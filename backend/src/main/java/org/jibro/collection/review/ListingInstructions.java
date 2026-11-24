package org.jibro.collection.review;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete listing clauses, distinct from metadata-only source exclusion.
 */
final class ListingInstructions {
    enum Topic {METADATA, REFERENCE, SCHEDULE, CORRECTION, PENALTY, VERIFY, APPLICATION, MODIFICATION}

    record Syntax(String syntax, Topic topic, String title) {
    }

    private record Grammar(String basis, List<Syntax> clauses) {
    }

    record Clause(Topic topic, String title, String text, String location) {
    }

    record Reading(Source source, List<Clause> clauses, List<String> blockers) {
        boolean complete() {
            return blockers.isEmpty() && !clauses.isEmpty();
        }
    }

    private static final List<Syntax> GRAMMAR = grammar();

    private ListingInstructions() {
    }

    static Reading read(Source source, SupplementaryRules.Identity primary) {
        var clauses = new ArrayList<Clause>();
        var blockers = new LinkedHashSet<String>();
        for (var part : source.context())
            for (String raw : part.text().lines().toList()) {
                String text = SupplementaryRules.compact(raw);
                if (text.isEmpty()) continue;
                var matches = GRAMMAR.stream().filter(s -> pattern(s.syntax()).matcher(text).matches()).distinct().toList();
                if (matches.size() != 1) {
                    blockers.add("LISTING_CLAUSE_UNPARSED:" + part.location());
                    continue;
                }
                var matched = matches.get(0);
                if (text.startsWith("공고일")) {
                    String date = text.substring(3).replaceFirst(":", "");
                    if (!date.equals(primary.publication().toString().replace('-', '.')))
                        blockers.add("LISTING_RECRUITMENT_MISMATCH");
                }
                if (matched.topic() != Topic.METADATA)
                    clauses.add(new Clause(matched.topic(), matched.title(), raw.strip(), part.location()));
            }
        return new Reading(source, List.copyOf(clauses), List.copyOf(blockers));
    }

    private static Pattern pattern(String syntax) {
        var out = new StringBuilder();
        var slots = Pattern.compile("\\{\\{(date|longDate|time)}}").matcher(syntax);
        int end = 0;
        while (slots.find()) {
            out.append(Pattern.quote(syntax.substring(end, slots.start())));
            out.append(switch (slots.group(1)) {
                case "date" -> "20[0-9]{2}[.][0-9]{1,2}[.][0-9]{1,2}";
                case "longDate" -> "20[0-9]{2}년[0-9]{1,2}월[0-9]{1,2}일";
                default -> "[0-9]{1,2}:[0-9]{2}";
            });
            end = slots.end();
        }
        return Pattern.compile(out + Pattern.quote(syntax.substring(end)));
    }

    private static List<Syntax> grammar() {
        try (var in = ListingInstructions.class.getResourceAsStream("/review/listing-clause-syntax.json")) {
            if (in == null) throw new IllegalStateException("Missing listing clause grammar");
            return new ObjectMapper().readValue(in, Grammar.class).clauses().stream().distinct().toList();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
