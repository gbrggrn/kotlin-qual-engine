module com.qualengine.qualengine {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;

    requires org.kordamp.bootstrapfx.core;

    opens com.qualengine.qualengine to javafx.fxml;
    exports com.qualengine.qualengine;
}