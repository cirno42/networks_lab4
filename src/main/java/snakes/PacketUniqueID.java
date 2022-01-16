package snakes;

import lombok.Getter;
import lombok.Setter;

import java.net.DatagramPacket;

@Getter
@Setter
class PacketUniqueID {

    public PacketUniqueID(long firstTime, long lastTime, DatagramPacket packet, int receiverId) {
        this.firstTime = firstTime;
        this.lastTime = lastTime;
        this.packet = packet;
        this.receiverId = receiverId;
    }

    private int receiverId;
    private boolean failed = false;
    private long firstTime;
    private long lastTime;
    private DatagramPacket packet;
}
