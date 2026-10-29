package org.jibro.notice;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/notices")
public class NoticeController {
    private final NoticeService service;
    public NoticeController(NoticeService service){this.service=service;}
    @GetMapping public HousingModels.Page<HousingModels.Notice> list(
            @RequestParam(defaultValue="") List<String> region,@RequestParam(defaultValue="") List<String> type,
            @RequestParam(defaultValue="") List<String> status,@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="deadline") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return service.search(region,type,status,keyword,sort,page,size);
    }
    @GetMapping("/filters") public Map<String,List<String>> filters(){return service.filters();}
    @GetMapping("/{id}") public HousingModels.Notice detail(@PathVariable UUID id){return service.notice(id);}
}
