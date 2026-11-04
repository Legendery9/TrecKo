package com.trecko.application.trecko.repository;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Branch;
import com.trecko.application.trecko.domain.Models.Eatery;
import com.trecko.application.trecko.domain.Models.Item;
import com.trecko.application.trecko.domain.Models.MenuItem;
import com.trecko.application.trecko.domain.Models.Participant;
import com.trecko.application.trecko.domain.Models.ProfilePreferences;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class CatalogRepository extends BaseRepository {
    public CatalogRepository(Database database) {
        super(database);
    }

    public List<Participant> participants() throws SQLException {
        try (var statement = database.connection().prepareStatement(
                "SELECT id, name, is_profile FROM participant ORDER BY is_profile DESC, name COLLATE NOCASE");
             var rows = statement.executeQuery()) {
            List<Participant> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new Participant(rows.getLong("id"), rows.getString("name"), rows.getInt("is_profile") == 1));
            }
            return result;
        }
    }

    public List<Eatery> eateries() throws SQLException {
        try (var statement = database.connection().prepareStatement("SELECT id, name FROM eatery ORDER BY name COLLATE NOCASE");
             var rows = statement.executeQuery()) {
            List<Eatery> result = new ArrayList<>();
            while (rows.next()) result.add(new Eatery(rows.getLong("id"), rows.getString("name")));
            return result;
        }
    }

    public List<Branch> branches(boolean activeOnly) throws SQLException {
        String filter = activeOnly ? "WHERE b.status = 'ACTIVE'" : "";
        String sql = """
                SELECT b.id, b.eatery_id, e.name, b.address, b.phone_number, b.status, b.allow_ship
                FROM branch b JOIN eatery e ON e.id = b.eatery_id
                """ + filter + """
                 ORDER BY CASE (SELECT kind FROM participant_branch_pref
                                WHERE participant_id = 1 AND branch_id = b.id) WHEN 'WHITELIST' THEN 0
                                WHEN 'BLACKLIST' THEN 2 ELSE 1 END,
                          e.name COLLATE NOCASE, b.address COLLATE NOCASE
                """;
        try (var statement = database.connection().prepareStatement(sql);
             var rows = statement.executeQuery()) {
            List<Branch> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new Branch(rows.getLong("id"), rows.getLong("eatery_id"), rows.getString("name"),
                        rows.getString("address"), rows.getString("phone_number"), rows.getString("status"),
                        rows.getInt("allow_ship") == 1));
            }
            return result;
        }
    }

    public List<Item> items() throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                SELECT i.id, i.name, COALESCE(s.avg_price, 0) avg_price, COALESCE(s.branch_count, 0) branch_count
                FROM item i LEFT JOIN v_item_stats s ON s.item_id = i.id
                WHERE i.is_active = 1 ORDER BY i.name COLLATE NOCASE
                """);
             var rows = statement.executeQuery()) {
            List<Item> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new Item(rows.getString("id"), rows.getString("name"),
                        rows.getLong("avg_price"), rows.getInt("branch_count")));
            }
            return result;
        }
    }

    public List<MenuItem> menu(long branchId) throws SQLException {
        return menu(branchId, 1);
    }

    public List<MenuItem> menu(long branchId, long participantId) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                SELECT s.id storage_id, i.id item_id, i.name, s.price, s.note, p.kind preference
                FROM storage s JOIN item i ON i.id = s.item_id
                LEFT JOIN v_effective_pref p ON p.storage_id = s.id AND p.participant_id = ?
                WHERE s.branch_id = ? AND s.is_active = 1 AND i.is_active = 1
                ORDER BY CASE p.kind WHEN 'WHITELIST' THEN 0 WHEN 'BLACKLIST' THEN 2 ELSE 1 END,
                         i.name COLLATE NOCASE
                """)) {
            statement.setLong(1, participantId);
            statement.setLong(2, branchId);
            try (var rows = statement.executeQuery()) {
                List<MenuItem> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new MenuItem(rows.getString("storage_id"), rows.getString("item_id"),
                            rows.getString("name"), rows.getLong("price"), rows.getString("note"),
                            rows.getString("preference")));
                }
                return result;
            }
        }
    }

    public ProfilePreferences profilePreferences(long participantId) throws SQLException {
        List<String> branches = new ArrayList<>();
        try (var statement = database.connection().prepareStatement("""
                SELECT e.name || ' · ' || b.address
                FROM participant_branch_pref pref
                JOIN branch b ON b.id = pref.branch_id
                JOIN eatery e ON e.id = b.eatery_id
                WHERE pref.participant_id = ? AND pref.kind = 'WHITELIST'
                ORDER BY e.name COLLATE NOCASE, b.address COLLATE NOCASE
                """)) {
            statement.setLong(1, participantId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) branches.add(rows.getString(1));
            }
        }

        List<String> items = new ArrayList<>();
        try (var statement = database.connection().prepareStatement("""
                SELECT i.name || ' @ ' || e.name
                FROM participant_storage_pref pref
                JOIN storage s ON s.id = pref.storage_id
                JOIN item i ON i.id = s.item_id
                JOIN branch b ON b.id = s.branch_id
                JOIN eatery e ON e.id = b.eatery_id
                WHERE pref.participant_id = ? AND pref.kind = 'WHITELIST'
                ORDER BY i.name COLLATE NOCASE, e.name COLLATE NOCASE
                """)) {
            statement.setLong(1, participantId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) items.add(rows.getString(1));
            }
        }
        return new ProfilePreferences(List.copyOf(branches), List.copyOf(items));
    }

    public String note(String entity, String id) throws SQLException {
        String table = switch (entity) {
            case "participant" -> "participant";
            case "eatery" -> "eatery";
            case "branch" -> "branch";
            case "item" -> "item";
            case "storage" -> "storage";
            default -> throw new IllegalArgumentException("Unsupported note entity: " + entity);
        };
        try (var statement = database.connection().prepareStatement(
                "SELECT note FROM " + table + " WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Catalog record does not exist");
                return rows.getString(1);
            }
        }
    }
}
