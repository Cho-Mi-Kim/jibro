package org.jibro.collection.extraction;

import java.util.*;
import java.util.regex.Pattern;
import static org.jibro.collection.extraction.ExtractionModels.*;

/** Extracts source sentences only. Categories are review aids, never eligibility predicates. */
public final class ExcerptClassifier {
    private ExcerptClassifier() {}
    private static final Map<String, Pattern> CATEGORIES = Map.of(
            "CONDITION", Pattern.compile("무주택|소득|자산|연령|만\\s*\\d+\\s*세|신청\\s*자격|가구원|거주지|순위"),
            "DOCUMENT", Pattern.compile("제출\\s*서류|증명서|주민등록|동의서|확약서|발급|구비\\s*서류"),
            "SCHEDULE", Pattern.compile("접수|마감|발표|신청\\s*기간|공급\\s*일정"));

    public static List<Excerpt> extract(List<Part> parts, String url) {
        var result = new ArrayList<Excerpt>();
        var seen = new HashSet<String>();
        for (Part part : parts) {
            for (String raw : part.text().split("[\\r\\n]+")) {
                String line = raw.replaceAll("[\\p{Zs}\\t]+", " ").trim();
                if (line.length() < 5) continue;
                if (line.length() > 1200) line = line.substring(0, 1200) + "… [발췌 길이 제한]";
                for (String category : List.of("CONDITION", "DOCUMENT", "SCHEDULE")) {
                    if (CATEGORIES.get(category).matcher(line).find() && seen.add(category + line)) {
                        result.add(new Excerpt(category, line, part.location(), url));
                        if (result.size() >= 90) return List.copyOf(result);
                    }
                }
            }
        }
        return List.copyOf(result);
    }
}
