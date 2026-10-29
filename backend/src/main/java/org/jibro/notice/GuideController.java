package org.jibro.notice;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class GuideController {
    private final Environment env;
    public GuideController(Environment env){this.env=env;}
    @GetMapping("/meta") Map<String,Object> meta(){return Map.of("sampleData",Arrays.asList(env.getActiveProfiles()).contains("local-sample"));}
    @GetMapping("/guides") List<HousingModels.Guide> guides(){
        if(!Arrays.asList(env.getActiveProfiles()).contains("local-sample"))return List.of();
        return List.of(new HousingModels.Guide("sample-process","신청 절차","[샘플] 준비 기록과 실제 신청은 달라요",
                "집으로는 준비 기록을 관리합니다. 실제 접수는 해당 기관에서 완료하고 결과를 확인해야 합니다.",SampleData.content().evidence()));
    }
}
