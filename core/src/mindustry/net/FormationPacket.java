package mindustry.net;

import arc.util.io.*;

/**
 * Reliable control & synchronization packet for V2.1 persistent tactical unit formations.
 * Registered directly in {@link Net} so multiplayer formation creation, member removal,
 * disbanding, and full snapshot sync work without regenerating annotation-processor Call stubs.
 */
public class FormationPacket extends Packet{
    public static final byte OP_CREATE = 1;
    public static final byte OP_DISBAND = 2;
    public static final byte OP_REMOVE_MEMBER = 3;
    public static final byte OP_FULL_SYNC = 4;

    public byte op;
    public byte[] data = new byte[0];

    public FormationPacket(){
    }

    public FormationPacket(byte op, byte[] data){
        this.op = op;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public int getPriority(){
        return priorityNormal;
    }

    @Override
    public boolean allow(boolean server){
        return true;
    }

    @Override
    public void write(Writes buffer){
        buffer.b(op);
        buffer.i(data.length);
        buffer.b(data);
    }

    @Override
    public void read(Reads buffer){
        op = buffer.b();
        int len = buffer.i();
        if(len < 0 || len > 512 * 1024){
            data = new byte[0];
            return;
        }
        data = new byte[len];
        buffer.b(data);
    }
}
