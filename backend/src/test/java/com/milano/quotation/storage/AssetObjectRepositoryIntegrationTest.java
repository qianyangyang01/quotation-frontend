package com.milano.quotation.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AssetObjectRepositoryIntegrationTest {
    @Autowired AssetObjectRepository assets;
    @Autowired JdbcTemplate jdbc;

    @Test
    void retiresOrphansButKeepsAssetsSharedByAProduct() {
        var shared = UUID.randomUUID(); var orphan = UUID.randomUUID(); var product = UUID.randomUUID();
        insertAsset(shared, "shared"); insertAsset(orphan, "orphan");
        jdbc.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready) values (?,?,?,0,current_timestamp,current_timestamp,'ready',false)", product, "SAFE-ASSET-1", "{}");
        jdbc.update("insert into purchase_product_image(id,product_id,asset_id,image_type,sort_order) values (?,?,?,'product',0)", UUID.randomUUID(), product, shared);

        assertEquals(1, assets.retireUnreferenced(List.of(shared, orphan), Instant.now()));
        assertEquals("published", state(shared));
        assertEquals("temporary", state(orphan));
    }

    private void insertAsset(UUID id, String name) {
        jdbc.update("insert into asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,created_at) values (?,?,?,?,?,?,?,current_timestamp)",
                id, name.repeat(64).substring(0, 64), "objects/" + name, "image/png", 1, name + ".png", "published");
    }

    private String state(UUID id) {
        return jdbc.queryForObject("select storage_state from asset_object where id=?", String.class, id);
    }
}
