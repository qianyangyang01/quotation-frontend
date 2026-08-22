package com.milano.quotation.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.http.Cookie;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AuthenticationIntegrationTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void anonymousSessionCannotReadQuotationBusinessApi() throws Exception {
        mvc.perform(get("/api/v1/purchase-products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void foreignTrainingCookieCannotAuthenticateAgainstQuotationApi() throws Exception {
        mvc.perform(get("/api/v1/purchase-products")
                        .cookie(new Cookie("TRAINING_SESSION", "forged-training-session")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void loginCreatesQuotationOnlySessionAndRequiresInitialPasswordChange() throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"account\":\"ADMIN\",\"password\":\"TestAdmin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value("ADMIN"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        mvc.perform(get("/api/v1/purchase-products").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test void invalidCredentialsReturn401InsteadOf500() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"account\":\"ADMIN\",\"password\":\"WrongPass123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
