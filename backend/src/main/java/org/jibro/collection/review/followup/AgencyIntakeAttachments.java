package org.jibro.collection.review.followup;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.BoundedDocumentParser;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.IntakeChannelDates.Attachment;
import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jibro.collection.review.ScheduleSourceReader;
import org.jsoup.nodes.Element;

final class AgencyIntakeAttachments {
    record FileLink(String name, String url) {
    }

    private static final Pattern NAME = Pattern.compile("(?i)^(.{1,240}?\\.(?:pdf|hwpx?|xlsx?|zip))(?=$|[\\s)\\]])");
    private static final Pattern GB = Pattern.compile("openDownloadFiles\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\);?\\s*(?:return false;?)?");
    private static final Pattern DONG = Pattern.compile("yhLib\\.file\\.download\\('([0-9A-Fa-f]+)',\\s*'([0-9A-Fa-f]+)'\\);?\\s*(?:return false;?)?");
    private static final Pattern TEMPLATE = Pattern.compile("신청서(?:식|양식)?(?:[.(_\\s]|$)|개인정보.*동의서|주택목록|평면도");
    private static final Pattern SEPARATE = Pattern.compile("별도\\s*모집|별개의\\s*모집|\\d+\\s*차\\s*(?:추가\\s*)?모집");
    private static final Pattern OLD = Pattern.compile("변경\\s*전|정정\\s*전|종전\\s*(?:일정|접수)");
    private final BoundedDocumentParser parser;
    private final Map<String, AttachmentFingerprintClient> clients = new HashMap<>();
    private int downloads;

    AgencyIntakeAttachments(BoundedDocumentParser parser) {
        this.parser = parser;
    }

    private AttachmentFingerprintClient.Download download(String host, String url) throws Exception {
        var client = clients.computeIfAbsent(host, AttachmentFingerprintClient::new);
        try {
            return client.download(url);
        } catch (java.util.concurrent.ExecutionException failed) {
            if (!(failed.getCause() instanceof IOException cause) || !"ATTACHMENT_HTTP_STATUS".equals(cause.getMessage()))
                throw failed;
            // The official board sometimes fails an individual GET while its article and sibling forms remain available.
            var retry = new AttachmentFingerprintClient(host);
            clients.put(host, retry);
            return retry.download(url);
        }
    }

    List<Window> read(AgencyIntakePost.Post post, OfficialFollowupModels.Target target) throws IOException {
        var results = new ArrayList<>(post.bodyWindows());
        if (post.issued().isBefore(target.publishedDate())) return results;
        for (var file : links(post)) {
            if (AgencyIntakePost.separate(file.name(), target.title()) || TEMPLATE.matcher(file.name()).find())
                continue;
            if (++downloads > 12) throw new IOException("AGENCY_ATTACHMENT_LIMIT");
            try {
                String host = URI.create(post.url()).getHost();
                var download = download(host, file.url());
                var parts = parser.parse(download.bytes(), download.fingerprint().mediaType());
                results.addAll(claims(post, target, file.name(), download.fingerprint().sha256(), parts));
            } catch (AgencyIntakePost.InvalidField invalid) {
                throw invalid;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IOException("AGENCY_ATTACHMENT_RECHECK_REQUIRED", failure);
            }
        }
        return results;
    }

    OfficialFollowupModels.Related preparation(AgencyIntakePost.Post post, OfficialFollowupModels.Target target, List<Source> primary) throws IOException {
        var sources = new ArrayList<Source>();
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String sha = HexFormat.of().formatHex(digest.digest(post.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            sources.add(new Source(post.title(), post.url(), sha, "EXTRACTED", "", List.of(),
                    List.of(new Part("공식 게시글", post.body()))));
            for (var file : links(post)) {
                if (AgencyIntakePost.separate(file.name(), target.title()) || file.name().matches(".*(?:주택목록|평면도|배치도).*"))
                    continue;
                if (++downloads > 12) throw new IOException("AGENCY_ATTACHMENT_LIMIT");
                AttachmentFingerprintClient.Download download;
                try {
                    download = download(URI.create(post.url()).getHost(), file.url());
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception unavailable) {
                    sources.add(new Source(file.name(), file.url(), null, "UNREAD", "첨부 수집 재시도 대기", List.of(), List.of()));
                    continue;
                }
                String fingerprint = download.fingerprint().sha256();
                var identical = primary.stream().filter(original -> fingerprint.equals(original.sha256())
                        && "EXTRACTED".equals(original.status()) && !original.context().isEmpty()).findFirst();
                List<Part> parts;
                try {
                    parts = identical.isPresent() ? identical.get().context() : parser.parse(download.bytes(), download.fingerprint().mediaType());
                } catch (Exception failed) {
                    if (Thread.currentThread().isInterrupted() || failed instanceof InterruptedException) throw failed;
                    sources.add(new Source(file.name(), file.url(), fingerprint, "UNREAD", "첨부 원문 재판독 대기", List.of(), List.of()));
                    continue;
                }
                var nativeParts = parts.stream().filter(part -> !part.text().isBlank() && !OcrProvenance.contains(part)).toList();
                boolean complete = !nativeParts.isEmpty() && nativeParts.size() == parts.size();
                parts = nativeParts;
                if (parts.isEmpty()) {
                    sources.add(new Source(file.name(), file.url(), fingerprint, "UNREAD", "첨부 원문 재판독 대기", List.of(), List.of()));
                    continue;
                }
                if (identical.isEmpty()) {
                    if (file.name().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        var members = parts.stream().collect(java.util.stream.Collectors.groupingBy(part ->
                                        part.location().contains(" · ") ? part.location().substring(0, part.location().lastIndexOf(" · ")) : file.name(),
                                LinkedHashMap::new, java.util.stream.Collectors.toList()));
                        var matched = new ArrayList<Part>();
                        for (var member : members.entrySet()) {
                            if (samePreparationFile(target, member.getKey(), member.getValue()))
                                matched.addAll(member.getValue());
                        }
                        parts = List.copyOf(matched);
                        if (parts.isEmpty()) continue;
                    } else if (!samePreparationFile(target, file.name(), parts)) continue;
                }
                sources.add(new Source(file.name(), file.url(), fingerprint, complete ? "EXTRACTED" : "PARTIAL", complete ? "" : "일부 위치 재판독 대기", List.of(), parts));
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("AGENCY_PREPARATION_RECHECK", failure);
        }
        return new OfficialFollowupModels.Related("AGENCY", post.url(), post.title(), post.issued(), post.proof(), sources);
    }

    static boolean samePreparationFile(OfficialFollowupModels.Target target, String name, List<Part> parts) {
        // A form can quote a previous pledge date in its terms. Only the document identity,
        // not its eligibility examples or renewal terms, identifies a different recruitment.
        String title = parts.get(0).text().lines().map(String::strip)
                .filter(line -> line.length() <= 140 && line.matches(".*(?:입주자.{0,12}모집|모집\\s*공고).*"))
                .findFirst().orElse("");
        String heading = name + "\n" + title;
        if (AgencyIntakePost.separate(heading, target.title()) || heading.contains("별도 모집")
                || OfficialFollowupRelationship.multipleRecruitments(target, List.of(new Part("첨부 제목", heading)))
                || !OfficialFollowupRelationship.identity(target.title()).housing().containsAll(OfficialFollowupRelationship.identity(heading).housing()))
            return false;
        var dates = OfficialFollowupRelationship.references(heading);
        if (!dates.isEmpty() && !dates.equals(Set.of(target.publishedDate()))) return false;
        // A named notice must identify the target. Generic forms inherit the verified post's scope.
        if (!title.isBlank() || name.matches(".*(?:모집공고|모집 공고).*")) {
            var keyword = OfficialFollowupRelationship.queryTerm(target);
            if (keyword.isPresent() && !heading.replaceAll("\\s+", "").contains(keyword.get().replaceAll("\\s+", "").replaceFirst("(?<=[가-힣]{3})단지$", "")))
                return false;
        }
        for (var part : parts) {
            var ids = Pattern.compile("(?i)panId=([0-9]+)").matcher(part.text());
            while (ids.find())
                if (!target.primaryUrl().matches(".*[?&]panId=" + ids.group(1) + "(?:&.*|$)")) return false;
        }
        return true;
    }

    static List<FileLink> links(AgencyIntakePost.Post post) {
        URI base = URI.create(post.url());
        var links = new LinkedHashSet<FileLink>();
        for (var a : post.article().select("a[href]")) {
            if ((a.attr("title") + " " + a.text()).matches("(?is).*(?:미리보기|바로보기|전용뷰어).*")) continue;
            String name = filename(a);
            if (name.isEmpty()) continue;
            String href = a.attr("href");
            if (href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                var gb = GB.matcher(a.attr("onclick").strip());
                var dong = DONG.matcher(a.attr("onclick").strip());
                if (base.getHost().equals("www.gbgs.go.kr") && gb.matches())
                    href = "/programs/board/download.do?parm_file_uid=" + gb.group(1) + "&bod_uid=" + gb.group(2) + "&mnu_uid=" + gb.group(3);
                else if (base.getHost().equals("www.dong.daegu.kr") && dong.matches())
                    href = "/common/file/download.do?atchFileId=" + dong.group(1) + "&fileSn=" + dong.group(2);
                else continue;
            }
            try {
                URI url = base.resolve(href);
                if (!"https".equals(url.getScheme()) || !base.getHost().equals(url.getHost()) || url.getPort() != -1
                        || url.getUserInfo() != null || url.getFragment() != null || url.equals(base)
                        || url.getPath().toLowerCase(Locale.ROOT).matches(".*(?:viewer|preview|synap).*")) continue;
                links.add(new FileLink(name, url.toASCIIString()));
            } catch (IllegalArgumentException ignored) { /* Malformed board link. */ }
        }
        return List.copyOf(links);
    }

    private static String filename(Element anchor) {
        for (String value : List.of(anchor.text(), anchor.attr("download"), anchor.attr("title"),
                anchor.attr("href").replaceFirst("^.*/", ""))) {
            var m = NAME.matcher(value.strip().replaceFirst("^첨부파일\\(", ""));
            if (m.find()) return m.group(1).replace('+', ' ');
        }
        return "";
    }

    static List<Window> claims(AgencyIntakePost.Post post, OfficialFollowupModels.Target target,
                               String name, String sha, List<Part> parts) {
        var nativeParts = parts.stream().filter(p -> !OcrProvenance.contains(p) && !p.text().isBlank()).toList();
        if (nativeParts.isEmpty()) return List.of();
        String contents = String.join("\n", nativeParts.stream().map(Part::text).toList());
        String heading = name + "\n" + nativeParts.get(0).text().lines().limit(4).reduce("", (a, b) -> a + "\n" + b);
        var identity = OfficialFollowupRelationship.identity(target.title());
        var refs = OfficialFollowupRelationship.references(contents);
        var round = SEPARATE.matcher(heading);
        while (round.find())
            if (!target.title().replaceAll("\\s", "").contains(round.group().replaceAll("\\s", ""))) return List.of();
        boolean separateHeading = nativeParts.stream().flatMap(part -> part.text().lines())
                .filter(line -> line.length() <= 120 && line.matches(".*(?:모집\\s*공고|입주자\\s*모집|별도\\s*모집).*"))
                .anyMatch(line -> AgencyIntakePost.separate(line, target.title()) || line.contains("별도 모집"));
        if (separateHeading || AgencyIntakePost.separate(heading, target.title())
                || OfficialFollowupRelationship.multipleRecruitments(target, List.of(new Part("첨부 제목", heading), new Part("첨부 본문", contents)))
                || !refs.isEmpty() && !refs.equals(Set.of(target.publishedDate()))
                || !identity.housing().containsAll(OfficialFollowupRelationship.identity(heading).housing()))
            return List.of();
        var ids = Pattern.compile("(?i)panId=([0-9]+)").matcher(contents);
        boolean refers = false;
        while (ids.find()) {
            if (!target.primaryUrl().matches(".*[?&]panId=" + ids.group(1) + "(?:&.*|$)")) return List.of();
            refers = true;
        }
        boolean matches = OfficialFollowupRelationship.match(target, List.of(new Part("첨부 원문", name + "\n" + contents))).proof() != null;
        if (!matches && !refers && (!post.linked() || !sameScopeOrSchedule(heading, identity))) return List.of();
        // A before/after matrix without explicit row alignment cannot supply the current schedule.
        var results = new LinkedHashSet<Window>();
        boolean oldSection = false;
        for (var original : nativeParts) {
            if (original.location().contains("숨김")) continue;
            var current = new StringBuilder();
            for (String line : original.text().lines().toList()) {
                if (OLD.matcher(line).find() && !line.matches(".*[→⇒].*")) oldSection = true;
                else if (line.strip().matches("^[\\[(（]?(?:변경|정정)\\s*후[\\])）]?[:：]?$")) oldSection = false;
                if (!oldSection) current.append(line).append('\n');
            }
            var part = new Part(original.location(), current.toString());
            var source = new Source(name, post.url(), sha, "EXTRACTED", "", List.of(), List.of(part));
            results.addAll(org.jibro.collection.review.OfficeHoursAgreement.attachmentHours(source, post.issued()));
            for (var window : OfficeVisitFields.read(part.text(), post.url(), post.issued()))
                results.add(window.from(new Attachment(name, part.location(), sha)));
            for (Part field : ScheduleSourceReader.agencyFields(source)) {
                var proof = new Attachment(name, field.location(), sha);
                for (var window : AgencyIntakePost.fields(List.of(field.text()), part.text(), post.url(), post.issued()))
                    results.add(window.from(proof));
            }
        }
        return List.copyOf(results);
    }

    private static boolean sameScopeOrSchedule(String heading, OfficialFollowupRelationship.Identity identity) {
        String compact = heading.replaceAll("\\s", "");
        if (identity.scope().stream().allMatch(compact::contains)) return true;
        // A linked post can delegate to a generic schedule sheet, but not to another named recruitment.
        String name = heading.lines().findFirst().orElse("").replaceFirst("(?i)\\.(pdf|hwpx?|xlsx?|zip)$", "");
        String generic = name.replaceAll("[\\s_\\d.()\\[\\]-]", "");
        return generic.matches("붙임|첨부(?:파일)?|(?:붙임|첨부)?(?:공고문|(?:접수|신청|모집)?(?:일정|기간|일시)(?:변경|정정)?(?:안내|표)?)")
                && heading.lines().skip(1).noneMatch(line -> line.matches(".*(?:모집\\s*공고|입주자\\s*모집|[가-힣]+(?:시|군|구)\\s+(?:온라인|현장|접수|신청)).*"));
    }
}
