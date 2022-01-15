package snakes;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import snakes.proto.SnakesProto;

import java.net.SocketAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

public class MainSceneController extends ScreenController {

    @FXML
    private ResourceBundle resources;

    @FXML
    private URL location;

    @FXML
    private Button newGameButton;

    @FXML Button refreshButton;

    @FXML
    private HBox titlesList;

    @FXML
    private VBox gamesList;

    private Map<SocketAddress, HBox> gameRecords = new HashMap<>();

    @FXML
    void initialize() {

        newGameButton.setOnAction(actionEvent -> {
            setScreen("gameSetup.fxml", actionEvent, 300,600);
        });
        refreshButton.setOnAction(actionEvent -> update());
    }


    MainSceneController(Node node) {
        super(node);
    }


    private void update() {

        gameRecords.entrySet().removeIf(entry -> !getNode().getKnownGames().containsKey(entry.getKey()));


        getNode().getKnownGames().forEach((id, game) -> {
            String masterName = "Unknown";
            for (SnakesProto.GamePlayer player: game.getPlayers().getPlayersList()) {
                if (player.getRole().equals(SnakesProto.NodeRole.MASTER)) {
                    masterName = player.getName();
                    break;
                }
            }
            gameRecords.put(id, constructGameRecord(
                    id,
                    masterName,
                    game.getPlayers().getPlayersCount(),
                    game.getConfig().getWidth() + "x" + game.getConfig().getHeight(),
                    game.getConfig().getFoodStatic() + " + " + game.getConfig().getFoodPerPlayer() + "X"
            ));
        });

        Platform.runLater(() -> {
            gamesList.getChildren().clear();
            gamesList.getChildren().setAll(gameRecords.values());
        });


    }

    private HBox constructGameRecord(SocketAddress address, String masterName, Integer playersCount, String size, String foodParam) {
        HBox gameRecord = new HBox();
        Label masterIdLabel = new Label(masterName + " " + address + "   ");
        masterIdLabel.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));

        Label playersCountLabel = new Label(playersCount + "   ");
        playersCountLabel.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));
        Label sizeLabel = new Label(size + "    ");
        sizeLabel.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));
        Label foodParamLabel = new Label(foodParam + "    ");
        foodParamLabel.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));
        Button joinButton = new Button("join");

        joinButton.setOnAction(actionEvent -> {
            if (getNode().joinGame(address)) {
                setScreen("gameScene.fxml", actionEvent, 800, 600);
            }
        });
        gameRecord.getChildren().addAll(masterIdLabel, playersCountLabel, sizeLabel, foodParamLabel, joinButton);
        gameRecord.setId(masterName);
        return gameRecord;
    }


}

