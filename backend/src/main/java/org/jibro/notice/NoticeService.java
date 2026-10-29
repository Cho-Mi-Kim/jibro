package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.*;
import java.util.*;
import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @Transactional(readOnly=true)
public class NoticeService {
    private final NoticeRepository repo;
    private final JPAQueryFactory query;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    public NoticeService(NoticeRepository repo,JPAQueryFactory query,ObjectMapper json,JdbcTemplate db){this.repo=repo;this.query=query;this.json=json;this.db=db;}
    public Content content(UUID id){return json.readValue(entity(id).content,Content.class);}
    public boolean sourceExists(String key){return repo.findBySourceKey(key).isPresent();}
    @Transactional public void lock(UUID id){repo.locked(id).orElseThrow(ApiException::notFound);}
    public NoticeEntity entity(UUID id){return repo.findById(id).orElseThrow(ApiException::notFound);}
    public Notice notice(UUID id){var n=entity(id);if(!n.basicReview.equals("PASS"))throw ApiException.notFound();return view(n,false);}
    public Notice view(NoticeEntity n,boolean admin){
        Content c=json.readValue(n.content,Content.class);
        String state=n.changeReview?"CHANGE_REVIEW":n.guideReview.equals("PASS")?"AVAILABLE":n.guideReview.equals("UNSUPPORTED")?"UNSUPPORTED":"REVIEWING";
        String status=status(n);
        boolean ready=n.basicReview.equals("PASS") && state.equals("AVAILABLE");
        return new Notice(n.id.toString(),n.title,n.agency,n.region,n.supportType,status,n.startDate,n.endDate,n.publishedDate,
                c.attachments(),c.summary(),n.version,ready?n.version:null,state,ready && !status.equals("CLOSED"),c.evidence(),
                ready || admin?c.questions():List.of(),ready || admin?c.documents().stream().map(DocumentTemplate::title).toList():List.of());
    }
    private String status(NoticeEntity n){
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        return n.endDate!=null && n.endDate.isBefore(today)?"CLOSED":n.startDate!=null && n.startDate.isAfter(today)?"UPCOMING":"OPEN";
    }
    public Page<Notice> search(List<String> regions,List<String> types,List<String> statuses,String keyword,String sort,int page,int size){
        if(page<0 || page>100000 || size<1 || size>100 || keyword.length()>200 || !List.of("deadline","newest").contains(sort)
                || !List.of("OPEN","UPCOMING","CLOSED").containsAll(statuses)) throw new IllegalArgumentException();
        var n=QNoticeEntity.noticeEntity;var where=new BooleanBuilder(n.basicReview.eq("PASS"));
        if(!regions.isEmpty())where.and(n.region.in(regions));
        if(!types.isEmpty())where.and(n.supportType.in(types));
        if(!keyword.isBlank())where.and(n.title.containsIgnoreCase(keyword).or(n.agency.containsIgnoreCase(keyword)).or(n.region.containsIgnoreCase(keyword)));
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        if(!statuses.isEmpty()){
            var s=new BooleanBuilder();
            if(statuses.contains("CLOSED"))s.or(n.endDate.lt(today));
            if(statuses.contains("UPCOMING"))s.or(n.startDate.gt(today).and(n.endDate.isNull().or(n.endDate.goe(today))));
            if(statuses.contains("OPEN"))s.or(n.startDate.isNull().or(n.startDate.loe(today)).and(n.endDate.isNull().or(n.endDate.goe(today))));
            where.and(s);
        }
        var ordering=sort.equals("newest")?n.publishedDate.desc().nullsLast():n.endDate.asc().nullsLast();
        long total=Optional.ofNullable(query.select(n.count()).from(n).where(where).fetchOne()).orElse(0L);
        var items=query.selectFrom(n).where(where).orderBy(ordering,n.id.asc()).offset((long)page*size).limit(size).fetch();
        return new Page<>(items.stream().map(v -> view(v,false)).toList(),page,size,total,(total+size-1)/size);
    }
    public Map<String,List<String>> filters(){
        var n=QNoticeEntity.noticeEntity;
        return Map.of("regions",query.select(n.region).distinct().from(n).where(n.basicReview.eq("PASS")).orderBy(n.region.asc()).fetch(),
                "types",query.select(n.supportType).distinct().from(n).where(n.basicReview.eq("PASS")).orderBy(n.supportType.asc()).fetch());
    }
    public List<Review> reviews(){return repo.findAll().stream().sorted(Comparator.comparing(n -> n.title)).map(this::reviewView).toList();}
    private Review reviewView(NoticeEntity n){return new Review(n.id.toString(),view(n,true),n.basicReview,n.guideReview,n.reason,n.revision);}
    @Transactional
    public Review review(UUID id,String actor,int revision,String basic,String guide,String reason){
        var n=repo.locked(id).orElseThrow(ApiException::notFound);
        if(n.revision!=revision)throw ApiException.conflict();
        if(!List.of("PASS","HOLD").contains(basic) || !List.of("PASS","HOLD","UNSUPPORTED").contains(guide)
                || reason.isBlank() || reason.length()>2000)throw new IllegalArgumentException();
        Content c=json.readValue(n.content,Content.class);
        if(guide.equals("PASS") && (c.questions().isEmpty() || c.documents().isEmpty() || c.schedules().isEmpty()))
            throw new ApiException(409,"GUIDE_INCOMPLETE","질문·서류·일정과 출처를 작성한 후 준비 안내를 통과시켜 주세요.");
        n.basicReview=basic;n.guideReview=guide;n.reason=reason;n.revision++;
        if(guide.equals("PASS"))n.changeReview=false;
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES (?,?,?,?,?,?)",id,UUID.fromString(actor),n.revision,basic,guide,reason);
        return reviewView(n);
    }
    @Transactional
    public UUID insert(String key,String title,String agency,String region,String type,LocalDate start,LocalDate end,LocalDate published,Content c){
        if(repo.findBySourceKey(key).isPresent())return repo.findBySourceKey(key).orElseThrow().id;
        var n=new NoticeEntity();n.id=UUID.randomUUID();n.sourceKey=key;n.title=title;n.agency=agency;n.region=region;n.supportType=type;
        n.startDate=start;n.endDate=end;n.publishedDate=published;n.content=json.writeValueAsString(c);repo.save(n);return n.id;
    }
    @Transactional
    public void samplePublish(UUID id,String guide){var n=entity(id);n.basicReview="PASS";n.guideReview=guide;}

    @Transactional
    public UUID importSource(org.jibro.collection.LhNoticeClient.Item item){
        var existing=repo.findBySourceKey(item.key());
        if(existing.isEmpty()){
            UUID id=insert(item.key(),item.title(),"LH",item.region(),item.type(),null,null,item.published(),
                new Content("공식 목록에서 자동 수집했습니다. 신청 기간·조건·서류는 원문 대조 후 제공합니다.",
                        new Evidence("LH 공식 공고 원문",item.url(),LocalDate.now().toString()),List.of(),List.of(),List.of(),List.of()));
            entity(id).sourceFingerprint=item.fingerprint();return id;
        }
        var n=repo.locked(existing.get().id).orElseThrow();
        if(!Objects.equals(n.sourceFingerprint,item.fingerprint())){
            n.title=item.title();n.region=item.region();n.supportType=item.type();n.publishedDate=item.published();
            n.version++;n.revision++;n.basicReview="HOLD";n.guideReview="HOLD";n.changeReview=true;
            n.sourceFingerprint=item.fingerprint();
        }
        return n.id;
    }

    @Transactional
    public Review updateContent(UUID id,int revision,Content c){
        validateContent(c);
        var n=repo.locked(id).orElseThrow(ApiException::notFound);
        if(n.revision!=revision)throw ApiException.conflict();
        n.content=json.writeValueAsString(c);n.version++;n.revision++;n.basicReview="HOLD";n.guideReview="HOLD";n.changeReview=true;
        return reviewView(n);
    }
    public record Draft(String title,String agency,String region,String type,LocalDate startDate,LocalDate endDate,LocalDate publishedDate,Content content){}
    public Draft draft(UUID id){var n=entity(id);return new Draft(n.title,n.agency,n.region,n.supportType,n.startDate,n.endDate,n.publishedDate,content(id));}
    @Transactional public Review updateDraft(UUID id,int revision,Draft draft){
        if(draft==null || !text(draft.title(),300) || !text(draft.agency(),100) || !text(draft.region(),100) || !text(draft.type(),100)
                || draft.startDate()!=null && draft.endDate()!=null && draft.startDate().isAfter(draft.endDate()))throw new IllegalArgumentException();
        updateContent(id,revision,draft.content());var n=entity(id);
        n.title=draft.title();n.agency=draft.agency();n.region=draft.region();n.supportType=draft.type();
        n.startDate=draft.startDate();n.endDate=draft.endDate();n.publishedDate=draft.publishedDate();return reviewView(n);
    }
    public void validateContent(Content c){
        if(c==null || c.summary()==null || c.summary().length()>10000 || c.evidence()==null || c.questions()==null
                || c.documents()==null || c.schedules()==null || c.attachments()==null || c.questions().size()>100
                || c.documents().size()>100 || c.schedules().size()>100 || c.attachments().size()>50)throw new IllegalArgumentException();
        validateEvidence(c.evidence());c.attachments().forEach(this::validateEvidence);
        Set<String> questions=new HashSet<>();
        for(var q:c.questions()){
            if(q==null || !validId(q.id()) || !questions.add(q.id()) || !text(q.title(),300) || !text(q.reason(),2000))throw new IllegalArgumentException();
            validateEvidence(q.evidence());
        }
        Set<String> docs=new HashSet<>();
        for(var d:c.documents()){
            if(d==null || !validId(d.id()) || !docs.add(d.id()) || !text(d.title(),300) || !text(d.method(),2000) || !text(d.issuer(),100)
                    || d.conditionQuestionId()!=null && !questions.contains(d.conditionQuestionId()))throw new IllegalArgumentException();
            validateUrl(d.issueUrl());validateEvidence(d.evidence());
        }
        Set<String> schedules=new HashSet<>();
        for(var s:c.schedules()){
            if(s==null || !validId(s.id()) || !schedules.add(s.id()) || !text(s.title(),300))throw new IllegalArgumentException();
            LocalDate.parse(s.date());validateEvidence(s.evidence());
        }
    }
    private boolean validId(String s){return s!=null && s.matches("[a-zA-Z0-9_-]{1,64}");}
    private boolean text(String s,int length){return s!=null && !s.isBlank() && s.length()<=length;}
    private void validateEvidence(Evidence e){if(e==null || !text(e.label(),300))throw new IllegalArgumentException();validateUrl(e.url());LocalDate.parse(e.checkedAt());}
    private void validateUrl(String url){if(url==null)return;var u=java.net.URI.create(url);if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || url.length()>2000)throw new IllegalArgumentException();}
}
