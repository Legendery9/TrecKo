package com.trecko.application.trecko.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> REQUIRED_SCHEMA_OBJECTS = Set.of(
            "participant", "eatery", "branch", "item", "storage", "transaction",
            "transaction_item", "purchase_split", "debt_relation", "debt_settlement",
            "tag", "participant_tag", "eatery_tag", "item_tag",
            "participant_branch_pref", "participant_storage_pref", "app_settings",
            "v_item_stats", "v_effective_pref");

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE participant (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
                name_key TEXT NOT NULL UNIQUE,
                note TEXT NOT NULL DEFAULT '',
                is_profile INTEGER NOT NULL DEFAULT 0 CHECK (is_profile IN (0,1))
            )
            """,
            """
            CREATE TABLE eatery (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
                name_key TEXT NOT NULL UNIQUE,
                note TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE branch (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                eatery_id INTEGER NOT NULL REFERENCES eatery(id) ON DELETE RESTRICT,
                address TEXT NOT NULL CHECK (length(address) BETWEEN 4 AND 255),
                phone_number TEXT NOT NULL DEFAULT '' CHECK (length(phone_number) <= 20),
                allow_ship INTEGER NOT NULL DEFAULT 0 CHECK (allow_ship IN (0,1)),
                status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
                note TEXT NOT NULL DEFAULT '',
                UNIQUE (eatery_id, address)
            )
            """,
            """
            CREATE TABLE item (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL CHECK (length(name) BETWEEN 2 AND 60),
                name_key TEXT NOT NULL UNIQUE,
                note TEXT NOT NULL DEFAULT '',
                is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1))
            )
            """,
            """
            CREATE TABLE storage (
                id TEXT PRIMARY KEY,
                branch_id INTEGER NOT NULL REFERENCES branch(id) ON DELETE CASCADE,
                item_id TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
                price INTEGER NOT NULL DEFAULT 0 CHECK (price >= 0),
                is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
                note TEXT NOT NULL DEFAULT '',
                UNIQUE (branch_id, item_id)
            )
            """,
            """
            CREATE TABLE "transaction" (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL DEFAULT 'PURCHASE' CHECK (type IN ('PURCHASE','REPAYMENT')),
                branch_id INTEGER NOT NULL REFERENCES branch(id),
                participant_id INTEGER NOT NULL REFERENCES participant(id),
                payer_id INTEGER REFERENCES participant(id),
                amount INTEGER CHECK (amount IS NULL OR amount > 0),
                date TEXT NOT NULL,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('PAID','UNPAID','INVALID')),
                note TEXT NOT NULL DEFAULT '',
                CHECK (payer_id IS NULL OR payer_id <> participant_id),
                CHECK ((type = 'PURCHASE' AND amount IS NULL) OR
                       (type = 'REPAYMENT' AND amount IS NOT NULL AND payer_id IS NOT NULL))
            )
            """,
            """
            CREATE TABLE transaction_item (
                id TEXT PRIMARY KEY,
                transaction_id TEXT NOT NULL REFERENCES "transaction"(id) ON DELETE CASCADE,
                storage_id TEXT NOT NULL REFERENCES storage(id),
                quantity INTEGER NOT NULL CHECK (quantity > 0),
                unit_price INTEGER NOT NULL CHECK (unit_price >= 0),
                amount INTEGER GENERATED ALWAYS AS (quantity * unit_price) STORED,
                UNIQUE (transaction_id, storage_id)
            )
            """,
            """
            CREATE TABLE purchase_split (
                transaction_id TEXT PRIMARY KEY REFERENCES "transaction"(id) ON DELETE CASCADE,
                amount INTEGER NOT NULL CHECK (amount >= 0)
            )
            """,
            """
            CREATE TABLE debt_relation (
                id TEXT PRIMARY KEY,
                branch_id INTEGER NOT NULL REFERENCES branch(id),
                participant1_id INTEGER NOT NULL REFERENCES participant(id),
                participant2_id INTEGER NOT NULL REFERENCES participant(id),
                balance INTEGER NOT NULL DEFAULT 0,
                CHECK (participant1_id < participant2_id),
                UNIQUE (branch_id, participant1_id, participant2_id)
            )
            """,
            """
            CREATE TABLE debt_settlement (
                id TEXT PRIMARY KEY,
                debt_relation_id TEXT NOT NULL REFERENCES debt_relation(id),
                payment_transaction_id TEXT NOT NULL REFERENCES "transaction"(id) ON DELETE CASCADE,
                settled_transaction_id TEXT NOT NULL REFERENCES "transaction"(id),
                amount INTEGER NOT NULL CHECK (amount > 0),
                created_at TEXT NOT NULL,
                CHECK (payment_transaction_id <> settled_transaction_id)
            )
            """,
            """
            CREATE TABLE tag (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL CHECK (scope IN ('PARTICIPANT','EATERY','ITEM')),
                name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 30),
                name_key TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE (scope, name_key),
                UNIQUE (id, scope)
            )
            """,
            """
            CREATE TABLE participant_tag (
                participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE CASCADE,
                tag_id INTEGER NOT NULL,
                scope TEXT NOT NULL DEFAULT 'PARTICIPANT' CHECK (scope = 'PARTICIPANT'),
                PRIMARY KEY (participant_id, tag_id),
                FOREIGN KEY (tag_id, scope) REFERENCES tag(id, scope) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE eatery_tag (
                eatery_id INTEGER NOT NULL REFERENCES eatery(id) ON DELETE CASCADE,
                tag_id INTEGER NOT NULL,
                scope TEXT NOT NULL DEFAULT 'EATERY' CHECK (scope = 'EATERY'),
                PRIMARY KEY (eatery_id, tag_id),
                FOREIGN KEY (tag_id, scope) REFERENCES tag(id, scope) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE item_tag (
                item_id TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
                tag_id INTEGER NOT NULL,
                scope TEXT NOT NULL DEFAULT 'ITEM' CHECK (scope = 'ITEM'),
                PRIMARY KEY (item_id, tag_id),
                FOREIGN KEY (tag_id, scope) REFERENCES tag(id, scope) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE participant_branch_pref (
                participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE CASCADE,
                branch_id INTEGER NOT NULL REFERENCES branch(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK (kind IN ('WHITELIST','BLACKLIST')),
                created_at TEXT NOT NULL,
                PRIMARY KEY (participant_id, branch_id)
            )
            """,
            """
            CREATE TABLE participant_storage_pref (
                participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE CASCADE,
                storage_id TEXT NOT NULL REFERENCES storage(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK (kind IN ('WHITELIST','BLACKLIST')),
                created_at TEXT NOT NULL,
                PRIMARY KEY (participant_id, storage_id)
            )
            """,
            """
            CREATE TABLE app_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_storage_branch ON storage(branch_id)",
            "CREATE INDEX idx_storage_item ON storage(item_id)",
            "CREATE INDEX idx_tx_branch_created ON \"transaction\"(branch_id, created_at)",
            "CREATE INDEX idx_tx_participant ON \"transaction\"(participant_id)",
            "CREATE INDEX idx_tx_payer ON \"transaction\"(payer_id)",
            "CREATE INDEX idx_tx_status ON \"transaction\"(status)",
            "CREATE INDEX idx_ti_transaction ON transaction_item(transaction_id)",
            "CREATE INDEX idx_dr_branch ON debt_relation(branch_id)",
            "CREATE INDEX idx_ds_debt_relation ON debt_settlement(debt_relation_id)",
            "CREATE INDEX idx_ds_settled ON debt_settlement(settled_transaction_id)",
            "CREATE INDEX idx_branch_pref_branch ON participant_branch_pref(branch_id)",
            "CREATE INDEX idx_storage_pref_storage ON participant_storage_pref(storage_id)",
            """
            CREATE VIEW v_item_stats AS
            SELECT i.id AS item_id, CAST(ROUND(AVG(CASE WHEN b.id IS NOT NULL THEN s.price END)) AS INTEGER) AS avg_price,
                   COUNT(DISTINCT b.id) AS branch_count, group_concat(DISTINCT e.name) AS eatery_names
            FROM item i
            LEFT JOIN storage s ON s.item_id = i.id AND s.is_active = 1
            LEFT JOIN branch b ON b.id = s.branch_id AND b.status = 'ACTIVE'
            LEFT JOIN eatery e ON e.id = b.eatery_id
            WHERE i.is_active = 1 GROUP BY i.id
            """,
            """
            CREATE VIEW v_effective_pref AS
            SELECT s.id AS storage_id, p.id AS participant_id, COALESCE(sp.kind, bp.kind) AS kind
            FROM storage s JOIN branch b ON b.id = s.branch_id CROSS JOIN participant p
            LEFT JOIN participant_storage_pref sp ON sp.participant_id = p.id AND sp.storage_id = s.id
            LEFT JOIN participant_branch_pref bp ON bp.participant_id = p.id AND bp.branch_id = b.id
            WHERE s.is_active = 1 AND COALESCE(sp.kind, bp.kind) IS NOT NULL
            """
    );

    private final Path file;
    private Connection connection;

    public Database(Path file) throws SQLException, IOException {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        if (this.file != null) {
            Files.createDirectories(this.file.getParent());
        }
        try {
            open();
            migrate();
        } catch (SQLException | RuntimeException failure) {
            try {
                close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static Database inMemory() throws SQLException, IOException {
        return new Database(null);
    }

    public Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database is closed");
        }
        return connection;
    }

    public <T> T transaction(SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(work);
        Connection current = connection();
        current.setAutoCommit(false);
        try {
            T result = work.run(current);
            current.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                current.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            current.setAutoCommit(true);
        }
    }

    public void backupTo(Path destination) throws SQLException, IOException {
        if (file == null) {
            throw new IllegalStateException("In-memory databases cannot be backed up");
        }
        Path target = destination.toAbsolutePath().normalize();
        if (file.equals(target)) {
            throw new IllegalArgumentException("Backup destination is the active database");
        }
        try (Statement statement = connection().createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void restoreFrom(Path source) throws SQLException, IOException {
        if (file == null) {
            throw new IllegalStateException("An in-memory database cannot be restored");
        }
        Path backup = source.toAbsolutePath().normalize();
        if (file.equals(backup)) {
            throw new IllegalArgumentException("Restore source is the active database");
        }
        Path staged = Files.createTempFile(file.getParent(), "trecko-restore-", ".db");
        try {
            Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING);
            validateBackup(staged);
            try (Statement statement = connection().createStatement();
                 var checkpoint = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (checkpoint.next() && checkpoint.getInt(1) != 0) {
                    throw new SQLException("The active database could not be checkpointed for restore");
                }
            }
            close();
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
            try {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicMoveUnsupported) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
            open();
        } catch (SQLException | IOException | RuntimeException failure) {
            if (connection == null) {
                try {
                    open();
                } catch (SQLException reopenFailure) {
                    failure.addSuppressed(reopenFailure);
                }
            }
            throw failure;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private void open() throws SQLException {
        String url = file == null ? "jdbc:sqlite::memory:" : "jdbc:sqlite:" + file;
        connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            if (file != null) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
        }
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement statement = connection().createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            version = result.getInt(1);
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Database schema " + version + " is newer than supported schema " + SCHEMA_VERSION);
        }
        if (version == 0) {
            transaction(current -> {
                try (Statement statement = current.createStatement()) {
                    for (String ddl : SCHEMA) {
                        statement.execute(ddl);
                    }
                    statement.execute("INSERT INTO app_settings(setting_key, setting_value) VALUES ('tolerance','5000'), ('sort_desc','1')");
                    statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                }
                return null;
            });
        }
    }

    private static void validateBackup(Path backup) throws SQLException {
        try (Connection candidate = DriverManager.getConnection("jdbc:sqlite:" + backup);
             Statement statement = candidate.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Backup integrity check failed");
                }
            }
            try (var version = statement.executeQuery("PRAGMA user_version")) {
                if (!version.next() || version.getInt(1) != SCHEMA_VERSION) {
                    throw new SQLException("Backup schema version is not supported");
                }
            }
            try (var schema = statement.executeQuery("""
                    SELECT name FROM sqlite_master WHERE type IN ('table', 'view')
                    """)) {
                java.util.Set<String> found = new java.util.HashSet<>();
                while (schema.next()) found.add(schema.getString(1));
                if (!found.containsAll(REQUIRED_SCHEMA_OBJECTS)) {
                    throw new SQLException("Backup is missing required TrecKo schema objects");
                }
            }
            try (var foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) throw new SQLException("Backup contains foreign-key violations");
            }
        }
    }
}
