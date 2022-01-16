package snakes;

import lombok.Getter;
import lombok.Setter;
import snakes.proto.SnakesProto;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class Node extends Thread {

    private static final String MULTICAST_ADDRESS = "239.192.0.4";
    private static final int MULTICAST_PORT = 9192;


    private final Thread announceListener;
    private Thread announceSender;
    private Thread pendingHandler;
    private Thread unicastListener;
    private Thread pingSender;
    private final Thread messageCleaner;

    @Getter
    private final int unicastPort;

    @Getter
    private final String nodeName;

    private final Map<Long, PacketUniqueID> pendingMessages = new ConcurrentHashMap<>();

    @Getter
    private final Map<SocketAddress, SnakesProto.GameMessage.AnnouncementMsg> knownGames = new ConcurrentHashMap<>();
    private final Map<SocketAddress, Long> timeStamps = new ConcurrentHashMap<>();

    @Setter
    @Getter
    private SnakesProto.GameState gameState;

    @Setter
    private int idInGame = -1;

    private int stateOrder = 0;
    private long msgSeq = 0;

    private boolean errorReceived = false;

    private int masterId = -1;
    private InetAddress masterAddr;
    private int masterPort;

    private boolean inGame = false;
    private final Object sync = new Object();

    private SnakesProto.NodeRole role = SnakesProto.NodeRole.VIEWER;

    private final Map<Integer, SnakesProto.GamePlayer> deadPlayers = new HashMap<>();
    private final List<SnakesProto.GameState.Coord> removedFood = new LinkedList<>();
    private final List<SnakesProto.GamePlayer> playersToRemove = new LinkedList<>();
    private final Map<Integer, Long> lastSentMessage = new HashMap<>();
    private final List<SnakesProto.GameState.Coord> food = new LinkedList<>();
    //private final int deputyId = -1;
    private List<SnakesProto.GameState.Snake> snakes = new LinkedList<>();
    private final Map<Integer, SnakesProto.GamePlayer> players = new HashMap<>();
    private final Map<Integer, SnakesProto.Direction> directionMap = new HashMap<>();
    private DatagramSocket unicastSocket;
    private MulticastSocket multicastSocket;

    Node(int port, String name) {
        nodeName = name;
        unicastPort = port;
        gameState = SnakesProto.GameState.getDefaultInstance();

        try {
            multicastSocket = new MulticastSocket(MULTICAST_PORT);
            multicastSocket.joinGroup(InetAddress.getByName(MULTICAST_ADDRESS));
        } catch (IOException e) {
            e.printStackTrace();
        }
        messageCleaner = new Thread(this::cleanGames);
        announceListener = new Thread(this::listenAnnouncements);
        announceListener.setName("ANNOUNCELISTENER");
        announceSender = new Thread(this::sendAnnouncements);
        announceSender.setName("ANNOUNCESENDER");
        pendingHandler = new Thread(this::handlePendingMessages);
        pendingHandler.setName("PENDING HANDLER");
        unicastListener = new Thread(this::listenUnicast);
        unicastListener.setName("UNICASTLISTENER");
        pingSender = new Thread(this::keepAlive);
        pingSender.setName("PINGSENDER");
    }


    private synchronized void removePLayers() {
        playersToRemove.forEach(p -> players.remove(p.getId()));
        playersToRemove.clear();
    }


    @Override
    public void run() {
        System.out.println("running...");
        announceListener.start();
        messageCleaner.start();
        final Object obj = new Object();
        while (!Thread.currentThread().isInterrupted()) {
            synchronized (obj) {
                try {
                    if (role.equals(SnakesProto.NodeRole.MASTER)) {
                        proceedGameState();
                        SnakesProto.GameMessage gameMessage = constructGameStateMessage();
                        for (SnakesProto.GamePlayer player : players.values()) {
                            if (player.getId() == idInGame) continue;
                            sendGameState(gameMessage, player);
                        }

                        removePLayers();

                    }
                    obj.wait(gameState.getConfig().getStateDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

        }
    }


    private SnakesProto.Direction getDirection(SnakesProto.GameState.Coord offset) {
        if (offset.getX() > 0) return SnakesProto.Direction.LEFT;
        if (offset.getX() < 0) return SnakesProto.Direction.RIGHT;
        if (offset.getY() > 0) return SnakesProto.Direction.UP;
        return SnakesProto.Direction.DOWN;
    }

    private SnakesProto.GameState.Coord movePoint(SnakesProto.GameState.Coord p, SnakesProto.Direction direction) {
        if (direction.equals(SnakesProto.Direction.UP)) return coord(p.getX(), p.getY() - 1);
        if (direction.equals(SnakesProto.Direction.DOWN)) return coord(p.getX(), p.getY() + 1);
        if (direction.equals(SnakesProto.Direction.LEFT)) return coord(p.getX() - 1, p.getY());
        return coord(p.getX() + 1, p.getY());
    }

    private SnakesProto.Direction invertDirection(SnakesProto.Direction direction) {
        if (direction.equals(SnakesProto.Direction.UP)) return SnakesProto.Direction.DOWN;
        if (direction.equals(SnakesProto.Direction.DOWN)) return SnakesProto.Direction.UP;
        if (direction.equals(SnakesProto.Direction.LEFT)) return SnakesProto.Direction.RIGHT;
        return SnakesProto.Direction.LEFT;
    }

    private SnakesProto.GameState.Coord getNewControlPoint(SnakesProto.Direction newDirection) {
        if (newDirection.equals(SnakesProto.Direction.UP)) return coord(0, 1);
        if (newDirection.equals(SnakesProto.Direction.DOWN)) return coord(0, -1);
        if (newDirection.equals(SnakesProto.Direction.LEFT)) return coord(1, 0);
        return coord(-1, 0);
    }

    private SnakesProto.GameState.Coord fixPoint(SnakesProto.GameState.Coord p) {
        int width = gameState.getConfig().getWidth();
        int height = gameState.getConfig().getHeight();
        return coord((p.getX() + width) % width, (p.getY() + height) % height);
    }


    private SnakesProto.GamePlayer getDeputy() {
        for (SnakesProto.GamePlayer player : players.values()) {
            if (player.getRole().equals(SnakesProto.NodeRole.DEPUTY)) {
                return player;
            }
        }
        return null;
    }

    private void notifyDeputy() {
        SnakesProto.GamePlayer deputy = getDeputy();
        if (deputy == null) return;
        masterId = deputy.getId();
        masterPort = deputy.getPort();
        try {
            masterAddr = InetAddress.getByName(deputy.getIpAddress());
        } catch (UnknownHostException e) {
            System.out.println("notify deputy" + e.getLocalizedMessage());
        }
        players.put(deputy.getId(), changeRole(deputy, SnakesProto.NodeRole.MASTER));
        System.out.println("notifying deputy from " + role + " with id = " + idInGame);
        sendRoleChange(deputy, role, SnakesProto.NodeRole.MASTER);
    }

    private SnakesProto.GamePlayer changeRole(SnakesProto.GamePlayer player, SnakesProto.NodeRole newRole) {
        return SnakesProto.GamePlayer.newBuilder()
                .setType(player.getType())
                .setName(player.getName())
                .setScore(player.getScore())
                .setIpAddress(player.getIpAddress())
                .setPort(player.getPort())
                .setRole(newRole)
                .setId(player.getId())
                .build();
    }

    private SnakesProto.GameState.Snake killSnake(SnakesProto.GameState.Snake snake) {
        SnakesProto.GamePlayer player = players.get(snake.getPlayerId());
        deadPlayers.put(player.getId(), player);
        System.out.println("kill snake " + player.getId());
        return SnakesProto.GameState.Snake.newBuilder()
                .setState(SnakesProto.GameState.Snake.SnakeState.ZOMBIE)
                .setPlayerId(-2)
                .addAllPoints(snake.getPointsList())
                .setHeadDirection(snake.getHeadDirection())
                .build();
    }

    private void checkSnakesIntersections() {
        int it = 0;
        Map<Integer, SnakesProto.GameState.Snake> deadSnakes = new HashMap<>();
        for (SnakesProto.GameState.Snake snake : snakes) {
            if (snake.getState().equals(SnakesProto.GameState.Snake.SnakeState.ZOMBIE)) {
                it++;
                continue;
            }
            if (isIntersectWithSnake(snake.getPlayerId(), snake.getPoints(0), snakes)) {
                deadSnakes.put(it, snake);
            }
            it++;
        }
        deadSnakes.forEach((k, v) -> snakes.set(k, killSnake(v)));
    }

    private void setDeputy() {
        for (SnakesProto.GamePlayer player : players.values()) {
            if (player.getRole().equals(SnakesProto.NodeRole.NORMAL)) {
                SnakesProto.GamePlayer newPlayer = changeRole(player, SnakesProto.NodeRole.DEPUTY);
                players.put(newPlayer.getId(), newPlayer);
                sendRoleChange(newPlayer, role, SnakesProto.NodeRole.DEPUTY);
            }
        }
    }

    private synchronized void proceedGameState() {
        SnakesProto.GameConfig config = gameState.getConfig();

        snakes = nextSnakesState();



        checkSnakesIntersections();


        deadPlayers.forEach((k, v) -> {
            players.put(v.getId(), changeRole(v, SnakesProto.NodeRole.VIEWER));
            sendRoleChange(v, SnakesProto.NodeRole.MASTER, SnakesProto.NodeRole.VIEWER);
        });

        if (role.equals(SnakesProto.NodeRole.VIEWER)) {
            SnakesProto.GamePlayer deputy = getDeputy();
            if (deputy == null) setDeputy();
            notifyDeputy();
        }


        deadPlayers.clear();
        placeFood(snakes);
        stateOrder++;
        gameState = SnakesProto.GameState.newBuilder()
                .setPlayers(SnakesProto.GamePlayers.newBuilder()
                        .addAllPlayers(players.values())
                        .build())
                .setConfig(config)
                .addAllSnakes(snakes)
                .addAllFoods(food)
                .setStateOrder(stateOrder)
                .build();


    }


    private List<SnakesProto.GameState.Coord> freeCells(List<SnakesProto.GameState.Snake> snakes) {
        List<SnakesProto.GameState.Coord> result = new LinkedList<>();
        for (int x = 0; x < gameState.getConfig().getWidth(); x++) {
            for (int y = 0; y < gameState.getConfig().getHeight(); y++) {
                if (!isIntersectWithFood(coord(x, y)) && !isIntersectWithSnake(-1, coord(x, y), snakes)) {
                    result.add(coord(x, y));
                }
            }
        }
        java.util.Collections.shuffle(result);
        return result;
    }

    private void placeFood(List<SnakesProto.GameState.Snake> snakes) {
        int required = gameState.getConfig().getFoodStatic() + (int) (gameState.getPlayers().getPlayersCount() * gameState.getConfig().getFoodPerPlayer());

        food.removeAll(removedFood);
        removedFood.clear();
        int current = food.size();
        for (SnakesProto.GameState.Coord cell : freeCells(snakes)) {
            if (current >= required) break;
            food.add(cell);
            current++;
        }
    }

    private SnakesProto.GameState.Coord coord(int x, int y) {
        return SnakesProto.GameState.Coord.newBuilder()
                .setX(x)
                .setY(y)
                .build();
    }

    private SnakesProto.GameState.Coord addOffset(SnakesProto.GameState.Coord point, SnakesProto.GameState.Coord offset) {
        return coord(point.getX() + offset.getX(), point.getY() + offset.getY());
    }

    private int getMasterId() {
        for (SnakesProto.GamePlayer player : players.values()) {
            if (player.getRole().equals(SnakesProto.NodeRole.MASTER)) {
                return player.getId();
            }
        }
        return -1;
    }

    private boolean idExists(int id) {
        for (SnakesProto.GamePlayer player : gameState.getPlayers().getPlayersList()) {
            if (player.getId() == id) return true;
        }
        return false;
    }

    private SnakesProto.GamePlayer getPlayerById(int id) {
        return players.get(id);
    }

    private int getPlayerIdByAddress(InetAddress ip, int port) {
        for (SnakesProto.GamePlayer player : players.values()) {
            String strIp = ip.toString().substring(1);
            if (player.getPort() == port && player.getIpAddress().equals(strIp)) {
                return player.getId();
            }
        }
        return -1;
    }

    private boolean isIntersectLine(int x, int x1, int x2) {
        return x >= x1 && x <= x2;
    }

    private boolean isIntersectWithSegment(SnakesProto.GameState.Coord head, SnakesProto.GameState.Coord p1,
                                           SnakesProto.GameState.Coord p2) {
        int width = gameState.getConfig().getWidth();
        int height = gameState.getConfig().getHeight();
        if (p1.getX() == p2.getX() && p1.getX() == head.getX()) {
            if (p2.getY() < 0) {
                return isIntersectLine(head.getY(), p2.getY() + height, height - 1) ||
                        isIntersectLine(head.getY(), 0, p1.getY());
            }
            if (p2.getY() >= height) {
                return isIntersectLine(head.getY(), p1.getY(), height - 1) ||
                        isIntersectLine(head.getY(), 0, p2.getY() - height);
            }
            if (p1.getY() >= p2.getY()) {
                return isIntersectLine(head.getY(), p2.getY(), p1.getY());
            }
            return isIntersectLine(head.getY(), p1.getY(), p2.getY());
        }
        if (p1.getY() == p2.getY() && p1.getY() == head.getY()) {
            if (p2.getX() < 0) {
                return isIntersectLine(head.getX(), p2.getX() + width, width - 1) ||
                        isIntersectLine(head.getX(), 0, p1.getX());
            }
            if (p2.getX() >= width) {
                return isIntersectLine(head.getX(), p1.getX(), width - 1) ||
                        isIntersectLine(head.getX(), 0, p2.getX() - width);
            }
            if (p1.getX() >= p2.getX()) {
                return isIntersectLine(head.getX(), p2.getX(), p1.getX());
            }
            return isIntersectLine(head.getX(), p1.getX(), p2.getX());
        }
        return false;
    }

    private boolean isIntersectWithSnake(int ownerId, SnakesProto.GameState.Coord head,
                                         List<SnakesProto.GameState.Snake> snakes) {
        for (SnakesProto.GameState.Snake snake : snakes) {
            if (snake.getState().equals(SnakesProto.GameState.Snake.SnakeState.ZOMBIE)) continue;
            SnakesProto.GameState.Coord p1 = snake.getPoints(0);
            int it = 0;
            for (SnakesProto.GameState.Coord snakePoint : snake.getPointsList()) {
                if (it == 0) {
                    it++;
                    continue;
                }
                SnakesProto.GameState.Coord p2 = addOffset(p1, snakePoint);
                if (snake.getPlayerId() == ownerId && it == 1 &&
                        isIntersectWithSegment(head, movePoint(p1, invertDirection(snake.getHeadDirection())),p2)) {
                    return true;
                }

                if (!(snake.getPlayerId() == ownerId && it == 1) && (isIntersectWithSegment(head, p1, p2))) {
                    return true;
                }
                p1 = p2;
                it++;
            }
        }
        return false;
    }

    private boolean isIntersectWithFood(SnakesProto.GameState.Coord cell) {
        for (SnakesProto.GameState.Coord fd : food) {
            if (fd.equals(cell)) return true;
        }
        return false;
    }

    private SnakesProto.GameState.Coord calcOffset(SnakesProto.GameState.Coord c1, SnakesProto.GameState.Coord c2) {
        return coord(c2.getX() - c1.getX(), c2.getY() - c1.getY());
    }

    private SnakesProto.GamePlayer addPoint(SnakesProto.GamePlayer player) {
        return SnakesProto.GamePlayer.newBuilder()
                .setId(player.getId())
                .setRole(player.getRole())
                .setPort(player.getPort())
                .setIpAddress(player.getIpAddress())
                .setScore(player.getScore() + 1)
                .setName(player.getName())
                .setType(player.getType())
                .build();
    }

    private SnakesProto.GameState.Snake nextSnakeState(SnakesProto.GameState.Snake snake) {
        int pointsCount = snake.getPointsCount();
        SnakesProto.GameState.Coord head = snake.getPoints(0);
        SnakesProto.GameState.Coord prevHead = snake.getPoints(1);
        SnakesProto.Direction direction = directionMap.get(snake.getPlayerId());
        SnakesProto.Direction prevDirection = getDirection(prevHead);
        SnakesProto.GameState.Coord tail = snake.getPoints(pointsCount - 1);
        if (snake.getPlayerId() == -2) {
            direction = snake.getHeadDirection();
        }
        head = fixPoint(movePoint(head, direction));
        List<SnakesProto.GameState.Coord> newPoints = new LinkedList<>();
        newPoints.add(head);
        int pointCnt = snake.getPointsCount();


        boolean pickedFood = isIntersectWithFood(head);
        if (!direction.equals(prevDirection)) {
            newPoints.add(getNewControlPoint(direction));
            if (pointCnt == 2 && !pickedFood) {
                prevHead = movePoint(prevHead, prevDirection);
            }
        } else {
            if (pointCnt > 2 || pickedFood) {
                prevHead = movePoint(prevHead, invertDirection(prevDirection));
            }
        }


        if (prevHead.getX() != 0 || prevHead.getY() != 0) {
            newPoints.add(prevHead);
        }

        if (!pickedFood && pointCnt > 2) {
            tail = movePoint(tail, getDirection(tail));
        } else if (pickedFood) {
            if (pointCnt == 2 && direction.equals(prevDirection)) {
                tail = movePoint(tail, invertDirection(getDirection(tail)));
            }
            removedFood.add(head);
            SnakesProto.GamePlayer player = players.get(snake.getPlayerId());
            if (player != null) {
                players.put(snake.getPlayerId(), addPoint(player));
            }
        }

        int it = 0;
        for (SnakesProto.GameState.Coord point : snake.getPointsList()) {
            if (it < 2 || it == pointsCount - 1) {
                it++;
                continue;
            }
            newPoints.add(point);
            it++;
        }

        if ((tail.getX() != 0 || tail.getY() != 0) && pointCnt > 2) {
            newPoints.add(tail);
        }

        SnakesProto.GameState.Snake.SnakeState state = snake.getState();
        int id = snake.getPlayerId();
        if (playersToRemove.contains(getPlayerById(snake.getPlayerId()))) {
            state = SnakesProto.GameState.Snake.SnakeState.ZOMBIE;
            id = -2;
        }
        return SnakesProto.GameState.Snake.newBuilder()
                .setState(state)
                .setHeadDirection(direction)
                .setPlayerId(id)
                .addAllPoints(newPoints)
                .build();

    }

    private List<SnakesProto.GameState.Snake> nextSnakesState() {
        List<SnakesProto.GameState.Snake> newSnakes = new LinkedList<>();
        for (SnakesProto.GameState.Snake snake : snakes) {
            newSnakes.add(nextSnakeState(snake));
        }
        return newSnakes;
    }

    private List<SnakesProto.GameState.Coord> findSnakeSpawn(int playerId) {
        List<SnakesProto.GameState.Coord> spawn = new LinkedList<>();
        int width = gameState.getConfig().getWidth();
        int height = gameState.getConfig().getHeight();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                // top-left corner of 5x5 square
                int badPos = 0;
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        int yy = (row + y) % height;
                        int xx = (col + x) % width;
                        if (isIntersectWithSnake(playerId, coord(xx, yy), gameState.getSnakesList())) {
                            badPos = 1;
                            break;
                        }
                    }
                    if (badPos == 1) break;
                }
                if (badPos == 0) {
                    for (int i = -1; i <= 1; i++) {
                        for (int j = -1; j <= 1; j++) {
                            if ((i != 0 && j != 0) || (i == 0 && j == 0)) continue;
                            int x1 = (row + 2) % width;
                            int y1 = (col + 2) % height;
                            int x2 = (x1 + i) % width;
                            int y2 = (y1 + j) % height;
                            if (!isIntersectWithFood(coord(x1, y1)) && !isIntersectWithFood(coord(x2, y2))) {
                                spawn.add(coord(x1, y1));
                                spawn.add(calcOffset(coord(x1, y1), coord(x2, y2)));
                                return spawn;
                            }
                        }
                    }
                }
            }
        }

        return spawn;
    }

    private SnakesProto.GameState.Snake createNewSnake(int playerId) {
        List<SnakesProto.GameState.Coord> snakePoints = findSnakeSpawn(playerId);
        if (snakePoints.size() < 2) return null;
        SnakesProto.Direction direction;
        if (snakePoints.get(1).getX() < 0) direction = SnakesProto.Direction.RIGHT;
        else if (snakePoints.get(1).getX() > 0) direction = SnakesProto.Direction.LEFT;
        else if (snakePoints.get(1).getY() < 0) direction = SnakesProto.Direction.DOWN;
        else direction = SnakesProto.Direction.UP;
        directionMap.put(playerId, direction);
        return SnakesProto.GameState.Snake.newBuilder()
                .setPlayerId(playerId)
                .setHeadDirection(direction)
                .addAllPoints(snakePoints)
                .setState(SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .build();
    }


    private SnakesProto.GameState.Snake getSnake(int playerId) {
        for (SnakesProto.GameState.Snake snake : snakes) {
            if (snake.getPlayerId() == playerId) {
                return snake;
            }
        }
        return null;
    }

    private void changeSnakeDirection(int playerId, SnakesProto.Direction direction) {
        SnakesProto.GameState.Snake snake = getSnake(playerId);
        if (snake == null) return;
        if (snake.getState().equals(SnakesProto.GameState.Snake.SnakeState.ZOMBIE)) return;
        if (!direction.equals(invertDirection(snake.getHeadDirection()))) {
            directionMap.put(playerId, direction);
        }
    }

    synchronized void changeDirection(SnakesProto.Direction direction) {
        if (role.equals(SnakesProto.NodeRole.MASTER)) {
            changeSnakeDirection(idInGame, direction);
            return;
        }
        if (masterId == idInGame) return;
        long sendTime = System.currentTimeMillis();
        SnakesProto.GameMessage message = constructSteer(direction);
        DatagramPacket packet = constructPacket(message, masterAddr, masterPort);
        lastSentMessage.put(masterId, sendTime);
        pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet, masterId));
    }


    private SnakesProto.GameMessage constructGameStateMessage() {
        SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                .setState(gameState)
                .build();
        return SnakesProto.GameMessage.newBuilder()
                .setState(stateMsg)
                .setMsgSeq(msgSeq++)
                .build();
    }

    private DatagramPacket constructPacket(SnakesProto.GameMessage message, InetAddress ip, int port) {
        byte[] sendBuffer;

        DatagramPacket packet = null;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ObjectOutputStream oo = new ObjectOutputStream(outputStream)) {
            oo.writeObject(message);
            sendBuffer = message.toByteArray();
            packet = new DatagramPacket(sendBuffer, sendBuffer.length, ip, port);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
        return packet;
    }

    private SnakesProto.GameMessage constructAck(SnakesProto.GameMessage message, int receiverId) {
        long seq = message.getMsgSeq();

        return SnakesProto.GameMessage
                .newBuilder()
                .setMsgSeq(seq)
                .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build())
                .setSenderId(idInGame)
                .setReceiverId(receiverId)
                .build();
    }

    private SnakesProto.GameMessage constructSteer(SnakesProto.Direction direction) {
        return SnakesProto.GameMessage.newBuilder()
                .setSteer(SnakesProto.GameMessage.SteerMsg.newBuilder()
                        .setDirection(direction)
                        .build())
                .setMsgSeq(msgSeq++)
                .build();
    }


    private void cleanGames() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (knownGames) {
                    for (SocketAddress sender : knownGames.keySet()) {
                        if (System.currentTimeMillis() - timeStamps.get(sender) > 1200) {
                            timeStamps.remove(sender);
                            knownGames.remove(sender);
                        }
                    }
                    knownGames.wait(900);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void listenAnnouncements() {
        byte[] recvBuffer = new byte[8192];
        DatagramPacket datagramPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                multicastSocket.receive(datagramPacket);
                handleReceivedPacket(datagramPacket);
            } catch (IOException e) {
                System.out.println(e.getLocalizedMessage());
            }
        }
    }

    private void listenUnicast() {
        byte[] recvBuffer = new byte[8192];
        DatagramPacket datagramPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (inGame) {
                    unicastSocket.receive(datagramPacket);
                    synchronized (sync) {
                        if (inGame) {
                            handleReceivedPacket(datagramPacket);
                        }
                    }
                } else {
                    synchronized (datagramPacket) {
                        datagramPacket.wait(100);
                    }
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendAnnouncements() {
        System.out.println("run sending");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (role.equals(SnakesProto.NodeRole.MASTER)) {
                    unicastSocket.send(constructPacket(
                            SnakesProto.GameMessage.newBuilder().setAnnouncement(
                                    SnakesProto.GameMessage.AnnouncementMsg
                                            .newBuilder()
                                            .setConfig(gameState.getConfig())
                                            .setPlayers(gameState.getPlayers())
                                            .build()
                            )
                                    .setMsgSeq(msgSeq++)
                                    .build()
                            , InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT));
                }
                synchronized (gameState) {
                    gameState.wait(1000);
                }
            }
        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
        }
    }


    private void sendPing(InetAddress ip, int port, int receiverId) {
        SnakesProto.GameMessage.PingMsg pingMsg = SnakesProto.GameMessage.PingMsg.newBuilder().build();
        SnakesProto.GameMessage message = SnakesProto.GameMessage.newBuilder()
                .setPing(pingMsg)
                .setMsgSeq(msgSeq++)
                .build();
        DatagramPacket packet = constructPacket(message, ip, port);

        long sendTime = System.currentTimeMillis();
        pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet, receiverId));
        try {
            if (packet.getAddress() == null || packet.getData() == null) return;
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }

    }

    private synchronized void sendPings() {
        try {
            if (role.equals(SnakesProto.NodeRole.MASTER)) {
                for (SnakesProto.GamePlayer player : players.values()) {
                    if (player.getId() == idInGame || lastSentMessage.get(player.getId()) == null) continue;
                    long curTime = System.currentTimeMillis();
                    if (curTime - lastSentMessage.get(player.getId()) > gameState.getConfig().getPingDelayMs()) {
                        lastSentMessage.put(player.getId(), curTime);
                        sendPing(InetAddress.getByName(player.getIpAddress()), player.getPort(), player.getId());
                    }
                }

            } else if (masterId != -1) {
                long curTime = System.currentTimeMillis();
                if (lastSentMessage.get(masterId) == null) return;
                if (curTime - lastSentMessage.get(masterId) > gameState.getConfig().getPingDelayMs()) {
                    lastSentMessage.put(masterId, curTime);
                    sendPing(masterAddr, masterPort, masterId);
                }
            }
        } catch (UnknownHostException e) {
            System.out.println("pings " + e.getLocalizedMessage());
        }
    }

    private void keepAlive() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (sync) {
                    if (inGame) sendPings();
                    sync.wait(gameState.getConfig().getPingDelayMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendAck(SnakesProto.GameMessage message, int receiverId, InetAddress ip, int port) {
        try {
            lastSentMessage.put(receiverId, System.currentTimeMillis());
            unicastSocket.send(constructPacket(constructAck(message, receiverId), ip, port));
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

    private void sendError(InetAddress ip, int port) {
        try {
            SnakesProto.GameMessage.ErrorMsg errorMsg = SnakesProto.GameMessage.ErrorMsg.newBuilder()
                    .setErrorMessage("cannot connect")
                    .build();
            SnakesProto.GameMessage message = SnakesProto.GameMessage.newBuilder()
                    .setError(errorMsg)
                    .setMsgSeq(msgSeq++)
                    .build();
            long sendTime = System.currentTimeMillis();

            DatagramPacket packet = constructPacket(message, ip, port);
            pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet, -1));
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

    private void sendGameState(SnakesProto.GameMessage message, SnakesProto.GamePlayer player) {
        try {
            long sendTime = System.currentTimeMillis();
            InetAddress ip = InetAddress.getByName(player.getIpAddress());
            int port = player.getPort();
            DatagramPacket packet = constructPacket(message, ip, port);
            lastSentMessage.put(player.getId(), sendTime);
            pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet, player.getId()));
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

    private void sendRoleChange(SnakesProto.GamePlayer receiver, SnakesProto.NodeRole senderRole,
                                SnakesProto.NodeRole receiverRole) {
        players.put(receiver.getId(), changeRole(receiver, receiverRole));
        players.put(idInGame, changeRole(getPlayerById(idInGame),senderRole));
        if (receiver.getId() == idInGame) {
            role = receiverRole;
            return;
        }
        System.out.println("sending role change: from " + senderRole + " to " + receiverRole);
        System.out.println("role change from =  " + idInGame + "  to  =  " + receiver.getId());
        SnakesProto.GameMessage.RoleChangeMsg roleChangeMsg = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                .setReceiverRole(receiverRole)
                .setSenderRole(senderRole)
                .build();
        SnakesProto.GameMessage message = SnakesProto.GameMessage.newBuilder()
                .setRoleChange(roleChangeMsg)
                .setReceiverId(receiver.getId())
                .setSenderId(idInGame)
                .setMsgSeq(msgSeq++)
                .build();

        try {
            long sendTime = System.currentTimeMillis();
            DatagramPacket packet = constructPacket(message, InetAddress.getByName(receiver.getIpAddress()),
                    receiver.getPort());
            lastSentMessage.put(receiver.getId(), sendTime);
            pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet,
                    receiver.getId()));
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }

    private void setNewMaster() {
        for (SnakesProto.GamePlayer player : players.values()) {
            if (player.getRole().equals(SnakesProto.NodeRole.DEPUTY)) {
                masterId = player.getId();
                masterPort = player.getPort();
                try {
                    masterAddr = InetAddress.getByName(player.getIpAddress());
                } catch (UnknownHostException e) {
                    System.out.println("set master " + e.getLocalizedMessage());
                }
                players.put(masterId, changeRole(player, SnakesProto.NodeRole.MASTER));
                if (masterId == idInGame) {
                    role = SnakesProto.NodeRole.MASTER;
                    System.out.println("I Am Master Now #####");
                    for (SnakesProto.GamePlayer receiver : players.values()) {
                        if (receiver.getId() == idInGame || receiver.getRole().equals(SnakesProto.NodeRole.MASTER))
                            continue;
                        sendRoleChange(receiver, SnakesProto.NodeRole.MASTER, receiver.getRole());
                    }
                    setDeputy();
                }
                break;
            }
        }
    }

    private synchronized void checkTimeout() {
        long curTime = System.currentTimeMillis();
        pendingMessages.entrySet().removeIf(entry -> {
            boolean expired = curTime - entry.getValue().getFirstTime() > gameState.getConfig().getNodeTimeoutMs();
            if (expired) {
                SnakesProto.GamePlayer toDelete = getPlayerById(entry.getValue().getReceiverId());

                if (toDelete != null) {
                    playersToRemove.add(toDelete);
                }
                if (!role.equals(SnakesProto.NodeRole.MASTER)) {
                    System.out.println("setting new master!!!!!");
                    setNewMaster();
                }
            }
            return expired;
        });

        try {
            for (Map.Entry<Long, PacketUniqueID> entry : pendingMessages.entrySet()) {
                if (curTime - entry.getValue().getLastTime() > gameState.getConfig().getPingDelayMs()) {
                    entry.getValue().setLastTime(curTime);
                    if (entry.getValue().getPacket().getAddress() == null ||
                            entry.getValue().getPacket().getData() == null) {
                        return;
                    }
                    unicastSocket.send(entry.getValue().getPacket());
                }
            }
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }
    }


    private void handlePendingMessages() {
        while (!Thread.currentThread().isInterrupted()) {
            checkTimeout();
        }
    }

    private boolean isDeputySet() {
        for (SnakesProto.GamePlayer player : players.values()) {
            if (player.getRole().equals(SnakesProto.NodeRole.DEPUTY)) {
                return true;
            }
        }
        return false;
    }

    private void handleJoin(SnakesProto.GameMessage msg, SocketAddress sender) {
        InetAddress ip = ((InetSocketAddress) sender).getAddress();
        int port = ((InetSocketAddress) sender).getPort();
        for (SnakesProto.GamePlayer player : gameState.getPlayers().getPlayersList()) {
            if (player.getIpAddress().equals(ip.toString().substring(1)) &&
                    player.getPort() == port) {

                sendAck(msg, player.getId(), ip, port);

                return;
            }
        }

        int newId;
        do {
            newId = new Random().nextInt();
        } while (idExists(newId));
        SnakesProto.GameState.Snake newSnake = createNewSnake(newId);
        if (newSnake == null) {
            sendError(ip, port);
            return;
        }

        SnakesProto.GamePlayer newPlayer = SnakesProto.GamePlayer
                .newBuilder()
                .setName(msg.getJoin().getName())
                .setId(newId)
                .setPort(((InetSocketAddress) sender).getPort())
                .setIpAddress(((InetSocketAddress) sender).getAddress().toString().substring(1))
                .setRole(SnakesProto.NodeRole.NORMAL)
                .setScore(2)
                .build();
        players.put(newId, newPlayer);
        snakes.add(newSnake);
        sendAck(msg, newPlayer.getId(), ip, port);
        if (!isDeputySet()) {
            sendRoleChange(newPlayer, role, SnakesProto.NodeRole.DEPUTY);
        }
    }

    private void handleError(SnakesProto.GameMessage msg, SocketAddress sender) {
        if (pendingMessages.containsKey(msg.getMsgSeq())) {
            synchronized (pendingMessages) {
                pendingMessages.remove(msg.getMsgSeq());
            }
            errorReceived = true;
        } else {
            errorReceived = false;
        }
        int port = ((InetSocketAddress) sender).getPort();
        InetAddress ip = ((InetSocketAddress) sender).getAddress();
        sendAck(msg, masterId, ip, port);
    }

    private void handleSteer(SnakesProto.GameMessage msg, SocketAddress sender) {
        InetAddress ip = ((InetSocketAddress) sender).getAddress();
        int port = ((InetSocketAddress) sender).getPort();
        int playerId = getPlayerIdByAddress(ip, port);
        changeSnakeDirection(playerId, msg.getSteer().getDirection());
        sendAck(msg, playerId, ip, port);
    }

    private void handleAnnouncement(SnakesProto.GameMessage msg, SocketAddress sender) {
        timeStamps.put(sender, System.currentTimeMillis());
        synchronized (knownGames) {
            knownGames.put(sender, msg.getAnnouncement());
        }

    }

    private SnakesProto.GamePlayer setAddr(int id, String ip, int port) {
        SnakesProto.GamePlayer old = getPlayerById(id);
        return SnakesProto.GamePlayer.newBuilder()
                .setId(id)
                .setRole(old.getRole())
                .setType(old.getType())
                .setName(old.getName())
                .setPort(port)
                .setScore(old.getScore())
                .setIpAddress(ip)
                .build();
    }

    private void handleAck(SnakesProto.GameMessage msg, SocketAddress sender) {
        idInGame = msg.getReceiverId();

        if (getPlayerById(msg.getSenderId()).getIpAddress().isEmpty()) {
            players.put(msg.getSenderId(), setAddr(msg.getSenderId(),
                    ((InetSocketAddress) sender).getAddress().toString().substring(1),
                    ((InetSocketAddress) sender).getPort()));
        }

        pendingMessages.remove(msg.getMsgSeq());
    }

    private void handleState(SnakesProto.GameMessage msg, SocketAddress sender) {
        int curVersion = gameState.getStateOrder();
        SnakesProto.GameState newState = msg.getState().getState();
        if (curVersion < newState.getStateOrder()) {
            gameState = newState;
            stateOrder = gameState.getStateOrder();
            snakes.clear();
            snakes.addAll(gameState.getSnakesList());
            for (SnakesProto.GamePlayer player : gameState.getPlayers().getPlayersList()) {
                SnakesProto.GamePlayer oldPlayer = players.get(player.getId());
                SnakesProto.GamePlayer newPlayer = player;
                if (oldPlayer != null) {
                    String addr = oldPlayer.getIpAddress();
                    if (!addr.isEmpty()) newPlayer = setAddr(player.getId(), addr, player.getPort());
                    newPlayer = changeRole(newPlayer, player.getRole());
                }
                players.put(player.getId(), newPlayer);
            }
            food.clear();
            food.addAll(gameState.getFoodsList());
            for (SnakesProto.GameState.Snake snake : snakes) {
                directionMap.put(snake.getPlayerId(), snake.getHeadDirection());
            }
        }
        int port = ((InetSocketAddress) sender).getPort();
        InetAddress ip = ((InetSocketAddress) sender).getAddress();
        if (masterId != -1 && masterId != idInGame) {
            sendAck(msg, masterId, masterAddr, masterPort);
        }
    }

    private void handlePing(SnakesProto.GameMessage msg, SocketAddress sender) {
        int port = ((InetSocketAddress) sender).getPort();
        InetAddress ip = ((InetSocketAddress) sender).getAddress();
        int id;
        if (!role.equals(SnakesProto.NodeRole.MASTER)) {
            id = masterId;
        } else {
            id = getPlayerIdByAddress(ip, port);
        }
        sendAck(msg, id, ip, port);
    }

    private void handleRoleChange(SnakesProto.GameMessage msg, SocketAddress sender) {
        players.put(msg.getSenderId(), changeRole(getPlayerById(msg.getSenderId()),
                msg.getRoleChange().getSenderRole()));

        if (msg.getRoleChange().getReceiverRole().equals(SnakesProto.NodeRole.MASTER) &&
                !role.equals(SnakesProto.NodeRole.MASTER)) {
            System.out.println("I am MASTER NOW !!!!!!!!!!!!!!");
            System.out.println(players);
            for(SnakesProto.GamePlayer player : players.values()) {
                sendRoleChange(player, SnakesProto.NodeRole.MASTER, player.getRole());
            }
            setDeputy();
        }

        role = msg.getRoleChange().getReceiverRole();
        if (role.equals(SnakesProto.NodeRole.MASTER)) {
            masterId = idInGame;
        }

        int port = ((InetSocketAddress) sender).getPort();
        InetAddress ip = ((InetSocketAddress) sender).getAddress();


        if (msg.getRoleChange().getSenderRole().equals(SnakesProto.NodeRole.MASTER)) {
            masterId = msg.getSenderId();
            masterPort = port;
            masterAddr = ip;
        }
        sendAck(msg, msg.getSenderId(), ip, port);
    }

    private synchronized void handleReceivedPacket(DatagramPacket packet) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(packet.getData())) {
            byte[] bytes = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, bytes, 0, packet.getLength());
            SnakesProto.GameMessage message = SnakesProto.GameMessage.parseFrom(bytes);

            System.out.println("received from " + packet.getSocketAddress() + " add = " +
                    ((InetSocketAddress) packet.getSocketAddress()).getAddress() + " mes = " + message.getTypeCase());

            switch (message.getTypeCase()) {
                case PING:
                    handlePing(message, packet.getSocketAddress());
                    break;
                case STEER:
                    handleSteer(message, packet.getSocketAddress());
                    break;
                case ACK:
                    handleAck(message, packet.getSocketAddress());
                    break;
                case STATE:
                    handleState(message, packet.getSocketAddress());
                    break;
                case ANNOUNCEMENT:
                    handleAnnouncement(message, packet.getSocketAddress());
                    break;
                case JOIN:
                    handleJoin(message, packet.getSocketAddress());
                    break;
                case ERROR:
                    handleError(message, packet.getSocketAddress());
                    break;
                case ROLE_CHANGE:
                    handleRoleChange(message, packet.getSocketAddress());
                    break;
                case TYPE_NOT_SET:
                    break;
            }

        } catch (IOException  e) {
            e.printStackTrace();
        }
    }


    public boolean joinGame(SocketAddress address) {
        init();

        SnakesProto.GameMessage message = SnakesProto.GameMessage
                .newBuilder()
                .setJoin(
                        SnakesProto.GameMessage.JoinMsg.newBuilder()
                                .setName(nodeName)
                                .build()
                )
                .setMsgSeq(msgSeq++)
                .build();
        masterAddr = ((InetSocketAddress) address).getAddress();
        masterPort = ((InetSocketAddress) address).getPort();
        gameState = SnakesProto.GameState.newBuilder()
                .setConfig(knownGames.get(address).getConfig())
                .setPlayers(knownGames.get(address).getPlayers())
                .setStateOrder(-1)
                .buildPartial();

        for (SnakesProto.GamePlayer player : gameState.getPlayers().getPlayersList()) {
            players.put(player.getId(), player);
        }

        masterId = getMasterId();
        DatagramPacket packet = constructPacket(message, masterAddr, masterPort);
        long sendTime = System.currentTimeMillis();
        lastSentMessage.put(masterId, sendTime);
        synchronized (pendingMessages) {
            pendingMessages.put(message.getMsgSeq(), new PacketUniqueID(sendTime, sendTime, packet, masterId));
        }

        try {
            synchronized (sync) {
                inGame = true;
            }
            role = SnakesProto.NodeRole.NORMAL;
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getLocalizedMessage());
        }

        boolean joinSuccessful = true;
        try {
            while (pendingMessages.containsKey(message.getMsgSeq())) {
                synchronized (this) {
                    this.wait(100);
                }
            }
            if (System.currentTimeMillis() - sendTime > gameState.getConfig().getNodeTimeoutMs() || errorReceived) {
                synchronized (sync) {
                    inGame = false;
                }
                role = SnakesProto.NodeRole.VIEWER;
                exitGame();
                joinSuccessful = false;
                errorReceived = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return joinSuccessful;
    }

    private void init() {

        try {
            unicastSocket = new DatagramSocket(unicastPort);
        } catch (SocketException e) {
            System.out.println(e.getLocalizedMessage());
        }

        announceSender = new Thread(this::sendAnnouncements);
        pendingHandler = new Thread(this::handlePendingMessages);
        unicastListener = new Thread(this::listenUnicast);
        pingSender = new Thread(this::keepAlive);
        announceSender.start();
        pendingHandler.start();
        unicastListener.start();
        pingSender.start();


    }

    public void exitGame() {
        idInGame = -1;
        synchronized (sync) {
            inGame = false;
        }
        synchronized (pendingMessages) {
            pendingMessages.clear();
        }
        announceSender.interrupt();
        pendingHandler.interrupt();
        unicastListener.interrupt();
        pingSender.interrupt();
        if (unicastSocket != null) {
            unicastSocket.close();
        }

        role = SnakesProto.NodeRole.VIEWER;
        players.clear();
        snakes.clear();
        food.clear();
        playersToRemove.clear();
        msgSeq = 0;
        directionMap.clear();
        gameState = SnakesProto.GameState.newBuilder()
                .setConfig(SnakesProto.GameConfig.getDefaultInstance())
                .setStateOrder(0)
                .setPlayers(SnakesProto.GamePlayers.getDefaultInstance())
                .build();
    }


    public boolean createGame(int width, int height, int foodStatic, float foodPerPlayer, int stateDelayMs,
                       float deadProbFood, int pingDelayMs, int nodeTimeoutMs) {
        init();
        idInGame = 0;
        masterId = 0;
        SnakesProto.GameState.Snake snake = createNewSnake(idInGame);

        if (snake == null) {
            exitGame();
            return false;
        }

        role = SnakesProto.NodeRole.MASTER;
        gameState = SnakesProto.GameState
                .newBuilder()
                .setConfig(
                        SnakesProto.GameConfig
                                .newBuilder()
                                .setWidth(width)
                                .setHeight(height)
                                .setFoodStatic(foodStatic)
                                .setFoodPerPlayer(foodPerPlayer)
                                .setStateDelayMs(stateDelayMs)
                                .setDeadFoodProb(deadProbFood)
                                .setPingDelayMs(pingDelayMs)
                                .setNodeTimeoutMs(nodeTimeoutMs)
                                .build()
                )
                .addSnakes(snake)
                .setPlayers(SnakesProto.GamePlayers
                        .newBuilder()
                        .addPlayers(
                                0, SnakesProto.GamePlayer
                                        .newBuilder()
                                        .setId(idInGame)
                                        .setScore(2)
                                        .setRole(role)
                                        .setName(nodeName)
                                        .setIpAddress("")
                                        .setPort(unicastPort)
                                        .build()
                        ).build()
                )
                .setStateOrder(0)
                .build();

        for (SnakesProto.GamePlayer player : gameState.getPlayers().getPlayersList()) {
            players.put(player.getId(), player);
        }
        snakes.addAll(gameState.getSnakesList());
        synchronized (sync) {
            inGame = true;
        }
        return true;
    }


    public void stopWork() {
        messageCleaner.interrupt();
        announceListener.interrupt();
        multicastSocket.close();
        System.exit(0);
    }

}
