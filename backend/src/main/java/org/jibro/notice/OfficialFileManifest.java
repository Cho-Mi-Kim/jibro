package org.jibro.notice;

import java.text.Normalizer;
import java.util.*;
import static org.jibro.notice.HousingModels.*;

/** Published file names come from one bound official detail page and its ZIP directories. */
public record OfficialFileManifest(String checkedAt, List<File> files) {
    public record File(String label, String url, List<String> members) {}

    public List<Evidence> attachments(List<Evidence> published) {
        var result = new LinkedHashMap<String, Evidence>();
        for (var source : published) result.putIfAbsent(source.url().split("#", 2)[0], source);
        for (var file : files) result.put(file.url(), new Evidence(file.label(), file.url(), checkedAt));
        return List.copyOf(result.values());
    }

    private record Match(File file, String member) {}

    public List<DocumentTemplate> documents(List<DocumentTemplate> documents) {
        var candidates = new HashMap<String, List<Match>>();
        for (var file : files) {
            if (file.members() == null) continue; // ZIP directory unavailable, still a public attachment
            if (file.label().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                for (var member : file.members()) index(candidates, file, member);
            } else index(candidates, file, null);
        }
        return documents.stream().map(doc -> {
            if (doc.issueUrl() != null && !doc.issueUrl().isBlank()) return doc;
            var matches = candidates.getOrDefault(key(doc.title()), List.of());
            if (matches.size() != 1) return doc;
            var match = matches.get(0);
            return ReviewedNoticeFiles.withForm(doc, match.file().url(), match.file().label(), match.member());
        }).toList();
    }

    boolean contains(String url, String member) {
        return files.stream().anyMatch(file -> file.url().equals(url)
            && (member == null || file.members() != null && file.members().contains(member)));
    }

    private static void index(Map<String, List<Match>> candidates, File file, String member) {
        String path = member == null ? file.label() : member;
        if (!path.toLowerCase(Locale.ROOT).matches(".*\\.(?:hwp|hwpx|pdf|docx?|xlsx?)$")
            || path.matches("(?i).*(예시|견본|샘플|작성례|작성예|작성방법|작성요령|sample|example).*")) return;
        String name = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
        name = name.replaceFirst("^\\s*(?:(?:\\[(?:별지|별첨|붙임|첨부|양식|서식)[^\\]]*\\]|(?:별지|붙임|양식|서식)\\s*제?\\s*\\d+\\s*호?|\\d+[.)])\\s*[-_. ]*)+", "")
            .replaceFirst("\\s*\\((?:개정|입주신청및자격검증용)\\)\\s*$", "");
        String key = key(name);
        if (key.length() >= 4) candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Match(file, member));
    }

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
