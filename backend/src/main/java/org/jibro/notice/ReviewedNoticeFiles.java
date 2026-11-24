package org.jibro.notice;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.HousingModels.*;

/**
 * Download links independently checked against the LH detail page for a published notice version.
 */
public final class ReviewedNoticeFiles {
    private record Form(String documentId, String title, String url, String label, String member) {
    }

    private record Entry(String noticeId, int version, List<Evidence> attachments, List<Form> forms) {
    }

    private static final Map<String, Entry> ENTRIES = load();

    private ReviewedNoticeFiles() {
    }

    private static Map<String, Entry> load() {
        try (var stream = ReviewedNoticeFiles.class.getResourceAsStream("/reviewed-notice-files.json")) {
            var entries = new ObjectMapper().readValue(Objects.requireNonNull(stream), Entry[].class);
            return Arrays.stream(entries).collect(Collectors.toUnmodifiableMap(Entry::noticeId, Function.identity()));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static List<Evidence> attachments(String id, int version, List<Evidence> published) {
        var entry = ENTRIES.get(id);
        if (entry == null || entry.version() != version || entry.attachments().isEmpty()) return published;
        var result = new ArrayList<>(published);
        var urls = published.stream().map(Evidence::url).collect(Collectors.toSet());
        for (var file : entry.attachments()) {
            if (urls.add(file.url())) result.add(file);
        }
        return List.copyOf(result);
    }

    static List<DocumentTemplate> documents(String id, int version, List<DocumentTemplate> documents, List<Evidence> attachments) {
        return documents(id, version, documents, attachments, null);
    }

    static List<DocumentTemplate> documents(String id, int version, List<DocumentTemplate> documents, List<Evidence> attachments, OfficialFileManifest live) {
        var entry = ENTRIES.get(id);
        if (entry == null || entry.version() != version || entry.forms().isEmpty()) return documents;
        var urls = attachments.stream().map(Evidence::url).collect(Collectors.toSet());
        return documents.stream().map(d -> {
            if (d.issueUrl() != null && !d.issueUrl().isBlank()) return d;
            for (var form : entry.forms()) {
                if (form.documentId().equals(d.id()) && form.title().equals(d.title()) && urls.contains(form.url())
                        && (live == null || live.contains(form.url(), form.member()))) {
                    return withForm(d, form.url(), form.label(), form.member());
                }
            }
            return d;
        }).toList();
    }

    static DocumentTemplate withForm(DocumentTemplate d, String url, String file, String member) {
        String label = member == null ? (url.startsWith("https://apply.lh.or.kr/") ? "LH 공식 양식 · " : "공식 양식 · ") + file : file + " 내려받기 · ZIP 안의 " + member;
        return new DocumentTemplate(d.id(), d.title(), d.conditionQuestionId(), d.method(),
                d.issuer(), url, d.evidence(), d.conditionAnswerValues(), label);
    }

    /**
     * Include the published form on saved and one-time checklists of the same version.
     */
    public static void applyToPreparation(Preparation p, Notice notice) {
        if (!Objects.equals(p.noticeId, notice.id()) || p.version != notice.version()) return;
        var templates = notice.documentTemplates().stream()
                .collect(Collectors.toMap(DocumentTemplate::id, Function.identity()));
        for (var d : p.documents) {
            var template = templates.get(d.id);
            if (template != null && template.title().equals(d.title) && (d.issueLabel != null || (template.issueLabel() != null && (d.issueUrl == null || d.issueUrl.isBlank())))) {
                d.issueUrl = template.issueUrl();
                d.issueLabel = template.issueLabel();
            }
        }
    }
}
