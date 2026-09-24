package com.milano.quotation.finance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
@SpringBootTest @ActiveProfiles("test") @DirtiesContext
class CountryTaxWorkflowTest {
 @Autowired WebApplicationContext context;
 @Autowired ObjectMapper mapper;
 @org.springframework.test.context.bean.override.mockito.MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard guard;
 @org.springframework.test.context.bean.override.mockito.MockitoBean com.milano.quotation.logistics.LogisticsUploadService uploads;
 @Test void savesReloadsRejectsStaleVersionsAndUnauthorizedWrites() throws Exception {
  var mvc=webAppContextSetup(context).apply(springSecurity()).build();
  var admin=user("finance-test").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_finance"));
  var before=mvc.perform(get("/api/v1/finance-settings/tax-settings").with(admin)).andExpect(status().isOk()).andReturn();
  var data=mapper.readTree(before.getResponse().getContentAsByteArray()).path("data");
  long version=data.isNull() ? -1 : data.path("_version").asLong(-1);
  String body="""
  {"countries":[{"country":"美国","fixedFeeUsd":0.3,"selected":true,"enabled":true,"providers":[{"provider":"测试物流","mode":"exempt","selected":true,"channels":[]}]},{"country":"新西兰","fixedFeeUsd":1.5,"selected":true,"enabled":true,"providers":[{"provider":"测试物流","mode":"taxable","selected":true,"channels":[]}]}],"providers":[]}
  """;
  mvc.perform(put("/api/v1/finance-settings/tax-settings").with(user("sales").roles("SALES")).with(csrf()).header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/finance-settings/tax-settings").with(admin).header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/finance-settings/tax-settings").with(admin).with(csrf()).header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/finance-settings/tax-settings").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.value.countries[0].providers[0].mode").value("exempt")).andExpect(jsonPath("$.data.value.countries[1].providers[0].mode").value("taxable"));
  mvc.perform(put("/api/v1/finance-settings/tax-settings").with(admin).with(csrf()).header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isConflict());
  var current=mvc.perform(get("/api/v1/finance-settings/tax-settings").with(admin)).andReturn();
  long nextVersion=mapper.readTree(current.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();
  var gate=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   java.util.concurrent.Callable<Integer> write=()->{gate.await();return mvc.perform(put("/api/v1/finance-settings/tax-settings").with(user("finance-concurrent").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_finance"))).with(csrf()).header("If-Match",nextVersion).contentType("application/json").content(body)).andReturn().getResponse().getStatus();};
   var one=pool.submit(write);var two=pool.submit(write);gate.countDown();
   var codes=new java.util.ArrayList<Integer>(java.util.List.of(one.get(),two.get()));java.util.Collections.sort(codes);
   org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(200,409),codes);
  }

 }
}
