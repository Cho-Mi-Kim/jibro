package org.jibro.notice;

/** Exclude the applicant role, not a housing type or an amenity mentioned in a title. */
final class NonResidentialNoticeScope {
    private NonResidentialNoticeScope() { }

    static String exclusionReason(String title) {
        String text = title == null ? "" : title.replaceAll("\\s+", "");
        if (text.matches(".*어린이집.*(운영예정자|운영자|운영사업자|수탁자|수탁기관).*모집.*"))
            return "서비스 범위 제외: 주거 입주자가 아닌 어린이집 운영 주체 모집";
        if (text.contains("대상기업모집") || text.contains("기업체모집")
            || text.matches(".*(중소기업체|기업체).*기관공급.*"))
            return "서비스 범위 제외: 근로자 개인이 아닌 기업체 선정·기관공급 모집";
        return null;
    }
}
