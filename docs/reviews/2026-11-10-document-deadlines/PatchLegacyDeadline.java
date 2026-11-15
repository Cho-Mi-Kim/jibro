package org.jibro.notice;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.jibro.JibroApplication;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit one-notice data repair. Does not review or approve the unchanged eligibility guide. */
public class PatchLegacyDeadline {
 public static void main(String[] args)throws Exception{
  if(args.length!=1||!args[0].equals("apply"))throw new IllegalArgumentException();
  var root=Path.of(".tools/qa/document-deadlines-20261110");
  UUID id=UUID.fromString("cb715a71-3611-4d8a-a23f-573d0bae22e9");
  try(var ctx=new SpringApplication(JibroApplication.class).run("--server.port=0","--app.collection.gemini-enabled=false","--app.collection.automatic-review-enabled=false","--app.collection.extraction-enabled=false","--app.collection.initial-delay-ms=86400000","--JIBRO_REMINDER_INITIAL_MS=86400000")){
   ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).values().forEach(s->s.destroy());
   var repo=ctx.getBean(NoticeRepository.class);var views=ctx.getBean(NoticeService.class);var json=ctx.getBean(ObjectMapper.class);var http=ctx.getBean(AttachmentFingerprintClient.class);
   var n=repo.findById(id).orElseThrow();var before=json.readValue(n.content,HousingModels.Content.class);String raw=n.content;int revision=n.revision;
   if(!views.notice(id).status().equals("OPEN")||!n.basicReview.equals("PASS")||!n.guideReview.equals("PASS")||n.changeReview||!n.lifecycleState.equals("NORMAL"))throw new IllegalStateException("Not an unchanged active publication");
   if(before.schedules().stream().anyMatch(s->s.title().matches(".*서류.*마감.*")))throw new IllegalStateException("Already has deadline");
   String url=before.evidence().url(),pdf="https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68869885";
   if(!LhNoticeClient.sourceKey(url).equals(n.sourceKey))throw new IllegalStateException("Identity mismatch");
   String html=http.html(url);var detail=OfficialDetailParser.parse(html,url);
   if(!org.jsoup.Jsoup.parse(html).select(".bbs_ViewA li").text().replaceAll("\\s+","").matches("(?s).*공고상태(?:공고중|접수중|정정공고중).*" )||detail.attachments().stream().noneMatch(a->a.url().equals(pdf)))throw new IllegalStateException("Current official source mismatch");
   String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("youth-official.pdf"))));
   if(!http.fetchForSourceReview(pdf).sha256().equals(sha))throw new IllegalStateException("Reviewed PDF changed");
   var schedules=new ArrayList<>(before.schedules());schedules.add(new HousingModels.ScheduleTemplate("documents-end","서류 제출 마감 · 신청 시 스캔 첨부 (18:00, 조기 중단 가능)","2026-12-31",new HousingModels.Evidence("수정 공고문 3쪽 · 신청기간 및 신청 시 서류 첨부",pdf+"#page=3","2026-11-10")));
   var after=new HousingModels.Content(before.summary(),before.evidence(),before.attachments(),before.questions(),before.documents(),schedules,before.reviewIssues(),before.applicationClosure());views.validateContent(after);
   Files.writeString(root.resolve("legacy-before.json"),raw);Instant checked=Instant.now();
   var tx=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
   tx.executeWithoutResult(s->{var locked=repo.locked(id).orElseThrow();if(locked.revision!=revision||!locked.content.equals(raw)||locked.changeReview||!views.notice(id).status().equals("OPEN")||checked.isBefore(Instant.now().minusSeconds(120)))throw new IllegalStateException("Concurrent change");
    locked.content=json.writeValueAsString(after);locked.version++;locked.revision++;locked.automaticReviewBlocked=true;repo.saveAndFlush(locked);
    var audit=Map.of("scope","SCHEDULE_ONLY_EXPLICIT_REPAIR","reason","사용자 요청: 공식 원문상 동시 제출 마감 누락 보완. 기존 자격·서류 안내를 새로 검수하거나 승인하지 않음.","beforeContent",before,"afterContent",after,"officialDetailUrl",url,"pdfUrl",pdf,"pdfSha256",sha,"checkedAt",checked);
    String payload=json.writeValueAsString(audit);ctx.getBean(SourceReviewRepository.class).record(id,revision,locked.revision,"PASS",SourceReviewVerifier.hash(payload),payload);
   });
   Files.writeString(root.resolve("legacy-after.json"),json.writeValueAsString(views.notice(id)));System.out.println("SCHEDULE_ONLY_REPAIR PASS");
  }
 }
}
