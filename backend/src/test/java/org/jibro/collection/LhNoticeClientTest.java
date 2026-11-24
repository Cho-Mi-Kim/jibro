package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhNoticeClientTest {
    final LhNoticeClient client = new LhNoticeClient("", JsonMapper.builder().build());

    @Test
    void malformedPublicationDateCannotBecomeAnAcceptedMissingDate() {
        String body = """
                {"SS_CODE":"Y","PAN_NM":"공고","DTL_URL":"https://apply.lh.or.kr/example?id=date",
                 "PAN_NT_ST_DT":"%s"}
                """;
        assertNull(client.parse(body.formatted("")).items().get(0).published());
        for (String date : java.util.List.of("2026.02.30", "2026/11/24", "unreadable")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> client.parse(body.formatted(date)));
            assertEquals("SOURCE_PUBLICATION_DATE_INVALID", failure.getMessage());
        }
        assertEquals(LocalDate.of(2026, 11, 24), client.parse(body.formatted("20261124")).items().get(0).published());
    }

    @Test
    void readsOnlyExplicitClosureNotTheListingExpiryOrOtherStatus() {
        String body = """
                {"SS_CODE":"Y","PAN_NM":"공고","DTL_URL":"https://apply.lh.or.kr/example?id=closed",
                 "PAN_SS":"%s","CLSG_DT":"2020.01.01"}
                """;
        for (String status : java.util.List.of("접수마감", "접수종료", "공고종료"))
            assertTrue(client.parse(body.formatted(status)).items().get(0).officiallyClosed());
        for (String status : java.util.List.of("공고중", "접수중", "접수예정", ""))
            assertFalse(client.parse(body.formatted(status)).items().get(0).officiallyClosed());
    }

    @Test
    void requestsPersistedCategoryAndPublicationRangeAndEncodesRawKeyOnce() {
        var configured = new LhNoticeClient("test+key/value=", JsonMapper.builder().build());
        var uri = configured.requestUri(2, new CollectionQueryPlan.Query("13", LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 31)));
        assertEquals("https", uri.getScheme());
        assertEquals("apis.data.go.kr", uri.getHost());
        assertEquals("ServiceKey=test%2Bkey%2Fvalue%3D&PG_SZ=100&PAGE=2&UPP_AIS_TP_CD=13&PAN_ST_DT=20000101&PAN_ED_DT=20261031", uri.getRawQuery());
        assertFalse(uri.getRawQuery().contains("PAN_SS="));
        assertThrows(IllegalArgumentException.class, () -> configured.requestUri(501,
                new CollectionQueryPlan.Query("06", LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 31))));
    }

    @Test
    void parsesLiveResponseEnvelopeWithStringTotal() {
        var result = client.parse("""
                [{"dsSch":[{"PAN_ST_DT":"20260731","PAN_ED_DT":"20261031","PAGE":"1"}]},
                {"dsList":[{"PAN_NM":"검증 공고","DTL_URL":"https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=test",
                "CNP_CD_NM":"서울","AIS_TP_CD_NM":"행복주택","ALL_CNT":"276","PAN_NT_ST_DT":"2026.10.30"}],
                "resHeader":[{"SS_CODE":"Y","RS_DTTM":"20261031014400"}]}]
                """);
        assertEquals(276, result.total());
        assertEquals(1, result.items().size());
        assertEquals(LocalDate.of(2026, 10, 30), result.items().get(0).published());
    }

    @Test
    void parseOfficialFieldsWithoutGuessingApplicationDates() {
        var result = client.parse("[{\"dsSch\":[{\"SS_CODE\":\"Y\"}]},{\"dsList\":[{\"PAN_NM\":\"테스트\",\"DTL_URL\":\"https://apply.lh.or.kr/example?id=1\",\"CNP_CD_NM\":\"서울\",\"AIS_TP_CD_NM\":\"행복주택\",\"ALL_CNT\":1,\"PAN_NT_ST_DT\":\"2026.10.30\"}]}]");
        assertEquals(1, result.total());
        assertEquals("서울", result.items().get(0).region());
        assertEquals("2026-10-30", result.items().get(0).published().toString());
        assertFalse(client.configured());
    }

    @Test
    void rejectsErrorAndNonOfficialLinks() {
        assertThrows(IllegalArgumentException.class, () -> client.parse("{\"SS_CODE\":\"N\"}"));
        assertThrows(IllegalArgumentException.class, () -> client.parse("{\"SS_CODE\":\"Y\",\"PAN_NM\":\"제목\",\"DTL_URL\":\"https://evil.invalid/\"}"));
        for (String total : java.util.List.of("-1", "oops", "2147483648"))
            assertThrows(IllegalArgumentException.class, () -> client.parse("{\"SS_CODE\":\"Y\",\"ALL_CNT\":\"" + total + "\"}"));
    }
}
