package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.dm7codex.plugin.runtime.RuntimePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class DmDriverLoaderTest {
    @TempDir Path tempDir;

    @Test void verifiesHashLoadsExplicitDriverAndDoesNotRegisterGlobally() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver"));
        RuntimePaths paths = paths("normal");
        Path cache = paths.driverCacheDirectory();
        DmDriverLoader loader = new DmDriverLoader(paths);
        ConnectionProfile profile = profile(fixture);
        DmDriverLoader.DriverHandle handle = loader.load(profile);
        WeakReference<ClassLoader> classLoader;
        try {
            assertEquals(0, handle.registeredDriverCount());
            Properties properties = new Properties();
            properties.setProperty("user", "fixture-user");
            properties.setProperty("registerOnConnect", "true");
            assertNotNull(handle.connect(profile.jdbcUrl(), properties));
            assertEquals(1, handle.registeredDriverCount());
            classLoader = new WeakReference<>(handle.driverClassLoader());
        } finally {
            handle.close();
        }
        assertEquals(0, handle.registeredDriverCount());
        assertNoStagedJars(cache);
        handle = null;
        for (int i = 0; i < 20 && classLoader.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(classLoader.get(), "closed driver classloader must be releasable");
    }

    @Test void rejectsMissingDirectoryWrongHashAndNonDriverWithoutLeakingPath() throws Exception {
        RuntimePaths paths = paths("reject");
        Path cache = paths.driverCacheDirectory();
        DmDriverLoader loader = new DmDriverLoader(paths);
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("valid"));
        Exception hash = assertThrows(Exception.class, () -> loader.load(new ConnectionProfile(UUID.randomUUID(), "x",
                fixture.jar(), "f".repeat(64), fixture.driverClass(), "jdbc:dm7://host:5236", "u", null,
                10, 30, 60, 1000, 1024, true)));
        assertFalse(hash.getMessage().contains(fixture.jar().toString()));
        Files.createDirectories(tempDir.resolve("directory.jar"));
        assertThrows(Exception.class, () -> loader.load(profile(tempDir.resolve("directory.jar"), "0".repeat(64), fixture.driverClass())));
        assertThrows(Exception.class, () -> loader.load(profile(tempDir.resolve("missing.jar"), "0".repeat(64), fixture.driverClass())));
        FakeDriverJar.Fixture nonDriver = FakeDriverJar.createNonDriver(tempDir.resolve("not-driver"));
        assertThrows(Exception.class, () -> loader.load(profile(nonDriver.jar(), nonDriver.sha256(), nonDriver.driverClass())));
        assertNoStagedJars(cache);
    }

    @Test void sourceJarReplacementAfterLoadCannotChangeExecutedDriver() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("source"));
        RuntimePaths paths = paths("staging");
        Path cache = paths.driverCacheDirectory();
        DmDriverLoader loader = new DmDriverLoader(paths);
        ConnectionProfile profile = profile(fixture);
        try (DmDriverLoader.DriverHandle handle = loader.load(profile)) {
            Files.writeString(fixture.jar(), "replacement-not-a-jar");
            assertNotNull(handle.connect(profile.jdbcUrl(), new Properties()));
            try (var files = Files.list(cache)) {
                assertEquals(1, files.filter(DmDriverLoaderTest::isStagedJar).count());
            }
        }
        assertNoStagedJars(cache);
    }

    @Test void classInitializationFailureCleansStaticRegistrationAndStaging() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.createRegisterThenFail(tempDir.resolve("register-fail"));
        RuntimePaths paths = paths("failure-cleanup");
        Path cache = paths.driverCacheDirectory();
        DmDriverLoader loader = new DmDriverLoader(paths);
        assertThrows(Exception.class, () -> loader.load(profile(fixture)));
        @SuppressWarnings("unchecked")
        WeakReference<ClassLoader> failedLoader =
                (WeakReference<ClassLoader>) System.getProperties().remove("dm7.fixture.failedLoader");
        assertNotNull(failedLoader);
        for (int i = 0; i < 20 && failedLoader.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(failedLoader.get(), "failed driver loader must not be retained by DriverManager");
        assertNoStagedJars(cache);
    }

    @Test void publicConstructionIsBoundToRuntimePathsNotArbitraryPath() {
        assertTrue(Arrays.stream(DmDriverLoader.class.getConstructors()).allMatch(constructor ->
                Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{RuntimePaths.class})));
    }

    @Test void replacingCacheDirectoryIdentityFailsClosedBeforeDriverExecution() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("identity-driver"));
        RuntimePaths paths = paths("identity");
        DmDriverLoader loader = new DmDriverLoader(paths);
        Path cache = paths.driverCacheDirectory();
        Path original = cache.resolveSibling("jdbc-drivers-original");
        Files.move(cache, original);
        Files.createDirectory(cache);
        System.clearProperty("dm7.fixture.driverLoaded");
        DmDriverLoader.DriverIsolationException failure = assertThrows(
                DmDriverLoader.DriverIsolationException.class, () -> loader.load(profile(fixture)));
        assertTrue(failure.restartRequired());
        assertNull(System.getProperty("dm7.fixture.driverLoaded"));
    }

    @Test void failedLoadRunsCloseAndDeleteAndAggregatesBothFailures() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.createNonDriver(tempDir.resolve("cleanup-driver"));
        RuntimePaths paths = paths("cleanup-aggregation");
        AtomicBoolean closeAttempted = new AtomicBoolean();
        AtomicBoolean deleteAttempted = new AtomicBoolean();
        DmDriverLoader.LoaderFileOps operations = new DmDriverLoader.LoaderFileOps() {
            @Override public void close(java.net.URLClassLoader loader) {
                closeAttempted.set(true);
                try { loader.close(); } catch (Exception ignored) {}
                throw new IllegalStateException("injected close failure");
            }
            @Override public void delete(Path stagedJar) {
                deleteAttempted.set(true);
                try { Files.deleteIfExists(stagedJar); } catch (Exception ignored) {}
                throw new IllegalStateException("injected delete failure");
            }
        };
        DmDriverLoader loader = new DmDriverLoader(paths, operations);
        Exception failure = assertThrows(Exception.class, () -> loader.load(profile(fixture)));
        assertTrue(closeAttempted.get());
        assertTrue(deleteAttempted.get());
        assertEquals(2, failure.getSuppressed().length);
    }

    @Test void throwingDriverActionFailsClosedInIndependentJvmWithRestartRequired() throws Exception {
        assertEquals(0, runProbe("factory-service"));
    }

    @Test void freshJvmDisablesOnlyJarCachingAndConcurrentLoadsLeaveNoStagedFiles() throws Exception {
        assertEquals(0,runProbe("jar-cache"));
    }

    @Test void closeIsolationFailureLatchesAndBlocksExistingHandleInIndependentJvm() throws Exception {
        assertEquals(0, runProbe("close"));
    }

    @Test void factorySurfacesIsolationFromCleanupAfterConnectFailureInIndependentJvm() throws Exception {
        assertEquals(0, runProbe("factory-connect-cleanup"));
    }

    @Test void factorySurfacesIsolationFromCleanupAfterCredentialFailureInIndependentJvm() throws Exception {
        assertEquals(0, runProbe("factory-credential-cleanup"));
    }

    private int runProbe(String mode) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                DmDriverLoaderProcessProbe.class.getName(), tempDir.resolve("probe-data").toString(),
                tempDir.resolve("probe-fixture").toString(), mode).inheritIO().start();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertTrue(process.waitFor(25, TimeUnit.SECONDS)));
        return process.exitValue();
    }

    @Test void stagedFileReplacementAfterHashFailsBeforeDriverInitialization() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("stage-race-driver"));
        RuntimePaths paths = paths("stage-race");
        System.clearProperty("dm7.fixture.driverLoaded");
        DmDriverLoader loader = new DmDriverLoader(paths, DmDriverLoader.LoaderFileOps.DEFAULT,
                staged -> Files.writeString(staged, "tampered-after-hash"));
        assertThrows(DmDriverLoader.DriverIsolationException.class, () -> loader.load(profile(fixture)));
        assertNull(System.getProperty("dm7.fixture.driverLoaded"));
    }

    @Test void parentChainReplacementDuringLoadFailsClosedBeforeDriverInitialization() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("parent-race-driver"));
        RuntimePaths paths = paths("parent-race");
        CountDownLatch stagedHashed = new CountDownLatch(1);
        CountDownLatch replacementDone = new CountDownLatch(1);
        DmDriverLoader loader = new DmDriverLoader(paths, DmDriverLoader.LoaderFileOps.DEFAULT, staged -> {
            stagedHashed.countDown();
            try {
                assertTrue(replacementDone.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        System.clearProperty("dm7.fixture.driverLoaded");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var load = executor.submit(() -> loader.load(profile(fixture)));
            assertTrue(stagedHashed.await(10, TimeUnit.SECONDS));
            Path cacheParent = paths.pluginData().resolve("cache");
            deliberatelyRelaxDirectoryForReplacement(paths.pluginData());
            Files.move(cacheParent, cacheParent.resolveSibling("cache-original"));
            Files.createDirectories(paths.driverCacheDirectory());
            replacementDone.countDown();
            Exception failure = assertThrows(Exception.class, load::get);
            assertTrue(failure.getCause() instanceof DmDriverLoader.DriverIsolationException);
            assertNull(System.getProperty("dm7.fixture.driverLoaded"));
        } finally {
            replacementDone.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void deliberatelyRelaxDirectoryForReplacement(Path directory) throws Exception {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, java.util.EnumSet.allOf(java.nio.file.attribute.PosixFilePermission.class));
            return;
        }
        var view = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
        var owner = Files.getOwner(directory);
        var entry = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                .build();
        view.setAcl(java.util.List.of(entry));
    }

    private static void assertNoStagedJars(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(DmDriverLoaderTest::isStagedJar).count());
        }
    }

    private static boolean isStagedJar(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("dm-driver-") && name.endsWith(".jar");
    }

    private RuntimePaths paths(String name) {
        return RuntimePaths.forTest(tempDir.resolve("plugin-data-" + name));
    }

    static ConnectionProfile profile(FakeDriverJar.Fixture fixture) {
        return profile(fixture.jar(), fixture.sha256(), fixture.driverClass());
    }

    private static ConnectionProfile profile(Path jar, String sha, String driverClass) {
        return new ConnectionProfile(UUID.randomUUID(), "fixture", jar, sha, driverClass,
                "jdbc:dm7://fixture.invalid:5236?dbname=TEST", "fixture-user", "业务模式",
                7, 19, 31, 1000, 1024, true);
    }
}
