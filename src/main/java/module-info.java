module com.trecko.application.trecko {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires org.commonmark;
    requires katex;

    opens com.trecko.application.trecko to javafx.fxml;
    exports com.trecko.application.trecko;
}