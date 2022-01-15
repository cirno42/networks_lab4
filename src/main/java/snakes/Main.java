package snakes;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;


public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        List<String> params = getParameters().getRaw();
        if (params.size() < 2) {
            System.out.println("(port, name) expected");
        }

        Node node = new Node(Integer.parseInt(params.get(0)), params.get(1));
        node.start();
        FXMLLoader loader = new FXMLLoader(getClass().getClassLoader().getResource("mainScene.fxml"));
        loader.setControllerFactory(c -> new MainSceneController(node));
        Parent root = (Parent) loader.load();
        primaryStage.setOnCloseRequest(windowEvent -> {
            System.out.println("Closing application...");
            node.exitGame();
            node.stopWork();
        });
        primaryStage.setTitle("Snake");
        primaryStage.setResizable(false);
        primaryStage.setScene(new Scene(root, 800, 600));
        primaryStage.show();
    }


    public static void main(String[] args) {
        launch(args);
    }
}
