package snakes;

import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

public class GameSetupController extends ScreenController{

    @FXML
    private ResourceBundle resources;

    @FXML
    private URL location;

    @FXML
    private TextField widthField;

    @FXML
    private TextField heightField;

    @FXML
    private TextField foodStaticField;

    @FXML
    private TextField foodPerPlayerField;

    @FXML
    private TextField stateDelayMsField;

    @FXML
    private TextField deadProbFoodField;

    @FXML
    private TextField pingDelayMsField;

    @FXML
    private TextField nodeTimeoutMsField;

    @FXML
    private Button createButton;

    @FXML
    private Button cancelButton;

    @FXML
    void initialize() {
        System.out.println("On setup");
        cancelButton.setOnAction(actionEvent -> setScreen("mainScene.fxml", actionEvent, 800,600));
        createButton.setOnAction(actionEvent -> {
            if (createGame()) setScreen("gameScene.fxml", actionEvent, 800,600);
        });
        widthField.setText(String.valueOf(getNode().getGameState().getConfig().getWidth()));
        heightField.setText(String.valueOf(getNode().getGameState().getConfig().getHeight()));
        foodStaticField.setText(String.valueOf(getNode().getGameState().getConfig().getFoodStatic()));
        foodPerPlayerField.setText(String.valueOf(getNode().getGameState().getConfig().getFoodPerPlayer()));
        stateDelayMsField.setText(String.valueOf(getNode().getGameState().getConfig().getStateDelayMs()));
        deadProbFoodField.setText(String.valueOf(getNode().getGameState().getConfig().getDeadFoodProb()));
        pingDelayMsField.setText(String.valueOf(getNode().getGameState().getConfig().getPingDelayMs()));
        nodeTimeoutMsField.setText(String.valueOf(getNode().getGameState().getConfig().getNodeTimeoutMs()));
    }

    private boolean createGame() {
        return getNode().createGame(
                Integer.parseInt(widthField.getText()),
                Integer.parseInt(heightField.getText()),
                Integer.parseInt(foodStaticField.getText()),
                Float.parseFloat(foodPerPlayerField.getText()),
                Integer.parseInt(stateDelayMsField.getText()),
                Float.parseFloat(deadProbFoodField.getText()),
                Integer.parseInt(pingDelayMsField.getText()),
                Integer.parseInt(nodeTimeoutMsField.getText()));
    }

    GameSetupController(Node node) {
        super(node);
    }

}