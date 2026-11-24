package mindustry.game.strategy;

import arc.util.*;
import mindustry.game.*;
import mindustry.game.coop.*;
import mindustry.game.strategy.core.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.campaign.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Bridges Mindustry's Universe / Planet / SectorInfo with the pure-Java
 * StrategySimulationEngine for real-time cross-sector logistics and future
 * strategic troop mobilization.
 */
public class GlobalStrategyManager{
    private final HashMap<String, StrategySimulationEngine> engines = new HashMap<>();
    /**
     * Tracks the last integer item counts pushed by this manager into each background
     * sector's SectorInfo.items, keyed by planetName -> sectorId -> itemName -> intQty.
     * Used to reconcile external mutations (research spending, launch loadouts, legacy pads)
     * instead of overwriting them on the next 1-second tick.
     */
    private final HashMap<String, HashMap<Integer, HashMap<String, Integer>>> lastSyncedStorage = new HashMap<>();
    /** Max background sectors serialized per periodic 10-second save tick to keep main-thread spikes < 5ms. */
    public static final int DEFAULT_FLUSH_BATCH_SIZE = 30;
    /** FIFO-ordered background sectors whose inventory changed since the last disk flush. */
    private final LinkedHashSet<Sector> dirtySectors = new LinkedHashSet<>();
    /** Ordered pluggable cross-sector subsystems (power grid, conveyors, future devices). */
    private final ArrayList<CrossSectorExtension> extensions = new ArrayList<>();
    private final LogisticsPolicy policy;
    private StrategicAIController aiController;

    public GlobalStrategyManager(){
        this(new DefaultLogisticsPolicy());
    }

    public GlobalStrategyManager(LogisticsPolicy policy){
        this.policy = policy;
        registerExtension(new CrossSectorPowerExtension());
        registerExtension(new CrossSectorConveyorExtension());
    }

    /** Registers a pluggable cross-sector extension (deduplicated by extensionId). */
    public void registerExtension(CrossSectorExtension extension){
        if(extension == null) return;
        String id = extension.extensionId();
        for(int i = 0; i < extensions.size(); i++){
            if(extensions.get(i).extensionId().equals(id)){
                extensions.set(i, extension);
                return;
            }
        }
        extensions.add(extension);
    }

    public List<CrossSectorExtension> getExtensions(){
        return Collections.unmodifiableList(extensions);
    }

    /** Invoked by {@link SectorProfileCompiler} so extensions can contribute compiled nodes/metadata. */
    public void notifyCompileSector(Sector sector, SectorInfo info, SectorStrategyProfile profile){
        for(int i = 0; i < extensions.size(); i++){
            extensions.get(i).onCompileSector(sector, info, profile);
        }
    }

    /** Persists ALL background sectors modified by real-time settlement (used on sector switch, save write, or exit). */
    public void flushDirtySectors(){
        if(dirtySectors.isEmpty()) return;
        for(Sector sector : dirtySectors){
            if(sector != null && sector.hasBase() && !sector.isBeingPlayed()){
                sector.info.items.checkNegative();
                sector.saveInfo();
            }
        }
        dirtySectors.clear();
    }

    /**
     * Persists up to {@code maxBatchSize} dirty background sectors in fair FIFO order.
     * Remaining dirty sectors stay queued for the next periodic save tick, smoothing out
     * 272-sector late-game JSON serialization without stalling the main thread.
     *
     * @return number of background sectors flushed in this batch.
     */
    public int flushDirtySectorsBatched(int maxBatchSize){
        if(dirtySectors.isEmpty() || maxBatchSize <= 0) return 0;
        int flushed = 0;
        Iterator<Sector> it = dirtySectors.iterator();
        while(it.hasNext() && flushed < maxBatchSize){
            Sector sector = it.next();
            it.remove();
            if(sector != null && sector.hasBase() && !sector.isBeingPlayed()){
                sector.info.items.checkNegative();
                sector.saveInfo();
                flushed++;
            }
        }
        return flushed;
    }

    public int getDirtySectorCount(){
        return dirtySectors.size();
    }

    public void setAIController(StrategicAIController controller){
        this.aiController = controller;
        for(StrategySimulationEngine engine : engines.values()){
            engine.setAIController(controller);
        }
    }

    public StrategySimulationEngine getEngine(Planet planet){
        return engines.computeIfAbsent(planet.name, k -> {
            StrategySimulationEngine e = new StrategySimulationEngine(policy);
            e.setAIController(aiController);
            return e;
        });
    }

    /**
     * Advances real-time global logistics for the given planet by dtSeconds.
     * Typically called once per second from Universe.update().
     */
    public void update(Planet planet, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;

        StrategySimulationEngine engine = getEngine(planet);
        HashMap<Integer, HashMap<String, Integer>> planetLastSynced =
            lastSyncedStorage.computeIfAbsent(planet.name, k -> new HashMap<>());
        boolean legacyPads = planet.campaignRules.legacyLaunchPads;
        RemoteSectorBridge remoteBridge = MultiSectorCoordinator.get().strategyBridge;
        long nowMs = Time.millis();
        boolean isHost = net != null && net.server();

        // 1. Sync sector profiles, reconcile external storage changes, and build active routes
        for(Sector sector : planet.sectors){
            if(!sector.hasBase() && !sector.hasEnemyBase()){
                // Clean up abandoned or destroyed sectors so they never persist as ghost producers
                engine.removeSector(sector.id);
                planetLastSynced.remove(sector.id);
                if(sector.info.strategyProfile != null){
                    sector.info.strategyProfile.hasBase = false;
                }
                continue;
            }

            SectorStrategyProfile profile = ensureProfile(sector);
            boolean localForeground = sector.isBeingPlayed();
            boolean remoteForeground = !localForeground && isHost && remoteBridge.isRemoteForegroundActive(planet.name, sector.id, nowMs);
            profile.hasBase = sector.hasBase();
            profile.enemyBase = sector.hasEnemyBase();
            profile.underAttack = sector.isAttacked();
            profile.foregroundActive = localForeground || remoteForeground;
            profile.storageCapacity = sector.info.storageCapacity;
            profile.defaultDestinationSectorId = sector.info.destination != null ? sector.info.destination.id : -1;
            profile.requireExplicitImportDemand = false;
            profile.foregroundExportRates.clear();
            profile.clearExternalStepChannels();

            if(localForeground){
                // Foreground sector runs natively; sync live storage from active core (including zeroes)
                planetLastSynced.remove(sector.id);
                CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;
                if(core != null){
                    profile.storageCapacity = core.storageCapacity;
                    for(Item item : content.items()){
                        profile.setStored(item.name, core.items.get(item));
                    }
                }else{
                    for(Item item : content.items()){
                        profile.setStored(item.name, sector.info.items.get(item));
                    }
                }

                if(!legacyPads){
                    // In v8 LandingPad mode:
                    // - Outgoing foreground exports come from physical LaunchPad stats (sector.info.export)
                    // - Incoming foreground imports are gated by active physical LandingPads
                    sector.info.export.each((item, stat) -> {
                        if(stat != null && stat.mean > 1e-4f){
                            profile.foregroundExportRates.put(item.name, stat.mean);
                        }
                    });
                    profile.requireExplicitImportDemand = true;
                    syncForegroundLandingPadDemands(profile);
                }
                // In legacyLaunchPads mode: vanilla LaunchPad calls destsec.addItems() directly on launch
                // (absorbed by external delta reconciliation below), so foregroundExportRates stays empty.
            }else if(remoteForeground){
                // V2: Sector is actively simulated by a detached guest; project guest's live telemetry.
                planetLastSynced.remove(sector.id);
                remoteBridge.applyRemoteProfile(profile, planet.name, sector.id, nowMs);
            }else if(sector.hasBase()){
                // Reconcile any external changes to sector.info.items (tech tree, launch loadout, legacy pads)
                HashMap<String, Integer> prevSynced = planetLastSynced.get(sector.id);
                for(Item item : content.items()){
                    int actual = sector.info.items.get(item);
                    int expected = prevSynced != null
                        ? prevSynced.getOrDefault(item.name, 0)
                        : Math.max(0, Math.min(profile.storageCapacity, Math.round(profile.getStored(item.name))));
                    int externalDelta = actual - expected;
                    if(externalDelta != 0){
                        profile.addStored(item.name, externalDelta);
                    }
                }
            }else{
                planetLastSynced.remove(sector.id);
            }

            engine.putSector(profile);

            // Sync default route from SectorInfo.destination (or remote destination override)
            engine.clearRoutesFrom(sector.id);
            int destId = profile.defaultDestinationSectorId;
            Sector destSector = (destId >= 0 && destId < planet.sectors.size) ? planet.sectors.get(destId) : sector.info.destination;
            if(sector.hasBase() && destSector != null && destSector != sector && destSector.planet == planet){
                if(profile.foregroundActive){
                    if((!legacyPads || remoteForeground) && !profile.foregroundExportRates.isEmpty()){
                        float fgCap = 0f;
                        for(float v : profile.foregroundExportRates.values()) fgCap += v;
                        if(fgCap > 1e-4f){
                            engine.addRoute(new LogisticsRoute(sector.id, destSector.id, "*", fgCap));
                        }
                    }
                }else{
                    // Default to 0f so sectors without LaunchPads never receive phantom export capacity
                    float cap = profile.exportCapacities.getOrDefault("*", 0f);
                    if(cap > 1e-4f){
                        engine.addRoute(new LogisticsRoute(sector.id, destSector.id, "*", cap));
                    }
                }
            }
        }

        // 2. Pre-step cross-sector extensions (inter-sector power grid, background conveyor receiver drain, external demands)
        for(int i = 0; i < extensions.size(); i++){
            extensions.get(i).beforeStrategyStep(planet, engine, dtSeconds);
        }

        // 3. Step the pure-Java event-driven simulation engine
        engine.step(dtSeconds);

        // 4. Post-step cross-sector extensions (background conveyor sender refill + power/freeze-aware transfer)
        for(int i = 0; i < extensions.size(); i++){
            extensions.get(i).afterStrategyStep(planet, engine, dtSeconds);
        }

        // 5. Push solved inventory & rates back into Mindustry SectorInfo (and foreground core / detached guests)
        Sector foregroundSector = null;
        for(Sector sector : planet.sectors){
            if(!sector.hasBase()) continue;

            SectorStrategyProfile profile = engine.sectors.get(sector.id);
            if(profile == null) continue;

            if(sector.isBeingPlayed()){
                foregroundSector = sector;
                if(legacyPads){
                    flushForegroundDeliveries(profile);
                }else{
                    profile.foregroundPendingDelivery.clear();
                }
                continue;
            }

            if(isHost && remoteBridge.isRemoteForegroundActive(planet.name, sector.id, nowMs)){
                flushRemoteForegroundDeliveries(remoteBridge, planet.name, sector.id, profile);
                // Keep host's SectorInfo item & rate displays roughly in sync for PlanetDialog UI
                for(Item item : content.items()){
                    int qty = Math.max(0, Math.min(profile.storageCapacity, Math.round(profile.getStored(item.name))));
                    sector.info.items.set(item, qty);
                }
                syncRatesToExportStat(profile.solvedExportRates, sector.info.export);
                syncRatesToExportStat(profile.solvedImportRates, sector.info.imports);
                continue;
            }

            // Update core items from float accumulator and snapshot pushed integers
            HashMap<String, Integer> syncedMap = planetLastSynced.computeIfAbsent(sector.id, k -> new HashMap<>());
            syncedMap.clear();
            boolean inventoryChanged = false;
            for(Item item : content.items()){
                int prevQty = sector.info.items.get(item);
                int qty = Math.max(0, Math.min(sector.info.storageCapacity, Math.round(profile.getStored(item.name))));
                if(qty != prevQty){
                    sector.info.items.set(item, qty);
                    inventoryChanged = true;
                }
                if(qty > 0){
                    syncedMap.put(item.name, qty);
                }
            }
            if(inventoryChanged){
                dirtySectors.add(sector);
            }

            // Mirror solved rates into SectorInfo so PlanetDialog HUD and LandingPads show live numbers
            syncRatesToExportStat(profile.solvedNetRates, sector.info.production);
            syncRatesToExportStat(profile.solvedGrossProduction, sector.info.rawProduction);
            syncRatesToExportStat(profile.solvedExportRates, sector.info.export);
            syncRatesToExportStat(profile.solvedImportRates, sector.info.imports);
        }

        // Immediately refresh foreground LandingPad import rate cache so background export changes take effect now
        if(foregroundSector != null && !legacyPads){
            foregroundSector.info.refreshImportRates(planet);
        }

        // Push queued cross-sector logistics deliveries to detached guests
        if(isHost){
            MultiSectorCoordinator.get().flushRemoteLogisticsDeliveries();
        }
    }

    private void syncForegroundLandingPadDemands(SectorStrategyProfile profile){
        profile.importDemands.clear();
        if(state.rules.defaultTeam == null) return;
        for(Building build : state.rules.defaultTeam.data().buildings){
            if(build == null || !build.enabled) continue;
            if(build instanceof LandingPad.LandingPadBuild landingBuild && build.block instanceof LandingPad pad){
                if(landingBuild.config != null){
                    float cycleTicks = Math.max(1f, pad.cooldownTime + pad.arrivalDuration);
                    float demand = (60f / cycleTicks) * pad.itemCapacity;
                    profile.importDemands.put(
                        landingBuild.config.name,
                        profile.importDemands.getOrDefault(landingBuild.config.name, 0f) + demand
                    );
                }
            }
        }
    }

    private void flushForegroundDeliveries(SectorStrategyProfile profile){
        if(profile.foregroundPendingDelivery.isEmpty()) return;
        CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;
        if(core == null) return;

        Iterator<Map.Entry<String, Float>> it = profile.foregroundPendingDelivery.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, Float> entry = it.next();
            float pending = entry.getValue();
            int whole = (int)Math.floor(pending);
            if(whole <= 0) continue;

            Item item = content.item(entry.getKey());
            if(item == null){
                it.remove();
                continue;
            }
            int room = Math.max(0, core.storageCapacity - core.items.get(item));
            int toAdd = Math.min(whole, room);
            if(toAdd > 0){
                core.items.add(item, toAdd);
            }
            float remainder = pending - whole;
            if(remainder <= 1e-5f){
                it.remove();
            }else{
                entry.setValue(remainder);
            }
        }
    }

    private void flushRemoteForegroundDeliveries(RemoteSectorBridge bridge, String planetName, int sectorId, SectorStrategyProfile profile){
        if(profile.foregroundPendingDelivery.isEmpty()) return;
        Iterator<Map.Entry<String, Float>> it = profile.foregroundPendingDelivery.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, Float> entry = it.next();
            float pending = entry.getValue();
            int whole = (int)Math.floor(pending);
            if(whole <= 0) continue;

            bridge.queueDelivery(planetName, sectorId, entry.getKey(), whole);
            float remainder = pending - whole;
            if(remainder <= 1e-5f){
                it.remove();
            }else{
                entry.setValue(remainder);
            }
        }
    }

    private SectorStrategyProfile ensureProfile(Sector sector){
        SectorStrategyProfile profile = sector.info.strategyProfile;
        if(profile == null){
            profile = new SectorStrategyProfile(sector.id);
            profile.hasBase = sector.hasBase();
            profile.enemyBase = sector.hasEnemyBase();
            profile.storageCapacity = sector.info.storageCapacity;
            final SectorStrategyProfile created = profile;
            sector.info.items.each((item, amount) -> created.setStored(item.name, amount));

            // Bootstrap legacy sectors that have not been re-visited yet using vanilla mean rates
            ProductionNode legacyNode = new ProductionNode("legacy-bootstrap");
            boolean hasLegacy = false;
            for(Item item : content.items()){
                SectorInfo.ExportStat stat = sector.info.production.get(item);
                if(stat != null && stat.mean > 0.01f){
                    legacyNode.addOutput(item.name, stat.mean);
                    hasLegacy = true;
                }
            }
            if(hasLegacy){
                profile.nodes.add(legacyNode);
            }

            // Preserve recorded launch pad export throughput for legacy unvisited sectors (0 if none)
            float legacyExportTotal = 0f;
            for(Item item : content.items()){
                SectorInfo.ExportStat expStat = sector.info.export.get(item);
                if(expStat != null && expStat.mean > 0.01f){
                    legacyExportTotal += expStat.mean;
                }
            }
            profile.exportCapacities.put("*", legacyExportTotal);

            for(Sector near : sector.near()){
                profile.adjacentSectorIds.add(near.id);
            }
            sector.info.strategyProfile = profile;
        }
        return profile;
    }

    private void syncRatesToExportStat(Map<String, Float> source, arc.struct.ObjectMap<Item, SectorInfo.ExportStat> target){
        // Clear stale entries that are no longer present in the solved rates
        target.each((item, stat) -> {
            if(!source.containsKey(item.name)){
                stat.mean = 0f;
                stat.counter = 0f;
            }
        });
        for(Map.Entry<String, Float> entry : source.entrySet()){
            Item item = content.item(entry.getKey());
            if(item != null){
                target.get(item, SectorInfo.ExportStat::new).mean = entry.getValue();
            }
        }
    }
}
