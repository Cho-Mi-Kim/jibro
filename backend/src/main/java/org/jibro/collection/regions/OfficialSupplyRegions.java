package org.jibro.collection.regions;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.notice.OfficialCorrectionLink;
import org.jibro.notice.SupplyRegionNames;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * Only named supply-area fields, property addresses and municipality columns carry scope.
 */
final class OfficialSupplyRegions {
    record Proof(String url, String field, String value) {
    }

    private static final Map<String, String> NAMES = SupplyRegionNames.officialNames();
    private static final Set<String> COLUMNS = Set.of("시도", "시·도", "지역(지자체)", "지자체명", "모집지역", "공급지역", "지역명", "소재지", "주소");
    private static final Pattern FIELD = Pattern.compile("^(소재지|사업대상지역|공급지역|모집지역|공급대상지역)\\s*[:：]\\s*(.+)$");
    private static final Pattern LH_LOCALITY = Pattern.compile("^(?:전세|매입다가구|매입임대)\\(([^()]+)\\)$");
    private static final Pattern EXCEPTION = Pattern.compile("제외|이외|\\s외\\s*(?:지역|시군)|거주|신청자|본부|접수처|문의처|해당없음");

    private OfficialSupplyRegions() {
    }

    static Map<String, Proof> read(String html, String url) {
        var dom = Jsoup.parse(html, url);
        var ids = dom.select("form#wrtancViewForm input[name=panId]");
        if (ids.size() != 1 || !OfficialCorrectionLink.identity(url).panId().equals(ids.first().val()))
            throw new IllegalArgumentException("SUPPLY_NOTICE_MISMATCH");
        var result = new TreeMap<String, Proof>();
        for (var heading : dom.select("section > h3, section > h2, .panDtlInfo > h3")) {
            if (!Set.of("공급정보", "공급대상", "공급대상주택").contains(heading.text().replace(" ", ""))) continue;
            var section = heading.parent();
            for (var item : section.select("li,dd,p")) {
                var field = FIELD.matcher(item.text());
                if (field.matches()) addField(result, url, field.group(1), field.group(2));
            }
            for (var table : section.select("table")) table(result, url, table);
        }
        return result;
    }

    private static void table(Map<String, Proof> result, String url, Element table) {
        var head = table.selectFirst("thead > tr");
        if (head == null) return;
        // Row-spanning region headers on LH's two-line tables still start at their logical column.
        int index = 0;
        for (var th : head.children()) {
            String field = th.text().replace(" ", "");
            if (COLUMNS.contains(field) && !th.hasAttr("colspan")) {
                for (var row : table.select("tbody > tr")) {
                    var cells = row.children();
                    if (cells.size() <= index || cells.stream().anyMatch(c -> c.hasAttr("rowspan") || c.hasAttr("colspan")))
                        continue;
                    addField(result, url, field, cells.get(index).text());
                }
            }
            String span = th.attr("colspan");
            index += span.matches("[1-9][0-9]?") ? Integer.parseInt(span) : 1;
        }
    }

    /**
     * An attachment may be narrative. Accept only a complete, standalone list of named supply regions.
     */
    static Map<String, Proof> attachmentFields(String url, List<Part> parts) {
        var result = new TreeMap<String, Proof>();
        for (var part : parts) {
            for (String line : part.text().split("\\R")) {
                var field = FIELD.matcher(line.strip().replaceFirst("^(?:[○◦▪■◆※-]|[0-9]{1,2}[.)])\\s*", ""));
                if (!field.matches() || field.group(1).equals("소재지")) continue;
                String value = field.group(2).strip();
                String[] tokens = value.split("\\s*(?:[,·/、]|\\s및\\s)\\s*");
                if (Arrays.stream(tokens).anyMatch(token -> !NAMES.containsKey(token) && !token.equals("전국"))) continue;
                for (String token : tokens) {
                    var one = new TreeMap<String, Proof>();
                    addField(one, url, field.group(1), token);
                    for (String region : one.keySet())
                        result.putIfAbsent(region, new Proof(url, part.location(), line.strip()));
                }
            }
        }
        return result;
    }

    static void addField(Map<String, Proof> result, String url, String field, String value) {
        if (value.isBlank() || value.length() > 400 || EXCEPTION.matcher(value).find()) return;
        for (String part : value.split("[,·/;、]|\\s+및\\s+")) {
            String token = part.strip();
            var wrapped = LH_LOCALITY.matcher(token);
            boolean encoded = wrapped.matches();
            if (encoded) token = wrapped.group(1);
            if (token.equals("전국") || token.equals("전국 전 지역")) {
                for (var region : NAMES.values()) result.putIfAbsent(region, new Proof(url, field, value));
                result.putIfAbsent("전국", new Proof(url, field, value));
            } else {
                // A short ambiguous name such as 광주시, without a province, is not a province address.
                for (var entry : NAMES.entrySet()) {
                    String name = entry.getKey();
                    if (token.equals(name) || token.startsWith(name + " ") ||
                            (encoded && name.length() == 2 && token.startsWith(name) && token.substring(2).matches("[가-힣]+[시군구]"))) {
                        result.putIfAbsent(entry.getValue(), new Proof(url, field, value));
                        break;
                    }
                }
            }
        }
    }
}
