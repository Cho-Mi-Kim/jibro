package org.jibro.notice;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The same official-source boundary is used by authored guides and automated checks.
 */
public final class GuideSourcePolicy {
    private static final Set<String> DOMAINS = Set.of("lh.or.kr", "myhome.go.kr", "gov.kr", "scourt.go.kr",
        "molit.go.kr", "i-sh.co.kr", "gh.or.kr", "ih.co.kr", "bokjiro.go.kr", "kohom.or.kr", "law.go.kr",
        "mohw.go.kr", "seoulhousing.kr");
    private static final Set<String> RESOURCE_KEYS = Set.of("panid", "cappbizcd", "wlfareinfoid", "cntntsid",
        "list_no", "seq", "fileid", "srno", "bbscttsn", "nttid", "boardseq", "mid");
    private static final Set<String> SECRET_KEYS = Set.of("servicekey", "apikey", "key", "token", "accesstoken",
        "refreshtoken", "idtoken", "authorization", "password", "secret", "clientsecret", "sessionid", "jsessionid");

    private GuideSourcePolicy() {
    }

    public static boolean isOfficialUrl(String url) {
        try {
            requireOfficialUri(url);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    public static URI requireOfficialUri(String url) {
        if (url == null || url.length() > 2000 || url.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
            || uri.getPort() != -1 && uri.getPort() != 443 || agency(uri).isEmpty())
            throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
        if (query(uri).keySet().stream().map(key -> key.replaceAll("[^a-z0-9]", "")).anyMatch(SECRET_KEYS::contains))
            throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
        int fragment = url.indexOf('#');
        return fragment < 0 ? uri : URI.create(url.substring(0, fragment));
    }

    public static boolean sameAgency(URI from, URI to) {
        return !agency(from).isEmpty() && agency(from).equals(agency(to));
    }

    /**
     * A redirect to another notice/service is not a replacement for the reviewed source.
     */
    public static boolean preservesResourceIdentity(URI from, URI to) {
        Map<String, String> previous = query(from), next = query(to);
        for (String key : RESOURCE_KEYS) {
            if (previous.containsKey(key) && next.containsKey(key) && !previous.get(key).equals(next.get(key)))
                return false;
        }
        return true;
    }

    static String queryValue(URI uri, String name) {
        return query(uri).getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }

    private static String agency(URI uri) {
        if (uri == null || uri.getHost() == null) return "";
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return DOMAINS.stream().filter(domain -> host.equals(domain) || host.endsWith("." + domain)).findFirst().orElse("");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        if (uri.getRawQuery() == null) return result;
        try {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                // Ambiguous repeated identity parameters cannot be used as a safe redirect.
                if (result.containsKey(key) && !result.get(key).equals(value))
                    throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
                result.put(key, value);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("GUIDE_SOURCE_URL_REJECTED");
        }
        return result;
    }
}
