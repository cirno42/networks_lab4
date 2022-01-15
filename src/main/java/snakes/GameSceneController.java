package snakes;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import snakes.proto.SnakesProto;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class GameSceneController extends ScreenController {

    @FXML
    private ResourceBundle resources;

    @FXML
    private URL location;

    @FXML
    private Canvas snakeField;

    @FXML
    private VBox scoreBoard;

    @FXML
    private VBox infoList;

    @FXML
    private Button pauseButton;

    @FXML
    private Button exitButton;

    @FXML
    void initialize() {
        exitButton.setOnAction(actionEvent -> {
            getNode().exitGame();
            drawer.interrupt();
            setScreen("mainScene.fxml", actionEvent, 800, 600);
        });
        snakeField.setFocusTraversable(true);
        drawer = new Thread(this::drawField);
        drawer.start();
    }

    private Thread drawer;

    GameSceneController(Node node) {
        super(node);
    }

    private void drawField() {
        try {
            Platform.runLater(this::drawConfig);
            while (!Thread.currentThread().isInterrupted()) {
                Platform.runLater(this::drawBackGround);
                Platform.runLater(this::drawSnakes);
                Platform.runLater(this::drawFood);
                Platform.runLater(this::updateScoreBoard);
                synchronized (this) {
                    this.wait(getNode().getGameState().getConfig().getStateDelayMs());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drawConfig() {
        Label configLabel = new Label(getNode().getGameState().getConfig().toString());
        configLabel.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));
        infoList.getChildren().add(configLabel);
    }

    private void updateScoreBoard() {
        scoreBoard.getChildren().clear();
        for (SnakesProto.GamePlayer player : getNode().getGameState().getPlayers().getPlayersList()) {
            Label label = new Label(player.getName() + " " + player.getScore());
            label.setFont(Font.font("Arial", FontWeight.BOLD, FontPosture.ITALIC, 15));
            scoreBoard.getChildren().add(label);
        }
    }


    private void drawBackGround() {
        GraphicsContext gc = snakeField.getGraphicsContext2D();
        SnakesProto.GameConfig config = getNode().getGameState().getConfig();
        int width = config.getWidth();
        int height = config.getHeight();
        int fullWidth = (int) snakeField.getWidth();
        int fullHeight = (int) snakeField.getHeight();
        int side1 = fullWidth / width;
        int side2 = fullHeight / height;
        cellSide = Math.min(side1, side2);

        int fieldWidth = cellSide * width;
        int fieldHeight = cellSide * height;
        beginX = (int) snakeField.getLayoutX() + (fullWidth - fieldWidth) / 2;
        beginY = (int) snakeField.getLayoutY() + (fullHeight - fieldHeight) / 2;

        gc.setFill(Paint.valueOf("#2E3532"));
        gc.fillRect(0,0, snakeField.getWidth(), snakeField.getHeight());

        gc.setFill(Paint.valueOf("#D2D4C8"));
        gc.fillRect(beginX, beginY, fieldWidth, fieldHeight);
    }

    private void drawSnakes() {
        List<SnakesProto.GameState.Snake> snakes = getNode().getGameState().getSnakesList();
        for (SnakesProto.GameState.Snake snake : snakes) {
            drawSnake(snake);
        }
    }

    private void drawFood() {
        List<SnakesProto.GameState.Coord> foods = getNode().getGameState().getFoodsList();
        for (SnakesProto.GameState.Coord food : foods) {
            drawCell(food.getX(), food.getY(), Color.RED);
        }
    }

    private void drawCell(int x, int y, Color color) {
        GraphicsContext gc = snakeField.getGraphicsContext2D();
        gc.setFill(color);
        gc.fillRect(beginX + cellSide * x, beginY + cellSide * y, cellSide, cellSide);
    }

    private void drawHorLine(int x1, int x2, int y, Color color) {
        if (x1 > x2) {
            x1 += x2;
            x2 = x1 - x2;
            x1 = x1 - x2;
        }
        GraphicsContext gc = snakeField.getGraphicsContext2D();
        gc.setFill(color);
        for (int x = x1; x <= x2; x++) {
            gc.fillRect(beginX + x * cellSide, beginY + y * cellSide, cellSide, cellSide);
        }
    }

    private void drawVertLine(int y1, int y2, int x, Color color) {
        if (y1 > y2) {
            y1 += y2;
            y2 = y1 - y2;
            y1 = y1 - y2;
        }
        GraphicsContext gc = snakeField.getGraphicsContext2D();
        gc.setFill(color);
        for (int y = y1; y <= y2; y++) {
            gc.fillRect(beginX + x * cellSide, beginY + y * cellSide, cellSide, cellSide);
        }
    }

    private void drawVector(SnakesProto.GameState.Coord p1, int x, int y, int width, int height, Color color) {
        if (p1.getX() + x < 0) {
            drawHorLine(p1.getX() + x + width, width - 1, p1.getY(), color);
            drawHorLine(0, p1.getX(), p1.getY(), color);
        } else if (p1.getX() + x >= width) {
            drawHorLine(p1.getX(), width - 1, p1.getY(), color);
            drawHorLine(0, (p1.getX() + x) % width, p1.getY(), color);
        } else if (p1.getY() + y < 0) {
            drawVertLine(p1.getY() + y + height, height - 1, p1.getX(), color);
            drawVertLine(0, p1.getY(), p1.getX(), color);
        } else if (p1.getY() + y >= height) {
            drawVertLine(p1.getY(), height - 1, p1.getX(), color);
            drawVertLine(0, (p1.getY() + y) % height, p1.getX(), color);
        } else if (x == 0) {
            drawVertLine(p1.getY(), p1.getY() + y, p1.getX(), color);
        } else if (y == 0) {
            drawHorLine(p1.getX(), p1.getX() + x, p1.getY(), color);
        }
    }

    private void drawSnake(SnakesProto.GameState.Snake snake) {
        Color color = Color.GREEN;
        if (snake.getState().equals(SnakesProto.GameState.Snake.SnakeState.ZOMBIE)) {
            color = Color.SILVER;
        }
        int width = getNode().getGameState().getConfig().getWidth();
        int height = getNode().getGameState().getConfig().getHeight();
        SnakesProto.GameState.Coord p1 = snake.getPoints(0);
        for (SnakesProto.GameState.Coord cell : snake.getPointsList()) {
            if (cell.equals(snake.getPoints(0))) continue;
            drawVector(p1, cell.getX(), cell.getY(), width, height, color);
            p1 = SnakesProto.GameState.Coord.newBuilder()
                    .setX((p1.getX() + cell.getX() + width) % width)
                    .setY((p1.getY() + cell.getY() + height) % height)
                    .build();
        }
        drawCell(snake.getPoints(0).getX(), snake.getPoints(0).getY(), Color.ORANGE);
    }


    @FXML
    void handleKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case W: {
                getNode().changeDirection(SnakesProto.Direction.UP);
                break;
            }
            case S: {
                getNode().changeDirection(SnakesProto.Direction.DOWN);
                break;
            }
            case A: {
                getNode().changeDirection(SnakesProto.Direction.LEFT);
                break;
            }
            case D: {
                getNode().changeDirection(SnakesProto.Direction.RIGHT);
                break;
            }
            default: {
                System.out.println("key pressed");
            }
        }
    }

    private int cellSide;
    private int beginX;
    private int beginY;

}
