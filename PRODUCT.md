# Product

<!-- impeccable:product-schema 1 -->

## Platform

Desktop (JavaFX)

## Stack

Java 21, OpenJFX 21.0.6, Maven, SQLite JDBC, commonmark-java, and JUnit Jupiter as specified in the product brief.

## Users

Friends, families, and coworkers recording shared food purchases and repayments on one person's desktop.

## Product Purpose

Record who ate what, where, who paid, and the remaining debt between people at each restaurant branch. Success means users can enter purchases and repayments accurately and inspect their history and balances without losing audit data.

## Positioning

Branch-scoped debt tracking combines itemized purchase snapshots, manual invoice settlement, group bill splitting, and reversible repayment invalidation in a local SQLite database.

## Operating Context

The application is a local, single-user desktop tool. The owner profile is participant ID 1. Data is stored in `~/.treckko/treckko.db`; backups are selected by the user.

## Capabilities and Constraints

- Five primary areas: Transactions, People, Places, Items, and Branch Menu, with a global Branch Switcher.
- Catalog preferences support branch- and menu-item-level whitelist and blacklist.
- Money is integer VND (`long` in Java). A single SQLite connection serves the application lifecycle.
- Purchase items retain their unit-price snapshot. Split bills and multi-step changes must be atomic.
- Repayments are either independent balance offsets or linked invoice settlements. Repayment cancellation preserves the record as `INVALID`, records a reason, restores debt, and reopens linked invoices.
- Unpaid purchases can be deleted. Menu entries with transaction history are hidden instead of hard-deleted.
- SQLite schema changes use `PRAGMA user_version`; backup and restore are supported.
- Name-conflict behavior appends a numbered suffix after user confirmation.
- Inferred from the explicit brief where implementation details were open: branch names in the UI use eatery name plus address; equal split amounts are stored as explicit VND allocations instead of fabricated menu items, with any indivisible remainder assigned deterministically.

## Brand Commitments

The product is named TrecKo. The requested interface palette uses primary `#4F46E5`, success `#10B981`, danger `#EF4444`, and warning `#F59E0B`; text uses Segoe UI and monetary values use Consolas.

## Evidence on Hand

The source of product requirements is the user's TrecKo v1.0 specification. No production transaction data, logos, or image assets were supplied. Any sample data shown by the application must be clearly illustrative.

## Product Principles

- Preserve transaction history and make balance changes auditable.
- Make the current branch context visible and consistent across the application.
- Prefer precise, explicit invoice settlement over hidden automatic allocation.
- Keep financial arithmetic exact in integer VND.
- Keep user data local and provide backup and restore.

## Accessibility & Inclusion

Status must be communicated with text or symbols as well as color. Keyboard focus and clear validation feedback are required for dialogs and forms.
