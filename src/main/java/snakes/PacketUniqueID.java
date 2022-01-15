package snakes;

import lombok.Getter;
import lombok.Setter;

import java.net.DatagramPacket;
import java.net.InetAddress;

class PacketUniqueID {

    PacketUniqueID(long firstTime, long lastTime, DatagramPacket packet, int receiverId) {
        this.firstTime = firstTime;
        this.lastTime = lastTime;
        this.packet = packet;
        this.receiverId = receiverId;
    }

    void changeDestination(InetAddress ip, int port, int receiverId) {
        this.receiverId = receiverId;
        packet.setPort(port);
        packet.setAddress(ip);
    }

    @Getter
    @Setter
    private int receiverId;
    @Getter
    @Setter
    private boolean failed = false;
    @Getter
    private long firstTime;
    @Getter
    @Setter
    private long lastTime;
    @Getter
    private DatagramPacket packet;
}
