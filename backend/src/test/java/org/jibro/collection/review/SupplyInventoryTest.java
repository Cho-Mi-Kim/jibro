package org.jibro.collection.review;

import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class SupplyInventoryTest {
    private final ObjectMapper json = new ObjectMapper();

    Source primary() throws Exception {
        try (var in = getClass().getResourceAsStream("/review/suwon-main-variants.json")) {
            return json.treeToValue(json.readTree(in).get("fragments"), Source.class);
        }
    }

    Source inventory() throws Exception {
        try (var in = getClass().getResourceAsStream("/review/suwon-supply-inventory.json")) {
            return json.readValue(in, Source.class);
        }
    }

    private Source replace(Source source, String from, String to) {
        var parts = source.context().stream().map(p -> new Part(p.location(), p.text().replace(from, to))).toList();
        assertNotEquals(source.context(), parts);
        return new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), source.excerpts(), parts);
    }

    @Test
    void everyActualUnitAndApplicantColumnAreComparedWithTheLimitingFootnote() throws Exception {
        var result = SupplyInventory.read(inventory(), primary());
        assertTrue(result.complete(), result.blockers().toString());
        assertEquals(List.of(), result.differences());
        assertEquals(160, result.units().size());
        assertEquals(129, result.units().stream().filter(u -> u.shape().startsWith("14")).count());
        assertTrue(result.units().stream().filter(u -> u.shape().startsWith("14")).allMatch(u -> u.audiences().equals(Set.of("대학생", "청년"))));
        assertTrue(result.units().stream().filter(u -> u.shape().startsWith("26")).allMatch(u -> u.audiences().contains("주거급여수급자")));
    }

    @Test
    void movingAnApplicantToADifferentShapeIsADifferenceRatherThanAnInventoryOnlyFile() throws Exception {
        var altered = replace(inventory(), "G7: 14A(대학생,청년)", "G7: 14A(대학생,청년,주거급여수급자)");
        var result = SupplyInventory.read(altered, primary());
        assertTrue(result.complete(), result.blockers().toString());
        assertTrue(result.differences().stream().anyMatch(d -> d.field().equals("주택형별 신청 대상") && d.location().endsWith("행 7")));
    }

    @Test
    void anUnknownFootnoteHiddenRowOrIncompleteCellDoesNotProveCoverage() throws Exception {
        for (var altered : List.of(replace(inventory(), "A4: ※ 입주자격", "A4: ※ 모든 신청자는 추가 보증금을 내야 합니다. 입주자격"),
            replace(inventory(), "행 7\n", "행 7 [숨김]\n"), replace(inventory(), "G7: 14A(대학생,청년)", "H7: 14A(대학생,청년)"))) {
            assertFalse(SupplyInventory.read(altered, primary()).complete());
        }
    }

    @Test
    void splittingAComplexAcrossSheetsCannotHideADuplicateUnit() throws Exception {
        var original = inventory();
        String text = original.context().get(0).text();
        int start = text.indexOf("행 7\n"), next = text.indexOf("행 8\n");
        String preface = text.substring(0, start);
        String first = text.substring(start, next);
        String remaining = text.substring(next).replace("E8: 207", "E8: 112");
        var matcher = java.util.regex.Pattern.compile("(?m)^(행 )?([A-G]?)([0-9]+)(: [^\\r\\n]*|$)").matcher(remaining);
        var shifted = new StringBuffer();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(3));
            String value = matcher.group(4);
            if (matcher.group(2).equals("A") && value.startsWith(": "))
                value = ": " + (Integer.parseInt(value.substring(2)) - 1);
            matcher.appendReplacement(shifted, java.util.regex.Matcher.quoteReplacement((matcher.group(1) == null ? "" : matcher.group(1))
                + matcher.group(2) + (number - 1) + value));
        }
        matcher.appendTail(shifted);
        var parts = List.of(new Part("Excel 워크시트 앞 목록 (행·열 위치)", preface + first),
            new Part("Excel 워크시트 뒤 목록 (행·열 위치)", preface + shifted), original.context().get(1));
        var altered = new Source(original.label(), original.url(), original.sha256(), original.status(), original.message(), original.excerpts(), parts);
        var result = SupplyInventory.read(altered, primary());
        assertFalse(result.complete());
        assertTrue(result.blockers().contains("SUPPLY_INVENTORY_COMPLEX_OR_DUPLICATE_UNREAD:7"), result.blockers().toString());
    }
}
