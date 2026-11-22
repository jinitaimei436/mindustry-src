package mindustry.game.coop;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.Saves.*;
import mindustry.game.coop.CampaignChunkTransport.*;
import mindustry.game.coop.CampaignPermissions.*;
import mindustry.game.coop.RemoteSectorBridge.*;
import mindustry.game.coop.SectorOccupancy.*;
import mindustry.game.power.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.io.SaveIO.*;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.world.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Central V2 coordinator for multi-sector campaign multiplayer.
 *
 * Host acts as the authoritative Campaign Master (saves, leases, permissions,
 * occupancy map, shared tech tree, and planet-wide GlobalStrategyManager).
 * Guests can either co-play the host's current sector via native sync or
 * detach into independent sectors with local simulation and periodic/event-driven
 * compressed archive (.msav) sync back to the host.
 */
public class MultiSectorCoordinator{
    private static final MultiSectorCoordinator instance = new MultiSectorCoordinator();

    // Client -> Host request opcodes
    private static final byte REQ_SWITCH_SECTOR = 1;
    private static final byte REQ_LAUNCH_SECTOR = 2;
    private static final byte REQ_RESEARCH_UNLOCK = 3;
    private static final byte REQ_ABANDON_SECTOR = 4;
    private static final byte REQ_HEARTBEAT = 5;
    private static final byte REQ_LEAVE_DETACHED = 6;

    // Host -> Client control opcodes
    private static final byte CTRL_DENIED = 1;
    private static final byte CTRL_ENTER_DETACHED_STAY = 2;
    private static final byte CTRL_ENTER_DETACHED_NEW = 3;
    private static final byte CTRL_REQUEST_HANDOFF = 4;
    private static final byte CTRL_TECH_UNLOCKED = 5;
    private static final byte CTRL_SECTOR_ABANDONED = 6;
    private static final byte CTRL_INFO_TOAST = 7;

    private static final long LEASE_TIMEOUT_MS = 45_000L;
    private static final long HANDOFF_TIMEOUT_MS = 8_000L;
    private static final float DETACHED_SAVE_SYNC_INTERVAL_SEC = 20f;
    private static final float DETACHED_STRATEGY_SYNC_INTERVAL_SEC = 2f;
    private static final float HOST_MAINTENANCE_INTERVAL_SEC = 2f;

    public final CampaignPermissions permissions = new CampaignPermissions();
    public final SectorOccupancy occupancy = new SectorOccupancy();
    public final RemoteSectorBridge strategyBridge = new RemoteSectorBridge();
    private final CampaignChunkTransport chunkTransport = new CampaignChunkTransport();

    /** Resolved permissions received by a guest client from the host. */
    private EffectivePermissions clientPermissions = new EffectivePermissions(true, false, true, false);
    /** Host-side lookup of all active guest connections (both co-located and detached) by UUID. */
    private final ObjectMap<String, NetConnection> guestConnections = new ObjectMap<>();
    /** Host-side lookup of all active guest Player objects (retained even while detached). */
    private final ObjectMap<String, Player> guestPlayers = new ObjectMap<>();
    /** Client-side cache of host-authoritative sector base/capture/attack flags (avoids mutating local singleplayer saves). */
    private final ObjectMap<String, RemoteSectorSummary> remoteSectorSummaries = new ObjectMap<>();

    private boolean listenersRegistered = false;
    private float saveSyncTimer = 0f;
    private float strategySyncTimer = 0f;
    private float hostMaintTimer = 0f;
    private boolean applyingRemoteTech = false;

    private @Nullable PendingHandoff pendingHandoff;

    private static final class RemoteSectorSummary{
        final boolean hasBase;
        final boolean captured;
        final boolean attacked;

        RemoteSectorSummary(boolean hasBase, boolean captured, boolean attacked){
            this.hasBase = hasBase;
            this.captured = captured;
            this.attacked = attacked;
        }
    }

    private static final class PendingHandoff{
        final String planetName;
        final int sectorId;
        final String holderUuid;
        final Runnable onComplete;
        final long startedMs;
        boolean completed = false;

        PendingHandoff(String planetName, int sectorId, String holderUuid, Runnable onComplete, long startedMs){
            this.planetName = planetName;
            this.sectorId = sectorId;
            this.holderUuid = holderUuid;
            this.onComplete = onComplete;
            this.startedMs = startedMs;
        }
    }

    public static MultiSectorCoordinator get(){
        return instance;
    }

    private MultiSectorCoordinator(){
    }

    /** Registers network packet handlers and campaign lifecycle hooks once. */
    public void init(){
        if(listenersRegistered || net == null) return;
        listenersRegistered = true;

        net.handleServer(CampaignPacket.class, this::handleServerPacket);
        net.handleClient(CampaignPacket.class, this::handleClientPacket);

        Events.on(SectorCaptureEvent.class, e -> {
            if(net != null && net.isDetachedClient() && e.sector != null){
                uploadDetachedSectorArchive(e.sector, CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
            }
        });

        Events.on(WaveEvent.class, e -> {
            if(net != null && net.isDetachedClient() && state.hasSector()){
                uploadDetachedSectorArchive(state.getSector(), CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
            }
        });
    }

    public void reset(){
        occupancy.clear();
        strategyBridge.clear();
        chunkTransport.clear();
        guestConnections.clear();
        guestPlayers.clear();
        remoteSectorSummaries.clear();
        pendingHandoff = null;
        saveSyncTimer = 0f;
        strategySyncTimer = 0f;
        hostMaintTimer = 0f;
        if(net != null){
            net.setDetachedLocalSim(false);
        }
    }

    public boolean hasSyncedBase(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        if(state.isGame() && state.rules.sector == sector && !state.gameOver) return true;
        RemoteSectorSummary sum = remoteSectorSummaries.get(SectorOccupancy.sectorKey(sector.planet.name, sector.id));
        return sum != null && sum.hasBase;
    }

    public boolean isSyncedCaptured(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        if(sector.isBeingPlayed()) return !state.rules.waves && !state.rules.attackMode;
        RemoteSectorSummary sum = remoteSectorSummaries.get(SectorOccupancy.sectorKey(sector.planet.name, sector.id));
        return sum != null && sum.captured;
    }

    public boolean isSyncedAttacked(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        if(sector.isBeingPlayed()) return state.rules.waves || state.rules.attackMode;
        RemoteSectorSummary sum = remoteSectorSummaries.get(SectorOccupancy.sectorKey(sector.planet.name, sector.id));
        return sum != null && sum.attacked;
    }

    public EffectivePermissions getClientPermissions(){
        return clientPermissions;
    }

    public int getTotalOnlinePlayers(){
        int tracked = occupancy.getTotalPlayerCount();
        if(net != null && net.server()){
            return Math.max(tracked, 1 + guestConnections.size);
        }
        if(net != null && net.isConnectedClient()){
            return Math.max(tracked, 2);
        }
        return tracked;
    }

    public int getSectorPlayerCount(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return 0;
        if(net != null && net.active()){
            int count = occupancy.getPlayerCount(sector.planet.name, sector.id);
            if(count > 0) return count;
        }
        return sector.isBeingPlayed() ? 1 : 0;
    }

    public boolean isSectorOccupied(@Nullable Sector sector){
        return getSectorPlayerCount(sector) > 0;
    }

    public boolean hasRemoteLease(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        return occupancy.getLeaseHolder(sector.planet.name, sector.id) != null;
    }

    public Seq<Player> getAllConnectedPlayers(){
        Seq<Player> result = new Seq<>();
        if(!headless && player != null){
            result.add(player);
        }
        for(Player p : guestPlayers.values()){
            if(p != null && !result.contains(p)){
                result.add(p);
            }
        }
        for(Player p : Groups.player){
            if(p != null && !result.contains(p)){
                result.add(p);
            }
        }
        return result;
    }

    // ==================== HOST LIFECYCLE ====================

    public void onHostStarted(){
        init();
        occupancy.clear();
        strategyBridge.clear();
        guestConnections.clear();
        guestPlayers.clear();
        updateHostPresence();
    }

    public void updateHostPresence(){
        if(net == null || !net.server() || !state.isCampaign() || !state.hasSector()) return;
        Sector sec = state.getSector();
        String hostUuid = getHostUuid();
        String hostName = (!headless && player != null) ? player.plainName() : "Host";
        occupancy.setPlayerPresence(hostUuid, hostName, sec.planet.name, sec.id, ClientSectorMode.HOST);
        occupancy.releaseSectorLease(sec.planet.name, sec.id);
        strategyBridge.removeSector(sec.planet.name, sec.id);
        broadcastOccupancyAndSectorSummaries();
    }

    public void onGuestJoined(Player guest){
        if(net == null || !net.server() || guest == null || guest.con == null) return;
        init();

        guest.con.sectorMode = ClientSectorMode.CO_LOCATED;
        guestConnections.put(guest.uuid(), guest.con);
        guestPlayers.put(guest.uuid(), guest);

        if(state.isCampaign() && state.hasSector()){
            Sector sec = state.getSector();
            updateHostPresence();
            occupancy.setPlayerPresence(guest.uuid(), guest.plainName(), sec.planet.name, sec.id, ClientSectorMode.CO_LOCATED);
            sendPermissionsTo(guest.con, guest);
            broadcastOccupancyAndSectorSummaries();
        }

        // Enforce V2 anti-pause immediately when multiplayer becomes active (>1 player).
        if(state.isPaused() && state.isPauseLocked()){
            state.set(State.playing);
        }
    }

    public void onGuestDisconnected(Player guest){
        if(guest == null) return;
        String uuid = guest.uuid();
        NetConnection con = guestConnections.remove(uuid);
        guestPlayers.remove(uuid);
        chunkTransport.clearSender(uuid);

        PlayerPresence prev = occupancy.getPresence(uuid);
        if(prev != null && prev.mode == ClientSectorMode.DETACHED_LOCAL){
            strategyBridge.removeSector(prev.planetName, prev.sectorId);
        }
        occupancy.removePlayer(uuid);

        // If host was waiting on handoff from this guest, proceed immediately with last saved archive.
        if(pendingHandoff != null && uuid.equals(pendingHandoff.holderUuid)){
            completePendingHandoff();
        }

        if(con != null){
            con.sectorMode = ClientSectorMode.CO_LOCATED;
        }
        if(net != null && net.server()){
            broadcastOccupancyAndSectorSummaries();
        }
    }

    /**
     * Called on Host before switching sectors. If the target sector is currently
     * leased to a detached guest, requests an immediate archive handoff before
     * invoking {@code proceed}.
     */
    public boolean requestHostSwitchSector(Sector target, Runnable proceed){
        if(net == null || !net.server() || target == null || !state.isCampaign()){
            proceed.run();
            return true;
        }

        String holderUuid = occupancy.getLeaseHolder(target.planet.name, target.id);
        if(holderUuid == null){
            proceed.run();
            return true;
        }

        NetConnection holderCon = guestConnections.get(holderUuid);
        if(holderCon == null || !holderCon.isConnected()){
            occupancy.releaseSectorLease(target.planet.name, target.id);
            strategyBridge.removeSector(target.planet.name, target.id);
            proceed.run();
            return true;
        }

        holderCon.sectorMode = ClientSectorMode.TRANSFERRING;
        pendingHandoff = new PendingHandoff(target.planet.name, target.id, holderUuid, proceed, Time.millis());

        if(!headless && ui != null){
            ui.loadfrag.show("正在从队友同步区块存档...");
        }
        sendHostControl(holderCon, CTRL_REQUEST_HANDOFF, out -> {
            out.writeUTF(target.planet.name);
            out.writeInt(target.id);
        });
        return false;
    }

    /**
     * Called on Host immediately before leaving {@code leavingSector} for {@code targetSector}.
     * Hands off a lease for {@code leavingSector} to a co-located guest so the guest
     * stays in {@code leavingSector} instead of being forcibly dragged by WorldReloader.
     */
    public void onHostLeavingSector(@Nullable Sector leavingSector, @Nullable Sector targetSector){
        if(net == null || !net.server() || leavingSector == null || targetSector == null || leavingSector == targetSector){
            return;
        }

        // Persist the host's latest sector save before detaching guests.
        if(control != null && control.saves != null && control.saves.getCurrent() != null && state.isGame()){
            try{
                control.saves.getCurrent().save();
            }catch(Throwable t){
                Log.err("Failed to save sector before host switch", t);
            }
        }

        long now = Time.millis();
        boolean leaseGranted = occupancy.getLeaseHolder(leavingSector.planet.name, leavingSector.id) != null;

        Seq<Player> coLocated = new Seq<>();
        for(Player p : Groups.player){
            if(p.isLocal() || p.con == null) continue;
            if(p.con.isCoLocated()){
                coLocated.add(p);
            }
        }

        for(Player guest : coLocated){
            if(!leaseGranted && occupancy.acquireLease(leavingSector.planet.name, leavingSector.id, guest.uuid(), now)){
                leaseGranted = true;
                guest.con.sectorMode = ClientSectorMode.DETACHED_LOCAL;
                guestPlayers.put(guest.uuid(), guest);
                guestConnections.put(guest.uuid(), guest.con);

                // Detach from host's live Groups so WorldReloader skips pulling this guest.
                guest.clearUnit();
                guest.remove();

                occupancy.setPlayerPresence(
                    guest.uuid(), guest.plainName(),
                    leavingSector.planet.name, leavingSector.id,
                    ClientSectorMode.DETACHED_LOCAL
                );

                sendHostControl(guest.con, CTRL_ENTER_DETACHED_STAY, out -> {
                    out.writeUTF(leavingSector.planet.name);
                    out.writeInt(leavingSector.id);
                });
            }else{
                // Secondary co-located guest follows host to targetSector because single-lease is already held.
                sendHostControl(guest.con, CTRL_INFO_TOAST, out -> out.writeUTF("原区块已有队友留守，已随房主切换区块"));
            }
        }
    }

    /**
     * Called on Host right after loading a new sector. Updates occupancy and
     * seamlessly re-attaches any guest who handed off this sector back into CO_LOCATED sync.
     */
    public void onHostEnteredSector(Sector sector){
        if(net == null || !net.server() || sector == null) return;

        occupancy.releaseSectorLease(sector.planet.name, sector.id);
        strategyBridge.removeSector(sector.planet.name, sector.id);

        // Re-attach any transferring guest who just handed this sector back to the host.
        for(ObjectMap.Entry<String, NetConnection> entry : guestConnections){
            NetConnection con = entry.value;
            Player guest = guestPlayers.get(entry.key);
            if(con == null || guest == null || !con.isConnected()) continue;

            PlayerPresence pres = occupancy.getPresence(entry.key);
            if(con.sectorMode == ClientSectorMode.TRANSFERRING && pres != null
                && pres.sectorId == sector.id && pres.planetName.equals(sector.planet.name)){
                reattachGuestToHostSector(con, guest, sector);
            }else if(con.isCoLocated()){
                occupancy.setPlayerPresence(guest.uuid(), guest.plainName(), sector.planet.name, sector.id, ClientSectorMode.CO_LOCATED);
            }
        }

        updateHostPresence();
    }

    public void broadcastPermissions(){
        if(net == null || !net.server()) return;
        for(ObjectMap.Entry<String, NetConnection> entry : guestConnections){
            NetConnection con = entry.value;
            Player guest = guestPlayers.get(entry.key);
            if(con != null && con.isConnected() && guest != null){
                sendPermissionsTo(con, guest);
            }
        }
    }

    public void broadcastTechUnlock(UnlockableContent unlocked){
        if(unlocked == null || applyingRemoteTech || net == null) return;
        if(net.server()){
            for(NetConnection con : guestConnections.values()){
                if(con != null && con.isConnected()){
                    sendHostControl(con, CTRL_TECH_UNLOCKED, out -> {
                        out.writeByte(unlocked.getContentType().ordinal());
                        out.writeUTF(unlocked.name);
                    });
                }
            }
        }
    }

    public void broadcastChatToDetached(String formattedMessage){
        if(net == null || !net.server() || formattedMessage == null) return;
        byte[] payload = encodeUtf(formattedMessage);
        for(NetConnection con : guestConnections.values()){
            if(con != null && con.isConnected() && !con.isCoLocated()){
                con.send(new CampaignPacket(CampaignPacket.KIND_CROSS_SECTOR_CHAT, payload), true);
            }
        }
    }

    // ==================== CLIENT ACTIONS ====================

    public boolean canLocalGuestSwitchSector(){
        if(net == null || !net.isConnectedClient()) return true;
        return clientPermissions.canSwitchSector || (player != null && player.admin);
    }

    public boolean canLocalGuestLaunchCore(){
        if(net == null || !net.isConnectedClient()) return true;
        return clientPermissions.canLaunchCore || (player != null && player.admin);
    }

    public boolean canLocalGuestResearch(){
        if(net == null || !net.isConnectedClient()) return true;
        return clientPermissions.canResearch || (player != null && player.admin);
    }

    public boolean canLocalGuestAbandon(){
        if(net == null || !net.isConnectedClient()) return true;
        return clientPermissions.canAbandon || (player != null && player.admin);
    }

    public void requestGuestSwitchSector(Sector target){
        if(target == null || net == null || !net.isConnectedClient()) return;
        if(!canLocalGuestSwitchSector()){
            if(ui != null) ui.showInfoFade("房主未开启「房客切换区块」权限");
            return;
        }
        // Flush current detached sector before switching away.
        if(net.isDetachedClient() && state.hasSector()){
            uploadDetachedSectorArchive(state.getSector(), CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
        }
        if(ui != null) ui.loadfrag.show("正在请求切换区块...");
        sendClientRequest(REQ_SWITCH_SECTOR, out -> {
            out.writeUTF(target.planet.name);
            out.writeInt(target.id);
        });
    }

    public void requestGuestLaunchSector(@Nullable Sector origin, Sector target){
        if(target == null || net == null || !net.isConnectedClient()) return;
        if(!canLocalGuestLaunchCore()){
            if(ui != null) ui.showInfoFade("房主未开启「房客发射核心」权限");
            return;
        }
        if(net.isDetachedClient() && state.hasSector()){
            uploadDetachedSectorArchive(state.getSector(), CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
        }
        if(ui != null) ui.loadfrag.show("正在向房主请求核心发射...");
        sendClientRequest(REQ_LAUNCH_SECTOR, out -> {
            out.writeUTF(target.planet.name);
            out.writeInt(origin == null ? -1 : origin.id);
            out.writeInt(target.id);
        });
    }

    public void requestGuestResearch(UnlockableContent targetContent){
        if(targetContent == null || net == null || !net.isConnectedClient()) return;
        if(!canLocalGuestResearch()){
            if(ui != null) ui.showInfoFade("房主未开启「房客研究科技树」权限");
            return;
        }
        sendClientRequest(REQ_RESEARCH_UNLOCK, out -> {
            out.writeByte(targetContent.getContentType().ordinal());
            out.writeUTF(targetContent.name);
        });
    }

    public void requestGuestAbandonSector(Sector target){
        if(target == null || net == null || !net.isConnectedClient()) return;
        if(!canLocalGuestAbandon()){
            if(ui != null) ui.showInfoFade("房主未开启「房客放弃区块」权限");
            return;
        }
        sendClientRequest(REQ_ABANDON_SECTOR, out -> {
            out.writeUTF(target.planet.name);
            out.writeInt(target.id);
        });
    }

    public void sendDetachedChatMessage(String rawMessage){
        if(rawMessage == null || rawMessage.isEmpty() || net == null || !net.isConnectedClient()) return;
        net.send(new CampaignPacket(CampaignPacket.KIND_CROSS_SECTOR_CHAT, encodeUtf(rawMessage)), true);
    }

    /** V2.5: Client requests the authoritative Host to toggle a cross-sector power link. */
    public void requestTogglePowerLink(String planetName, int sectorAId, int sectorBId){
        if(net == null || !net.isConnectedClient() || planetName == null) return;
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(48);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF(planetName);
            out.writeInt(sectorAId);
            out.writeInt(sectorBId);
            out.flush();
            net.send(new CampaignPacket(CampaignPacket.KIND_POWER_LINK_REQUEST, baos.toByteArray()), true);
        }catch(IOException e){
            Log.err("Failed to send V2.5 power link request", e);
        }
    }

    public void flushBeforeClientDisconnect(){
        if(net != null && net.isDetachedClient() && state.hasSector()){
            Sector sec = state.getSector();
            uploadDetachedSectorArchive(sec, CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
            sendClientRequest(REQ_LEAVE_DETACHED, out -> {
                out.writeUTF(sec.planet.name);
                out.writeInt(sec.id);
            });
        }
    }

    // ==================== PERIODIC UPDATE ====================

    public void update(float dtSeconds){
        if(net == null || !net.active()) return;

        if(net.server()){
            updateHost(dtSeconds);
        }else if(net.isDetachedClient()){
            updateDetachedClient(dtSeconds);
        }
    }

    private void updateHost(float dtSeconds){
        long now = Time.millis();

        // Check pending handoff timeout so host never deadlocks on a dropped client.
        if(pendingHandoff != null && !pendingHandoff.completed && (now - pendingHandoff.startedMs) > HANDOFF_TIMEOUT_MS){
            Log.warn("Timed out waiting for sector handoff from @; proceeding with last synced save.", pendingHandoff.holderUuid);
            completePendingHandoff();
        }

        hostMaintTimer += dtSeconds;
        if(hostMaintTimer < HOST_MAINTENANCE_INTERVAL_SEC) return;
        hostMaintTimer = 0f;

        // Detect disconnected detached guests & deliver queued global logistics cargo.
        Seq<Player> disconnected = new Seq<>();
        for(ObjectMap.Entry<String, NetConnection> entry : guestConnections){
            NetConnection con = entry.value;
            Player guest = guestPlayers.get(entry.key);
            if(con == null || !con.isConnected()){
                if(guest != null) disconnected.add(guest);
                continue;
            }
            if(con.sectorMode == ClientSectorMode.DETACHED_LOCAL){
                flushRemoteLogisticsDeliveriesFor(entry.key, con);
            }
        }
        for(Player p : disconnected){
            NetServer.onDisconnect(p, "detached-timeout");
        }

        // Expire stale leases if a detached client stopped sending heartbeats.
        List<SectorLease> expired = occupancy.expireStaleLeases(now, LEASE_TIMEOUT_MS);
        for(SectorLease lease : expired){
            strategyBridge.removeSector(lease.key.planetName, lease.key.sectorId);
        }
        chunkTransport.pruneExpired(now, LEASE_TIMEOUT_MS);
        // Periodically broadcast V2.5 cross-sector power grid telemetry & topology to all connected clients.
        broadcastPowerGridSnapshot();
    }

    /** Immediately flushes all queued global-logistics deliveries to active detached guests. */
    public void flushRemoteLogisticsDeliveries(){
        if(net == null || !net.server()) return;
        for(ObjectMap.Entry<String, NetConnection> entry : guestConnections){
            NetConnection con = entry.value;
            if(con != null && con.isConnected() && con.sectorMode == ClientSectorMode.DETACHED_LOCAL){
                flushRemoteLogisticsDeliveriesFor(entry.key, con);
            }
        }
    }

    private void flushRemoteLogisticsDeliveriesFor(String uuid, NetConnection con){
        PlayerPresence pres = occupancy.getPresence(uuid);
        if(pres == null) return;
        Map<String, Integer> deliveries = strategyBridge.drainDeliveries(pres.planetName, pres.sectorId);
        if(!deliveries.isEmpty()){
            byte[] data = RemoteSectorBridge.serializeDeliveries(pres.planetName, pres.sectorId, deliveries);
            con.send(new CampaignPacket(CampaignPacket.KIND_LOGISTICS_DELIVERY, data), true);
        }
    }

    private void updateDetachedClient(float dtSeconds){
        if(!state.isGame() || !state.hasSector()) return;
        Sector sec = state.getSector();

        strategySyncTimer += dtSeconds;
        if(strategySyncTimer >= DETACHED_STRATEGY_SYNC_INTERVAL_SEC){
            strategySyncTimer = 0f;
            sendClientRequest(REQ_HEARTBEAT, out -> {
                out.writeUTF(sec.planet.name);
                out.writeInt(sec.id);
            });
            byte[] snap = RemoteSectorBridge.serializeLocalStrategySnapshot(sec);
            net.send(new CampaignPacket(CampaignPacket.KIND_STRATEGY_SYNC, snap), true);
            try{
                byte[] powerSnap = SectorPowerGridManager.get().serializeLocalSectorTelemetry(sec);
                net.send(new CampaignPacket(CampaignPacket.KIND_POWER_TELEMETRY, powerSnap), true);
            }catch(IOException e){
                Log.err("Failed to serialize V2.5 detached sector power telemetry", e);
            }
        }

        saveSyncTimer += dtSeconds;
        if(saveSyncTimer >= DETACHED_SAVE_SYNC_INTERVAL_SEC){
            saveSyncTimer = 0f;
            uploadDetachedSectorArchive(sec, CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
        }
    }

    // ==================== PACKET HANDLING (HOST) ====================

    private void handleServerPacket(NetConnection con, CampaignPacket packet){
        if(con == null || packet == null) return;
        Player sender = con.player != null ? con.player : guestPlayers.get(con.uuid);
        if(sender == null) return;

        try{
            switch(packet.kind){
                case CampaignPacket.KIND_CLIENT_REQUEST -> handleServerClientRequest(con, sender, packet.data);
                case CampaignPacket.KIND_ARCHIVE_CHUNK -> handleServerArchiveChunk(con, sender, packet.data);
                case CampaignPacket.KIND_STRATEGY_SYNC -> {
                    RemoteSectorSnapshot snap = RemoteSectorBridge.deserializeSnapshot(packet.data, Time.millis());
                    if(sender.uuid().equals(occupancy.getLeaseHolder(snap.planetName, snap.sectorId))){
                        strategyBridge.putSnapshot(snap);
                    }
                }
                case CampaignPacket.KIND_CROSS_SECTOR_CHAT -> {
                    String msg = decodeUtf(packet.data);
                    if(msg != null && !msg.isEmpty()){
                        String formatted = netServer.chatFormatter.format(sender, msg);
                        if(!headless && ui != null){
                            ui.chatfrag.addMessage(formatted);
                        }
                        Call.sendMessage(formatted, msg, sender);
                        broadcastChatToDetached(formatted);
                    }
                }
                case CampaignPacket.KIND_POWER_TELEMETRY -> {
                    PlayerPresence pres = occupancy.getPresence(sender.uuid());
                    if(pres != null && sender.uuid().equals(occupancy.getLeaseHolder(pres.planetName, pres.sectorId))){
                        SectorPowerGridManager.get().applyRemoteSectorTelemetry(packet.data, pres.planetName, pres.sectorId);
                    }
                }
                case CampaignPacket.KIND_POWER_LINK_REQUEST -> {
                    try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data))){
                        String planetName = in.readUTF();
                        int aId = in.readInt();
                        int bId = in.readInt();
                        int res = SectorPowerGridManager.get().toggleLinkAuthoritative(planetName, aId, bId);
                        if(res != 0){
                            broadcastPowerGridSnapshot();
                            sendHostControl(con, CTRL_INFO_TOAST, out -> out.writeUTF(res > 0 ? "已连接跨区块电网" : "已断开跨区块电网连接"));
                        }else{
                            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("无法连接：仅相邻且建有巨浪区块电力枢纽的区块可连线"));
                        }
                    }
                }
                default -> {}
            }
        }catch(Throwable t){
            Log.err("Error handling V2 campaign server packet kind=" + packet.kind, t);
        }
    }

    private void handleServerClientRequest(NetConnection con, Player sender, byte[] data) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            byte op = in.readByte();
            switch(op){
                case REQ_SWITCH_SECTOR -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    handleGuestSwitchRequest(con, sender, planetName, sectorId);
                }
                case REQ_LAUNCH_SECTOR -> {
                    String planetName = in.readUTF();
                    int originId = in.readInt();
                    int targetId = in.readInt();
                    handleGuestLaunchRequest(con, sender, planetName, originId, targetId);
                }
                case REQ_RESEARCH_UNLOCK -> {
                    int typeOrd = in.readByte() & 0xFF;
                    String contentName = in.readUTF();
                    handleGuestResearchRequest(con, sender, typeOrd, contentName);
                }
                case REQ_ABANDON_SECTOR -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    handleGuestAbandonRequest(con, sender, planetName, sectorId);
                }
                case REQ_HEARTBEAT -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    occupancy.heartbeatLease(planetName, sectorId, sender.uuid(), Time.millis());
                }
                case REQ_LEAVE_DETACHED -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    if(sender.uuid().equals(occupancy.getLeaseHolder(planetName, sectorId))){
                        occupancy.releaseSectorLease(planetName, sectorId);
                        strategyBridge.removeSector(planetName, sectorId);
                        broadcastOccupancyAndSectorSummaries();
                    }
                }
                default -> {}
            }
        }
    }

    private void handleGuestSwitchRequest(NetConnection con, Player sender, String planetName, int sectorId){
        if(!permissions.canSwitchSector(sender.uuid(), sender.admin)){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("房主未允许你切换区块"));
            return;
        }
        Sector target = findSector(planetName, sectorId);
        if(target == null){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("目标区块不存在"));
            return;
        }

        // Case 1: Target is the host's current sector -> switch back to native CO_LOCATED sync.
        if(state.hasSector() && state.getSector() == target){
            occupancy.releaseLeasesHeldBy(sender.uuid());
            reattachGuestToHostSector(con, sender, target);
            broadcastOccupancyAndSectorSummaries();
            return;
        }

        // Case 2: Target must already have a base / save to switch without launching.
        if(!target.hasBase() || target.save == null || !target.save.file.exists()){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("目标区块尚未建立基地，请使用发射功能"));
            return;
        }

        // Case 3: Check single-holder remote lease.
        if(occupancy.isLeasedByOther(planetName, sectorId, sender.uuid())){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("该区块已有队友正在独立经营，暂不可同时进入"));
            return;
        }

        if(!occupancy.acquireLease(planetName, sectorId, sender.uuid(), Time.millis())){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("获取区块租约失败"));
            return;
        }

        // Detach guest from host's sector simulation and stream authoritative .msav.
        con.sectorMode = ClientSectorMode.DETACHED_LOCAL;
        sender.clearUnit();
        sender.remove();
        occupancy.setPlayerPresence(sender.uuid(), sender.plainName(), planetName, sectorId, ClientSectorMode.DETACHED_LOCAL);
        broadcastOccupancyAndSectorSummaries();

        byte[] archiveBytes = target.save.file.readBytes();
        sendArchiveChunksToClient(con, CampaignChunkTransport.PURPOSE_SECTOR_ASSIGN, planetName, sectorId, archiveBytes);
    }

    private void handleGuestLaunchRequest(NetConnection con, Player sender, String planetName, int originId, int targetId){
        if(!permissions.canLaunchCore(sender.uuid(), sender.admin)){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("房主未允许你发射核心"));
            return;
        }
        Sector target = findSector(planetName, targetId);
        if(target == null){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("目标区块不存在"));
            return;
        }
        if(state.hasSector() && state.getSector() == target){
            reattachGuestToHostSector(con, sender, target);
            broadcastOccupancyAndSectorSummaries();
            return;
        }
        if(occupancy.isLeasedByOther(planetName, targetId, sender.uuid())){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("该区块已有队友正在独立经营"));
            return;
        }
        if(!occupancy.acquireLease(planetName, targetId, sender.uuid(), Time.millis())){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("获取目标区块租约失败"));
            return;
        }

        con.sectorMode = ClientSectorMode.DETACHED_LOCAL;
        sender.clearUnit();
        sender.remove();
        occupancy.setPlayerPresence(sender.uuid(), sender.plainName(), planetName, targetId, ClientSectorMode.DETACHED_LOCAL);
        broadcastOccupancyAndSectorSummaries();

        sendHostControl(con, CTRL_ENTER_DETACHED_NEW, out -> {
            out.writeUTF(planetName);
            out.writeInt(originId);
            out.writeInt(targetId);
        });
    }

    private void handleGuestResearchRequest(NetConnection con, Player sender, int typeOrd, String contentName){
        if(!permissions.canResearch(sender.uuid(), sender.admin)){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("房主未允许你研究科技树"));
            return;
        }
        if(typeOrd < 0 || typeOrd >= ContentType.all.length) return;
        MappableContent raw = content.getByName(ContentType.all[typeOrd], contentName);
        if(!(raw instanceof UnlockableContent unlockable) || unlockable.techNode == null) return;

        TechTree.TechNode node = unlockable.techNode;
        if(unlockable.unlocked()){
            broadcastTechUnlock(unlockable);
            return;
        }
        if(node.parent != null && node.parent.content.locked()){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("前置科技尚未解锁"));
            return;
        }

        // Spend required items across the planet's campaign sectors (host authoritative).
        Planet planet = state.getPlanet();
        if(planet != null && node.requirements.length > 0){
            ItemSeq available = new ItemSeq();
            for(Sector sec : planet.sectors){
                if(sec.hasBase()) sec.items().each((it, amt) -> available.add(it, amt));
            }
            boolean allMet = true;
            for(int i = 0; i < node.requirements.length; i++){
                ItemStack req = node.requirements[i];
                ItemStack done = node.finishedRequirements[i];
                int needed = Math.max(0, req.amount - done.amount);
                int spend = Math.min(needed, available.get(req.item));
                if(spend > 0){
                    deductPlanetItem(planet, req.item, spend);
                    done.amount += spend;
                }
                if(done.amount < req.amount){
                    allMet = false;
                }
            }
            node.save();
            if(!allMet){
                sendHostControl(con, CTRL_INFO_TOAST, out -> out.writeUTF("已投入部分资源，尚需更多物资完成研发"));
                return;
            }
        }

        unlockable.unlock();
        node.save();
        broadcastTechUnlock(unlockable);
    }

    private void handleGuestAbandonRequest(NetConnection con, Player sender, String planetName, int sectorId){
        if(!permissions.canAbandon(sender.uuid(), sender.admin)){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("房主未允许你放弃区块"));
            return;
        }
        Sector target = findSector(planetName, sectorId);
        if(target == null || (state.hasSector() && state.getSector() == target)){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("无法放弃房主当前所在区块"));
            return;
        }
        if(occupancy.isLeasedByOther(planetName, sectorId, sender.uuid())){
            sendHostControl(con, CTRL_DENIED, out -> out.writeUTF("无法放弃其他队友正在经营的区块"));
            return;
        }
        target.info.hasCore = false;
        target.info.wasCaptured = false;
        if(target.save != null){
            target.save.delete();
            target.save = null;
        }
        target.saveInfo();
        occupancy.releaseSectorLease(planetName, sectorId);
        strategyBridge.removeSector(planetName, sectorId);
        broadcastOccupancyAndSectorSummaries();
        sendHostControl(con, CTRL_SECTOR_ABANDONED, out -> {
            out.writeUTF(planetName);
            out.writeInt(sectorId);
        });
    }

    private void handleServerArchiveChunk(NetConnection con, Player sender, byte[] chunkData){
        CompletedTransfer transfer = chunkTransport.receiveChunk(sender.uuid(), chunkData, Time.millis());
        if(transfer == null) return;

        Sector sector = findSector(transfer.planetName, transfer.sectorId);
        if(sector == null) return;

        // Only accept archive writes from the legitimate lease holder (or transitioning handoff owner).
        String holder = occupancy.getLeaseHolder(transfer.planetName, transfer.sectorId);
        boolean isHandoff = transfer.purpose == CampaignChunkTransport.PURPOSE_HANDOFF_SYNC
            && pendingHandoff != null
            && sender.uuid().equals(pendingHandoff.holderUuid)
            && pendingHandoff.sectorId == transfer.sectorId
            && pendingHandoff.planetName.equals(transfer.planetName);

        if(!isHandoff && (holder == null || !holder.equals(sender.uuid()))){
            Log.warn("Ignored unauthorized sector archive upload from @ for @#@", sender.plainName(), transfer.planetName, transfer.sectorId);
            return;
        }

        writeAuthoritativeSectorSave(sector, transfer.data);
        occupancy.heartbeatLease(transfer.planetName, transfer.sectorId, sender.uuid(), Time.millis());

        if(isHandoff){
            completePendingHandoff();
        }
    }

    // ==================== PACKET HANDLING (CLIENT) ====================

    private void handleClientPacket(CampaignPacket packet){
        if(packet == null) return;
        try{
            switch(packet.kind){
                case CampaignPacket.KIND_OCCUPANCY_SNAPSHOT -> readOccupancyAndSectorSummaries(packet.data);
                case CampaignPacket.KIND_PERMISSIONS_SYNC -> {
                    try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data))){
                        clientPermissions = EffectivePermissions.read(in);
                    }
                }
                case CampaignPacket.KIND_HOST_CONTROL -> handleClientHostControl(packet.data);
                case CampaignPacket.KIND_ARCHIVE_CHUNK -> handleClientArchiveChunk(packet.data);
                case CampaignPacket.KIND_LOGISTICS_DELIVERY -> {
                    if(net != null && net.isDetachedClient()){
                        RemoteSectorBridge.applyDeliveriesLocally(packet.data);
                    }
                }
                case CampaignPacket.KIND_CROSS_SECTOR_CHAT -> {
                    String formatted = decodeUtf(packet.data);
                    if(formatted != null && !formatted.isEmpty() && ui != null){
                        ui.chatfrag.addMessage(formatted);
                        Sounds.uiChat.play();
                    }
                }
                case CampaignPacket.KIND_POWER_GRID_SYNC -> {
                    SectorPowerGridManager.get().applyAllTopologies(packet.data);
                }
                default -> {}
            }
        }catch(Throwable t){
            Log.err("Error handling V2 campaign client packet kind=" + packet.kind, t);
        }
    }

    private void handleClientHostControl(byte[] data) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            byte op = in.readByte();
            switch(op){
                case CTRL_DENIED -> {
                    String reason = in.readUTF();
                    if(ui != null){
                        ui.loadfrag.hide();
                        ui.showInfoFade(reason);
                    }
                }
                case CTRL_INFO_TOAST -> {
                    String msg = in.readUTF();
                    if(ui != null) ui.showInfoFade(msg);
                }
                case CTRL_ENTER_DETACHED_STAY -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    Sector sec = findSector(planetName, sectorId);
                    if(sec != null && state.isGame()){
                        // Host left our sector; seamlessly assume local simulation in place!
                        net.setDetachedLocalSim(true);
                        state.rules.sector = sec;
                        saveSyncTimer = 0f;
                        strategySyncTimer = 0f;
                        if(ui != null){
                            ui.loadfrag.hide();
                            ui.showInfoFade("房主已切往其他区块，你已接管当前区块独立运行");
                        }
                    }
                }
                case CTRL_ENTER_DETACHED_NEW -> {
                    String planetName = in.readUTF();
                    int originId = in.readInt();
                    int targetId = in.readInt();
                    Sector target = findSector(planetName, targetId);
                    Sector origin = originId >= 0 ? findSector(planetName, originId) : null;
                    if(target != null){
                        enterDetachedNewSector(origin, target);
                    }
                }
                case CTRL_REQUEST_HANDOFF -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    Sector current = state.getSector();
                    if(ui != null) ui.loadfrag.show("房主正在进入当前区块，同步存档中...");
                    if(current != null && current.id == sectorId && current.planet.name.equals(planetName)){
                        uploadDetachedSectorArchive(current, CampaignChunkTransport.PURPOSE_HANDOFF_SYNC);
                    }else{
                        // Upload empty ack so host never stalls if already moved away.
                        sendArchiveChunksToServer(CampaignChunkTransport.PURPOSE_HANDOFF_SYNC, planetName, sectorId, new byte[0]);
                    }
                }
                case CTRL_TECH_UNLOCKED -> {
                    int typeOrd = in.readByte() & 0xFF;
                    String contentName = in.readUTF();
                    if(typeOrd >= 0 && typeOrd < ContentType.all.length){
                        MappableContent raw = content.getByName(ContentType.all[typeOrd], contentName);
                        if(raw instanceof UnlockableContent u){
                            applyingRemoteTech = true;
                            try{
                                u.quietUnlock();
                                if(state.rules != null && state.rules.researched != null){
                                    state.rules.researched.add(u);
                                }
                            }finally{
                                applyingRemoteTech = false;
                            }
                        }
                    }
                }
                case CTRL_SECTOR_ABANDONED -> {
                    String planetName = in.readUTF();
                    int sectorId = in.readInt();
                    remoteSectorSummaries.remove(SectorOccupancy.sectorKey(planetName, sectorId));
                }
                default -> {}
            }
        }
    }

    private void handleClientArchiveChunk(byte[] chunkData){
        CompletedTransfer transfer = chunkTransport.receiveChunk("host", chunkData, Time.millis());
        if(transfer == null) return;

        if(transfer.purpose == CampaignChunkTransport.PURPOSE_SECTOR_ASSIGN){
            Sector target = findSector(transfer.planetName, transfer.sectorId);
            if(target != null){
                enterDetachedSectorFromArchive(target, transfer.data);
            }
        }
    }

    // ==================== DETACHED SECTOR LOAD / SAVE HELPERS ====================

    private void enterDetachedSectorFromArchive(Sector target, byte[] msavBytes){
        Core.app.post(() -> {
            try{
                if(ui != null) ui.planet.hide();
                logic.reset();
                net.setDetachedLocalSim(true);
                SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(msavBytes)), world.makeSectorContext(target));
                state.rules.sector = target;
                state.rules.cloudColor = target.planet.landCloudColor;
                state.set(State.playing);
                if(player != null){
                    player.reset();
                    player.team(state.rules.defaultTeam);
                    player.add();
                }
                saveSyncTimer = 0f;
                strategySyncTimer = 0f;
                if(ui != null) ui.loadfrag.hide();
            }catch(Throwable t){
                Log.err("Failed to load detached sector archive", t);
                net.setDetachedLocalSim(false);
                if(ui != null){
                    ui.loadfrag.hide();
                    ui.showException("加载独立区块存档失败", t);
                }
            }
        });
    }

    private void enterDetachedNewSector(@Nullable Sector origin, Sector target){
        Core.app.post(() -> {
            try{
                if(ui != null) ui.planet.hide();
                logic.reset();
                net.setDetachedLocalSim(true);
                WorldParams params = new WorldParams();
                params.saveInfo = false;
                world.loadSector(target, params);
                state.rules.sector = target;
                target.info.origin = origin;
                target.info.destination = origin;
                Events.fire(new RulesLoadEvent(state.rules));
                logic.play();
                state.set(State.playing);
                saveSyncTimer = 0f;
                strategySyncTimer = 0f;
                // Upload initial archive so host immediately holds a valid save for this new sector.
                uploadDetachedSectorArchive(target, CampaignChunkTransport.PURPOSE_SECTOR_SYNC);
                if(ui != null) ui.loadfrag.hide();
            }catch(Throwable t){
                Log.err("Failed to launch detached sector", t);
                net.setDetachedLocalSim(false);
                if(ui != null){
                    ui.loadfrag.hide();
                    ui.showException("独立区块发射失败", t);
                }
            }
        });
    }

    public void uploadDetachedSectorArchive(Sector sector, byte purpose){
        if(sector == null || net == null || !net.isConnectedClient() || !state.isGame()) return;
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            SaveIO.write(new FastDeflaterOutputStream(baos), new SaveOptions());
            sendArchiveChunksToServer(purpose, sector.planet.name, sector.id, baos.toByteArray());
        }catch(Throwable t){
            Log.err("Failed to serialize detached sector archive for sync", t);
        }
    }

    private void writeAuthoritativeSectorSave(Sector sector, byte[] msavBytes){
        if(msavBytes == null || msavBytes.length == 0 || control == null || control.saves == null) return;
        try{
            Fi file = control.saves.getSectorFile(sector);
            if(file.exists()){
                file.copyTo(file.sibling(file.name() + "-backup." + file.extension()));
            }
            file.writeBytes(msavBytes, false);
            if(sector.save == null){
                SaveSlot slot = control.saves.new SaveSlot(file);
                slot.setName(file.nameWithoutExtension());
                slot.setAutosave(true);
                sector.save = slot;
                control.saves.getSaveSlots().add(slot);
            }
            sector.save.meta = SaveIO.getMeta(file);
            sector.info.hasCore = true;
            sector.saveInfo();
        }catch(Throwable t){
            Log.err("Failed to persist synced archive for sector " + sector.planet.name + "#" + sector.id, t);
        }
    }

    private void reattachGuestToHostSector(NetConnection con, Player guest, Sector hostSector){
        con.sectorMode = ClientSectorMode.CO_LOCATED;
        boolean wasAdmin = guest.admin;
        guest.reset();
        guest.admin = wasAdmin;
        guest.team(state.rules.defaultTeam);
        occupancy.setPlayerPresence(guest.uuid(), guest.plainName(), hostSector.planet.name, hostSector.id, ClientSectorMode.CO_LOCATED);
        Call.worldDataBegin(con);
        netServer.sendWorldAndAssets(guest);
    }

    private void completePendingHandoff(){
        PendingHandoff h = pendingHandoff;
        if(h == null || h.completed) return;
        h.completed = true;
        pendingHandoff = null;
        Core.app.post(() -> {
            if(!headless && ui != null){
                ui.loadfrag.hide();
            }
            h.onComplete.run();
        });
    }

    // ==================== SERIALIZATION & BROADCAST HELPERS ====================

    private void broadcastOccupancyAndSectorSummaries(){
        if(net == null || !net.server()) return;
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            DataOutputStream out = new DataOutputStream(baos);
            occupancy.writeSnapshot(out);

            // Include lightweight planet base/capture status so guests' PlanetDialog renders accurately.
            Planet planet = state.getPlanet();
            if(planet != null){
                out.writeBoolean(true);
                out.writeUTF(planet.name);
                Seq<Sector> notable = new Seq<>();
                for(Sector sec : planet.sectors){
                    if(sec.hasBase() || sec.isCaptured() || sec.isAttacked()){
                        notable.add(sec);
                    }
                }
                out.writeInt(notable.size);
                for(Sector sec : notable){
                    out.writeInt(sec.id);
                    out.writeBoolean(sec.hasBase());
                    out.writeBoolean(sec.isCaptured());
                    out.writeBoolean(sec.isAttacked());
                }
            }else{
                out.writeBoolean(false);
            }
            out.flush();

            CampaignPacket packet = new CampaignPacket(CampaignPacket.KIND_OCCUPANCY_SNAPSHOT, baos.toByteArray());
            for(NetConnection con : guestConnections.values()){
                if(con != null && con.isConnected()){
                    con.send(packet, true);
                }
            }
            broadcastPowerGridSnapshot();
        }catch(IOException e){
            Log.err("Failed to broadcast occupancy snapshot", e);
        }
    }

    /** V2.5: Broadcasts authoritative planetary cross-sector power grid topology & live aggregates to all guests. */
    public void broadcastPowerGridSnapshot(){
        if(net == null || !net.server() || guestConnections.isEmpty()) return;
        try{
            byte[] payload = SectorPowerGridManager.get().serializeAllTopologies();
            CampaignPacket packet = new CampaignPacket(CampaignPacket.KIND_POWER_GRID_SYNC, payload);
            for(NetConnection con : guestConnections.values()){
                if(con != null && con.isConnected()){
                    con.send(packet, true);
                }
            }
        }catch(IOException e){
            Log.err("Failed to broadcast V2.5 power grid snapshot", e);
        }
    }

    private void readOccupancyAndSectorSummaries(byte[] data) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            occupancy.readSnapshot(in);
            if(in.readBoolean()){
                String planetName = in.readUTF();
                int count = in.readInt();
                // Rebuild remote summary table for this planet cleanly without touching local singleplayer saves.
                Seq<String> toRemove = new Seq<>();
                String prefix = planetName + "#";
                for(String k : remoteSectorSummaries.keys()){
                    if(k.startsWith(prefix)) toRemove.add(k);
                }
                for(String k : toRemove){
                    remoteSectorSummaries.remove(k);
                }
                for(int i = 0; i < count; i++){
                    int id = in.readInt();
                    boolean hasBase = in.readBoolean();
                    boolean captured = in.readBoolean();
                    boolean attacked = in.readBoolean();
                    remoteSectorSummaries.put(SectorOccupancy.sectorKey(planetName, id), new RemoteSectorSummary(hasBase, captured, attacked));
                }
            }
        }
        // Enforce V2 anti-pause if client learns >1 players are present.
        if(state.isPaused() && state.isPauseLocked()){
            state.set(State.playing);
        }
    }

    private void sendPermissionsTo(NetConnection con, Player guest){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(16);
            DataOutputStream out = new DataOutputStream(baos);
            permissions.effectiveFor(guest.uuid(), guest.admin).write(out);
            out.flush();
            con.send(new CampaignPacket(CampaignPacket.KIND_PERMISSIONS_SYNC, baos.toByteArray()), true);
        }catch(IOException e){
            Log.err("Failed to send campaign permissions", e);
        }
    }

    private void sendArchiveChunksToClient(NetConnection con, byte purpose, String planetName, int sectorId, byte[] bytes){
        int txId = chunkTransport.allocateTransferId();
        for(byte[] chunk : chunkTransport.split(txId, purpose, planetName, sectorId, bytes)){
            con.send(new CampaignPacket(CampaignPacket.KIND_ARCHIVE_CHUNK, chunk), true);
        }
    }

    private void sendArchiveChunksToServer(byte purpose, String planetName, int sectorId, byte[] bytes){
        int txId = chunkTransport.allocateTransferId();
        for(byte[] chunk : chunkTransport.split(txId, purpose, planetName, sectorId, bytes)){
            net.send(new CampaignPacket(CampaignPacket.KIND_ARCHIVE_CHUNK, chunk), true);
        }
    }

    private interface DataWriter{
        void write(DataOutputStream out) throws IOException;
    }

    private void sendClientRequest(byte op, DataWriter writer){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(op);
            writer.write(out);
            out.flush();
            net.send(new CampaignPacket(CampaignPacket.KIND_CLIENT_REQUEST, baos.toByteArray()), true);
        }catch(IOException e){
            Log.err("Failed to send V2 client request", e);
        }
    }

    private void sendHostControl(NetConnection con, byte op, DataWriter writer){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(op);
            writer.write(out);
            out.flush();
            con.send(new CampaignPacket(CampaignPacket.KIND_HOST_CONTROL, baos.toByteArray()), true);
        }catch(IOException e){
            Log.err("Failed to send V2 host control", e);
        }
    }

    private void deductPlanetItem(Planet planet, Item item, int amount){
        int remaining = amount;
        for(Sector sec : planet.sectors){
            if(remaining <= 0) break;
            if(!sec.hasBase()) continue;
            int has = sec.items().get(item);
            if(has > 0){
                int take = Math.min(has, remaining);
                sec.removeItem(item, take);
                remaining -= take;
            }
        }
    }

    private static @Nullable Sector findSector(String planetName, int sectorId){
        if(planetName == null || sectorId < 0 || content == null) return null;
        Planet planet = content.planet(planetName);
        if(planet == null || sectorId >= planet.sectors.size) return null;
        return planet.sectors.get(sectorId);
    }

    private static String getHostUuid(){
        if(!headless && player != null && player.uuid() != null){
            return player.uuid();
        }
        return "host-local";
    }

    private static byte[] encodeUtf(String s){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(s.length() + 4);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF(s);
            out.flush();
            return baos.toByteArray();
        }catch(IOException e){
            return new byte[0];
        }
    }

    private static @Nullable String decodeUtf(byte[] bytes){
        if(bytes == null || bytes.length == 0) return null;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))){
            return in.readUTF();
        }catch(IOException e){
            return null;
        }
    }
}
