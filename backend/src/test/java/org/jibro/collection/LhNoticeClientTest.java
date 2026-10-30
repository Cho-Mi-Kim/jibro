package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhNoticeClientTest {
    final LhNoticeClient client = new LhNoticeClient("", JsonMapper.builder().build());

    @Test
    void requestsThreeMonthPublicationRangeAndEncodesRawKeyOnce() {
        var configured = new LhNoticeClient("test+key/value=", JsonMapper.builder().build());
        var uri = configured.requestUri(2, LocalDate.of(2026, 10, 31));
        assertEquals("https", uri.getScheme());
        assertEquals("apis.data.go.kr", uri.getHost());
        assertEquals("ServiceKey=test%2Bkey%2Fvalue%3D&PG_SZ=100&PAGE=2&UPP_AIS_TP_CD=06&PAN_ST_DT=20260731&PAN_ED_DT=20261031", uri.getRawQuery());
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
    }
}
