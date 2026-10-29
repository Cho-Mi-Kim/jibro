package org.jibro.collection;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class LhNoticeClient {
    public record Item(String key,String title,String region,String type,String url,LocalDate published,String fingerprint) {}
    public record Batch(List<Item> items,int total) {}
    private final String key;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public LhNoticeClient(@Value("${LH_SERVICE_KEY:}") String key,ObjectMapper json){this.key=key;this.json=json;}
    public boolean configured(){return !key.isBlank();}
    public Batch fetch(int page) throws Exception {
        String query="ServiceKey="+URLEncoder.encode(key,StandardCharsets.UTF_8)+"&PG_SZ=100&PAGE="+page+"&UPP_AIS_TP_CD=06"
                +"&PAN_NT_ST_DT="+LocalDate.now().minusMonths(3).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                +"&CLSG_DT="+LocalDate.now().plusYears(1).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        var request=HttpRequest.newBuilder(URI.create("https://apis.data.go.kr/B552555/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1?"+query))
                .timeout(Duration.ofSeconds(20)).header("Accept","application/json").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200 || response.body().length()>5_000_000)throw new IllegalStateException("SOURCE_RESPONSE_INVALID");
        return parse(response.body());
    }
    public Batch parse(String body){
        JsonNode root=json.readTree(body);
        List<JsonNode> objects=new ArrayList<>();walk(root,objects);
        if(objects.stream().noneMatch(n -> "Y".equals(n.path("SS_CODE").asText())))throw new IllegalArgumentException("SOURCE_RESULT_INVALID");
        List<Item> items=new ArrayList<>();int total=0;
        for(JsonNode n:objects){
            total=Math.max(total,n.path("ALL_CNT").asInt(0));
            if(!n.has("PAN_NM"))continue;
            String title=n.path("PAN_NM").asText();String url=n.path("DTL_URL").asText();
            URI uri=URI.create(url);
            if(!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost()) || uri.getUserInfo()!=null
                    || title.isBlank() || title.length()>300 || url.length()>2000)throw new IllegalArgumentException("SOURCE_ITEM_INVALID");
            // URL is the official stable detail identifier; never construct a guessed detail link.
            String sourceKey="LH:"+UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
            String region=n.path("CNP_CD_NM").asText("확인 필요");String type=n.path("AIS_TP_CD_NM").asText("임대주택");
            if(region.length()>100 || type.length()>100)throw new IllegalArgumentException("SOURCE_ITEM_INVALID");
            Map<String,String> stable=new TreeMap<>();
            n.properties().forEach(entry -> {if(!Set.of("RNUM","ALL_CNT","RS_DTTM").contains(entry.getKey()))stable.put(entry.getKey(),entry.getValue().toString());});
            String fingerprint;
            try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(stable)));}
            catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            items.add(new Item(sourceKey,title,region,type,url,date(n.path("PAN_NT_ST_DT").asText()),fingerprint));
        }
        return new Batch(items,total);
    }
    private LocalDate date(String value){
        if(value==null || value.isBlank())return null;
        try{return value.matches("\\d{8}")?LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE):LocalDate.parse(value.replace('.','-'));}
        catch(java.time.format.DateTimeParseException ex){return null;}
    }
    private void walk(JsonNode node,List<JsonNode> result){
        if(node.isObject())result.add(node);
        if(node.isContainer())node.forEach(child -> walk(child,result));
    }
}
