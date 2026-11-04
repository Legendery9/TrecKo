package com.trecko.application.trecko;

import com.trecko.application.trecko.domain.Models.*;
import com.trecko.application.trecko.domain.Models.MenuItem;
import com.trecko.application.trecko.service.CatalogService.DuplicateNameException;
import com.trecko.application.trecko.service.HomeService;
import com.trecko.application.trecko.service.SearchService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class HomeController {
    @FXML private BorderPane root;
    @FXML private ComboBox<Branch> branchSelector;
    @FXML private Label profileName;
    @FXML private TabPane tabs;

    private HomeService service;
    private Stage owner;
    private List<Participant> people = List.of();
    private List<Eatery> eateries = List.of();
    private List<Item> items = List.of();
    private List<Branch> branches = List.of();
    private long debtTolerance = 5_000;
    private TableView<Transaction> transactionTable;
    private TableView<Debt> debtTable;
    private TableView<Participant> peopleTable;
    private TableView<Eatery> eateryTable;
    private TableView<Branch> placesBranchTable;
    private TableView<Item> itemTable;
    private TableView<MenuItem> menuTable;

    @FXML
    private void initialize() {
        branchSelector.setConverter(converter(Branch::toString));
        branchSelector.setTooltip(new Tooltip("Đổi chi nhánh để cập nhật giao dịch và menu ở mọi tab."));
    }

    public void configure(HomeService service, Stage owner) throws SQLException {
        this.service = service;
        this.owner = owner;
        people = service.participants();
        profileName.setText(people.stream().filter(Participant::profile).findFirst().map(Participant::name).orElse("Hồ sơ"));
        buildTabs();
        refreshCatalog();
        branchSelector.setOnAction(event -> refreshBranchContent());
        if (!branchSelector.getItems().isEmpty()) branchSelector.getSelectionModel().selectFirst();
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> refreshSelectedTab());
    }

    public void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN), this::showPurchaseDialog);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN),
                () -> showRepaymentDialog(null));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), this::onSearch);
    }

    @FXML
    private void onBackupQuick() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Sao lưu CSDL TrecKo");
        chooser.setInitialFileName("trecko-backup-" + java.time.LocalDate.now() + ".db");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite database", "*.db"));
        var target = chooser.showSaveDialog(owner);
        if (target != null) runAction("Không thể sao lưu dữ liệu", () -> {
            service.backup(target.toPath());
            showInfo("Sao lưu hoàn tất", "Bản sao lưu được lưu tại:\n" + target.getAbsolutePath());
        });
    }

    @FXML
    private void onOpenSettings() {
        showSettings();
    }

    @FXML
    private void onSearch() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Tìm kiếm TrecKo");
        dialog.setHeaderText("Scopes: participant:, branch:, item-branch:, participant-branch:, transaction:\nAND / OR · @id · #tag · status: · date: · $>=price · allow:true");
        dialog.setContentText("Biểu thức tìm kiếm:");
        dialog.showAndWait().ifPresent(query -> runAction("Tìm kiếm thất bại", () -> {
            List<SearchService.SearchResult> results = service.search(query);
            ListView<SearchService.SearchResult> list = new ListView<>(FXCollections.observableArrayList(results));
            list.setPlaceholder(empty("Không có kết quả. Thử từ khóa hoặc phạm vi khác."));
            list.setPrefSize(650, 360);
            showContentDialog("Kết quả tìm kiếm · " + results.size(), list);
        }));
    }

    private void buildTabs() {
        tabs.getTabs().setAll(
                new Tab("Transactions", buildTransactions()),
                new Tab("People", buildPeople()),
                new Tab("Places", buildPlaces()),
                new Tab("Items", buildItems()),
                new Tab("Branch Menu", buildBranchMenu()));
    }

    private Node buildTransactions() {
        transactionTable = new TableView<>();
        transactionTable.setPlaceholder(empty("Chưa có giao dịch tại chi nhánh này. Ghi nhận bữa ăn hoặc chia hóa đơn để bắt đầu."));
        transactionTable.getColumns().setAll(
                column("NGÀY", row -> row.date(), 105),
                column("GIAO DỊCH", row -> row.type().equals("REPAYMENT") ? "Trả nợ" : row.participantName(), 145),
                column("NGƯỜI TRẢ / NGƯỜI ĂN", row -> row.payerName() == null ? "Tự thanh toán" : row.payerName(), 175),
                moneyColumn("SỐ TIỀN", row -> row.amount(), 145),
                transactionStatusColumn("TRẠNG THÁI", Transaction::status, 125));
        transactionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        transactionTable.setRowFactory(table -> {
            TableRow<Transaction> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) showTransaction(row.getItem());
            });
            return row;
        });

        debtTable = new TableView<>();
        debtTable.setPlaceholder(empty("Sổ nợ đang cân bằng. Mọi khoản nợ sẽ xuất hiện tại đây."));
        debtTable.getColumns().setAll(
                column("CẶP NGƯỜI", debt -> debt.participant1Name() + " / " + debt.participant2Name(), 175),
                column("AI ĐANG NỢ", this::debtDirection, 190),
                moneyColumn("SỐ DƯ", debt -> Math.abs(debt.balance()), 125));
        debtTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Button purchase = primary("＋  Giao dịch mới", this::showPurchaseDialog);
        Button split = quiet("Chia tiền nhóm", this::showSplitDialog);
        Button repayment = quiet("Ghi nhận trả nợ", () -> showRepaymentDialog(null));
        Button details = quiet("Chi tiết", () -> Optional.ofNullable(transactionTable.getSelectionModel().getSelectedItem()).ifPresent(this::showTransaction));
        Button delete = danger("Xóa hóa đơn chưa trả", this::deleteSelectedPurchase);
        Button invalidate = danger("Vô hiệu hóa trả nợ", this::invalidateSelectedRepayment);
        transactionTable.getSelectionModel().selectedItemProperty().addListener((obs, old, tx) -> {
            delete.setDisable(tx == null || !tx.type().equals("PURCHASE") || !tx.status().equals("UNPAID"));
            delete.setTooltip(new Tooltip(tx == null ? "Chọn một hóa đơn trước." :
                    tx.type().equals("PURCHASE") && tx.status().equals("UNPAID")
                            ? "Xóa hóa đơn chưa thanh toán và hoàn tác số dư nợ."
                            : "Chỉ hóa đơn PURCHASE đang UNPAID được xóa."));
            invalidate.setDisable(tx == null || !tx.type().equals("REPAYMENT") || !tx.status().equals("PAID"));
            invalidate.setTooltip(new Tooltip(tx == null ? "Chọn một giao dịch trả nợ trước." :
                    tx.type().equals("REPAYMENT") && tx.status().equals("PAID")
                            ? "Vô hiệu hóa và hoàn tác khoản trả nợ này."
                            : "Chỉ REPAYMENT đang PAID có thể vô hiệu hóa."));
        });
        delete.setDisable(true);
        invalidate.setDisable(true);

        HBox actions = actionRow(purchase, split, repayment, details, delete, invalidate);
        VBox history = section("LỊCH SỬ GIAO DỊCH", transactionTable);
        VBox ledger = section("SỔ CÔNG NỢ", debtTable);
        SplitPane splitPane = new SplitPane(history, ledger);
        splitPane.setDividerPositions(0.68);
        VBox page = page("Transactions", "Giao dịch và số dư chỉ hiển thị trong chi nhánh đang chọn.", actions, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        purchase.disableProperty().bind(branchSelector.valueProperty().isNull());
        split.disableProperty().bind(branchSelector.valueProperty().isNull());
        repayment.disableProperty().bind(branchSelector.valueProperty().isNull());
        purchase.setTooltip(new Tooltip("Chọn chi nhánh trên thanh trên cùng để ghi giao dịch."));
        split.setTooltip(new Tooltip("Chọn chi nhánh trên thanh trên cùng để chia hóa đơn."));
        repayment.setTooltip(new Tooltip("Chọn chi nhánh trên thanh trên cùng để ghi trả nợ."));
        return page;
    }

    private Node buildPeople() {
        peopleTable = new TableView<>();
        peopleTable.setPlaceholder(empty("Chưa có thành viên. Thêm những người thường cùng ăn để ghi nhận công nợ."));
        peopleTable.getColumns().setAll(
                column("TÊN", Participant::name, 250),
                column("HỒ SƠ", person -> person.profile() ? "Bạn · Profile" : "Thành viên", 150));
        peopleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button add = primary("＋  Thêm người", this::createParticipant);
        Button profile = quiet("Mở hồ sơ & đối chiếu", () ->
                Optional.ofNullable(peopleTable.getSelectionModel().getSelectedItem()).ifPresent(this::showProfile));
        Button edit = quiet("Sửa người", this::editSelectedParticipant);
        Button tags = quiet("Gắn thẻ", () -> Optional.ofNullable(peopleTable.getSelectionModel().getSelectedItem())
                .ifPresent(person -> editTags("PARTICIPANT", Long.toString(person.id()), person.name())));
        Button delete = danger("Xóa người chưa phát sinh giao dịch", () ->
                Optional.ofNullable(peopleTable.getSelectionModel().getSelectedItem()).ifPresent(person ->
                        confirm("Xóa " + person.name() + "?", "Chỉ người chưa có giao dịch mới được xóa.", () ->
                                runAction("Không thể xóa người", () -> { service.deleteParticipant(person.id()); refreshCatalog(); }))));
        profile.disableProperty().bind(peopleTable.getSelectionModel().selectedItemProperty().isNull());
        edit.disableProperty().bind(peopleTable.getSelectionModel().selectedItemProperty().isNull());
        tags.disableProperty().bind(peopleTable.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(peopleTable.getSelectionModel().selectedItemProperty().isNull());
        profile.setTooltip(new Tooltip("Chọn một thành viên để mở hồ sơ."));
        tags.setTooltip(new Tooltip("Chọn một thành viên để quản lý thẻ."));
        delete.setTooltip(new Tooltip("Chọn một thành viên; thành viên có lịch sử chỉ được giữ lại."));
        VBox page = page("People", "Hồ sơ người tham gia và lịch sử công nợ hai chiều.", actionRow(add, edit, profile, tags, delete), peopleTable);
        VBox.setVgrow(peopleTable, Priority.ALWAYS);
        return page;
    }

    private Node buildPlaces() {
        eateryTable = new TableView<>();
        eateryTable.setPlaceholder(empty("Chưa có quán ăn. Tạo một quán trước khi thêm chi nhánh."));
        eateryTable.getColumns().setAll(column("QUÁN ĂN", Eatery::name, 220));
        eateryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        placesBranchTable = new TableView<>();
        placesBranchTable.setPlaceholder(empty("Chọn quán hoặc thêm chi nhánh để bắt đầu."));
        placesBranchTable.getColumns().setAll(
                column("CHI NHÁNH", branch -> branch.address(), 260),
                column("TRẠNG THÁI", Branch::status, 100),
                column("GIAO HÀNG", branch -> branch.allowShip() ? "Có" : "Không", 90));
        placesBranchTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        eateryTable.getSelectionModel().selectedItemProperty().addListener((obs, old, eatery) -> {
            if (eatery != null) placesBranchTable.setItems(FXCollections.observableArrayList(
                    branches.stream().filter(branch -> branch.eateryId() == eatery.id()).toList()));
        });
        Button addEatery = primary("＋  Thêm quán", this::createEatery);
        Button editEatery = quiet("Sửa quán", this::editSelectedEatery);
        Button tags = quiet("Gắn thẻ quán", () -> Optional.ofNullable(eateryTable.getSelectionModel().getSelectedItem())
                .ifPresent(eatery -> editTags("EATERY", Long.toString(eatery.id()), eatery.name())));
        Button addBranch = quiet("Thêm chi nhánh", this::createBranch);
        Button editBranch = quiet("Sửa chi nhánh", this::editSelectedBranch);
        Button preference = quiet("♥ Yêu thích / ✕ Tránh", this::toggleBranchPreference);
        Button deleteEatery = danger("Xóa quán trống", this::deleteSelectedEatery);
        Button closeBranch = danger("Đóng / xóa chi nhánh", this::closeSelectedBranch);
        addBranch.disableProperty().bind(eateryTable.getSelectionModel().selectedItemProperty().isNull());
        editEatery.disableProperty().bind(eateryTable.getSelectionModel().selectedItemProperty().isNull());
        editBranch.disableProperty().bind(placesBranchTable.getSelectionModel().selectedItemProperty().isNull());
        tags.disableProperty().bind(eateryTable.getSelectionModel().selectedItemProperty().isNull());
        preference.disableProperty().bind(placesBranchTable.getSelectionModel().selectedItemProperty().isNull());
        deleteEatery.disableProperty().bind(eateryTable.getSelectionModel().selectedItemProperty().isNull());
        closeBranch.disableProperty().bind(placesBranchTable.getSelectionModel().selectedItemProperty().isNull());
        tags.setTooltip(new Tooltip("Chọn một quán để gắn thẻ."));
        addBranch.setTooltip(new Tooltip("Chọn quán ăn trước khi thêm chi nhánh."));
        preference.setTooltip(new Tooltip("Chọn chi nhánh để đặt trạng thái yêu thích hoặc tránh."));
        closeBranch.setTooltip(new Tooltip("Chọn chi nhánh để đóng hoặc xóa."));
        deleteEatery.setTooltip(new Tooltip("Chọn quán chưa có chi nhánh."));
        SplitPane split = new SplitPane(section("EATERIES", eateryTable), section("BRANCHES", placesBranchTable));
        split.setDividerPositions(0.40);
        VBox page = page("Places", "Quán ăn và chi nhánh; yêu thích chi nhánh này cho hồ sơ chủ máy.",
                actionRow(addEatery, editEatery, tags, addBranch, editBranch, preference, closeBranch, deleteEatery), split);
        VBox.setVgrow(split, Priority.ALWAYS);
        return page;
    }

    private Node buildItems() {
        itemTable = new TableView<>();
        itemTable.setPlaceholder(empty("Danh mục đang trống. Thêm món vào kho chung, sau đó gán món cho menu chi nhánh."));
        itemTable.getColumns().setAll(
                column("MÓN ĂN", Item::name, 300),
                moneyColumn("GIÁ TRUNG BÌNH", Item::averagePrice, 180),
                column("SỐ CHI NHÁNH", item -> Integer.toString(item.branchCount()), 150));
        itemTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button add = primary("＋  Thêm món", this::createItem);
        Button tags = quiet("Gắn thẻ", () -> Optional.ofNullable(itemTable.getSelectionModel().getSelectedItem())
                .ifPresent(item -> editTags("ITEM", item.id(), item.name())));
        Button delete = danger("Xóa / Ẩn món", this::deleteSelectedItem);
        tags.disableProperty().bind(itemTable.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(itemTable.getSelectionModel().selectedItemProperty().isNull());
        tags.setTooltip(new Tooltip("Chọn một món để quản lý thẻ."));
        delete.setTooltip(new Tooltip("Chọn một món để xóa hoặc ẩn khỏi danh mục."));
        VBox page = page("Items", "Danh mục món dùng chung; giá trung bình được tính từ menu đang hoạt động.", actionRow(add, tags, delete), itemTable);
        VBox.setVgrow(itemTable, Priority.ALWAYS);
        return page;
    }

    private Node buildBranchMenu() {
        menuTable = new TableView<>();
        menuTable.setPlaceholder(empty("Chi nhánh chưa có menu. Thêm món từ danh mục Items ở trên."));
        TableColumn<MenuItem, String> preferenceColumn = column("SỞ THÍCH CỦA BẠN", menu -> preferenceLabel(menu.preference()), 190);
        preferenceColumn.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-blacklist", "status-whitelist");
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    if (item.contains("Blacklist")) getStyleClass().add("status-blacklist");
                    if (item.contains("Whitelist")) getStyleClass().add("status-whitelist");
                }
            }
        });
        TableColumn<MenuItem, String> noteColumn = column("GHI CHÚ", MenuItem::note, 270);
        noteColumn.setCellFactory(ignored -> new TableCell<>() {
            private final Label content = new Label();
            private final Tooltip tooltip = new Tooltip();

            {
                content.setWrapText(true);
                content.setPrefWidth(220);
                widthProperty().addListener((obs, old, width) ->
                        content.setPrefWidth(Math.max(100, width.doubleValue() - 24)));
                setGraphic(content);
                setText(null);
                setTooltip(tooltip);
            }

            @Override protected void updateItem(String note, boolean empty) {
                super.updateItem(note, empty);
                content.setText(empty ? "" : note);
                tooltip.setText(note == null ? "" : note);
                setGraphic(empty || note == null || note.isBlank() ? null : content);
            }
        });
        menuTable.getColumns().setAll(
                column("MÓN TRONG MENU", MenuItem::name, 330),
                moneyColumn("GIÁ HIỆN TẠI", MenuItem::price, 180),
                noteColumn, preferenceColumn);
        menuTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        menuTable.setFixedCellSize(-1);
        menuTable.getStyleClass().add("menu-table");
        Button add = primary("＋  Thêm món vào menu", this::addMenuItem);
        Button price = quiet("Sửa món", this::editMenuPrice);
        Button remove = danger("Xóa khỏi menu", this::removeMenuItem);
        Button preference = quiet("♥ Yêu thích / ✕ Tránh", this::toggleStoragePreference);
        price.disableProperty().bind(menuTable.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(menuTable.getSelectionModel().selectedItemProperty().isNull());
        preference.disableProperty().bind(menuTable.getSelectionModel().selectedItemProperty().isNull());
        price.setTooltip(new Tooltip("Chọn món trong menu để sửa giá và ghi chú."));
        remove.setTooltip(new Tooltip("Chọn món để xóa hoặc ẩn khỏi menu chi nhánh."));
        preference.setTooltip(new Tooltip("Chọn món để đặt trạng thái yêu thích hoặc tránh."));
        VBox page = page("Branch Menu", "Giá bán và trạng thái sở thích của món trong chi nhánh đang chọn.",
                actionRow(add, price, remove, preference), menuTable);
        VBox.setVgrow(menuTable, Priority.ALWAYS);
        return page;
    }

    private void refreshCatalog() throws SQLException {
        people = service.participants();
        profileName.setText(people.stream().filter(Participant::profile).findFirst()
                .map(Participant::name).orElse("Hồ sơ"));
        eateries = service.eateries();
        branches = service.branches(true);
        items = service.items();
        Branch selected = branchSelector.getValue();
        branchSelector.setItems(FXCollections.observableArrayList(branches));
        if (selected != null) branchSelector.getItems().stream().filter(branch -> branch.id() == selected.id()).findFirst()
                .ifPresentOrElse(branchSelector.getSelectionModel()::select, branchSelector.getSelectionModel()::selectFirst);
        if (peopleTable != null) peopleTable.setItems(FXCollections.observableArrayList(people));
        if (eateryTable != null) eateryTable.setItems(FXCollections.observableArrayList(eateries));
        if (itemTable != null) itemTable.setItems(FXCollections.observableArrayList(items));
        refreshPlacesBranches();
        refreshBranchContent();
    }

    private void refreshPlacesBranches() {
        if (placesBranchTable == null) return;
        Eatery selected = eateryTable.getSelectionModel().getSelectedItem();
        if (selected == null && !eateries.isEmpty()) {
            eateryTable.getSelectionModel().selectFirst();
        } else if (selected != null) {
            eateryTable.getSelectionModel().select(eateries.stream().filter(e -> e.id() == selected.id()).findFirst().orElse(null));
        }
    }

    private void refreshBranchContent() {
        Branch branch = branchSelector == null ? null : branchSelector.getValue();
        if (branch == null) {
            if (transactionTable != null) transactionTable.getItems().clear();
            if (debtTable != null) debtTable.getItems().clear();
            if (menuTable != null) menuTable.getItems().clear();
            return;
        }
        runAction("Không thể tải dữ liệu chi nhánh", () -> {
            Settings settings = service.settings();
            debtTolerance = settings.tolerance();
            if (transactionTable != null) transactionTable.setItems(FXCollections.observableArrayList(
                    service.transactions(branch.id(), settings.sortDescending())));
            if (debtTable != null) debtTable.setItems(FXCollections.observableArrayList(service.debts(branch.id())));
            if (menuTable != null) menuTable.setItems(FXCollections.observableArrayList(service.menu(branch.id())));
        });
    }

    private void refreshSelectedTab() {
        if (tabs.getSelectionModel().getSelectedIndex() == 0) refreshBranchContent();
        if (tabs.getSelectionModel().getSelectedIndex() == 1) {
            runAction("Không thể tải danh sách người", () -> peopleTable.setItems(FXCollections.observableArrayList(service.participants())));
        }
        if (tabs.getSelectionModel().getSelectedIndex() == 2) {
            runAction("Không thể tải danh sách quán", () -> {
                eateries = service.eateries();
                branches = service.branches(false);
                eateryTable.setItems(FXCollections.observableArrayList(eateries));
                refreshPlacesBranches();
            });
        }
        if (tabs.getSelectionModel().getSelectedIndex() == 3) {
            runAction("Không thể tải danh mục món", () -> itemTable.setItems(FXCollections.observableArrayList(service.items())));
        }
        if (tabs.getSelectionModel().getSelectedIndex() == 4) refreshBranchContent();
    }

    private void createParticipant() {
        showNamedDialog("Thêm người tham gia", "TÊN NGƯỜI", "", "").ifPresent(draft ->
                createNamed("Không thể thêm người", draft, service::createParticipant, () -> refreshCatalog()));
    }

    private void createEatery() {
        showNamedDialog("Thêm quán ăn", "TÊN QUÁN", "", "").ifPresent(draft ->
                createNamed("Không thể thêm quán", draft, service::createEatery, () -> refreshCatalog()));
    }

    private void createItem() {
        showNamedDialog("Thêm món ăn", "TÊN MÓN", "", "").ifPresent(draft ->
                createNamed("Không thể thêm món", draft, service::createItem, () -> refreshCatalog()));
    }

    private <T> void createNamed(String errorTitle, NamedDraft draft, NamedCreator<T> creator, Refresh refresh) {
        try {
            try {
                creator.create(draft.name, draft.note, false);
            } catch (DuplicateNameException duplicate) {
                if (!confirmResult("Tên đã tồn tại", "Tạo tên mới là “" + duplicate.suggestedName() + "”?", () -> {})) return;
                creator.create(draft.name, draft.note, true);
            }
            refresh.run();
        } catch (Exception failure) {
            showError(errorTitle, failure);
        }
    }

    private void editSelectedParticipant() {
        Participant person = peopleTable.getSelectionModel().getSelectedItem();
        if (person == null) return;
        runAction("Không thể tải thông tin người", () -> {
            showNamedDialog("Sửa người", "TÊN NGƯỜI", person.name(),
                    service.note("participant", Long.toString(person.id()))).ifPresent(draft ->
                    updateNamed("Không thể cập nhật người", draft, person.id(),
                            service::updateParticipant, () -> refreshCatalog()));
        });
    }

    private void editSelectedEatery() {
        Eatery eatery = eateryTable.getSelectionModel().getSelectedItem();
        if (eatery == null) return;
        runAction("Không thể tải thông tin quán", () -> {
            showNamedDialog("Sửa quán", "TÊN QUÁN", eatery.name(),
                    service.note("eatery", Long.toString(eatery.id()))).ifPresent(draft ->
                    updateNamed("Không thể cập nhật quán", draft, eatery.id(),
                            service::updateEatery, () -> refreshCatalog()));
        });
    }

    private void updateNamed(String errorTitle, NamedDraft draft, long id, NamedUpdater updater, Refresh refresh) {
        try {
            try {
                updater.update(id, draft.name, draft.note, false);
            } catch (DuplicateNameException duplicate) {
                if (!confirmResult("Tên đã tồn tại", "Đổi tên thành “" + duplicate.suggestedName() + "”?", () -> {})) return;
                updater.update(id, draft.name, draft.note, true);
            }
            refresh.run();
        } catch (Exception failure) {
            showError(errorTitle, failure);
        }
    }

    private void createBranch() {
        Eatery eatery = eateryTable.getSelectionModel().getSelectedItem();
        if (eatery == null) return;
        showBranchDialog("Thêm chi nhánh", eatery, null, "").ifPresent(draft -> runAction("Không thể tạo chi nhánh", () -> {
            service.createBranch(eatery.id(), draft.address, draft.phone, draft.shipping, draft.note);
            refreshCatalog();
        }));
    }

    private void editSelectedBranch() {
        Branch branch = placesBranchTable.getSelectionModel().getSelectedItem();
        Eatery eatery = eateryTable.getSelectionModel().getSelectedItem();
        if (branch == null || eatery == null) return;
        runAction("Không thể tải thông tin chi nhánh", () ->
                showBranchDialog("Sửa chi nhánh", eatery, branch,
                        service.note("branch", Long.toString(branch.id()))).ifPresent(draft ->
                        runAction("Không thể cập nhật chi nhánh", () -> {
                            service.updateBranch(branch.id(), draft.address, draft.phone, draft.shipping, draft.note);
                            refreshCatalog();
                        })));
    }

    private Optional<BranchDraft> showBranchDialog(String title, Eatery eatery, Branch branch, String initialNote) {
        Dialog<BranchDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText("Chi nhánh · " + eatery.name());
        TextField address = new TextField(branch == null ? "" : branch.address());
        address.setPromptText("Địa chỉ đầy đủ · 4–255 ký tự");
        TextField phone = new TextField(branch == null ? "" : branch.phoneNumber());
        phone.setPromptText("Số điện thoại (không bắt buộc)");
        CheckBox shipping = new CheckBox("Có giao hàng");
        shipping.setSelected(branch != null && branch.allowShip());
        NoteInput note = noteInput(initialNote);
        GridPane form = formGrid();
        form.addRow(0, new Label("ĐỊA CHỈ"), address);
        form.addRow(1, new Label("ĐIỆN THOẠI"), phone);
        form.add(shipping, 1, 2);
        form.add(new Label("GHI CHÚ"), 0, 3);
        form.add(note.container, 1, 3);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save ? new BranchDraft(address.getText(), phone.getText(),
                shipping.isSelected(), note.text.getText()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        Runnable validate = () -> saveButton.setDisable(address.getText().strip().length() < 4
                || address.getText().strip().length() > 255 || phone.getText().length() > 20
                || note.text.getText().length() > 5000);
        address.textProperty().addListener((obs, old, value) -> validate.run());
        phone.textProperty().addListener((obs, old, value) -> validate.run());
        note.text.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        return dialog.showAndWait();
    }

    private void deleteSelectedEatery() {
        Eatery eatery = eateryTable.getSelectionModel().getSelectedItem();
        if (eatery == null) return;
        confirm("Xóa quán " + eatery.name() + "?", "Chỉ quán chưa có chi nhánh mới được xóa.", () ->
                runAction("Không thể xóa quán", () -> { service.deleteEatery(eatery.id()); refreshCatalog(); }));
    }

    private void closeSelectedBranch() {
        Branch branch = placesBranchTable.getSelectionModel().getSelectedItem();
        if (branch == null) return;
        confirm("Đóng hoặc xóa chi nhánh?", "Chi nhánh có menu hoặc hóa đơn sẽ được đóng; chi nhánh trống sẽ bị xóa.", () ->
                runAction("Không thể cập nhật chi nhánh", () -> {
                    service.closeBranch(branch.id());
                    refreshCatalog();
                }));
    }

    private void deleteSelectedItem() {
        Item item = itemTable.getSelectionModel().getSelectedItem();
        if (item == null) return;
        confirm("Xóa món " + item.name() + "?", "Món có lịch sử hóa đơn sẽ được ẩn để giữ nguyên dữ liệu cũ.", () ->
                runAction("Không thể xóa món", () -> { service.deleteItem(item.id()); refreshCatalog(); }));
    }

    private void toggleBranchPreference() {
        Branch branch = placesBranchTable.getSelectionModel().getSelectedItem();
        if (branch == null) return;
        ChoiceDialog<String> dialog = new ChoiceDialog<>("WHITELIST", List.of("WHITELIST", "BLACKLIST", "Xóa tùy chọn"));
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Sở thích chi nhánh");
        dialog.setHeaderText(branch.toString());
        dialog.setContentText("Áp dụng cho hồ sơ chủ máy:");
        dialog.showAndWait().ifPresent(value -> runAction("Không thể cập nhật sở thích", () -> {
            service.setBranchPreference(1, branch.id(), value.equals("Xóa tùy chọn") ? null : value);
            refreshCatalog();
        }));
    }

    private void addMenuItem() {
        Branch branch = branchSelector.getValue();
        if (branch == null || items.isEmpty()) {
            showInfo("Chưa có món", "Tạo món trong tab Items trước khi thêm vào menu.");
            return;
        }
        Dialog<MenuDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Thêm món vào menu");
        dialog.setHeaderText(branch.toString());
        ComboBox<Item> item = new ComboBox<>(FXCollections.observableArrayList(items));
        item.setConverter(converter(Item::name));
        item.setMaxWidth(Double.MAX_VALUE);
        TextField price = new TextField();
        price.setPromptText("Giá VND · ví dụ 45000");
        NoteInput note = noteInput("");
        GridPane form = formGrid();
        form.addRow(0, new Label("MÓN"), item);
        form.addRow(1, new Label("GIÁ"), price);
        form.add(new Label("GHI CHÚ"), 0, 2);
        form.add(note.container, 1, 2);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save && item.getValue() != null
                ? new MenuDraft(item.getValue(), parseMoneyOrNull(price.getText()), note.text.getText()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        Runnable validate = () -> updateSaveState(saveButton, item.getValue() != null
                && parseMoneyOrNull(price.getText()) != null && note.text.getText().length() <= 5000);
        item.valueProperty().addListener((obs, old, value) -> validate.run());
        price.textProperty().addListener((obs, old, value) -> validate.run());
        note.text.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        dialog.showAndWait().ifPresent(draft -> runAction("Không thể thêm món vào menu", () -> {
            service.addMenuItem(branch.id(), draft.item.id(), draft.price, draft.note);
            refreshCatalog();
        }));
    }

    private void editMenuPrice() {
        MenuItem menu = menuTable.getSelectionModel().getSelectedItem();
        if (menu == null) return;
        Dialog<MenuDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Sửa món trong menu");
        dialog.setHeaderText(menu.name());
        TextField price = new TextField(Long.toString(menu.price()));
        price.setPromptText("Giá VND");
        NoteInput note = noteInput(menu.note());
        GridPane form = formGrid();
        form.addRow(0, new Label("GIÁ HIỆN TẠI"), price);
        form.add(new Label("GHI CHÚ"), 0, 1);
        form.add(note.container, 1, 1);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save
                ? new MenuDraft(null, parseMoneyOrNull(price.getText()), note.text.getText()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        Runnable validate = () -> saveButton.setDisable(parseMoneyOrNull(price.getText()) == null
                || (parseMoneyOrNull(price.getText()) == menu.price() && note.text.getText().equals(menu.note()))
                || note.text.getText().length() > 5000);
        price.textProperty().addListener((obs, old, value) -> validate.run());
        note.text.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        dialog.showAndWait().ifPresent(draft -> runAction("Không thể sửa món trong menu", () -> {
            service.updateMenuItem(menu.storageId(), draft.price, draft.note);
            refreshCatalog();
        }));
    }

    private void removeMenuItem() {
        MenuItem menu = menuTable.getSelectionModel().getSelectedItem();
        if (menu == null) return;
        confirm("Xóa " + menu.name() + " khỏi menu?", "Nếu đã xuất hiện trên hóa đơn, món sẽ được ẩn và giá cũ vẫn được giữ.", () ->
                runAction("Không thể xóa món khỏi menu", () -> { service.removeMenuItem(menu.storageId()); refreshCatalog(); }));
    }

    private void toggleStoragePreference() {
        MenuItem menu = menuTable.getSelectionModel().getSelectedItem();
        if (menu == null) return;
        ChoiceDialog<String> dialog = new ChoiceDialog<>("WHITELIST", List.of("WHITELIST", "BLACKLIST", "Xóa tùy chọn"));
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Sở thích món");
        dialog.setHeaderText(menu.name() + " · " + branchSelector.getValue());
        dialog.setContentText("Ưu tiên trực tiếp món tại chi nhánh:");
        dialog.showAndWait().ifPresent(value -> runAction("Không thể cập nhật sở thích", () -> {
            service.setStoragePreference(1, menu.storageId(), value.equals("Xóa tùy chọn") ? null : value);
            refreshBranchContent();
        }));
    }

    private void showPurchaseDialog() {
        Branch branch = branchSelector.getValue();
        if (branch == null) return;
        List<MenuItem> menu = safeMenu(branch.id());
        if (menu.isEmpty()) {
            showInfo("Menu trống", "Thêm món vào menu chi nhánh trước khi ghi nhận giao dịch.");
            return;
        }
        Dialog<PurchaseDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Giao dịch mới");
        dialog.setHeaderText("Branch · " + branch);
        ComboBox<Participant> eater = new ComboBox<>(FXCollections.observableArrayList(people));
        eater.setConverter(converter(Participant::toString));
        if (!people.isEmpty()) eater.getSelectionModel().selectFirst();
        CheckBox selfPaid = new CheckBox("Người ăn tự thanh toán");
        selfPaid.setSelected(true);
        ComboBox<Participant> payer = new ComboBox<>();
        payer.setConverter(converter(Participant::toString));
        payer.setDisable(true);
        selfPaid.selectedProperty().addListener((obs, old, selected) -> payer.setDisable(selected));
        AtomicReference<List<MenuItem>> eaterMenu = new AtomicReference<>(safeMenu(branch.id(),
                eater.getValue() == null ? 1 : eater.getValue().id()));
        ObservableList<PurchaseEntry> entries = FXCollections.observableArrayList();
        ListView<PurchaseEntry> lineList = new ListView<>(entries);
        lineList.setPrefHeight(145);
        lineList.setPlaceholder(empty("Chưa thêm món. Snapshot giá được lấy từ menu khi lưu."));
        Button addLine = quiet("Thêm món", () -> addPurchaseLine(eaterMenu.get(), entries));
        Button removeLine = quiet("Bỏ món đã chọn", () -> entries.remove(lineList.getSelectionModel().getSelectedItem()));
        TextArea note = new TextArea();
        note.setPromptText("Ghi chú Markdown / LaTeX (không bắt buộc)");
        note.setPrefRowCount(3);
        GridPane form = formGrid();
        form.addRow(0, new Label("NGƯỜI ĂN"), eater);
        form.addRow(1, selfPaid, payer);
        form.add(new Label("MÓN ĂN"), 0, 2);
        form.add(new VBox(7, lineList, actionRow(addLine, removeLine)), 1, 2);
        form.add(new Label("GHI CHÚ"), 0, 3);
        form.add(note, 1, 3);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save
                ? new PurchaseDraft(eater.getValue(), selfPaid.isSelected() ? null : payer.getValue(),
                        List.copyOf(entries), note.getText()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        Runnable validate = () -> saveButton.setDisable(eater.getValue() == null || entries.isEmpty()
                || (!selfPaid.isSelected() && (payer.getValue() == null || payer.getValue().id() == eater.getValue().id()))
                || note.getText().length() > 5000);
        Runnable updateEater = () -> {
            Participant selected = eater.getValue();
            payer.setItems(FXCollections.observableArrayList(people.stream()
                    .filter(person -> selected == null || person.id() != selected.id()).toList()));
            if (!payer.getItems().isEmpty()) payer.getSelectionModel().selectFirst();
            if (selected != null) eaterMenu.set(safeMenu(branch.id(), selected.id()));
            validate.run();
        };
        eater.valueProperty().addListener((obs, old, value) -> updateEater.run());
        updateEater.run();
        payer.valueProperty().addListener((obs, old, value) -> validate.run());
        selfPaid.selectedProperty().addListener((obs, old, value) -> validate.run());
        entries.addListener((javafx.collections.ListChangeListener<PurchaseEntry>) change -> validate.run());
        note.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        dialog.showAndWait().ifPresent(draft -> runAction("Không thể lưu giao dịch", () -> {
            List<PurchaseLine> lines = draft.entries.stream()
                    .map(entry -> new PurchaseLine(entry.item.storageId(), entry.quantity)).toList();
            service.recordPurchase(branch.id(), draft.eater.id(), draft.payer == null ? null : draft.payer.id(), lines, draft.note);
            refreshBranchContent();
        }));
    }

    private void addPurchaseLine(List<MenuItem> menu, ObservableList<PurchaseEntry> entries) {
        Dialog<PurchaseEntry> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Thêm món");
        ComboBox<MenuItem> item = new ComboBox<>(FXCollections.observableArrayList(menu));
        item.setConverter(converter(menuItem -> menuItem.name() + " · " + money(menuItem.price()) +
                (menuItem.preference() == null ? "" : " · " + preferenceLabel(menuItem.preference()))));
        item.setMaxWidth(Double.MAX_VALUE);
        Spinner<Integer> quantity = new Spinner<>(1, 999, 1);
        GridPane form = formGrid();
        form.addRow(0, new Label("MÓN"), item);
        form.addRow(1, new Label("SỐ LƯỢNG"), quantity);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save && item.getValue() != null
                ? new PurchaseEntry(item.getValue(), quantity.getValue()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        item.valueProperty().addListener((obs, old, value) -> saveButton.setDisable(value == null));
        dialog.showAndWait().ifPresent(entry -> {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).item.storageId().equals(entry.item.storageId())) {
                    PurchaseEntry existing = entries.get(i);
                    entries.set(i, new PurchaseEntry(existing.item, Math.addExact(existing.quantity, entry.quantity)));
                    return;
                }
            }
            entries.add(entry);
        });
    }

    private void showSplitDialog() {
        Branch branch = branchSelector.getValue();
        if (branch == null) return;
        List<MenuItem> menu = safeMenu(branch.id());
        if (menu.isEmpty() || people.isEmpty()) {
            showInfo("Chưa đủ dữ liệu", "Cần ít nhất một thành viên và một món trong menu.");
            return;
        }
        Dialog<SplitDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Chia tiền nhóm");
        dialog.setHeaderText("Lưu toàn bộ phần ăn trong một thao tác · " + branch);
        ComboBox<Participant> payer = new ComboBox<>(FXCollections.observableArrayList(people));
        payer.setConverter(converter(Participant::toString));
        payer.getSelectionModel().selectFirst();
        ToggleGroup modes = new ToggleGroup();
        RadioButton equalMode = new RadioButton("Chia đều tổng bill");
        RadioButton itemMode = new RadioButton("Chọn món cụ thể cho từng người");
        equalMode.setToggleGroup(modes);
        itemMode.setToggleGroup(modes);
        equalMode.setSelected(true);
        TextField total = new TextField();
        total.setPromptText("Tổng bill VND");
        VBox participantChecks = new VBox(7);
        Map<Long, CheckBox> checks = new HashMap<>();
        for (Participant person : people) {
            CheckBox check = new CheckBox(person.toString());
            checks.put(person.id(), check);
            participantChecks.getChildren().add(check);
        }
        ScrollPane attendeeScroll = new ScrollPane(participantChecks);
        attendeeScroll.setFitToWidth(true);
        attendeeScroll.setPrefHeight(125);
        VBox equalPane = new VBox(8, total, new Label("NGƯỜI CÙNG ĂN"), attendeeScroll);
        ObservableList<AssignedLine> assignments = FXCollections.observableArrayList();
        ListView<AssignedLine> assignedList = new ListView<>(assignments);
        assignedList.setPrefHeight(145);
        assignedList.setPlaceholder(empty("Chưa phân món. Mỗi dòng là món của một người."));
        Button addAssignment = quiet("Gán món", () -> addAssignment(branch, menu, assignments));
        Button removeAssignment = quiet("Bỏ dòng", () -> assignments.remove(assignedList.getSelectionModel().getSelectedItem()));
        VBox itemPane = new VBox(8, assignedList, actionRow(addAssignment, removeAssignment));
        itemPane.setVisible(false);
        itemPane.setManaged(false);
        equalMode.selectedProperty().addListener((obs, old, value) -> {
            equalPane.setVisible(value);
            equalPane.setManaged(value);
            itemPane.setVisible(!value);
            itemPane.setManaged(!value);
        });
        VBox content = new VBox(12, new Label("NGƯỜI TRẢ TIỀN"), payer, new HBox(18, equalMode, itemMode), equalPane, itemPane);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> {
            if (button != save) return null;
            if (equalMode.isSelected()) {
                List<Long> selected = checks.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
                return new SplitDraft(true, parseMoneyOrNull(total.getText()), selected, payer.getValue().id(), List.of());
            }
            Map<Long, List<PurchaseLine>> grouped = new LinkedHashMap<>();
            for (AssignedLine entry : assignments) {
                grouped.computeIfAbsent(entry.person.id(), ignored -> new ArrayList<>())
                        .add(new PurchaseLine(entry.item.storageId(), entry.quantity));
            }
            List<SplitLine> splitLines = grouped.entrySet().stream().map(entry -> new SplitLine(entry.getKey(), entry.getValue())).toList();
            return new SplitDraft(false, null, List.of(), payer.getValue().id(), splitLines);
        });
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        Runnable validate = () -> {
            boolean valid = payer.getValue() != null && (equalMode.isSelected()
                    ? parseMoneyOrNull(total.getText()) != null && parseMoneyOrNull(total.getText()) > 0
                        && checks.values().stream().anyMatch(CheckBox::isSelected)
                    : !assignments.isEmpty());
            saveButton.setDisable(!valid);
        };
        saveButton.setDisable(true);
        payer.valueProperty().addListener((obs, old, value) -> validate.run());
        total.textProperty().addListener((obs, old, value) -> validate.run());
        checks.values().forEach(check -> check.selectedProperty().addListener((obs, old, value) -> validate.run()));
        assignments.addListener((javafx.collections.ListChangeListener<AssignedLine>) change -> validate.run());
        modes.selectedToggleProperty().addListener((obs, old, value) -> validate.run());
        dialog.showAndWait().ifPresent(draft -> runAction("Không thể lưu chia tiền nhóm", () -> {
            if (draft.equal) service.recordSplitEqual(branch.id(), draft.payerId, draft.total, draft.participants, "");
            else service.recordSplitByItems(branch.id(), draft.payerId, draft.lines, "");
            refreshBranchContent();
        }));
    }

    private void addAssignment(Branch branch, List<MenuItem> menu, ObservableList<AssignedLine> assignments) {
        Dialog<AssignedLine> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Gán món cho người ăn");
        dialog.setHeaderText("Snapshot giá từ menu · " + branch);
        ComboBox<Participant> person = new ComboBox<>(FXCollections.observableArrayList(people));
        person.setConverter(converter(Participant::toString));
        ComboBox<MenuItem> item = new ComboBox<>(FXCollections.observableArrayList(menu));
        item.setConverter(converter(entry -> entry.name() + " · " + money(entry.price()) +
                (entry.preference() == null ? "" : " · " + preferenceLabel(entry.preference()))));
        Spinner<Integer> quantity = new Spinner<>(1, 999, 1);
        person.setMaxWidth(Double.MAX_VALUE);
        item.setMaxWidth(Double.MAX_VALUE);
        GridPane form = formGrid();
        form.addRow(0, new Label("NGƯỜI ĂN"), person);
        form.addRow(1, new Label("MÓN"), item);
        form.addRow(2, new Label("SỐ LƯỢNG"), quantity);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save && person.getValue() != null && item.getValue() != null
                ? new AssignedLine(person.getValue(), item.getValue(), quantity.getValue()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setDisable(true);
        person.valueProperty().addListener((obs, old, value) -> saveButton.setDisable(value == null || item.getValue() == null));
        item.valueProperty().addListener((obs, old, value) -> saveButton.setDisable(value == null || person.getValue() == null));
        person.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                item.setItems(FXCollections.observableArrayList(safeMenu(branch.id(), value.id())));
                item.getSelectionModel().clearSelection();
            }
        });
        dialog.showAndWait().ifPresent(assignments::add);
    }

    private void showRepaymentDialog(Debt preset) {
        Branch branch = branchSelector.getValue();
        if (branch == null || people.size() < 2) return;
        Dialog<RepaymentDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Ghi nhận trả nợ");
        dialog.setHeaderText("Sổ công nợ · " + branch);
        ComboBox<Participant> receiver = new ComboBox<>(FXCollections.observableArrayList(people));
        ComboBox<Participant> payer = new ComboBox<>(FXCollections.observableArrayList(people));
        receiver.setConverter(converter(Participant::toString));
        payer.setConverter(converter(Participant::toString));
        Participant initialReceiver = preset == null ? null : findPerson(preset.balance() > 0 ? preset.participant2Id() : preset.participant1Id());
        Participant initialPayer = preset == null ? null : findPerson(preset.balance() > 0 ? preset.participant1Id() : preset.participant2Id());
        if (initialReceiver == null) receiver.getSelectionModel().selectFirst(); else receiver.setValue(initialReceiver);
        if (initialPayer == null) payer.getSelectionModel().select(people.size() > 1 ? 1 : 0); else payer.setValue(initialPayer);
        TextField amount = new TextField();
        amount.setPromptText("Số tiền trả VND");
        RadioButton independent = new RadioButton("Bù trừ nợ độc lập");
        RadioButton linked = new RadioButton("Khấu trừ hóa đơn cụ thể");
        ToggleGroup modes = new ToggleGroup();
        independent.setToggleGroup(modes);
        linked.setToggleGroup(modes);
        independent.setSelected(true);
        VBox invoiceList = new VBox(6);
        ScrollPane invoiceScroll = new ScrollPane(invoiceList);
        invoiceScroll.setFitToWidth(true);
        invoiceScroll.setPrefHeight(155);
        invoiceScroll.setVisible(false);
        invoiceScroll.setManaged(false);
        Label due = new Label("Chọn hóa đơn cần thanh toán.");
        invoiceList.getChildren().add(due);
        List<CheckBox> invoiceChecks = new ArrayList<>();
        Button[] saveButtonRef = new Button[1];
        Runnable loadInvoices = () -> {
            invoiceChecks.clear();
            invoiceList.getChildren().clear();
            if (payer.getValue() == null || receiver.getValue() == null || payer.getValue().id() == receiver.getValue().id()) {
                invoiceList.getChildren().add(new Label("Chọn hai người khác nhau."));
            } else {
                runAction("Không thể tải hóa đơn chưa trả", () -> {
                    List<Transaction> unpaid = service.unpaidPurchases(branch.id(), payer.getValue().id(), receiver.getValue().id());
                    for (Transaction transaction : unpaid) {
                        CheckBox check = new CheckBox(transaction.date() + " · " + money(transaction.amount()) + " · " + transaction.id().substring(0, 8));
                        check.setUserData(transaction);
                        invoiceChecks.add(check);
                        invoiceList.getChildren().add(check);
                        check.selectedProperty().addListener((obs, old, value) -> {
                            if (saveButtonRef[0] != null) {
                                validateRepayment(amount, linked, invoiceChecks, saveButtonRef[0], receiver, payer);
                            }
                        });
                    }
                    if (unpaid.isEmpty()) invoiceList.getChildren().add(new Label("Không có hóa đơn UNPAID giữa hai người ở chi nhánh này."));
                });
            }
        };
        GridPane form = formGrid();
        form.addRow(0, new Label("NGƯỜI NHẬN"), receiver);
        form.addRow(1, new Label("NGƯỜI TRẢ"), payer);
        form.addRow(2, new Label("SỐ TIỀN"), amount);
        form.add(new HBox(18, independent, linked), 1, 3);
        form.add(invoiceScroll, 0, 4, 2, 1);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButtonRef[0] = saveButton;
        saveButton.setDisable(true);
        receiver.valueProperty().addListener((obs, old, value) -> { loadInvoices.run(); validateRepayment(amount, linked, invoiceChecks, saveButton, receiver, payer); });
        payer.valueProperty().addListener((obs, old, value) -> { loadInvoices.run(); validateRepayment(amount, linked, invoiceChecks, saveButton, receiver, payer); });
        amount.textProperty().addListener((obs, old, value) -> validateRepayment(amount, linked, invoiceChecks, saveButton, receiver, payer));
        modes.selectedToggleProperty().addListener((obs, old, value) -> {
            boolean show = linked.isSelected();
            invoiceScroll.setVisible(show);
            invoiceScroll.setManaged(show);
            validateRepayment(amount, linked, invoiceChecks, saveButton, receiver, payer);
        });
        dialog.setResultConverter(button -> {
            if (button != save) return null;
            List<String> invoiceIds = linked.isSelected()
                    ? invoiceChecks.stream().filter(CheckBox::isSelected)
                        .map(check -> (Transaction) check.getUserData()).filter(java.util.Objects::nonNull)
                        .map(Transaction::id).toList()
                    : List.of();
            return new RepaymentDraft(receiver.getValue(), payer.getValue(), parseMoneyOrNull(amount.getText()), invoiceIds);
        });
        loadInvoices.run();
        validateRepayment(amount, linked, invoiceChecks, saveButton, receiver, payer);
        dialog.showAndWait().ifPresent(draft -> runAction("Không thể ghi nhận trả nợ", () -> {
            service.recordRepayment(branch.id(), draft.receiver.id(), draft.payer.id(), draft.amount, draft.invoices, "");
            refreshBranchContent();
        }));
    }

    private void validateRepayment(TextField amountField, RadioButton linked, List<CheckBox> checks,
                                   Button button, ComboBox<Participant> receiver, ComboBox<Participant> payer) {
        Long amount = parseMoneyOrNull(amountField.getText());
        long selected = 0;
        for (CheckBox check : checks) {
            if (check.isSelected() && check.getUserData() instanceof Transaction invoice) {
                try { selected = Math.addExact(selected, invoice.amount()); }
                catch (ArithmeticException overflow) { button.setDisable(true); return; }
            }
        }
        boolean hasLinked = checks.stream().anyMatch(CheckBox::isSelected);
        button.setDisable(receiver.getValue() == null || payer.getValue() == null
                || receiver.getValue().id() == payer.getValue().id() || amount == null || amount <= 0
                || (linked.isSelected() && (!hasLinked || amount < selected)));
    }

    private void deleteSelectedPurchase() {
        Transaction selected = transactionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        confirm("Xóa hóa đơn chưa trả?", "Số dư công nợ sẽ được hoàn tác; hóa đơn đã PAID không thể xóa.", () ->
                runAction("Không thể xóa hóa đơn", () -> { service.deleteUnpaidPurchase(selected.id()); refreshBranchContent(); }));
    }

    private void invalidateSelectedRepayment() {
        Transaction selected = transactionTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Vô hiệu hóa giao dịch trả nợ");
        dialog.setHeaderText("Giao dịch sẽ giữ lại lịch sử và hoàn tác công nợ.");
        dialog.setContentText("Lý do (bắt buộc):");
        dialog.showAndWait().ifPresent(reason -> runAction("Không thể vô hiệu hóa giao dịch", () -> {
            service.invalidateRepayment(selected.id(), reason);
            refreshBranchContent();
        }));
    }

    private void showTransaction(Transaction transaction) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Transaction Detail");
        dialog.setHeaderText((transaction.type().equals("REPAYMENT") ? "Trả nợ" : "Hóa đơn") + " · " + transaction.date());
        VBox content = new VBox(10);
        content.getChildren().addAll(
                new Label("Người: " + transaction.participantName()),
                new Label("Người trả: " + (transaction.payerName() == null ? "Tự thanh toán" : transaction.payerName())),
                new Label("Số tiền: " + money(transaction.amount())),
                statusLabel(transaction.status()));
        ListView<String> details = new ListView<>();
        details.setPrefHeight(145);
        details.setPlaceholder(empty("Không có dòng chi tiết."));
        runAction("Không thể tải chi tiết giao dịch", () -> details.setItems(
                FXCollections.observableArrayList(service.transactionDetails(transaction.id()))));
        content.getChildren().add(details);
        if (transaction.note() != null && !transaction.note().isBlank()) {
            WebView note = new WebView();
            note.setPrefSize(650, 280);
            content.getChildren().add(note);
            try {
                NoteRenderer.show(note, transaction.note());
            } catch (IOException failure) {
                content.getChildren().add(new Label("Không thể tải bộ hiển thị Markdown/LaTeX: " + failure.getMessage()));
            }
        }
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void editTags(String scope, String id, String label) {
        List<String> current;
        try {
            current = service.tags(scope, id);
        } catch (SQLException failure) {
            showError("Không thể đọc thẻ", failure);
            return;
        }
        String original = String.join(", ", current);
        TextArea editor = new TextArea(original);
        editor.setPromptText("Ví dụ: Bạn thân, Hay gọi trà sữa");
        editor.setWrapText(true);
        editor.setPrefRowCount(3);

        Dialog<List<String>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Thẻ danh mục");
        dialog.setHeaderText(label);
        ButtonType save = new ButtonType("Lưu thẻ", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Phân tách thẻ bằng dấu phẩy hoặc xuống dòng (tối đa 30 ký tự mỗi thẻ)."),
                editor));
        Node saveButton = dialog.getDialogPane().lookupButton(save);
        saveButton.disableProperty().bind(editor.textProperty().isEqualTo(original));
        Tooltip.install(saveButton, new Tooltip("Thay đổi danh sách thẻ trước khi lưu."));
        dialog.setResultConverter(button -> button == save
                ? Arrays.stream(editor.getText().split("[,\\r\\n]")).map(String::strip).filter(name -> !name.isEmpty()).toList()
                : null);
        dialog.showAndWait().ifPresent(names -> runAction("Không thể lưu thẻ", () -> {
            service.setTags(scope, id, names);
            showInfo("Đã lưu thẻ", "Danh sách thẻ của " + label + " đã được cập nhật.");
        }));
    }

    private void showProfile(Participant person) {
        ProfilePreferences preferences;
        try {
            preferences = service.profilePreferences(person.id());
        } catch (SQLException failure) {
            showError("Không thể tải sở thích cá nhân", failure);
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Profile Inspector");
        dialog.setHeaderText(person.name() + (person.profile() ? " · Bạn" : ""));
        ComboBox<Participant> other = new ComboBox<>(FXCollections.observableArrayList(
                people.stream().filter(candidate -> candidate.id() != person.id()).toList()));
        other.setConverter(converter(Participant::toString));
        if (!other.getItems().isEmpty()) other.getSelectionModel().selectFirst();
        Label summary = new Label("Chọn người để tra cứu công nợ.");
        summary.getStyleClass().add("section-heading");
        TableView<Relationship> history = new TableView<>();
        history.setPrefSize(700, 300);
        history.setPlaceholder(empty("Chưa có lịch sử giữa hai người."));
        history.getColumns().setAll(
                column("NGÀY", Relationship::date, 100),
                column("CHI NHÁNH", Relationship::branchName, 185),
                column("NỘI DUNG", Relationship::description, 185),
                moneyColumn("SỐ TIỀN", Relationship::amount, 130),
                relationshipStatusColumn("TRẠNG THÁI", Relationship::status, 100));
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button settle = quiet("Đánh dấu hóa đơn đã trả", () -> {
            Relationship row = history.getSelectionModel().getSelectedItem();
            if (row == null || !row.type().equals("PURCHASE") || !row.status().equals("UNPAID") || row.payerId() == null) return;
            confirm("Đánh dấu hóa đơn đã trả?", "Sẽ tạo một giao dịch REPAYMENT được liên kết với hóa đơn.", () ->
                    runAction("Không thể thanh toán hóa đơn", () -> {
                        service.recordRepayment(row.branchId(), row.payerId(), row.participantId(),
                                row.amount(), List.of(row.transactionId()), "Thanh toán từ Profile Inspector");
                        refreshProfileHistory(person, other.getValue(), history, summary);
                    }));
        });
        history.getSelectionModel().selectedItemProperty().addListener((obs, old, row) ->
                settle.setDisable(row == null || !row.type().equals("PURCHASE")
                        || !row.status().equals("UNPAID") || row.payerId() == null));
        Button notes = quiet("Ghi chú Markdown / LaTeX", () -> showNoteEditor(person));
        settle.setDisable(true);
        settle.setTooltip(new Tooltip("Chọn một hóa đơn chưa trả có người thanh toán."));
        other.valueProperty().addListener((obs, old, value) -> refreshProfileHistory(person, value, history, summary));
        refreshProfileHistory(person, other.getValue(), history, summary);
        Label favoriteBranches = new Label(preferences.branches().isEmpty()
                ? "Chưa có chi nhánh yêu thích."
                : String.join("\n", preferences.branches()));
        favoriteBranches.setWrapText(true);
        Label favoriteItems = new Label(preferences.items().isEmpty()
                ? "Chưa có món yêu thích."
                : String.join("\n", preferences.items()));
        favoriteItems.setWrapText(true);
        VBox content = new VBox(10, notes,
                section("CHI NHÁNH YÊU THÍCH", favoriteBranches),
                section("MÓN YÊU THÍCH", favoriteItems),
                new Label("ĐỐI CHIẾU VỚI"), other, summary, history, settle);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void showNoteEditor(Participant person) {
        runAction("Không thể mở ghi chú", () -> {
            String original = service.participantNote(person.id());
            Dialog<String> dialog = new Dialog<>();
            dialog.initOwner(owner);
            styleDialog(dialog);
            dialog.setTitle("NoteEditor · Markdown + LaTeX offline");
            dialog.setHeaderText(person.name());
            TextArea editor = new TextArea(original);
            editor.setPromptText("Ghi chú Markdown, hỗ trợ công thức $...$ và $$...$$");
            editor.setWrapText(true);
            WebView preview = new WebView();
            preview.setPrefSize(580, 280);
            Button previewButton = quiet("Xem trước", () -> {
                try { NoteRenderer.show(preview, editor.getText()); }
                catch (IOException failure) { showError("Không thể hiển thị ghi chú offline", failure); }
            });
            HBox editorActions = actionRow(previewButton, new Label(editor.getText().length() + " / 5000"));
            editor.textProperty().addListener((obs, old, value) ->
                    ((Label) editorActions.getChildren().get(1)).setText(value.length() + " / 5000"));
            VBox content = new VBox(9, editorActions, new SplitPane(editor, preview));
            VBox.setVgrow(content.getChildren().get(1), Priority.ALWAYS);
            DialogPane pane = dialog.getDialogPane();
            ButtonType save = saveType();
            pane.getButtonTypes().addAll(save, ButtonType.CANCEL);
            pane.setContent(content);
            dialog.setResultConverter(button -> button == save ? editor.getText() : null);
            Button saveButton = (Button) pane.lookupButton(save);
            saveButton.setDisable(true);
            editor.textProperty().addListener((obs, old, value) ->
                    saveButton.setDisable(value.length() > 5000 || value.equals(original)));
            pane.setPrefSize(960, 520);
            dialog.setOnShown(event -> editor.requestFocus());
            dialog.showAndWait().ifPresent(note -> runAction("Không thể lưu ghi chú", () -> {
                service.updateParticipantNote(person.id(), note);
            }));
        });
    }

    private void refreshProfileHistory(Participant person, Participant other, TableView<Relationship> history, Label summary) {
        if (other == null) {
            history.getItems().clear();
            summary.setText("Chọn người để tra cứu công nợ.");
            return;
        }
        runAction("Không thể đối chiếu công nợ", () -> {
            long balance = service.balanceBetween(person.id(), other.id());
            if (balance > 0) summary.setText(person.name() + " đang nợ " + other.name() + ": " + money(balance));
            else if (balance < 0) summary.setText(other.name() + " đang nợ " + person.name() + ": " + money(Math.negateExact(balance)));
            else summary.setText("✓ Hai người đang cân bằng công nợ.");
            history.setItems(FXCollections.observableArrayList(service.relationship(person.id(), other.id())));
        });
    }

    private void showSettings() {
        runAction("Không thể tải cài đặt", () -> {
            Settings current = service.settings();
            Dialog<SettingsDraft> dialog = new Dialog<>();
            dialog.initOwner(owner);
            styleDialog(dialog);
            dialog.setTitle("Cài đặt TrecKo");
            dialog.setHeaderText("Ngưỡng cân bằng và thứ tự sổ giao dịch");
            TextField tolerance = new TextField(Long.toString(current.tolerance()));
            tolerance.setPromptText("VND");
            CheckBox descending = new CheckBox("Giao dịch mới nhất trước");
            descending.setSelected(current.sortDescending());
            GridPane form = formGrid();
            form.addRow(0, new Label("NGƯỠNG VND"), tolerance);
            form.add(descending, 1, 1);
            ButtonType save = saveType();
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            Button backup = new Button("Sao lưu…");
            Button restore = new Button("Khôi phục…");
            form.add(actionRow(backup, restore), 1, 2);
            backup.setOnAction(event -> onBackupQuick());
            restore.setOnAction(event -> restoreBackup());
            dialog.getDialogPane().setContent(form);
            dialog.setResultConverter(button -> button == save ? new SettingsDraft(parseMoneyOrNull(tolerance.getText()), descending.isSelected()) : null);
            Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
            Runnable validate = () -> {
                Long value = parseMoneyOrNull(tolerance.getText());
                saveButton.setDisable(value == null || value < 0
                        || (value == current.tolerance() && descending.isSelected() == current.sortDescending()));
            };
            saveButton.setDisable(true);
            tolerance.textProperty().addListener((obs, old, value) -> validate.run());
            descending.selectedProperty().addListener((obs, old, value) -> validate.run());
            dialog.showAndWait().ifPresent(draft -> runAction("Không thể lưu cài đặt", () -> {
                service.updateSettings(draft.tolerance, draft.sortDescending);
                refreshBranchContent();
            }));
        });
    }

    private void restoreBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn bản sao lưu TrecKo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite database", "*.db"));
        var file = chooser.showOpenDialog(owner);
        if (file != null) confirm("Khôi phục bản sao lưu?", "Dữ liệu hiện tại sẽ được thay thế. Tạo một bản sao lưu trước khi tiếp tục.", () ->
                runAction("Không thể khôi phục dữ liệu", () -> {
                    service.restore(file.toPath());
                    refreshCatalog();
                    showInfo("Khôi phục hoàn tất", "Dữ liệu đã được nạp lại từ bản sao lưu.");
                }));
    }

    private void showContentDialog(String title, Node content) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private List<MenuItem> safeMenu(long branchId) {
        try { return service.menu(branchId); }
        catch (SQLException failure) { showError("Không thể tải menu", failure); return List.of(); }
    }

    private List<MenuItem> safeMenu(long branchId, long participantId) {
        try { return service.menu(branchId, participantId); }
        catch (SQLException failure) { showError("Không thể tải sở thích món ăn", failure); return List.of(); }
    }

    private Participant findPerson(long id) {
        return people.stream().filter(person -> person.id() == id).findFirst().orElse(null);
    }

    private String debtDirection(Debt debt) {
        if (debt.balance() >= -debtTolerance && debt.balance() <= debtTolerance) return "✓ Cân bằng";
        return debt.balance() > 0 ? debt.participant1Name() + " nợ " + debt.participant2Name()
                : debt.participant2Name() + " nợ " + debt.participant1Name();
    }

    private static String preferenceLabel(String preference) {
        if ("BLACKLIST".equals(preference)) return "✕ Blacklist";
        if ("WHITELIST".equals(preference)) return "♥ Whitelist";
        return "—";
    }

    private static Label statusLabel(String status) {
        Label label = new Label(statusText(status));
        label.getStyleClass().add(statusClass(status));
        return label;
    }

    private static TableColumn<Transaction, String> transactionStatusColumn(String title, Function<Transaction, String> value, double width) {
        TableColumn<Transaction, String> column = column(title, value.andThen(HomeController::statusText), width);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-paid", "status-unpaid", "status-invalid", "status-settled");
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    getStyleClass().add(switch (item) {
                        case "✓ PAID" -> "status-paid";
                        case "! UNPAID" -> "status-unpaid";
                        case "✕ INVALID" -> "status-invalid";
                        default -> "";
                    });
                }
            }
        });
        return column;
    }

    private static TableColumn<Relationship, String> relationshipStatusColumn(String title, Function<Relationship, String> value, double width) {
        TableColumn<Relationship, String> column = column(title, value.andThen(HomeController::statusText), width);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-paid", "status-unpaid", "status-invalid");
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    getStyleClass().add(item.equals("✓ PAID") ? "status-paid" :
                            item.equals("! UNPAID") ? "status-unpaid" : "status-invalid");
                }
            }
        });
        return column;
    }

    private static String statusText(String status) {
        return switch (status) {
            case "PAID" -> "✓ PAID";
            case "UNPAID" -> "! UNPAID";
            case "INVALID" -> "✕ INVALID";
            default -> status;
        };
    }

    private static String statusClass(String status) {
        return switch (status) {
            case "PAID" -> "status-paid";
            case "UNPAID" -> "status-unpaid";
            case "INVALID" -> "status-invalid";
            default -> "";
        };
    }

    private static <T> TableColumn<T, String> column(String title, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static <T> TableColumn<T, String> moneyColumn(String title, Function<T, Long> value, double width) {
        TableColumn<T, String> column = column(title, row -> money(value.apply(row)), width);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!getStyleClass().contains("money-cell")) getStyleClass().add("money-cell");
            }
        });
        return column;
    }

    private static VBox page(String title, String subtitle, Node actions, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-heading");
        Label description = new Label(subtitle);
        description.getStyleClass().add("page-subtitle");
        VBox header = new VBox(5, heading, description);
        VBox page = new VBox(12, header, actions, content);
        page.getStyleClass().add("page");
        VBox.setVgrow(content, Priority.ALWAYS);
        return page;
    }

    private static VBox section(String title, Node content) {
        Label label = new Label(title);
        label.getStyleClass().addAll("section-heading", "section-rule");
        VBox section = new VBox(9, label, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return section;
    }

    private static HBox actionRow(Node... nodes) {
        HBox row = new HBox(9, nodes);
        row.getStyleClass().add("action-row");
        return row;
    }

    private static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new javafx.geometry.Insets(8, 4, 8, 4));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(120);
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, inputs);
        return grid;
    }

    private Optional<NamedDraft> showNamedDialog(String title, String nameLabel, String initialName, String initialNote) {
        Dialog<NamedDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        TextField name = new TextField(initialName);
        name.setPromptText("2–60 ký tự");
        NoteInput note = noteInput(initialNote);
        GridPane form = formGrid();
        form.addRow(0, new Label(nameLabel), name);
        form.add(new Label("GHI CHÚ"), 0, 1);
        form.add(note.container, 1, 1);
        ButtonType save = saveType();
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save
                ? new NamedDraft(name.getText(), note.text.getText()) : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        Runnable validate = () -> saveButton.setDisable(name.getText().strip().length() < 2
                || name.getText().strip().length() > 60 || note.text.getText().length() > 5000);
        name.textProperty().addListener((obs, old, value) -> validate.run());
        note.text.textProperty().addListener((obs, old, value) -> validate.run());
        validate.run();
        dialog.getDialogPane().setPrefWidth(640);
        return dialog.showAndWait();
    }

    private NoteInput noteInput(String initialNote) {
        TextArea text = new TextArea(initialNote);
        text.setPromptText("Ghi chú Markdown / LaTeX (tối đa 5.000 ký tự)");
        text.setWrapText(true);
        text.setPrefRowCount(4);
        text.setPrefColumnCount(42);
        Label count = new Label(text.getText().length() + " / 5000");
        text.textProperty().addListener((obs, old, value) -> count.setText(value.length() + " / 5000"));
        Button preview = quiet("Xem trước", () -> showNotePreview(text.getText()));
        VBox container = new VBox(6, actionRow(preview, count), text);
        container.setMaxWidth(Double.MAX_VALUE);
        return new NoteInput(container, text);
    }

    private void showNotePreview(String source) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle("Xem trước ghi chú");
        dialog.setHeaderText("Markdown + LaTeX · xử lý ngoại tuyến");
        WebView preview = new WebView();
        preview.setPrefSize(620, 340);
        try {
            NoteRenderer.show(preview, source);
        } catch (IOException failure) {
            showError("Không thể hiển thị ghi chú", failure);
            return;
        }
        dialog.getDialogPane().setContent(preview);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static Label empty(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("empty-guidance");
        label.setWrapText(true);
        return label;
    }

    private static Button primary(String label, Runnable action) {
        return button(label, "primary-button", action);
    }

    private static Button quiet(String label, Runnable action) {
        return button(label, "quiet-button", action);
    }

    private static Button danger(String label, Runnable action) {
        return button(label, "danger-button", action);
    }

    private static Button button(String label, String style, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static ButtonType saveType() {
        return new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
    }

    private static <T> StringConverter<T> converter(Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String value) { return null; }
        };
    }

    private static String money(long value) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " ₫";
    }

    private static Long parseMoneyOrNull(String value) {
        try { return parseMoney(value); }
        catch (RuntimeException failure) { return null; }
    }

    private static long parseMoney(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\s.,₫]", "");
        if (!clean.matches("\\d+")) throw new IllegalArgumentException("Enter a non-negative integer amount in VND");
        return Long.parseLong(clean);
    }

    private static void updateSaveState(Button button, boolean valid) {
        button.setDisable(!valid);
    }

    private void confirm(String title, String message, Runnable yesAction) {
        ButtonType yes = new ButtonType("Có", ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType("Không", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, no);
        alert.initOwner(owner);
        styleDialog(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        if (alert.showAndWait().orElse(no) == yes) yesAction.run();
    }

    private boolean confirmResult(String title, String message, Runnable yes) {
        ButtonType accept = new ButtonType("Có", ButtonBar.ButtonData.YES);
        ButtonType decline = new ButtonType("Không", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, accept, decline);
        alert.initOwner(owner);
        styleDialog(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        boolean accepted = alert.showAndWait().orElse(decline) == accept;
        if (accepted) yes.run();
        return accepted;
    }

    private Optional<String> inputText(String title, String prompt) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        styleDialog(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.setContentText(prompt);
        return dialog.showAndWait();
    }

    private void runAction(String title, SqlAction action) {
        try { action.run(); }
        catch (Exception failure) { showError(title, failure); }
    }

    private void showError(String title, Exception failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        styleDialog(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(owner);
        styleDialog(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static void styleDialog(Dialog<?> dialog) {
        var stylesheet = HomeController.class.getResource("treckko.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
    }

    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    @FunctionalInterface private interface Refresh { void run() throws Exception; }
    @FunctionalInterface private interface NamedCreator<T> { T create(String name, String note, boolean allowDuplicate) throws Exception; }
    @FunctionalInterface private interface NamedUpdater { void update(long id, String name, String note, boolean allowDuplicate) throws Exception; }

    private record NamedDraft(String name, String note) {}
    private record NoteInput(VBox container, TextArea text) {}
    private record BranchDraft(String address, String phone, boolean shipping, String note) {}
    private record MenuDraft(Item item, long price, String note) {}
    private record PurchaseEntry(MenuItem item, int quantity) {
        @Override public String toString() { return item.name() + " × " + quantity + " · " + money(item.price() * quantity); }
    }
    private record PurchaseDraft(Participant eater, Participant payer, List<PurchaseEntry> entries, String note) {}
    private record AssignedLine(Participant person, MenuItem item, int quantity) {
        @Override public String toString() { return person.name() + " · " + item.name() + " × " + quantity; }
    }
    private record SplitDraft(boolean equal, Long total, List<Long> participants, long payerId, List<SplitLine> lines) {}
    private record RepaymentDraft(Participant receiver, Participant payer, Long amount, List<String> invoices) {}
    private record SettingsDraft(Long tolerance, boolean sortDescending) {}
}
