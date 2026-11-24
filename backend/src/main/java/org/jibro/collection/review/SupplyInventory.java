package org.jibro.collection.review;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.local.DocumentContextReader;
import org.jibro.collection.review.local.SpreadsheetContextReader;

/**
 * Compares every located supply row, its applicant column and the recruitment's limiting footnotes.
 */
final class SupplyInventory {
    record Unit(String complex, String block, String room, String shape, BigDecimal area, Set<String> audiences,
                String location) {
    }

    record Difference(String field, String primary, String inventory, String location) {
    }

    record Reading(List<Unit> units, List<Difference> differences, List<String> blockers) {
        boolean complete() {
            return blockers.isEmpty() && !units.isEmpty();
        }
    }

    private record CountScope(String location, int table, int row, int column, int rowSpan, int columnSpan) {
    }

    private record Supply(String complex, String shapes, Map<String, BigDecimal> areas, Set<String> audiences,
                          int count, CountScope countScope) {
    }

    private record Primary(List<Supply> rows, Map<String, Set<String>> allowedShapes, List<String> blockers) {
    }

    private static final Set<String> FIELDS = Set.of("연번", "주소", "단지", "동", "호", "전용면적(㎡)", "공급형");
    private static final Set<String> AUDIENCES = Set.of("대학생", "청년", "주거급여수급자");
    private static final Pattern SHAPE = Pattern.compile("([0-9]+)([A-Z]?)");

    private static final class UnreadUnit extends IllegalArgumentException {
        UnreadUnit(String field) {
            super(field);
        }
    }

    private SupplyInventory() {
    }

    static Reading read(Source source, Source primary) {
        var official = primary(primary);
        var blockers = new LinkedHashSet<>(official.blockers());
        var units = new ArrayList<Unit>();
        var differences = new ArrayList<Difference>();
        var counted = new LinkedHashMap<CountScope, Integer>();
        var addresses = new HashSet<String>();
        if (official.rows().isEmpty()) blockers.add("SUPPLY_PRIMARY_TABLE_UNREAD");
        for (var part : source.context()) {
            var sheet = SpreadsheetContextReader.read(part);
            if (!sheet.complete()) {
                blockers.add("SUPPLY_INVENTORY_STRUCTURE_UNREAD");
                continue;
            }
            var header = sheet.rows().stream().filter(r -> r.cells().size() == FIELDS.size()
                && new HashSet<>(r.cells().values().stream().map(SupplementaryRules::compact).toList()).equals(FIELDS)).toList();
            if (header.size() != 1) {
                blockers.add("SUPPLY_INVENTORY_HEADER_UNREAD");
                continue;
            }
            var columns = new LinkedHashMap<String, String>();
            header.get(0).cells().forEach((column, label) -> columns.put(SupplementaryRules.compact(label), column));
            String firstColumn = columns.get("연번"), lastColumn = columns.get("공급형");
            String titleComplex = null;
            for (var row : sheet.rows().stream().filter(r -> r.number() < header.get(0).number()).toList()) {
                if (row.cells().size() != 1 || !row.cells().containsKey(firstColumn)) {
                    blockers.add("SUPPLY_INVENTORY_PREFACE_UNREAD");
                    continue;
                }
                String text = compact(row.cells().get(firstColumn));
                var title = Pattern.compile("^(.+단지)공급공고관련공실현황$").matcher(text);
                if (title.matches() && titleComplex == null) titleComplex = title.group(1);
                else if (!text.equals("※실제계약진행과정에서현장여건및기타사정에따라공급대상동·호수등공실현황이변경될수있습니다.")
                    && !text.equals("※입주자격(대학생·청년·주거급여수급자등)에따라신청및계약이가능한호수가다를수있으므로,세부공급가능호수는계약안내시확인하시기바랍니다."))
                    blockers.add("SUPPLY_INVENTORY_PREFACE_UNREAD");
            }
            if (titleComplex == null) blockers.add("SUPPLY_INVENTORY_COMPLEX_UNREAD");
            for (String merge : sheet.merges()) {
                var region = Pattern.compile("^([A-Z]+)([0-9]+):([A-Z]+)([0-9]+)$").matcher(merge);
                if (!region.matches() || !region.group(1).equals(firstColumn) || !region.group(3).equals(lastColumn)
                    || !region.group(2).equals(region.group(4)) || Integer.parseInt(region.group(2)) >= header.get(0).number())
                    blockers.add("SUPPLY_INVENTORY_DATA_MERGE_UNREAD");
            }
            int sequence = 0;
            for (var row : sheet.rows().stream().filter(r -> r.number() > header.get(0).number()).toList()) {
                if (!row.cells().keySet().equals(header.get(0).cells().keySet())) {
                    blockers.add("SUPPLY_INVENTORY_ROW_INCOMPLETE");
                    continue;
                }
                var cells = new LinkedHashMap<String, String>();
                columns.forEach((field, column) -> cells.put(field, compact(row.cells().get(column))));
                String at = part.location() + " · 행 " + row.number();
                try {
                    if (Integer.parseInt(cells.get("연번")) != ++sequence || !cells.get("동").matches("[0-9]+") || !cells.get("호").matches("[0-9]+")
                        || !cells.get("주소").matches("[가-힣]+[0-9]*(?:로|길)[0-9]+(?:번길[0-9]+)?(?:-[0-9]+)?\\([가-힣0-9]+동\\)"))
                        throw new UnreadUnit("ADDRESS_OR_IDENTIFIER");
                    String complex = cells.get("단지").replaceFirst("\\(행복주택\\)$", "");
                    if (!complex.equals(titleComplex) || !addresses.add(complex + ":" + cells.get("동") + ":" + cells.get("호")))
                        throw new UnreadUnit("COMPLEX_OR_DUPLICATE");
                    var shape = Pattern.compile("^([0-9]+[A-Z]?(?:/[0-9]+[A-Z]?)*)\\(([^()]+)\\)$").matcher(cells.get("공급형"));
                    if (!shape.matches()) throw new UnreadUnit("SHAPE");
                    var audiences = audiences(shape.group(2));
                    if (audiences.isEmpty()) throw new UnreadUnit("AUDIENCE");
                    BigDecimal area = new BigDecimal(cells.get("전용면적(㎡)"));
                    if (area.signum() <= 0) throw new UnreadUnit("AREA");
                    var unit = new Unit(complex, cells.get("동"), cells.get("호"), shape.group(1), area, audiences, at);
                    units.add(unit);
                    var shapes = Set.copyOf(Arrays.asList(unit.shape().split("/")));
                    var matches = official.rows().stream().filter(s -> s.complex().endsWith(complex) && s.areas().keySet().containsAll(shapes)).toList();
                    if (matches.size() != 1) {
                        differences.add(new Difference("공급 단지·주택형", "본문 공급표", complex + " " + unit.shape(), at));
                        continue;
                    }
                    var match = matches.get(0);
                    counted.merge(match.countScope(), 1, Integer::sum);
                    if (shapes.stream().noneMatch(name -> match.areas().get(name).compareTo(area) == 0))
                        differences.add(new Difference("전용면적", shapes.stream().map(name -> match.areas().get(name).toPlainString()).toList().toString(), area.toPlainString(), at));
                    Set<String> expected = new LinkedHashSet<>(match.audiences());
                    var numericSize = SHAPE.matcher(unit.shape());
                    if (!numericSize.find()) throw new UnreadUnit("SHAPE");
                    String size = numericSize.group(1);
                    expected.removeIf(a -> official.allowedShapes().containsKey(a) && !official.allowedShapes().get(a).contains(size));
                    if (!expected.equals(audiences))
                        differences.add(new Difference("주택형별 신청 대상", expected.toString(), audiences.toString(), at));
                } catch (UnreadUnit invalid) {
                    blockers.add("SUPPLY_INVENTORY_" + invalid.getMessage() + "_UNREAD:" + row.number());
                } catch (NumberFormatException invalid) {
                    blockers.add("SUPPLY_INVENTORY_NUMBER_UNREAD:" + row.number());
                }
            }
        }
        var comparedCounts = new HashSet<CountScope>();
        for (var row : official.rows())
            if (comparedCounts.add(row.countScope()) && row.count() != counted.getOrDefault(row.countScope(), 0)) {
                String targets = official.rows().stream().filter(s -> s.countScope().equals(row.countScope()))
                    .map(s -> s.complex() + " " + s.shapes()).collect(java.util.stream.Collectors.joining(" / "));
                differences.add(new Difference("모집 호수", targets + " " + row.count(), "목록 " + counted.getOrDefault(row.countScope(), 0), "전체 목록"));
            }
        return new Reading(List.copyOf(units), List.copyOf(differences), List.copyOf(blockers));
    }

    static Map<String, Set<String>> combinations(Source source) {
        var result = new LinkedHashMap<String, Set<String>>();
        var read = primary(source);
        if (!read.blockers().isEmpty()) return Map.of();
        for (var row : read.rows())
            result.computeIfAbsent(row.complex(), ignored -> new LinkedHashSet<>()).addAll(row.areas().keySet());
        return result;
    }

    private static Primary primary(Source source) {
        var supplies = new ArrayList<Supply>();
        var allowed = new LinkedHashMap<String, Set<String>>();
        var blockers = new LinkedHashSet<String>();
        for (var part : source.context()) {
            var reading = DocumentContextReader.read(source, part);
            if (!reading.valid()) {
                blockers.add("SUPPLY_PRIMARY_STRUCTURE_UNREAD");
                continue;
            }
            for (String line : reading.text(part.text(), 0, part.text().length()).lines().toList()) {
                String text = compact(line).replaceFirst("^※", "");
                if (!text.contains("형") || !text.contains("신청가능") || !text.contains(":")) continue;
                for (String rule : text.split(",")) {
                    var restriction = Pattern.compile("^([가-힣·]+):([0-9]+형(?:및[0-9]+형)*)(?:만)?신청가능[.]?$").matcher(rule);
                    if (!restriction.matches()) {
                        blockers.add("SUPPLY_PRIMARY_FOOTNOTE_UNREAD");
                        continue;
                    }
                    var targets = audiences(restriction.group(1));
                    var sizes = Set.copyOf(Arrays.asList(restriction.group(2).replace("형", "").split("및")));
                    if (targets.isEmpty()) {
                        blockers.add("SUPPLY_PRIMARY_FOOTNOTE_UNREAD");
                        continue;
                    }
                    for (String target : targets) {
                        var old = allowed.putIfAbsent(target, sizes);
                        if (old != null && !old.equals(sizes)) blockers.add("SUPPLY_PRIMARY_FOOTNOTE_CONFLICT");
                    }
                }
            }
            for (var table : reading.tables()) {
                var grid = table.grid();
                if (grid == null || table.rows() < 2) continue;
                var columns = new HashMap<String, Integer>();
                for (int r = 0; r < Math.min(3, grid.length); r++)
                    for (int c = 0; c < grid[r].length; c++) {
                        String label = cell(reading, part, grid[r][c]);
                        if (Set.of("단지", "공급형별", "공급대상(계층)", "주거전용", "금회모집호수").contains(label)) columns.put(label, c);
                    }
                if (columns.size() != 5) continue;
                for (int r = 0; r < grid.length; r++) {
                    String shapes = cell(reading, part, grid[r][columns.get("공급형별")]);
                    if (!shapes.matches("[0-9]+[A-Z]?(?:/[A-Z])?")) continue;
                    var names = new ArrayList<String>();
                    names.add(shapes.split("/")[0]);
                    if (shapes.contains("/")) names.add(shapes.replaceFirst("[A-Z]/", ""));
                    var areas = cell(reading, part, grid[r][columns.get("주거전용")]).split("/");
                    var groups = audiences(cell(reading, part, grid[r][columns.get("공급대상(계층)")]));
                    try {
                        if (areas.length != names.size() || groups.isEmpty()) throw new IllegalArgumentException();
                        var values = new LinkedHashMap<String, BigDecimal>();
                        for (int i = 0; i < names.size(); i++) values.put(names.get(i), new BigDecimal(areas[i]));
                        var countCell = grid[r][columns.get("금회모집호수")].position();
                        var scope = new CountScope(part.location(), table.id(), countCell.row(), countCell.column(), countCell.rowSpan(), countCell.columnSpan());
                        supplies.add(new Supply(cell(reading, part, grid[r][columns.get("단지")]), shapes, Map.copyOf(values), groups,
                            Integer.parseInt(cell(reading, part, grid[r][columns.get("금회모집호수")])), scope));
                    } catch (IllegalArgumentException invalid) {
                        blockers.add("SUPPLY_PRIMARY_ROW_UNREAD");
                    }
                }
            }
        }
        return new Primary(List.copyOf(supplies), Map.copyOf(allowed), List.copyOf(blockers));
    }

    private static String cell(DocumentContextReader.Reading reading, Part part, DocumentContextReader.BoundedCell cell) {
        return compact(reading.text(part.text(), cell.start(), cell.end()));
    }

    private static Set<String> audiences(String text) {
        var labels = new LinkedHashSet<>(Arrays.asList(compact(text).split("[,·]")));
        return AUDIENCES.containsAll(labels) ? Set.copyOf(labels) : Set.of();
    }

    private static String compact(String value) {
        return SupplementaryRules.compact(value);
    }
}
