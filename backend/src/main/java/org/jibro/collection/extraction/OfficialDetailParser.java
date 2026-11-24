package org.jibro.collection.extraction;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;

import static org.jibro.collection.extraction.ExtractionModels.*;

public final class OfficialDetailParser {
    public record Detail(List<Part> parts, List<Link> attachments) {
    }

    private static final Pattern FILE_ID = Pattern.compile("(?:lhFileDown|fnFileDown|fileDownLoad|fileDown|downloadFile)\\s*\\(\\s*['\"]?(\\d{1,20})['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTICE_SECTION = Pattern.compile("공급\\s*일정|모집\\s*일정|신청\\s*(?:자격|방법)|기타\\s*사항|공고\\s*내용|제출\\s*서류|접수처\\s*정보");

    private OfficialDetailParser() {
    }

    public static Detail parse(String html, String url) {
        var document = Jsoup.parse(html, url);
        document.select("script,style,noscript,header,footer,nav").remove();
        var links = attachmentLinks(document, url, 50);
        var selected = document.select(".bbs_ViewA, .panDtlInfo");
        org.jsoup.nodes.Element body;
        if (!selected.isEmpty()) {
            body = new org.jsoup.nodes.Element("main");
            for (var section : selected) {
                // The property-by-property supply grid is huge and not a shared applicant criterion.
                var heading = section.selectFirst("h3");
                if (heading != null && heading.text().strip().equals("공급정보")) continue;
                body.appendChild(section.clone());
            }
            // LH renders the actual timetable and applicant notes as siblings of its metadata box.
            // Keeping only .bbs_ViewA/.panDtlInfo silently lost those governing fields.
            for (var section : document.select("section")) {
                var heading = section.selectFirst("h2,h3,h4");
                if (heading == null || !NOTICE_SECTION.matcher(heading.text()).find()
                        || selected.stream().anyMatch(parent -> parent == section || section.parents().contains(parent)))
                    continue;
                body.appendChild(section.clone());
            }
        } else {
            body = document.selectFirst("main, #contents, #content, .contents");
            if (body == null) body = document.body();
        }
        body.select("[hidden], [style*=display:none], [style*=display: none], .bbsV_atchmnfl").remove();
        List<Part> parts = new ArrayList<>();
        int row = 0, chars = 0;
        var contextBody = body;
        for (var element : body.select("tr,p,li,dd,pre")) {
            // A row owns its cells, and a list item owns its paragraph/pre children. Extract once.
            if (element.parents().stream().anyMatch(parent -> parent != contextBody
                    && Set.of("tr", "p", "li", "dd", "pre").contains(parent.tagName()))) continue;
            String text = element.tagName().equals("tr") ? tableRow(element) : element.wholeText().strip();
            text = text.replaceAll("[\\p{Zs}\\t]+", " ").replaceAll("\\n[ ]+", "\n");
            if (text.isBlank()) continue;
            chars += text.length();
            if (++row > 1000 || chars > 300_000) throw new IllegalArgumentException("OFFICIAL_CONTEXT_LIMIT");
            parts.add(new Part("공식 상세 본문 · 항목 " + row, text));
        }
        return new Detail(parts, links);
    }

    /**
     * Read attachments even when a large notice's body cannot be extracted.
     */
    public static List<Link> attachments(String html, String url) {
        var document = Jsoup.parse(html, url);
        document.select("header,footer,nav").remove();
        return attachmentLinks(document, url, 2048);
    }

    private static List<Link> attachmentLinks(org.jsoup.nodes.Document document, String url, int limit) {
        var links = new LinkedHashMap<String, Link>();
        for (var a : document.select("a[href],button[onclick],a[onclick]")) {
            String href;
            String label = a.text().strip();
            var matcher = FILE_ID.matcher(a.attr("onclick") + " " + a.attr("href"));
            try {
                href = matcher.find() ? "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + matcher.group(1)
                        : URI.create(url).resolve(a.attr("href")).toString();
                URI uri = URI.create(href);
                if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                    continue;
                String path = uri.getPath().toLowerCase(Locale.ROOT);
                if (!(path.endsWith("/lhfile.do") || path.matches(".*\\.(pdf|hwp|hwpx|zip|xls|xlsx)$"))) continue;
                if (href.length() <= 2000)
                    links.putIfAbsent(href, new Link(label.isBlank() ? "공식 첨부" : label.substring(0, Math.min(200, label.length())), href));
            } catch (IllegalArgumentException ignored) { /* Not a supported official attachment link. */ }
            if (links.size() > limit) throw new IllegalArgumentException("OFFICIAL_ATTACHMENTS_LIMIT");
        }
        return List.copyOf(links.values());
    }

    private static String tableRow(org.jsoup.nodes.Element row) {
        var cells = row.children().stream().filter(cell -> Set.of("th", "td").contains(cell.tagName())).toList();
        if (cells.isEmpty()) return row.wholeText().strip();
        var table = row.closest("table");
        var header = table == null ? null : table.selectFirst("thead tr");
        if (header != null && cells.stream().allMatch(cell -> cell.tagName().equals("td"))) {
            var headers = header.children();
            if (headers.size() == cells.size() && table.select("[rowspan], [colspan]").isEmpty()) {
                List<String> fields = new ArrayList<>();
                for (int index = 0; index < cells.size(); index++) {
                    fields.add(headers.get(index).text().strip() + " : " + cells.get(index).wholeText().strip());
                }
                return String.join("\n", fields);
            }
        }
        // Explicit separators retain a label/value boundary even when HTML contains no whitespace.
        return cells.stream().map(cell -> cell.wholeText().strip()).reduce((a, b) -> a + " : " + b).orElse("");
    }
}
