package org.neo4j.agentmemory.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs public API probes with isolated NAMS environment settings. */
public final class EnvironmentProbe {
    private EnvironmentProbe() {}

    public static String run(Class<?> mainClass, Map<String, String> environment, String... arguments)
            throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), mainClass.getName()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("NAMS_BASE_URL");
        builder.environment().remove("NAMS_API_KEY");
        builder.environment().putAll(environment);
        var process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
