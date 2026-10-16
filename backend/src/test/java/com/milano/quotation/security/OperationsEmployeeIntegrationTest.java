package com.milano.quotation.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperationsEmployeeIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @MockitoBean AssetStorageService storage;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void administratorCanCreateOperationsStaffWhoCanManageSessionButCannotAccessBusinessData() throws Exception {
        mvc.perform(post("/api/v1/users").with(user("ROLEADMIN").authorities(new SimpleGrantedAuthority("PERM_permissions"))).with(csrf())
                        .contentType("application/json")
                        .content("{\"account\":\"OPSNEW\",\"name\":\"运营员工\",\"password\":\"Operations123\",\"role\":\"operations_employee\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("operations_employee"));
        var session = login("OPSNEW", "Operations123");
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("operations_employee"))
                .andExpect(jsonPath("$.data.permissions").isEmpty()).andExpect(jsonPath("$.data.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/auth/csrf").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/finance-settings").session(session)).andExpect(status().is(428));
        mvc.perform(post("/api/v1/auth/change-password").session(session).with(csrf()).contentType("application/json")
                        .content("{\"currentPassword\":\"Operations123\",\"newPassword\":\"Operations456\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions").isEmpty())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        for (var path : List.of("/finance-settings", "/finance-settings/exchange-rate",
                "/finance-settings/logistics-required-previews", "/finance-settings/logistics-required-previews/" + UUID.randomUUID(),
                "/assets/" + UUID.randomUUID(), "/purchase-products", "/fob-purchase-products/TEST",
                "/logistics/providers", "/logistics/published/manifest", "/quotations", "/quotation-drafts", "/users")) {
            mvc.perform(get("/api/v1" + path).session(session))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(post("/api/v1/quotations").session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(storage, never()).open(any());
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void assigningOperationsRoleRevokesExistingSessionBusinessAccessOnItsNextRequest() throws Exception {
        var account = users.saveAndFlush(UserAccount.create("OPSCHANGE", "业务员工", encoder.encode("Operations123"), "employee", false));
        var session = login(account.account, "Operations123");
        mvc.perform(get("/api/v1/finance-settings").session(session)).andExpect(status().isOk());
        var update = mapper.createObjectNode().put("role", "operations_employee").put("status", "enabled").put("version", account.version);
        mvc.perform(patch("/api/v1/users/" + account.id).with(user("ROLEADMIN").authorities(new SimpleGrantedAuthority("PERM_permissions")))
                        .with(csrf()).contentType("application/json").content(update.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("operations_employee"));
        mvc.perform(get("/api/v1/finance-settings").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/purchase-products").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("operations_employee")).andExpect(jsonPath("$.data.permissions").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"super_admin", "finance", "logistics", "purchase", "employee"})
    void existingRolesRetainAccessToSharedBusinessSettings(String role) throws Exception {
        users.saveAndFlush(UserAccount.create("EXISTINGROLE", "原有角色", encoder.encode("Operations123"), role, false));
        var session = login("EXISTINGROLE", "Operations123");
        mvc.perform(get("/api/v1/finance-settings").session(session)).andExpect(status().isOk());
        var id = UUID.randomUUID();
        var bytes = "existing-role-asset".getBytes(StandardCharsets.UTF_8);
        var asset = mock(AssetObject.class); asset.mediaType = "image/png"; asset.sizeBytes = bytes.length;
        when(storage.open(id)).thenReturn(new AssetStorageService.AssetStream(asset, new ByteArrayInputStream(bytes)));
        mvc.perform(get("/api/v1/assets/" + id).session(session)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
    }

    private MockHttpSession login(String account, String password) throws Exception {
        var body = mapper.createObjectNode().put("account", account).put("password", password);
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
