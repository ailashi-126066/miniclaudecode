package dev.miniclaudecode.persistence.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Workspace-isolated ACE memory backed by one user-local SQLite database and FTS5. */
public final class SqliteMemoryStore implements MemoryStore {
  private static final int SCHEMA_VERSION = 3;
  private static final String USER_SCOPE_ID = "__user__";
  private final Path database;
  private final String workspaceId;
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> warnings = new ArrayList<>();
  private final Set<String> knownSecrets;
  private final int consolidationThreshold;
  private final ExecutorService consolidationExecutor =
      Executors.newSingleThreadExecutor(
          task -> Thread.ofPlatform().daemon().name("memory-consolidation").unstarted(task));
  private final AtomicBoolean consolidationQueued = new AtomicBoolean();

  public SqliteMemoryStore(Path database, String workspaceId, Path legacyJsonl) {
    this(database, workspaceId, legacyJsonl, Set.of());
  }

  public SqliteMemoryStore(
      Path database, String workspaceId, Path legacyJsonl, Set<String> knownSecrets) {
    this(database, workspaceId, legacyJsonl, knownSecrets, 10);
  }

  public SqliteMemoryStore(
      Path database,
      String workspaceId,
      Path legacyJsonl,
      Set<String> knownSecrets,
      int consolidationThreshold) {
    this.database =
        Objects.requireNonNull(database, "database must not be null").toAbsolutePath().normalize();
    this.workspaceId = requireText(workspaceId, "workspaceId");
    this.knownSecrets =
        Set.copyOf(Objects.requireNonNull(knownSecrets, "knownSecrets must not be null"));
    if (consolidationThreshold < 2) {
      throw new IllegalArgumentException("consolidationThreshold must be at least 2");
    }
    this.consolidationThreshold = consolidationThreshold;
    initialize();
    importLegacy(legacyJsonl);
  }

  @Override
  public synchronized AceBullet curate(AceBullet candidate) {
    return upsert(
        Objects.requireNonNull(candidate, "candidate must not be null"), AceBullet.State.ACTIVE);
  }

  @Override
  public synchronized Optional<MemoryRecord> remember(MemoryCandidate candidate) {
    Optional<MemoryCandidate> accepted = MemoryWriteGate.accept(candidate);
    if (accepted.isEmpty()) {
      return Optional.empty();
    }
    MemoryCandidate value = accepted.orElseThrow();
    validateSafeText(value.content() + "\n" + String.join("\n", value.evidence()));
    String storageScope = storageScope(value.scope());
    Instant now = Instant.now();
    try (Connection connection = open()) {
      connection.setAutoCommit(false);
      StoredMemory existing = findActiveByKey(connection, storageScope, value.normalizedKey());
      boolean duplicate = existing != null && existing.content().equals(value.content());
      String id =
          existing == null || duplicate
              ? (existing == null ? value.normalizedKey() : existing.id())
              : value.normalizedKey()
                  + ":"
                  + UUID.nameUUIDFromBytes(value.content().getBytes(StandardCharsets.UTF_8));
      if (existing != null && !duplicate) {
        supersede(connection, storageScope, existing.id(), id, now);
      }
      int occurrences = duplicate ? existing.occurrences() + 1 : 1;
      Instant createdAt = duplicate ? existing.createdAt() : now;
      List<String> evidence =
          duplicate
              ? java.util.stream.Stream.concat(
                      existing.evidence().stream(), value.evidence().stream())
                  .filter(text -> text != null && !text.isBlank())
                  .distinct()
                  .limit(8)
                  .toList()
              : value.evidence();
      writeRecord(connection, storageScope, id, value, evidence, occurrences, createdAt, now);
      connection.commit();
      scheduleConsolidationIfNeeded();
      return Optional.of(
          new MemoryRecord(
              id,
              value,
              MemoryState.ACTIVE,
              createdAt,
              now,
              occurrences,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    } catch (SQLException error) {
      throw failure("remember memory", error);
    }
  }

  @Override
  public synchronized List<AceBullet> search(String query, int limit) {
    String text = Objects.requireNonNullElse(query, "").strip();
    if (text.isEmpty()) {
      return List.of();
    }
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("limit must be between 1 and 20");
    }
    if (text.codePointCount(0, text.length()) < 3) {
      return likeSearch(text, limit);
    }
    try (Connection connection = open();
        PreparedStatement queryStatement =
            connection.prepareStatement(
                """
                SELECT m.*, bm25(memory_fts) AS relevance
                FROM memory_fts
                JOIN memory_entry m ON m.row_id = memory_fts.rowid
                WHERE memory_fts MATCH ?
                  AND (m.workspace_id=? OR (m.workspace_id=? AND m.scope='USER'))
                  AND m.state='ACTIVE'
                ORDER BY relevance ASC
                LIMIT ?
                """)) {
      queryStatement.setString(1, quoteFts(text));
      queryStatement.setString(2, workspaceId);
      queryStatement.setString(3, USER_SCOPE_ID);
      queryStatement.setInt(4, 20);
      List<Scored> candidates = new ArrayList<>();
      try (ResultSet rows = queryStatement.executeQuery()) {
        while (rows.next()) {
          candidates.add(new Scored(decode(rows), rows.getDouble("relevance")));
        }
      }
      return candidates.stream()
          .sorted(
              Comparator.comparingDouble(Scored::relevance)
                  .thenComparing(value -> value.bullet().confidence(), Comparator.reverseOrder())
                  .thenComparing(value -> value.bullet().occurrences(), Comparator.reverseOrder())
                  .thenComparing(value -> value.bullet().updatedAt(), Comparator.reverseOrder()))
          .limit(limit)
          .map(Scored::bullet)
          .toList();
    } catch (SQLException error) {
      warnings.add("FTS5 search degraded to LIKE: " + safeMessage(error));
      return likeSearch(text, limit);
    }
  }

  @Override
  public synchronized boolean archive(String id) {
    try (Connection connection = open();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE memory_entry SET state='ARCHIVED', updated_at=? WHERE workspace_id IN (?,?) AND memory_id=? AND state<>'ARCHIVED'")) {
      update.setString(1, Instant.now().toString());
      update.setString(2, workspaceId);
      update.setString(3, USER_SCOPE_ID);
      update.setString(4, requireText(id, "id"));
      return update.executeUpdate() > 0;
    } catch (SQLException error) {
      throw failure("archive memory", error);
    }
  }

  @Override
  public synchronized boolean edit(String id, String content) {
    String memoryId = requireText(id, "id");
    String lesson = requireText(content, "content");
    validateSafeText(lesson);
    try (Connection connection = open();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE memory_entry SET lesson=?, updated_at=? WHERE workspace_id IN (?,?) AND memory_id=?")) {
      update.setString(1, lesson);
      update.setString(2, Instant.now().toString());
      update.setString(3, workspaceId);
      update.setString(4, USER_SCOPE_ID);
      update.setString(5, memoryId);
      return update.executeUpdate() > 0;
    } catch (SQLException error) {
      throw failure("edit memory", error);
    }
  }

  @Override
  public synchronized int clear() {
    try (Connection connection = open();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE memory_entry SET state='ARCHIVED', updated_at=? WHERE workspace_id IN (?,?) AND state='ACTIVE'")) {
      update.setString(1, Instant.now().toString());
      update.setString(2, workspaceId);
      update.setString(3, USER_SCOPE_ID);
      return update.executeUpdate();
    } catch (SQLException error) {
      throw failure("clear memories", error);
    }
  }

  @Override
  public synchronized boolean supersede(String id, String supersededBy) {
    requireText(supersededBy, "supersededBy");
    try (Connection connection = open();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE memory_entry SET state='SUPERSEDED', superseded_by=?, updated_at=? WHERE workspace_id IN (?,?) AND memory_id=? AND state='ACTIVE'")) {
      update.setString(1, requireText(supersededBy, "supersededBy"));
      update.setString(2, Instant.now().toString());
      update.setString(3, workspaceId);
      update.setString(4, USER_SCOPE_ID);
      update.setString(5, requireText(id, "id"));
      return update.executeUpdate() > 0;
    } catch (SQLException error) {
      throw failure("supersede memory", error);
    }
  }

  @Override
  public synchronized List<AceBullet> list() {
    try (Connection connection = open();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT * FROM memory_entry WHERE workspace_id=? OR (workspace_id=? AND scope='USER') ORDER BY updated_at DESC")) {
      query.setString(1, workspaceId);
      query.setString(2, USER_SCOPE_ID);
      return rows(query);
    } catch (SQLException error) {
      throw failure("list memories", error);
    }
  }

  @Override
  public synchronized List<String> warnings() {
    return List.copyOf(warnings);
  }

  @Override
  public void close() {
    consolidationExecutor.shutdown();
    try {
      if (!consolidationExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        consolidationExecutor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      consolidationExecutor.shutdownNow();
    }
  }

  private synchronized void scheduleConsolidationIfNeeded() {
    long active =
        list().stream().filter(memory -> memory.state() == AceBullet.State.ACTIVE).count();
    if (active < consolidationThreshold
        || active % consolidationThreshold != 0
        || !consolidationQueued.compareAndSet(false, true)) {
      return;
    }
    consolidationExecutor.execute(
        () -> {
          try {
            consolidateExactDuplicates();
          } finally {
            consolidationQueued.set(false);
          }
        });
  }

  @Override
  public void requestConsolidation() {
    queueConsolidation();
  }

  private void queueConsolidation() {
    if (!consolidationQueued.compareAndSet(false, true)) {
      return;
    }
    consolidationExecutor.execute(
        () -> {
          try {
            consolidateExactDuplicates();
          } finally {
            consolidationQueued.set(false);
          }
        });
  }

  private synchronized void consolidateExactDuplicates() {
    java.util.Map<String, List<AceBullet>> groups =
        list().stream()
            .filter(memory -> memory.state() == AceBullet.State.ACTIVE)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    memory ->
                        (memory.trigger() + "\n" + memory.lesson())
                            .toLowerCase(java.util.Locale.ROOT)));
    for (List<AceBullet> duplicates : groups.values()) {
      if (duplicates.size() < 2) {
        continue;
      }
      AceBullet keep = duplicates.getFirst();
      duplicates.stream().skip(1).forEach(memory -> supersede(memory.id(), keep.id()));
    }
  }

  private AceBullet upsert(AceBullet candidate, AceBullet.State targetState) {
    validateSafe(candidate);
    try (Connection connection = open()) {
      connection.setAutoCommit(false);
      AceBullet existing = find(connection, candidate.id());
      AceBullet stored =
          existing == null
              ? withState(candidate, targetState)
              : withState(existing.merge(candidate), targetState);
      write(connection, stored);
      connection.commit();
      return stored;
    } catch (SQLException error) {
      throw failure("save memory", error);
    }
  }

  private void validateSafe(AceBullet candidate) {
    String content =
        candidate.trigger()
            + "\n"
            + candidate.lesson()
            + "\n"
            + String.join("\n", candidate.evidence());
    validateSafeText(content);
  }

  private void validateSafeText(String content) {
    if (knownSecrets.stream().filter(secret -> !secret.isBlank()).anyMatch(content::contains)
        || content.matches(
            "(?is).*(-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}|\\bsk-[A-Za-z0-9_-]{12,}|\\bAKIA[0-9A-Z]{16}\\b).*")) {
      throw new IllegalArgumentException("memory candidate contains secret-like content");
    }
  }

  private void write(Connection connection, AceBullet bullet) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_entry(
              workspace_id,memory_id,trigger,lesson,evidence_json,evidence_text,
              applicable_paths_json,paths_text,state,confidence,occurrences,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(workspace_id,memory_id) DO UPDATE SET
              trigger=excluded.trigger, lesson=excluded.lesson,
              evidence_json=excluded.evidence_json, evidence_text=excluded.evidence_text,
              applicable_paths_json=excluded.applicable_paths_json, paths_text=excluded.paths_text,
              state=excluded.state, confidence=excluded.confidence,
              occurrences=excluded.occurrences, updated_at=excluded.updated_at
            """)) {
      statement.setString(1, workspaceId);
      statement.setString(2, bullet.id());
      statement.setString(3, bullet.trigger());
      statement.setString(4, bullet.lesson());
      statement.setString(5, json(bullet.evidence()));
      statement.setString(6, String.join(" ", bullet.evidence()));
      statement.setString(7, json(bullet.applicablePaths()));
      statement.setString(8, String.join(" ", bullet.applicablePaths()));
      statement.setString(9, bullet.state().name());
      statement.setDouble(10, bullet.confidence());
      statement.setInt(11, bullet.occurrences());
      statement.setString(12, bullet.createdAt().toString());
      statement.setString(13, bullet.updatedAt().toString());
      statement.executeUpdate();
    }
  }

  private void writeRecord(
      Connection connection,
      String storageScope,
      String id,
      MemoryCandidate candidate,
      List<String> evidence,
      int occurrences,
      Instant createdAt,
      Instant updatedAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO memory_entry(
              workspace_id,memory_id,trigger,lesson,evidence_json,evidence_text,
              applicable_paths_json,paths_text,state,confidence,occurrences,created_at,updated_at,
              type,scope,normalized_key,authority,durability,source_session_id,source_turn_id,
              last_used_at,consolidated_at,superseded_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(workspace_id,memory_id) DO UPDATE SET
              trigger=excluded.trigger, lesson=excluded.lesson,
              evidence_json=excluded.evidence_json, evidence_text=excluded.evidence_text,
              state='ACTIVE', confidence=excluded.confidence,
              occurrences=excluded.occurrences, updated_at=excluded.updated_at,
              type=excluded.type, scope=excluded.scope, normalized_key=excluded.normalized_key,
              authority=excluded.authority, durability=excluded.durability,
              source_session_id=excluded.source_session_id, source_turn_id=excluded.source_turn_id,
              superseded_by=NULL
            """)) {
      statement.setString(1, storageScope);
      statement.setString(2, id);
      statement.setString(3, candidate.type().name());
      statement.setString(4, candidate.content());
      statement.setString(5, json(evidence));
      statement.setString(6, String.join(" ", evidence));
      statement.setString(7, "[]");
      statement.setString(8, "");
      statement.setString(9, MemoryState.ACTIVE.name());
      statement.setDouble(10, candidate.authority() == MemoryAuthority.USER_STATED ? 0.95 : 0.85);
      statement.setInt(11, occurrences);
      statement.setString(12, createdAt.toString());
      statement.setString(13, updatedAt.toString());
      statement.setString(14, candidate.type().name());
      statement.setString(15, candidate.scope().name());
      statement.setString(16, candidate.normalizedKey());
      statement.setString(17, candidate.authority().name());
      statement.setString(18, candidate.durability().name());
      statement.setString(19, candidate.sourceSessionId());
      statement.setString(20, candidate.sourceTurnId());
      statement.setString(21, null);
      statement.setString(22, null);
      statement.setString(23, null);
      statement.executeUpdate();
    }
  }

  private StoredMemory findActiveByKey(
      Connection connection, String storageScope, String normalizedKey) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            """
            SELECT memory_id,lesson,evidence_json,occurrences,created_at
            FROM memory_entry
            WHERE workspace_id=? AND normalized_key=? AND state='ACTIVE'
            ORDER BY updated_at DESC LIMIT 1
            """)) {
      query.setString(1, storageScope);
      query.setString(2, normalizedKey);
      try (ResultSet row = query.executeQuery()) {
        return row.next()
            ? new StoredMemory(
                row.getString("memory_id"),
                row.getString("lesson"),
                strings(row.getString("evidence_json")),
                row.getInt("occurrences"),
                Instant.parse(row.getString("created_at")))
            : null;
      }
    }
  }

  private void supersede(
      Connection connection, String storageScope, String id, String supersededBy, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE memory_entry SET state='SUPERSEDED',superseded_by=?,updated_at=? WHERE workspace_id=? AND memory_id=? AND state='ACTIVE'")) {
      update.setString(1, supersededBy);
      update.setString(2, now.toString());
      update.setString(3, storageScope);
      update.setString(4, id);
      update.executeUpdate();
    }
  }

  private List<AceBullet> byState(AceBullet.State state) {
    try (Connection connection = open();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT * FROM memory_entry WHERE workspace_id=? AND state=? ORDER BY updated_at DESC")) {
      query.setString(1, workspaceId);
      query.setString(2, state.name());
      return rows(query);
    } catch (SQLException error) {
      throw failure("list memories by state", error);
    }
  }

  private List<AceBullet> likeSearch(String text, int limit) {
    String pattern = "%" + escapeLike(text.toLowerCase(java.util.Locale.ROOT)) + "%";
    try (Connection connection = open();
        PreparedStatement query =
            connection.prepareStatement(
                """
                SELECT * FROM memory_entry
                WHERE (workspace_id=? OR (workspace_id=? AND scope='USER')) AND state='ACTIVE'
                  AND lower(trigger || ' ' || lesson || ' ' || evidence_text || ' ' || paths_text)
                      LIKE ? ESCAPE '\\'
                ORDER BY confidence DESC, occurrences DESC, updated_at DESC
                LIMIT ?
                """)) {
      query.setString(1, workspaceId);
      query.setString(2, USER_SCOPE_ID);
      query.setString(3, pattern);
      query.setInt(4, limit);
      return rows(query);
    } catch (SQLException error) {
      throw failure("fallback memory search", error);
    }
  }

  private List<AceBullet> rows(PreparedStatement query) throws SQLException {
    List<AceBullet> values = new ArrayList<>();
    try (ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        values.add(decode(rows));
      }
    }
    return List.copyOf(values);
  }

  private AceBullet find(Connection connection, String id) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT * FROM memory_entry WHERE workspace_id=? AND memory_id=?")) {
      query.setString(1, workspaceId);
      query.setString(2, id);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? decode(row) : null;
      }
    }
  }

  private boolean conflictsWithActive(Connection connection, AceBullet candidate)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            """
            SELECT 1 FROM memory_entry
            WHERE workspace_id=? AND state='ACTIVE'
              AND lower(trigger)=lower(?) AND lower(lesson)<>lower(?)
            LIMIT 1
            """)) {
      query.setString(1, workspaceId);
      query.setString(2, candidate.trigger());
      query.setString(3, candidate.lesson());
      try (ResultSet row = query.executeQuery()) {
        return row.next();
      }
    }
  }

  private AceBullet decode(ResultSet row) throws SQLException {
    return new AceBullet(
        row.getString("memory_id"),
        row.getString("trigger"),
        row.getString("lesson"),
        strings(row.getString("evidence_json")),
        row.getInt("occurrences"),
        Instant.parse(row.getString("created_at")),
        Instant.parse(row.getString("updated_at")),
        row.getDouble("confidence"),
        strings(row.getString("applicable_paths_json")),
        parseState(row.getString("state")));
  }

  private void initialize() {
    try {
      Files.createDirectories(
          Objects.requireNonNull(database.getParent(), "memory database has no parent"));
      try (Connection connection = open();
          Statement statement = connection.createStatement()) {
        migrateLegacyStateTable(connection, statement);
        statement.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS memory_entry(
              row_id INTEGER PRIMARY KEY AUTOINCREMENT,
              workspace_id TEXT NOT NULL,
              memory_id TEXT NOT NULL,
              trigger TEXT NOT NULL,
              lesson TEXT NOT NULL,
              evidence_json TEXT NOT NULL,
              evidence_text TEXT NOT NULL,
              applicable_paths_json TEXT NOT NULL,
              paths_text TEXT NOT NULL,
              state TEXT NOT NULL CHECK(state IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
              confidence REAL NOT NULL CHECK(confidence BETWEEN 0.0 AND 1.0),
              occurrences INTEGER NOT NULL CHECK(occurrences > 0),
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              type TEXT NOT NULL DEFAULT 'VERIFIED_LESSON',
              scope TEXT NOT NULL DEFAULT 'PROJECT',
              normalized_key TEXT NOT NULL DEFAULT '',
              authority TEXT NOT NULL DEFAULT 'VERIFIED_RESULT',
              durability TEXT NOT NULL DEFAULT 'DURABLE',
              source_session_id TEXT NOT NULL DEFAULT 'legacy',
              source_turn_id TEXT NOT NULL DEFAULT 'legacy',
              last_used_at TEXT,
              consolidated_at TEXT,
              superseded_by TEXT,
              UNIQUE(workspace_id,memory_id)
            )
            """);
        ensureMetadataColumns(statement);
        statement.executeUpdate(
            "UPDATE memory_entry SET normalized_key=memory_id WHERE normalized_key='' OR normalized_key IS NULL");
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS memory_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        statement.executeUpdate(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
              trigger,lesson,evidence_text,paths_text,
              content='memory_entry',content_rowid='row_id',tokenize='trigram')
            """);
        statement.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS memory_entry_ai AFTER INSERT ON memory_entry BEGIN
              INSERT INTO memory_fts(rowid,trigger,lesson,evidence_text,paths_text)
              VALUES(new.row_id,new.trigger,new.lesson,new.evidence_text,new.paths_text);
            END
            """);
        statement.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS memory_entry_ad AFTER DELETE ON memory_entry BEGIN
              INSERT INTO memory_fts(memory_fts,rowid,trigger,lesson,evidence_text,paths_text)
              VALUES('delete',old.row_id,old.trigger,old.lesson,old.evidence_text,old.paths_text);
            END
            """);
        statement.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS memory_entry_au AFTER UPDATE ON memory_entry BEGIN
              INSERT INTO memory_fts(memory_fts,rowid,trigger,lesson,evidence_text,paths_text)
              VALUES('delete',old.row_id,old.trigger,old.lesson,old.evidence_text,old.paths_text);
              INSERT INTO memory_fts(rowid,trigger,lesson,evidence_text,paths_text)
              VALUES(new.row_id,new.trigger,new.lesson,new.evidence_text,new.paths_text);
            END
            """);
        statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        try (ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
          if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
            throw new IllegalStateException("memory database integrity check failed");
          }
        }
      }
    } catch (IOException | SQLException error) {
      throw failure("initialize memory database", error);
    }
  }

  private static void ensureMetadataColumns(Statement statement) throws SQLException {
    Set<String> columns = new java.util.HashSet<>();
    try (ResultSet rows = statement.executeQuery("PRAGMA table_info(memory_entry)")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    addColumn(statement, columns, "type", "TEXT NOT NULL DEFAULT 'VERIFIED_LESSON'");
    addColumn(statement, columns, "scope", "TEXT NOT NULL DEFAULT 'PROJECT'");
    addColumn(statement, columns, "normalized_key", "TEXT NOT NULL DEFAULT ''");
    addColumn(statement, columns, "authority", "TEXT NOT NULL DEFAULT 'VERIFIED_RESULT'");
    addColumn(statement, columns, "durability", "TEXT NOT NULL DEFAULT 'DURABLE'");
    addColumn(statement, columns, "source_session_id", "TEXT NOT NULL DEFAULT 'legacy'");
    addColumn(statement, columns, "source_turn_id", "TEXT NOT NULL DEFAULT 'legacy'");
    addColumn(statement, columns, "last_used_at", "TEXT");
    addColumn(statement, columns, "consolidated_at", "TEXT");
    addColumn(statement, columns, "superseded_by", "TEXT");
  }

  private static void addColumn(
      Statement statement, Set<String> columns, String name, String declaration)
      throws SQLException {
    if (!columns.contains(name)) {
      statement.executeUpdate("ALTER TABLE memory_entry ADD COLUMN " + name + " " + declaration);
    }
  }

  private void migrateLegacyStateTable(Connection connection, Statement statement)
      throws SQLException {
    boolean legacyTable;
    try (ResultSet table =
        statement.executeQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='memory_entry'")) {
      legacyTable = table.next() && table.getString(1).contains("PENDING_REVIEW");
    }
    if (!legacyTable) {
      return;
    }
    connection.setAutoCommit(false);
    try {
      statement.executeUpdate("DROP TRIGGER IF EXISTS memory_entry_ai");
      statement.executeUpdate("DROP TRIGGER IF EXISTS memory_entry_ad");
      statement.executeUpdate("DROP TRIGGER IF EXISTS memory_entry_au");
      statement.executeUpdate("DROP TABLE IF EXISTS memory_fts");
      statement.executeUpdate("ALTER TABLE memory_entry RENAME TO memory_entry_v1");
      statement.executeUpdate(
          """
          CREATE TABLE memory_entry(
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            workspace_id TEXT NOT NULL,
            memory_id TEXT NOT NULL,
            trigger TEXT NOT NULL,
            lesson TEXT NOT NULL,
            evidence_json TEXT NOT NULL,
            evidence_text TEXT NOT NULL,
            applicable_paths_json TEXT NOT NULL,
            paths_text TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('ACTIVE','SUPERSEDED','ARCHIVED')),
            confidence REAL NOT NULL CHECK(confidence BETWEEN 0.0 AND 1.0),
            occurrences INTEGER NOT NULL CHECK(occurrences > 0),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            UNIQUE(workspace_id,memory_id)
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO memory_entry(
            row_id,workspace_id,memory_id,trigger,lesson,evidence_json,evidence_text,
            applicable_paths_json,paths_text,state,confidence,occurrences,created_at,updated_at)
          SELECT row_id,workspace_id,memory_id,trigger,lesson,evidence_json,evidence_text,
            applicable_paths_json,paths_text,
            CASE WHEN state='PENDING_REVIEW' THEN 'ACTIVE' ELSE state END,
            confidence,occurrences,created_at,updated_at
          FROM memory_entry_v1
          """);
      statement.executeUpdate("DROP TABLE memory_entry_v1");
      connection.commit();
    } catch (SQLException error) {
      connection.rollback();
      throw error;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private Connection open() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("PRAGMA busy_timeout=5000");
      statement.execute("PRAGMA synchronous=NORMAL");
    }
    return connection;
  }

  private void importLegacy(Path legacyJsonl) {
    if (legacyJsonl == null || !Files.isRegularFile(legacyJsonl)) {
      return;
    }
    String key = "legacy-import:" + workspaceId;
    try (Connection connection = open()) {
      if (metaExists(connection, key)) {
        return;
      }
      connection.setAutoCommit(false);
      int lineNumber = 0;
      for (String line : Files.readAllLines(legacyJsonl, StandardCharsets.UTF_8)) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        try {
          AceBullet legacy = decodeLegacy(line);
          validateSafe(legacy);
          write(connection, legacy);
        } catch (RuntimeException | SQLException malformed) {
          warnings.add("legacy memory line " + lineNumber + " skipped: " + safeMessage(malformed));
        }
      }
      try (PreparedStatement meta =
          connection.prepareStatement("INSERT INTO memory_meta(key,value) VALUES(?,?)")) {
        meta.setString(1, key);
        meta.setString(2, Instant.now().toString());
        meta.executeUpdate();
      }
      connection.commit();
    } catch (IOException | SQLException error) {
      throw failure("import legacy memory", error);
    }
  }

  private boolean metaExists(Connection connection, String key) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT 1 FROM memory_meta WHERE key=?")) {
      query.setString(1, key);
      try (ResultSet row = query.executeQuery()) {
        return row.next();
      }
    }
  }

  private AceBullet decodeLegacy(String line) {
    try {
      JsonNode node = mapper.readTree(line);
      return new AceBullet(
          node.path("id").asText(),
          node.path("trigger").asText(),
          node.path("lesson").asText(),
          nodeStrings(node.path("evidence")),
          node.path("occurrences").asInt(1),
          Instant.parse(node.path("createdAt").asText()),
          Instant.parse(node.path("updatedAt").asText()),
          node.path("confidence").isNumber() ? node.path("confidence").asDouble() : 0.55,
          nodeStrings(node.path("applicablePaths")),
          parseState(node.path("state").asText("ACTIVE")));
    } catch (IOException error) {
      throw new IllegalArgumentException("malformed legacy memory", error);
    }
  }

  private String json(List<String> values) {
    try {
      return mapper.writeValueAsString(values);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode memory list", error);
    }
  }

  private List<String> strings(String json) {
    try {
      return nodeStrings(mapper.readTree(json));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot decode memory list", error);
    }
  }

  private static List<String> nodeStrings(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(value -> values.add(value.asText()));
    }
    return List.copyOf(values);
  }

  private static AceBullet withState(AceBullet bullet, AceBullet.State state) {
    return new AceBullet(
        bullet.id(),
        bullet.trigger(),
        bullet.lesson(),
        bullet.evidence(),
        bullet.occurrences(),
        bullet.createdAt(),
        Instant.now(),
        bullet.confidence(),
        bullet.applicablePaths(),
        state);
  }

  private static AceBullet.State parseState(String value) {
    if ("PENDING_REVIEW".equals(value)) {
      return AceBullet.State.ACTIVE;
    }
    try {
      return AceBullet.State.valueOf(value);
    } catch (IllegalArgumentException error) {
      return AceBullet.State.ACTIVE;
    }
  }

  private static String quoteFts(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.strip();
  }

  private String storageScope(MemoryScope scope) {
    return scope == MemoryScope.USER ? USER_SCOPE_ID : workspaceId;
  }

  private static IllegalStateException failure(String action, Exception error) {
    return new IllegalStateException("cannot " + action + ": " + safeMessage(error), error);
  }

  private static String safeMessage(Throwable error) {
    return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
  }

  private record Scored(AceBullet bullet, double relevance) {}

  private record StoredMemory(
      String id, String content, List<String> evidence, int occurrences, Instant createdAt) {}
}
