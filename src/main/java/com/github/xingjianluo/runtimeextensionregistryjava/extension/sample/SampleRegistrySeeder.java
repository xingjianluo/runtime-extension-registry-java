package com.github.xingjianluo.runtimeextensionregistryjava.extension.sample;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.DependencyRequirement;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时播种示例扩展（一批原子变更）：
 * <ul>
 *   <li>{@code echo} 1.0.0：无依赖，默认启用</li>
 *   <li>{@code transform} 1.0.0：依赖 echo [1.0.0,2.0.0)，默认启用，mode=UPPER</li>
 *   <li>{@code strict} 1.0.0：无依赖，默认停用，带合法初始配置</li>
 * </ul>
 */
@Component
@Order(0)
public class SampleRegistrySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleRegistrySeeder.class);

    private final ExtensionRegistry registry;

    public SampleRegistrySeeder(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        ExtensionDefinition echo = new ExtensionDefinition(
                "echo",
                Version.parse("1.0.0"),
                List.of(),
                true,
                Map.of("prefix", "echo"),
                EchoExtension::new);

        ExtensionDefinition transform = new ExtensionDefinition(
                "transform",
                Version.parse("1.0.0"),
                List.of(new DependencyRequirement("echo", "[1.0.0,2.0.0)")),
                true,
                Map.of("mode", "UPPER"),
                TransformExtension::new);

        ExtensionDefinition strict = new ExtensionDefinition(
                "strict",
                Version.parse("1.0.0"),
                List.of(),
                false,
                Map.of("required", "seed-value"),
                StrictConfigExtension::new);

        ChangeSet seed = new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(echo),
                new ChangeCommand.RegisterDefinition(transform),
                new ChangeCommand.RegisterDefinition(strict)));

        var outcome = registry.apply(seed);
        log.info("sample extensions seeded at generation {}", outcome.generation());
    }
}
