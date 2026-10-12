package org.acme;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Activates {@code orm-oracle.xml} when the datasource is Oracle and the app has not set
 * {@code spring.jpa.mapping-resources} itself (added last, so an explicit property wins).
 * Must stay inactive on H2/PostgreSQL, whose BAMOE DDL matches the entity annotations.
 * Registered in {@code META-INF/spring.factories} because JPA settings are read before beans exist.
 */
public class OracleOrmMappingPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url", "");
        String existing = environment.getProperty("spring.jpa.mapping-resources");
        if (url.toLowerCase().contains("oracle") && (existing == null || existing.isBlank())) {
            environment.getPropertySources().addLast(new MapPropertySource(
                    "bamoe-oracle-orm",
                    Map.of("spring.jpa.mapping-resources", "orm-oracle.xml")));
        }
    }
}
