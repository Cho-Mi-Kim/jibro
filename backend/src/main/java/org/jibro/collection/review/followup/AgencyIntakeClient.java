package org.jibro.collection.review.followup;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/** Public directory search discovers article URLs only. All evidence comes from the issuing site. */
@Component
public class AgencyIntakeClient {
    private static final String KOHOM = "https://www.kohom.or.kr/web/mainComm/HM001002002.do";
    private static final Pattern POST = Pattern.compile("(?i)(view|article|read|selectBbs|dataSid=|nttId=|bIdx=|parm_bod_uid=|amode=view|idx=|boardSeq=)");
    private static final Pattern MANAGEMENT_ID = Pattern.compile("fn_goView\\('(\\d{1,12})'\\)");
    private static final Pattern RECRUITMENT = Pattern.compile("접수|공고|모집|임대|일정|신청");
    private static final Pattern HOUSING_POST = Pattern.compile("임대|아파트|공공주택|입주|주거|청약|전세|주공|주택공급");
    private static final Pattern OTHER_SERVICE = Pattern.compile("채용|경력직|강좌|수강생|일자리|물품구매|입찰");
    private static final int LIMIT = 32;

    private final org.jibro.collection.extraction.BoundedDocumentParser parser;

    private final MyHomeIntakeClient myHome;

    public AgencyIntakeClient(org.jibro.collection.extraction.BoundedDocumentParser parser, MyHomeIntakeClient myHome) {
        this.myHome = myHome;
        this.parser = parser;
    }

    private List<AgencyIntakePost.Post> posts(OfficialFollowupModels.Target target, boolean visitTimes) throws IOException {
        if (target.publishedDate() == null || target.title() == null) return List.of();
        var term = OfficialFollowupRelationship.queryTerm(target);
        if (term.isEmpty()) return List.of();
        var links = new LinkedHashSet<String>();
        var listedDates = new java.util.HashMap<String, LocalDate>();
        var complex = Pattern.compile("([가-힣]{3,})\\s*\\d+(?:단지|[,·])").matcher(target.title());
        String keyword = complex.find() ? complex.group(1) : term.get();
        keyword = keyword.replaceFirst("(?<=[가-힣]{3})단지$", "");
        // Search central management notices for each complex, including posts absent from web indexes.
        managementLinks(keyword, target.publishedDate(), links);
        String title = target.title();
        var housing = OfficialFollowupRelationship.identity(title).housing();
        String localKeyword = keyword.matches(".*(?:시|군|구)") && !housing.isEmpty() ? housing.get(0) : keyword;
        if (visitTimes && title.contains("원주") && !housing.isEmpty()) {
            wonjuNotices("144", "220", housing.get(0), target.publishedDate(), links, listedDates);
            wonjuNotices("1119", "5251", housing.get(0), target.publishedDate(), links, listedDates);
        }
        if (visitTimes && title.contains("수원")) suwonDeskPosts(keyword.equals("수원") && !housing.isEmpty() ? housing.get(0) : keyword, target.publishedDate(), links);
        if (title.contains("김제")) collect(fetch("https://www.gimje.go.kr/index.gimje",
            "menuCd","DOM_000000107006000000","menu","board","searchTerm",keyword), keyword, links);
        if (title.contains("삼척")) collect(fetch("https://www.samcheok.go.kr/search/searchResult.web","sstring",localKeyword,"type","BOARD"), keyword, links);
        if (title.contains("경산")) collect(fetch("https://www.gbgs.go.kr/search/front/Search.jsp","qt",keyword), keyword, links);
        if (title.contains("대구신서")) collect(fetch("https://www.dong.daegu.kr/search/front/Search.jsp","qt",keyword), keyword, links);
        if (title.contains("충주")) collect(fetch("https://www.chungju.go.kr/search.do", "query",keyword), keyword, links);
        if (title.contains("남원")) collect(fetch("https://www.namwon.go.kr/index.do", "menuUid","ff8080818fa2b984018fa350ef31018d","keyword",keyword,"collection","total"), keyword, links);
        if (title.contains("태백")) collect(fetch("https://www.taebaek.go.kr/search/search.do", "query",keyword), keyword, links);
        if (title.contains("익산")) collect(fetch("https://www.iksan.go.kr/index.do", "menuUid","ff8080819a943f6b019a945a64bf00b4","keyword",keyword,"collection","total"), keyword, links);
        var index = fetch("https://search.naver.com/search.naver","query", keyword + " "
            + String.join(" ", OfficialFollowupRelationship.identity(title).housing()) + " 접수 공고 site:go.kr");
        if (index.selectFirst("#wrap,#content,#main_pack") == null) throw new IOException("MUNICIPAL_DIRECTORY_UNAVAILABLE");
        collect(index, keyword, links);
        if (links.size() > LIMIT) throw new IOException("AGENCY_NOTICE_SEARCH_LIMIT " + links.size());
        var results = new ArrayList<AgencyIntakePost.Post>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (String link : links) {
            var post = AgencyIntakePost.inspect(fetch(link), target, today, listedDates.get(link));
            if (post != null) results.add(post);
        }
        return List.copyOf(results);
    }

    public List<OfficialFollowupModels.Related> preparation(OfficialFollowupModels.Target target, List<org.jibro.collection.extraction.ExtractionModels.Source> primary) throws IOException {
        var attachments = new AgencyIntakeAttachments(parser);
        var related = new ArrayList<OfficialFollowupModels.Related>();
        for (var post : posts(target, false)) {
            if (post.proof() != null) related.add(attachments.preparation(post, target, primary));
        }
        return related;
    }

    List<OfficialFollowupModels.Related> alternatives(OfficialFollowupModels.Target target,
            List<org.jibro.collection.extraction.ExtractionModels.Source> primary, Set<String> retries) throws IOException {
        var posts = new java.util.TreeMap<String, AgencyIntakePost.Post>();
        try {
            Document mirror = myHome.detail(target);
            if (mirror != null) addMirror(posts, mirror, target);
        } catch (IOException unavailable) {
            retries.add("MYHOME_ALTERNATIVE_RETRY");
        }
        var keyword = OfficialFollowupRelationship.queryTerm(target).orElse(target.title());
        var links = new LinkedHashSet<String>();
        try {
            var index = fetch("https://search.naver.com/search.naver", "query", keyword + " 주거복지포털 모집 공지");
            if (index.selectFirst("#wrap,#content,#main_pack") == null) throw new IOException("REGIONAL_PORTAL_SEARCH_UNAVAILABLE");
            collect(index, keyword, links);
        } catch (IOException unavailable) {
            retries.add("REGIONAL_ALTERNATIVE_RETRY");
        }
        // The Gwangju portal also indexes official MyHome detail links directly.
        if (target.title().contains("광주")) {
            try {
                var list = fetch("https://www.xn--v69aylw70b3vla29io83afif.kr/pubRantal/pubRantalSch.do",
                    "annouNm", keyword, "stdNoticeDt", target.publishedDate().toString());
                if (list.selectFirst("form[name=searchForm]") == null) throw new IOException("REGIONAL_PORTAL_DIRECTORY_UNAVAILABLE");
                for (var anchor : list.select("a[href]")) {
                    String url = anchor.absUrl("href");
                    if (url.matches("https://www[.]myhome[.]go[.]kr/hws/portal/sch/selectRsdtRcritNtcDetailView[.]do[?]pblancId=[0-9]{1,12}")
                        && anchor.text().replace(" ", "").contains(keyword.replace(" ", "")) && !posts.containsKey(url)) links.add(url);
                }
                collect(list, keyword, links);
            } catch (IOException unavailable) {
                retries.add("REGIONAL_ALTERNATIVE_RETRY");
            }
        }
        if (links.size() > LIMIT) throw new IOException("REGIONAL_PORTAL_SEARCH_LIMIT");
        for (String url : links) {
            Document page;
            try {
                page = fetch(url);
            } catch (IOException unavailable) {
                retries.add("REGIONAL_ALTERNATIVE_RETRY");
                continue;
            }
            if (url.startsWith("https://www.myhome.go.kr/")) {
                if (url.contains("selectRsdtRcritNtcDetailView.do?")) addMirror(posts, page, target);
                continue;
            }
            var post = AgencyIntakePost.inspect(page, target, LocalDate.now(ZoneId.of("Asia/Seoul")));
            if (post != null && post.proof() != null) posts.put(post.url(), post);
        }
        var attachments = new AgencyIntakeAttachments(parser);
        var found = new ArrayList<OfficialFollowupModels.Related>();
        for (var post : posts.values()) found.add(attachments.preparation(post, target, primary));
        return found;
    }

    private static void addMirror(java.util.Map<String, AgencyIntakePost.Post> posts, Document page,
                                  OfficialFollowupModels.Target target) throws IOException {
        var post = MyHomeIntakeClient.preparationPost(page, target);
        if (post != null) posts.put(post.url(), post);
    }

    public List<Window> find(OfficialFollowupModels.Target target) throws IOException {
        var results = new ArrayList<Window>();
        var attachments = new AgencyIntakeAttachments(parser);
        for (var post : posts(target, true)) results.addAll(attachments.read(post, target));
        return results.stream().distinct().sorted(Comparator.comparing(Window::url).thenComparing(Window::toString)).toList();
    }

    private static void suwonDeskPosts(String keyword, LocalDate edition, Set<String> links) throws IOException {
        String board = "https://paldal.suwon.go.kr/bbsplus/list.asp";
        var directory = fetch(board + "?code=tbl_bbs_sub200510&bd_gubn=20&mnuflag=");
        var boards = directory.select("a[href*=bd_gubn]").stream().map(a -> a.absUrl("href"))
            .filter(url -> url.matches(Pattern.quote(board) + "\\?code=tbl_bbs_sub200510&bd_gubn=\\d+&mnuflag="))
            .distinct().toList();
        if (boards.isEmpty() || boards.size() > 20) throw new IOException("SUWON_DESK_DIRECTORY_UNAVAILABLE");
        for (String url : boards) {
            var results = fetch(url, "strSearch_div", "title", "strSearch_text", keyword, "tag", "search");
            if (results.selectFirst("[name=strSearch_div]") == null) throw new IOException("SUWON_DESK_SEARCH_UNAVAILABLE");
            for (var row : results.select("tr:has(a[href*=view.asp])")) {
                var date = Pattern.compile("20\\d{2}[.-]\\d{2}[.-]\\d{2}").matcher(row.text());
                if (date.find() && LocalDate.parse(date.group().replace('.','-')).isBefore(edition.minusDays(14))) continue;
                for (var link : row.select("a[href*=view.asp]")) {
                    if (link.text().contains(keyword) && HOUSING_POST.matcher(link.text()).find()
                        && link.absUrl("href").startsWith("https://paldal.suwon.go.kr/bbsplus/view.asp?")) links.add(link.absUrl("href"));
                }
            }
        }
    }

    private static void managementLinks(String keyword, LocalDate published, Set<String> links) throws IOException {
        for (int page = 1; page <= 5; page++) {
            var management = fetch(KOHOM, "schCon", "1", "schStr", keyword, "page", String.valueOf(page));
            if (management.selectFirst("form [name=schStr]") == null || management.selectFirst("table tbody") == null)
                throw new IOException("MANAGEMENT_DIRECTORY_UNAVAILABLE");
            boolean earlier = false;
            for (var a : management.select("tbody a[onclick]")) {
                var row = a.closest("tr");
                var listed = Pattern.compile("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)").matcher(row == null ? "" : row.text());
                if (listed.find() && LocalDate.parse(listed.group(1)).isBefore(published)) {
                    earlier = true;
                    continue;
                }
                var id = MANAGEMENT_ID.matcher(a.attr("onclick"));
                if (id.find()) links.add(KOHOM + "?mode=view&p_idx=" + id.group(1));
            }
            if (earlier || management.select("a[onclick]").stream().map(a -> a.attr("onclick"))
                .noneMatch(Pattern.compile("fn_linkPage\\(" + (page + 1) + "\\)").asPredicate())) return;
            if (links.size() >= LIMIT || page == 5) throw new IOException("MANAGEMENT_DIRECTORY_LIMIT");
        }
    }

    private static Document fetch(String url, String... data) throws IOException {
        try {
            return fetchPage(url, data);
        } catch (org.jsoup.HttpStatusException failure) {
            if (!Set.of(502, 503, 504).contains(failure.getStatusCode())) throw failure;
            if (data.length == 0) {
                try {
                    var client = new org.jibro.collection.attachments.AttachmentFingerprintClient(URI.create(url).getHost());
                    return Jsoup.parse(client.html(url), url);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("AGENCY_NOTICE_INTERRUPTED", interrupted);
                } catch (Exception retryFailure) {
                    throw new IOException("AGENCY_NOTICE_UNAVAILABLE " + url, retryFailure);
                }
            }
            return fetchPage(url, data);
        }
    }

    private static Document fetchPage(String url, String... data) throws IOException {
        var session = Jsoup.newSession();
        var request = session.newRequest(url).userAgent("Mozilla/5.0 (compatible; JibroNoticeReview/1.0)")
            .timeout(8000).maxBodySize(2_000_000).followRedirects(false);
        if (data.length > 0) request.data(data);
        var response = request.execute();
        URI original = URI.create(url);
        for (int hop = 0; hop < 4 && response.statusCode() / 100 == 3 && response.hasHeader("Location"); hop++) {
            URI next = URI.create(response.url().toString()).resolve(response.header("Location"));
            if ("https".equals(next.getScheme()) && next.getPort() == -1 && next.getUserInfo() == null
                && next.getHost() != null && original.getHost().replaceFirst("^www[.]", "").equals(next.getHost().replaceFirst("^www[.]", "")))
                response = session.newRequest(next.toString()).userAgent("Mozilla/5.0 (compatible; JibroNoticeReview/1.0)")
                    .timeout(8000).maxBodySize(2_000_000).followRedirects(false).execute();
            else break;
        }
        if (response.statusCode() != 200 || response.bodyAsBytes().length >= 2_000_000) throw new IOException("AGENCY_NOTICE_UNAVAILABLE " + response.statusCode() + " " + response.url().getHost() + response.url().getPath());
        return response.parse();
    }

    private static void wonjuNotices(String board, String key, String term, LocalDate since, Set<String> links, java.util.Map<String, LocalDate> listedDates) throws IOException {
        for (int page = 1; page <= 5; page++) {
            var list = fetch("https://www.wonju.go.kr/www/selectBbsNttList.do", "bbsNo", board, "key", key,
                "searchCnd", "SJ", "searchKrwd", term, "pageIndex", String.valueOf(page));
            if (list.selectFirst("table tbody") == null) throw new IOException("MUNICIPAL_DIRECTORY_UNAVAILABLE");
            boolean older = false;
            var entries = list.select("tbody tr:has(a[href*=selectBbsNttView])");
            for (var row : entries) {
                var day = row.select("td").stream().map(org.jsoup.nodes.Element::text)
                    .filter(value -> value.matches("20[0-9]{2}[.][0-9]{2}[.][0-9]{2}")).findFirst()
                    .orElseThrow(() -> new IOException("MUNICIPAL_NOTICE_DATE_UNAVAILABLE"));
                if (LocalDate.parse(day.replace('.', '-')).isBefore(since)) older = true;
                else {
                    var anchor = row.selectFirst("a[href*=selectBbsNttView]");
                    var address = URI.create(anchor.absUrl("href"));
                    var id = Pattern.compile("(?:^|&)nttNo=(\\d{1,12})(?:&|$)").matcher(address.getQuery());
                    if (id.find()) {
                        String url = "https://www.wonju.go.kr/www/selectBbsNttView.do?key=" + key + "&bbsNo=" + board + "&nttNo=" + id.group(1);
                        links.add(url);
                        listedDates.put(url, LocalDate.parse(day.replace('.', '-')));
                    }
                }
            }
            if (older || entries.size() < 10) return;
        }
        throw new IOException("MUNICIPAL_NOTICE_SEARCH_LIMIT");
    }

    private static void collect(Document doc, String keyword, Set<String> result) {
        for (var anchor : doc.select("a[href]")) {
            String heading = anchor.text();
            if (!RECRUITMENT.matcher(heading).find() || OTHER_SERVICE.matcher(heading).find()) continue;
            String context = anchor.parent() == null ? heading : anchor.parent().text();
            if (!heading.replaceAll("\\s+", "").contains(keyword.replaceAll("\\s+", ""))) continue;
            if (!HOUSING_POST.matcher(context).find() && !heading.matches(".*접수.*(?:정정|변경).*")) continue;
            String link = anchor.absUrl("href").replaceFirst("^http://","https://").split("#",2)[0];
            if (link.startsWith("https://www.namwon.go.kr/board/post/view.9is?")) link = link.replace("/view.9is?", "/view.do?");
            // Namwon links its public notices from several menu aliases and in different query orders.
            if (link.startsWith("https://www.namwon.go.kr/board/post/view.do?")
                && link.matches(".*[?&]boardUid=ff8080818ea1fec5018ea24137680031(?:&.*|$)")) {
                var id = Pattern.compile("[?&]postUid=([a-fA-F0-9]{32})(?:&|$)").matcher(link);
                if (id.find()) link = "https://www.namwon.go.kr/board/post/view.do?menuUid=ff8080818e3beff0018e4077131b007a"
                    + "&boardUid=ff8080818ea1fec5018ea24137680031&postUid=" + id.group(1);
            }
            if (link.length() > 1600 || !POST.matcher(link).find()) continue;
            try {
                var uri = URI.create(link);
                if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getHost() == null || !OfficialFollowupModels.agencyHost(uri.getHost())) continue;
                String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
                if (uri.getHost().endsWith("samcheok.go.kr") && path.endsWith("/00182.web")) continue; // 시민 자유게시판
                if (path.matches(".*(?:download|/synap/|/preview|/search/|/file/|\\.pdf|\\.hwp).*")) continue;
                result.add(link);
            } catch (IllegalArgumentException ignored) { /* Not a public article URL. */ }
        }
    }
}
