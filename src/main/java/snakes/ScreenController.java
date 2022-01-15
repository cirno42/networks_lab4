package snakes;

import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;

public class ScreenController {
    void setScreen(String name, ActionEvent actionEvent, int width, int height) {
        FXMLLoader loader = new FXMLLoader(getClass().getClassLoader().getResource(name));
        loader.setControllerFactory(c -> factory(name));
        Parent root = null;
        try {
            root = (Parent) loader.load();
        } catch (IOException e) {
            e.printStackTrace();
        }

        assert root != null;
        Scene scene = new Scene(root, width, height);
        Stage stage = (Stage) ((javafx.scene.Node) actionEvent.getSource()).getScene().getWindow();
        stage.setScene(scene);
        stage.show();

    }

    private ScreenController factory(String name) {
        switch (name) {
            case "gameScene.fxml": {
                return new GameSceneController(node);
            }
            case "mainScene.fxml": {
                return new MainSceneController(node);
            }
            case "gameSetup.fxml": {
                return new GameSetupController(node);
            }
            default: return new GameSetupController(node);
        }
    }

    ScreenController(Node node) {
        this.node = node;
    }
    @Getter
    @Setter
    private Node node;
}