package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.DocumentTemplate;
import org.jibro.notice.HousingModels.Evidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class EvidenceChecksIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    EvidenceCheckService checks;
    @Autowired
    MockMvc mvc;
    @MockitoBean
    AttachmentFingerprintClient transport;
    UUID id;

    @BeforeEach
    void setup() throws Exception {
        var evidence = new Evidence("공고문 4쪽", "https://apply.lh.or.kr/example#page=4", "2026-01-01");
        var document = new DocumentTemplate("form", "신청서", null, "서명 후 제출", "LH 공고 첨부", null, evidence);
        var content = new Content("원문 대조 필요", evidence, List.of(), List.of(), List.of(document), List.of());
        id = notices.insert("evidence-check-" + UUID.randomUUID(), "공고", "LH", "서울", "임대",
                LocalDate.now(), LocalDate.now().plusDays(1), LocalDate.now(), content);
        when(transport.probe(anyString())).thenReturn(200);
    }

    @Test
    void checksStoredSourcesWithoutPublishingOrReplacingHumanDates() {
        var before = notices.entity(id);
        String content = before.content;
        var report = checks.check(id, before.revision);
        assertEquals(1, report.revision());
        assertEquals("MISSING", report.results().get(2).status());
        assertNull(report.results().get(2).checkedAt());
        assertEquals("2026-01-01", report.results().get(0).checkedAt());
        var after = notices.entity(id);
        assertEquals(content, after.content);
        assertEquals("HOLD", after.basicReview);
        assertEquals("HOLD", after.guideReview);
        assertNull(after.basicCheckedAt);
        assertNull(after.guideCheckedAt);
        assertEquals(1, after.revision);
    }

    @Test
    void endpointRequiresAdminCsrfAndCurrentRevision() throws Exception {
        String path = "/api/v1/admin/reviews/" + id + "/evidence-checks";
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden());
        // jwt() intentionally skips CSRF in Spring Security Test; use authentication() to exercise the filter.
        var admin = new JwtAuthenticationToken(Jwt.withTokenValue("test")
                .header("alg", "none").subject(UUID.randomUUID().toString()).build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mvc.perform(post(path).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(authentication(admin)).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(csrf()).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(transport);
        mvc.perform(post(path).with(csrf()).with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isOk());
        verify(transport).probe("https://apply.lh.or.kr/example");
    }

    @Test
    void rejectsFutureSourceDatesWithoutChangingSavedContent() {
        var existing = notices.content(id);
        var future = new Evidence("공고문 4쪽", existing.evidence().url(), LocalDate.now().plusDays(2).toString());
        var changed = new Content(existing.summary(), future, existing.attachments(), existing.questions(),
                existing.documents(), existing.schedules());
        String original = notices.entity(id).content;
        assertThrows(IllegalArgumentException.class, () -> notices.updateContent(id, 1, changed));
        assertEquals(original, notices.entity(id).content);
        assertEquals(1, notices.entity(id).revision);
    }
}
