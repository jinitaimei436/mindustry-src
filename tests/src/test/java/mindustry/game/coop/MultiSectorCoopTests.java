package mindustry.game.coop;

import mindustry.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.coop.CampaignChunkTransport.*;
import mindustry.game.coop.CampaignPermissions.*;
import mindustry.game.coop.RemoteSectorBridge.*;
import mindustry.game.coop.SectorOccupancy.*;
import mindustry.game.strategy.core.*;
import mindustry.net.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for V2 multi-sector campaign multiplayer core systems:
 * permissions, occupancy & single-holder leases, chunked archive transport,
 * remote logistics bridge, anti-pause enforcement, and V2 version tag.
 */
public class MultiSectorCoopTests{

    @Test
    void versionForkTagIsV2(){
        assertEquals("v2.8.1", Version.forkVersion);
    }

    @Test
    void permissionsSupportGlobalDefaultsOverridesAdminBypassAndSerialization() throws IOException{
        CampaignPermissions perms = new CampaignPermissions();
        assertTrue(perms.allowGuestSectorSwitch);
        assertFalse(perms.allowGuestCoreLaunch);
        assertTrue(perms.allowGuestResearch);
        assertFalse(perms.allowGuestAbandon);

        // Global defaults apply to normal guests
        assertTrue(perms.canSwitchSector("guest-a", false));
        assertFalse(perms.canLaunchCore("guest-a", false));

        // Per-guest override takes precedence over global defaults
        GuestOverride ov = perms.getOrCreateOverride("guest-a");
        ov.allowSectorSwitch = false;
        ov.allowCoreLaunch = true;
        ov.allowAbandon = true;

        assertFalse(perms.canSwitchSector("guest-a", false));
        assertTrue(perms.canLaunchCore("guest-a", false));
        assertTrue(perms.canResearch("guest-a", false)); // falls back to global
        assertTrue(perms.canAbandon("guest-a", false));

        // Other guests remain unaffected
        assertTrue(perms.canSwitchSector("guest-b", false));
        assertFalse(perms.canLaunchCore("guest-b", false));

        // Admins always bypass restrictions
        assertTrue(perms.canSwitchSector("guest-a", true));
        assertTrue(perms.canLaunchCore("guest-b", true));
        assertTrue(perms.canAbandon("guest-b", true));

        // EffectivePermissions binary roundtrip
        EffectivePermissions resolved = perms.effectiveFor("guest-a", false);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(baos)){
            resolved.write(out);
        }
        EffectivePermissions decoded;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))){
            decoded = EffectivePermissions.read(in);
        }
        assertEquals(resolved.canSwitchSector, decoded.canSwitchSector);
        assertEquals(resolved.canLaunchCore, decoded.canLaunchCore);
        assertEquals(resolved.canResearch, decoded.canResearch);
        assertEquals(resolved.canAbandon, decoded.canAbandon);
    }

    @Test
    void occupancyTracksMultiSectorCountsSingleHolderLeasesAndSnapshotRoundtrip() throws IOException{
        SectorOccupancy hostOcc = new SectorOccupancy();
        long t0 = 10_000L;

        hostOcc.setPlayerPresence("host", "Host", "serpulo", 15, ClientSectorMode.HOST);
        hostOcc.setPlayerPresence("guest-1", "Alice", "serpulo", 15, ClientSectorMode.CO_LOCATED);
        hostOcc.setPlayerPresence("guest-2", "Bob", "serpulo", 175, ClientSectorMode.DETACHED_LOCAL);

        assertEquals(3, hostOcc.getTotalPlayerCount());
        assertEquals(2, hostOcc.getPlayerCount("serpulo", 15));
        assertEquals(1, hostOcc.getPlayerCount("serpulo", 175));
        assertEquals(0, hostOcc.getPlayerCount("serpulo", 99));
        assertTrue(hostOcc.isOccupied("serpulo", 175));
        assertFalse(hostOcc.isOccupied("serpulo", 99));
        assertEquals("serpulo#175", SectorOccupancy.sectorKey("serpulo", 175));

        // Single-holder lease acquisition and conflict rejection
        assertTrue(hostOcc.acquireLease("serpulo", 175, "guest-2", t0));
        assertFalse(hostOcc.acquireLease("serpulo", 175, "guest-1", t0 + 100));
        assertTrue(hostOcc.isLeasedByOther("serpulo", 175, "guest-1"));
        assertFalse(hostOcc.isLeasedByOther("serpulo", 175, "guest-2"));

        // Acquiring a new lease automatically releases the holder's previous lease
        assertTrue(hostOcc.acquireLease("serpulo", 200, "guest-2", t0 + 200));
        assertNull(hostOcc.getLeaseHolder("serpulo", 175));
        assertEquals("guest-2", hostOcc.getLeaseHolder("serpulo", 200));

        // Heartbeat keeps lease alive; stale leases expire cleanly
        hostOcc.heartbeatLease("serpulo", 200, "guest-2", t0 + 5_000L);
        assertTrue(hostOcc.expireStaleLeases(t0 + 10_000L, 15_000L).isEmpty());
        List<SectorLease> expired = hostOcc.expireStaleLeases(t0 + 25_000L, 15_000L);
        assertEquals(1, expired.size());
        assertEquals(200, expired.get(0).key.sectorId);
        assertNull(hostOcc.getLeaseHolder("serpulo", 200));

        // Compact binary snapshot roundtrip to guest client
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(baos)){
            hostOcc.writeSnapshot(out);
        }
        SectorOccupancy clientOcc = new SectorOccupancy();
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))){
            clientOcc.readSnapshot(in);
        }
        assertEquals(3, clientOcc.getTotalPlayerCount());
        assertEquals(2, clientOcc.getPlayerCount("serpulo", 15));
        assertEquals(1, clientOcc.getPlayerCount("serpulo", 175));
        assertEquals(0, clientOcc.getPlayerCount("serpulo", 99));

        // Removing a player releases their presence and leases
        hostOcc.acquireLease("serpulo", 175, "guest-2", t0 + 30_000L);
        hostOcc.removePlayer("guest-2");
        assertEquals(2, hostOcc.getTotalPlayerCount());
        assertEquals(0, hostOcc.getPlayerCount("serpulo", 175));
        assertNull(hostOcc.getLeaseHolder("serpulo", 175));
    }

    @Test
    void chunkTransportSplitsAndReassemblesOutOfOrderArchives(){
        CampaignChunkTransport sender = new CampaignChunkTransport();
        CampaignChunkTransport receiver = new CampaignChunkTransport();

        byte[] payload = new byte[40_000];
        new Random(42L).nextBytes(payload);

        int txId = sender.allocateTransferId();
        List<byte[]> chunks = sender.split(txId, CampaignChunkTransport.PURPOSE_HANDOFF_SYNC, "serpulo", 42, payload, 1024);
        assertEquals(40, chunks.size());

        // Deliver chunks out of order (reverse order) with a duplicate chunk
        CompletedTransfer done = null;
        for(int i = chunks.size() - 1; i >= 0; i--){
            if(i == 10){
                // Duplicate chunk must be ignored safely
                assertNull(receiver.receiveChunk("guest-1", chunks.get(chunks.size() - 1), 1000L));
            }
            CompletedTransfer res = receiver.receiveChunk("guest-1", chunks.get(i), 1000L + i);
            if(i > 0){
                assertNull(res);
            }else{
                done = res;
            }
        }

        assertNotNull(done);
        assertEquals(txId, done.transferId);
        assertEquals(CampaignChunkTransport.PURPOSE_HANDOFF_SYNC, done.purpose);
        assertEquals("serpulo", done.planetName);
        assertEquals(42, done.sectorId);
        assertArrayEquals(payload, done.data);
    }

    @Test
    void remoteSectorBridgeProjectsLiveTelemetryAndQueuesDeliveries(){
        RemoteSectorBridge bridge = new RemoteSectorBridge();
        long t0 = 50_000L;

        RemoteSectorSnapshot snap = new RemoteSectorSnapshot("serpulo", 85);
        snap.hasBase = true;
        snap.underAttack = false;
        snap.storageCapacity = 13000;
        snap.requireExplicitImportDemand = true;
        snap.coreItems.put("copper", 4500);
        snap.coreItems.put("silicon", 1200);
        snap.exportRates.put("copper", 12.5f);
        snap.importDemands.put("titanium", 6.0f);
        snap.updatedMs = t0;

        bridge.putSnapshot(snap);
        assertTrue(bridge.isRemoteForegroundActive("serpulo", 85, t0 + 2_000L));
        assertFalse(bridge.isRemoteForegroundActive("serpulo", 85, t0 + RemoteSectorBridge.SNAPSHOT_STALE_MS + 1L));

        SectorStrategyProfile profile = new SectorStrategyProfile(85);
        assertTrue(bridge.applyRemoteProfile(profile, "serpulo", 85, t0 + 1_000L));
        assertTrue(profile.foregroundActive);
        assertTrue(profile.hasBase);
        assertEquals(13000, profile.storageCapacity);
        assertEquals(4500f, profile.getStored("copper"), 1e-4f);
        assertEquals(12.5f, profile.foregroundExportRates.get("copper"), 1e-4f);
        assertEquals(6.0f, profile.importDemands.get("titanium"), 1e-4f);

        // Delivery queueing accumulates and drains atomically
        bridge.queueDelivery("serpulo", 85, "titanium", 40);
        bridge.queueDelivery("serpulo", 85, "titanium", 25);
        bridge.queueDelivery("serpulo", 85, "graphite", 15);

        Map<String, Integer> drained = bridge.drainDeliveries("serpulo", 85);
        assertEquals(65, drained.get("titanium"));
        assertEquals(15, drained.get("graphite"));
        assertTrue(bridge.drainDeliveries("serpulo", 85).isEmpty());
    }

    @Test
    void gameStateLocksPauseWhenMultiplayerOrPauseDisabled(){
        Net previousNet = Vars.net;
        try{
            Vars.net = null;
            GameState gs = new GameState();
            gs.rules = new Rules();

            // Singleplayer allows pausing normally
            assertFalse(gs.isPauseLocked());
            gs.set(State.playing);
            gs.set(State.paused);
            assertEquals(State.paused, gs.getState());

            // Rules.pauseDisabled strictly locks pause and coerces paused -> playing
            gs.rules.pauseDisabled = true;
            assertTrue(gs.isPauseLocked());
            gs.set(State.playing);
            gs.set(State.paused);
            assertEquals(State.playing, gs.getState());
        }finally{
            Vars.net = previousNet;
        }
    }
}
