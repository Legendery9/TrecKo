package com.trecko.application.trecko;

import com.trecko.application.trecko.database.Database;
import com.trecko.application.trecko.service.HomeService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.web.WebView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;

public class HelloApplication extends Application {
    private Database database;

    @Override
    public void start(Stage stage) throws Exception {
        Path databasePath = Path.of(System.getProperty("user.home"), ".treckko", "treckko.db");
        database = new Database(databasePath);
        HomeService service = new HomeService(database);
        if (!service.profileInitialized() && !createProfile(service)) {
            database.close();
            database = null;
            stage.close();
            return;
        }

        FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("home-view.fxml"));
        Scene scene = new Scene(loader.load(), 1440, 900);
        var stylesheet = HelloApplication.class.getResource("treckko.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        HomeController controller = loader.getController();
        controller.configure(service, stage);
        controller.installShortcuts(scene);
        stage.setTitle("TrecKo — Local Debt Ledger");
        stage.setMinWidth(1080);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if (database != null) database.close();
    }

    private static boolean createProfile(HomeService service) throws Exception {
        while (true) {
            Dialog<ProfileDraft> dialog = new Dialog<>();
            styleDialog(dialog);
            dialog.setTitle("TrecKo · Hồ sơ chủ máy");
            dialog.setHeaderText("Tạo hồ sơ của bạn");
            TextField name = new TextField();
            name.setPromptText("Tên hiển thị · 2–60 ký tự");
            TextArea note = new TextArea();
            note.setPromptText("Ghi chú Markdown / LaTeX (không bắt buộc, tối đa 5.000 ký tự)");
            note.setWrapText(true);
            note.setPrefRowCount(4);
            note.setPrefColumnCount(42);
            Button preview = new Button("Xem trước");
            preview.setOnAction(event -> showNotePreview(note.getText()));
            dialog.getDialogPane().setContent(new VBox(9, new Label("TÊN HIỂN THỊ"), name,
                    new Label("GHI CHÚ"), preview, note));
            ButtonType save = new ButtonType("Tạo hồ sơ", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            dialog.setResultConverter(button -> button == save ? new ProfileDraft(name.getText(), note.getText()) : null);
            Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
            Runnable validate = () -> saveButton.setDisable(name.getText().strip().length() < 2
                    || name.getText().strip().length() > 60 || note.getText().length() > 5000);
            name.textProperty().addListener((obs, old, value) -> validate.run());
            note.textProperty().addListener((obs, old, value) -> validate.run());
            validate.run();
            dialog.getDialogPane().setPrefWidth(640);
            var value = dialog.showAndWait();
            if (value.isEmpty()) return false;
            try {
                service.createProfile(value.get().name, value.get().note);
                return true;
            } catch (IllegalArgumentException failure) {
                Alert alert = new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK);
                styleDialog(alert);
                alert.setTitle("Thông tin chưa hợp lệ");
                alert.showAndWait();
            }
        }
    }

    private static void showNotePreview(String source) {
        Dialog<Void> dialog = new Dialog<>();
        styleDialog(dialog);
        dialog.setTitle("Xem trước ghi chú");
        WebView preview = new WebView();
        preview.setPrefSize(620, 340);
        try {
            NoteRenderer.show(preview, source);
        } catch (IOException failure) {
            Alert alert = new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK);
            styleDialog(alert);
            alert.setTitle("Không thể hiển thị ghi chú");
            alert.showAndWait();
            return;
        }
        dialog.getDialogPane().setContent(preview);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static void styleDialog(javafx.scene.control.Dialog<?> dialog) {
        var stylesheet = HelloApplication.class.getResource("treckko.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
    }

    private record ProfileDraft(String name, String note) {}
}
