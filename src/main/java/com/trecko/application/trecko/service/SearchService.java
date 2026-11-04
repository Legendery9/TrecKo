package com.trecko.application.trecko.service;

import com.trecko.application.trecko.database.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchService {
    public record SearchResult(String type, String id, String name, String detail) {
        @Override public String toString() { return type + " · " + name + " — " + detail; }
    }

    private static final Pattern TERMS = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final Pattern PRICE = Pattern.compile("^\\$([<>]=?|=)?(\\d+)$");
    private static final String TARGETS = "all|participant|people|eatery|places|branch|item|item-branch|participant-branch|transaction|transactions";

    private final Database database;

    public SearchService(Database database) {
        this.database = database;
    }

    public List<SearchResult> search(String input) throws SQLException {
        String query = input == null ? "" : input.strip();
        if (query.isEmpty()) return List.of();
        String target = "";
        int separator = query.indexOf(':');
        if (separator > 0) {
            String candidate = query.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            if (candidate.matches(TARGETS)) {
                target = candidate;
                query = query.substring(separator + 1).strip();
            }
        }
        if (query.isEmpty()) throw new IllegalArgumentException("Enter a search term after the target");

        Map<String, SearchResult> combined = new LinkedHashMap<>();
        for (String disjunction : query.split("(?i)\\s+OR\\s+|\\|\\|")) {
            Map<String, SearchResult> conjunction = new LinkedHashMap<>();
            boolean first = true;
            for (String part : disjunction.split("(?i)\\s+AND\\s+")) {
                Matcher matcher = TERMS.matcher(part);
                while (matcher.find()) {
                    String term = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
                    Map<String, SearchResult> matches = execute(target, term);
                    if (first) {
                        conjunction.putAll(matches);
                        first = false;
                    } else {
                        conjunction.keySet().retainAll(matches.keySet());
                    }
                }
            }
            if (!first) combined.putAll(conjunction);
        }
        return List.copyOf(combined.values());
    }

    private Map<String, SearchResult> execute(String target, String term) throws SQLException {
        List<SearchResult> results = new ArrayList<>();
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        if (term.startsWith("@")) {
            exactId(results, lowerTarget, term.substring(1));
        } else if (term.startsWith("#")) {
            searchTag(results, lowerTarget, term.substring(1));
        } else if (term.regionMatches(true, 0, "status:", 0, 7)) {
            searchStatus(results, lowerTarget, term.substring(7));
        } else if (term.regionMatches(true, 0, "date:", 0, 5)) {
            searchDate(results, lowerTarget, term.substring(5));
        } else if (term.startsWith("$")) {
            searchPrice(results, lowerTarget, term);
        } else if (term.regionMatches(true, 0, "allow:", 0, 6)) {
            searchAllow(results, lowerTarget, term.substring(6));
        } else {
            searchText(results, lowerTarget, term);
        }
        Map<String, SearchResult> indexed = new LinkedHashMap<>();
        for (SearchResult result : results) indexed.put(result.type() + ":" + result.id(), result);
        return indexed;
    }

    private void searchText(List<SearchResult> results, String target, String term) throws SQLException {
        switch (target) {
            case "", "all" -> {
                searchNames(results, "People", "SELECT id, name, 'Participant' detail FROM participant WHERE name LIKE ? COLLATE NOCASE", term);
                searchExact(results, "Places", """
                        SELECT b.id, e.name, 'Branch · ' || b.address detail FROM branch b
                        JOIN eatery e ON e.id = b.eatery_id WHERE e.name LIKE ? OR b.address LIKE ?
                        """, term);
                searchNames(results, "Places", "SELECT id, name, 'Eatery' detail FROM eatery WHERE name LIKE ? COLLATE NOCASE", term);
                searchNames(results, "Items", "SELECT id, name, 'Item' detail FROM item WHERE name LIKE ? COLLATE NOCASE AND is_active = 1", term);
                searchExact(results, "Transactions", """
                        SELECT t.id, p.name, t.type || ' · ' || t.status detail FROM "transaction" t
                        JOIN participant p ON p.id = t.participant_id
                        WHERE p.name LIKE ? COLLATE NOCASE OR t.note LIKE ? COLLATE NOCASE
                        """, term);
            }
            case "participant", "people" -> searchNames(results, "People",
                    "SELECT id, name, 'Participant' detail FROM participant WHERE name LIKE ? COLLATE NOCASE", term);
            case "eatery", "places" -> searchNames(results, "Places",
                    "SELECT id, name, 'Eatery' detail FROM eatery WHERE name LIKE ? COLLATE NOCASE", term);
            case "branch" -> searchNames(results, "Places", """
                    SELECT b.id, e.name, 'Branch · ' || b.address detail FROM branch b JOIN eatery e ON e.id = b.eatery_id
                    WHERE e.name LIKE ? COLLATE NOCASE OR b.address LIKE ? COLLATE NOCASE
                    """, term);
            case "item" -> searchNames(results, "Items",
                    "SELECT id, name, 'Item' detail FROM item WHERE name LIKE ? COLLATE NOCASE AND is_active = 1", term);
            case "item-branch" -> searchNames(results, "Menu", """
                    SELECT s.id, i.name, e.name || ' · ' || b.address detail
                    FROM storage s JOIN item i ON i.id = s.item_id JOIN branch b ON b.id = s.branch_id
                    JOIN eatery e ON e.id = b.eatery_id
                    WHERE i.name LIKE ? COLLATE NOCASE AND s.is_active = 1
                    """, term);
            case "participant-branch" -> searchNames(results, "Participant · Branch", """
                    SELECT p.id || ':' || b.id, p.name, e.name || ' · ' || b.address || ' · ' || pref.kind detail
                    FROM participant_branch_pref pref JOIN participant p ON p.id = pref.participant_id
                    JOIN branch b ON b.id = pref.branch_id JOIN eatery e ON e.id = b.eatery_id
                    WHERE p.name LIKE ? COLLATE NOCASE OR e.name LIKE ? COLLATE NOCASE OR b.address LIKE ? COLLATE NOCASE
                    """, term);
            case "transaction", "transactions" -> searchNames(results, "Transactions", """
                    SELECT t.id, p.name, t.type || ' · ' || t.status || ' · ' || t.date detail
                    FROM "transaction" t JOIN participant p ON p.id = t.participant_id
                    WHERE p.name LIKE ? COLLATE NOCASE OR t.note LIKE ? COLLATE NOCASE
                    """, term);
            default -> throw new IllegalArgumentException("Unknown search target: " + target);
        }
    }

    private void exactId(List<SearchResult> results, String target, String id) throws SQLException {
        if (id.isBlank()) throw new IllegalArgumentException("Enter an ID after @");
        switch (target) {
            case "", "all", "participant", "people" -> searchExact(results, "People",
                    "SELECT id, name, 'Participant' detail FROM participant WHERE CAST(id AS TEXT) = ?", id);
            case "eatery", "places" -> searchExact(results, "Places",
                    "SELECT id, name, 'Eatery' detail FROM eatery WHERE CAST(id AS TEXT) = ?", id);
            case "branch" -> searchExact(results, "Places", """
                    SELECT b.id, e.name, 'Branch · ' || b.address detail FROM branch b JOIN eatery e ON e.id = b.eatery_id
                    WHERE CAST(b.id AS TEXT) = ?
                    """, id);
            case "item" -> searchExact(results, "Items", "SELECT id, name, 'Item' detail FROM item WHERE id = ?", id);
            case "item-branch" -> searchExact(results, "Menu", """
                    SELECT s.id, i.name, e.name || ' · ' || b.address detail
                    FROM storage s JOIN item i ON i.id = s.item_id JOIN branch b ON b.id = s.branch_id
                    JOIN eatery e ON e.id = b.eatery_id WHERE s.id = ?
                    """, id);
            case "participant-branch" -> searchExact(results, "Participant · Branch", """
                    SELECT p.id || ':' || b.id, p.name, e.name || ' · ' || b.address || ' · ' || pref.kind detail
                    FROM participant_branch_pref pref JOIN participant p ON p.id = pref.participant_id
                    JOIN branch b ON b.id = pref.branch_id JOIN eatery e ON e.id = b.eatery_id
                    WHERE p.id || ':' || b.id = ?
                    """, id);
            case "transaction", "transactions" -> searchExact(results, "Transactions", """
                    SELECT t.id, p.name, t.type || ' · ' || t.status || ' · ' || t.date detail
                    FROM "transaction" t JOIN participant p ON p.id = t.participant_id WHERE t.id = ?
                    """, id);
            default -> throw new IllegalArgumentException("Unknown search target: " + target);
        }
        if (target.isEmpty() || target.equals("all")) {
            exactId(results, "branch", id);
            exactId(results, "item", id);
            exactId(results, "item-branch", id);
            exactId(results, "transaction", id);
            exactId(results, "eatery", id);
        }
    }

    private void searchTag(List<SearchResult> results, String target, String tag) throws SQLException {
        if (tag.isBlank()) throw new IllegalArgumentException("Enter a tag name after #");
        if (target.isEmpty() || target.equals("all") || target.equals("participant") || target.equals("people")) {
            searchNames(results, "People", """
                    SELECT p.id, p.name, '#' || t.name detail FROM participant_tag pt
                    JOIN participant p ON p.id = pt.participant_id JOIN tag t ON t.id = pt.tag_id
                    WHERE t.scope = 'PARTICIPANT' AND t.name LIKE ? COLLATE NOCASE
                    """, tag);
        }
        if (target.isEmpty() || target.equals("all") || target.equals("eatery") || target.equals("places")) {
            searchExact(results, "Places", """
                    SELECT e.id, e.name, '#' || t.name detail FROM eatery_tag et
                    JOIN eatery e ON e.id = et.eatery_id JOIN tag t ON t.id = et.tag_id
                    WHERE t.scope = 'EATERY' AND t.name LIKE ? COLLATE NOCASE
                    """, tag);
        }
        if (target.isEmpty() || target.equals("all") || target.equals("item")) {
            searchNames(results, "Items", """
                    SELECT i.id, i.name, '#' || t.name detail FROM item_tag it
                    JOIN item i ON i.id = it.item_id JOIN tag t ON t.id = it.tag_id
                    WHERE t.scope = 'ITEM' AND t.name LIKE ? COLLATE NOCASE
                    """, tag);
        }
        if (!(target.isEmpty() || target.matches("all|participant|people|eatery|places|item"))) {
            throw new IllegalArgumentException("Tags are not supported for target: " + target);
        }
    }

    private void searchStatus(List<SearchResult> results, String target, String status) throws SQLException {
        String clean = status.strip().toUpperCase(Locale.ROOT);
        if (!clean.matches("PAID|UNPAID|INVALID|ACTIVE|CLOSED")) {
            throw new IllegalArgumentException("Unknown status: " + status);
        }
        if (target.isEmpty() || target.matches("all|transaction|transactions")) {
            searchExact(results, "Transactions", """
                    SELECT t.id, p.name, t.type || ' · ' || t.status || ' · ' || t.date detail
                    FROM "transaction" t JOIN participant p ON p.id = t.participant_id WHERE t.status = ?
                    """, clean);
        } else if (target.equals("branch")) {
            searchNames(results, "Places", """
                    SELECT b.id, e.name, 'Branch · ' || b.address || ' · ' || b.status detail
                    FROM branch b JOIN eatery e ON e.id = b.eatery_id WHERE b.status = ?
                    """, clean);
        } else {
            throw new IllegalArgumentException("Status is not supported for target: " + target);
        }
    }

    private void searchDate(List<SearchResult> results, String target, String date) throws SQLException {
        if (!target.isEmpty() && !target.matches("all|transaction|transactions")) {
            throw new IllegalArgumentException("Date is not supported for target: " + target);
        }
        String clean;
        try {
            clean = LocalDate.parse(date).toString();
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD", failure);
        }
        searchExact(results, "Transactions", """
                SELECT t.id, p.name, t.type || ' · ' || t.status || ' · ' || t.date detail
                FROM "transaction" t JOIN participant p ON p.id = t.participant_id WHERE date(t.date) = ?
                """, clean);
    }

    private void searchPrice(List<SearchResult> results, String target, String expression) throws SQLException {
        if (!target.isEmpty() && !target.matches("all|item-branch")) {
            throw new IllegalArgumentException("Price is not supported for target: " + target);
        }
        Matcher matcher = PRICE.matcher(expression);
        if (!matcher.matches()) throw new IllegalArgumentException("Use price syntax such as $25000 or $>=25000");
        String operator = matcher.group(1) == null ? "=" : matcher.group(1);
        long amount;
        try {
            amount = Long.parseLong(matcher.group(2));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Price is outside the supported range", failure);
        }
        searchNames(results, "Menu", """
                SELECT s.id, i.name, e.name || ' · ' || b.address || ' · ' || s.price || ' VND' detail
                FROM storage s JOIN item i ON i.id = s.item_id JOIN branch b ON b.id = s.branch_id
                JOIN eatery e ON e.id = b.eatery_id WHERE s.is_active = 1 AND s.price %s
                """.formatted(operator + " ?"), amount);
    }

    private void searchAllow(List<SearchResult> results, String target, String value) throws SQLException {
        if (!target.isEmpty() && !target.matches("all|branch")) {
            throw new IllegalArgumentException("Shipping filter is not supported for target: " + target);
        }
        String clean = value.strip().toLowerCase(Locale.ROOT);
        if (!clean.matches("true|false")) throw new IllegalArgumentException("Use allow:true or allow:false");
        searchNames(results, "Places", """
                SELECT b.id, e.name, 'Branch · ' || b.address || ' · ' ||
                    CASE b.allow_ship WHEN 1 THEN 'allow shipping' ELSE 'no shipping' END detail
                FROM branch b JOIN eatery e ON e.id = b.eatery_id WHERE b.allow_ship = ?
                """, clean.equals("true") ? 1L : 0L);
    }

    private void searchNames(List<SearchResult> results, String type, String sql, Object term) throws SQLException {
        search(results, type, sql, term, false);
    }

    private void searchExact(List<SearchResult> results, String type, String sql, String term) throws SQLException {
        search(results, type, sql, term, true);
    }

    private void search(List<SearchResult> results, String type, String sql, Object term, boolean exact) throws SQLException {
        try (var statement = database.connection().prepareStatement(sql)) {
            String pattern = term instanceof String text && !exact ? "%" + text + "%" : null;
            int parameters = statement.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= parameters; i++) {
                if (term instanceof String) statement.setString(i, exact ? (String) term : pattern);
                else statement.setObject(i, term);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(new SearchResult(type, rows.getString(1), rows.getString(2), rows.getString(3)));
                }
            }
        }
    }
}
