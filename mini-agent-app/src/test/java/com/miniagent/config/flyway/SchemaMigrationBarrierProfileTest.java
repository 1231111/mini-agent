package com.miniagent.config.flyway;

import com.miniagent.application.SubjectErasureService;
import com.miniagent.config.service.DbActionJournal;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.DependsOn;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationBarrierProfileTest {

    @Test
    void defaultProductionProfileProvidesSharedBarrierForComponents() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setDefaultProfiles("prod");
            context.register(NonDesktopSchemaMigrationBarrierConfig.class);
            context.refresh();

            assertThat(context.containsBean("postJpaSchemaMigrationBarrier")).isTrue();
            assertThat(SubjectErasureService.class.getAnnotation(DependsOn.class).value())
                    .contains("postJpaSchemaMigrationBarrier");
            assertThat(DbActionJournal.class.getAnnotation(DependsOn.class).value())
                    .contains("postJpaSchemaMigrationBarrier");
        }
    }
}
