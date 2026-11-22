package mindustry.net;

import arc.util.io.*;

/**
 * Custom reliable control & archive-chunk packet for V2 multi-sector campaign co-op.
 * Registered directly in {@link Net} so it functions identically on the host,
 * co-located guests, and detached locally-simulating guests without requiring
 * annotation-processor regeneration of generated Call stubs.
 */
public class CampaignPacket extends Packet{
    public static final byte KIND_OCCUPANCY_SNAPSHOT = 1;
    public static final byte KIND_PERMISSIONS_SYNC = 2;
    public static final byte KIND_CLIENT_REQUEST = 3;
    public static final byte KIND_HOST_CONTROL = 4;
    public static final byte KIND_ARCHIVE_CHUNK = 5;
    public static final byte KIND_CROSS_SECTOR_CHAT = 6;
    public static final byte KIND_STRATEGY_SYNC = 7;
    public static final byte KIND_LOGISTICS_DELIVERY = 8;

    public byte kind;
    public byte[] data = new byte[0];

    public CampaignPacket(){
    }

    public CampaignPacket(byte kind, byte[] data){
        this.kind = kind;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public int getPriority(){
        // High priority ensures control & handoff packets are never stalled behind world loading.
        return priorityHigh;
    }

    @Override
    public boolean allow(boolean server){
        return true;
    }

    @Override
    public void write(Writes buffer){
        buffer.b(kind);
        buffer.i(data.length);
        buffer.b(data);
    }

    @Override
    public void read(Reads buffer){
        kind = buffer.b();
        int len = buffer.i();
        if(len < 0 || len > 1024 * 1024){
            data = new byte[0];
            return;
        }
        data = new byte[len];
        buffer.b(data);
    }
}
