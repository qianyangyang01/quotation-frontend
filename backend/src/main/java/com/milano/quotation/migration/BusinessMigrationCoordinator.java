package com.milano.quotation.migration;

import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@Service
class BusinessMigrationCoordinator {
    private final BusinessMigrationService migrations;private final BusinessMigrationExecutor executor;
    BusinessMigrationCoordinator(BusinessMigrationService migrations,BusinessMigrationExecutor executor){this.migrations=migrations;this.executor=executor;}
    BusinessMigrationBatch execute(UUID id,String actor,String requestId){var batch=migrations.markExecuting(id,requestId);try{var execution=executor.execute(batch,actor);return migrations.markCompleted(id,execution);}catch(RuntimeException error){migrations.markFailed(id,error);throw error;}}
    BusinessMigrationBatch rollback(UUID id){var batch=migrations.get(id);var result=executor.rollback(batch);return migrations.markRolledBack(id,result);}
}
