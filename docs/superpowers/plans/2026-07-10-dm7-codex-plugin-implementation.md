# DM7 Codex Database Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, package, and verify a shareable Codex plugin that exposes safe Dameng 7 MCP tools plus a polished local management console, per-session release SQL logging, and UTF-8 exports.

**Architecture:** A Java 17 fat JAR owns the STDIO MCP server, loopback HTTP API, JDBC execution, session state, encrypted credentials, SQLite history, and release-log rotation. A React/Vite SPA is packaged into that JAR and opened through Codex's in-app browser; all MCP and UI operations call the same application services.

**Tech Stack:** Java 17 bytecode on JDK 21, Maven 3.9.16, MCP Java SDK BOM 2.0.0, Jackson 2.22.1, SQLite JDBC 3.53.2.0, JUnit 6.1.1, React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Vitest 4.1.10, Playwright 1.61.1, CodeMirror 6, TanStack Table 8.21.3, TanStack Virtual 3.14.5, plain CSS.

## Global Constraints

- Plugin identifier is exactly `dm7-database`; Java package root is `io.dm7codex.plugin`.
- Build with JDK 21 and `maven.compiler.release=17`; runtime floor is Java 17.
- The shareable plugin never contains the Dameng JDBC JAR, JDBC password, test password, or machine-specific absolute paths.
- `plugins/dm7-database/lib/dm7-codex-plugin.jar` is a reproducible, versioned runtime artifact committed at the packaging task so a marketplace install works without Maven; source build directories and ZIP staging remain ignored.
- The driver is loaded explicitly as `dm7.jdbc.driver.Dm7Driver` from a user-configured JAR.
- All writable state lives below `PLUGIN_DATA`; the installed plugin root is read-only.
- STDOUT is reserved exclusively for MCP JSON-RPC. Application diagnostics go to STDERR or `PLUGIN_DATA/logs/server.log`.
- HTTP binds only to `127.0.0.1`, uses a single-use console token, and redirects to a token-free URL.
- Every new Codex thread creates an independent UTF-8, no-BOM `v001 active.sql` on its first plugin call.
- Only successfully committed `production_change` or `migration` DDL/DML enters release SQL; `test`, `mock`, `seed`, and `sample` never enter it.
- A release version binds to the first tracked database fingerprint and rejects tracked cross-database writes until rotation.
- `atomic=true` accepts pure DML only; any script containing DDL is rejected before execution.
- Export rotates atomically, creates the next empty version immediately, does not add idempotency guards, and returns SHA-256.
- JDBC strings stay as Java Unicode; MCP, HTTP, SSE, CSV, JSON, and SQL are explicitly UTF-8.
- Integration secrets are injected only through `DM7_IT_JDBC_URL`, `DM7_IT_USERNAME`, `DM7_IT_PASSWORD`, and `DM7_IT_DRIVER_JAR`.
- Use `apply_patch` for source edits, TDD for every behavior, `git diff --check` before each commit, and small commits at task boundaries.

---

## Locked File Structure

```text
.agents/plugins/marketplace.json
.gitignore
README.md
docs/superpowers/specs/2026-07-10-dm7-codex-plugin-design.md
docs/superpowers/plans/2026-07-10-dm7-codex-plugin-implementation.md
tests/plugin_layout_test.py
tests/mcp_stdio_smoke.py
plugins/dm7-database/.codex-plugin/plugin.json
plugins/dm7-database/.mcp.json
plugins/dm7-database/README.md
plugins/dm7-database/LICENSE
plugins/dm7-database/THIRD_PARTY_NOTICES.md
plugins/dm7-database/assets/icon.svg
plugins/dm7-database/assets/logo.svg
plugins/dm7-database/assets/logo-dark.svg
plugins/dm7-database/assets/screenshot-console.png
plugins/dm7-database/assets/screenshot-release.png
plugins/dm7-database/hooks/hooks.json
plugins/dm7-database/hooks/session-context.ps1
plugins/dm7-database/skills/dm7-database/SKILL.md
plugins/dm7-database/scripts/build.ps1
plugins/dm7-database/scripts/test.ps1
plugins/dm7-database/scripts/package.ps1
plugins/dm7-database/lib/dm7-codex-plugin.jar
plugins/dm7-database/server/pom.xml
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/AppMain.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/runtime/RuntimePaths.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/runtime/SessionIdentity.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/runtime/SessionState.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/runtime/SessionIdentityResolver.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/runtime/SessionInitializer.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/state/StateDatabase.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/state/SessionRepository.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/state/ExecutionRepository.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/state/ExportRepository.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/ConnectionProfile.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/ConnectionConfigRepository.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/CredentialVault.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/DmDriverLoader.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/DmConnectionFactory.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/ConnectionTestService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/connection/JdbcUrlDiagnostics.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/SqlKind.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/SqlPurpose.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/ParsedStatement.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/DmSqlParser.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/DmLiteralRenderer.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/SqlSecurityPolicy.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/SecretBearingSqlException.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/sql/UnrenderableParameterException.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/release/ReleaseLogService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/release/ReleaseExportService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/release/SessionFileLock.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/release/ReleaseLogConnectionMismatch.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/release/ReleaseExportLockTimeout.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/ExecutionModels.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/ExecutionEventBus.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/ExecutionRegistry.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/ExecutionService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/MetadataService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/AtomicDdlNotSupported.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/execution/UntrackableMutationException.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/mcp/Dm7McpServer.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/mcp/Dm7ToolSchemas.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/http/ConsoleHttpServer.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/http/ConsoleTokenService.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/http/HttpSecurity.java
plugins/dm7-database/server/src/main/java/io/dm7codex/plugin/http/JsonHttp.java
plugins/dm7-database/server/src/main/resources/logging.properties
plugins/dm7-database/server/src/test/java/io/dm7codex/plugin/**
plugins/dm7-database/server/src/test/resources/fixtures/**
plugins/dm7-database/web/package.json
plugins/dm7-database/web/pnpm-lock.yaml
plugins/dm7-database/web/tsconfig.json
plugins/dm7-database/web/vite.config.ts
plugins/dm7-database/web/playwright.config.ts
plugins/dm7-database/web/index.html
plugins/dm7-database/web/src/main.tsx
plugins/dm7-database/web/src/App.tsx
plugins/dm7-database/web/src/api/client.ts
plugins/dm7-database/web/src/api/types.ts
plugins/dm7-database/web/src/components/**
plugins/dm7-database/web/src/pages/**
plugins/dm7-database/web/src/styles/**
plugins/dm7-database/web/src/**/*.test.tsx
plugins/dm7-database/web/e2e/**
artifacts/acceptance/.gitkeep
```

## Task 1: Scaffold the Shareable Plugin and Reproducible Builds

**Files:**
- Create: `.agents/plugins/marketplace.json`, `.gitignore`, `README.md`, `tests/plugin_layout_test.py`
- Create: `plugins/dm7-database/.codex-plugin/plugin.json`, `.mcp.json`, assets, skill, hook, scripts
- Create: `plugins/dm7-database/server/pom.xml`, `plugins/dm7-database/web/package.json`, TypeScript/Vite config
- Test: `tests/plugin_layout_test.py`

**Interfaces:**
- Consumes: approved design specification only.
- Produces: plugin root `plugins/dm7-database`, Maven module, frontend module, repo marketplace entry, `build.ps1`, `test.ps1`, `package.ps1`.

- [ ] **Step 1: Write the failing repository layout test**

```python
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"

class PluginLayoutTest(unittest.TestCase):
    def test_required_plugin_files_and_names_match(self):
        manifest = json.loads((PLUGIN / ".codex-plugin" / "plugin.json").read_text("utf-8"))
        market = json.loads((ROOT / ".agents" / "plugins" / "marketplace.json").read_text("utf-8"))
        self.assertEqual("dm7-database", manifest["name"])
        self.assertEqual("./.mcp.json", manifest["mcpServers"])
        self.assertEqual("dm7-database", market["plugins"][0]["name"])
        self.assertEqual("./plugins/dm7-database", market["plugins"][0]["source"]["path"])
        for relative in [".mcp.json", "skills/dm7-database/SKILL.md", "assets/icon.svg", "server/pom.xml", "web/package.json"]:
            self.assertTrue((PLUGIN / relative).is_file(), relative)

if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the layout test and verify it fails**

Run: `python -m unittest tests.plugin_layout_test -v`

Expected: `ERROR` because `plugins/dm7-database/.codex-plugin/plugin.json` does not exist.

- [ ] **Step 3: Run the plugin-creator scaffold and replace generated metadata with approved values**

Run from the plugin-creator skill directory:

```powershell
$Repo = (Resolve-Path '.').Path
python scripts/create_basic_plugin.py dm7-database `
  --path (Join-Path $Repo 'plugins') `
  --marketplace-path (Join-Path $Repo '.agents\plugins\marketplace.json') `
  --marketplace-name dm7-database-local `
  --with-skills --with-hooks --with-scripts --with-assets --with-mcp --with-marketplace
```

Set the manifest core to:

```json
{
  "name": "dm7-database",
  "version": "0.1.0",
  "description": "Operate Dameng 7 safely from Codex with a local SQL console and release logs.",
  "author": { "name": "DM7 Codex Plugin Contributors" },
  "license": "Apache-2.0",
  "keywords": ["dameng", "dm7", "database", "sql", "mcp"],
  "skills": "./skills/",
  "mcpServers": "./.mcp.json",
  "interface": {
    "displayName": "Dameng 7 Database",
    "shortDescription": "Query DM7 and export session-scoped release SQL",
    "longDescription": "Connect Codex to Dameng 7, run SQL with live progress, manage connections, and export filtered DDL/DML release logs.",
    "developerName": "DM7 Codex Plugin Contributors",
    "category": "Developer Tools",
    "capabilities": ["Read", "Write", "Interactive"],
    "defaultPrompt": ["打开达梦数据库管理控制台", "查询当前达梦数据库结构", "导出本会话的发版 SQL"],
    "brandColor": "#147D64",
    "composerIcon": "./assets/icon.svg",
    "logo": "./assets/logo.svg",
    "logoDark": "./assets/logo-dark.svg"
  }
}
```

Use this MCP declaration:

```json
{
  "mcp_servers": {
    "dm7": {
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "${PLUGIN_ROOT}/lib/dm7-codex-plugin.jar", "--stdio"]
    }
  }
}
```

- [ ] **Step 4: Add exact Maven and frontend dependency locks**

The Maven POM must import `io.modelcontextprotocol.sdk:mcp-bom:2.0.0` and `org.junit:junit-bom:6.1.1`; add `mcp-core`, `mcp-json-jackson2`, `jackson-databind:2.22.1`, `sqlite-jdbc:3.53.2.0`, `slf4j-simple:2.0.17`, and `junit-jupiter` test scope. Configure Maven Compiler 3.14.1 with release 17, Surefire 3.5.6, JaCoCo 0.8.14, and Maven Shade 3.6.1 with `io.dm7codex.plugin.AppMain` as the main class and output `../lib/dm7-codex-plugin.jar`. Set `project.build.outputTimestamp` from `SOURCE_DATE_EPOCH`, sort copied frontend resources, and strip signature files so two builds from the same commit have identical SHA-256.

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <project.build.outputTimestamp>${env.SOURCE_DATE_EPOCH}</project.build.outputTimestamp>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-bom</artifactId><version>2.0.0</version><type>pom</type><scope>import</scope></dependency>
    <dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>6.1.1</version><type>pom</type><scope>import</scope></dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-core</artifactId></dependency>
  <dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-json-jackson2</artifactId></dependency>
  <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.22.1</version></dependency>
  <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>3.53.2.0</version></dependency>
  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>2.0.17</version></dependency>
  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
</dependencies>
```

Use this `.gitignore` content; deliberately do not ignore the distributable runtime JAR:

```gitignore
**/target/
**/node_modules/
plugins/dm7-database/web/dist/
dist/
.env
.env.*
*.env.local
```

The frontend `package.json` must pin the exact versions from the Tech Stack plus:

```json
{
  "scripts": {
    "dev": "vite --host 127.0.0.1",
    "build": "tsc -b && vite build",
    "test": "vitest run",
    "e2e": "playwright test",
    "check": "tsc -b --pretty false && vitest run"
  },
  "dependencies": {
    "@codemirror/commands": "6.10.4",
    "@codemirror/lang-sql": "6.10.0",
    "@codemirror/state": "6.7.1",
    "@codemirror/view": "6.43.6",
    "@tanstack/react-table": "8.21.3",
    "@tanstack/react-virtual": "3.14.5",
    "lucide-react": "1.24.0",
    "react": "19.2.7",
    "react-dom": "19.2.7"
  },
  "devDependencies": {
    "@playwright/test": "1.61.1",
    "@testing-library/react": "16.3.2",
    "@types/react": "19.2.17",
    "@types/react-dom": "19.2.3",
    "@vitejs/plugin-react": "6.0.3",
    "jsdom": "29.1.1",
    "typescript": "7.0.2",
    "vite": "8.1.4",
    "vitest": "4.1.10"
  }
}
```

- [ ] **Step 5: Run scaffold validation and commit**

Run:

```powershell
python -m unittest tests.plugin_layout_test -v
python "$env:USERPROFILE\.codex\skills\.system\plugin-creator\scripts\validate_plugin.py" "plugins\dm7-database"
git diff --check
git add .agents .gitignore README.md tests plugins/dm7-database
git commit -m "build: scaffold DM7 Codex plugin"
```

Expected: layout test `OK`; plugin validator reports valid manifest; commit succeeds.

## Task 2: Runtime Paths, SQLite State, and First-Call Session Initialization

**Files:**
- Create: runtime and state Java files listed in Locked File Structure
- Test: `RuntimePathsTest`, `StateDatabaseTest`, `SessionIdentityResolverTest`, `SessionInitializerTest`

**Interfaces:**
- Produces: `RuntimePaths.fromEnvironment(Map<String,String>, Path)`, `SessionIdentityResolver.resolve(Map<String,String>)`, `SessionInitializer.initialize(SessionIdentity)`, SQLite schema version 1.
- Later tasks consume `SessionIdentity.externalId()`, `SessionIdentity.isolation()`, `SessionRepository`, and `RuntimePaths` directory getters.

- [ ] **Step 1: Write failing tests for path safety and session creation**

```java
@Test void firstCallCreatesIndependentV001ActiveSql() throws Exception {
    RuntimePaths paths = RuntimePaths.forTest(tempDir);
    StateDatabase db = StateDatabase.open(paths.stateDatabase());
    SessionInitializer initializer = new SessionInitializer(paths, new SessionRepository(db));
    var a = initializer.initialize(new SessionIdentity("thread-a", "codex_thread", "verified"));
    var b = initializer.initialize(new SessionIdentity("thread-b", "codex_thread", "verified"));
    assertEquals(1, a.version());
    assertEquals(1, b.version());
    assertTrue(Files.readString(a.activeSql(), UTF_8).contains("version: v001"));
    assertNotEquals(a.activeSql(), b.activeSql());
    assertArrayEquals(new byte[]{'-','-' }, Files.readAllBytes(a.activeSql()).length >= 2
        ? Arrays.copyOf(Files.readAllBytes(a.activeSql()), 2) : new byte[0]);
}
```

Also test: missing `PLUGIN_DATA` fails closed outside tests; invalid `CODEX_THREAD_ID` is hashed into a safe directory; repeated initialization returns the same version without truncation; fallback isolation is `process_fallback`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=RuntimePathsTest,StateDatabaseTest,SessionIdentityResolverTest,SessionInitializerTest test`

Expected: compilation failure because runtime/state classes do not exist.

- [ ] **Step 3: Implement the exact records and schema**

```java
public record SessionIdentity(String externalId, String source, String isolation) {}

public record SessionState(
    String sessionId,
    String externalIdHash,
    int version,
    String databaseFingerprint,
    Path activeSql,
    Instant createdAt
) {}
```

SQLite migration 1 creates `logical_session`, `release_version`, `execution`, `statement_event`, and `export_artifact`. Enable `PRAGMA foreign_keys=ON`, `journal_mode=WAL`, and `busy_timeout=5000`. `SessionInitializer.initialize` performs one transaction, creates `sessions/{sessionHash}/active.sql` with `CREATE_NEW`, and writes a UTF-8 header with `database-fingerprint: unbound`.

- [ ] **Step 4: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=RuntimePathsTest,StateDatabaseTest,SessionIdentityResolverTest,SessionInitializerTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: initialize per-thread release sessions"
```

Expected: all four test classes pass.

## Task 3: Encrypted Credentials, Connection Profiles, Driver Loading, and URL Diagnostics

**Files:**
- Create: connection Java files from Locked File Structure
- Test: `CredentialVaultTest`, `ConnectionConfigRepositoryTest`, `DmDriverLoaderTest`, `JdbcUrlDiagnosticsTest`, `DmConnectionFactoryTest`, `ConnectionTestServiceTest`

**Interfaces:**
- Produces: `CredentialVault.put/read/delete`, `ConnectionConfigRepository.list/find/save/delete/setDefault`, `DmConnectionFactory.open(UUID)`, `ConnectionTestService.test(UUID)`, `JdbcUrlDiagnostics.inspect(String)`.
- `ConnectionProfile` fields: `id`, `name`, `driverJar`, `driverSha256`, `driverClass`, `jdbcUrl`, `username`, `schema`, `connectTimeoutSeconds`, `socketTimeoutSeconds`, `queryTimeoutSeconds`, `maxRows`, `maxBytes`, `isDefault`.

- [ ] **Step 1: Write failing vault and configuration tests**

```java
@Test void vaultEncryptsAndNeverPersistsPlaintext() throws Exception {
    CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
    UUID id = UUID.randomUUID();
    vault.put(id, "中文密码-123");
    assertEquals("中文密码-123", vault.read(id).orElseThrow());
    String raw = Files.readString(tempDir.resolve("secrets/vault.json"), UTF_8);
    assertFalse(raw.contains("中文密码-123"));
}

@Test void oldDm7PathSegmentProducesActionableWarningWithoutMutation() {
    String url = "jdbc:dm7://203.0.113.10:5236/SYSTEM?ignoreCase=true";
    UrlDiagnostic diagnostic = JdbcUrlDiagnostics.inspect(url);
    assertEquals(url, diagnostic.original());
    assertTrue(diagnostic.warnings().stream().anyMatch(v -> v.contains("dbname=SYSTEM")));
    assertTrue(diagnostic.warnings().stream().anyMatch(v -> v.contains("schema=SYSTEM")));
}
```

- [ ] **Step 2: Verify tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=CredentialVaultTest,ConnectionConfigRepositoryTest,DmDriverLoaderTest,JdbcUrlDiagnosticsTest,DmConnectionFactoryTest,ConnectionTestServiceTest test`

Expected: compilation failure for missing connection classes.

- [ ] **Step 3: Implement connection storage and AES-GCM vault**

Use 32 random key bytes in `secrets/master.key`; each value uses a fresh 12-byte IV and AES/GCM/NoPadding with a 128-bit tag. Persist `{connectionId, ivBase64, ciphertextBase64}` atomically through a sibling temporary file and `ATOMIC_MOVE`. Apply `rw-------` POSIX permissions or a Windows ACL granting the current user only read/write.

`ConnectionConfigRepository` writes `config/connections.json` atomically, rejects duplicate names, permits exactly one default, never serializes a password, and preserves an existing password when an edit omits it.

```java
public interface ConnectionConfigStore {
    List<ConnectionProfile> list();
    Optional<ConnectionProfile> find(UUID id);
    ConnectionProfile save(ConnectionProfile profile, Optional<char[]> newPassword);
    void delete(UUID id);
    ConnectionProfile setDefault(UUID id);
}

public interface SecretStore {
    void put(UUID connectionId, char[] secret);
    Optional<char[]> read(UUID connectionId);
    void delete(UUID connectionId);
}
```

- [ ] **Step 4: Implement isolated DM driver loading and connection properties**

`DmDriverLoader` verifies the configured SHA-256, opens a child `URLClassLoader`, loads `dm7.jdbc.driver.Dm7Driver`, instantiates `java.sql.Driver`, and calls `driver.connect(url, properties)` directly so it does not leak into global `DriverManager`. Set `user`, `password`, `connectTimeout`, and `socketTimeout`; execute schema selection only through validated configuration and supported SQL, never `Connection.setSchema()`.

```java
public interface ConnectionFactory {
    ManagedConnection open(UUID profileId) throws SQLException;
}

public record ConnectionTestResult(
    boolean success, long latencyMs, String driverVersion, String serverVersion,
    String actualUser, String actualSchema, boolean chineseRoundTrip, List<String> warnings
) {}

public record ManagedConnection(
    Connection connection,
    AutoCloseable driverHandle,
    String databaseFingerprint
) implements AutoCloseable {
    @Override public void close() throws Exception {
        try { connection.close(); } finally { driverHandle.close(); }
    }
}
```

- [ ] **Step 5: Run tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=CredentialVaultTest,ConnectionConfigRepositoryTest,DmDriverLoaderTest,JdbcUrlDiagnosticsTest,DmConnectionFactoryTest,ConnectionTestServiceTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: manage encrypted DM7 connections"
```

Expected: all connection tests pass, including a fake JDBC driver JAR fixture.

## Task 4: DM-Aware SQL Splitting, Classification, and Literal Rendering

**Files:**
- Create: SQL Java files from Locked File Structure
- Test: `DmSqlParserTest`, `DmLiteralRendererTest`, `SqlSecurityPolicyTest`
- Test fixtures: `server/src/test/resources/fixtures/sql/*.sql`

**Interfaces:**
- Produces: `List<ParsedStatement> DmSqlParser.parse(String)`, `String DmLiteralRenderer.render(Object, int jdbcType)`, `SqlSecurityPolicy.assertNoEmbeddedCredentials(ParsedStatement)`.
- `SqlKind`: `QUERY`, `EXPLAIN`, `DDL`, `DML`, `DCL`, `TRANSACTION`, `SESSION`, `CALL`, `ANONYMOUS_BLOCK`, `UNKNOWN`.
- `SqlPurpose`: `PRODUCTION_CHANGE`, `MIGRATION`, `TEST`, `MOCK`, `SEED`, `SAMPLE` with `isReleaseEligible()`.

- [ ] **Step 1: Write table-driven failing parser tests**

```java
@ParameterizedTest
@CsvSource(delimiter = '|', textBlock = """
SELECT 1|QUERY
WITH q AS (SELECT 1) SELECT * FROM q|QUERY
WITH q AS (SELECT 1) UPDATE T SET C=1|DML
CREATE TABLE T(ID INT)|DDL
MERGE INTO T USING S ON (T.ID=S.ID) WHEN MATCHED THEN UPDATE SET T.C=S.C|DML
GRANT SELECT ON T TO U|DCL
BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; END|ANONYMOUS_BLOCK
""")
void classifiesTopLevelOperation(String sql, SqlKind expected) {
    assertEquals(expected, parser.parse(sql).get(0).kind());
}
```

Add focused tests proving semicolons inside strings/comments/procedure bodies do not split, optimizer hints survive byte-for-byte, and CRLF input normalizes only file line endings.

Add security-policy tests that reject `CREATE USER ... IDENTIFIED BY`, `ALTER USER ... IDENTIFIED BY`, and database-link syntax containing credentials, while allowing comments or ordinary string literals that merely contain the words `IDENTIFIED BY`.

- [ ] **Step 2: Verify parser tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=DmSqlParserTest,DmLiteralRendererTest,SqlSecurityPolicyTest test`

Expected: compilation failure for missing SQL types.

- [ ] **Step 3: Implement a state-machine lexer and classifier**

Use lexer states `NORMAL`, `SINGLE_QUOTE`, `DOUBLE_QUOTE`, `LINE_COMMENT`, `BLOCK_COMMENT`, and `PROCEDURAL_BLOCK`. Track parentheses plus `BEGIN/END` depth. Classification ignores leading comments/hints and, for `WITH`, scans balanced CTE definitions to the final top-level verb. Preserve `originalSql` exactly; calculate normalized SHA-256 separately.

`DmLiteralRenderer` supports null, boolean, integral, decimal, string/N-string, date, time, timestamp, and byte array. It doubles single quotes and emits values such as `HEXTORAW('0A1B')`; unsupported JDBC types throw `UnrenderableParameterException` before release logging.

`SqlSecurityPolicy` consumes lexer tokens with comments and string contents removed, then rejects credential-bearing account/database-link DDL before JDBC execution for every purpose. Error responses name the policy and correlation ID but never echo the secret-bearing SQL.

```java
public record ParsedStatement(int index, String originalSql, SqlKind kind, String sha256) {
    public boolean releaseEligibleKind() {
        return kind == SqlKind.DDL || kind == SqlKind.DML;
    }
}

public List<ParsedStatement> parse(String script) {
    List<String> statements = lexer.splitTopLevelStatements(script);
    List<ParsedStatement> parsed = new ArrayList<>();
    for (int index = 0; index < statements.size(); index++) {
        String sql = statements.get(index);
        parsed.add(new ParsedStatement(index, sql, classifier.classify(sql), sha256(sql)));
    }
    return List.copyOf(parsed);
}
```

- [ ] **Step 4: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=DmSqlParserTest,DmLiteralRendererTest,SqlSecurityPolicyTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: parse and classify DM7 SQL scripts"
```

## Task 5: Release Log Binding, Filtering, Atomic Rotation, and Recovery

**Files:**
- Create: release Java files from Locked File Structure
- Modify: `SessionRepository`, `ExportRepository`
- Test: `ReleaseLogServiceTest`, `ReleaseExportServiceTest`, `SessionFileLockTest`

**Interfaces:**
- Produces: `recordCommitted(SessionState, databaseFingerprint, SqlPurpose, ParsedStatement, renderedSql)`, `ReleaseSnapshot inspect(SessionState)`, `ExportArtifact export(SessionState)`.
- Throws: `ReleaseLogConnectionMismatch`, `ReleaseExportLockTimeout`.

- [ ] **Step 1: Write failing release behavior tests**

```java
@Test void recordsOnlyEligibleCommittedDdlAndDml() throws Exception {
    service.recordCommitted(session, "db-a", MIGRATION, ddl, "CREATE TABLE A(ID INT)");
    service.recordCommitted(session, "db-a", TEST, dml, "INSERT INTO A VALUES (1)");
    service.recordCommitted(session, "db-a", PRODUCTION_CHANGE, query, "SELECT 1");
    String sql = Files.readString(session.activeSql(), UTF_8);
    assertTrue(sql.contains("CREATE TABLE A(ID INT);"));
    assertFalse(sql.contains("INSERT INTO"));
    assertFalse(sql.contains("SELECT 1"));
}

@Test void rejectsTrackedCrossDatabaseWriteBeforeAppend() {
    service.recordCommitted(session, "db-a", MIGRATION, ddl, "CREATE TABLE A(ID INT)");
    assertThrows(ReleaseLogConnectionMismatch.class,
        () -> service.assertWritable(session, "db-b", PRODUCTION_CHANGE));
}
```

Add tests for empty header export, UTF-8 Chinese SQL, concurrent append/export cutoff, Windows-style close-before-rename, final-file SHA-256, v001→v002, and sealed-but-unexported recovery.

- [ ] **Step 2: Verify tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=ReleaseLogServiceTest,ReleaseExportServiceTest,SessionFileLockTest test`

- [ ] **Step 3: Implement release services with channel locks and atomic moves**

Lock `sessions/{sessionId}/active.lock` with `FileChannel.tryLock()` and a bounded retry. On first eligible append, bind the SQLite release version to `databaseFingerprint` and add one binding comment before the first statement. Append the exact rendered SQL, one top-level semicolon, and LF.

Export sequence is: force active channel, close, `ATOMIC_MOVE active.sql -> sealed/vNNN.sql`, write export temporary file, force, `ATOMIC_MOVE` to final, persist metadata, increment version, create a new header-only `active.sql`, release lock. Hash the sealed source in the header and the final artifact separately in SQLite.

```java
public void recordCommitted(SessionState session, String fingerprint, SqlPurpose purpose,
                            ParsedStatement statement, String replayableSql) {
    if (!purpose.isReleaseEligible() || !statement.releaseEligibleKind()) return;
    try (SessionFileLock ignored = locks.acquire(session.sessionId())) {
        repository.bindOrAssertFingerprint(session.sessionId(), session.version(), fingerprint);
        files.appendUtf8Lf(session.activeSql(), ensureSingleTerminator(replayableSql));
        repository.recordReleaseStatement(session.sessionId(), session.version(), statement, replayableSql);
    }
}
```

- [ ] **Step 4: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=ReleaseLogServiceTest,ReleaseExportServiceTest,SessionFileLockTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: rotate session-scoped release SQL logs"
```

## Task 6: Query, Mutation, Transactions, Limits, Progress, and Cancellation

**Files:**
- Create: execution Java files from Locked File Structure
- Modify: `ExecutionRepository`, `DmConnectionFactory`, `ReleaseLogService`
- Test: `ExecutionServiceQueryTest`, `ExecutionServiceMutationTest`, `ExecutionRegistryTest`, `ExecutionEventBusTest`, `MetadataServiceTest`

**Interfaces:**
- Produces records in `ExecutionModels`: `QueryCommand`, `ExecuteCommand`, `ColumnValue`, `QueryResult`, `StatementResult`, `ExecutionResult`, `ExecutionEvent`, `ExecutionStatus`, `ExecutionSource`, `ExecutionFilter`, `ExecutionSummary`, `SafeError`, and generic `Page<T>`.
- Produces: `query(SessionState, QueryCommand)`, `execute(SessionState, ExecuteCommand)`, `cancel(UUID)`, `events(sessionId, afterSequence)`, `MetadataService.describe(UUID, MetadataRequest)`.

- [ ] **Step 1: Write failing transaction and result-limit tests**

```java
@Test void atomicModeRejectsDdlBeforeOpeningConnection() {
    ExecuteCommand command = new ExecuteCommand(profileId, "CREATE TABLE T(ID INT)", MIGRATION, true, false, 60);
    assertThrows(AtomicDdlNotSupported.class, () -> service.execute(session, command));
    assertEquals(0, fakeConnections.openCount());
}

@Test void rollbackDiscardsPendingReleaseEntries() {
    fakeJdbc.failOnStatement(2);
    ExecutionResult result = service.execute(session,
        new ExecuteCommand(profileId, "UPDATE A SET C=1; UPDATE B SET C=2", MIGRATION, true, false, 60));
    assertFalse(result.success());
    assertEquals(0, releaseLog.inspect(session).statementCount());
}

@Test void trackedAnonymousBlockIsRejectedBeforeExecution() {
    ExecuteCommand command = new ExecuteCommand(profileId,
        "BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; END", MIGRATION, false, false, 60);
    assertThrows(UntrackableMutationException.class, () -> service.execute(session, command));
    assertEquals(0, fakeConnections.openCount());
}

@Test void chineseQueryResultsRemainExactAndBounded() {
    fakeJdbc.rows(List.of(Map.of("中文列", "达梦数据库")));
    QueryResult result = service.query(session, new QueryCommand(profileId, "SELECT NAME AS \"中文列\" FROM T", 1000, 10_000_000, 60));
    assertEquals("达梦数据库", result.rows().get(0).get("中文列"));
    assertFalse(result.truncated());
}

@Test void metadataIsPagedAndFallsBackWhenJdbcMetadataIsIncomplete() {
    fakeJdbc.metadataTablesUnsupported();
    SchemaPage page = metadata.describe(profileId, new MetadataRequest("SYSTEM", "T%", 0, 50));
    assertEquals(50, page.limit());
    assertTrue(fakeJdbc.executedSql().stream().anyMatch(sql -> sql.contains("ALL_TABLES")));
}
```

- [ ] **Step 2: Verify tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=ExecutionServiceQueryTest,ExecutionServiceMutationTest,ExecutionRegistryTest,ExecutionEventBusTest,MetadataServiceTest test`

- [ ] **Step 3: Implement the execution pipeline**

Publish events in this exact order when applicable: `QUEUED`, `CONNECTING`, `PARSING`, `EXECUTING`, `COMMITTING`, `LOGGING`, terminal state. Use a bounded `ThreadPoolExecutor`; hold active `Connection` and `Statement` in `ExecutionRegistry`. Query rejects every non-query kind before opening a connection. Mutation rejects query/explain and explicit transaction-control statements, requires purpose, applies `SqlSecurityPolicy`, rejects tracked anonymous/dynamic blocks, calls release-log fingerprint preflight before tracked SQL, and holds release entries pending until commit. Anonymous blocks may run only with `test`, `mock`, `seed`, or `sample`, always untracked and with a visible reason.

Set JDBC query timeout, cap rows at 10,000 and bytes at 50 MiB, and mark truncation. Cancellation calls `Statement.cancel()`, waits two seconds, then closes Statement and Connection. Close ResultSet, Statement, Connection, and driver classloader on every path.

```java
public ExecutionResult execute(SessionState session, ExecuteCommand command) {
    List<ParsedStatement> statements = parser.parse(command.script());
    if (command.atomic() && statements.stream().anyMatch(s -> s.kind() == SqlKind.DDL)) {
        throw new AtomicDdlNotSupported();
    }
    preflightReleaseFingerprint(session, command, statements);
    return command.atomic()
        ? executeAtomicDml(session, command, statements)
        : executeIndependently(session, command, statements);
}


public interface MetadataReader {
    SchemaPage describe(UUID profileId, MetadataRequest request);
}

public record MetadataRequest(String schemaPattern, String objectPattern, int offset, int limit) {}
public record SchemaObject(String schema, String name, String type, List<SchemaColumn> columns) {}
public record SchemaColumn(String name, int jdbcType, String typeName, boolean nullable, int ordinal) {}
public record SchemaPage(List<SchemaObject> items, int offset, int limit, boolean hasMore) {}
```

- [ ] **Step 4: Persist sanitized execution history**

Persist correlation ID, session, connection fingerprint, source (`MCP` or `CONSOLE`), purpose, timestamps, phase, row counts, SQLState/error code, recorded flag, and exclusion reason. Do not store passwords or full JDBC URLs. Store SQL only in the restricted SQLite database.

```java
public interface ExecutionHistory {
    void started(UUID executionId, String sessionId, String connectionFingerprint,
                 ExecutionSource source, Optional<SqlPurpose> purpose, String sql);
    void statementFinished(UUID executionId, StatementResult result);
    void terminal(UUID executionId, ExecutionStatus status, Optional<SafeError> error);
    Page<ExecutionSummary> search(ExecutionFilter filter, int offset, int limit);
}
```

- [ ] **Step 5: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=ExecutionServiceQueryTest,ExecutionServiceMutationTest,ExecutionRegistryTest,ExecutionEventBusTest,MetadataServiceTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: execute DM7 SQL with progress and cancellation"
```

## Task 7: MCP Server and Ten Tool Contracts

**Files:**
- Create: `Dm7McpServer.java`, `Dm7ToolSchemas.java`, `tests/mcp_stdio_smoke.py`
- Modify: `AppMain.java`
- Test: `Dm7McpServerTest`, `tests/mcp_stdio_smoke.py`

**Interfaces:**
- Produces MCP tools: `dm7_open_console`, `dm7_list_connections`, `dm7_test_connection`, `dm7_query`, `dm7_execute`, `dm7_describe_schema`, `dm7_get_execution`, `dm7_cancel_execution`, `dm7_get_release_log`, `dm7_release_export`.
- All handlers call `SessionInitializer.initialize(identity)` before their primary behavior.

- [ ] **Step 1: Write failing schema/annotation tests**

```java
@Test void toolsExposeExactSafetyAnnotations() {
    Map<String, Tool> tools = server.toolDefinitions();
    assertEquals(10, tools.size());
    assertTrue(tools.get("dm7_query").annotations().readOnlyHint());
    assertTrue(tools.get("dm7_execute").annotations().destructiveHint());
    assertFalse(tools.get("dm7_open_console").annotations().readOnlyHint());
    assertTrue(tools.get("dm7_release_export").annotations().destructiveHint());
    assertTrue(tools.get("dm7_execute").inputSchema().required().contains("purpose"));
}
```

- [ ] **Step 2: Verify tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=Dm7McpServerTest test`

- [ ] **Step 3: Build the SDK 2.0.0 STDIO server**

Instantiate `StdioServerTransportProvider(McpJsonDefaults.getMapper())`, then `McpServer.sync(provider).serverInfo("dm7-database", "0.1.0").capabilities(ServerCapabilities.builder().tools(false).build())`. Register each `SyncToolSpecification` with JSON Schema 2020-12, accurate descriptions, annotations, and structured content. Convert expected execution failures to `CallToolResult.isError=true`; reserve JSON-RPC errors for malformed protocol requests.

```java
StdioServerTransportProvider transport =
    new StdioServerTransportProvider(McpJsonDefaults.getMapper());
McpSyncServer server = McpServer.sync(transport)
    .serverInfo("dm7-database", "0.1.0")
    .capabilities(ServerCapabilities.builder().tools(false).build())
    .tools(toolSpecifications())
    .build();
```

- [ ] **Step 4: Add a black-box STDIO smoke client**

`tests/mcp_stdio_smoke.py` starts `java -jar plugins/dm7-database/lib/dm7-codex-plugin.jar --stdio` with temporary `PLUGIN_DATA` and `CODEX_THREAD_ID`, sends `initialize`, `notifications/initialized`, `tools/list`, and `tools/call` for `dm7_get_release_log`, and asserts stdout contains only parseable JSON-RPC and the created `v001 active.sql` exists.

```python
requests = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "dm7-smoke", "version": "1.0.0"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
        "name": "dm7_get_release_log", "arguments": {}}},
]
```

- [ ] **Step 5: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml package
python tests/mcp_stdio_smoke.py
git diff --check
git add plugins/dm7-database/server tests/mcp_stdio_smoke.py
git commit -m "feat: expose DM7 operations through MCP"
```

## Task 8: Secured Loopback HTTP API, Console Tokens, and SSE

**Files:**
- Create: HTTP Java files from Locked File Structure
- Test: `ConsoleTokenServiceTest`, `HttpSecurityTest`, `ConsoleHttpServerTest`, `SseEndpointTest`

**Interfaces:**
- Produces: `ConsoleHttpServer.start()` returning loopback URI, `ConsoleTokenService.issue(sessionId)`, REST/SSE routes consumed by the SPA.
- API prefix is `/api`; all JSON responses are `application/json; charset=utf-8`.

- [ ] **Step 1: Write failing security tests**

```java
@Test void tokenRedeemsOnceThenRedirectsWithoutLeakage() throws Exception {
    String token = tokens.issue("thread-a");
    HttpResponse<Void> first = post("/console/redeem?token=" + token);
    assertEquals(303, first.statusCode());
    assertEquals("/app/", first.headers().firstValue("Location").orElseThrow());
    assertEquals("no-referrer", first.headers().firstValue("Referrer-Policy").orElseThrow());
    assertEquals(401, post("/console/redeem?token=" + token).statusCode());
    assertFalse(logCapture.text().contains(token));
}
```

Also test non-loopback bind rejection, invalid Host/Origin, missing Cookie, CSP headers, path traversal, SSE `Last-Event-ID`, UTF-8 JSON, and query-string redaction.

- [ ] **Step 2: Verify tests fail**

Run: `mvn -f plugins/dm7-database/server/pom.xml -Dtest=ConsoleTokenServiceTest,HttpSecurityTest,ConsoleHttpServerTest,SseEndpointTest test`

- [ ] **Step 3: Implement JDK HttpServer routes**

Use `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)`. Static assets resolve only below classpath `/web/`; unknown `/app/*` routes return `index.html`. Require session Cookie for API routes. Add exact endpoints for runtime/session summary, connection CRUD/test, query, execute, metadata, execution/cancel, history, release preview/export/download, and `/api/events` SSE.

Security headers: `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` for API responses.

```java
server.createContext("/console/redeem", secured(this::redeem));
server.createContext("/api/connections", authenticated(this::connections));
server.createContext("/api/runtime", authenticated(this::runtimeSummary));
server.createContext("/api/query", authenticated(this::query));
server.createContext("/api/execute", authenticated(this::execute));
server.createContext("/api/metadata", authenticated(this::metadata));
server.createContext("/api/executions", authenticated(this::executions));
server.createContext("/api/history", authenticated(this::history));
server.createContext("/api/release", authenticated(this::release));
server.createContext("/api/events", authenticated(this::events));
server.createContext("/app/", secured(this::staticAsset));
```

- [ ] **Step 4: Pass tests and commit**

Run:

```powershell
mvn -f plugins/dm7-database/server/pom.xml -Dtest=ConsoleTokenServiceTest,HttpSecurityTest,ConsoleHttpServerTest,SseEndpointTest test
git diff --check
git add plugins/dm7-database/server
git commit -m "feat: serve the secured DM7 management console"
```

## Task 9: Frontend Foundation and Codex-Compatible Visual System

**Files:**
- Create: frontend entry, app shell, navigation, API types/client, foundational components/styles
- Test: `App.test.tsx`, `Navigation.test.tsx`, `StatusBar.test.tsx`

**Interfaces:**
- Produces TypeScript API records matching Java JSON exactly and routes: `overview`, `sql`, `activity`, `release`, `connections`, `settings`.
- Later UI tasks consume `api`, `useEventStream`, `PageHeader`, `DataTable`, `EmptyState`, `StatusBadge`, `ConfirmDialog`.

- [ ] **Step 1: Write failing shell tests**

```tsx
it('renders all six destinations and the active session status', async () => {
  render(<App api={fakeApi({sessionShortId: '019f4a71', version: 'v001'})} />)
  for (const name of ['概览', 'SQL 控制台', '实时执行', '发版日志', '连接管理', '设置']) {
    expect(screen.getByRole('button', {name})).toBeVisible()
  }
  expect(await screen.findByText('019f4a71')).toBeVisible()
  expect(screen.getByText('v001')).toBeVisible()
})
```

- [ ] **Step 2: Verify tests fail**

Run: `pnpm --dir plugins/dm7-database/web test`

- [ ] **Step 3: Implement visual tokens and app shell**

Define CSS custom properties for light/dark surfaces, `--accent:#147D64`, success/warning/danger, 4/8/12/16/24/32 spacing, 6/10 radius, system UI font, and `ui-monospace` SQL font. Build a fixed 232px sidebar, sticky 52px status bar, responsive content, visible keyboard focus, and no decorative gradients. Persist theme in `localStorage`; navigation uses History API and remains functional after reload under `/app/`. Implement `OverviewPage` with connection/session/version/running-task summary and `SettingsPage` with theme, runtime versions, hard limits, and read-only `PLUGIN_DATA` location; neither page displays secrets.

```css
:root {
  color-scheme: light;
  --bg: #f7f7f5; --surface: #ffffff; --surface-2: #f0f1ee;
  --text: #1f2421; --muted: #66706a; --border: #dfe3df;
  --accent: #147d64; --success: #147d64; --warning: #a96200; --danger: #b42318;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-6: 24px; --space-8: 32px; --radius-sm: 6px; --radius-md: 10px;
}
[data-theme='dark'] {
  color-scheme: dark;
  --bg: #111311; --surface: #181b19; --surface-2: #202421;
  --text: #edf1ed; --muted: #a5aea7; --border: #303630; --accent: #43b894;
}
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
```

- [ ] **Step 4: Pass tests and commit**

Run:

```powershell
pnpm --dir plugins/dm7-database/web check
git diff --check
git add plugins/dm7-database/web
git commit -m "feat: add the DM7 console visual foundation"
```

## Task 10: Connection Management UI

**Files:**
- Create: `pages/ConnectionsPage.tsx`, connection editor/test-result components and tests
- Modify: API types/client and app route table

**Interfaces:**
- Consumes connection REST endpoints from Task 8.
- Produces create/edit/copy/delete/default/test flows; never displays an existing password.

- [ ] **Step 1: Write failing interaction tests**

```tsx
it('saves a default connection without echoing its password', async () => {
  render(<ConnectionsPage api={fakeConnectionsApi()} />)
  await user.click(screen.getByRole('button', {name: '新增连接'}))
  await user.type(screen.getByLabelText('名称'), '测试达梦')
  await user.type(screen.getByLabelText('JDBC URL'), 'jdbc:dm7://127.0.0.1:5236?schema=SYSTEM')
  await user.type(screen.getByLabelText('密码'), 'secret-value')
  await user.click(screen.getByLabelText('设为默认连接'))
  await user.click(screen.getByRole('button', {name: '保存连接'}))
  expect(await screen.findByText('测试达梦')).toBeVisible()
  expect(screen.queryByDisplayValue('secret-value')).toBeNull()
})
```

Add tests for driver file path, preserved password on blank edit, explicit clear, URL path warning, test result Chinese message, default replacement, and destructive delete confirmation.

- [ ] **Step 2: Verify tests fail**

Run: `pnpm --dir plugins/dm7-database/web test -- ConnectionsPage`

- [ ] **Step 3: Implement the page and pass tests**

Use connection cards for scanability and a right-side editor drawer. Put driver/JDBC/user/password/schema first; place timeout/row/byte limits in an expanded “高级设置” section. Display URL diagnostics inline without rewriting input. Test results show latency, driver/server version, actual schema, and a Chinese round-trip badge.

```ts
export type ConnectionProfile = {
  id: string; name: string; driverJar: string; driverSha256: string;
  jdbcUrl: string; username: string; schema: string | null;
  connectTimeoutSeconds: number; socketTimeoutSeconds: number;
  queryTimeoutSeconds: number; maxRows: number; maxBytes: number;
  isDefault: boolean; hasPassword: boolean;
}

export type SaveConnectionRequest = Omit<ConnectionProfile, 'driverSha256' | 'hasPassword'> & {
  password?: string; clearPassword: boolean;
}
```

Run: `pnpm --dir plugins/dm7-database/web test -- ConnectionsPage`

- [ ] **Step 4: Commit**

```powershell
git diff --check
git add plugins/dm7-database/web
git commit -m "feat: add DM7 connection management"
```

## Task 11: SQL Console, Virtualized Results, Live Progress, and Cancellation UI

**Files:**
- Create: SQL console page, CodeMirror wrapper, result grid, execution timeline, event hook, tests
- Modify: API types/client

**Interfaces:**
- Consumes query/execute/cancel/SSE endpoints.
- Produces selected/all execution, required mutation purpose confirmation, result export, and task cancellation.

- [ ] **Step 1: Write failing query and mutation flow tests**

```tsx
it('requires a purpose for mutations and shows Chinese results for queries', async () => {
  const api = fakeSqlApi({columns: [{name: '中文列'}], rows: [{'中文列': '达梦数据库'}]})
  render(<SqlConsolePage api={api} />)
  await setEditorText('UPDATE T SET NAME=\'测试\'')
  await user.click(screen.getByRole('button', {name: '执行全部'}))
  expect(screen.getByRole('dialog', {name: '确认修改操作'})).toBeVisible()
  expect(screen.getByLabelText('用途')).toBeRequired()
  await user.click(screen.getByRole('button', {name: '取消'}))
  await setEditorText('SELECT NAME AS "中文列" FROM T')
  await user.click(screen.getByRole('button', {name: '执行全部'}))
  expect(await screen.findByText('达梦数据库')).toBeVisible()
})
```

- [ ] **Step 2: Verify tests fail**

Run: `pnpm --dir plugins/dm7-database/web test -- SqlConsolePage`

- [ ] **Step 3: Implement editor, controls, and event timeline**

Configure CodeMirror SQL language, line numbers, selection execution, `Ctrl+Enter`, and theme synchronization. Query runs immediately; mutation opens confirmation with required purpose and atomic/continue controls. Timeline renders the exact backend phases and elapsed durations. Cancellation remains enabled only for active tasks.

```ts
const runKeymap = keymap.of([{
  key: 'Ctrl-Enter',
  mac: 'Cmd-Enter',
  run(view) {
    const selection = view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)
    onRun(selection.length > 0 ? selection : view.state.doc.toString())
    return true
  },
}])
```

- [ ] **Step 4: Implement virtualized result grid and UTF-8 downloads**

Use TanStack Table plus Virtual for row virtualization. Add column resizing, sorting, copy cell/row, and CSV/JSON downloads. Build Blob from a Unicode string, prepend UTF-8 BOM only for CSV compatibility, use no BOM for JSON, and surface backend truncation.

```ts
export function downloadCsv(filename: string, csv: string) {
  downloadBlob(filename, new Blob(['\uFEFF', csv], {type: 'text/csv;charset=utf-8'}))
}
export function downloadJson(filename: string, value: unknown) {
  downloadBlob(filename, new Blob([JSON.stringify(value, null, 2)], {type: 'application/json;charset=utf-8'}))
}
```

- [ ] **Step 5: Pass tests and commit**

```powershell
pnpm --dir plugins/dm7-database/web test -- SqlConsolePage ResultGrid ExecutionTimeline
pnpm --dir plugins/dm7-database/web check
git diff --check
git add plugins/dm7-database/web
git commit -m "feat: add live SQL execution and results"
```

## Task 12: Activity History and Release Export UI

**Files:**
- Create: `ActivityPage.tsx`, `ReleasePage.tsx`, filters, preview, export dialog, tests
- Modify: overview widgets and API client/types

**Interfaces:**
- Consumes history/release/export/download endpoints.
- Produces execution filtering, recorded/excluded reasons, version preview, explicit export confirmation, immutable artifact history.

- [ ] **Step 1: Write failing release rotation test**

```tsx
it('exports v001 and switches to empty v002', async () => {
  const api = fakeReleaseApi({currentVersion: 'v001', statementCount: 2})
  render(<ReleasePage api={api} />)
  await user.click(screen.getByRole('button', {name: '发版并导出'}))
  expect(screen.getByText('v001')).toBeVisible()
  expect(screen.getByText('2 条语句')).toBeVisible()
  await user.click(screen.getByRole('button', {name: '确认发版'}))
  expect(await screen.findByText('v002')).toBeVisible()
  expect(screen.getByText('当前版本暂无 SQL')).toBeVisible()
  expect(screen.getByRole('link', {name: /dm7-.*-v001-.*\.sql/})).toBeVisible()
})
```

- [ ] **Step 2: Verify tests fail**

Run: `pnpm --dir plugins/dm7-database/web test -- ActivityPage ReleasePage`

- [ ] **Step 3: Implement pages and pass tests**

Activity starts with a live SSE-backed running-task section, including phase, elapsed time, source, SQL summary, and cancel action; below it, history filters by source, kind, purpose, success, recorded state, and correlation ID. Release page shows database binding, version, eligible/excluded/failed totals, SQL preview with line numbers, mismatch warnings, export artifact SHA-256, and recovery actions for sealed incomplete exports.

```ts
export type ReleaseSnapshot = {
  sessionShortId: string; currentVersion: string; databaseFingerprint: string | null;
  statementCount: number; excludedCount: number; failedCount: number;
  sqlPreview: string; recoverableExports: ExportArtifact[]; exports: ExportArtifact[];
}

export type ExportArtifact = {
  id: string; version: string; filename: string; byteLength: number;
  sha256: string; downloadUrl: string; createdAt: string;
}
```

Run: `pnpm --dir plugins/dm7-database/web test -- ActivityPage ReleasePage`

- [ ] **Step 4: Commit**

```powershell
git diff --check
git add plugins/dm7-database/web
git commit -m "feat: add execution history and release export"
```

## Task 13: Browser QA, Accessibility, Responsive Polish, and Plugin Assets

**Files:**
- Create: Playwright fixtures/tests; update CSS/components
- Create: SVG assets and PNG screenshots
- Test: `web/e2e/console.spec.ts`, `connections.spec.ts`, `release.spec.ts`, `accessibility.spec.ts`

**Interfaces:**
- Consumes complete HTTP/UI feature set.
- Produces verified light/dark 1280×800 and 1440×900 UI plus manifest screenshots.

- [ ] **Step 1: Write failing Playwright journeys**

```ts
test('manual SQL journey remains usable at 1280x800', async ({page}) => {
  await page.setViewportSize({width: 1280, height: 800})
  await page.goto('/app/sql')
  await page.getByTestId('sql-editor').fill('SELECT \'达梦数据库\' AS "中文列"')
  await page.getByRole('button', {name: '执行全部'}).click()
  await expect(page.getByText('达梦数据库')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
```

Add keyboard-only navigation, visible focus, dark theme, long SQL, long Chinese, loading/empty/error states, connection save/test, activity cancellation, and release export journeys.

- [ ] **Step 2: Run E2E and capture failures**

Run: `pnpm --dir plugins/dm7-database/web e2e`

Expected: tests fail until the packaged server fixture and polish are complete.

- [ ] **Step 3: Fix visual and accessibility defects, then capture approved screenshots**

Use Playwright screenshots at 1440×900 for SQL console and release page. Store exact files as `assets/screenshot-console.png` and `assets/screenshot-release.png`. Keep SVG brand assets geometric, database-oriented, readable at 24px, and free of text at icon size.

```css
@media (max-width: 1320px) {
  .app-sidebar { width: 208px; }
  .page-content { padding: var(--space-4); }
}
.result-grid, .sql-editor, .timeline { min-width: 0; overflow: auto; }
.status-badge::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
```

- [ ] **Step 4: Pass UI suite and commit**

```powershell
pnpm --dir plugins/dm7-database/web check
pnpm --dir plugins/dm7-database/web e2e
git diff --check
git add plugins/dm7-database/web plugins/dm7-database/assets plugins/dm7-database/.codex-plugin/plugin.json
git commit -m "style: polish and verify the DM7 console"
```

## Task 14: Build, Package, Plugin Skill, Hooks, and Documentation

**Files:**
- Create/modify: build/test/package scripts, plugin skill, hook, all documentation/legal files
- Test: plugin validator, zip inspection, fresh-path launch

**Interfaces:**
- Produces: `dist/dm7-database-0.1.0.zip`, repo marketplace install source, trusted SessionStart evidence, user/install/troubleshooting docs.

- [ ] **Step 1: Write build/package smoke assertions**

`package.ps1` must fail unless frontend tests, backend tests, plugin validation, and JAR build pass. It stages only runtime files, source notices, docs, and screenshots; it rejects files matching `Dm*Jdbc*.jar`, `*.env*`, `vault.json`, or `master.key`. For every non-empty integration environment value, it scans the staged plugin bytes and fails if the exact URL, username, password, or driver path appears.

Build the runtime JAR twice from clean `target` and `web/dist` directories with the same `SOURCE_DATE_EPOCH`; assert both SHA-256 values are identical before packaging.

```powershell
$forbiddenFiles = Get-ChildItem -LiteralPath $stage -Recurse -File | Where-Object {
  $_.Name -like 'Dm*Jdbc*.jar' -or $_.Name -like '*.env*' -or
  $_.Name -in @('vault.json', 'master.key')
}
if ($forbiddenFiles) { throw "Package contains forbidden files: $($forbiddenFiles.FullName -join ', ')" }
$forbiddenValues = @($env:DM7_IT_JDBC_URL, $env:DM7_IT_USERNAME, $env:DM7_IT_PASSWORD, $env:DM7_IT_DRIVER_JAR) |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
foreach ($value in $forbiddenValues) {
  if (Get-ChildItem -LiteralPath $stage -Recurse -File | Select-String -SimpleMatch -Pattern $value -Quiet) {
    throw 'Package contains an integration environment value'
  }
}
```

- [ ] **Step 2: Implement scripts and plugin guidance**

`build.ps1` uses bundled pnpm when available, builds web, then Maven with JDK 21. `test.ps1` runs Python layout/MCP smoke, Maven verify, Vitest, and Playwright. `package.ps1` creates a deterministic zip.

The skill instructs Codex to prefer `dm7_query` for reads, require explicit purpose for mutations, never request passwords in chat, call `dm7_open_console` for configuration/manual use, and confirm `dm7_release_export`.

The SessionStart hook writes only a SHA-256 of `session_id`, timestamp, and process environment thread hash under `PLUGIN_DATA/session-context`; it does not create the release log before the first plugin call.

```json
{
  "hooks": {
    "SessionStart": [{
      "hooks": [{
        "type": "command",
        "command": "powershell -NoProfile -ExecutionPolicy Bypass -File ${PLUGIN_ROOT}/hooks/session-context.ps1",
        "statusMessage": "Preparing DM7 session context"
      }]
    }]
  }
}
```

- [ ] **Step 3: Write complete user and maintainer docs**

Document Java 17+, driver licensing/BYO-driver, install from marketplace, connection setup, URL path warning, tools, approvals, manual SQL, log filtering, versions, export, UTF-8, limits, data directories, sensitive release SQL, backup, troubleshooting, development, testing, and packaging. Include no live password.

```markdown
## Quick start
1. Install Java 17 or newer.
2. Install `dm7-database` from the configured marketplace and start a new Codex task.
3. Ask Codex to “打开达梦数据库管理控制台”.
4. Add the JDBC driver path and connection details in the local console, then select “测试连接”.
5. Keep passwords in the console; never paste them into a Codex prompt.
```

- [ ] **Step 4: Validate package and commit**

```powershell
& plugins/dm7-database/scripts/test.ps1
& plugins/dm7-database/scripts/package.ps1
python "$env:USERPROFILE\.codex\skills\.system\plugin-creator\scripts\validate_plugin.py" "plugins\dm7-database"
git diff --check
git add .agents README.md plugins/dm7-database
git add -f plugins/dm7-database/lib/dm7-codex-plugin.jar
git commit -m "docs: package and document the DM7 plugin"
```

Expected: clean validation and `dist/dm7-database-0.1.0.zip` containing no driver or secret.

## Task 15: Real Dameng 7 Integration and Sanitized Acceptance Report

**Files:**
- Create: `server/src/test/java/io/dm7codex/plugin/integration/Dm7IntegrationTest.java`
- Create: `artifacts/acceptance/dm7-integration-summary.json`
- Modify: test script to enable integration profile only when four environment variables exist

**Interfaces:**
- Consumes: user-supplied DM7 URL/username/password/driver through environment.
- Produces: sanitized evidence with driver SHA, server/driver version, connection fingerprint, case results, Chinese assertions, cleanup confirmation.

- [ ] **Step 1: Add integration tests guarded by environment assumptions**

```java
@Test void chineseRoundTripMetadataAndCleanup() throws Exception {
    Assumptions.assumeTrue(requiredEnvironmentPresent());
    String table = "CODEX_DM7_IT_" + randomAsciiSuffix();
    try {
        executeTestPurpose("CREATE TABLE " + table + " (ID INT, NAME VARCHAR(100))");
        executeTestPurpose("INSERT INTO " + table + " VALUES (1, '中文验证：达梦数据库')");
        QueryResult result = query("SELECT NAME AS \"中文列名\" FROM " + table + " WHERE ID=1");
        assertEquals("中文验证：达梦数据库", result.rows().get(0).get("中文列名"));
        assertTrue(metadata().tableNames().contains(table));
        assertEquals(0, releaseLog().statementCount());
    } finally {
        dropQuietly(table);
        assertFalse(tableExists(table));
    }
}
```

Also test original URL connection/diagnostic, update/delete counts, query limits, connection/socket timeout configuration, `atomic=true` DDL preflight, non-atomic mixed DDL/failure reporting, and cancellation where the driver/server provides a safe cancellable query.

- [ ] **Step 2: Inject secrets only for the test process and run**

```powershell
$env:DM7_IT_JDBC_URL=Read-Host '输入本次达梦集成测试 JDBC URL'
$env:DM7_IT_USERNAME=Read-Host '输入本次达梦集成测试用户名'
$dm7Credential = Get-Credential -UserName $env:DM7_IT_USERNAME -Message '输入本次达梦集成测试密码；密码不会写入文件'
$env:DM7_IT_PASSWORD=$dm7Credential.GetNetworkCredential().Password
$env:DM7_IT_DRIVER_JAR=Read-Host '输入本次达梦集成测试驱动 JAR 路径'
$forbiddenValues = @($env:DM7_IT_JDBC_URL, $env:DM7_IT_USERNAME, $env:DM7_IT_PASSWORD, $env:DM7_IT_DRIVER_JAR)
try {
  mvn -f plugins/dm7-database/server/pom.xml -Pintegration verify
  $scanRoots = @('artifacts', 'plugins', 'dist') | Where-Object { Test-Path $_ }
  foreach ($value in $forbiddenValues) {
    if (Get-ChildItem -LiteralPath $scanRoots -Recurse -File | Select-String -SimpleMatch -Pattern $value -Quiet) {
      throw 'An integration environment value was persisted to an artifact'
    }
  }
} finally {
  'DM7_IT_JDBC_URL','DM7_IT_USERNAME','DM7_IT_PASSWORD','DM7_IT_DRIVER_JAR' |
    ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
}
```

Expected: integration tests pass; random object is absent afterward; no release SQL contains test DDL/DML.

- [ ] **Step 3: Generate and inspect sanitized evidence**

The JSON report includes only hashes, versions, timings, and booleans. Validate it against this schema after the exact-value scan in Step 2 reports no matches:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["passed", "driverSha256", "driverVersion", "serverVersion", "targetFingerprint", "cases", "cleanupConfirmed"],
  "properties": {
    "passed": {"type": "boolean"},
    "driverSha256": {"type": "string", "pattern": "^[A-F0-9]{64}$"},
    "driverVersion": {"type": "string"},
    "serverVersion": {"type": "string"},
    "targetFingerprint": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
    "cases": {"type": "array", "items": {"type": "object", "required": ["name", "passed", "durationMs"]}},
    "cleanupConfirmed": {"const": true}
  },
  "additionalProperties": false
}
```

- [ ] **Step 4: Commit sanitized evidence**

```powershell
git diff --check
git add plugins/dm7-database/server artifacts/acceptance/dm7-integration-summary.json
git commit -m "test: verify DM7 integration and Chinese round trips"
```

## Task 16: Install in Codex and Prove Two-Task Session Isolation

**Files:**
- Create: `artifacts/acceptance/codex-session-isolation.json`
- Modify only if verification reveals host-lifecycle incompatibility: MCP launcher/session resolution and tests

**Interfaces:**
- Consumes: built plugin and repo marketplace.
- Produces: authoritative evidence that two real new Codex tasks discover tools, create independent v001 logs, and rotate independently.

- [ ] **Step 1: Validate and install the repo marketplace plugin**

Run:

```powershell
python "$env:USERPROFILE\.codex\skills\.system\plugin-creator\scripts\validate_plugin.py" "plugins\dm7-database"
$Repo = (Resolve-Path '.').Path
codex plugin marketplace add $Repo
codex plugin add dm7-database@dm7-database-local
```

If the desktop-packaged `codex.exe` remains inaccessible from the terminal, install through the Codex plugin UI using the repo marketplace and record that exact route in the evidence artifact; do not treat a raw MCP smoke test as a substitute.

- [ ] **Step 2: Start two real new Codex tasks and call `dm7_get_release_log` first**

For Task A and Task B, capture only task/thread hash, MCP process ID, release directory hash, version, and active file SHA. Assert both are `v001` and directories differ.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["taskA", "taskB", "directoriesDiffer"],
  "properties": {
    "taskA": {"$ref": "#/$defs/taskEvidence"},
    "taskB": {"$ref": "#/$defs/taskEvidence"},
    "directoriesDiffer": {"const": true}
  },
  "$defs": {
    "taskEvidence": {
      "type": "object",
      "required": ["threadHash", "processId", "releaseDirectoryHash", "version", "activeFileSha256"],
      "properties": {
        "threadHash": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "processId": {"type": "integer", "minimum": 1},
        "releaseDirectoryHash": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "version": {"const": "v001"},
        "activeFileSha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"}
      },
      "additionalProperties": false
    }
  },
  "additionalProperties": false
}
```

- [ ] **Step 3: Rotate Task A and prove Task B is unchanged**

Call `dm7_release_export` in Task A with explicit confirmation. Assert Task A is `v002`, Task B remains `v001`, Task A export exists, and neither task can access the other's release preview.

- [ ] **Step 4: Run the full completion audit**

```powershell
& plugins/dm7-database/scripts/test.ps1
& plugins/dm7-database/scripts/package.ps1
git status --short
git log --oneline --decorate -20
```

Check every item in design specification section 17 against a file, command result, screenshot, runtime call, or acceptance JSON. Any missing evidence means the goal remains incomplete.

- [ ] **Step 5: Commit final evidence**

```powershell
git diff --check
git add artifacts/acceptance/codex-session-isolation.json
git commit -m "test: prove Codex session isolation"
```

Expected: clean worktree, all automated tests pass, real DM7 evidence passes, plugin validates, package contains no secrets/driver, and two-task isolation is proven.

## Plan Self-Review Mapping

- Plugin/tool capability: Tasks 1, 7, 14, 16.
- Management/default connection: Tasks 3, 8, 10.
- Live execution/manual SQL/results: Tasks 6, 8, 11, 12.
- Chinese correctness: Tasks 3, 6, 11, 15.
- First-call per-session log: Tasks 2, 7, 16.
- DDL/DML-only filtering and fixture exclusion: Tasks 4, 5, 6, 15.
- Atomic release rotation/export/no idempotency: Tasks 5, 12, 16.
- Shareability and polished UI: Tasks 1, 9–14.
- Real DM7 and completion evidence: Tasks 15–16.
