package com.trecko.application.trecko.repository;

import com.trecko.application.trecko.database.Database;

import java.sql.SQLException;

public final class SettingsRepository extends BaseRepository {
    public SettingsRepository(Database database) {
        super(database);
    }

    public String get(String key, String fallback) throws SQLException {
        try (var statement = database.connection().prepareStatement(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : fallback;
            }
        }
    }

    public void put(String key, String value) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO app_settings(setting_key, setting_value) VALUES(?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}
