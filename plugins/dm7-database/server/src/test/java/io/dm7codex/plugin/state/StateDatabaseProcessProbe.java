package io.dm7codex.plugin.state;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class StateDatabaseProcessProbe {
    private StateDatabaseProcessProbe() {}

    public static void main(String[] args) throws Exception {
        var mode = args[0];
        var databasePath = Path.of(args[1]).toAbsolutePath().normalize();
        var readyPath = Path.of(args[2]);
        if ("open".equals(mode)) {
            try (var ignored = StateDatabase.open(databasePath)) {
                return;
            }
        }
        if (!"hold-and-halt".equals(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        Files.createDirectories(databasePath.getParent());
        var lockPath = databasePath.resolveSibling(databasePath.getFileName() + ".migration.lock");
        try (var channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            Files.writeString(readyPath, "ready", StandardOpenOption.CREATE_NEW);
            Thread.sleep(1_000);
            Runtime.getRuntime().halt(0);
        }
    }
}
