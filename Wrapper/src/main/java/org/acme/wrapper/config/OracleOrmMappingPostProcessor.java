package org.acme.wrapper.config;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Works around a BAMOE 9.4.2 bug on Oracle: the Oracle kie-flyway script of
 * {@code jbpm-addons-usertask-storage-jpa} creates the usertask comments column as
 * {@code TASK_COMMENT} ({@code COMMENT} is reserved in Oracle), but the shipped
 * {@code CommentEntity} maps the field to {@code comment} — every comment read fails
 * with ORA-01747.
 *
 * <p>When the datasource URL is Oracle and the consumer has not configured
 * {@code spring.jpa.mapping-resources} itself, this activates the bundled
 * {@code orm-oracle.xml} override (CommentEntity.comment → task_comment). Registered
 * via {@code META-INF/spring.factories}; added with lowest precedence so any explicit
 * application property wins.
 */
public class OracleOrmMappingPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url", "");
        String existing = environment.getProperty("spring.jpa.mapping-resources");
        if (url.toLowerCase().contains("oracle") && (existing == null || existing.isBlank())) {
            environment.getPropertySources().addLast(new MapPropertySource(
                    "bamoe-wrapper-oracle-orm",
                    Map.of("spring.jpa.mapping-resources", "orm-oracle.xml")));
        }
    }
}
