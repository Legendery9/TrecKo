package com.trecko.application.trecko.service;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Branch;
import com.trecko.application.trecko.domain.Models.Eatery;
import com.trecko.application.trecko.domain.Models.Item;
import com.trecko.application.trecko.domain.Models.MenuItem;
import com.trecko.application.trecko.domain.Models.Participant;
import com.trecko.application.trecko.domain.Models.ProfilePreferences;
import com.trecko.application.trecko.repository.CatalogRepository;

import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CatalogService {
    public static final class DuplicateNameException extends IllegalArgumentException {
        private final String suggestedName;

        public DuplicateNameException(String suggestedName) {
            super("A record with this name already exists");
            this.suggestedName = suggestedName;
        }

        public String suggestedName() { return suggestedName; }
    }

    private final Database database;
    private final CatalogRepository repository;

    public CatalogService(Database database) {
        this.database = database;
        this.repository = new CatalogRepository(database);
    }

    public boolean profileInitialized() throws SQLException {
        try (var statement = database.connection().prepareStatement("SELECT 1 FROM participant WHERE id = 1 AND is_profile = 1");
             var rows = statement.executeQuery()) {
            return rows.next();
        }
    }

    public Participant createProfile(String name) throws SQLException {
        return createProfile(name, "");
    }

    public Participant createProfile(String name, String note) throws SQLException {
        if (profileInitialized()) throw new IllegalStateException("Profile already exists");
        String clean = validateName(name);
        String cleanNote = validateNote(note);
        String key = nameKey(clean);
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO participant(id, name, name_key, note, is_profile) VALUES(1, ?, ?, ?, 1)")) {
                statement.setString(1, clean);
                statement.setString(2, key);
                statement.setString(3, cleanNote);
                statement.executeUpdate();
            }
            return null;
        });
        return new Participant(1, clean, true);
    }

    public List<Participant> participants() throws SQLException { return repository.participants(); }
    public List<Eatery> eateries() throws SQLException { return repository.eateries(); }
    public List<Branch> branches(boolean activeOnly) throws SQLException { return repository.branches(activeOnly); }
    public List<Item> items() throws SQLException { return repository.items(); }
    public List<MenuItem> menu(long branchId) throws SQLException { return repository.menu(branchId); }
    public List<MenuItem> menu(long branchId, long participantId) throws SQLException {
        return repository.menu(branchId, participantId);
    }
    public ProfilePreferences profilePreferences(long participantId) throws SQLException {
        return repository.profilePreferences(participantId);
    }

    public List<String> tags(String scope, String entityId) throws SQLException {
        TagMapping mapping = tagMapping(scope);
        try (var statement = database.connection().prepareStatement("""
                SELECT t.name FROM %s link JOIN tag t ON t.id = link.tag_id
                WHERE link.%s = ? ORDER BY t.name COLLATE NOCASE
                """.formatted(mapping.linkTable(), mapping.entityColumn()))) {
            statement.setString(1, entityId);
            try (var rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getString(1));
                return List.copyOf(result);
            }
        }
    }

    public void setTags(String scope, String entityId, List<String> names) throws SQLException {
        String tagScope = scope == null ? "" : scope.toUpperCase(Locale.ROOT);
        TagMapping mapping = tagMapping(tagScope);
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String name : names == null ? List.<String>of() : names) {
            String clean = Normalizer.normalize(name == null ? "" : name.strip(), Normalizer.Form.NFC);
            if (clean.isEmpty()) continue;
            if (clean.length() > 30) throw new IllegalArgumentException("Tags must be at most 30 characters");
            normalized.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
        }
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM " + mapping.entityTable() + " WHERE CAST(id AS TEXT) = ?")) {
                statement.setString(1, entityId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Catalog record does not exist");
                }
            }
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + mapping.linkTable() + " WHERE " + mapping.entityColumn() + " = ?")) {
                statement.setString(1, entityId);
                statement.executeUpdate();
            }
            for (String name : normalized.values()) {
                String key = name.toLowerCase(Locale.ROOT);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO tag(scope, name, name_key, created_at) VALUES(?, ?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT(scope, name_key) DO NOTHING
                        """)) {
                    statement.setString(1, tagScope);
                    statement.setString(2, name);
                    statement.setString(3, key);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("SELECT id FROM tag WHERE scope = ? AND name_key = ?")) {
                    statement.setString(1, tagScope);
                    statement.setString(2, key);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Tag could not be loaded after saving");
                        try (var link = connection.prepareStatement("""
                                INSERT INTO %s(%s, tag_id) VALUES(?, ?)
                                """.formatted(mapping.linkTable(), mapping.entityColumn()))) {
                            link.setString(1, entityId);
                            link.setLong(2, rows.getLong(1));
                            link.executeUpdate();
                        }
                    }
                }
            }
            return null;
        });
    }

    public String participantNote(long participantId) throws SQLException {
        return repository.note("participant", Long.toString(participantId));
    }

    public void updateParticipantNote(long participantId, String note) throws SQLException {
        updateParticipant(participantId, null, note, false);
    }

    public Participant createParticipant(String name, boolean allowDuplicate) throws SQLException {
        return createParticipant(name, "", allowDuplicate);
    }

    public Participant createParticipant(String name, String note, boolean allowDuplicate) throws SQLException {
        String clean = uniqueName("participant", name, allowDuplicate);
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement(
                "INSERT INTO participant(name, name_key, note) VALUES(?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clean);
            statement.setString(2, nameKey(clean));
            statement.setString(3, cleanNote);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Participant ID was not generated");
                return new Participant(keys.getLong(1), clean, false);
            }
        }
    }

    public Eatery createEatery(String name, boolean allowDuplicate) throws SQLException {
        return createEatery(name, "", allowDuplicate);
    }

    public Eatery createEatery(String name, String note, boolean allowDuplicate) throws SQLException {
        String clean = uniqueName("eatery", name, allowDuplicate);
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement(
                "INSERT INTO eatery(name, name_key, note) VALUES(?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clean);
            statement.setString(2, nameKey(clean));
            statement.setString(3, cleanNote);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Eatery ID was not generated");
                return new Eatery(keys.getLong(1), clean);
            }
        }
    }

    public Branch createBranch(long eateryId, String address, String phone, boolean allowShip) throws SQLException {
        return createBranch(eateryId, address, phone, allowShip, "");
    }

    public Branch createBranch(long eateryId, String address, String phone, boolean allowShip, String note) throws SQLException {
        String cleanAddress = address == null ? "" : address.strip();
        if (cleanAddress.length() < 4 || cleanAddress.length() > 255) {
            throw new IllegalArgumentException("Address must contain 4–255 characters");
        }
        String cleanPhone = phone == null ? "" : phone.strip();
        if (cleanPhone.length() > 20) throw new IllegalArgumentException("Phone number must be at most 20 characters");
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO branch(eatery_id, address, phone_number, allow_ship, note) VALUES(?, ?, ?, ?, ?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, eateryId);
            statement.setString(2, cleanAddress);
            statement.setString(3, cleanPhone);
            statement.setInt(4, allowShip ? 1 : 0);
            statement.setString(5, cleanNote);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Branch ID was not generated");
                long branchId = keys.getLong(1);
                try (var lookup = database.connection().prepareStatement("""
                        SELECT b.id, b.eatery_id, e.name, b.address, b.phone_number, b.status, b.allow_ship
                        FROM branch b JOIN eatery e ON e.id = b.eatery_id WHERE b.id = ?
                        """)) {
                    lookup.setLong(1, branchId);
                    try (var rows = lookup.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Created branch could not be reloaded");
                        return new Branch(rows.getLong("id"), rows.getLong("eatery_id"), rows.getString("name"),
                                rows.getString("address"), rows.getString("phone_number"), rows.getString("status"),
                                rows.getInt("allow_ship") == 1);
                    }
                }
            }
        } catch (SQLException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("UNIQUE constraint failed: branch.eatery_id, branch.address")) {
                throw new IllegalArgumentException("This branch address already exists for the selected eatery", failure);
            }
            throw failure;
        }
    }

    public Item createItem(String name, boolean allowDuplicate) throws SQLException {
        return createItem(name, "", allowDuplicate);
    }

    public Item createItem(String name, String note, boolean allowDuplicate) throws SQLException {
        String clean = uniqueName("item", name, allowDuplicate);
        String cleanNote = validateNote(note);
        String id = UUID.randomUUID().toString();
        try (var statement = database.connection().prepareStatement(
                "INSERT INTO item(id, name, name_key, note) VALUES(?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, clean);
            statement.setString(3, nameKey(clean));
            statement.setString(4, cleanNote);
            statement.executeUpdate();
        }
        return new Item(id, clean, 0, 0);
    }

    public String addMenuItem(long branchId, String itemId, long price) throws SQLException {
        return addMenuItem(branchId, itemId, price, "");
    }

    public String addMenuItem(long branchId, String itemId, long price, String note) throws SQLException {
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO storage(id, branch_id, item_id, price, is_active, note)
                VALUES(?, ?, ?, ?, 1, ?)
                ON CONFLICT(branch_id, item_id) DO UPDATE SET price = excluded.price, is_active = 1, note = excluded.note
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, branchId);
            statement.setString(3, itemId);
            statement.setLong(4, price);
            statement.setString(5, cleanNote);
            statement.executeUpdate();
        }
        try (var statement = database.connection().prepareStatement("SELECT id FROM storage WHERE branch_id = ? AND item_id = ?")) {
            statement.setLong(1, branchId);
            statement.setString(2, itemId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Menu item could not be reloaded");
                return rows.getString(1);
            }
        }
    }

    public void updateMenuPrice(String storageId, long price) throws SQLException {
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        try (var statement = database.connection().prepareStatement("UPDATE storage SET price = ? WHERE id = ? AND is_active = 1")) {
            statement.setLong(1, price);
            statement.setString(2, storageId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Menu item is no longer active");
        }
    }

    public void updateMenuItem(String storageId, long price, String note) throws SQLException {
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement(
                "UPDATE storage SET price = ?, note = ? WHERE id = ? AND is_active = 1")) {
            statement.setLong(1, price);
            statement.setString(2, cleanNote);
            statement.setString(3, storageId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Menu item is no longer active");
        }
    }

    public void updateParticipant(long id, String name, String note, boolean allowDuplicate) throws SQLException {
        String cleanName = name == null ? null : uniqueName("participant", name, allowDuplicate, Long.toString(id));
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement("""
                UPDATE participant SET name = COALESCE(?, name), name_key = COALESCE(?, name_key), note = ? WHERE id = ?
                """)) {
            statement.setString(1, cleanName);
            statement.setString(2, cleanName == null ? null : nameKey(cleanName));
            statement.setString(3, cleanNote);
            statement.setLong(4, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Participant does not exist");
        }
    }

    public void updateEatery(long id, String name, String note, boolean allowDuplicate) throws SQLException {
        String cleanName = uniqueName("eatery", name, allowDuplicate, Long.toString(id));
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement(
                "UPDATE eatery SET name = ?, name_key = ?, note = ? WHERE id = ?")) {
            statement.setString(1, cleanName);
            statement.setString(2, nameKey(cleanName));
            statement.setString(3, cleanNote);
            statement.setLong(4, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Eatery does not exist");
        }
    }

    public void updateBranch(long id, String address, String phone, boolean allowShip, String note) throws SQLException {
        String cleanAddress = address == null ? "" : address.strip();
        String cleanPhone = phone == null ? "" : phone.strip();
        if (cleanAddress.length() < 4 || cleanAddress.length() > 255) {
            throw new IllegalArgumentException("Address must contain 4–255 characters");
        }
        if (cleanPhone.length() > 20) throw new IllegalArgumentException("Phone number must be at most 20 characters");
        String cleanNote = validateNote(note);
        try (var statement = database.connection().prepareStatement("""
                UPDATE branch SET address = ?, phone_number = ?, allow_ship = ?, note = ? WHERE id = ?
                """)) {
            statement.setString(1, cleanAddress);
            statement.setString(2, cleanPhone);
            statement.setInt(3, allowShip ? 1 : 0);
            statement.setString(4, cleanNote);
            statement.setLong(5, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Branch does not exist");
        } catch (SQLException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("UNIQUE constraint failed: branch.eatery_id, branch.address")) {
                throw new IllegalArgumentException("This branch address already exists for the selected eatery", failure);
            }
            throw failure;
        }
    }

    public String note(String entity, String id) throws SQLException { return repository.note(entity, id); }

    public void removeMenuItem(String storageId) throws SQLException {
        database.transaction(connection -> {
            boolean used;
            try (var statement = connection.prepareStatement("SELECT 1 FROM transaction_item WHERE storage_id = ? LIMIT 1")) {
                statement.setString(1, storageId);
                try (var rows = statement.executeQuery()) { used = rows.next(); }
            }
            try (var statement = connection.prepareStatement(used
                    ? "UPDATE storage SET is_active = 0 WHERE id = ?"
                    : "DELETE FROM storage WHERE id = ?")) {
                statement.setString(1, storageId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void deleteItem(String itemId) throws SQLException {
        database.transaction(connection -> {
            boolean used;
            try (var statement = connection.prepareStatement("""
                    SELECT 1 FROM transaction_item ti JOIN storage s ON s.id = ti.storage_id
                    WHERE s.item_id = ? LIMIT 1
                    """)) {
                statement.setString(1, itemId);
                try (var rows = statement.executeQuery()) { used = rows.next(); }
            }
            if (used) {
                try (var statement = connection.prepareStatement("UPDATE item SET is_active = 0 WHERE id = ?")) {
                    statement.setString(1, itemId);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("UPDATE storage SET is_active = 0 WHERE item_id = ?")) {
                    statement.setString(1, itemId);
                    statement.executeUpdate();
                }
            } else {
                try (var statement = connection.prepareStatement("DELETE FROM item WHERE id = ?")) {
                    statement.setString(1, itemId);
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public void setBranchPreference(long participantId, long branchId, String kind) throws SQLException {
        setPreference("participant_branch_pref", "branch_id", participantId, branchId, kind);
    }

    public void setStoragePreference(long participantId, String storageId, String kind) throws SQLException {
        try (var statement = database.connection().prepareStatement(kind == null
                ? "DELETE FROM participant_storage_pref WHERE participant_id = ? AND storage_id = ?"
                : """
                  INSERT INTO participant_storage_pref(participant_id, storage_id, kind, created_at)
                  VALUES(?, ?, ?, CURRENT_TIMESTAMP)
                  ON CONFLICT(participant_id, storage_id) DO UPDATE SET kind = excluded.kind
                  """)) {
            statement.setLong(1, participantId);
            statement.setString(2, storageId);
            if (kind != null) {
                validatePreference(kind);
                statement.setString(3, kind);
            }
            statement.executeUpdate();
        }
    }

    public void closeBranch(long branchId) throws SQLException {
        database.transaction(connection -> {
            boolean used;
            try (var statement = connection.prepareStatement("""
                    SELECT EXISTS(SELECT 1 FROM "transaction" WHERE branch_id = ?)
                        OR EXISTS(SELECT 1 FROM storage WHERE branch_id = ?)
                    """)) {
                statement.setLong(1, branchId);
                statement.setLong(2, branchId);
                try (var rows = statement.executeQuery()) { used = rows.next() && rows.getInt(1) == 1; }
            }
            try (var statement = connection.prepareStatement(used
                    ? "UPDATE branch SET status = 'CLOSED' WHERE id = ?"
                    : "DELETE FROM branch WHERE id = ?")) {
                statement.setLong(1, branchId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void deleteEatery(long eateryId) throws SQLException {
        try (var statement = database.connection().prepareStatement("DELETE FROM eatery WHERE id = ?")) {
            statement.setLong(1, eateryId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Eatery does not exist");
        } catch (SQLException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("FOREIGN KEY constraint failed")) {
                throw new IllegalArgumentException("Remove or close its branches before deleting the eatery", failure);
            }
            throw failure;
        }
    }

    public void deleteParticipant(long participantId) throws SQLException {
        if (participantId == 1) throw new IllegalArgumentException("The owner profile cannot be deleted");
        try (var statement = database.connection().prepareStatement("""
                DELETE FROM participant
                WHERE id = ? AND NOT EXISTS (SELECT 1 FROM "transaction" WHERE participant_id = ? OR payer_id = ?)
                AND NOT EXISTS (SELECT 1 FROM debt_relation WHERE participant1_id = ? OR participant2_id = ?)
                """)) {
            for (int i = 1; i <= 5; i++) statement.setLong(i, participantId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("A participant with transaction history cannot be deleted");
            }
        }
    }

    private void setPreference(String table, String itemColumn, long participantId, long targetId, String kind) throws SQLException {
        if (kind == null) {
            try (var statement = database.connection().prepareStatement(
                    "DELETE FROM " + table + " WHERE participant_id = ? AND " + itemColumn + " = ?")) {
                statement.setLong(1, participantId);
                statement.setLong(2, targetId);
                statement.executeUpdate();
            }
            return;
        }
        validatePreference(kind);
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO %s(participant_id, %s, kind, created_at) VALUES(?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(participant_id, %s) DO UPDATE SET kind = excluded.kind
                """.formatted(table, itemColumn, itemColumn))) {
            statement.setLong(1, participantId);
            statement.setLong(2, targetId);
            statement.setString(3, kind);
            statement.executeUpdate();
        }
    }

    private String uniqueName(String table, String name, boolean allowDuplicate) throws SQLException {
        return uniqueName(table, name, allowDuplicate, null);
    }

    private String uniqueName(String table, String name, boolean allowDuplicate, String excludedId) throws SQLException {
        String clean = validateName(name);
        String key = nameKey(clean);
        String exclude = excludedId == null ? "" : " AND CAST(id AS TEXT) <> ?";
        try (var statement = database.connection().prepareStatement(
                "SELECT 1 FROM " + table + " WHERE name_key = ?" + exclude)) {
            statement.setString(1, key);
            if (excludedId != null) statement.setString(2, excludedId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return clean;
            }
        }
        String suggested = clean + " 2";
        if (!allowDuplicate) throw new DuplicateNameException(suggested);
        for (int index = 2; ; index++) {
            String candidate = clean + " " + index;
            try (var statement = database.connection().prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE name_key = ?" + exclude)) {
                statement.setString(1, nameKey(candidate));
                if (excludedId != null) statement.setString(2, excludedId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return candidate;
                }
            }
        }
    }

    private static String validateName(String name) {
        String clean = name == null ? "" : Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (clean.length() < 2 || clean.length() > 60) throw new IllegalArgumentException("Name must contain 2–60 characters");
        return clean;
    }

    private static String nameKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String validateNote(String note) {
        String clean = note == null ? "" : note.strip();
        if (clean.length() > 5000) throw new IllegalArgumentException("Note must be at most 5,000 characters");
        return clean;
    }

    private static void validatePreference(String kind) {
        if (!"WHITELIST".equals(kind) && !"BLACKLIST".equals(kind)) {
            throw new IllegalArgumentException("Preference must be WHITELIST or BLACKLIST");
        }
    }

    private static TagMapping tagMapping(String scope) {
        return switch (scope == null ? "" : scope.toUpperCase(Locale.ROOT)) {
            case "PARTICIPANT" -> new TagMapping("participant", "participant_tag", "participant_id");
            case "EATERY" -> new TagMapping("eatery", "eatery_tag", "eatery_id");
            case "ITEM" -> new TagMapping("item", "item_tag", "item_id");
            default -> throw new IllegalArgumentException("Unsupported tag scope: " + scope);
        };
    }

    private record TagMapping(String entityTable, String linkTable, String entityColumn) {}
}
