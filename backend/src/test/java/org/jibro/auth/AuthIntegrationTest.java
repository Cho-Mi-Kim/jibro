package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void signupLoginRefreshReplayAndLogout() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        String body = "{\"name\":\"테스트\",\"email\":\"" + email + "\",\"password\":\"test-password-123\"}";
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/signup").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated());
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("USER")).andReturn();
        String access = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie original = login.getResponse().getCookie("jibro_refresh");
        assertNotNull(original);
        assertTrue(original.isHttpOnly());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/reviews").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        var renewed = mvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(original)).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(original)).andExpect(status().isUnauthorized());
        Cookie next = renewed.getResponse().getCookie("jibro_refresh");
        mvc.perform(post("/api/v1/auth/logout").with(csrf()).cookie(next)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(next)).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfEndpointSuppliesActualCookieTokenPair() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(response.getContentAsString()).get("token").asText();
        mvc.perform(post("/api/v1/auth/logout").cookie(response.getCookie("XSRF-TOKEN")).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent());
    }
}

