package org.jibro.notice;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;

/**
 * Reads only LH's explicit correction navigation metadata; never evaluates remote JavaScript.
 */
public final class OfficialCorrectionLink {
    // Older official notices use zero-padded 30-digit IDs. Preserve the literal identity.
    private static final String LITERAL = "['\"]([0-9]{0,30})['\"]";
    private static final Pattern CURRENT = Pattern.compile(
        "(?m)^\\s*var\\s+panId\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+currPanId\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+panKdCd\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+currPanKdCd\\s*=\\s*" + LITERAL + "\\s*;");
    private static final Pattern NAVIGATION = Pattern.compile(
        "(?m)^\\s*function\\s+GoCrc\\s*\\(\\s*param\\s*\\)\\s*\\{\\s*"
            + "var\\s+ccrCnntSysDsCd\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+panKdCd\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+panId\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+sOtxtPanId\\s*=\\s*" + LITERAL + "\\s*;");
    private static final Pattern BRANCH_NAVIGATION = Pattern.compile(
        "(?m)^\\s*function\\s+GoCrc\\s*\\(\\s*param\\s*\\)\\s*\\{\\s*"
            + "var\\s+ccrCnntSysDsCd\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+panKdCd\\s*=\\s*" + LITERAL + "\\s*;\\s*"
            + "var\\s+panId\\s*=\\s*(?:''|\"\")\\s*;\\s*"
            + "if\\s*\\(\\s*param\\s*==\\s*['\"]curr['\"]\\s*\\)\\s*\\{\\s*panId\\s*=\\s*" + LITERAL + "\\s*;\\s*\\}\\s*"
            + "else\\s+if\\s*\\(\\s*param\\s*==\\s*['\"]otxt['\"]\\s*\\)\\s*\\{\\s*panId\\s*=\\s*" + LITERAL + "\\s*;\\s*\\}");
    private static final Pattern SCRIPT_TOKEN = Pattern.compile(
        "(?s)'(?:\\\\.|[^'\\\\])*+'|\"(?:\\\\.|[^\"\\\\])*+\"|`(?:\\\\.|[^`\\\\])*+`|/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern NAVIGATION_DEFINITION = Pattern.compile("\\bfunction\\s+GoCrc\\s*\\(");
    private static final Pattern NAVIGATION_REPLACEMENT = Pattern.compile("\\bGoCrc\\s*=(?!=)");
    private static final String NAVIGATION_VARIABLE = "(?:panId|sOtxtPanId|ccrCnntSysDsCd|panKdCd)";
    private static final Pattern NAVIGATION_MUTATION = Pattern.compile(
        "\\b" + NAVIGATION_VARIABLE + "\\s*(?:=(?!=)|[+*/%&|^?-]+=|\\+\\+|--)"
            + "|(?:\\+\\+|--)\\s*\\b" + NAVIGATION_VARIABLE + "\\b");
    // A verified LH template has a default original-ID fallback, followed by empty
    // alternate routes. Recognize the entire static block, not arbitrary assignments.
    private static final Pattern DEFAULT_NAVIGATION = Pattern.compile(
        "\\s*if\\s*\\(\\s*isEmpty\\s*\\(\\s*param\\s*\\)\\s*\\)\\s*\\{\\s*"
            + "if\\s*\\(\\s*sOtxtPanId\\s*==\\s*null\\s*\\|\\|\\s*sOtxtPanId\\s*==\\s*(?:''|\"\")\\s*\\)\\s*\\{\\s*"
            + "sOtxtPanId\\s*=\\s*" + LITERAL + "\\s*;\\s*\\}\\s*"
            + formValue("ccrCnntSysDsCd", "ccrCnntSysDsCd") + formValue("panKdCd", "panKdCd")
            + formValue("panId", "panId") + formValue("otxtPanId", "sOtxtPanId")
            + "\\}\\s*else\\s*\\{\\s*"
            + "if\\s*\\(\\s*param\\s*==\\s*['\"]curr['\"]\\s*\\)\\s*\\{\\s*panId\\s*=\\s*(?:''|\"\")\\s*;\\s*\\}\\s*"
            + "else\\s+if\\s*\\(\\s*param\\s*==\\s*['\"]otxt['\"]\\s*\\)\\s*\\{\\s*panId\\s*=\\s*(?:''|\"\")\\s*;\\s*\\}\\s*"
            + formValue("ccrCnntSysDsCd", "ccrCnntSysDsCd") + formValue("panKdCd", "panKdCd")
            + formValue("panId", "panId") + "\\}\\s*");
    private static final Pattern DEFAULT_SUBMISSION = Pattern.compile(
        "\\s*var\\s+chkOs\\s*=\\s*getCookie\\s*\\(\\s*['\"]d['\"]\\s*\\)\\s*;\\s*"
            + "if\\s*\\(\\s*['\"]ios['\"]\\s*==\\s*chkOs\\s*\\)\\s*\\{\\s*"
            + "\\$\\s*\\(\\s*['\"]#wrtancViewForm['\"]\\s*\\)\\s*\\.attr\\s*\\(\\s*['\"]method['\"]\\s*,\\s*['\"]get['\"]\\s*\\)\\s*;\\s*\\}\\s*"
            + "\\$\\s*\\(\\s*['\"]#wrtancViewForm['\"]\\s*\\)\\s*\\.attr\\s*\\(\\s*['\"]action['\"]\\s*,\\s*['\"]"
            + "/lhapply/apply/wt/wrtanc/selectWrtancInfo\\.do['\"]\\s*\\)\\s*\\.submit\\s*\\(\\s*\\)\\s*;\\s*");

    public record Identity(String panId, String system, String category, String type) {
    }

    /**
     * Only the bound detail's status field, never footer recommendations or document prose.
     */
    public static boolean explicitlyClosed(String url, String html) {
        var id = identity(url);
        if (html == null || html.length() > 2 * 1024 * 1024) throw invalid();
        var doc = Jsoup.parse(html);
        var ids = doc.select("form#wrtancViewForm input[name=panId]");
        if (ids.size() != 1 || !id.panId().equals(ids.first().val())) throw invalid();
        var statuses = doc.select(".bbs_ViewA .bbsV_data li").stream()
            .filter(li -> li.select("strong").text().equals("공고상태"))
            .map(li -> {
                var copy = li.clone();
                copy.select("strong").remove();
                return copy.text().strip();
            }).toList();
        if (statuses.size() > 1) throw invalid();
        return statuses.size() == 1 && Set.of("접수마감", "접수종료", "공고종료", "공고취소").contains(statuses.get(0));
    }

    public record Navigation(String latestUrl, String originalUrl, String kind) {
    }

    public static String withPanId(String url, String panId) {
        identity(url);
        if (!panId.matches("[0-9]{1,30}")) throw invalid();
        return Pattern.compile("([?&]panId=)[0-9]{1,30}(?=&|$)").matcher(url)
            .replaceFirst(match -> java.util.regex.Matcher.quoteReplacement(match.group(1) + panId));
    }

    /**
     * Candidates still require fresh two-page verification before any database write.
     */
    public static Optional<Navigation> navigation(String url, String html) {
        var id = identity(url);
        var p = page(html, id.panId());
        if (!p.current()[0].equals(id.panId())) throw invalid();
        if (!p.current()[3].equals("02")) return Optional.empty();
        if (!Set.of("01", "02").contains(p.current()[2]) || p.current()[1].isEmpty()
            || !p.navigation()[0].equals(id.system()) || !p.navigation()[1].equals("02")
            || !p.navigation()[2].equals(p.current()[1])) throw invalid();
        return Optional.of(new Navigation(withPanId(url, p.current()[1]),
            p.navigation()[3].isEmpty() ? null : withPanId(url, p.navigation()[3]), p.current()[2]));
    }

    /**
     * A complete, explicit official history can prove earlier corrections as well as the root.
     */
    public static Proof verifyFamilyHistory(String originalUrl, String originalHtml, String latestUrl,
                                            String latestHtml, String historyHtml) {
        var old = identity(originalUrl);
        var next = identity(latestUrl);
        if (!old.system().equals(next.system()) || !old.category().equals(next.category())
            || !old.type().equals(next.type()) || old.panId().equals(next.panId())) throw invalid();
        var entries = OfficialCorrectionHistory.read(latestUrl, historyHtml);
        var row = entries.stream().skip(1).filter(e -> identity(e.url()).equals(old)).findFirst().orElseThrow(OfficialCorrectionLink::invalid);
        var a = page(originalHtml, old.panId());
        var b = page(latestHtml, next.panId());
        boolean legacyRoot = legacyRoot(old, row, a);
        if (!(pointsToLatest(a, old, next, row.kind()) || legacyRoot)
            || !pointsToLatest(b, next, next, "02")
            || !row.title().equals(Jsoup.parse(originalHtml).select(".bbs_ViewA h3").text())
            || !entries.get(0).title().equals(Jsoup.parse(latestHtml).select(".bbs_ViewA h3").text())) throw invalid();
        String reason = familyReason(latestHtml, legacyPublicRental(old));
        return new Proof(old.panId(), next.panId(), hash(originalHtml), hash(latestHtml), reason, hash(historyHtml));
    }

    private static boolean pointsToLatest(Page page, Identity current, Identity latest, String kind) {
        return Arrays.equals(page.current(), new String[]{current.panId(), latest.panId(), kind, "02"})
            && page.navigation()[0].equals(current.system()) && page.navigation()[1].equals("02")
            && page.navigation()[2].equals(latest.panId());
    }

    /**
     * Old public-rental roots keep their own ID. Only an explicit official history row can select them.
     */
    private static boolean legacyRoot(Identity old, OfficialCorrectionHistory.Entry row, Page page) {
        return legacyPublicRental(old)
            && row.kind().equals("01")
            && Arrays.equals(page.current(), new String[]{old.panId(), old.panId(), "01", "01"})
            && Arrays.equals(page.navigation(), new String[]{"02", "01", old.panId(), ""});
    }

    private static boolean legacyPublicRental(Identity id) {
        return id.system().equals("02") && id.category().equals("06") && id.type().equals("08");
    }

    /**
     * Explicit editorial repair only: prove this exact root with the same official family evidence.
     */
    public static Proof verifyLegacyRootHistory(String originalUrl, String originalHtml, String latestUrl,
                                                String latestHtml, String historyHtml) {
        var old = identity(originalUrl);
        var row = OfficialCorrectionHistory.read(latestUrl, historyHtml).stream().skip(1)
            .filter(e -> identity(e.url()).equals(old)).findFirst().orElseThrow(OfficialCorrectionLink::invalid);
        if (!legacyRoot(old, row, page(originalHtml, old.panId()))) throw invalid();
        return verifyFamilyHistory(originalUrl, originalHtml, latestUrl, latestHtml, historyHtml);
    }

    private static String familyReason(String html, boolean legacyRoot) {
        var doc = Jsoup.parse(html);
        var reasons = doc.select("dt").stream().filter(e -> e.text().equals("정정사유"))
            .map(e -> e.nextElementSibling()).filter(e -> e != null && e.tagName().equals("dd"))
            .map(e -> e.text().strip()).toList();
        if (reasons.isEmpty()) {
            var bodies = doc.select(".bbsV_cont dt").stream()
                .filter(e -> e.text().equals("내용")).map(e -> e.nextElementSibling())
                .filter(e -> e != null && e.tagName().equals("dd")).flatMap(e -> e.select("pre").stream())
                .map(e -> e.wholeText().strip()).toList();
            // '수정정보' contains the substring '정정'; it is a field label, not a complete reason.
            if (legacyRoot && bodies.stream().anyMatch(s -> s.startsWith("수정정보")))
                reasons = bodies.stream().filter(s -> s.matches("(?s)^수정정보\\s*[:：]\\s*\\S.*")).toList();
            else reasons = bodies.stream().map(s -> s.split("\\R", 2)[0].strip())
                .filter(s -> !s.startsWith("수정정보") && s.contains("정정")).toList();
        }
        if (reasons.size() != 1 || reasons.get(0).isBlank() || reasons.get(0).length() > 2000) throw invalid();
        return reasons.get(0);
    }

    /**
     * Page identity only; an explicit attachment review must additionally prove the exact pair.
     */
    static Proof unlinkedLegacyPages(String originalUrl, String originalHtml, String latestUrl, String latestHtml) {
        var old = identity(originalUrl);
        var next = identity(latestUrl);
        if (!legacyPublicRental(old) || !legacyPublicRental(next) || old.equals(next)) throw invalid();
        var a = page(originalHtml, old.panId());
        var b = page(latestHtml, next.panId());
        if (!Arrays.equals(a.current(), new String[]{old.panId(), old.panId(), "01", "01"})
            || !Arrays.equals(a.navigation(), new String[]{"02", "01", old.panId(), ""})
            || !Arrays.equals(b.current(), new String[]{next.panId(), next.panId(), "02", "02"})
            || !Arrays.equals(b.navigation(), new String[]{"02", "02", next.panId(), ""})) throw invalid();
        return new Proof(old.panId(), next.panId(), hash(originalHtml), hash(latestHtml), familyReason(latestHtml, true));
    }

    public record Proof(String originalPanId, String correctedPanId, String originalHtmlSha256,
                        String correctedHtmlSha256, String reason, String historyHtmlSha256) {
        public Proof(String originalPanId, String correctedPanId, String originalHtmlSha256,
                     String correctedHtmlSha256, String reason) {
            this(originalPanId, correctedPanId, originalHtmlSha256, correctedHtmlSha256, reason, null);
        }
    }

    public static String historyUrl(String correctedUrl) {
        var id = identity(correctedUrl);
        return "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancHistoryInfoPopUp.do?panId=" + id.panId()
            + "&ccrCnntSysDsCd=" + id.system() + "&uppAisTpCd=" + id.category() + "&aisTpCd=" + id.type() + "&mi=1026";
    }

    /**
     * Public-rental sales templates expose the reverse identity in the official history popup.
     */
    public static Proof verifyHistory(String originalUrl, String originalHtml, String correctedUrl,
                                      String correctedHtml, String historyHtml) {
        var old = identity(originalUrl);
        var next = identity(correctedUrl);
        if (!old.system().equals("02") || !old.category().equals("06") || !old.type().equals("08")
            || !old.system().equals(next.system()) || !old.category().equals(next.category())
            || !old.type().equals(next.type()) || old.panId().equals(next.panId())) throw invalid();
        var a = page(originalHtml, old.panId());
        var b = page(correctedHtml, next.panId());
        if (!Arrays.equals(a.current(), new String[]{old.panId(), next.panId(), "01", "02"})
            || !Arrays.equals(b.current(), new String[]{next.panId(), next.panId(), "02", "02"})
            || !Arrays.equals(a.navigation(), new String[]{"02", "02", next.panId(), ""})
            || !Arrays.equals(b.navigation(), new String[]{"02", "02", next.panId(), ""})) throw invalid();
        if (historyHtml == null || historyHtml.length() > 2 * 1024 * 1024) throw invalid();
        var rows = Jsoup.parse(historyHtml).select("table:has(caption) tbody tr");
        if (rows.size() != 2 || !rows.get(0).select("th").text().equals("정정공고")
            || !rows.get(1).select("th").text().equals("일반공고")) throw invalid();
        var currentCells = rows.get(0).select("td");
        var oldCells = rows.get(1).select("td");
        if (currentCells.size() != 3 || oldCells.size() != 3
            || !currentCells.get(1).text().equals(Jsoup.parse(correctedHtml).select(".bbs_ViewA h3").text())
            || !oldCells.get(1).text().equals(Jsoup.parse(originalHtml).select(".bbs_ViewA h3").text())
            || !rows.get(0).select("a").isEmpty() || rows.get(1).select("a").size() != 1
            || !rows.get(1).select("a").attr("href").equals("javascript:goUrl('" + old.panId() + "','02')"))
            throw invalid();
        try {
            if (java.time.LocalDate.parse(currentCells.get(0).text()).isBefore(java.time.LocalDate.parse(oldCells.get(0).text())))
                throw invalid();
        } catch (java.time.format.DateTimeParseException ex) {
            throw invalid();
        }
        var reasons = Jsoup.parse(correctedHtml).select(".bbsV_cont dt").stream()
            .filter(e -> e.text().equals("내용")).map(e -> e.nextElementSibling())
            .filter(e -> e != null && e.tagName().equals("dd"))
            .flatMap(e -> e.select("pre").stream()).map(e -> e.wholeText().strip().split("\\R", 2)[0].strip())
            .filter(s -> s.contains("정정") && !s.isBlank() && s.length() <= 2000).toList();
        if (reasons.size() != 1) throw invalid();
        return new Proof(old.panId(), next.panId(), hash(originalHtml), hash(correctedHtml), reasons.get(0), hash(historyHtml));
    }

    private OfficialCorrectionLink() {
    }

    public static Identity identity(String value) {
        try {
            var uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || !"/lhapply/apply/wt/wrtanc/selectWrtancInfo.do".equals(uri.getPath())) throw invalid();
            Map<String, String> query = new HashMap<>();
            for (var field : Objects.requireNonNull(uri.getRawQuery()).split("&")) {
                var pair = field.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String content = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (query.putIfAbsent(key, content) != null) throw invalid();
            }
            String pan = query.get("panId"), system = query.get("ccrCnntSysDsCd"),
                category = query.get("uppAisTpCd"), type = query.get("aisTpCd");
            if (pan == null || !pan.matches("[0-9]{1,30}") || system == null || !system.matches("[0-9]{2}")
                || category == null || !category.matches("[0-9]{2}") || type == null || !type.matches("[0-9]{2}"))
                throw invalid();
            return new Identity(pan, system, category, type);
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    public static Proof verify(String originalUrl, String originalHtml, String correctedUrl, String correctedHtml) {
        var original = identity(originalUrl);
        var corrected = identity(correctedUrl);
        if (original.panId().equals(corrected.panId()) || !original.system().equals(corrected.system())
            || !original.category().equals(corrected.category()) || !original.type().equals(corrected.type()))
            throw invalid();
        var oldPage = page(originalHtml, original.panId());
        var newPage = page(correctedHtml, corrected.panId());
        if (!oldPage.current()[0].equals(original.panId()) || !oldPage.current()[1].equals(corrected.panId())
            || !oldPage.current()[2].equals("01") || !oldPage.current()[3].equals("02")
            || !newPage.current()[0].equals(corrected.panId()) || !newPage.current()[1].equals(corrected.panId())
            || !newPage.current()[2].equals("02") || !newPage.current()[3].equals("02")
            || !oldPage.navigation()[0].equals(original.system()) || !oldPage.navigation()[1].equals("02")
            || !oldPage.navigation()[2].equals(corrected.panId())
            || !newPage.navigation()[0].equals(corrected.system()) || !newPage.navigation()[1].equals("02")
            || !newPage.navigation()[2].equals(corrected.panId()) || !newPage.navigation()[3].equals(original.panId()))
            throw invalid();
        var reasons = Jsoup.parse(correctedHtml).select("dt").stream()
            .filter(e -> e.text().strip().equals("정정사유"))
            .map(e -> e.nextElementSibling())
            .filter(e -> e != null && e.tagName().equals("dd"))
            .map(e -> e.text().strip()).toList();
        if (reasons.size() != 1 || reasons.get(0).isBlank() || reasons.get(0).length() > 2000) throw invalid();
        return new Proof(original.panId(), corrected.panId(), hash(originalHtml), hash(correctedHtml), reasons.get(0));
    }

    private record Page(String[] current, String[] navigation) {
    }

    private static Page page(String html, String panId) {
        if (html == null || html.length() > 2 * 1024 * 1024) throw invalid();
        var doc = Jsoup.parse(html);
        var ids = doc.select("form#wrtancViewForm input[name=panId]");
        if (ids.size() != 1 || !panId.equals(ids.first().val())) throw invalid();
        String scripts = String.join("\n", doc.select("script:not([src])").stream().map(e -> e.data()).toList());
        scripts = maskTokens(scripts, false);
        String code = maskTokens(scripts, true);
        var definition = NAVIGATION_DEFINITION.matcher(code);
        if (!definition.find()) throw invalid();
        int definitionStart = definition.start();
        if (definition.find() || NAVIGATION_REPLACEMENT.matcher(code).find()) throw invalid();
        var navigation = new ArrayList<String[]>();
        for (var pattern : List.of(NAVIGATION, BRANCH_NAVIGATION)) {
            var match = pattern.matcher(scripts);
            while (match.find()) {
                int start = match.start();
                while (Character.isWhitespace(code.charAt(start))) start++;
                if (start != definitionStart) throw invalid();
                // Both supported prefixes leave only the outer function brace open.
                // Inspect the remaining body as text; remote JavaScript is never executed.
                int depth = 1, end = match.end();
                while (end < code.length() && depth > 0) {
                    char next = code.charAt(end++);
                    if (next == '{') depth++;
                    else if (next == '}') depth--;
                }
                if (depth != 0 || scripts.substring(match.end(), end).contains("`")) throw invalid();
                int checkedEnd = match.end();
                if (pattern == NAVIGATION) {
                    var defaults = DEFAULT_NAVIGATION.matcher(scripts.substring(match.end(), end - 1));
                    if (defaults.lookingAt()) {
                        if (!defaults.group(1).equals(panId)) throw invalid();
                        checkedEnd += defaults.end();
                        if (!DEFAULT_SUBMISSION.matcher(scripts.substring(checkedEnd, end - 1)).matches())
                            throw invalid();
                    }
                }
                if (NAVIGATION_MUTATION.matcher(code.substring(checkedEnd, end)).find()) throw invalid();
                navigation.add(new String[]{match.group(1), match.group(2), match.group(3), match.group(4)});
            }
        }
        if (navigation.size() != 1) throw invalid();
        return new Page(single(CURRENT, scripts), navigation.get(0));
    }

    /**
     * Keep offsets and line boundaries stable while ignoring comments and, optionally, strings.
     */
    private static String maskTokens(String text, boolean strings) {
        var result = new StringBuilder(text);
        var tokens = SCRIPT_TOKEN.matcher(text);
        while (tokens.find()) {
            if (!strings && text.charAt(tokens.start()) != '/') continue;
            for (int i = tokens.start(); i < tokens.end(); i++) {
                char value = text.charAt(i);
                if (value != '\n' && value != '\r') result.setCharAt(i, ' ');
            }
        }
        return result.toString();
    }

    private static String[] single(Pattern pattern, String text) {
        var match = pattern.matcher(text);
        if (!match.find()) throw invalid();
        var values = new String[]{match.group(1), match.group(2), match.group(3), match.group(4)};
        if (match.find()) throw invalid();
        return values;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String formValue(String field, String variable) {
        return "\\$\\s*\\(\\s*['\"]#" + field + "['\"]\\s*\\)\\s*\\.\\s*val\\s*\\(\\s*"
            + variable + "\\s*\\)\\s*;\\s*";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OFFICIAL_CORRECTION_LINK_UNVERIFIED");
    }
}
