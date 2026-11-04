package com.trecko.application.trecko.repository;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Debt;
import com.trecko.application.trecko.domain.Models.Relationship;
import com.trecko.application.trecko.domain.Models.Transaction;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class TransactionRepository extends BaseRepository {
    public TransactionRepository(Database database) {
        super(database);
    }

    public List<Transaction> transactions(long branchId, boolean descending) throws SQLException {
        String direction = descending ? "DESC" : "ASC";
        String sql = """
                SELECT t.id, t.type, t.branch_id, t.participant_id, p.name participant_name,
                       t.payer_id, payer.name payer_name, t.date, t.status, t.note,
                       CASE WHEN t.type = 'REPAYMENT' THEN t.amount
                            ELSE COALESCE((SELECT ps.amount FROM purchase_split ps WHERE ps.transaction_id = t.id),
                                          (SELECT SUM(ti.amount) FROM transaction_item ti WHERE ti.transaction_id = t.id), 0)
                       END amount
                FROM "transaction" t
                JOIN participant p ON p.id = t.participant_id
                LEFT JOIN participant payer ON payer.id = t.payer_id
                WHERE t.branch_id = ?
                ORDER BY t.date %s, t.created_at %s
                """.formatted(direction, direction);
        try (var statement = database.connection().prepareStatement(sql)) {
            statement.setLong(1, branchId);
            try (var rows = statement.executeQuery()) {
                List<Transaction> result = new ArrayList<>();
                while (rows.next()) {
                    long payerValue = rows.getLong("payer_id");
                    Long payerId = rows.wasNull() ? null : payerValue;
                    result.add(new Transaction(rows.getString("id"), rows.getString("type"), rows.getLong("branch_id"),
                            rows.getLong("participant_id"), rows.getString("participant_name"), payerId,
                            rows.getString("payer_name"), rows.getLong("amount"), rows.getString("date"),
                            rows.getString("status"), rows.getString("note")));
                }
                return result;
            }
        }
    }

    public List<Debt> debts(long branchId) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                SELECT d.id, d.participant1_id, d.participant2_id, p1.name participant1_name,
                       p2.name participant2_name, d.balance
                FROM debt_relation d
                JOIN participant p1 ON p1.id = d.participant1_id
                JOIN participant p2 ON p2.id = d.participant2_id
                WHERE d.branch_id = ? AND d.balance <> 0
                ORDER BY ABS(d.balance) DESC
                """)) {
            statement.setLong(1, branchId);
            try (var rows = statement.executeQuery()) {
                List<Debt> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Debt(rows.getString("id"), rows.getLong("participant1_id"),
                            rows.getLong("participant2_id"), rows.getString("participant1_name"),
                            rows.getString("participant2_name"), rows.getLong("balance")));
                }
                return result;
            }
        }
    }

    public long balanceBetween(long participantA, long participantB) throws SQLException {
        long balance = 0;
        try (var statement = database.connection().prepareStatement("""
                SELECT balance FROM debt_relation WHERE participant1_id = ? AND participant2_id = ?
                """)) {
            statement.setLong(1, Math.min(participantA, participantB));
            statement.setLong(2, Math.max(participantA, participantB));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    long current = rows.getLong(1);
                    balance = Math.addExact(balance, participantA < participantB ? current : Math.negateExact(current));
                }
            }
        }
        return balance;
    }

    public List<Transaction> unpaidPurchases(long branchId, long debtorId, long creditorId) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                SELECT t.id, t.type, t.branch_id, t.participant_id, p.name participant_name,
                       t.payer_id, payer.name payer_name, t.date, t.status, t.note,
                       COALESCE((SELECT ps.amount FROM purchase_split ps WHERE ps.transaction_id = t.id),
                                (SELECT SUM(ti.amount) FROM transaction_item ti WHERE ti.transaction_id = t.id), 0) amount
                FROM "transaction" t
                JOIN participant p ON p.id = t.participant_id
                LEFT JOIN participant payer ON payer.id = t.payer_id
                WHERE t.type = 'PURCHASE' AND t.status = 'UNPAID'
                  AND t.branch_id = ? AND t.participant_id = ? AND t.payer_id = ?
                ORDER BY t.date, t.created_at
                """)) {
            statement.setLong(1, branchId);
            statement.setLong(2, debtorId);
            statement.setLong(3, creditorId);
            try (var rows = statement.executeQuery()) {
                List<Transaction> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Transaction(rows.getString("id"), rows.getString("type"), rows.getLong("branch_id"),
                            rows.getLong("participant_id"), rows.getString("participant_name"),
                            rows.getLong("payer_id"), rows.getString("payer_name"), rows.getLong("amount"),
                            rows.getString("date"), rows.getString("status"), rows.getString("note")));
                }
                return result;
            }
        }
    }

    public List<Relationship> relationship(long participantA, long participantB) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                SELECT t.branch_id, e.name || ' · ' || b.address branch_name, t.id, t.type,
                       t.participant_id, t.payer_id,
                       CASE WHEN t.type = 'REPAYMENT' THEN 'Trả nợ'
                            WHEN EXISTS(SELECT 1 FROM purchase_split ps WHERE ps.transaction_id = t.id) THEN 'Chia đều'
                            ELSE COALESCE((SELECT group_concat(i.name || ' ×' || ti.quantity, ', ')
                                           FROM transaction_item ti JOIN storage s ON s.id = ti.storage_id
                                           JOIN item i ON i.id = s.item_id WHERE ti.transaction_id = t.id), 'Hóa đơn') END description,
                       CASE WHEN t.type = 'REPAYMENT' THEN t.amount
                            ELSE COALESCE((SELECT ps.amount FROM purchase_split ps WHERE ps.transaction_id = t.id),
                                          (SELECT SUM(ti.amount) FROM transaction_item ti WHERE ti.transaction_id = t.id), 0)
                       END amount, t.date, t.status
                FROM "transaction" t JOIN branch b ON b.id = t.branch_id JOIN eatery e ON e.id = b.eatery_id
                WHERE (t.participant_id = ? AND t.payer_id = ?)
                   OR (t.participant_id = ? AND t.payer_id = ?)
                   OR (t.type = 'REPAYMENT' AND ((t.participant_id = ? AND t.payer_id = ?)
                                              OR (t.participant_id = ? AND t.payer_id = ?)))
                ORDER BY t.date DESC, t.created_at DESC
                """)) {
            statement.setLong(1, participantA);
            statement.setLong(2, participantB);
            statement.setLong(3, participantB);
            statement.setLong(4, participantA);
            statement.setLong(5, participantA);
            statement.setLong(6, participantB);
            statement.setLong(7, participantB);
            statement.setLong(8, participantA);
            try (var rows = statement.executeQuery()) {
                List<Relationship> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Relationship(rows.getLong("branch_id"), rows.getString("branch_name"),
                            rows.getString("id"), rows.getString("type"), rows.getLong("participant_id"),
                            rows.getObject("payer_id") == null ? null : rows.getLong("payer_id"), rows.getString("description"),
                            rows.getLong("amount"), rows.getString("date"), rows.getString("status")));
                }
                return result;
            }
        }
    }
}
