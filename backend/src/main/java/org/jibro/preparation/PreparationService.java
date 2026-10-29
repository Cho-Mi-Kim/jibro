package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import java.util.*;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @Transactional
public class PreparationService {
    private final JdbcTemplate db;
    private final NoticeService notices;
    private final ObjectMapper json;
    public PreparationService(JdbcTemplate db,NoticeService notices,ObjectMapper json){this.db=db;this.notices=notices;this.json=json;}
    public List<Preparation> list(String user){
        return db.query("SELECT snapshot::text FROM preparation WHERE user_id=? ORDER BY updated_at DESC,id",(rs,n) ->
                decorate(json.readValue(rs.getString(1),Snapshot.class).preparation()),UUID.fromString(user));
    }
    public Preparation get(String user,UUID id){return decorate(load(user,id,false).preparation());}
    public Preparation start(String user,UUID noticeId,int version){
        // Serialize starts for one owner before checking the unique owner/notice key.
        if(db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",UUID.fromString(user)).isEmpty())throw ApiException.notFound();
        var existing=db.queryForList("SELECT id FROM preparation WHERE user_id=? AND notice_id=?",UUID.fromString(user),noticeId);
        if(!existing.isEmpty())return get(user,(UUID)existing.get(0).get("id"));
        notices.lock(noticeId);
        Notice notice=notices.notice(noticeId);
        if(!notice.canStart() || notice.version()!=version)throw ApiException.conflict();
        Content content=notices.content(noticeId);
        Preparation p=new Preparation();p.id=UUID.randomUUID().toString();p.noticeId=noticeId.toString();p.title=notice.title();
        p.version=version;p.revision=1;p.guideState=notice.guideState();p.questions=content.questions();
        Map<String,String> rules=new LinkedHashMap<>();
        for(var template:content.documents()){
            var d=new Document();d.id=template.id();d.title=template.title();d.necessity=template.conditionQuestionId()==null?"REQUIRED":"UNKNOWN";
            d.method=template.method();d.issuer=template.issuer();d.issueUrl=template.issueUrl();d.evidence=template.evidence();p.documents.add(d);
            if(template.conditionQuestionId()!=null)rules.put(d.id,template.conditionQuestionId());
        }
        for(var template:content.schedules()){
            var s=new Schedule();s.id=template.id();s.title=template.title();s.date=template.date();s.evidence=template.evidence();p.schedules.add(s);
        }
        calculate(p);
        db.update("INSERT INTO preparation(id,user_id,notice_id,notice_version,snapshot) VALUES (?,?,?,?,?::jsonb)",
                UUID.fromString(p.id),UUID.fromString(user),noticeId,version,json.writeValueAsString(new Snapshot(p,rules)));
        return p;
    }
    public Preparation answers(String user,UUID id,int revision,Map<String,Answer> answers){
        Snapshot snap=editable(user,id,revision);Preparation p=snap.preparation();
        Set<String> ids=new HashSet<>(p.questions.stream().map(Question::id).toList());
        if(answers==null || !ids.containsAll(answers.keySet()) || answers.values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException();
        p.answers=new LinkedHashMap<>(answers);
        for(var d:p.documents){
            String question=snap.rules().get(d.id);if(question==null)continue;
            Answer a=p.answers.getOrDefault(question,Answer.UNKNOWN);
            String next=a==Answer.YES?"CONDITIONAL":a==Answer.NO?"NOT_APPLICABLE":"UNKNOWN";
            if(!next.equals(d.necessity)){d.state=d.state==DocumentState.READY?DocumentState.RECHECK:DocumentState.UNCHECKED;d.necessity=next;}
        }
        return save(snap);
    }
    public Preparation document(String user,UUID id,String documentId,int revision,DocumentState state,String note){
        Snapshot snap=editable(user,id,revision);
        var d=snap.preparation().documents.stream().filter(v -> v.id.equals(documentId)).findFirst().orElseThrow(ApiException::notFound);
        if(state==null || note==null || note.length()>2000)throw new IllegalArgumentException();
        if(List.of("UNKNOWN","NOT_APPLICABLE").contains(d.necessity))throw new ApiException(409,"CONDITION_REQUIRED","서류 해당 여부를 먼저 확인하세요.");
        d.state=state;d.note=note;return save(snap);
    }
    public Preparation schedule(String user,UUID id,String scheduleId,int revision,boolean recorded){
        Snapshot snap=editable(user,id,revision);
        var s=snap.preparation().schedules.stream().filter(v -> v.id.equals(scheduleId)).findFirst().orElseThrow(ApiException::notFound);
        s.recorded=recorded;return save(snap);
    }
    private Snapshot load(String user,UUID id,boolean lock){
        return db.query("SELECT snapshot::text FROM preparation WHERE user_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (rs,n) -> json.readValue(rs.getString(1),Snapshot.class),UUID.fromString(user),id).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    private Snapshot editable(String user,UUID id,int revision){
        Snapshot snap=load(user,id,true);
        notices.lock(UUID.fromString(snap.preparation().noticeId));
        Preparation p=decorate(snap.preparation());
        if(p.revision!=revision || !p.guideState.equals("AVAILABLE"))throw ApiException.conflict();
        return snap;
    }
    private Preparation decorate(Preparation p){
        try{
            Notice n=notices.notice(UUID.fromString(p.noticeId));
            p.guideState=n.version()!=p.version?"CHANGE_REVIEW":n.guideState();
        }catch(ApiException ex){if(ex.status!=404)throw ex;p.guideState="CHANGE_REVIEW";}
        return calculate(p);
    }
    private Preparation save(Snapshot snap){
        Preparation p=snap.preparation();p.revision++;calculate(p);
        db.update("UPDATE preparation SET revision=?,snapshot=?::jsonb,updated_at=now() WHERE id=?",p.revision,json.writeValueAsString(snap),UUID.fromString(p.id));return p;
    }
    public static Preparation calculate(Preparation p){
        long known=p.questions.stream().filter(q -> p.answers.containsKey(q.id()) && p.answers.get(q.id())!=Answer.UNKNOWN).count();
        var relevant=p.documents.stream().filter(d -> !d.necessity.equals("NOT_APPLICABLE")).toList();
        long ready=relevant.stream().filter(d -> d.state==DocumentState.READY && !d.necessity.equals("UNKNOWN")).count();
        long recorded=p.schedules.stream().filter(s -> s.recorded).count();
        p.progress=(int)Math.round((p.questions.isEmpty()?0:30.0*known/p.questions.size())+(relevant.isEmpty()?0:50.0*ready/relevant.size())
                +(p.schedules.isEmpty()?0:20.0*recorded/p.schedules.size()));
        p.houseStage=p.progress<20?1:p.progress<40?2:p.progress<70?3:p.progress<100?4:5;
        p.warnings=new ArrayList<>(p.questions.stream().filter(q -> p.answers.getOrDefault(q.id(),Answer.UNKNOWN)==Answer.UNKNOWN).map(Question::title).toList());
        String prefix="/preparations/"+p.id;
        var recheck=relevant.stream().filter(d -> d.state==DocumentState.RECHECK).findFirst();
        var unready=relevant.stream().filter(d -> d.state!=DocumentState.READY || d.necessity.equals("UNKNOWN")).findFirst();
        if(!p.guideState.equals("AVAILABLE")){
            p.warnings.add("공고 기준이 변경되었거나 안내를 재검수 중입니다. 기존 기록은 보존됩니다.");
            p.nextAction=new NextAction("공고 변경 내용 확인하기","새 기준 확인 전에는 준비 기록을 수정할 수 없어요.","/notices/"+p.noticeId);
        }else if(recheck.isPresent())p.nextAction=new NextAction("서류 다시 확인하기","조건 변경으로 재확인이 필요한 서류가 있어요.",prefix+"/documents");
        else if(!p.warnings.isEmpty())p.nextAction=new NextAction("확인하지 못한 조건 살펴보기","모름·미입력 항목이 남아 있어요.",prefix+"/conditions/edit");
        else if(unready.isPresent())p.nextAction=new NextAction(unready.get().title+" 준비하기","아직 준비가 끝나지 않은 서류예요.",prefix+"/documents");
        else p.nextAction=new NextAction("신청 일정과 기록 확인하기","공식 절차와 내 기록을 함께 확인하세요.",prefix+"/schedule");
        return p;
    }
}

