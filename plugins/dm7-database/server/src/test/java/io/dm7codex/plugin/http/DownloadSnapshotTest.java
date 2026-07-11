package io.dm7codex.plugin.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSnapshotTest {
    @TempDir Path temporary;

    @Test void replacementAfterValidationCannotChangeTransferredBytes() throws Exception {
        Path source=temporary.resolve("release.sql"), snapshots=temporary.resolve("snapshots");
        byte[] verified="-- verified 中文\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source,verified);
        try(var download=ConsoleHttpServer.Download.snapshot("release.sql","application/sql; charset=utf-8",
                source,snapshots,sha256(verified),1024)){
            Path replacement=temporary.resolve("replacement.sql");Files.writeString(replacement,"-- unverified\n");
            Files.move(replacement,source,StandardCopyOption.REPLACE_EXISTING);
            var output=new ByteArrayOutputStream();download.writeTo(output);
            assertArrayEquals(verified,output.toByteArray());assertEquals(verified.length,download.length());
        }
        assertNoSnapshotFiles(snapshots);
    }

    @Test void shaMismatchAndSizeOverflowFailClosedWithoutTempResidue() throws Exception {
        Path source=temporary.resolve("release.sql"),snapshots=temporary.resolve("snapshots");
        Files.writeString(source,"actual");
        assertThrows(ConsoleHttpServer.DownloadRejected.class,()->ConsoleHttpServer.Download.snapshot(
                "release.sql","application/sql",source,snapshots,sha256("different".getBytes(StandardCharsets.UTF_8)),1024));
        assertThrows(ConsoleHttpServer.DownloadRejected.class,()->ConsoleHttpServer.Download.snapshot(
                "release.sql","application/sql",source,snapshots,sha256("actual".getBytes(StandardCharsets.UTF_8)),2));
        assertNoSnapshotFiles(snapshots);
    }

    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void assertNoSnapshotFiles(Path directory)throws Exception{if(!Files.exists(directory))return;try(var files=Files.list(directory)){assertEquals(0,files.count());}}
}
