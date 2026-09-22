package com.milano.quotation.logistics;

import java.sql.Connection;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Additive directory configuration only. Never creates bindings, versions or formal prices. */
public final class AdditionalCompanyChannels {
    private AdditionalCompanyChannels() {}
    public static int apply(Connection connection,JsonNode additions) throws Exception {
        return apply(connection,additions,"migration-v45-logistics-additions");
    }
    public static int apply(Connection connection,JsonNode additions,String actor) throws Exception {
        var mapper=new ObjectMapper();long revision;boolean enabled;
        try(var query=connection.prepareStatement("select revision,enabled,paused from logistics_company_state where singleton for update");var rows=query.executeQuery()) {
            if(!rows.next())throw new IllegalStateException("Missing company directory state");
            if(rows.getBoolean("paused"))throw new IllegalStateException("Company directory rebuild is active");
            revision=rows.getLong("revision");enabled=rows.getBoolean("enabled");
        }
        if(!enabled)return 0; // Do not initialize or enable directory mode on fresh installations.
        var all=mapper.createArrayNode();var existing=new HashMap<String,JsonNode>();
        try(var query=connection.prepareStatement("select payload::text from logistics_company_channel order by id");var rows=query.executeQuery()) {
            while(rows.next()){var entry=mapper.readTree(rows.getString(1));all.add(entry);existing.put(entry.path("id").asText(),entry);}
        }
        var inserted=new ArrayList<JsonNode>();
        for(var entry:additions.path("entries")) {
            var id=entry.path("id").asText();UUID.fromString(id);
            var prior=existing.get(id);
            if(prior!=null) {
                for(var field:List.of("providerName","channelName"))if(!CompanyChannelScope.normalize(prior.path(field).asText()).equals(CompanyChannelScope.normalize(entry.path(field).asText())))
                    throw new IllegalStateException("Additional channel identity conflicts with existing directory: "+id);
                continue; // Preserve existing aliases, attributes and disabled state.
            }
            inserted.add(entry);all.add(entry);existing.put(id,entry);
        }
        CompanyChannelService.validate(all); // Reject alias/code collisions before any write.
        if(inserted.isEmpty())return 0;
        try(var query=connection.prepareStatement("insert into logistics_company_channel(id,enabled,payload,updated_by) values(?,?,?::jsonb,?)")) {
            for(var entry:inserted){query.setObject(1,UUID.fromString(entry.path("id").asText()));query.setBoolean(2,entry.path("enabled").asBoolean());query.setString(3,entry.toString());query.setString(4,actor);query.addBatch();}query.executeBatch();
        }
        try(var query=connection.prepareStatement("update logistics_company_state set revision=revision+1 where singleton")){query.executeUpdate();}
        var snapshot=mapper.createObjectNode().put("revision",revision+1).put("enabled",enabled);snapshot.set("entries",all);
        try(var query=connection.prepareStatement("insert into logistics_company_revision(revision,payload,created_by) values(?,?::jsonb,?)")) {
            query.setLong(1,revision+1);query.setString(2,snapshot.toString());query.setString(3,actor);query.executeUpdate();
        }
        return inserted.size();
    }
}
