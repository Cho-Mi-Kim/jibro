package org.jibro.collection.review;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Adds a physical PDF page only for an explicitly identified top-level PDF page.
 */
final class EvidenceLocationUrl {
    private static final Pattern PAGE = Pattern.compile("공고문 ([1-9][0-9]{0,3})쪽");

    private EvidenceLocationUrl() {
    }

    static String forSource(Source source, String location, String anchor) {
        if (location != null && location.endsWith(" · 공식 표")) {
            if (anchor == null || source.excerpts() == null) return source.url();
            return source.excerpts().stream().filter(e -> OfficialGuidanceRows.ROW_EVIDENCE.equals(e.category())
                && location.equals(e.location()) && anchor.strip().equals(e.text())
                && e.url().startsWith(source.url() + "#doc2=" + source.sha256() + ".")).map(e -> e.url()).findFirst().orElse(source.url());
        }
        String pdf = forPart(source.label(), source.url(), location);
        if (!java.util.Objects.equals(pdf, source.url()))
            return source.sha256() != null && source.sha256().matches("[0-9a-f]{64}")
                ? pdf + "&sha256=" + source.sha256() : pdf;
        if (source.label() == null || source.sha256() == null || !source.sha256().matches("[0-9a-f]{64}") || anchor == null)
            return source.url();
        String label = source.label().toLowerCase(Locale.ROOT);
        boolean document = label.endsWith(".hwp") || label.endsWith(".hwpx") || label.endsWith(".xls") || label.endsWith(".xlsx");
        boolean archiveDocument = label.endsWith(".zip") && location != null
            && location.toLowerCase(Locale.ROOT).matches(".*\\.(?:hwp|hwpx|xls|xlsx) · .+");
        if (!document && !archiveDocument) return source.url();
        try {
            var uri = URI.create(source.url());
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost())
                || !"/lhapply/lhFile.do".equals(uri.getPath()) || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getFragment() != null || uri.getRawQuery() == null || !uri.getRawQuery().matches("fileid=[0-9]{1,20}"))
                return source.url();
            int part = -1;
            for (int i = 0; i < source.context().size(); i++) {
                if (location != null && location.equals(source.context().get(i).location())) {
                    if (part != -1) return source.url();
                    part = i;
                }
            }
            if (part < 0) return source.url();
            String partText = source.context().get(part).text();
            if (partText == null) return source.url();
            String candidate = anchor.lines().map(String::strip).filter(line -> line.length() >= 8 && !line.startsWith("⟦") && partText.indexOf(line) >= 0 && partText.indexOf(line) == partText.lastIndexOf(line)).findFirst().orElse("");
            if (candidate.isEmpty()) return source.url();
            if (candidate.codePointCount(0, candidate.length()) > 100)
                candidate = candidate.substring(0, candidate.offsetByCodePoints(0, 100));
            if (partText.indexOf(candidate) != partText.lastIndexOf(candidate)) return source.url();
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(candidate.getBytes(StandardCharsets.UTF_8));
            String encodedLocation = Base64.getUrlEncoder().withoutPadding().encodeToString(location.getBytes(StandardCharsets.UTF_8));
            if (encodedLocation.length() > 600) return source.url();
            return source.url() + "#doc2=" + source.sha256() + "." + encodedLocation + "." + encoded;
        } catch (IllegalArgumentException invalid) {
            return source.url();
        }
    }

    static String base(String url) {
        return url == null ? null : url.split("#", 2)[0];
    }

    static boolean matchesPart(Source source, org.jibro.collection.extraction.ExtractionModels.Part part, String url) {
        if (source.url().equals(url)) return true;
        if (!source.url().equals(base(url))) return false;
        if (url.contains("#page=")) return url.equals(forSource(source, part.location(), null));
        String prefix = source.url() + "#doc2=" + source.sha256() + ".";
        if (!url.startsWith(prefix)) return false;
        String[] fields = url.substring(prefix.length()).split("\\.", -1);
        if (fields.length != 2 || fields[1].length() > 550) return false;
        try {
            String excerpt = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            return url.equals(forSource(source, part.location(), excerpt));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static String forExcerpt(Source source, String location, String anchor) {
        if (location == null) return source.url();
        var matches = source.context().stream().filter(part -> part.location() != null
            && (location.equals(part.location()) || location.startsWith(part.location() + " · ")
            || location.endsWith(" · " + part.location()))).toList();
        if (matches.size() != 1) return source.url();
        return forSource(source, matches.get(0).location(), anchor);
    }

    static String forTerms(Source source, String... terms) {
        String matched = null;
        for (var part : source.context()) {
            for (String line : part.text().lines().toList()) {
                String compact = line.replaceAll("[\\s\\p{Zs}]+", "");
                boolean contains = java.util.Arrays.stream(terms).filter(term -> term != null && !term.isBlank())
                    .anyMatch(term -> compact.contains(term.replaceAll("[\\s\\p{Zs}]+", "")));
                if (!contains) continue;
                String result = forSource(source, part.location(), line);
                if (result.equals(source.url())) continue;
                if (matched != null && !matched.equals(result)) return source.url();
                matched = result;
            }
        }
        return matched == null ? source.url() : matched;
    }

    static String forPart(String label, String url, String location) {
        if (label == null || url == null || location == null || !label.toLowerCase(Locale.ROOT).endsWith(".pdf"))
            return url;
        var match = PAGE.matcher(location);
        if (!match.matches()) return url;
        try {
            var uri = URI.create(url);
            if (url.matches("https://www[.]myhome[.]go[.]kr/hws/com/fms/cvplFileDownload[.]do[?]atchFileId=[A-Za-z0-9_-]{1,80}&fileSn=[0-9]{1,4}"))
                return url + "#page=" + match.group(1);
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost())
                || !"/lhapply/lhFile.do".equals(uri.getPath()) || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getFragment() != null || uri.getRawQuery() == null
                || !uri.getRawQuery().matches("fileid=[0-9]{1,20}")) return url;
            return url + "#page=" + match.group(1);
        } catch (IllegalArgumentException invalid) {
            return url;
        }
    }
}
