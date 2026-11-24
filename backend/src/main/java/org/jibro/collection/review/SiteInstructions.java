package org.jibro.collection.review;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.local.DocumentContextReader;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads every site clause in its complex cell; performance ratings are descriptive fields.
 */
final class SiteInstructions {
    enum Topic {
        HEADING, DESCRIPTION, SCHOOL, COST, USE_RESTRICTION, CLAIM_RESTRICTION,
        COOPERATION, DELAY_COMPENSATION, EV_PARKING, WASHER_LOCATION_UNCLEAR
    }

    record Syntax(String syntax, Topic topic, String trigger) {
    }

    private record Grammar(String basis, List<Syntax> clauses) {
    }

    record Clause(String complex, Topic topic, String trigger, String text, String location) {
    }

    record Reference(String complex, String title, String text, String location) {
    }

    record Reading(Source source, List<Clause> clauses, List<Reference> references, List<String> blockers) {
        boolean complete() {
            return blockers.isEmpty() && !clauses.isEmpty();
        }
    }

    private static final List<Syntax> GRAMMAR = load("/review/site-clause-syntax.json", Grammar.class).clauses();
    private static final Set<String> PERFORMANCE = Set.copyOf(Arrays.asList(load("/review/performance-fields.json", String[].class)));
    private static final Set<String> GREEN = Set.of("측벽", "외벽(직접면)", "외벽(간접면)", "창호(직접면)", "창호(간접면)", "지붕", "바닥(직접면)", "바닥(간접면)", "바닥(층간바닥)", "대기전력차단장치", "일괄소등스위치", "LED조명", "실별온도조절장치", "절수설비", "고효율설비");

    private SiteInstructions() {
    }

    static Reading read(Source source, SupplementaryRules.Identity primary) {
        var clauses = new ArrayList<Clause>();
        var references = new ArrayList<Reference>();
        var blockers = new LinkedHashSet<String>();
        var complexes = SupplyInventory.combinations(primary.source()).keySet();
        for (var part : source.context()) {
            var read = DocumentContextReader.read(source, part);
            if (!read.valid()) {
                blockers.add("SITE_STRUCTURE_UNREAD");
                continue;
            }
            var ratingScopes = ratingScopes(read, part.text(), complexes, blockers);
            for (var table : read.tables()) {
                var grid = table.grid();
                if (grid == null) {
                    blockers.add("SITE_TABLE_INCOMPLETE");
                    continue;
                }
                java.util.function.Function<DocumentContextReader.BoundedCell, String> cell = c -> compact(read.text(part.text(), c.start(), c.end()));
                String at = part.location() + " · 표 " + table.id();
                if (table.columns() == 2 && table.rows() >= 2 && cell.apply(grid[0][0]).equals("단지특성") && cell.apply(grid[0][1]).equals("유의사항")) {
                    for (int r = 1; r < grid.length; r++) {
                        if (grid[r][0].position().rowSpan() != 1 || grid[r][1].position().rowSpan() != 1) {
                            blockers.add("SITE_COMPLEX_SCOPE_UNREAD");
                            continue;
                        }
                        String title = cell.apply(grid[r][0]).replaceFirst("\\([A-Za-z]-[0-9]+블록\\)$", "");
                        if (!complexes.contains(title)) {
                            blockers.add("SITE_RECRUITMENT_MISMATCH");
                            continue;
                        }
                        String body = read.text(part.text(), grid[r][1].start(), grid[r][1].end());
                        for (String raw : body.split("(?m)^\\s*(?=[-□ㅇ])")) {
                            String text = compact(raw);
                            if (text.isEmpty()) continue;
                            var matches = GRAMMAR.stream().filter(s -> s.syntax().replace("{{site}}", primary.site()).equals(text)).toList();
                            if (matches.size() != 1) {
                                blockers.add("SITE_CLAUSE_UNPARSED:" + at + ":" + (r + 1));
                                continue;
                            }
                            var rule = matches.get(0);
                            if (rule.topic() != Topic.HEADING)
                                clauses.add(new Clause(title, rule.topic(), rule.trigger(), raw.strip(), at + " · 행 " + (r + 1) + " 열 2"));
                        }
                    }
                } else if (table.rows() == 1 && table.columns() == 1) {
                    if (!cell.apply(grid[0][0]).matches("\\[별첨[0-9]+](?:단지별특성|주택성능등급및친환경주택의성능수준|시행자및시공업체현황)"))
                        blockers.add("SITE_HEADING_UNREAD");
                } else if (table.columns() == 3 && performance(grid, cell)) {
                    references.add(new Reference(ratingScopes.get(table.id()), "주택 성능등급 · 입주자 의무와 구분해요", rows(grid, cell), at));
                } else if (table.columns() == 4 && green(grid, cell)) {
                    references.add(new Reference(ratingScopes.get(table.id()), "친환경 주택 설비의 적용 여부", rows(grid, cell), at));
                } else if (table.columns() == 5 && cell.apply(grid[0][0]).equals("블록") && cell.apply(grid[0][1]).equals("사업주체")
                    && cell.apply(grid[0][2]).equals("시공업체") && cell.apply(grid[0][3]).equals("연대보증업체") && cell.apply(grid[0][4]).equals("감리회사")
                    && Arrays.stream(grid).skip(1).flatMap(Arrays::stream).allMatch(c -> cell.apply(c).matches("[가-힣A-Za-z0-9㈜().,·\\-]+"))) {
                    references.add(new Reference(null, "시행자·시공업체", rows(grid, cell), at));
                } else blockers.add("SITE_TABLE_ROLE_UNREAD:" + at);
            }
            for (var segment : read.segments()) {
                if (segment.context() != null && !segment.context().cells().isEmpty()) continue;
                String text = compact(read.text(part.text(), segment.start(), segment.end())).replace(primary.site(), "{{site}}");
                if (text.isEmpty()) continue;
                if (!text.matches("(?:「주택공급에관한규칙」제21조에따라주택성능등급및친환경주택의성능수준을다음과같이표기합니다\\.)?(?:■공동주택성능등급\\[\\{\\{site}}[0-9]+단지])?(?:\\*★로해당등급표시)?(?:■친환경주택의성능수준\\[\\{\\{site}}[0-9]+단지])?(?:\\[\\{\\{site}}[0-9]+단지])?(?:\\*적용/미적용여부표기)?"))
                    blockers.add("SITE_OUTSIDE_CONTEXT_UNREAD");
            }
        }
        return new Reading(source, List.copyOf(clauses), List.copyOf(references), List.copyOf(blockers));
    }

    private static Map<Integer, String> ratingScopes(DocumentContextReader.Reading read, String text, Set<String> complexes, Set<String> blockers) {
        var result = new HashMap<Integer, String>();
        var groups = new ArrayList<List<DocumentContextReader.Table>>();
        for (var table : read.tables()) {
            var grid = table.grid();
            if (grid == null || table.columns() != 3) continue;
            String heading = compact(read.text(text, grid[0][0].start(), grid[0][0].end()));
            if (heading.equals("가)소음관련등급")) groups.add(new ArrayList<>());
            if (groups.isEmpty()) {
                blockers.add("SITE_PERFORMANCE_SCOPE_UNREAD");
                continue;
            }
            groups.get(groups.size() - 1).add(table);
        }
        int previous = 0;
        for (var group : groups) {
            int end = group.get(group.size() - 1).end();
            String between = outside(read, text, previous, end);
            var names = Pattern.compile("■공동주택성능등급\\[([^]]+)]").matcher(compact(between)).results().map(m -> m.group(1)).distinct().toList();
            if (names.size() != 1 || !complexes.contains(names.get(0))) blockers.add("SITE_PERFORMANCE_SCOPE_UNREAD");
            else group.forEach(table -> result.put(table.id(), names.get(0)));
            previous = end;
        }
        for (var table : read.tables()) {
            var grid = table.grid();
            if (grid == null || table.columns() != 4 || !compact(read.text(text, grid[0][0].start(), grid[0][0].end())).equals("구분"))
                continue;
            String between = compact(outside(read, text, previous, table.end()));
            var names = Pattern.compile("\\[([^]]+단지)]").matcher(between).results().map(m -> m.group(1)).distinct().toList();
            if (names.size() != 1 || !complexes.contains(names.get(0))) blockers.add("SITE_GREEN_SCOPE_UNREAD");
            else result.put(table.id(), names.get(0));
            previous = table.end();
        }
        return result;
    }

    private static String outside(DocumentContextReader.Reading read, String text, int start, int end) {
        return read.segments().stream().filter(s -> s.start() >= start && s.end() <= end && (s.context() == null || s.context().cells().isEmpty()))
            .map(s -> read.text(text, s.start(), s.end())).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean performance(DocumentContextReader.BoundedCell[][] grid, java.util.function.Function<DocumentContextReader.BoundedCell, String> cell) {
        boolean header = false;
        for (var row : grid) {
            String a = cell.apply(row[0]), b = cell.apply(row[1]), c = cell.apply(row[2]);
            if (row[0] == row[1] && b.matches("(?:[가-마]\\)|[1-5]\\))(?:소음관련등급|구조관련등급|환경관련등급(?:\\(계속\\))?|생활환경등급|화재소방등급)") && c.isEmpty())
                continue;
            if (row[0] == row[1] && a.equals("성능항목") && c.equals("성능등급")) {
                header = true;
                continue;
            }
            if (a.isEmpty() && b.isEmpty() && c.isEmpty()) continue;
            if (!a.matches("[0-9]+[.]?") || !PERFORMANCE.contains(b) || !c.matches("★{1,4}|-")) return false;
        }
        return header;
    }

    private static boolean green(DocumentContextReader.BoundedCell[][] grid, java.util.function.Function<DocumentContextReader.BoundedCell, String> cell) {
        if (!cell.apply(grid[0][0]).equals("구분") || !cell.apply(grid[0][1]).equals("적용여부") || !cell.apply(grid[0][2]).equals("구분") || !cell.apply(grid[0][3]).equals("적용여부"))
            return false;
        for (int r = 1; r < grid.length; r++)
            for (int c = 0; c < 4; c += 2) {
                String field = cell.apply(grid[r][c]), value = cell.apply(grid[r][c + 1]);
                if (field.isEmpty() && value.isEmpty()) continue;
                if (!GREEN.contains(field) || !Set.of("적용", "미적용").contains(value)) return false;
            }
        return true;
    }

    private static String rows(DocumentContextReader.BoundedCell[][] grid, java.util.function.Function<DocumentContextReader.BoundedCell, String> cell) {
        return Arrays.stream(grid).map(row -> Arrays.stream(row).distinct().map(cell).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(" · "))).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String compact(String value) {
        return SupplementaryRules.compact(value);
    }

    private static <T> T load(String path, Class<T> type) {
        try (var in = SiteInstructions.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing site clause grammar");
            return new ObjectMapper().readValue(in, type);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
