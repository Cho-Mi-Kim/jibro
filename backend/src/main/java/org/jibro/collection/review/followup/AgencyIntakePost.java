package org.jibro.collection.review.followup;

import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jibro.notice.OfficialCorrectionLink;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads dated, institutional notice bodies. Search snippets and nearby recruitments are never claims.
 */
final class AgencyIntakePost {
    private static final Pattern DAY = Pattern.compile("(20\\d{2})\\s*[.년/-]\\s*(\\d{1,2})\\s*[.월/-]\\s*(\\d{1,2})(?!\\d)");
    private static final String BOUND = "(20\\d{2}\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2})[.일]?\\s*(?:\\([월화수목금토일](?:요일)?\\))?\\s*(\\d{1,2}:\\d{2})?";
    private static final Pattern FIELD = Pattern.compile("^(?:[■•▪○*-]|\\d+[.)])?\\s*(?:\\[?([가-힣A-Za-z0-9-]+(?:단지|순위))\\]?\\s*[·:]?\\s*)?"
            + "(온라인|인터넷(?:[/·]모바일)?|현장(?:방문)?|방문|등기우편|우편)?\\s*(?:청약\\s*)?(?:신청\\s*)?(?:접수|신청)\\s*"
            + "(기간|일정|일시|일|시작(?:일시|일|시각)?|개시|마감(?:일시|일|시각)?|종료)\\s*[:：]?\\s*" + BOUND
            + "(?:\\s*(?:~|∼|～|부터)\\s*" + BOUND + ")?\\s*(?:까지)?[.]?$");
    private static final Pattern CLOCK_ONLY = Pattern.compile("^(?:(?:\\[([^\\]]{1,40})\\]|([가-힣A-Za-z0-9-]+(?:단지|순위)))\\s*[·:]?\\s*)?"
            + "(온라인|인터넷|현장(?:방문)?|방문|우편)?\\s*(?:첫날\\s*)?(?:청약\\s*)?(?:접수|신청)\\s*(시작|개시|마감|종료)\\s*(?:시각|시간)?\\s*[:：]?\\s*(\\d{1,2}:\\d{2})$");
    private static final Pattern MANAGEMENT_PERIOD = Pattern.compile("^(?:\\[?([가-힣A-Za-z0-9-]+(?:단지|순위))\\]?\\s*)?"
            + "(?:(현장|방문|온라인|인터넷|우편)\\s*)?[:：]?\\s*(20\\d{2}.+)$");

    private static String titleKey(String value) {
        return value.replaceAll("\\s+", "").replaceAll("(?<=\\d)\\.(?=\\)|$)", "");
    }

    private static String publicationTitle(String title, LocalDate day) {
        return titleKey(title).replaceFirst("^" + day.getYear() + "년0?" + day.getMonthValue() + "월", "");
    }

    private static final Pattern KOREAN_CLOCK = Pattern.compile("(?<!\\d)(?:(오전|오후)\\s*)?(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분)?(?![가-힣])");
    private static final Pattern OTHER = Pattern.compile("잔여세대|추가모집|자격완화|재모집|상시모집|수시모집");
    private static final Pattern BOARD = Pattern.compile("공지사항|시정소식|읍면동\\s*소식|우리동\\s*소식|고시\\s*[·/]?\\s*공고|공고\\s*/\\s*고시|새소식|모집안내|알림마당|주택공지");
    private static final Pattern PUBLIC = Pattern.compile("자유게시판|시민게시판|주민게시판|중고장터|국민신문고|묻고답하기");

    private AgencyIntakePost() {
    }

    static List<Window> read(Document page, OfficialFollowupModels.Target target, LocalDate today) {
        var post = inspect(page, target, today);
        return post == null ? List.of() : post.bodyWindows();
    }

    record Post(Element article, String url, LocalDate issued, boolean linked, String title, String proof, String body,
                List<Window> bodyWindows) {
    }

    static Post inspect(Document page, OfficialFollowupModels.Target target, LocalDate today) {
        return inspect(page, target, today, null);
    }

    static Post inspect(Document page, OfficialFollowupModels.Target target, LocalDate today, LocalDate listedDate) {
        boolean kohom = URI.create(page.location()).getHost().equals("www.kohom.or.kr");
        Element article = page.selectFirst(kohom ? "#content .txt_frm" :
                ".p-wrap.bbs__view, .bod_view, .bbs_view, .board_view, .boardView, .board-view, .bbs-view, .bd-view, .view-wrap, table.view_table, article:has(time), .board > .view:has(.substance)");
        if (article == null) return null;
        if (kohom) {
            // The board puts adjacent notice titles inside the current article's wrapper.
            article = article.clone();
            article.select(".prev_next, .board_btn, .custom_check").remove();
        }
        String board = page.title() + " " + page.select(".location,.breadcrumb,article > h3").text();
        // Navigation menus can also contain public-board names. Check the current page's title separately.
        if (!BOARD.matcher(board).find() || PUBLIC.matcher(board).find()) return null;
        Element title = article.selectFirst(".p-table__subject_text,h1,h2,h3,h4,.subject,.view-title,.title,.bbs_tit,.view_tit strong,caption + thead strong,th strong");
        String heading = kohom ? cell(article, "제목") : title == null ? "" : title.text();
        Element meta = article.selectFirst("time,.bbs_vtop .col,.view_name,.view-info,.view_info,.board_info,.bbs_info,.date,.info,li:has(strong:contains(등록일)) span");
        String publicationDate = kohom ? cell(article, "작성일") : meta == null ? "" :
                meta.hasAttr("datetime") ? meta.attr("datetime") : meta.text();
        if (heading.isBlank()) return null;
        Element body = kohom ? article : article.selectFirst(".ntt_cn_container,.bbs_con,.view_cont,.view_body,.view-content,.board_cont,.bbs_content,.view_con,.content,.contents,.con,.substance");
        if (body == null) return null;
        String original = target.title().replaceAll("\\s", "");
        if (separate(heading, original)) return null;
        // Keep adjacent metadata fields separate even when the board emits minified HTML.
        Element prose = body.clone();
        if (kohom) prose.select("tr:has(th:matchesOwn(조회\\s*수?)), .hit, .views").remove();
        if (kohom) prose.select("th").forEach(th -> th.before(new org.jsoup.nodes.TextNode("\n│\n")));
        prose.select("p,li,tr").forEach(node -> node.before(new org.jsoup.nodes.TextNode("\n")));
        String text = java.text.Normalizer.normalize(prose.wholeText().strip(), java.text.Normalizer.Form.NFKC);
        text = text.replaceAll("(모집\\s*공고일)\\s*\\R\\s*[-*]\\h*(?=20\\d{2})", "$1: ");
        var parts = List.of(new Part("게시글 제목", heading), new Part("공식 게시글", heading + "\n" + text));
        if (OfficialFollowupRelationship.multipleRecruitments(target, parts)) return null;
        var referencedDates = OfficialFollowupRelationship.references(text);
        if (!referencedDates.isEmpty() && !referencedDates.equals(java.util.Set.of(target.publishedDate())))
            return null;
        var linked = body.select("a[href]").stream().map(a -> a.absUrl("href"))
                .filter(url -> url.contains("selectWrtancInfo.do")).toList();
        if (!linked.isEmpty()) {
            try {
                var identity = OfficialCorrectionLink.identity(target.primaryUrl());
                if (!linked.stream().allMatch(url -> identity.equals(OfficialCorrectionLink.identity(url))))
                    return null;
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        // An explicit new recruitment in the body can cite the previous round's notice for context.
        if (Pattern.compile("(?:별도\\s*모집|별개의\\s*모집|추가\\s*모집\\s*공고|잔여\\s*세대\\s*모집)").matcher(text).find()
                && !heading.replaceAll("\\s", "").equals(original)) return null;
        LocalDate issued;
        try {
            issued = date(publicationDate);
        } catch (DateTimeException invalid) {
            throw new InvalidField("AGENCY_PUBLICATION_DATE_INVALID", page.location(), publicationDate, invalid);
        }
        if (issued == null) issued = listedDate;
        if (issued == null || issued.isAfter(today)) return null;
        var identity = OfficialFollowupRelationship.identity(heading);
        boolean samePublication;
        try {
            samePublication = kohom && target.publishedDate().equals(date(cell(article, "모집공고일")));
        } catch (DateTimeException invalid) {
            throw new InvalidField("AGENCY_EDITION_DATE_INVALID", page.location(), cell(article, "모집공고일"), invalid);
        }
        boolean sameTitle = samePublication && !identity.housing().isEmpty() && !identity.scope().isEmpty()
                && publicationTitle(heading, target.publishedDate()).equals(publicationTitle(target.title(), target.publishedDate()));
        boolean matched = !linked.isEmpty() || OfficialFollowupRelationship.match(target, parts).proof() != null;
        // Read the management board's structured interval only through the paired-date check.
        var intervals = new ArrayList<String>();
        if (kohom) {
            for (var th : prose.select("th")) {
                if (!th.text().replaceAll("\\s", "").equals("신청접수일")) continue;
                var value = th.nextElementSibling();
                if (value != null && value.normalName().equals("td")) {
                    intervals.add(value.text());
                    value.remove();
                }
                th.remove();
            }
        }
        var lines = new java.util.LinkedHashSet<String>(prose.wholeText().lines().map(String::strip).toList());
        for (Element row : prose.select("tr,p,li")) lines.add(row.text());
        String permalink = page.location().replaceFirst("(?i);jsessionid=[a-z0-9._-]+", "");
        if (permalink.startsWith("https://www.namwon.go.kr/board/post/view.9is?"))
            permalink = permalink.replace("/view.9is?", "/view.do?");
        var windows = new ArrayList<Window>();
        if (matched) windows.addAll(fields(lines, text, permalink, issued));
        if (samePublication && (matched || sameTitle)) {
            for (String interval : intervals) {
                var row = MANAGEMENT_PERIOD.matcher(interval);
                if (!row.matches()) continue;
                String scope = java.util.Objects.toString(row.group(1), "") + " " + java.util.Objects.toString(row.group(2), "");
                for (var w : fields(List.of(scope.strip() + " 접수 기간: " + row.group(3)), "", permalink, issued)) {
                    if (w.start() != null && w.end() != null && (w.opens() != null || w.closes() != null))
                        windows.add(new Window(w.channel(), w.qualifier(), w.start(), w.end(), permalink, w.opens(),
                                w.closes(), issued, null, null, org.jibro.collection.review.IntakeChannelDates.AgencyField.KOHOM_INTAKE,
                                null, null, w.channel().matches("온라인|인터넷|우편") || w.channel().isBlank() && text.matches("(?s).*(?:온라인|인터넷)\\s*(?:접수|신청)\\s*(?:가능|기간|일시).*") ? null : ReceptionDeskFields.counter(text, target.publishedDate())));
                }
            }
        }
        String proof = !linked.isEmpty() ? "공식 게시글의 LH 원공고 링크 일치" :
                sameTitle ? "관리소 모집공고일과 공고 제목 일치" : OfficialFollowupRelationship.match(target, parts).proof();
        if (proof == null && !kohom && referencedDates.equals(java.util.Set.of(target.publishedDate()))) {
            String compactHeading = titleKey(heading);
            var site = OfficialFollowupRelationship.identity(target.title());
            var names = site.scope().stream().map(name -> name.replaceFirst("(?<=[가-힣]{3})단지$", ""))
                    .filter(name -> !name.matches("(?:입주)?자?격완화|상시|상시모집")).toList();
            if (!names.isEmpty() && !site.housing().isEmpty() && names.stream().anyMatch(name -> name.length() >= 4)
                    && names.stream().allMatch(compactHeading::contains) && site.housing().stream().allMatch(compactHeading::contains)
                    && !separate(heading, target.title()) && !separate(target.title(), heading + text + article.select("a[href]").text()))
                proof = "지자체 공지의 원모집 공고일·단지명·주택 유형 일치";
        }
        if (kohom && !cell(article, "모집공고일").isBlank() && !samePublication) proof = null;
        if (proof != null || referencedDates.equals(java.util.Set.of(target.publishedDate()))
                && article.select("a[href]").stream().anyMatch(a -> OfficeVisitFields.sameEditionFile(a.text(), target)))
            for (var w : OfficeVisitFields.read(text, permalink, issued)) {
                windows.add(w);
                var counter = ReceptionDeskFields.counter(text, target.publishedDate());
                if (counter != null && (w.qualifier().equals(counter.name()) || w.qualifier().isBlank()))
                    windows.add(new Window("현장", "", w.start(), w.end(), w.url(), w.opens(), w.closes(), w.issued(),
                            null, null, w.field(), w.breakStart(), w.breakEnd(), counter));
            }
        boolean exactReference = referencedDates.equals(java.util.Set.of(target.publishedDate()));
        boolean cityHeadline = titleKey(target.title()).replaceFirst("\\(20\\d{2}.*$", "").replaceFirst("모집공고$", "모집")
                .equals(titleKey(heading).replaceFirst("\\([^()]+\\)$", "").replaceFirst("모집공고$", "모집"));
        var deskClaims = !kohom && exactReference && (proof != null || cityHeadline)
                ? ReceptionDeskFields.read(article, text, heading, permalink, issued, target.publishedDate()) : List.<Window>of();
        if (issued.isBefore(target.publishedDate())) {
            if (deskClaims.isEmpty()) return null;
            return new Post(article, permalink, issued, false, heading, null, text, deskClaims);
        }
        windows.addAll(deskClaims);
        return new Post(article, permalink, issued, matched, heading, proof, text, List.copyOf(windows));
    }

    static boolean separate(String label, String title) {
        var flags = OTHER.matcher(label.replaceAll("\\s", ""));
        String original = title.replaceAll("\\s", "");
        while (flags.find()) if (!original.contains(flags.group())) return true;
        return false;
    }

    static List<Window> fields(Iterable<String> lines, String text, String url, LocalDate issued) {
        var result = new ArrayList<Window>();
        for (String line : lines) {
            // Expand a repeated year's month/day only within one explicit interval.
            String normalized = line.strip().replaceFirst("(?<y>20\\d{2})(?<rest>\\s*[.년/-][^~∼～]{1,45}[~∼～]\\s*)(?=\\d{1,2}[.월/-])", "${y}${rest}${y}.");
            normalized = normalizeClocks(normalized);
            String[] change = normalized.split("\\s*(?:→|⇒)\\s*", -1);
            org.jibro.collection.review.IntakeChannelDates.Previous previous = null;
            if (change.length > 1) {
                String labelAndOld = change[0].replaceFirst("[(（]변경\\s*전[)）]\\s*", "").strip();
                var old = FIELD.matcher(labelAndOld);
                if (change.length != 2 || !old.matches() || old.group(6) != null || !old.group(3).matches("시작.*|개시|마감.*|종료"))
                    continue;
                try {
                    var before = date(old.group(4));
                    if (before == null) continue;
                    previous = new org.jibro.collection.review.IntakeChannelDates.Previous(before, clock(old.group(5)));
                } catch (DateTimeException invalid) {
                    throw new InvalidField("AGENCY_AMENDMENT_BEFORE_INVALID", url, line, invalid);
                }
                normalized = labelAndOld.substring(0, old.start(4))
                        + change[1].replaceFirst("^[(（]변경\\s*후[)）]\\s*", "");
            }
            var field = FIELD.matcher(normalized);
            if (!field.matches()) {
                var boundary = CLOCK_ONLY.matcher(normalized);
                if (boundary.matches()) {
                    String qualifier = boundary.group(1) != null ? boundary.group(1) : boundary.group(2);
                    boolean start = boundary.group(4).matches("시작|개시");
                    try {
                        LocalTime time = clock(boundary.group(5));
                        result.add(new Window(boundary.group(3) == null ? "" : boundary.group(3), qualifier == null ? "" : qualifier, null, null,
                                url, start ? time : null, start ? null : time, issued));
                    } catch (DateTimeException invalid) {
                        throw new InvalidField("AGENCY_INTAKE_TIME_INVALID", url, line, invalid);
                    }
                }
                continue;
            }
            try {
                LocalDate first = date(field.group(4)), last = date(field.group(6));
                LocalTime open = clock(field.group(5)), close = clock(field.group(7));
                if (first == null || field.group(6) != null && last == null) continue;
                if (field.group(3).matches("마감.*|종료")) {
                    if (last != null) continue;
                    last = first;
                    close = open;
                    first = null;
                    open = null;
                } else if (last == null && field.group(3).equals("일")) {
                    last = first;
                } else if (last == null && !field.group(3).matches("시작.*|개시")) continue;
                if (first != null && last != null && (last.isBefore(first) || last.equals(first) && open != null && close != null && close.isBefore(open)))
                    continue;
                String method = field.group(2);
                if (method == null) method = text.matches("(?s).*현장\\s*(?:신청|접수)만\\s*가능.*") ? "현장" : "";
                result.add(new Window(method, field.group(1) == null ? "" : field.group(1),
                        first, last, url, open, close, issued, previous));
            } catch (DateTimeException invalid) {
                throw new InvalidField("AGENCY_INTAKE_DATE_TIME_INVALID", url, line, invalid);
            }
        }
        return result.stream().distinct().toList();
    }

    private static String normalizeClocks(String line) {
        return KOREAN_CLOCK.matcher(line).replaceAll(m -> {
            int hour = Integer.parseInt(m.group(2));
            if (m.group(1) != null) {
                if (hour < 1 || hour > 12) return m.group();
                hour = hour % 12 + ("오후".equals(m.group(1)) ? 12 : 0);
            }
            int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute);
        });
    }

    private static String cell(Element article, String label) {
        for (var th : article.select("th"))
            if (th.text().equals(label) && th.nextElementSibling() != null) return th.nextElementSibling().text();
        return "";
    }

    static final class InvalidField extends IllegalArgumentException {
        InvalidField(String code, String url, String excerpt, DateTimeException cause) {
            super(code + " · " + url + " · " + excerpt, cause);
        }
    }

    private static LocalTime clock(String text) {
        if (text == null) return null;
        String[] pair = text.split(":");
        return LocalTime.of(Integer.parseInt(pair[0]), Integer.parseInt(pair[1]));
    }

    private static LocalDate date(String text) {
        if (text == null) return null;
        var m = DAY.matcher(text);
        if (!m.find()) return null;
        return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }
}
