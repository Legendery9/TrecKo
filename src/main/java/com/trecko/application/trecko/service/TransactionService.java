package com.trecko.application.trecko.service;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Debt;
import com.trecko.application.trecko.domain.Models.PurchaseLine;
import com.trecko.application.trecko.domain.Models.Relationship;
import com.trecko.application.trecko.domain.Models.SplitLine;
import com.trecko.application.trecko.domain.Models.Transaction;
import com.trecko.application.trecko.repository.TransactionRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TransactionService {
    private final Database database;
    private final TransactionRepository repository;

    public TransactionService(Database database) {
        this.database = database;
        this.repository = new TransactionRepository(database);
    }

    public List<Transaction> transactions(long branchId, boolean descending) throws SQLException {
        return repository.transactions(branchId, descending);
    }

    public List<Debt> debts(long branchId) throws SQLException {
        return repository.debts(branchId);
    }

    public List<Transaction> unpaidPurchases(long branchId, long debtorId, long creditorId) throws SQLException {
        return repository.unpaidPurchases(branchId, debtorId, creditorId);
    }

    public List<Relationship> relationship(long participantA, long participantB) throws SQLException {
        return repository.relationship(participantA, participantB);
    }

    public List<String> details(String transactionId) throws SQLException {
        Purchase purchase = database.transaction(connection -> TransactionService.purchase(connection, transactionId));
        List<String> details = new ArrayList<>();
        if (purchase.type.equals("REPAYMENT")) {
            if (purchase.status.equals("INVALID")) {
                return List.of("Giao dịch đã vô hiệu hóa; công nợ và các liên kết hóa đơn đã được hoàn tác.");
            }
            long linkedTotal = 0;
            try (var statement = database.connection().prepareStatement("""
                    SELECT ds.amount, t.date, t.id
                    FROM debt_settlement ds JOIN "transaction" t ON t.id = ds.settled_transaction_id
                    WHERE ds.payment_transaction_id = ? ORDER BY t.date
                    """)) {
                statement.setString(1, transactionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        linkedTotal = Math.addExact(linkedTotal, rows.getLong("amount"));
                        details.add("Đã khấu trừ hóa đơn " + rows.getString("date") + " · " +
                                rows.getString("id").substring(0, 8) + " · " + money(rows.getLong("amount")));
                    }
                }
            }
            if (linkedTotal == 0) details.add("Bù trừ công nợ độc lập.");
            else if (linkedTotal < purchase.amount) details.add("Tiền trả trước còn lại · " + money(purchase.amount - linkedTotal));
        } else {
            try (var statement = database.connection().prepareStatement("""
                    SELECT ps.amount split_amount FROM purchase_split ps WHERE ps.transaction_id = ?
                    """)) {
                statement.setString(1, transactionId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) details.add("Phần chia đều · " + money(rows.getLong("split_amount")));
                }
            }
            try (var statement = database.connection().prepareStatement("""
                    SELECT i.name, ti.quantity, ti.unit_price, ti.amount
                    FROM transaction_item ti JOIN storage s ON s.id = ti.storage_id
                    JOIN item i ON i.id = s.item_id WHERE ti.transaction_id = ?
                    ORDER BY i.name COLLATE NOCASE
                    """)) {
                statement.setString(1, transactionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        details.add(rows.getString("name") + " × " + rows.getInt("quantity") + " · " +
                                money(rows.getLong("unit_price")) + " × " + rows.getInt("quantity") +
                                " = " + money(rows.getLong("amount")));
                    }
                }
            }
        }
        return List.copyOf(details);
    }

    public String recordPurchase(long branchId, long eaterId, Long payerId,
                                 List<PurchaseLine> lines, String note) throws SQLException {
        List<PurchaseLine> cleanLines = validateLines(lines);
        return database.transaction(connection -> {
            long total = verifyLines(connection, branchId, cleanLines);
            return createPurchase(connection, branchId, eaterId, payerId, cleanLines, total, null, note);
        });
    }

    public List<String> recordSplitEqual(long branchId, long payerId, long total,
                                         List<Long> participantIds, String note) throws SQLException {
        List<Long> participants = distinctParticipants(participantIds);
        if (participants.isEmpty()) throw new IllegalArgumentException("Select at least one participant");
        if (total <= 0) throw new IllegalArgumentException("Bill total must be greater than zero");
        return database.transaction(connection -> {
            requireActiveBranch(connection, branchId);
            List<String> created = new ArrayList<>();
            long each = total / participants.size();
            long remainder = total % participants.size();
            for (int i = 0; i < participants.size(); i++) {
                long participantId = participants.get(i);
                long share = each + (i == 0 ? remainder : 0);
                String id = createPurchase(connection, branchId, participantId,
                        participantId == payerId || share == 0 ? null : payerId, List.of(), 0, share, note);
                created.add(id);
            }
            return created;
        });
    }

    public List<String> recordSplitByItems(long branchId, long payerId,
                                           List<SplitLine> splitLines, String note) throws SQLException {
        if (splitLines == null || splitLines.isEmpty()) throw new IllegalArgumentException("Add at least one participant");
        Set<Long> participants = new HashSet<>();
        for (SplitLine split : splitLines) {
            if (!participants.add(split.participantId())) throw new IllegalArgumentException("A participant can appear only once");
            validateLines(split.items());
        }
        return database.transaction(connection -> {
            requireActiveBranch(connection, branchId);
            List<String> created = new ArrayList<>();
            for (SplitLine split : splitLines) {
                List<PurchaseLine> lines = validateLines(split.items());
                long total = verifyLines(connection, branchId, lines);
                created.add(createPurchase(connection, branchId, split.participantId(),
                        split.participantId() == payerId ? null : payerId, lines, total, null, note));
            }
            return created;
        });
    }

    public String recordRepayment(long branchId, long receiverId, long payerId, long amount,
                                  List<String> settledPurchaseIds, String note) throws SQLException {
        if (receiverId == payerId) throw new IllegalArgumentException("Payer and receiver must be different people");
        if (amount <= 0) throw new IllegalArgumentException("Repayment must be greater than zero");
        List<String> selected = settledPurchaseIds == null ? List.of() : List.copyOf(settledPurchaseIds);
        if (new HashSet<>(selected).size() != selected.size()) throw new IllegalArgumentException("An invoice can only be selected once");
        return database.transaction(connection -> {
            requireActiveBranch(connection, branchId);
            long settledTotal = 0;
            for (String purchaseId : selected) {
                Purchase purchase = purchase(connection, purchaseId);
                if (!purchase.status.equals("UNPAID") || !purchase.type.equals("PURCHASE")
                        || purchase.branchId != branchId || purchase.debtorId != payerId || purchase.creditorId != receiverId) {
                    throw new IllegalArgumentException("A selected invoice is no longer payable by this pair");
                }
                settledTotal = Math.addExact(settledTotal, purchase.amount);
            }
            if (amount < settledTotal) throw new IllegalArgumentException("Repayment must cover the selected invoices");
            String id = createRepayment(connection, branchId, receiverId, payerId, amount, note);
            adjustDebt(connection, branchId, payerId, receiverId, -amount);
            for (String purchaseId : selected) {
                Purchase purchase = purchase(connection, purchaseId);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO debt_settlement(id, debt_relation_id, payment_transaction_id,
                                                    settled_transaction_id, amount, created_at)
                        VALUES(?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, debtRelationId(connection, branchId, payerId, receiverId));
                    statement.setString(3, id);
                    statement.setString(4, purchaseId);
                    statement.setLong(5, purchase.amount);
                    statement.setString(6, Instant.now().toString());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(
                        "UPDATE \"transaction\" SET status = 'PAID' WHERE id = ? AND status = 'UNPAID'")) {
                    statement.setString(1, purchaseId);
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException("A selected invoice changed during payment");
                }
            }
            return id;
        });
    }

    public void invalidateRepayment(String transactionId, String reason) throws SQLException {
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.isEmpty() || cleanReason.length() > 2000) {
            throw new IllegalArgumentException("Enter a reason of 1–2,000 characters");
        }
        database.transaction(connection -> {
            Repayment repayment = repayment(connection, transactionId);
            if (!repayment.status.equals("PAID")) throw new IllegalArgumentException("Only a paid repayment can be invalidated");
            adjustDebt(connection, repayment.branchId, repayment.payerId, repayment.receiverId, repayment.amount);
            try (var statement = connection.prepareStatement("""
                    UPDATE "transaction" SET status = 'UNPAID'
                    WHERE id IN (SELECT settled_transaction_id FROM debt_settlement WHERE payment_transaction_id = ?)
                    """)) {
                statement.setString(1, transactionId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("DELETE FROM debt_settlement WHERE payment_transaction_id = ?")) {
                statement.setString(1, transactionId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE "transaction" SET status = 'INVALID',
                        note = CASE WHEN note = '' THEN ? ELSE note || char(10) || char(10) || ? END
                    WHERE id = ?
                    """)) {
                String callout = "> [!Attention] Lý do vô hiệu hóa: " + cleanReason;
                statement.setString(1, callout);
                statement.setString(2, callout);
                statement.setString(3, transactionId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void deleteUnpaidPurchase(String transactionId) throws SQLException {
        database.transaction(connection -> {
            Purchase purchase = purchase(connection, transactionId);
            if (!purchase.type.equals("PURCHASE") || !purchase.status.equals("UNPAID")) {
                throw new IllegalArgumentException("Only an unpaid purchase can be deleted");
            }
            if (purchase.creditorId != null) adjustDebt(connection, purchase.branchId, purchase.debtorId,
                    purchase.creditorId, -purchase.amount);
            try (var statement = connection.prepareStatement("DELETE FROM \"transaction\" WHERE id = ?")) {
                statement.setString(1, transactionId);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Purchase no longer exists");
            }
            return null;
        });
    }

    private static String createPurchase(Connection connection, long branchId, long eaterId, Long payerId,
                                         List<PurchaseLine> lines, long computedTotal,
                                         Long splitAmount, String note) throws SQLException {
        if (payerId != null && eaterId == payerId) throw new IllegalArgumentException("A person cannot owe themselves");
        String status = payerId == null || (splitAmount == null && computedTotal == 0)
                || (splitAmount != null && splitAmount == 0) ? "PAID" : "UNPAID";
        String id = UUID.randomUUID().toString();
        insertTransaction(connection, id, "PURCHASE", branchId, eaterId, payerId, null, status, note);
        for (PurchaseLine line : lines) {
            long price;
            try (var statement = connection.prepareStatement("""
                    SELECT s.price FROM storage s JOIN branch b ON b.id = s.branch_id
                    WHERE s.id = ? AND s.branch_id = ? AND s.is_active = 1 AND b.status = 'ACTIVE'
                    """)) {
                statement.setString(1, line.storageId());
                statement.setLong(2, branchId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("A selected menu item is no longer active at this branch");
                    price = rows.getLong(1);
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO transaction_item(id, transaction_id, storage_id, quantity, unit_price)
                    VALUES(?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, id);
                statement.setString(3, line.storageId());
                statement.setInt(4, line.quantity());
                statement.setLong(5, price);
                statement.executeUpdate();
            }
        }
        if (splitAmount != null) {
            try (var statement = connection.prepareStatement("INSERT INTO purchase_split(transaction_id, amount) VALUES(?, ?)")) {
                statement.setString(1, id);
                statement.setLong(2, splitAmount);
                statement.executeUpdate();
            }
        }
        if (payerId != null && computedTotal > 0) adjustDebt(connection, branchId, eaterId, payerId, computedTotal);
        if (payerId != null && splitAmount != null && splitAmount > 0) adjustDebt(connection, branchId, eaterId, payerId, splitAmount);
        return id;
    }

    private static long verifyLines(Connection connection, long branchId, List<PurchaseLine> lines) throws SQLException {
        requireActiveBranch(connection, branchId);
        long total = 0;
        for (PurchaseLine line : lines) {
            try (var statement = connection.prepareStatement("""
                    SELECT s.price FROM storage s JOIN branch b ON b.id = s.branch_id
                    WHERE s.id = ? AND s.branch_id = ? AND s.is_active = 1 AND b.status = 'ACTIVE'
                    """)) {
                statement.setString(1, line.storageId());
                statement.setLong(2, branchId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("A selected menu item is not active at this branch");
                    total = Math.addExact(total, Math.multiplyExact((long) line.quantity(), rows.getLong(1)));
                }
            }
        }
        return total;
    }

    private static List<PurchaseLine> validateLines(List<PurchaseLine> lines) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Add at least one menu item");
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (PurchaseLine line : lines) {
            if (line == null || line.storageId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("Menu item quantity must be greater than zero");
            }
            quantities.merge(line.storageId(), line.quantity(), Math::addExact);
        }
        return quantities.entrySet().stream().map(entry -> new PurchaseLine(entry.getKey(), entry.getValue())).toList();
    }

    private static List<Long> distinctParticipants(List<Long> input) {
        if (input == null || new HashSet<>(input).size() != input.size()) {
            throw new IllegalArgumentException("Each participant can be selected only once");
        }
        return List.copyOf(input);
    }

    private static void requireActiveBranch(Connection connection, long branchId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT status FROM branch WHERE id = ?")) {
            statement.setLong(1, branchId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"ACTIVE".equals(rows.getString(1))) {
                    throw new IllegalArgumentException("Select an active branch");
                }
            }
        }
    }

    private static String createRepayment(Connection connection, long branchId, long receiverId, long payerId,
                                          long amount, String note) throws SQLException {
        String id = UUID.randomUUID().toString();
        insertTransaction(connection, id, "REPAYMENT", branchId, receiverId, payerId, amount, "PAID", note);
        return id;
    }

    private static void insertTransaction(Connection connection, String id, String type, long branchId,
                                          long participantId, Long payerId, Long amount,
                                          String status, String note) throws SQLException {
        String cleanNote = note == null ? "" : note.strip();
        if (cleanNote.length() > 5000) throw new IllegalArgumentException("Note must be at most 5,000 characters");
        try (var statement = connection.prepareStatement("""
                INSERT INTO "transaction"(id, type, branch_id, participant_id, payer_id, amount, date, created_at, status, note)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            String now = Instant.now().toString();
            statement.setString(1, id);
            statement.setString(2, type);
            statement.setLong(3, branchId);
            statement.setLong(4, participantId);
            if (payerId == null) statement.setNull(5, java.sql.Types.INTEGER); else statement.setLong(5, payerId);
            if (amount == null) statement.setNull(6, java.sql.Types.INTEGER); else statement.setLong(6, amount);
            statement.setString(7, LocalDate.now().toString());
            statement.setString(8, now);
            statement.setString(9, status);
            statement.setString(10, cleanNote);
            statement.executeUpdate();
        }
    }

    private static void adjustDebt(Connection connection, long branchId, long debtorId, long creditorId,
                                   long amount) throws SQLException {
        if (amount == 0) return;
        long first = Math.min(debtorId, creditorId);
        long second = Math.max(debtorId, creditorId);
        long delta = debtorId == first ? amount : Math.negateExact(amount);
        Long currentBalance = null;
        try (var statement = connection.prepareStatement("""
                SELECT balance FROM debt_relation
                WHERE branch_id = ? AND participant1_id = ? AND participant2_id = ?
                """)) {
            statement.setLong(1, branchId);
            statement.setLong(2, first);
            statement.setLong(3, second);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) currentBalance = rows.getLong(1);
            }
        }
        if (currentBalance == null) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO debt_relation(id, branch_id, participant1_id, participant2_id, balance)
                    VALUES(?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setLong(2, branchId);
                statement.setLong(3, first);
                statement.setLong(4, second);
                statement.setLong(5, delta);
                statement.executeUpdate();
            }
        } else {
            long nextBalance = Math.addExact(currentBalance, delta);
            try (var statement = connection.prepareStatement("""
                    UPDATE debt_relation SET balance = ?
                    WHERE branch_id = ? AND participant1_id = ? AND participant2_id = ?
                    """)) {
                statement.setLong(1, nextBalance);
                statement.setLong(2, branchId);
                statement.setLong(3, first);
                statement.setLong(4, second);
                statement.executeUpdate();
            }
        }
    }

    private static String debtRelationId(Connection connection, long branchId, long debtorId, long creditorId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id FROM debt_relation
                WHERE branch_id = ? AND participant1_id = ? AND participant2_id = ?
                """)) {
            statement.setLong(1, branchId);
            statement.setLong(2, Math.min(debtorId, creditorId));
            statement.setLong(3, Math.max(debtorId, creditorId));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Debt relation was not created");
                return rows.getString(1);
            }
        }
    }

    private static Purchase purchase(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT t.type, t.status, t.branch_id, t.participant_id, t.payer_id,
                       CASE WHEN t.type = 'REPAYMENT' THEN t.amount
                            ELSE COALESCE((SELECT ps.amount FROM purchase_split ps WHERE ps.transaction_id = t.id),
                                          (SELECT SUM(ti.amount) FROM transaction_item ti WHERE ti.transaction_id = t.id), 0)
                       END amount
                FROM "transaction" t WHERE t.id = ?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Transaction does not exist");
                long payer = rows.getLong("payer_id");
                Long payerId = rows.wasNull() ? null : payer;
                return new Purchase(rows.getString("type"), rows.getString("status"), rows.getLong("branch_id"),
                        rows.getLong("participant_id"), payerId, rows.getLong("amount"));
            }
        }
    }

    private static Repayment repayment(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT type, status, branch_id, participant_id, payer_id, amount
                FROM "transaction" WHERE id = ?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"REPAYMENT".equals(rows.getString("type"))) {
                    throw new IllegalArgumentException("Repayment does not exist");
                }
                return new Repayment(rows.getString("status"), rows.getLong("branch_id"),
                        rows.getLong("participant_id"), rows.getLong("payer_id"), rows.getLong("amount"));
            }
        }
    }

    private static String money(long value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " ₫";
    }

    private record Purchase(String type, String status, long branchId, long debtorId, Long creditorId, long amount) {}
    private record Repayment(String status, long branchId, long receiverId, long payerId, long amount) {}
}
