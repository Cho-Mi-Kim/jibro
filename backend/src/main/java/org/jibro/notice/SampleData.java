package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("local-sample")
public class SampleData implements CommandLineRunner {
    private final NoticeService service;
    public SampleData(NoticeService service){this.service=service;}
    @Override public void run(String...args){
        for(int i=1;i<=3;i++){
            String key="local-sample-"+i;if(service.sourceExists(key))continue;
            var id=service.insert(key,"[로컬 샘플] 청년 주거 준비 예시 "+i,"가상 운영기관",i==2?"경기":"서울",i==2?"주거비 지원":"공공임대",
                    LocalDate.now().minusDays(10),LocalDate.now().plusDays(60),LocalDate.now().minusDays(14),content());
            service.samplePublish(id,i==1?"PASS":i==2?"HOLD":"UNSUPPORTED");
        }
    }
    public static Content content(){
        var evidence=new Evidence("개발용 가상 자료 · 실제 모집 공고 아님",null,LocalDate.now().toString());
        return new Content("실제 신청에 사용할 수 없는 로컬 샘플입니다. 서버 저장과 준비 흐름을 확인하세요.",evidence,List.of(),
                List.of(new Question("residence","공고 기준일의 거주 조건을 확인했나요?","실제 공고의 기준일과 거주지를 확인해야 합니다.",evidence),
                        new Question("house","조건별 추가 서류 제출 대상인가요?","샘플에서 예는 추가 제출, 아니오는 해당 없음을 의미합니다.",evidence)),
                List.of(new DocumentTemplate("residence-document","거주 확인 서류",null,"공고의 발급 기준과 제출 방법 확인","공고에서 확인",null,evidence),
                        new DocumentTemplate("conditional-document","조건별 확인 서류","house","조건에 해당할 때만 준비","공고에서 확인",null,evidence)),
                List.of(new ScheduleTemplate("apply","공식 신청 여부 확인",LocalDate.now().plusDays(60).toString(),evidence)));
    }
}
