package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.source.NoticeSourceInformationModels.*;

/** Self-contained instructions in a linked original PDF, including lines wrapped by its page layout. */
final class OfficialMirrorInstructions {
    private record Rule(String title, Area area, Pattern pattern, Pattern exception) {
        Rule(String title, Area area, String pattern, String exception) { this(title, area, Pattern.compile(pattern), Pattern.compile(exception)); }
    }

    private static final List<Rule> RULES = List.of(
        new Rule("세대별 신청 수", Area.CONDITION, "(?:본 모집공고문의 모집대상 주택에 대하여\\s*|\\(중복신청 불가\\)\\s*)?1세대(?:\\(무주택세대구성원\\))?\\s*1주택 신청[^.\\n]{0,100}중복\\s*신청[^.\\n]{0,45}전부\\s*무효\\s*처리\\s*합니다[.]", "(?:중복\\s*신청|복수\\s*신청).{0,30}(?:허용|가능(?!하지|하지 않은))|1세대\\s*[2-9][주호]"),
        new Rule("서류 발급 기준일", Area.DOCUMENT, "(?:제출서류는|아래의 제증명서류는)\\s*(?:입주자\\s*)?모집공고일(?:\\([^)]{1,30}\\))?\\s*이후\\s*발급분(?:이어\\s*야\\s*합니다[.]|에\\s*한함|에 한하여 유효하오니,[^.\\n]{4,120}[.])", "(?:모집)?공고일\\s*이전.{0,28}(?:서류|발급).{0,24}(?:가능|인정|유효)"),
        new Rule("등본·초본·가족관계증명서 발급 방법", Area.DOCUMENT, "주민등록표등본,\\s*주민등록표초본,\\s*가족관계증명서 등 발급 시 주민등록번호 13자리 숫자\\s*(?:모두 기재|전부 표기되도록 발급받아 제출하여야 합니다[.])", "주민등록번호.{0,30}(?:뒷자리.{0,10}(?:가림|미표기|생략)|앞자리만|비공개로)")
    );

    static List<Section> read(List<Source> mirrors, List<Source> other, String day) {
        var items = new ArrayList<Section>();
        for (var rule : RULES) {
            var values = new LinkedHashMap<String, Section>();
            for (var file : mirrors) {
                if (!file.label().contains("공고") || !file.label().toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
                for (var page : file.context()) {
                    String url = EvidenceLocationUrl.forPart(file.label(), file.url(), page.location());
                    if (url.equals(file.url())) continue;
                    // Keep section and bullet boundaries. A qualifier after the matched phrase prevents publication.
                    for (String block : page.text().split("(?m)(?:^|\\R)\\s*[※▪■•]")) {
                        var text = block.replaceAll("\\s+", " ").strip();
                        var match = rule.pattern().matcher(text);
                        if (!match.find() || !text.substring(match.end()).matches("(?:\\s*\\(주민등록번호 표시 예시[^)]*\\))?\\s*(?:-\\s*\\d+\\s*-)?")) continue;
                        String value = match.group();
                        String id = "mirror-" + UUID.nameUUIDFromBytes(rule.title().getBytes(StandardCharsets.UTF_8));
                        values.putIfAbsent(value.replaceAll("\\s+", ""), new Section(id, rule.area(), rule.title(), value,
                            new Evidence(file.label() + " · " + page.location(), url, day)));
                    }
                }
            }
            if (values.size() != 1) continue;
            var section = values.values().iterator().next();
            // An explicit, differently worded version of the same instruction needs comparison.
            boolean conflict = java.util.stream.Stream.concat(other.stream(), mirrors.stream()).flatMap(s -> s.context().stream()).anyMatch(p -> {
                if (rule.exception().matcher(p.text().replaceAll("\\s+", " ")).find()) return true;
                var matcher = rule.pattern().matcher(p.text().replaceAll("\\s+", " "));
                while (matcher.find()) if (!values.containsKey(matcher.group().replaceAll("\\s+", ""))) return true;
                return false;
            });
            if (!conflict) items.add(section);
        }
        return items;
    }

    private OfficialMirrorInstructions() {}
}
