package org.jibro.notice;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeVisitor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts policy content, never evaluates the untrusted source's JavaScript.
 */
final class GuideSourceContent {
    record Attachment(String title, String url) {
    }

    record Content(String title, String text, List<Attachment> attachments) {
    }

    private static final Pattern FILE = Pattern.compile("^javascript:fileDownLoad\\(['\"]([0-9]{1,20})['\"]\\);?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTER = Pattern.compile("^(?:조회\\s*수|조회|방문자\\s*수|조회건수)\\s*[:：]?\\s*[0-9,]+(?:\\s*회)?$");
    private static final Pattern ERROR = Pattern.compile("(서비스\\s*접속\\s*대기|접속량이\\s*많아|접근이\\s*차단|접근\\s*권한이\\s*없|요청하신\\s*(?:페이지|서비스).{0,20}(?:없|찾을|오류)|페이지를\\s*찾을\\s*수\\s*없|서비스\\s*점검\\s*중|access denied|access blocked|service unavailable|captcha)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "tr", "br");
    private final ObjectMapper json;

    GuideSourceContent(ObjectMapper json) {
        this.json = json;
    }

    Content extract(URI url, byte[] bytes, String contentType) throws Exception {
        String charset = null;
        var encoding = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (encoding.find()) charset = encoding.group(1);
        Document document = Jsoup.parse(new ByteArrayInputStream(bytes), charset, url.toString());
        if (url.getHost().equalsIgnoreCase("www.bokjiro.go.kr")) return welfare(url, document);
        List<Element> selected = select(url, document);
        if (selected.isEmpty()) throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
        String title = document.title();
        Element heading = document.selectFirst(".bbs_ViewA h3, #pageCont h2");
        if (heading != null) title = heading.text();
        String text = selected.stream().map(GuideSourceContent::text).filter(part -> !part.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
        if (text.length() < 40 || ERROR.matcher(title).find() || ERROR.matcher(text.substring(0, Math.min(300, text.length()))).find())
            throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
        List<Attachment> attachments = new ArrayList<>();
        if (url.getPath().endsWith("selectWrtancInfo.do")) {
            for (Element anchor : document.select(".bbsV_atchmnfl a[href]")) {
                String name = anchor.text();
                var match = FILE.matcher(anchor.attr("href"));
                if (name.toLowerCase(Locale.ROOT).endsWith(".pdf") && name.matches(".*(?:공고|모집).*") && match.matches()) {
                    String link = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + match.group(1);
                    if (attachments.stream().noneMatch(file -> file.url().equals(link)))
                        attachments.add(new Attachment(name, link));
                }
            }
            // A notice landing page is not a substitute for the actual qualifications in its document.
            if (attachments.isEmpty() || attachments.size() > 2)
                throw new IllegalArgumentException("GUIDE_SOURCE_DOCUMENTS_UNREADABLE");
        }
        // Institution/product and certificate names are part of the evidence, not site navigation.
        return new Content(normalize(title), normalize(title + "\n" + text), List.copyOf(attachments));
    }

    private List<Element> select(URI url, Document document) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String selector = switch (host) {
            case "apply.lh.or.kr" ->
                url.getPath().endsWith("selectWrtancInfo.do") ? "#sub_container > section" : "#cntntsView";
            case "www.gov.kr" -> ".contentsWrap .wrap_col > .left";
            case "efamily.scourt.go.kr" -> "#content .serviceWrap.iss, .csWrap";
            case "www.kohom.or.kr" -> "#content .faq_list";
            case "www.lh.or.kr", "www.mohw.go.kr" -> "#contents_body";
            case "www.seoulhousing.kr" -> ".sub_content_middle";
            default -> "main, [role=main], article";
        };
        return document.select(selector).stream().filter(element -> element.parent() == null
            || element.parents().stream().noneMatch(parent -> parent.is(selector))).toList();
    }

    private Content welfare(URI url, Document document) throws Exception {
        String requestedId = GuideSourcePolicy.queryValue(url, "wlfareInfoId");
        if (requestedId.isEmpty()) throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
        for (Element script : document.select("script")) {
            String source = script.data();
            String marker = "cpr.core.Platform.INSTANCE.initParameter(";
            int start = source.indexOf(marker);
            if (start < 0) continue;
            String payload = objectAt(source, start + marker.length());
            JsonNode initial = json.readTree(payload).path("initValue");
            JsonNode service = decode(initial.path("dmWlfareInfo"));
            if (!requestedId.equals(service.path("wlfareInfoId").asText()))
                throw new IllegalArgumentException("GUIDE_SOURCE_IDENTITY_MISMATCH");
            String title = service.path("wlfareInfoNm").asText("");
            List<String> lines = new ArrayList<>();
            lines.add(title);
            for (String field : List.of("crtrYr", "bizChrInstNm", "bizChrDeptNm", "wlfareInfoOutlCn", "wlfareSprtTrgtCn",
                "wlfareSprtTrgtSlcrCn", "wlfareSprtBnftCn", "aplyMtdDc", "aplyMtdDcdnm", "rprsCtadr")) {
                String value = service.path(field).asText("");
                if (!value.isBlank()) lines.add(text(Jsoup.parseBodyFragment(value).body()));
            }
            JsonNode details = decode(initial.path("dsWlfareInfoDtl"));
            if (details.isArray()) for (JsonNode detail : details) {
                if (!requestedId.equals(detail.path("wlfareInfoId").asText()))
                    throw new IllegalArgumentException("GUIDE_SOURCE_IDENTITY_MISMATCH");
                String label = detail.path("wlfareInfoReldNm").asText("");
                String value = detail.path("wlfareInfoReldCn").asText("");
                if (!label.isBlank() && !value.isBlank())
                    lines.add(text(Jsoup.parseBodyFragment(label + " " + value).body()));
            }
            if (service.path("wlfareSprtTrgtCn").asText("").isBlank() || service.path("wlfareSprtBnftCn").asText("").isBlank())
                throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
            return new Content(title, normalize(String.join("\n", lines)), List.of());
        }
        throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
    }

    private JsonNode decode(JsonNode node) throws Exception {
        return node.isString() ? json.readTree(node.asText()) : node;
    }

    private static String objectAt(String source, int offset) {
        int start = offset;
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) start++;
        if (start >= source.length() || source.charAt(start) != '{')
            throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new IllegalArgumentException("GUIDE_SOURCE_CONTENT_MISSING");
    }

    static String text(Element original) {
        Element element = original.clone();
        element.select("script, style, noscript, iframe, nav, header, footer, input, select, button, .wtViewer, .preview, .btns, .satisfaction, .sns, .share, .print, .loading, .lyPop, .visit-count, .view-count, .hit, .hits").remove();
        // Do not remove display:none: official FAQ answers are initially collapsed, yet contain the policy.
        for (Element candidate : new ArrayList<>(element.getAllElements())) {
            if (candidate != element && COUNTER.matcher(candidate.text()).matches()) candidate.remove();
        }
        for (Element image : element.select("img[alt]")) {
            if (!image.attr("alt").isBlank()) image.after(new TextNode(image.attr("alt")));
        }
        StringBuilder result = new StringBuilder();
        element.traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof Element tag && BLOCKS.contains(tag.normalName())) result.append('\n');
                if (node instanceof TextNode text) result.append(text.text()).append(' ');
            }

            public void tail(Node node, int depth) {
                if (node instanceof Element tag && BLOCKS.contains(tag.normalName())) result.append('\n');
                else if (node instanceof Element tag && Set.of("td", "th").contains(tag.normalName()))
                    result.append(" | ");
            }
        });
        return normalize(result.toString());
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace('\u00a0', ' ')
            .replaceAll("[\\p{Zs}\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n").strip();
    }
}
