package com.milano.quotation.migration;

import db.migration.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeployedMigrationChecksumTest {
    @Test void resourceBytesRetainChecksumsOfAlreadyDeployedMigrations() {
        assertEquals(909714377, new V40__complete_documented_logistics_minimum_weights().getChecksum());
        assertEquals(-127072854, new V41__tongyou_sensitive_b_minimum_weight().getChecksum());
        assertEquals(-293962671, new V42__reviewed_channel_minimum_weights().getChecksum());
        assertEquals(-1041946381, new V45__additional_logistics_company_channels().getChecksum());
        assertEquals(2137329533, new V46__sunyou_company_channels().getChecksum());
    }
}
