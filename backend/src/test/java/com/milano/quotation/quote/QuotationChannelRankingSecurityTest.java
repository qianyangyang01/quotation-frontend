package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotationChannelRankingSecurityTest {
 @Configuration @EnableMethodSecurity static class Config {
  @Bean QuotationChannelRanking service(){return mock(QuotationChannelRanking.class);}
  @Bean QuotationChannelRankingController controller(QuotationChannelRanking service){return new QuotationChannelRankingController(service);}
 }
 @Test void onlyCompanyStatisticsPermissionCanReadRanking() {
  try(var context=new AnnotationConfigApplicationContext(Config.class)) {
   var controller=context.getBean(QuotationChannelRankingController.class);
   var service=context.getBean(QuotationChannelRanking.class);
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("employee","",List.of(new SimpleGrantedAuthority("PERM_myRecords"))));
   assertThrows(AccessDeniedException.class,()->controller.list(null,null,"","","","",0,5,false));
   verifyNoInteractions(service);
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("finance","",List.of(new SimpleGrantedAuthority("PERM_allRecords"))));
   controller.list(null,null,"","","","",0,5,false);
   verify(service).read(any(),eq(0),eq(5),eq(false));
  }finally{SecurityContextHolder.clearContext();}
 }
}
