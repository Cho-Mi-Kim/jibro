package org.jibro.notice;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class NonResidentialNoticeScopeTest {
    @Test void excludesEnterpriseApplicantsAndChildcareOperators() {
        for(String title:new String[]{"기장군 중소기업 근로자 기관공급 사업 대상기업 모집계획 공고","파주시 중소기업체 기관공급(행복주택)","가정어린이집 운영예정자 모집공고","어린이집 위탁 운영자 모집"})
            assertNotNull(NonResidentialNoticeScope.exclusionReason(title),title);
    }
    @Test void preservesIndividualWorkersAndHousingWithChildcareAmenities() {
        for(String title:new String[]{"중소기업 근로자 행복주택 입주자 모집","어린이집 인접 신혼희망타운 입주자 모집","행복주택 기관공급 입주자 모집","기업체 근로자 공공임대 입주자 모집"})
            assertNull(NonResidentialNoticeScope.exclusionReason(title),title);
    }
}
