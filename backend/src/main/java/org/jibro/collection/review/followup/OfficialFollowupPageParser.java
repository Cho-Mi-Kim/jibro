package org.jibro.collection.review.followup;

import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.jibro.notice.OfficialCorrectionLink;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jsoup.Jsoup;

/** Reads the observed LH list/metadata template without running remote JavaScript. */
final class OfficialFollowupPageParser {
    record Row(String url, String title, LocalDate publishedDate) {}
    record Page(int total, int page, int pages, List<Row> rows) {}
    private static final Pattern TOTAL = Pattern.compile("전체\\s*([0-9]+)\\s*건\\s*([0-9]+)\\s*/\\s*([0-9]+)\\s*페이지");
    private OfficialFollowupPageParser() {}

    static Page search(String html, int requestedPage) {
        var doc = Jsoup.parse(html);
        var totals = doc.select(".bbs_total");
        if (totals.size() != 1) throw invalid();
        var count = TOTAL.matcher(totals.first().text());
        if (!count.matches()) throw invalid();
        int total = Integer.parseInt(count.group(1)), page = Integer.parseInt(count.group(2)), pages = Integer.parseInt(count.group(3));
        if (total < 0 || total > 1_000_000 || pages < 0 || pages > 100_000
            || (total > 0 && (page != requestedPage || pages < page)) || (total == 0 && pages != 0)) throw invalid();
        var rows = new ArrayList<Row>();
        var ids = new HashSet<String>();
        for (var row : doc.select(".bbs_ListA tbody tr")) {
            var link = row.selectFirst(".slpaInfoBtn");
            if (link == null) continue;
            String id = link.attr("data-id1"), system = link.attr("data-id2"), title = link.text().strip();
            if (!id.matches("[0-9]{1,20}") || !system.matches("[0-9]{2}") || title.isBlank() || title.length() > 500) throw invalid();
            var dateCells = row.select("td").stream().map(e -> e.text().strip())
                .filter(t -> t.matches("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")).toList();
            if (dateCells.size() != 1) throw invalid();
            String url = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=" + id
                + "&ccrCnntSysDsCd=" + system + "&slBbsKdCd=03&mi=1079";
            if (!ids.add(url)) throw invalid();
            rows.add(new Row(url, title, LocalDate.parse(dateCells.get(0).replace('.', '-'))));
        }
        if (rows.size() > 100 || (total > 0 && rows.isEmpty()) || rows.size() > total) throw invalid();
        return new Page(total, page, pages, List.copyOf(rows));
    }

    static LocalDate publication(String html) {
        return metadataDate(html, "게시일");
    }

    static LocalDate correctionNoticeDate(String html) {
        return metadataDate(html, "공고일");
    }

    private static LocalDate metadataDate(String html, String name) {
        var doc = Jsoup.parse(html);
        var dates = new ArrayList<LocalDate>();
        for (var item : doc.select(".bbs_ViewA .bbsV_data li")) {
            var label = item.selectFirst("strong");
            if (label != null && label.text().strip().equals(name)) {
                var copy = item.clone(); copy.select("strong").remove();
                dates.add(LocalDate.parse(copy.text().strip().replace('.', '-')));
            }
        }
        if (dates.size() != 1) throw new IllegalArgumentException("FOLLOWUP_PUBLICATION_DATE_UNREAD");
        return dates.get(0);
    }

    /** A candidate only. The strict two-page verifier remains mandatory before accepting it. */
    static Optional<String> correctionCandidate(String primaryUrl, String html) {
        var identity = OfficialCorrectionLink.identity(primaryUrl);
        String scripts = String.join("\n", Jsoup.parse(html).select("script:not([src])").stream().map(e -> e.data()).toList());
        var current = Pattern.compile("(?m)^\\s*var\\s+currPanId\\s*=\\s*['\"]([0-9]{1,30})['\"]\\s*;").matcher(scripts);
        if (!current.find()) return Optional.empty();
        String target = current.group(1);
        if (current.find()) throw new IllegalArgumentException("CORRECTION_CANDIDATE_AMBIGUOUS");
        if (target.equals(identity.panId())) return Optional.empty();
        return Optional.of("https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=" + target
            + "&ccrCnntSysDsCd=" + identity.system() + "&uppAisTpCd=" + identity.category() + "&aisTpCd=" + identity.type() + "&mi=1026");
    }

    static boolean linksPrimary(String primaryUrl, String html, String sourceUrl) {
        var expected = OfficialCorrectionLink.identity(primaryUrl);
        return primaryLinks(html, sourceUrl).equals(Set.of(expected));
    }

    static Set<OfficialCorrectionLink.Identity> primaryLinks(String html, String sourceUrl) {
        var links = new LinkedHashSet<OfficialCorrectionLink.Identity>();
        for (var link : Jsoup.parse(html, sourceUrl).select(".bbs_ViewA .bbsV_cont a[href]")) {
            try {
                String url = URI.create(sourceUrl).resolve(link.attr("href")).toString();
                links.add(OfficialCorrectionLink.identity(url));
            } catch (IllegalArgumentException ignored) { /* Navigation or unrelated link is not a recruitment identity. */ }
        }
        return Set.copyOf(links);
    }

    static List<Part> context(String html, String url) {
        var doc = Jsoup.parse(html, url);
        var box = doc.selectFirst(".bbs_ViewA");
        if (box == null || box.selectFirst("h3") == null || box.selectFirst(".bbsV_cont") == null) throw invalid();
        var parts = new ArrayList<Part>();
        parts.add(new Part("공식 후속 공지 제목", box.selectFirst("h3").text().strip()));
        var metadata = box.selectFirst(".bbsV_data");
        if (metadata != null) {
            var copy = metadata.clone();
            copy.select("li").stream().filter(li -> li.selectFirst("strong") != null
                && li.selectFirst("strong").text().strip().equals("조회수")).toList().forEach(org.jsoup.nodes.Element::remove);
            if (!copy.text().isBlank()) parts.add(new Part("공식 후속 공지 게시 정보", copy.text()));
        }
        var body = box.selectFirst(".bbsV_cont").clone();
        var parsed = OfficialDetailParser.parse("<main>" + body.outerHtml() + "</main>", url);
        for (var part : parsed.parts()) parts.add(new Part("공식 후속 공지 본문 · " + part.location(), part.text()));
        // Preserve an otherwise plain text body; never drop it because it has no paragraph markup.
        if (parsed.parts().isEmpty() && !body.text().isBlank()) parts.add(new Part("공식 후속 공지 본문", body.wholeText().strip()));
        return List.copyOf(parts);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FOLLOWUP_SEARCH_TEMPLATE_UNVERIFIED"); }
}
