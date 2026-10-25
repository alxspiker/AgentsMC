package dev.agentsmc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bundled per-version notes for {@code plugin.updated}. Only the running
 * {@code plugin.yml} version is announced; older entries are never backfilled.
 */
final class PluginChangelog {
    static final class Notes {
        final String version;
        final List<String> changes;
        final List<String> actionsAdded;
        final List<String> actionsChanged;
        Notes(String version, List<String> changes, List<String> actionsAdded, List<String> actionsChanged) {
            this.version = version == null ? "" : version;
            this.changes = List.copyOf(changes == null ? List.of() : changes);
            this.actionsAdded = List.copyOf(actionsAdded == null ? List.of() : actionsAdded);
            this.actionsChanged = List.copyOf(actionsChanged == null ? List.of() : actionsChanged);
        }
    }

    private static final Object LOCK = new Object();
    private static Map<String, Notes> bundled = Map.of();

    static void resetForTest() {
        synchronized (LOCK) { bundled = Map.of(); }
    }

    static void loadBundled(String yml) {
        synchronized (LOCK) { bundled = parse(yml); }
    }

    static Notes current() {
        return notes(AgentVersion.current());
    }

    static Notes notes(String version) {
        if (version == null || version.isBlank()) return null;
        synchronized (LOCK) {
            if (bundled.isEmpty()) bundled = loadDefault();
            return bundled.get(version);
        }
    }

    static Map<String, Notes> parse(String yml) {
        Map<String, Notes> out = new LinkedHashMap<>();
        if (yml == null || yml.isBlank()) return out;
        String version = null;
        String section = null;
        List<String> changes = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String raw : yml.split("\\R")) {
            String line = raw.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":")) {
                if (version != null) out.put(version, new Notes(version, changes, added, changed));
                version = trimmed.substring(0, trimmed.length() - 1).trim();
                section = null;
                changes = new ArrayList<>();
                added = new ArrayList<>();
                changed = new ArrayList<>();
                continue;
            }
            if (version == null) continue;
            if (trimmed.endsWith(":") || trimmed.contains(":")) {
                String key = trimmed.contains(":") ? trimmed.substring(0, trimmed.indexOf(':')).trim() : trimmed;
                if (key.equals("changes") || key.equals("actionsAdded") || key.equals("actionsChanged")) {
                    section = key;
                    continue;
                }
            }
            if (!trimmed.startsWith("-")) continue;
            String item = trimmed.substring(1).trim();
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\""))
                item = item.substring(1, item.length() - 1);
            if (item.isEmpty()) continue;
            if ("actionsAdded".equals(section)) added.add(item);
            else if ("actionsChanged".equals(section)) changed.add(item);
            else changes.add(item);
        }
        if (version != null) out.put(version, new Notes(version, changes, added, changed));
        return out;
    }

    private static Map<String, Notes> loadDefault() {
        for (String path : new String[] {
                "source/AgentsMC/main/src/main/resources/plugin-changelog.yml",
                "main/src/main/resources/plugin-changelog.yml",
                "plugin-changelog.yml"
        }) {
            try {
                Path file = Path.of(path);
                if (Files.isRegularFile(file)) return parse(Files.readString(file));
            } catch (Exception ignored) {}
        }
        ClassLoader loader = PluginChangelog.class.getClassLoader();
        for (String res : new String[] {"plugin-changelog.yml", "/plugin-changelog.yml"}) {
            String name = res.startsWith("/") ? res.substring(1) : res;
            try (var in = loader.getResourceAsStream(name)) {
                if (in == null) continue;
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return Map.of();
    }

    private PluginChangelog() {}
}