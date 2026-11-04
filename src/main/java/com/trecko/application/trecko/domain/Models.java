package com.trecko.application.trecko.domain;

import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public record Participant(long id, String name, boolean profile) {
        @Override public String toString() { return name + (profile ? " (Bạn)" : ""); }
    }

    public record Eatery(long id, String name) {
        @Override public String toString() { return name; }
    }

    public record Branch(long id, long eateryId, String eateryName, String address, String phoneNumber,
                         String status, boolean allowShip) {
        @Override public String toString() { return eateryName + " · " + address; }
    }

    public record Item(String id, String name, long averagePrice, int branchCount) {
        @Override public String toString() { return name; }
    }

    public record MenuItem(String storageId, String itemId, String name, long price, String note, String preference) {
        @Override public String toString() { return name; }
    }

    public record PurchaseLine(String storageId, int quantity) {}

    public record Transaction(
            String id, String type, long branchId, long participantId, String participantName,
            Long payerId, String payerName, long amount, String date, String status, String note) {}

    public record Debt(String relationId, long participant1Id, long participant2Id,
                       String participant1Name, String participant2Name, long balance) {}

    public record SplitLine(long participantId, List<PurchaseLine> items) {}

    public record SplitShare(long participantId, long amount) {}

    public record Relationship(long branchId, String branchName, String transactionId, String type,
                               long participantId, Long payerId, String description, long amount, String date, String status) {}

    public record Settings(long tolerance, boolean sortDescending) {}

    public record ProfilePreferences(List<String> branches, List<String> items) {}

    public record EqualSplit(long total, List<Long> participantIds, long payerId) {}

    public record ItemSplit(Map<Long, List<PurchaseLine>> itemsByParticipant, long payerId) {}
}
