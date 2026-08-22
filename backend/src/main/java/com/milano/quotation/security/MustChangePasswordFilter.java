package com.milano.quotation.security;

import com.fasterxml.jackson.databind.ObjectMapper;import com.milano.quotation.common.ApiResponse;import jakarta.servlet.FilterChain;import jakarta.servlet.ServletException;import jakarta.servlet.http.*;import org.springframework.http.MediaType;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;import java.io.IOException;import java.util.List;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter{
    private final ObjectMapper mapper;public MustChangePasswordFilter(ObjectMapper mapper){this.mapper=mapper;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{var auth=SecurityContextHolder.getContext().getAuthentication();if(auth!=null&&auth.getPrincipal()instanceof QuotationPrincipal principal&&principal.mustChangePassword()&&request.getRequestURI().startsWith("/api/v1/")&&!request.getRequestURI().startsWith("/api/v1/auth/")){response.setStatus(428);response.setContentType(MediaType.APPLICATION_JSON_VALUE);mapper.writeValue(response.getOutputStream(),ApiResponse.error("PASSWORD_CHANGE_REQUIRED","首次登录必须修改临时密码",List.of()));return;}chain.doFilter(request,response);}
}
