package dev.agentsmc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.plugin.Plugin;

/**
 * Plugin version comes from {@code plugin.yml} / {@link org.bukkit.plugin.PluginDescriptionFile},
 * never a hardcoded constant that can drift.
 */
final class AgentVersion {
    static String current() {
        return current(AgentsMC.INSTANCE);
    }

    static String current(Plugin plugin) {
        String described = null;
        try {
            if (plugin != null) described = plugin.getDescription().getVersion();
        } catch (Throwable ignored) {}
        if (agentsMc(described)) return described;
        String yml = fromPluginYml();
        if (agentsMc(yml)) return yml;
        if (described != null && !described.isBlank()) return described;
        if (yml != null && !yml.isBlank()) return yml;
        throw new IllegalStateException("AgentsMC version is missing from plugin.yml and getDescription()");
    }

    static boolean agentsMc(String version) {
        return version != null && version.matches("0\\.\\d+\\.\\d+.*");
    }

    static String fromPluginYml() {
        for (String path : new String[] {
                "source/AgentsMC/main/src/main/resources/plugin.yml",
                "main/src/main/resources/plugin.yml",
                "plugin.yml"
        }) {
            try {
                Path file = Path.of(path);
                if (Files.isRegularFile(file)) {
                    String version = parseVersion(Files.readString(file));
                    if (agentsMc(version)) return version;
                }
            } catch (Exception ignored) {}
        }
        ClassLoader loader = AgentVersion.class.getClassLoader();
        for (String res : new String[] {"plugin.yml", "/plugin.yml"}) {
            String name = res.startsWith("/") ? res.substring(1) : res;
            try (var in = loader.getResourceAsStream(name)) {
                if (in == null) continue;
                String version = parseVersion(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (agentsMc(version)) return version;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String parseVersion(String yml) {
        if (yml == null) return null;
        for (String line : yml.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("version:")) {
                String value = trimmed.substring("version:".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                return value;
            }
        }
        return null;
    }

    private AgentVersion() {}
}
