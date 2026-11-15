package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class LhNoticeTransportTest {
    private final CollectionQueryPlan.Query query = new CollectionQueryPlan.Query("13", LocalDate.of(2000, 1, 1), LocalDate.of(2026, 11, 7));

    @Test
    void executesTheScopedHttpsRequestAndKeepsSourceJson() throws Exception {
        var http = mock(HttpClient.class);
        var response = response(200, "{\"SS_CODE\":\"Y\",\"ALL_CNT\":0,\"ServiceKey\":\"test+key\"}", Map.of());
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response);
        var client = new LhNoticeClient("test+key", JsonMapper.builder().build(), http);
        var batch = client.fetch(2, query);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertEquals("https://apis.data.go.kr/B552555/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1?ServiceKey=test%2Bkey&PG_SZ=100&PAGE=2&UPP_AIS_TP_CD=13&PAN_ST_DT=20000101&PAN_ED_DT=20261107", request.getValue().uri().toString());
        assertEquals("GET", request.getValue().method());
        assertEquals("application/json", request.getValue().headers().firstValue("Accept").orElseThrow());
        assertEquals(Duration.ofSeconds(20), request.getValue().timeout().orElseThrow());
        assertEquals(200, batch.response().status());
        assertTrue(batch.response().redacted());
        assertFalse(batch.response().bodyJson().contains("test+key"));
    }

    @Test
    void providerThrottlingIsPreservedWithoutFollowingAnotherEndpoint() throws Exception {
        var http = mock(HttpClient.class);
        var throttled = response(429, "{\"error\":\"quota\"}", Map.of("Retry-After", List.of("3600")));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenReturn(throttled);
        var client = new LhNoticeClient("test", JsonMapper.builder().build(), http);
        var failure = assertThrows(SourceResponse.Rejected.class, () -> client.fetch(1, query));
        assertTrue(CollectionFailure.retryable(failure));
        assertEquals(Duration.ofHours(1), CollectionFailure.providerDelay(failure));
        assertEquals(429, failure.response().status());
        verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (key, value) -> true));
        return response;
    }
}
