package com.milano.quotation.quote;
import com.milano.quotation.common.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Redact all serialized copies, including saved records, previews and revision history. */
@ControllerAdvice
public class FobCoefficientVisibility implements ResponseBodyAdvice<Object> {
    private final ObjectMapper mapper;
    public FobCoefficientVisibility(ObjectMapper mapper){this.mapper=mapper;}
    public static boolean admin(){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_SUPER_ADMIN"));}
    public static void redact(JsonNode node){
        if(node instanceof ObjectNode object){
            object.remove("fobCoefficient");object.remove("fobInvoiceRatio");
            // Quote synchronization needs only the revision, never coefficient values.
            if(object.path("fob-pricing") instanceof ObjectNode policy && policy.has("_version")) policy.putObject("value");
            else if(object.path("fob-pricing").isObject()) object.remove("fob-pricing");
        }
        if(node.isObject() || node.isArray()) for(var child:node)redact(child);
    }
    @Override public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter){return true;}
    @Override public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
        if(admin()||!(body instanceof ApiResponse<?>))return body;
        var copy=mapper.valueToTree(body);redact(copy);return copy;
    }
}
