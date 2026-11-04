package com.trecko.application.trecko.service;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.domain.Models.*;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public final class HomeService {
    private final CatalogService catalog;
    private final TransactionService transactions;
    private final ProfileSettingsService profileSettings;
    private final SearchService search;

    public HomeService(Database database) {
        catalog = new CatalogService(database);
        transactions = new TransactionService(database);
        profileSettings = new ProfileSettingsService(database);
        search = new SearchService(database);
    }

    public boolean profileInitialized() throws SQLException { return catalog.profileInitialized(); }
    public Participant createProfile(String name) throws SQLException { return catalog.createProfile(name); }
    public Participant createProfile(String name, String note) throws SQLException { return catalog.createProfile(name, note); }
    public List<Participant> participants() throws SQLException { return catalog.participants(); }
    public List<Eatery> eateries() throws SQLException { return catalog.eateries(); }
    public List<Branch> branches(boolean activeOnly) throws SQLException { return catalog.branches(activeOnly); }
    public List<Item> items() throws SQLException { return catalog.items(); }
    public List<MenuItem> menu(long branchId) throws SQLException { return catalog.menu(branchId); }
    public List<MenuItem> menu(long branchId, long participantId) throws SQLException {
        return catalog.menu(branchId, participantId);
    }
    public ProfilePreferences profilePreferences(long participantId) throws SQLException {
        return catalog.profilePreferences(participantId);
    }
    public List<String> tags(String scope, String entityId) throws SQLException { return catalog.tags(scope, entityId); }
    public void setTags(String scope, String entityId, List<String> tags) throws SQLException {
        catalog.setTags(scope, entityId, tags);
    }
    public String participantNote(long participantId) throws SQLException { return catalog.participantNote(participantId); }
    public void updateParticipantNote(long participantId, String note) throws SQLException {
        catalog.updateParticipantNote(participantId, note);
    }
    public Participant createParticipant(String name, boolean allowDuplicate) throws SQLException {
        return catalog.createParticipant(name, allowDuplicate);
    }
    public Participant createParticipant(String name, String note, boolean allowDuplicate) throws SQLException {
        return catalog.createParticipant(name, note, allowDuplicate);
    }
    public Eatery createEatery(String name, boolean allowDuplicate) throws SQLException {
        return catalog.createEatery(name, allowDuplicate);
    }
    public Eatery createEatery(String name, String note, boolean allowDuplicate) throws SQLException {
        return catalog.createEatery(name, note, allowDuplicate);
    }
    public Branch createBranch(long eateryId, String address, String phone, boolean shipping) throws SQLException {
        return catalog.createBranch(eateryId, address, phone, shipping);
    }
    public Branch createBranch(long eateryId, String address, String phone, boolean shipping, String note) throws SQLException {
        return catalog.createBranch(eateryId, address, phone, shipping, note);
    }
    public Item createItem(String name, boolean allowDuplicate) throws SQLException { return catalog.createItem(name, allowDuplicate); }
    public Item createItem(String name, String note, boolean allowDuplicate) throws SQLException {
        return catalog.createItem(name, note, allowDuplicate);
    }
    public void deleteItem(String itemId) throws SQLException { catalog.deleteItem(itemId); }
    public void closeBranch(long branchId) throws SQLException { catalog.closeBranch(branchId); }
    public void deleteEatery(long eateryId) throws SQLException { catalog.deleteEatery(eateryId); }
    public void deleteParticipant(long participantId) throws SQLException { catalog.deleteParticipant(participantId); }
    public String addMenuItem(long branchId, String itemId, long price) throws SQLException {
        return catalog.addMenuItem(branchId, itemId, price);
    }
    public String addMenuItem(long branchId, String itemId, long price, String note) throws SQLException {
        return catalog.addMenuItem(branchId, itemId, price, note);
    }
    public void updateMenuPrice(String storageId, long price) throws SQLException { catalog.updateMenuPrice(storageId, price); }
    public void updateMenuItem(String storageId, long price, String note) throws SQLException {
        catalog.updateMenuItem(storageId, price, note);
    }
    public String note(String entity, String id) throws SQLException { return catalog.note(entity, id); }
    public void updateParticipant(long id, String name, String note, boolean allowDuplicate) throws SQLException {
        catalog.updateParticipant(id, name, note, allowDuplicate);
    }
    public void updateEatery(long id, String name, String note, boolean allowDuplicate) throws SQLException {
        catalog.updateEatery(id, name, note, allowDuplicate);
    }
    public void updateBranch(long id, String address, String phone, boolean shipping, String note) throws SQLException {
        catalog.updateBranch(id, address, phone, shipping, note);
    }
    public void removeMenuItem(String storageId) throws SQLException { catalog.removeMenuItem(storageId); }
    public void setBranchPreference(long participantId, long branchId, String kind) throws SQLException {
        catalog.setBranchPreference(participantId, branchId, kind);
    }
    public void setStoragePreference(long participantId, String storageId, String kind) throws SQLException {
        catalog.setStoragePreference(participantId, storageId, kind);
    }
    public String recordPurchase(long branchId, long eaterId, Long payerId, List<PurchaseLine> lines, String note)
            throws SQLException { return transactions.recordPurchase(branchId, eaterId, payerId, lines, note); }
    public List<String> recordSplitEqual(long branchId, long payerId, long total, List<Long> participants, String note)
            throws SQLException { return transactions.recordSplitEqual(branchId, payerId, total, participants, note); }
    public List<String> recordSplitByItems(long branchId, long payerId, List<SplitLine> splitLines, String note)
            throws SQLException { return transactions.recordSplitByItems(branchId, payerId, splitLines, note); }
    public String recordRepayment(long branchId, long receiverId, long payerId, long amount,
                                  List<String> invoices, String note) throws SQLException {
        return transactions.recordRepayment(branchId, receiverId, payerId, amount, invoices, note);
    }
    public void invalidateRepayment(String id, String reason) throws SQLException {
        transactions.invalidateRepayment(id, reason);
    }
    public void deleteUnpaidPurchase(String id) throws SQLException { transactions.deleteUnpaidPurchase(id); }
    public List<Transaction> transactions(long branchId, boolean descending) throws SQLException {
        return transactions.transactions(branchId, descending);
    }
    public List<Debt> debts(long branchId) throws SQLException { return transactions.debts(branchId); }
    public List<Transaction> unpaidPurchases(long branchId, long debtorId, long creditorId) throws SQLException {
        return transactions.unpaidPurchases(branchId, debtorId, creditorId);
    }
    public List<Relationship> relationship(long a, long b) throws SQLException {
        return profileSettings.relationship(a, b);
    }
    public List<String> transactionDetails(String id) throws SQLException { return transactions.details(id); }
    public long balanceBetween(long a, long b) throws SQLException { return profileSettings.balanceBetween(a, b); }
    public Settings settings() throws SQLException { return profileSettings.settings(); }
    public void updateSettings(long tolerance, boolean sortDescending) throws SQLException {
        profileSettings.updateSettings(tolerance, sortDescending);
    }
    public void backup(Path destination) throws SQLException, IOException { profileSettings.backup(destination); }
    public void restore(Path source) throws SQLException, IOException { profileSettings.restore(source); }
    public List<SearchService.SearchResult> search(String query) throws SQLException { return search.search(query); }
}
