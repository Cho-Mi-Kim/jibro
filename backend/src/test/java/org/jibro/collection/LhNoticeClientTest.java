package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhNoticeClientTest {
    final LhNoticeClient client = new LhNoticeClient("", JsonMapper.builder().build());

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
