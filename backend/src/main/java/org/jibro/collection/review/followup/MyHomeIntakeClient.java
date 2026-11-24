package org.jibro.collection.review.followup;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jibro.notice.OfficialCorrectionLink;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Public MyHome search, joined to LH by its official notice identity, never by title alone. */
@Component
public class MyHomeIntakeClient {
    private static final String BASE = "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtc";
    private static final Pattern LINK = Pattern.compile("window\\.open\\(['\"](https://apply\\.lh\\.or\\.kr/[^'\"]+)['\"]");
    private static final Pattern DATE = Pattern.compile("(20\\d\\d)년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");
    private final ObjectMapper json;

    public MyHomeIntakeClient(ObjectMapper json) {
        this.json = json;
    }

    public List<Window> find(OfficialFollowupModels.Target target) throws IOException {
        var detail = detail(target);
        return detail == null ? List.of() : windows(detail, OfficialCorrectionLink.identity(target.primaryUrl()), detail.location());
    }

    Document detail(OfficialFollowupModels.Target target) throws IOException {
        if (target.publishedDate() == null || target.title() == null || target.title().isBlank()) return null;
        var identity = OfficialCorrectionLink.identity(target.primaryUrl());
        var session = Jsoup.newSession().timeout(12000).maxBodySize(2_000_000).followRedirects(false);
        var form = session.newRequest(BASE + "View.do").get().selectFirst("form#frm");
        if (form == null) throw new IOException("MYHOME_SEARCH_UNAVAILABLE");
        String month = target.publishedDate().format(DateTimeFormatter.ofPattern("yyyyMM"));
        var terms = new java.util.LinkedHashSet<String>();
        terms.add(target.title());
        OfficialFollowupRelationship.queryTerm(target).ifPresent(terms::add);
        for (var term : terms) {
            var ids = noticeIds(session, identity, month, term);
            if (ids.size() > 1) throw new IOException("MYHOME_AMBIGUOUS_RECRUITMENT");
            if (!ids.isEmpty()) {
                String url = BASE + "DetailView.do?pblancId=" + ids.iterator().next();
                return session.newRequest(url).get();
            }
        }
        return null;
    }

    private java.util.Set<String> noticeIds(org.jsoup.Connection session, OfficialCorrectionLink.Identity identity,
                                           String month, String term) throws IOException {
        var request = session.newRequest(BASE + "List.do").referrer(BASE + "View.do")
            .header("X-Requested-With", "XMLHttpRequest").header("Accept", "application/json")
            .ignoreContentType(true).data("pageIndex", "1", "srchPblancNm", term,
                "srchRcritPblancDeYearMtBegin", month, "srchRcritPblancDeYearMtEnd", month);
        for (String field : List.of("searchTyId", "srchSuplyTy", "srchHouseTy", "srchSuplyPrvuseAr",
            "srchBassMtRntchrg", "srchPrgrStts", "srchbrtcCode", "srchsignguCode")) request.data(field, "");
        var page = json.readTree(request.method(org.jsoup.Connection.Method.POST).execute().body());
        if (!page.path("resultList").isArray()) throw new IOException("MYHOME_LIST_UNAVAILABLE");
        var ids = new java.util.LinkedHashSet<String>();
        if (page.path("resultCnt").asInt() > page.path("resultList").size()) return ids;
        for (var item : page.path("resultList")) {
            if (!"LH".equals(item.path("suplyInsttNm").asString()) || !same(identity, item.path("url").asString())) continue;
            String id = item.path("pblancId").asString();
            if (id.matches("[0-9]{1,12}")) ids.add(id);
        }
        return ids;
    }

    private static boolean same(OfficialCorrectionLink.Identity id, String url) {
        try {
            return id.equals(OfficialCorrectionLink.identity(url));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static AgencyIntakePost.Post preparationPost(Document page, OfficialFollowupModels.Target target) throws IOException {
        var id = OfficialCorrectionLink.identity(target.primaryUrl());
        var actions = page.select(".notice-action-list [onclick]").stream()
            .filter(e -> e.text().replace(" ", "").contains("해당기관공고보기")).toList();
        if (actions.size() != 1) throw new IOException("MYHOME_DETAIL_IDENTITY_UNVERIFIED");
        var link = LINK.matcher(actions.get(0).attr("onclick"));
        if (!link.find() || !same(id, link.group(1))) return null;
        var title = page.selectFirst(".notice-title");
        var date = page.selectFirst("tr > th:matchesOwn(^모집공고일$) + td");
        var content = page.selectFirst(".cpnt-common-detail");
        if (date == null || title == null || content == null) throw new IOException("MYHOME_DETAIL_INCOMPLETE");
        if (!target.publishedDate().equals(day(date.text())) || AgencyIntakePost.separate(title.text(), target.title())) return null;
        var article = content.clone();
        var files = Pattern.compile("(?i)(?:javascript:)?fnDownFile\\(\\s*'([a-z0-9_-]{1,80})'\\s*,\\s*'(\\d{1,4})'\\s*\\)\\s*;?");
        for (var a : article.select("a[href]")) {
            var m = files.matcher(a.attr("href"));
            if (m.matches()) a.attr("href", "/hws/com/fms/cvplFileDownload.do?atchFileId=" + m.group(1) + "&fileSn=" + m.group(2));
        }
        var body = new StringBuilder(title.text());
        for (var row : article.select("tr:has(> th)")) {
            var heading = row.selectFirst("> th");
            var cell = row.selectFirst("> td");
            if (cell != null && heading.text().matches(".*(?:신청자격|입주자격|제출서류|신청대상|정정사유).*"))
                body.append("\n").append(heading.text()).append(" · ").append(cell.text());
        }
        var correction = page.selectFirst(".notice-correction-reason .reason");
        if (correction != null && !correction.text().isBlank()) body.append("\n정정사유 · ").append(correction.text());
        return new AgencyIntakePost.Post(article, page.location(), target.publishedDate(), true,
            title.text(), "마이홈 상세의 LH 공고 식별자와 모집공고일 일치: " + target.primaryUrl(), body.toString(), List.of());
    }

    static List<Window> windows(Document page, OfficialCorrectionLink.Identity id, String url) throws IOException {
        if (page.select("[onclick]").stream().noneMatch(e -> {
            var link = LINK.matcher(e.attr("onclick"));
            return link.find() && same(id, link.group(1));
        })) throw new IOException("MYHOME_DETAIL_IDENTITY_UNVERIFIED");
        var windows = new ArrayList<Window>();
        for (var row : page.select("tr:has(> th)")) {
            var heading = row.selectFirst("> th");
            String channel = heading.text().replaceAll("\\s+", "").replaceFirst("(접수|신청)일정$", "");
            if (!heading.text().replaceAll("\\s+", "").matches(".+(접수|신청)일정") || channel.isBlank()) continue;
            for (var rank : row.select("> td .rank")) {
                var range = rank.selectFirst(".text-gray");
                if (range == null) continue;
                String[] dates = range.text().split("\\s*[~∼～]\\s*", -1);
                if (dates.length > 2) continue;
                var first = day(dates[0]);
                var last = day(dates[dates.length - 1]);
                if (first == null || last == null || last.isBefore(first)) continue;
                var qualifier = rank.selectFirst(".em");
                windows.add(new Window(channel, qualifier == null ? "" : qualifier.text(), first, last, url));
            }
        }
        return List.copyOf(windows);
    }

    private static LocalDate day(String value) {
        var m = DATE.matcher(value.strip());
        if (!m.matches()) return null;
        return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }
}
