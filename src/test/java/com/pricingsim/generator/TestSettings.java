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
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.pricing.PricingPolicy;

/**
 * Binds {@code generator.*} from the real {@code application.yml} without starting Spring, so generator tests use the
 * same profiles as the CLI.
 */
public final class TestSettings {

    private static final Binder BINDER = binder();
    private static final GeneratorProperties PROPERTIES = BINDER.bind("generator", GeneratorProperties.class)
            .orElseThrow(() -> new IllegalStateException("No generator.* properties in application.yml"));
    private static final PricingPolicy POLICY = BINDER.bind("pricing.policy", PricingPolicyProperties.class)
            .orElseThrow(() -> new IllegalStateException("No pricing.policy.* properties in application.yml"))
            .toPolicy();

    private TestSettings() {
    }

    public static GeneratorProperties properties() {
        return PROPERTIES;
    }

    /** The pricing policy bound from {@code pricing.policy.*}. */
    public static PricingPolicy policy() {
        return POLICY;
    }

    /** Settings for a named profile with the default seed. */
    public static GenerationSettings forScale(String scale) {
        return forScale(scale, PROPERTIES.seed());
    }

    public static GenerationSettings forScale(String scale, long seed) {
        return new GenerationSettings(seed, scale, PROPERTIES.scale(scale), PROPERTIES.referenceTs(), POLICY,
                List.of("generate", "--scale", scale));
    }

    private static Binder binder() {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
            MutablePropertySources mutable = new MutablePropertySources();
            sources.forEach(mutable::addLast);
            return new Binder(ConfigurationPropertySources.from(mutable));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
