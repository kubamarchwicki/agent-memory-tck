package org.neo4j.agentmemory.testsupport;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs public API probes with isolated NAMS environment settings. */
public final class EnvironmentProbe {
    private EnvironmentProbe() {}

    public static String run(Class<?> mainClass, Map<String, String> environment, String... arguments)
            throws Exception {
        return runOnClasspath(System.getProperty("java.class.path"), mainClass, environment, arguments);
    }

    public static String classpathWithout(String... fileNamePrefixes) {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> Arrays.stream(fileNamePrefixes)
                        .noneMatch(prefix -> Path.of(entry).getFileName().toString().startsWith(prefix)))
                .collect(Collectors.joining(File.pathSeparator));
    }

    public static String runOnClasspath(String classpath, Class<?> mainClass,
            Map<String, String> environment, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, mainClass.getName()));
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
