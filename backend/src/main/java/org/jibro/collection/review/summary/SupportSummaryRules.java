package org.jibro.collection.review.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative paraphrases of explicit program definitions and qualification lead-ins. No amounts are inferred. */
final class SupportSummaryRules {
    private SupportSummaryRules() {}
    record Brief(String audience, String benefit, String waiting) {
        String text() { return audience + " " + benefit + (waiting == null ? "" : " " + waiting); }
    }

    static Brief read(SupportSummarySource source) {
        String type = type(source.heading());
        if (type == null) return null;
        String audience = audience(source, type);
        String benefit = benefit(source, type);
        if (audience == null || benefit == null) return null;
        String waiting = source.heading().contains("예비입주")
            && source.has("(?:공가|빈집|해약).{0,180}(?:순번|순위).{0,100}계약")
            ? "예비입주자로 선정되면 빈집이 생겼을 때 대기순번에 따라 계약을 안내받습니다." : null;
        return new Brief(audience, benefit, waiting);
    }

    private static String type(String heading) {
        // The opening recruitment heading must identify one program. References to other programs later do not count.
        var types = new ArrayList<String>();
        for (String type : List.of("전세임대", "매입임대", "국민임대", "영구임대", "행복주택", "공공임대", "통합공공임대"))
            if (heading.contains(type) && !(type.equals("공공임대") && heading.contains("통합공공임대"))) types.add(type);
        return types.size() == 1 ? types.get(0) : types.isEmpty() && heading.contains("임대주택") ? "임대" : null;
    }

    private static String audience(SupportSummarySource s, String type) {
        if (s.heading().contains("다자녀") && s.lead("(?:2명|두명)이상.{0,55}직계비속.{0,80}양육")
            && s.has("직계비속.{0,60}태아.{0,60}미성년"))
            return "미성년 직계비속(태아 포함)을 2명 이상 양육하는 가구 중 공고의 자격 기준을 충족하는 가구가 대상입니다.";
        if (s.heading().contains("신혼") && s.has("신혼부부") && s.has("예비신혼부부") && s.has("신생아가구")
            && nonOwner(s, true))
            return "신혼부부·예비신혼부부·신생아가구 등 공고의 자격 기준을 충족하는 무주택가구가 대상입니다.";
        if (s.heading().contains("청년") && s.heading().contains("전세임대")
            && (nonOwner(s, false) || nonOwner(s, true)) && s.has("대학생") && s.has("취업준비생")) {
            String rank = s.heading().contains("1순위입주자") ? "1순위 " : "";
            return "청년·대학생·취업준비생 중 공고의 " + rank + "자격 기준을 충족하는 무주택자가 대상입니다.";
        }
        if (type.equals("행복주택")) {
            // Read the explicitly listed applicant groups near 신청자격, not later exclusions or comparison tables.
            var groups = new ArrayList<String>();
            for (String group : List.of("대학생", "청년", "신혼부부", "한부모가족", "고령자", "주거급여수급자"))
                if (s.lead(Pattern.quote(group))) groups.add(group);
            if (groups.isEmpty()) return null;
            return String.join("·", groups) + " 등 공고에 명시된 공급 대상 중 해당 유형의 입주 자격을 충족하는 사람이 대상입니다.";
        }
        if (s.has("(?:무주택요건|무주택조건|주택소유요건|주택보유기준).{0,5}완화")
            && s.has("(?:소형.{0,2}저가주택|연접지역).{0,120}(?:허용|가능)"))
            return "완화된 주택 보유 기준 등 공고에서 정한 입주 자격을 충족하는 가구가 대상입니다.";
        if (nonOwner(s, true))
            return "공고에서 정한 입주 자격을 충족하는 무주택세대구성원이 대상입니다.";
        if (nonOwner(s, false))
            return "공고에서 정한 입주 자격을 충족하는 무주택자가 대상입니다.";
        return null;
    }

    private static String benefit(SupportSummarySource s, String type) {
        if (type.equals("전세임대")) {
            if (!s.has("지원한도.{0,70}(?:전세주택|주택).{0,100}(?:한국토지주택공사|LH).{0,110}전세계약을?체결(?:하고|하여|한뒤|한후).{0,100}재임대(?:하는|합니다)")) return null;
            return "선정되면 지원 한도 안에서 전세주택을 찾고, LH가 집주인과 계약한 뒤 다시 임대해 줍니다.";
        }
        if (!s.has("(?:임대보증금|보증금)") || !s.has("월임대료")) return null;
        if (type.equals("매입임대")) {
            if (!s.has("(?:LH|한국토지주택공사).{0,80}매입.{0,60}주택")) return null;
            return "LH가 매입한 주택을 공고에서 정한 보증금과 월 임대료로 거주할 수 있도록 공급합니다.";
        }
        return "공고에 나온 " + (type.endsWith("주택") ? type : type + "주택")
            + "을 보증금과 월 임대료를 내고 거주할 수 있도록 공급합니다.";
    }

    private static boolean nonOwner(SupportSummarySource s, boolean household) {
        String subject = household ? "무주택세대구성원" : "무주택자";
        return s.qualificationLeads().stream().anyMatch(lead ->
            (Pattern.compile(subject + "(?:으로|로|이|인|중|에해당|만)").matcher(lead).find()
                || Pattern.compile("(?:인|하는)" + subject + "(?:[•▪■]|$)").matcher(lead).find())
                && !Pattern.compile(subject + ".{0,30}(?:신청할수없|대상이아니|신청대상에서제외)").matcher(lead).find());
    }
}
