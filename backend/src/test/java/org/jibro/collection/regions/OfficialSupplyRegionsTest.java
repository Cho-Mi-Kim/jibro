package org.jibro.collection.regions;

import java.util.*;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfficialSupplyRegionsTest {
    static final String URL = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07";
    static final String FORM = "<form id='wrtancViewForm'><input name='panId' value='123'></form>";

    @Test
    void readsPropertyAddressesAndEncodedMunicipalitiesAcrossTabs() {
        var page = FORM + """
            <section><h3>공급정보</h3><li>소재지 : 대구광역시 동구 초례로 66</li>
            <div style='display:none'><li>소재지 : 경상북도 경산시 봉황길 29</li></div>
            <table><thead><tr><th>지역본부</th><th>지역(지자체)</th><th>문의처</th></tr></thead>
            <tbody><tr><td>서울지역본부</td><td>매입다가구(경기부천시)</td><td>인천광역시</td></tr></tbody></table></section>
            <section><h3>접수처</h3><li>소재지 : 서울특별시 강남구</li></section>
            <section><h3>지원 자격</h3><li>모집지역 : 제주특별자치도</li></section>
            """;
        var regions = OfficialSupplyRegions.read(page, URL);
        assertEquals(Set.of("대구광역시","경상북도","경기도"), regions.keySet());
        assertEquals("매입다가구(경기부천시)", regions.get("경기도").value());
        assertThrows(IllegalArgumentException.class, () -> OfficialSupplyRegions.read(page, URL.replace("123","321")));
    }

    @Test
    void numberedNationwideSupplyFieldIsDistinctFromApplicantResidence() {
        var scope = OfficialSupplyRegions.attachmentFields(URL, List.of(new Part("1쪽", "2. 사업대상지역 : 전국")));
        assertEquals("1쪽", scope.get("전국").field());
        assertTrue(OfficialSupplyRegions.attachmentFields(URL,
            List.of(new Part("1쪽", "2. 신청자격 : 전국 거주자\\n2. 공급지역 : 전국 일부 지역 제외"))).isEmpty());
    }

    @Test
    void attachmentsRequireExplicitSupplyListsAndKeepTheSourceLocation() {
        var text = "신청자격 : 서울특별시 거주\n모집지역 : 대전광역시 제외\n공급지역 : 대구광역시, 경상북도\n공급지역 : 인천광역시 거주자만 해당";
        var result = OfficialSupplyRegions.attachmentFields(URL,List.of(new Part("공고문 2쪽",text)));
        assertEquals(Set.of("대구광역시","경상북도"),result.keySet());
        assertEquals("공고문 2쪽",result.get("경상북도").field());
    }
}
