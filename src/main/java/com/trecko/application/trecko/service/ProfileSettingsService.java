package com.trecko.application.trecko.service;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Relationship;
import com.trecko.application.trecko.domain.Models.Settings;
import com.trecko.application.trecko.repository.SettingsRepository;
import com.trecko.application.trecko.repository.TransactionRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public final class ProfileSettingsService {
    private final Database database;
    private final SettingsRepository settings;
    private final TransactionRepository transactions;

    public ProfileSettingsService(Database database) {
        this.database = database;
        this.settings = new SettingsRepository(database);
        this.transactions = new TransactionRepository(database);
    }

    public Settings settings() throws SQLException {
        long tolerance;
        try {
            tolerance = Long.parseLong(settings.get("tolerance", "5000"));
        } catch (NumberFormatException failure) {
            throw new SQLException("Stored debt tolerance is invalid", failure);
        }
        if (tolerance < 0) throw new SQLException("Stored debt tolerance cannot be negative");
        return new Settings(tolerance, !"0".equals(settings.get("sort_desc", "1")));
    }

    public void updateSettings(long tolerance, boolean sortDescending) throws SQLException {
        if (tolerance < 0) throw new IllegalArgumentException("Tolerance cannot be negative");
        database.transaction(connection -> {
            settings.put("tolerance", Long.toString(tolerance));
            settings.put("sort_desc", sortDescending ? "1" : "0");
            return null;
        });
    }

    public List<Relationship> relationship(long participantA, long participantB) throws SQLException {
        return transactions.relationship(participantA, participantB);
    }

    public long balanceBetween(long participantA, long participantB) throws SQLException {
        return transactions.balanceBetween(participantA, participantB);
    }

    public void backup(Path destination) throws SQLException, IOException {
        database.backupTo(destination);
    }

    public void restore(Path source) throws SQLException, IOException {
        database.restoreFrom(source);
    }
}
