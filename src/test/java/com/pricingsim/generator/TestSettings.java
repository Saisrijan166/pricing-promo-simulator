package com.pricingsim.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.pricingsim.config.GeneratorProperties;

/**
 * Binds {@code generator.*} from the real {@code application.yml} without starting Spring, so generator tests use the
 * same profiles as the CLI.
 */
public final class TestSettings {

    private static final GeneratorProperties PROPERTIES = load();

    private TestSettings() {
    }

    public static GeneratorProperties properties() {
        return PROPERTIES;
    }

    /** Settings for a named profile with the default seed. */
    public static GenerationSettings forScale(String scale) {
        return forScale(scale, PROPERTIES.seed());
    }

    public static GenerationSettings forScale(String scale, long seed) {
        return new GenerationSettings(seed, scale, PROPERTIES.scale(scale), PROPERTIES.referenceTs(),
                List.of("generate", "--scale", scale));
    }

    private static GeneratorProperties load() {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
            MutablePropertySources mutable = new MutablePropertySources();
            sources.forEach(mutable::addLast);
            return new Binder(ConfigurationPropertySources.from(mutable))
                    .bind("generator", GeneratorProperties.class)
                    .orElseThrow(() -> new IllegalStateException("No generator.* properties in application.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
