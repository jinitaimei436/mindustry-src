package mindustry.game;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.game.Schematic.*;
import mindustry.game.coop.*;
import mindustry.game.logistics.*;
import mindustry.game.power.*;
import mindustry.game.strategy.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/** Updates and handles state of the campaign universe. Has no relevance to other gamemodes. */
public class Universe{
    private int seconds;
    private int netSeconds;
    private float secondCounter;
    private int turn;
    private float turnCounter;

    private @Nullable Schematic lastLoadout;
    private ItemSeq lastLaunchResources = new ItemSeq();
    public final GlobalStrategyManager strategy = new GlobalStrategyManager();

    public Universe(){
        load();
        SectorPowerGridManager.get().init();
        SectorConveyorManager.get().init();

        //update base coverage on capture
        Events.on(SectorCaptureEvent.class, e -> {
            if(!net.isConnectedClient() && state.isCampaign()){
                state.getSector().planet.updateBaseCoverage();
            }
        });

        // V3.2: Flush all queued background sector updates whenever a sector save is written or the app exits
        Events.on(SaveWriteEvent.class, e -> {
            if(!net.isConnectedClient() && state != null && state.isCampaign()){
                flushAllBackgroundState(true);
            }
        });

        Events.on(DisposeEvent.class, e -> {
            if(net == null || !net.isConnectedClient()){
                flushAllBackgroundState(true);
            }
        });
    }

    /** Update regardless of whether the player is in the campaign. */
    public void updateGlobal(){
        for(Planet planet : content.planets()){
            //update all parentless planets (solar system root), regardless of which one the player is in
            if(planet.parent == null) updatePlanet(planet);
        }
    }

    public int turn(){
        return turn;
    }

    private void updatePlanet(Planet planet){
        planet.position.setZero();
        planet.addParentOffset(planet.position);
        if(planet.parent != null){
            planet.position.add(planet.parent.position);
        }
        for(Planet child : planet.children){
            updatePlanet(child);
        }
    }

    /** Update planet rotations, global time and relevant state. */
    public void update(){

        if(net.active()){
            MultiSectorCoordinator.get().update(Time.delta / 60f);
        }

        //only update authoritative global campaign time/logistics on singleplayer or host (never on detached guests)
        if(!net.isConnectedClient()){
            secondCounter += Time.delta / 60f;
            turnCounter += Time.delta;

            //auto-run turns
            if(turnCounter >= turnDuration){
                turnCounter = 0;
                runTurn();
            }

            if(secondCounter >= 1){
                int elapsedSec = (int)secondCounter;
                seconds += elapsedSec;
                secondCounter %= 1f;

                // Real-time 1-second settlement (coordinates GlobalStrategyManager, Cross-Sector Power, Conveyors & future extensions)
                Planet curPlanet = state.getPlanet();
                for(Planet planet : content.planets()){
                    if(curPlanet != null && curPlanet != planet && !curPlanet.updateGroup.contains(planet) && !planet.updateGroup.contains(curPlanet)){
                        continue;
                    }
                    strategy.update(planet, elapsedSec);
                }

                //save every few seconds
                if(seconds % 10 == 1){
                    save();
                }
            }
        }

        if(state.hasSector() && state.getSector().planet.updateLighting && !(state.getSector().preset != null && state.getSector().preset.noLighting)){
            var planet = state.getSector().planet;
            //update sector light
            float light = state.getSector().getLight();
            float alpha = Mathf.clamp(Mathf.map(light, planet.lightSrcFrom, planet.lightSrcTo, planet.lightDstFrom, planet.lightDstTo));

            //assign and map so darkness is not 100% dark
            state.rules.ambientLight.a = 1f - alpha;
            state.rules.lighting = !Mathf.equal(alpha, 1f);
        }
    }

    public void clearLoadoutInfo(){
        lastLoadout = null;
        lastLaunchResources = new ItemSeq();
        Core.settings.remove("launch-resources-seq");
        Core.settings.remove("lastloadout-core-shard");
        Core.settings.remove("lastloadout-core-nucleus");
        Core.settings.remove("lastloadout-core-foundation");
    }

    public ItemSeq getLaunchResources(){
        lastLaunchResources = Core.settings.getJson("launch-resources-seq", ItemSeq.class, ItemSeq::new);
        return lastLaunchResources;
    }

    public void updateLaunchResources(ItemSeq stacks){
        this.lastLaunchResources = stacks;
        Core.settings.putJson("launch-resources-seq", lastLaunchResources);
    }

    /** Updates selected loadout for future deployment. Creates an empty schematic with a single core block. */
    public void updateLoadout(CoreBlock block){
        updateLoadout(block, new Schematic(Seq.with(new Stile(block, 0, 0, null, (byte)0)), new StringMap(), block.size, block.size));
    }

    /** Updates selected loadout for future deployment. */
    public void updateLoadout(CoreBlock block, Schematic schem){
        Core.settings.put("lastloadout-" + block.name, schem.file == null ? "" : schem.file.nameWithoutExtension());
        lastLoadout = schem;
    }

    public Schematic getLastLoadout(){
        if(lastLoadout == null) lastLoadout = state.rules.sector == null || state.rules.sector.planet.generator == null ? Loadouts.basicShard : state.rules.sector.planet.generator.defaultLoadout;
        return lastLoadout;
    }

    /** @return the last selected loadout for this specific core type. */
    @Nullable
    public Schematic getLoadout(CoreBlock core){
        //for tools - schem
        if(schematics == null) return Loadouts.basicShard;

        //find last used loadout file name
        String file = Core.settings.getString("lastloadout-" + core.name, "");

        //use default (first) schematic if not found
        Seq<Schematic> all = schematics.getLoadouts(core);
        Schematic schem = all.find(s -> s.file != null && s.file.nameWithoutExtension().equals(file));

        return schem == null ? all.any() ? all.first() : null : schem;
    }

    /** Runs periodic campaign world events (capture timers, invasions, TurnEvent).
     *  Vanilla 2-minute multi-sector resource batch settlement has been removed in favor of
     *  the unified 1-second real-time {@link GlobalStrategyManager} settlement engine. */
    public void runTurn(){
        turn++;

        Planet current = state.getPlanet();

        //update relevant sectors
        for(Planet planet : content.planets()){

            //do not update other planets
            if(current != null && current != planet && !current.updateGroup.contains(planet) && !planet.updateGroup.contains(current)){
                continue;
            }

            for(Sector sector : planet.sectors){
                if(sector.hasBase()){
                    if(sector.info.importRateCache != null){
                        sector.info.refreshImportRates(planet);
                    }

                    //if it is being attacked, capture time is 0; otherwise, increment the timer
                    if(sector.isAttacked()){
                        sector.info.minutesCaptured = 0;
                    }else{
                        sector.info.minutesCaptured += turnDuration / 60 / 60;
                    }

                    //attacked sectors are frozen in time; don't update those
                    if(!sector.isAttacked()){
                        // V3.2: Background inventory persistence is handled incrementally via GlobalStrategyManager's
                        // batched dirty-sector queue (no 272-sector full JSON spike every 2 minutes).

                        //queue random invasions
                        if(sector.planet.campaignRules.sectorInvasion && sector.info.minutesCaptured > invasionGracePeriod && sector.info.hasSpawns){
                            int count = sector.near().count(s -> s.hasEnemyBase() && !s.hasBase() && (s.preset == null || !s.preset.requireUnlock));

                            //invasion chance depends on # of nearby bases
                            if(count > 0 && Mathf.chance(baseInvasionChance * (0.8f + (count - 1) * 0.3f))){
                                int waveMax = Math.max(sector.info.winWave, sector.isBeingPlayed() ? state.wave : sector.info.wave) + Mathf.random(2, 4) * 5;

                                //assign invasion-related things
                                if(sector.isBeingPlayed()){
                                    state.rules.winWave = waveMax;
                                    state.rules.waves = true;
                                    state.rules.attackMode = false;
                                    planet.campaignRules.apply(planet, state.rules); //enabling waves may force changes in campaign rules
                                    //update rules in multiplayer
                                    if(net.server()){
                                        Call.setRules(state.rules);
                                    }
                                }else{
                                    sector.info.winWave = waveMax;
                                    sector.info.waves = true;
                                    sector.info.attack = false;
                                    sector.saveInfo();
                                }

                                Events.fire(new SectorInvasionEvent(sector));
                            }
                        }
                    }
                }
            }
        }

        Events.fire(new TurnEvent());

        save();
    }

    public void updateNetSeconds(int value){
        netSeconds = value;
    }

    public float secondsMod(float mod, float scale){
        return (seconds() / scale) % mod;
    }

    public int seconds(){
        //use networked seconds when playing as client
        return net.isConnectedClient() ? netSeconds : seconds;
    }

    public void setSeconds(float seconds){
        this.seconds = (int)seconds;
        this.secondCounter = seconds - this.seconds;

        save();
    }

    public float secondsf(){
        return seconds() + secondCounter;
    }

    private void flushAllBackgroundState(boolean manualSaveDisk){
        if(Core.settings == null) return;
        strategy.flushDirtySectors();
        SectorPowerGridManager.get().saveIfDirty(false);
        SectorConveyorManager.get().saveIfDirty(false);
        if(manualSaveDisk){
            Core.settings.manualSave();
        }
    }

    private void save(){
        if(net.isConnectedClient()) return;
        Core.settings.put("utimei", seconds);
        Core.settings.put("turn", turn);
        strategy.flushDirtySectorsBatched(GlobalStrategyManager.DEFAULT_FLUSH_BATCH_SIZE);
        SectorPowerGridManager.get().saveIfDirty(false);
        SectorConveyorManager.get().saveIfDirty(false);
        Core.settings.manualSave();
    }

    private void load(){
        seconds = Core.settings.getInt("utimei");
        turn = Core.settings.getInt("turn");
    }

}
