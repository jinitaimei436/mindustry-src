package mindustry.game.coop;

import arc.util.*;

import java.io.*;
import java.util.*;

/**
 * Pure-Java chunked binary transfer helper for streaming compressed sector
 * archives (.msav) reliably between host and detached guests in V2 co-op.
 */
public class CampaignChunkTransport{
    public static final byte PURPOSE_SECTOR_ASSIGN = 1;
    public static final byte PURPOSE_SECTOR_SYNC = 2;
    public static final byte PURPOSE_HANDOFF_SYNC = 3;

    /** 12 KB per chunk stays safely below Mindustry's reliable packet buffer cap. */
    public static final int DEFAULT_CHUNK_SIZE = 12 * 1024;
    /** Hard cap per transfer (32 MB) to protect memory against malformed packets. */
    public static final int MAX_TRANSFER_BYTES = 32 * 1024 * 1024;

    private int nextTransferId = 1;
    private final HashMap<String, IncomingTransfer> incoming = new HashMap<>();

    public static final class CompletedTransfer{
        public final int transferId;
        public final byte purpose;
        public final String planetName;
        public final int sectorId;
        public final byte[] data;

        public CompletedTransfer(int transferId, byte purpose, String planetName, int sectorId, byte[] data){
            this.transferId = transferId;
            this.purpose = purpose;
            this.planetName = planetName;
            this.sectorId = sectorId;
            this.data = data;
        }
    }

    private static final class IncomingTransfer{
        final int transferId;
        final byte purpose;
        final String planetName;
        final int sectorId;
        final int totalChunks;
        final byte[][] chunks;
        int receivedCount = 0;
        int totalBytes = 0;
        long lastActivityMs;

        IncomingTransfer(int transferId, byte purpose, String planetName, int sectorId, int totalChunks, long nowMs){
            this.transferId = transferId;
            this.purpose = purpose;
            this.planetName = planetName;
            this.sectorId = sectorId;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
            this.lastActivityMs = nowMs;
        }
    }

    public synchronized int allocateTransferId(){
        return nextTransferId++;
    }

    public List<byte[]> split(int transferId, byte purpose, String planetName, int sectorId, byte[] fullData){
        return split(transferId, purpose, planetName, sectorId, fullData, DEFAULT_CHUNK_SIZE);
    }

    public List<byte[]> split(int transferId, byte purpose, String planetName, int sectorId, byte[] fullData, int chunkSize){
        if(fullData == null) fullData = new byte[0];
        if(chunkSize <= 0) chunkSize = DEFAULT_CHUNK_SIZE;
        int totalChunks = Math.max(1, (fullData.length + chunkSize - 1) / chunkSize);
        List<byte[]> result = new ArrayList<>(totalChunks);

        for(int i = 0; i < totalChunks; i++){
            int offset = i * chunkSize;
            int len = Math.min(chunkSize, Math.max(0, fullData.length - offset));
            try{
                ByteArrayOutputStream baos = new ByteArrayOutputStream(len + 64);
                DataOutputStream out = new DataOutputStream(baos);
                out.writeInt(transferId);
                out.writeByte(purpose);
                out.writeUTF(planetName == null ? "" : planetName);
                out.writeInt(sectorId);
                out.writeInt(i);
                out.writeInt(totalChunks);
                out.writeInt(len);
                if(len > 0){
                    out.write(fullData, offset, len);
                }
                out.flush();
                result.add(baos.toByteArray());
            }catch(IOException e){
                throw new RuntimeException("Failed to split sector chunk", e);
            }
        }
        return result;
    }

    /**
     * Ingests a single received chunk. Returns a {@link CompletedTransfer} when
     * the final missing chunk arrives, or {@code null} if more chunks are pending.
     */
    public synchronized @Nullable CompletedTransfer receiveChunk(String senderKey, byte[] chunkPacket, long nowMs){
        if(senderKey == null || chunkPacket == null || chunkPacket.length == 0) return null;

        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(chunkPacket))){
            int transferId = in.readInt();
            byte purpose = in.readByte();
            String planetName = in.readUTF();
            int sectorId = in.readInt();
            int chunkIndex = in.readInt();
            int totalChunks = in.readInt();
            int len = in.readInt();

            if(totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks || len < 0 || len > MAX_TRANSFER_BYTES){
                return null;
            }

            byte[] payload = new byte[len];
            if(len > 0){
                in.readFully(payload);
            }

            String key = senderKey + ":" + transferId;
            IncomingTransfer tx = incoming.get(key);
            if(tx == null){
                tx = new IncomingTransfer(transferId, purpose, planetName, sectorId, totalChunks, nowMs);
                incoming.put(key, tx);
            }else if(tx.totalChunks != totalChunks || tx.purpose != purpose || tx.sectorId != sectorId){
                incoming.remove(key);
                return null;
            }

            tx.lastActivityMs = nowMs;
            if(tx.chunks[chunkIndex] == null){
                if(tx.totalBytes + len > MAX_TRANSFER_BYTES){
                    incoming.remove(key);
                    return null;
                }
                tx.chunks[chunkIndex] = payload;
                tx.receivedCount++;
                tx.totalBytes += len;
            }

            if(tx.receivedCount == tx.totalChunks){
                incoming.remove(key);
                byte[] assembled = new byte[tx.totalBytes];
                int pos = 0;
                for(byte[] part : tx.chunks){
                    if(part != null && part.length > 0){
                        System.arraycopy(part, 0, assembled, pos, part.length);
                        pos += part.length;
                    }
                }
                return new CompletedTransfer(tx.transferId, tx.purpose, tx.planetName, tx.sectorId, assembled);
            }
            return null;
        }catch(IOException e){
            return null;
        }
    }

    public synchronized void pruneExpired(long nowMs, long timeoutMs){
        incoming.values().removeIf(tx -> nowMs - tx.lastActivityMs > timeoutMs);
    }

    public synchronized void clearSender(String senderKey){
        if(senderKey == null) return;
        String prefix = senderKey + ":";
        incoming.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public synchronized void clear(){
        incoming.clear();
    }
}
