module com.example.ihatereadonlyfiles {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.desktop;


    opens com.example.ihatereadonlyfiles to javafx.fxml;
    exports com.example.ihatereadonlyfiles;
}