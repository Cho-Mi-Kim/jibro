package org.jibro.notice;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import static org.jibro.notice.SourceReviewModels.*;

/**
 * Additional official evidence for explicit editorial review only; never changes the primary snapshot.
 */
@Component
public final class SupplementalSourceVerifier {
    private static final String LH = "apply.lh.or.kr";
    private static final String LAW = "www.law.go.kr";
    private static final String MANAGEMENT = "www.kohom.or.kr";
    private static final String ANNOUNCEMENT = "/lhapply/apply/noti/an/view.do";
    private static final String DETAIL = "/lhapply/apply/wt/wrtanc/selectWrtancInfo.do";
    private final AttachmentFingerprintClient lh;
    private final AttachmentFingerprintClient historicalLaw;
    private final AttachmentFingerprintClient management;

    public record Parsed(String title, String text, List<String> attachmentUrls, String sha256) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SupplementalSourceVerifier(AttachmentFingerprintClient lh) {
        // Deliberately separate from the collection transport's LH-only allowlist.
        this(lh, new AttachmentFingerprintClient(LAW));
    }

    SupplementalSourceVerifier(AttachmentFingerprintClient lh, AttachmentFingerprintClient historicalLaw) {
        this(lh, historicalLaw, new AttachmentFingerprintClient(MANAGEMENT));
    }

    SupplementalSourceVerifier(AttachmentFingerprintClient lh, AttachmentFingerprintClient historicalLaw, AttachmentFingerprintClient management) {
        this.lh = lh;
        this.historicalLaw = historicalLaw;
        this.management = management;
    }

    Map<String, String> verify(List<SupplementalReference> references, Map<String, SourceReview> reviewed) throws Exception {
        Map<String, String> digests = new LinkedHashMap<>();
        for (var reference : references) {
            validate(reference);
            String host = URI.create(reference.url()).getHost();
            var client = host.equals(LAW) ? historicalLaw : host.equals(MANAGEMENT) ? management : lh;
            var page = parse(reference.url(), client.html(reference.url()));
            if (!page.title().equals(reference.title()) || !page.sha256().equals(reference.sha256()))
                throw new IllegalArgumentException("SUPPLEMENTAL_PAGE_CHANGED");
            requireReview(reviewed, reference.url(), page.sha256());
            if (!page.attachmentUrls().containsAll(reference.attachmentUrls()))
                throw new IllegalArgumentException("SUPPLEMENTAL_ATTACHMENT_NOT_LINKED");
            digests.put(reference.url(), page.sha256());
            for (var url : reference.attachmentUrls()) {
                if (!reviewed.containsKey(url)) throw new IllegalArgumentException("SUPPLEMENTAL_REVIEW_INCOMPLETE");
                if (digests.containsKey(url)) continue;
                String digest;
                if (reviewed.get(url).role().equals("SUPPLEMENTAL_HTML_ATTACHMENT")) {
                    var file = lh.htmlAttachmentForSourceReview(url);
                    String html = new String(file.bytes(), StandardCharsets.UTF_8);
                    if (!html.toLowerCase(Locale.ROOT).contains("<html") || Jsoup.parse(html).body().text().isBlank())
                        throw new IllegalArgumentException("SUPPLEMENTAL_HTML_ATTACHMENT_INVALID");
                    digest = file.fingerprint().sha256();
                } else {
                    digest = lh.fetchForSourceReview(url).sha256();
                }
                requireReview(reviewed, url, digest);
                digests.put(url, digest);
            }
        }
        return Map.copyOf(digests);
    }

    private static void requireReview(Map<String, SourceReview> reviewed, String url, String digest) {
        var review = reviewed.get(url);
        if (review == null) throw new IllegalArgumentException("SUPPLEMENTAL_REVIEW_INCOMPLETE");
        if (!Objects.equals(review.sha256(), digest)) throw new IllegalArgumentException("SUPPLEMENTAL_REVIEW_CHANGED");
    }

    public static void validate(SupplementalReference reference) {
        if (reference == null || reference.title() == null || reference.title().isBlank() || reference.title().length() > 500
                || reference.sha256() == null || !reference.sha256().matches("[0-9a-f]{64}")
                || reference.relation() == null || reference.relation().isBlank() || reference.relation().length() > 2000
                || reference.attachmentUrls().size() > 50
                || new HashSet<>(reference.attachmentUrls()).size() != reference.attachmentUrls().size())
            throw new IllegalArgumentException("INVALID_SUPPLEMENTAL_REFERENCE");
        var uri = pageUri(reference.url());
        if (uri.getHost().equals(LAW) && !reference.attachmentUrls().isEmpty())
            throw new IllegalArgumentException("HISTORICAL_LAW_BODY_ONLY");
        if (uri.getHost().equals(MANAGEMENT) && !reference.attachmentUrls().isEmpty())
            throw new IllegalArgumentException("MANAGEMENT_NOTICE_BODY_ONLY");
        reference.attachmentUrls().forEach(SupplementalSourceVerifier::attachmentUri);
    }

    /**
     * Stable review digest excludes page chrome and visit counts but includes every official child link.
     */
    public static Parsed parse(String url, String html) {
        var uri = pageUri(url);
        if (html == null || html.isBlank() || html.length() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("SUPPLEMENTAL_BODY_INVALID");
        var doc = Jsoup.parse(html, url);
        String title, text;
        List<String> files;
        if (uri.getHost().equals(MANAGEMENT)) {
            var body = doc.selectFirst("#content .txt_frm");
            if (body == null) throw new IllegalArgumentException("SUPPLEMENTAL_BODY_INVALID");
            body = body.clone();
            body.select(".prev_next,.board_btn,.custom_check,script,style,noscript,tr:has(th:matchesOwn(조회\\s*수?)),.hit,.views").remove();
            title = body.select("tr:has(th:matchesOwn(^제목$)) td").text();
            title = normalize(title);
            if (body.select("tr:has(th:matchesOwn(^작성일$)) td").text().isBlank()
                    || body.select("tr:has(th:matchesOwn(^모집공고일$)) td").text().isBlank())
                throw new IllegalArgumentException("SUPPLEMENTAL_BODY_INVALID");
            text = normalize(body.text()) + "\n" + body.select("a[href]").stream().map(a -> {
                String link = a.absUrl("href");
                // This body-only proof does not claim to verify management files. Their download tokens
                // change on every request; keep the displayed filename instead of the temporary token.
                if (link.matches("https://www\\.kohom\\.or\\.kr/common/download\\.do(?:;jsessionid=[A-Za-z0-9]+)?\\?.*"))
                    return "management attachment: " + normalize(a.text());
                return link;
            }).sorted().reduce((a, b) -> a + "\n" + b).orElse("");
            // Management attachments require their own reviewed path; this scope only verifies the official body.
            files = List.of();
        } else if (uri.getHost().equals(LAW)) {
            var query = query(uri);
            for (var name : List.of("lsiSeq", "lsId", "efYd")) {
                var field = doc.selectFirst("input#" + name);
                if (field == null || !field.val().equals(query.get(name)))
                    throw new IllegalArgumentException("HISTORICAL_LAW_VERSION_MISMATCH");
            }
            var lawTitle = doc.selectFirst("input#lsNm");
            var body = doc.selectFirst("#conScroll");
            var heading = doc.selectFirst("#conTop h2");
            var metadata = doc.selectFirst("#conTop .ct_sub");
            if (lawTitle == null || body == null || heading == null || metadata == null)
                throw new IllegalArgumentException("HISTORICAL_LAW_BODY_MISSING");
            body.select("script,style,noscript").remove();
            title = normalize(lawTitle.val());
            var effective = Pattern.compile("^\\[시행\\s+(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\]")
                    .matcher(normalize(metadata.text()));
            if (!title.equals(normalize(heading.text())) || !effective.find())
                throw new IllegalArgumentException("HISTORICAL_LAW_VERSION_MISMATCH");
            try {
                var actualDate = LocalDate.of(Integer.parseInt(effective.group(1)), Integer.parseInt(effective.group(2)), Integer.parseInt(effective.group(3)));
                if (!actualDate.format(DateTimeFormatter.BASIC_ISO_DATE).equals(query.get("efYd")))
                    throw new IllegalArgumentException("HISTORICAL_LAW_VERSION_MISMATCH");
            } catch (java.time.DateTimeException ex) {
                throw new IllegalArgumentException("HISTORICAL_LAW_VERSION_MISMATCH");
            }
            text = "법령 " + query.get("lsId") + " · 연혁 " + query.get("lsiSeq") + " · 시행 " + query.get("efYd")
                    + "\n" + normalize(metadata.text()) + "\n" + normalize(body.text());
            if (!body.text().matches("(?s).*제\\s*\\d+\\s*조.*"))
                throw new IllegalArgumentException("HISTORICAL_LAW_BODY_MISSING");
            files = List.of();
        } else {
            var box = doc.selectFirst(".bbs_ViewA");
            var heading = box == null ? null : box.selectFirst("h3");
            if (heading == null) throw new IllegalArgumentException("SUPPLEMENTAL_TITLE_MISSING");
            title = normalize(heading.text());
            // Only metadata counters are excluded. A number appearing in the actual body remains hashed.
            for (var item : doc.select(".bbsV_data li")) {
                var label = item.selectFirst("strong");
                if (label != null && normalize(label.text()).equals("조회수")) item.remove();
            }
            doc.select("script,style,noscript").remove();
            var detail = OfficialDetailParser.parse(doc.outerHtml(), url);
            var linked = new TreeSet<String>();
            for (var file : detail.attachments()) {
                attachmentUri(file.url());
                linked.add(file.url());
            }
            files = List.copyOf(linked);
            if (uri.getPath().equals(ANNOUNCEMENT)) {
                var body = box.selectFirst(".bbsV_cont");
                if (body == null || body.text().isBlank())
                    throw new IllegalArgumentException("SUPPLEMENTAL_BODY_INVALID");
                var metadata = box.selectFirst(".bbsV_data");
                text = (metadata == null ? "" : normalize(metadata.text()) + "\n") + normalize(body.text());
            } else {
                text = detail.parts().stream().map(part -> normalize(part.text())).reduce((a, b) -> a + "\n" + b).orElse("");
            }
        }
        if (title.isBlank() || title.length() > 500 || text.isBlank() || text.length() > 500_000)
            throw new IllegalArgumentException("SUPPLEMENTAL_BODY_INVALID");
        String canonical = "supplemental-source-v1\n" + frame(title) + frame(text)
                + files.stream().map(SupplementalSourceVerifier::frame).reduce("", String::concat);
        return new Parsed(title, text, files, SourceReviewVerifier.hash(canonical));
    }

    private static URI pageUri(String url) {
        URI uri = safeUri(url);
        var query = query(uri);
        if (uri.getHost().equals(LH) && uri.getPath().equals(ANNOUNCEMENT)) {
            requireKeys(query, query.containsKey("slBbsKdCd")
                    ? Set.of("bbsSn", "ccrCnntSysDsCd", "slBbsKdCd", "mi") : Set.of("bbsSn", "ccrCnntSysDsCd", "mi"));
            if (!digits(query.get("bbsSn"), 20) || !digits(query.get("ccrCnntSysDsCd"), 2)
                    || query.containsKey("slBbsKdCd") && !"03".equals(query.get("slBbsKdCd")) || !"1079".equals(query.get("mi")))
                reject();
        } else if (uri.getHost().equals(LH) && uri.getPath().equals(DETAIL)) {
            requireKeys(query, Set.of("panId", "ccrCnntSysDsCd", "uppAisTpCd", "aisTpCd", "mi"));
            if (!digits(query.get("panId"), 40) || !digits(query.get("ccrCnntSysDsCd"), 2)
                    || !digits(query.get("uppAisTpCd"), 2) || !digits(query.get("aisTpCd"), 2)
                    || !"1026".equals(query.get("mi"))) reject();
        } else if (uri.getHost().equals(MANAGEMENT) && uri.getPath().equals("/web/mainComm/HM001002002.do")) {
            requireKeys(query, Set.of("mode", "p_idx"));
            if (!"view".equals(query.get("mode")) || !digits(query.get("p_idx"), 20)) reject();
        } else if (uri.getHost().equals(LAW) && uri.getPath().equals("/LSW/lsInfoR.do")) {
            requireKeys(query, Set.of("lsiSeq", "lsId", "chrClsCd", "efYd"));
            if (!digits(query.get("lsiSeq"), 12) || !digits(query.get("lsId"), 12)
                    || !"010202".equals(query.get("chrClsCd")) || !query.get("efYd").matches("\\d{8}")) reject();
            try {
                if (LocalDate.parse(query.get("efYd"), DateTimeFormatter.BASIC_ISO_DATE).isAfter(LocalDate.now()))
                    reject();
            } catch (java.time.DateTimeException ex) {
                reject();
            }
        } else reject();
        return uri;
    }

    private static void attachmentUri(String url) {
        var uri = safeUri(url);
        var query = query(uri);
        if (!uri.getHost().equals(LH) || !uri.getPath().equals("/lhapply/lhFile.do")
                || !query.keySet().equals(Set.of("fileid")) || !digits(query.get("fileid"), 20)) reject();
    }

    private static URI safeUri(String url) {
        try {
            if (url == null || url.length() > 2000) throw new IllegalArgumentException();
            var uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !Set.of(LH, LAW, MANAGEMENT).contains(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("SUPPLEMENTAL_URL_REJECTED");
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) reject();
        for (var pair : uri.getRawQuery().split("&", -1)) {
            var values = pair.split("=", 2);
            if (values.length != 2) reject();
            String key = URLDecoder.decode(values[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(values[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) reject();
        }
        return result;
    }

    private static boolean digits(String value, int max) {
        return value != null && value.matches("[0-9]{1," + max + "}");
    }

    private static void requireKeys(Map<String, String> query, Set<String> keys) {
        if (!query.keySet().equals(keys)) reject();
    }

    private static void reject() {
        throw new IllegalArgumentException("SUPPLEMENTAL_URL_REJECTED");
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s\\p{Zs}]+", " ").strip();
    }

    private static String frame(String text) {
        return text.length() + ":" + text + "\n";
    }
}
