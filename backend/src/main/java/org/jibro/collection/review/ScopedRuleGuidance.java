package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.jibro.notice.HousingModels.*;
import static org.jibro.collection.review.AutomaticGuidanceCompiler.*;

/** Exact grammar claims keep their scope and provenance; conflicting values never become an answerable rule. */
final class ScopedRuleGuidance {
    private ScopedRuleGuidance() {}

    static Result compile(List<LocatedRule> rules, Content original, List<Evidence> attachments) {
        Map<String,List<LocatedRule>> groups = rules.stream().collect(Collectors.groupingBy(
            r -> r.rule().kind() + ":" + r.rule().key(), LinkedHashMap::new, Collectors.toList()));
        var questions = new LinkedHashMap<String,Question>();
        var documents = new ArrayList<DocumentTemplate>();
        var reviewIssues = new ArrayList<ReviewIssue>();
        var documentGroups = new LinkedHashMap<String,List<LocatedRule>>();
        for (var group : groups.values()) {
            var first = group.get(0); var rule = first.rule();
            if (rule.kind().equals("CONDITION")) {
                String id = "auto-" + rule.key();
                boolean conflict = group.stream().map(r -> r.rule().value()).distinct().count() > 1;
                String title = conflict ? conditionName(rule.key()) + " 확인 필요" : rule.title();
                questions.put(id,new Question(id,title,conflict ? "공식 자료의 기준이 서로 달라 기관 확인이 필요합니다." : first.statement(),
                    first.evidence(),List.of(new AnswerOption("YES","해당해요"),new AnswerOption("NO","해당하지 않아요",true),new AnswerOption("UNKNOWN","잘 모르겠어요",true))));
                if (conflict) reviewIssues.add(issue("auto-conflict-"+rule.key(),"CONDITION",title,List.of(id),List.of(),group));
            } else if (rule.kind().equals("DOCUMENT")) {
                if (!rule.key().equals("common")) {
                    String id="auto-document-"+rule.key();
                    questions.put(id,new Question(id,rule.title()+"에 해당하나요?","신분에 따른 제출 대상은 공식 서류 안내와 함께 확인합니다.",
                        first.evidence(),List.of(new AnswerOption("YES","해당해요"),new AnswerOption("NO","해당하지 않아요"),new AnswerOption("UNKNOWN","잘 모르겠어요",true))));
                }
                for (var located : group) for (String name : located.rule().items())
                    documentGroups.computeIfAbsent(name,ignored -> new ArrayList<>()).add(located);
            }
        }
        for (var entry : documentGroups.entrySet()) {
            String name=entry.getKey(); var claims=entry.getValue(); var first=claims.get(0);
            String id="auto-doc-"+UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            var scopes=claims.stream().map(c->c.rule().key()).distinct().toList();
            var related=new ArrayList<LocatedRule>();
            boolean conflict=scopes.size()>1;
            for (String scope:scopes) {
                var variants=groups.get("DOCUMENT:"+scope);
                related.addAll(variants);
                if (variants.stream().anyMatch(c->!c.rule().items().contains(name))) conflict=true;
            }
            String qid=first.rule().key().equals("common")?null:"auto-document-"+first.rule().key();
            documents.add(new DocumentTemplate(id,name,qid,conflict?"공식 자료의 제출 목록·대상이 달라 기관 확인이 필요합니다.":first.statement(),
                "공식 공고의 발급 안내 확인",null,first.evidence(),qid==null?null:List.of("YES")));
            if (conflict) reviewIssues.add(issue("auto-doc-conflict-"+UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                "DOCUMENT",name+" 제출 범위 확인 필요",List.of(),List.of(id),related));
        }
        if (reviewIssues.size()>30 || reviewIssues.stream().anyMatch(i->i.detail().length()>3000 || i.evidence().size()>10))
            return new Result(false,List.of("SCOPED_CONFLICT_LIMIT"),null);
        // The independent schedule compiler owns every date and time conflict, including application boundaries.
        return new Result(true,List.of(),new Content(original.summary(),original.evidence(),attachments,
            List.copyOf(questions.values()),documents,List.of(),reviewIssues));
    }

    private static ReviewIssue issue(String id,String area,String title,List<String> qids,List<String> dids,List<LocatedRule> claims) {
        String detail="공식 자료의 아래 안내가 서로 다릅니다. 어느 한쪽을 확정하지 않으며 기관 확인 전에는 이 항목을 준비 완료로 계산하지 않습니다.\n"
            +claims.stream().map(LocatedRule::statement).distinct().collect(Collectors.joining("\n"));
        return new ReviewIssue(id,area,title,detail,qids,dids,claims.stream().map(LocatedRule::evidence).distinct().toList());
    }

    private static String conditionName(String key) {
        return switch(key) { case "age" -> "연령 기준"; case "income" -> "소득 기준"; case "assets" -> "자산 기준";
            case "vehicle" -> "자동차 기준"; case "housing" -> "무주택 기준"; case "marriage" -> "혼인 기준"; default -> "신청 기준"; };
    }
}
