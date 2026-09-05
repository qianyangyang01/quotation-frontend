package com.milano.quotation.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test")
class LogisticsRebuildPermissionTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final String root="/api/v1/logistics/rebuild",id="00000000-0000-0000-0000-000000000001";
    @BeforeEach void setup(){mvc=webAppContextSetup(context).apply(springSecurity()).build();}
    @Test @WithMockUser(username="NO_LOGISTICS",authorities="PERM_purchase")
    void requiresLogisticsPermissionForHistorySourcesExportsAndWrites()throws Exception {
        for(var path:new String[]{"/datasets","/datasets/"+id+"/prices.xlsx","/versions/"+id,"/imports/"+id+"/files/0","/imports/"+id+"/changes.xlsx","/imports/"+id+"/standardized.xlsx","/versions/"+id+"/standardized.xlsx"})
            mvc.perform(get(root+path)).andExpect(status().isForbidden());
        mvc.perform(post(root+"/datasets").with(csrf()).header("Idempotency-Key","qa-permission-1").contentType("application/json").content("{\"name\":\"QA\"}")).andExpect(status().isForbidden());
        mvc.perform(post(root+"/datasets/"+id+"/activate").with(csrf()).header("Idempotency-Key","qa-permission-2").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(root+"/datasets/"+id+"/required-channels")).andExpect(status().isForbidden());
        mvc.perform(get(root+"/downloads/prepare?kind=prices&id="+id)).andExpect(status().isForbidden());
        mvc.perform(put(root+"/datasets/"+id+"/required-channels").with(csrf()).header("Idempotency-Key","qa-required-permission").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(root+"/versions/"+id+"/billing-acceptance")).andExpect(status().isForbidden());
        mvc.perform(get(root+"/imports/"+id+"/files/0/evidence")).andExpect(status().isForbidden());
        mvc.perform(get(root+"/imports/"+id+"/publish-progress")).andExpect(status().isForbidden());
        mvc.perform(post(root+"/versions/"+id+"/billing-acceptance").with(csrf()).header("Idempotency-Key","qa-billing-permission").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void rejectsAnonymousExports()throws Exception {mvc.perform(get(root+"/datasets/"+id+"/prices.xlsx")).andExpect(status().isUnauthorized());mvc.perform(get(root+"/versions/"+id+"/standardized.xlsx")).andExpect(status().isUnauthorized());mvc.perform(get(root+"/downloads/prepare?kind=prices&id="+id)).andExpect(status().isUnauthorized());}
}
