package com.trecko.application.trecko.repository;

import com.trecko.application.trecko.database.Database;

import java.util.Objects;

public abstract class BaseRepository {
    protected final Database database;

    protected BaseRepository(Database database) {
        this.database = Objects.requireNonNull(database);
    }
}
