package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.security.UserAccountRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/quotation-template-sync")
@PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('PERM_quote')")
public class QuotationTemplateSyncController {
    private final QuotationTemplateRepository templates;
    private final UserAccountRepository users;
    private final JdbcClient jdbc;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    public QuotationTemplateSyncController(QuotationTemplateRepository templates, UserAccountRepository users,
            JdbcClient jdbc, IdempotencyService idempotency, AuditService audit) {
        this.templates=templates;this.users=users;this.jdbc=jdbc;this.idempotency=idempotency;this.audit=audit;
    }
    public record Account(String account, String name, long templateCount) {}
    private List<Account> eligibleAccounts() {
        return jdbc.sql("""
            select u.account,u.display_name,count(t.id) as template_count from app_user u
            left join quotation_template t on t.owner_account=u.account
            where u.status='enabled' and exists (select 1 from role_permission p where p.role_key=u.role_key and p.permission_key='quote')
            group by u.account,u.display_name order by u.account
            """).query((rs,n)->new Account(rs.getString("account"),rs.getString("display_name"),rs.getLong("template_count"))).list();
    }
    @GetMapping("/accounts") @Transactional(readOnly=true)
    ApiResponse<List<Account>> accounts() { return ApiResponse.ok(eligibleAccounts()); }

    @GetMapping("/templates") @Transactional(readOnly=true)
    ApiResponse<List<JsonNode>> sourceTemplates(@RequestParam String account) {
        if(eligibleAccounts().stream().noneMatch(u->u.account().equals(account))) throw AppException.unprocessable("来源账号不可用或没有报价权限");
        return ApiResponse.ok(templates.findByOwnerAccountOrderByUpdatedAtDesc(account).stream().map(QuotationTemplateSyncController::view).toList());
    }
    @PostMapping @Transactional(timeout=30)
    ApiResponse<JsonNode> sync(@RequestBody ObjectNode body, @RequestHeader("Idempotency-Key") String key, Authentication auth) {
        String actor=((QuotationPrincipal)auth.getPrincipal()).account();
        var cached=idempotency.existing(actor,"quotation-template-sync",key,body);
        if(cached.isPresent()) return ApiResponse.ok(cached.get());
        String sourceAccount=body.path("sourceAccount").asText("");
        String prefix=body.path("prefix").asText("").trim();
        var requested=body.path("templates");var targetsNode=body.path("targetAccounts");
        if(!requested.isArray()||requested.isEmpty()||requested.size()>200||!targetsNode.isArray()||targetsNode.isEmpty()||targetsNode.size()>200||requested.size()*targetsNode.size()>10000)
            throw AppException.unprocessable("请选择模板和接收人员，单次最多200个模板、200人和10000个副本");
        if(prefix.length()>40) throw AppException.unprocessable("模板名称前缀不能超过40个字符");
        var expected=new LinkedHashMap<UUID,Long>();
        for(var item:requested) {
            UUID id;try { id=UUID.fromString(item.path("id").asText()); } catch(IllegalArgumentException e) { throw AppException.unprocessable("模板编号无效"); }
            if(!item.path("version").isIntegralNumber()||item.path("version").asLong()<0||expected.put(id,item.path("version").asLong())!=null)
                throw AppException.unprocessable("模板版本无效或模板重复，请重新选择");
        }
        var targets=new TreeSet<String>();
        for(var item:targetsNode) {
            String account=item.asText("");
            if(account.isBlank()||account.equals(sourceAccount)||!targets.add(account)) throw AppException.unprocessable("接收账号无效、重复或与来源账号相同");
        }
        // Every sync locks the same account rows in a stable order, including empty recipients.
        // This serializes overlapping syncs even when administrators use different request keys.
        var involved=new TreeSet<>(targets);involved.add(sourceAccount);
        var locked=users.lockTemplateSyncAccounts(involved);
        var eligible=new HashMap<String,Account>();for(var u:eligibleAccounts()) eligible.put(u.account(),u);
        if(locked.size()!=involved.size()||!eligible.keySet().containsAll(involved)) throw AppException.conflict("账号状态或报价权限已变化，请重新加载接收人员");
        var sources=templates.lockSources(expected.keySet());
        if(sources.size()!=expected.size()||sources.stream().anyMatch(t->!t.ownerAccount.equals(sourceAccount)||t.version!=expected.get(t.id)))
            throw AppException.conflict("来源模板已变化，请重新加载并选择模板后同步");
        for(var s:sources) {
            if((prefix+s.name).length()>120) throw AppException.unprocessable("添加前缀后模板名称超过120个字符："+s.name);
            if(!s.payload.isObject()||!s.payload.path("items").isArray()||s.payload.path("items").isEmpty()) throw AppException.unprocessable("来源模板没有可同步的渠道："+s.name);
        }
        var result=JsonNodeFactory.instance.objectNode();var people=result.putArray("recipients");int created=0,skipped=0;
        for(String target:targets) {
            var names=new HashSet<String>();for(var t:templates.findByOwnerAccountOrderByUpdatedAtDesc(target)) names.add(t.name);
            var person=people.addObject().put("account",target).put("name",eligible.get(target).name());var details=person.putArray("templates");int added=0,ignored=0;
            for(var source:sources) {
                String name=prefix+source.name;
                var detail=details.addObject().put("sourceId",source.id.toString()).put("name",name);
                if(!names.add(name)) { ignored++;detail.put("status","skipped").put("reason","已有同名模板，保留原模板");continue; }
                var row=new QuotationTemplateEntity();row.id=UUID.randomUUID();row.ownerAccount=target;row.name=name;
                var payload=(ObjectNode)source.payload.deepCopy();
                for(String field:List.of("id","createdAt","updatedAt","_version","_confirmedUpdate")) payload.remove(field);
                payload.put("name",name).put("ownerAccount",target).put("ownerName",eligible.get(target).name()).put("ownerKey","ACCOUNT:"+target);
                row.payload=payload;row.createdAt=Instant.now();row.updatedAt=row.createdAt;templates.save(row);
                audit.record("template.sync","quotation-template",row.id.toString(),"success",Map.of("sourceAccount",sourceAccount,"sourceTemplateId",source.id.toString(),"sourceVersion",source.version,"targetAccount",target,"templateName",name));
                detail.put("status","created").put("id",row.id.toString());added++;
            }
            person.put("created",added).put("skipped",ignored);created+=added;skipped+=ignored;
        }
        result.put("created",created).put("skipped",skipped);
        templates.flush();
        audit.record("template.sync.batch","quotation-template",null,"success",Map.of("sourceAccount",sourceAccount,"prefix",prefix,"result",result));
        idempotency.save(actor,"quotation-template-sync",key,body,result);
        return ApiResponse.ok(result);
    }
    private static JsonNode view(QuotationTemplateEntity row) {
        return ((ObjectNode)row.payload.deepCopy()).put("id",row.id.toString()).put("name",row.name).put("ownerAccount",row.ownerAccount)
                .put("_version",row.version).put("createdAt",row.createdAt.toString()).put("updatedAt",row.updatedAt.toString());
    }
}
