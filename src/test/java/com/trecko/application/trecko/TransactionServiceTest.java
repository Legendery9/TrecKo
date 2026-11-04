package com.trecko.application.trecko;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.Participant;
import com.trecko.application.trecko.domain.Models.PurchaseLine;
import com.trecko.application.trecko.domain.Models.Transaction;
import com.trecko.application.trecko.service.CatalogService.DuplicateNameException;
import com.trecko.application.trecko.service.HomeService;
import com.trecko.application.trecko.service.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionServiceTest {
    private Database database;
    private HomeService service;
    private long branchId;
    private String storageId;
    private Participant owner;
    private Participant friend;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.inMemory();
        service = new HomeService(database);
        owner = service.createProfile("TrecKo Owner");
        friend = service.createParticipant("Friend One", false);
        var eatery = service.createEatery("Local Cafe", false);
        branchId = service.createBranch(eatery.id(), "12 Main Street", "", false).id();
        var item = service.createItem("Milk Tea", false);
        storageId = service.addMenuItem(branchId, item.id(), 25_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void purchaseSnapshotsPriceAndRepaymentInvalidationRestoresDebtAndInvoice() throws Exception {
        String purchaseId = service.recordPurchase(branchId, friend.id(), owner.id(),
                List.of(new PurchaseLine(storageId, 1)), "Dinner note");
        service.updateMenuPrice(storageId, 50_000);
        assertEquals(25_000, service.balanceBetween(friend.id(), owner.id()));
        assertEquals(25_000, service.transactions(branchId, true).getFirst().amount());

        String repaymentId = service.recordRepayment(branchId, owner.id(), friend.id(), 25_000, List.of(purchaseId), "");
        assertEquals(0, service.balanceBetween(friend.id(), owner.id()));
        assertEquals("PAID", transaction(purchaseId).status());

        service.invalidateRepayment(repaymentId, "Chuyển khoản thất bại");
        assertEquals(25_000, service.balanceBetween(friend.id(), owner.id()));
        assertEquals("UNPAID", transaction(purchaseId).status());
        Transaction invalid = service.transactions(branchId, true).stream()
                .filter(row -> row.id().equals(repaymentId)).findFirst().orElseThrow();
        assertEquals("INVALID", invalid.status());
        assertTrue(invalid.note().contains("> [!Attention] Lý do vô hiệu hóa: Chuyển khoản thất bại"));
    }

    @Test
    void splitBillAssignsEveryVndAndCreatesOnePurchasePerEater() throws Exception {
        Participant third = service.createParticipant("Friend Two", false);
        List<String> ids = service.recordSplitEqual(branchId, owner.id(), 10_000,
                List.of(owner.id(), friend.id(), third.id()), "");

        assertEquals(3, ids.size());
        assertEquals(3, service.transactions(branchId, true).size());
        try (var statement = database.connection().prepareStatement("SELECT SUM(amount) FROM purchase_split");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(10_000, rows.getLong(1));
        }
        assertEquals(3_333, service.balanceBetween(friend.id(), owner.id()));
        assertEquals(3_333, service.balanceBetween(third.id(), owner.id()));
    }

    @Test
    void deletingUnpaidPurchaseReversesItsDebt() throws Exception {
        String id = service.recordPurchase(branchId, friend.id(), owner.id(),
                List.of(new PurchaseLine(storageId, 2)), "");
        assertEquals(50_000, service.balanceBetween(friend.id(), owner.id()));
        service.deleteUnpaidPurchase(id);
        assertEquals(0, service.balanceBetween(friend.id(), owner.id()));
        assertTrue(service.transactions(branchId, true).isEmpty());
    }

    @Test
    void independentRepaymentOffsetsDebtAndInvalidationRestoresIt() throws Exception {
        service.recordPurchase(branchId, friend.id(), owner.id(), List.of(new PurchaseLine(storageId, 1)), "");
        String repayment = service.recordRepayment(branchId, owner.id(), friend.id(), 30_000, List.of(), "");
        assertEquals(-5_000, service.balanceBetween(friend.id(), owner.id()));
        service.invalidateRepayment(repayment, "Nhập nhầm số tiền");
        assertEquals(25_000, service.balanceBetween(friend.id(), owner.id()));
    }

    @Test
    void splitByItemsCreatesOneSnapshotInvoiceForEachPerson() throws Exception {
        Participant third = service.createParticipant("Friend Two", false);
        List<String> ids = service.recordSplitByItems(branchId, owner.id(), List.of(
                new com.trecko.application.trecko.domain.Models.SplitLine(friend.id(), List.of(new PurchaseLine(storageId, 1))),
                new com.trecko.application.trecko.domain.Models.SplitLine(third.id(), List.of(new PurchaseLine(storageId, 2)))),
                "");
        assertEquals(2, ids.size());
        assertEquals(25_000, service.balanceBetween(friend.id(), owner.id()));
        assertEquals(50_000, service.balanceBetween(third.id(), owner.id()));
        assertEquals(2, service.transactions(branchId, true).size());
    }

    @Test
    void menuHistoryIsHiddenRatherThanDeletedAndStoragePreferenceOverridesBranch() throws Exception {
        String purchaseId = service.recordPurchase(branchId, friend.id(), owner.id(),
                List.of(new PurchaseLine(storageId, 1)), "");
        service.setBranchPreference(owner.id(), branchId, "WHITELIST");
        service.setStoragePreference(owner.id(), storageId, "BLACKLIST");
        try (var statement = database.connection().prepareStatement(
                "SELECT kind FROM v_effective_pref WHERE participant_id = ? AND storage_id = ?")) {
            statement.setLong(1, owner.id());
            statement.setString(2, storageId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("BLACKLIST", rows.getString(1));
            }
        }

        service.removeMenuItem(storageId);
        assertTrue(service.menu(branchId).isEmpty());
        try (var statement = database.connection().prepareStatement("""
                SELECT ti.unit_price FROM transaction_item ti WHERE ti.transaction_id = ?
                """)) {
            statement.setString(1, purchaseId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(25_000, rows.getLong(1));
            }
        }
    }

    @Test
    void profileListsBranchAndMenuFavorites() throws Exception {
        service.setBranchPreference(owner.id(), branchId, "WHITELIST");
        service.setStoragePreference(owner.id(), storageId, "WHITELIST");

        var preferences = service.profilePreferences(owner.id());

        assertEquals(List.of("Local Cafe · 12 Main Street"), preferences.branches());
        assertEquals(List.of("Milk Tea @ Local Cafe"), preferences.items());
    }

    @Test
    void catalogNotesAreSavedAtCreationAndCanBeEdited() throws Exception {
        service.updateParticipantNote(owner.id(), "Owner profile note");
        Participant person = service.createParticipant("Note Person", "**Friend** $x$", false);
        var eatery = service.createEatery("Note Cafe", "Open late", false);
        var branch = service.createBranch(eatery.id(), "99 North Street", "123456", true, "Near the station");
        var item = service.createItem("Note Dish", "Contains sesame", false);
        String storage = service.addMenuItem(branch.id(), item.id(), 32_000, "Ask for less salt");

        assertEquals("Owner profile note", service.note("participant", Long.toString(owner.id())));
        assertEquals("**Friend** $x$", service.note("participant", Long.toString(person.id())));
        assertEquals("Open late", service.note("eatery", Long.toString(eatery.id())));
        assertEquals("Near the station", service.note("branch", Long.toString(branch.id())));
        assertEquals("Contains sesame", service.note("item", item.id()));
        assertEquals("Ask for less salt", service.menu(branch.id()).getFirst().note());

        service.updateParticipant(person.id(), "Updated Person", "$y$", false);
        service.updateEatery(eatery.id(), "Updated Cafe", "New hours", false);
        service.updateBranch(branch.id(), "99 South Street", "654321", false, "Moved downstairs");
        service.updateMenuItem(storage, 35_000, "Updated menu note **here**");

        assertEquals("$y$", service.note("participant", Long.toString(person.id())));
        assertEquals("New hours", service.note("eatery", Long.toString(eatery.id())));
        assertEquals("Moved downstairs", service.note("branch", Long.toString(branch.id())));
        assertEquals("Updated menu note **here**", service.menu(branch.id()).getFirst().note());
        assertTrue(service.branches(true).stream().anyMatch(row -> row.id() == branch.id()
                && row.address().equals("99 South Street") && row.phoneNumber().equals("654321")));
    }

    @Test
    void duplicateNamesRequireConfirmationAndReceiveTheNextSuffix() throws Exception {
        assertThrows(DuplicateNameException.class, () -> service.createParticipant("Friend One", false));
        Participant duplicate = service.createParticipant("Friend One", true);
        assertEquals("Friend One 2", duplicate.name());
    }

    @Test
    void searchSupportsBooleanTermsIdsTagsDatesAndStructuredFilters() throws Exception {
        String purchaseId = service.recordPurchase(branchId, friend.id(), owner.id(),
                List.of(new PurchaseLine(storageId, 1)), "Dinner note");
        service.setBranchPreference(friend.id(), branchId, "WHITELIST");
        service.setTags("participant", Long.toString(friend.id()), List.of("Close Friends", "close friends"));
        assertEquals(List.of("Close Friends"), service.tags("PARTICIPANT", Long.toString(friend.id())));

        SearchService search = new SearchService(database);
        assertTrue(search.search("participant:Friend One").stream().anyMatch(result -> result.id().equals(Long.toString(friend.id()))));
        assertTrue(search.search("participant:@1").stream().anyMatch(result -> result.id().equals("1")));
        assertTrue(search.search("participant:#Close").stream().anyMatch(result -> result.id().equals(Long.toString(friend.id()))));
        assertTrue(search.search("participant-branch:Friend").stream().anyMatch(result ->
                result.id().equals(friend.id() + ":" + branchId)));
        assertTrue(search.search("item-branch:$>=25000").stream().anyMatch(result -> result.id().equals(storageId)));
        assertTrue(search.search("transaction:status:UNPAID").stream().anyMatch(result -> result.id().equals(purchaseId)));
        assertTrue(search.search("transaction:Friend One AND status:UNPAID").stream()
                .anyMatch(result -> result.id().equals(purchaseId)));
        assertTrue(search.search("transaction:date:" + transaction(purchaseId).date()).stream()
                .anyMatch(result -> result.id().equals(purchaseId)));
        assertTrue(search.search("participant:Friend OR TrecKo").size() >= 2);
        assertThrows(IllegalArgumentException.class, () -> search.search("transaction:date:not-a-date"));
        service.setTags("PARTICIPANT", Long.toString(friend.id()), List.of());
        assertTrue(service.tags("PARTICIPANT", Long.toString(friend.id())).isEmpty());
    }

    @Test
    void databaseEnforcesMoneyAndForeignKeyConstraints() throws Exception {
        try (var statement = database.connection().prepareStatement("UPDATE storage SET price = -1 WHERE id = ?")) {
            statement.setString(1, storageId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        try (var statement = database.connection().prepareStatement("PRAGMA foreign_keys")) {
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void markdownPreviewEscapesRawHtmlAndKeepsLatexOffline() throws Exception {
        String html = NoteRenderer.render("<script>alert(1)</script>\n\nBalance: $x + 1$",
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "trecko-katex"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("data-expr=\"x + 1\""));
        assertTrue(html.contains("katex.render"));
        assertTrue(html.contains("katex.min.js"));
        assertTrue(html.contains("background: #F8FAFC"));
        assertFalse(html.contains("background: #15171c"));
    }

    @Test
    void backupAndRestoreReplaceDataOnlyAfterIntegrityValidation(@TempDir java.nio.file.Path tempDirectory) throws Exception {
        database.close();
        var active = new Database(tempDirectory.resolve("active.db"));
        try {
            HomeService diskService = new HomeService(active);
            diskService.createProfile("Backup Owner");
            java.nio.file.Path invalid = tempDirectory.resolve("not-trecko.db");
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + invalid);
                 var statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 1");
            }
            assertThrows(SQLException.class, () -> active.restoreFrom(invalid));
            assertEquals(1, diskService.participants().size());
            java.nio.file.Path backup = tempDirectory.resolve("backup.db");
            active.backupTo(backup);
            diskService.createParticipant("Later Entry", false);
            active.restoreFrom(backup);
            assertEquals(1, diskService.participants().size());
        } finally {
            active.close();
            database = Database.inMemory();
        }
    }

    private Transaction transaction(String id) throws Exception {
        return service.transactions(branchId, true).stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }
}
