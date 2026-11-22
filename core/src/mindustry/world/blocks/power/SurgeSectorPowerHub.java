package mindustry.world.blocks.power;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.power.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.draw.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * V2.5 Late-game 4x4 pure-transmission cross-sector power hub ("巨浪区块电力枢纽").
 *
 * Key rules:
 * - Size 4x4, unlocked directly after Surge Tower in the Serpulo tech tree.
 * - Strictly limited to 1 hub per sector per team.
 * - Circular local power connection range of 20 tiles (inherits PowerNode laser linking).
 * - Pure transmission node with zero built-in battery capacity: bridges its connected local
 *   PowerGraph into the planetary inter-sector power grid ("区块间电网") with unlimited throughput.
 *
 * Pre-reserved Animation Slots (startup / running / shutdown):
 * 1) Sprite-sequence auto-loading (zero code changes needed once sprites are added):
 *    - {@code <name>} / {@code <name>-bottom}: base sprite / optional underlay
 *    - {@code <name>-idle}: optional offline/standby sprite (falls back to base)
 *    - {@code <name>-start0..N} (also supports {@code -start-0..N}, {@code -start1..N}, {@code -start-1..N}): startup frames
 *    - {@code <name>-run0..N}   (also supports {@code -run-0..N},   {@code -run1..N},   {@code -run-1..N}):   running loop frames
 *    - {@code <name>-stop0..N}  (also supports {@code -stop-0..N},  {@code -stop1..N},  {@code -stop-1..N}):  shutdown frames
 *      (if {@code -stop*} frames are omitted while {@code -start*} frames exist, startup frames automatically play in reverse)
 *    - {@code <name>-glow}: optional additive glow layer scaled by {@code warmup()}
 *    - {@code <name>-top}:  optional top cap layer rendered above animation frames
 * 2) Custom Mindustry {@link DrawBlock} slots:
 *    - {@link #idleDrawer}, {@link #startupDrawer}, {@link #runningDrawer}, {@link #shutdownDrawer}, {@link #overlayDrawer}
 * 3) Overridable phase hooks on {@link SurgeSectorPowerHubBuild}:
 *    - {@code drawIdleAnimation()}, {@code drawStartupAnimation(float)}, {@code drawRunningAnimation(float)},
 *      {@code drawShutdownAnimation(float)}, {@code onHubAnimStateChanged(HubAnimState, HubAnimState)}
 */
public class SurgeSectorPowerHub extends PowerNode{
    public Color hubGlowColor = Pal.accent;

    // ==================== ANIMATION CONFIGURATION SLOTS ====================

    /** Startup animation duration in ticks (default 1.5s = 90 ticks). */
    public float startupDuration = 60f * 1.5f;
    /** Single running animation loop cycle duration in ticks (default 1.2s = 72 ticks). */
    public float runCycleDuration = 60f * 1.2f;
    /** Shutdown animation duration in ticks (default 1.2s = 72 ticks). */
    public float shutdownDuration = 60f * 1.2f;
    /** Maximum number of frames scanned per animation phase during load(). */
    public int maxAutoFrames = 64;
    /**
     * When false (default), the hub starts up as soon as it is enabled and linked to any local or
     * cross-sector grid, and shuts down when disabled or all links are severed.
     * Set true if you also want the hub to play its shutdown animation whenever grid power reaches zero.
     */
    public boolean requireEnergizedGrid = false;

    /** Optional DrawBlock hooks per animation state (take precedence over auto-loaded frame sequences). */
    public @Nullable DrawBlock idleDrawer;
    public @Nullable DrawBlock startupDrawer;
    public @Nullable DrawBlock runningDrawer;
    public @Nullable DrawBlock shutdownDrawer;
    /** Optional DrawBlock rendered across all states after the phase animation (e.g. DrawGlowRegion / DrawPlasma). */
    public @Nullable DrawBlock overlayDrawer;

    // ==================== PRE-RESERVED SPRITE REGIONS ====================

    public TextureRegion bottomRegion;
    public TextureRegion idleRegion;
    public TextureRegion glowRegion;
    public TextureRegion topRegion;
    public TextureRegion[] startupFrames = new TextureRegion[0];
    public TextureRegion[] runningFrames = new TextureRegion[0];
    public TextureRegion[] shutdownFrames = new TextureRegion[0];
    /** True when custom base sprite is not yet bundled and fallback surge-tower texture is used. */
    public boolean usingFallbackSprite = false;

    public SurgeSectorPowerHub(String name){
        super(name);
        size = 4;
        laserRange = 20f;
        maxNodes = 20;
        consumesPower = false;
        outputsPower = false;
        hasPower = true;
        update = true;
        noUpdateDisabled = false;
        solid = true;
        destructible = true;
        drawCached = false;
        buildCostMultiplier = 1.25f;
        localizedName = "巨浪区块电力枢纽";
        description = "大后期跨区块电力核心枢纽。每个区块限建造 1 座，可将半径 20 格内连接的区块内电网并入星球区块间电网，实现跨区块实时电力与共享蓄电池互通（纯传输，无内置蓄电）。";
    }

    @Override
    public void init(){
        super.init();
        if(localizedName == null || localizedName.equals(name)){
            localizedName = "巨浪区块电力枢纽";
        }
        if(description == null || description.isEmpty()){
            description = "大后期跨区块电力核心枢纽。每个区块限建造 1 座，可将半径 20 格内连接的区块内电网并入星球区块间电网，实现跨区块实时电力与共享蓄电池互通（纯传输，无内置蓄电）。";
        }
    }

    @Override
    public void load(){
        super.load();

        // Safe visual fallback so code works seamlessly before custom sprites are integrated
        if(region == null || !region.found()){
            region = Core.atlas.find("surge-tower");
            usingFallbackSprite = true;
        }else{
            usingFallbackSprite = false;
        }

        // Optional static layers
        bottomRegion = Core.atlas.find(name + "-bottom");
        idleRegion = Core.atlas.has(name + "-idle") ? Core.atlas.find(name + "-idle") : region;
        glowRegion = Core.atlas.find(name + "-glow");
        topRegion = Core.atlas.find(name + "-top");

        // Auto-discover startup / running / shutdown frame sequences
        startupFrames = loadFrameSequence("start");
        runningFrames = loadFrameSequence("run");
        shutdownFrames = loadFrameSequence("stop");

        // Load any custom DrawBlock hooks if configured
        if(idleDrawer != null) idleDrawer.load(this);
        if(startupDrawer != null) startupDrawer.load(this);
        if(runningDrawer != null) runningDrawer.load(this);
        if(shutdownDrawer != null) shutdownDrawer.load(this);
        if(overlayDrawer != null) overlayDrawer.load(this);
    }

    /**
     * Scans atlas for sequential animation frames using common naming conventions:
     * {@code <name>-<prefix>0..N}, {@code <name>-<prefix>-0..N},
     * {@code <name>-<prefix>1..N}, {@code <name>-<prefix>-1..N}.
     */
    protected TextureRegion[] loadFrameSequence(String prefix){
        TextureRegion[] found = scanFrames(name + "-" + prefix, 0);
        if(found.length > 0) return found;

        found = scanFrames(name + "-" + prefix + "-", 0);
        if(found.length > 0) return found;

        found = scanFrames(name + "-" + prefix, 1);
        if(found.length > 0) return found;

        return scanFrames(name + "-" + prefix + "-", 1);
    }

    private TextureRegion[] scanFrames(String basePrefix, int startIndex){
        if(!Core.atlas.has(basePrefix + startIndex)){
            return new TextureRegion[0];
        }
        Seq<TextureRegion> list = new Seq<>();
        for(int i = 0; i < maxAutoFrames; i++){
            String key = basePrefix + (startIndex + i);
            if(!Core.atlas.has(key)) break;
            list.add(Core.atlas.find(key));
        }
        return list.toArray(TextureRegion.class);
    }

    @Override
    public void loadIcon(){
        super.loadIcon();
        if(fullIcon == null || !fullIcon.found()){
            fullIcon = Core.atlas.find("surge-tower-full", Core.atlas.find("surge-tower"));
        }
        if(uiIcon == null || !uiIcon.found()){
            uiIcon = fullIcon;
        }
    }

    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.abilities, "每个区块限建造 1 座，无上限跨区块实时互通（纯传输不含蓄电）");
    }

    @Override
    public void setBars(){
        super.setBars();
        addBar("sectorGrid", entity -> new Bar(
            () -> {
                String animTag = "";
                if(entity instanceof SurgeSectorPowerHubBuild hub){
                    HubAnimState st = hub.animState();
                    if(st == HubAnimState.STARTING) animTag = " [启动中]";
                    else if(st == HubAnimState.STOPPING) animTag = " [关闭中]";
                    else if(st == HubAnimState.OFFLINE) animTag = " [待机]";
                }
                if(!state.isCampaign() || !state.hasSector()) return "跨区电网: 非战役模式" + animTag;
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                if(agg.sectorCount() <= 1){
                    return "跨区电网: 单区独立 (前往星球电力视野连线)" + animTag;
                }
                long netPerSec = Math.round(agg.netBalancePerTick * 60f);
                String sign = netPerSec >= 0 ? "+" : "";
                return "跨区电网: " + agg.sectorCount() + "区块 (" + sign + UI.formatAmount(netPerSec) + "/秒)" + animTag;
            },
            () -> {
                if(!state.isCampaign() || !state.hasSector()) return Pal.gray;
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                if(agg.sectorCount() <= 1) return Pal.accent;
                return agg.overloaded ? Pal.remove : Pal.powerBar;
            },
            () -> {
                if(!state.isCampaign() || !state.hasSector()) return 0f;
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                return agg.sectorCount() <= 1 ? 1f : agg.satisfaction;
            }
        ));

        addBar("sectorBatteries", entity -> new Bar(
            () -> {
                if(!state.isCampaign() || !state.hasSector()) return "跨区共享蓄电: -";
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                return "跨区共享蓄电: " + UI.formatAmount((long)agg.totalBatteryStored) + " / " + UI.formatAmount((long)agg.totalBatteryCapacity);
            },
            () -> Pal.powerLight,
            () -> {
                if(!state.isCampaign() || !state.hasSector()) return 0f;
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                return agg.totalBatteryCapacity <= 0.001f ? 0f : Mathf.clamp(agg.totalBatteryStored / agg.totalBatteryCapacity);
            }
        ));
    }

    /** Enforces strictly 1 Surge Sector Power Hub per sector per team (including active construct plans). */
    public boolean hasExistingHub(Team team, @Nullable Tile ignoreTile){
        if(team == null) return false;
        Seq<Building> existing = team.data().getBuildings(this);
        for(int i = 0; i < existing.size; i++){
            Building b = existing.get(i);
            if(b != null && b.isValid() && (ignoreTile == null || b.tile != ignoreTile)){
                return true;
            }
        }
        Seq<Building> constructing = team.data().getBuildings(ConstructBlock.get(size));
        for(int i = 0; i < constructing.size; i++){
            Building b = constructing.get(i);
            if(b instanceof ConstructBuild cb && cb.current == this && (ignoreTile == null || b.tile != ignoreTile)){
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation){
        if(!super.canPlaceOn(tile, team, rotation)) return false;
        return !hasExistingHub(team, tile);
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        Tile tile = world.tile(x, y);
        if(tile != null && hasExistingHub(player.team(), tile)){
            drawPlaceText("每个区块仅限建造 1 座巨浪区块电力枢纽", x, y, false);
            Lines.stroke(1.2f);
            Draw.color(Pal.remove);
            Drawf.circles(x * tilesize + offset, y * tilesize + offset, laserRange * tilesize);
            Draw.reset();
            return;
        }
        super.drawPlace(x, y, rotation, valid);
    }

    public class SurgeSectorPowerHubBuild extends PowerNodeBuild{
        /** Drives OFFLINE -> STARTING -> RUNNING -> STOPPING animation phases. */
        public final HubAnimationController anim = new HubAnimationController();

        @Override
        public void created(){
            super.created();
            anim.resetOffline();
            if(world == null || !world.isGenerating()){
                registerIfCampaign();
            }
        }

        @Override
        public void placed(){
            super.placed();
            anim.resetOffline();
            if(world == null || !world.isGenerating()){
                registerIfCampaign();
            }
        }

        private void registerIfCampaign(){
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                SectorPowerGridManager.get().onHubActive(state.getSector(), this);
            }
        }

        @Override
        public void onRemoved(){
            super.onRemoved();
            if(world != null && world.isGenerating()) return;
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                // Only unregister if no other hub remains for this team in this sector
                if(!hasExistingHub(team, tile)){
                    SectorPowerGridManager.get().onHubRemoved(state.getSector());
                }
            }
        }

        public HubAnimState animState(){
            return anim.state;
        }

        /** Normalized [0..1] progress within STARTING or STOPPING (0 in OFFLINE, 1 in RUNNING). */
        public float animProgress(){
            return anim.phaseProgress;
        }

        /** Normalized [0..1) looping cycle progress while RUNNING. */
        public float runCycleProgress(){
            return anim.runCycleProgress;
        }

        @Override
        public float warmup(){
            return anim.warmup;
        }

        @Override
        public float totalProgress(){
            return anim.totalProgress;
        }

        /**
         * Determines whether the hub should be running (triggers startup when transitioning false -> true,
         * and shutdown when transitioning true -> false).
         */
        public boolean shouldHubOperate(){
            if(!enabled || power == null || power.graph == null) return false;

            boolean hasLocalGridLink = power.links.size > 0 || power.graph.all.size > 1;
            boolean hasCrossSectorLink = false;
            boolean crossSectorEnergized = false;

            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                hasCrossSectorLink = agg.sectorCount() >= 2;
                crossSectorEnergized = agg.totalProduced > 0.001f || agg.totalBatteryStored > 0.01f;
            }

            if(!hasLocalGridLink && !hasCrossSectorLink){
                return false;
            }

            if(requireEnergizedGrid){
                boolean localEnergized = power.graph.getSatisfaction() > 0.01f
                    || power.graph.getLastPowerProduced() > 0.001f
                    || power.graph.getBatteryStored() > 0.01f;
                return localEnergized || crossSectorEnergized;
            }

            return true;
        }

        @Override
        public void updateTile(){
            super.updateTile();
            if(power != null && power.graph != null){
                power.graph.ensureSectorHub(this);
            }
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                Sector sec = state.getSector();
                if(sec.hasBase() && !SectorPowerGridManager.get().hasHub(sec)){
                    SectorPowerGridManager.get().onHubActive(sec, this);
                }
            }
            HubAnimState previous = anim.state;
            // Use delta() rather than edelta() so shutdown animations always finish smoothly even when disabled
            if(anim.step(shouldHubOperate(), delta(), startupDuration, runCycleDuration, shutdownDuration)){
                onHubAnimStateChanged(previous, anim.state);
            }
        }

        /**
         * Pre-reserved lifecycle hook called whenever the hub transitions between
         * OFFLINE, STARTING, RUNNING, and STOPPING (ideal for custom SFX / Fx effects).
         */
        protected void onHubAnimStateChanged(HubAnimState from, HubAnimState to){
            // Pre-reserved for downstream audio/VFX integration
        }

        protected Color resolveIndicatorColor(){
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                if(agg.overloaded) return Pal.remove;
            }
            return hubGlowColor;
        }

        protected void drawBaseRegion(){
            if(bottomRegion != null && bottomRegion.found()){
                Draw.rect(bottomRegion, x, y);
            }
            if(usingFallbackSprite){
                Draw.rect(region, x, y, size * tilesize, size * tilesize);
            }else{
                Draw.rect(region, x, y);
            }
        }

        /** Pre-reserved idle / offline animation slot. */
        protected void drawIdleAnimation(){
            if(idleDrawer != null){
                idleDrawer.draw(this);
                return;
            }
            if(bottomRegion != null && bottomRegion.found()){
                Draw.rect(bottomRegion, x, y);
            }
            if(idleRegion != null && idleRegion.found()){
                if(usingFallbackSprite && idleRegion == region){
                    Draw.rect(idleRegion, x, y, size * tilesize, size * tilesize);
                }else{
                    Draw.rect(idleRegion, x, y);
                }
            }else{
                drawBaseRegion();
            }

            // Dim standby indicator when using placeholder artwork
            if(usingFallbackSprite){
                float prevZ = Draw.z();
                Draw.z(Layer.blockOver);
                Draw.color(Pal.gray, 0.45f);
                Fill.circle(x, y, 3.2f);
                Draw.reset();
                Draw.z(prevZ);
            }
        }

        /** Pre-reserved startup animation slot (progress 0 -> 1). */
        protected void drawStartupAnimation(float progress){
            if(startupDrawer != null){
                startupDrawer.draw(this);
                return;
            }
            if(startupFrames.length > 0){
                if(bottomRegion != null && bottomRegion.found()){
                    Draw.rect(bottomRegion, x, y);
                }
                int idx = HubAnimationController.frameIndex(startupFrames.length, progress);
                if(idx >= 0){
                    Draw.rect(startupFrames[idx], x, y);
                }
                return;
            }

            // Procedural placeholder startup animation until custom startup frames are integrated
            drawBaseRegion();
            float p = Mathf.clamp(progress);
            Color glow = resolveIndicatorColor();
            float prevZ = Draw.z();
            Draw.z(Layer.blockOver);
            Draw.color(glow, 0.25f + 0.75f * p);
            Fill.circle(x, y, 2.5f + 3.2f * p);
            Lines.stroke(0.8f + 1.1f * p);
            Lines.circle(x, y, 4f + 7.5f * p);
            Draw.reset();
            Draw.z(prevZ);
        }

        /** Pre-reserved running loop animation slot (cycleProgress 0 -> 1 looping). */
        protected void drawRunningAnimation(float cycleProgress){
            if(runningDrawer != null){
                runningDrawer.draw(this);
                return;
            }
            if(runningFrames.length > 0){
                if(bottomRegion != null && bottomRegion.found()){
                    Draw.rect(bottomRegion, x, y);
                }
                int idx = HubAnimationController.frameIndex(runningFrames.length, cycleProgress);
                if(idx >= 0){
                    Draw.rect(runningFrames[idx], x, y);
                }
                return;
            }

            // Procedural placeholder running animation until custom running frames are integrated
            drawBaseRegion();
            Color glow = resolveIndicatorColor();
            float pulse = 0.6f + Mathf.absin(Time.time, 8f, 0.35f);
            float prevZ = Draw.z();
            Draw.z(Layer.blockOver);
            Draw.color(glow, pulse);
            Fill.circle(x, y, 4.8f + Mathf.absin(Time.time, 8f, 1.4f));
            Lines.stroke(1.4f);
            Lines.circle(x, y, 9.5f + Mathf.absin(Time.time, 10f, 1.8f));
            // Rotating orbital markers synced to cycleProgress
            float baseAngle = cycleProgress * 360f;
            for(int i = 0; i < 4; i++){
                float ang = baseAngle + i * 90f;
                float ox = x + Angles.trnsx(ang, 12.5f);
                float oy = y + Angles.trnsy(ang, 12.5f);
                Fill.circle(ox, oy, 1.6f);
            }
            Draw.reset();
            Draw.z(prevZ);
        }

        /** Pre-reserved shutdown animation slot (progress 0 -> 1, warmup 1 -> 0). */
        protected void drawShutdownAnimation(float progress){
            if(shutdownDrawer != null){
                shutdownDrawer.draw(this);
                return;
            }
            if(shutdownFrames.length > 0){
                if(bottomRegion != null && bottomRegion.found()){
                    Draw.rect(bottomRegion, x, y);
                }
                int idx = HubAnimationController.frameIndex(shutdownFrames.length, progress);
                if(idx >= 0){
                    Draw.rect(shutdownFrames[idx], x, y);
                }
                return;
            }
            // Auto-reverse startup frames if dedicated shutdown frames were not provided
            if(startupFrames.length > 0){
                if(bottomRegion != null && bottomRegion.found()){
                    Draw.rect(bottomRegion, x, y);
                }
                int idx = HubAnimationController.reverseFrameIndex(startupFrames.length, progress);
                if(idx >= 0){
                    Draw.rect(startupFrames[idx], x, y);
                }
                return;
            }

            // Procedural placeholder shutdown animation until custom shutdown frames are integrated
            drawBaseRegion();
            float remain = 1f - Mathf.clamp(progress);
            Color glow = resolveIndicatorColor();
            float prevZ = Draw.z();
            Draw.z(Layer.blockOver);
            Draw.color(glow, 0.2f + 0.7f * remain);
            Fill.circle(x, y, 2.5f + 3.0f * remain);
            Lines.stroke(0.7f + 1.0f * remain);
            Lines.circle(x, y, 4f + 7.5f * remain);
            Draw.reset();
            Draw.z(prevZ);
        }

        @Override
        public void draw(){
            float baseZ = Draw.z();
            switch(anim.state){
                case OFFLINE -> drawIdleAnimation();
                case STARTING -> drawStartupAnimation(anim.phaseProgress);
                case RUNNING -> drawRunningAnimation(anim.runCycleProgress);
                case STOPPING -> drawShutdownAnimation(anim.phaseProgress);
            }
            Draw.z(baseZ);

            // Optional additive glow region driven smoothly by anim.warmup across all states
            if(glowRegion != null && glowRegion.found() && anim.warmup > 0.01f){
                Draw.z(Layer.blockAdditive);
                Draw.blend(Blending.additive);
                Draw.color(resolveIndicatorColor(), anim.warmup * 0.85f);
                Draw.rect(glowRegion, x, y);
                Draw.blend();
                Draw.reset();
                Draw.z(baseZ);
            }

            // Optional top cap region always rendered above animation frames
            if(topRegion != null && topRegion.found()){
                Draw.z(Layer.blockOver);
                Draw.rect(topRegion, x, y);
                Draw.z(baseZ);
            }

            // Optional cross-state overlay DrawBlock
            if(overlayDrawer != null){
                overlayDrawer.draw(this);
                Draw.z(baseZ);
            }

            // Draw vanilla PowerNode power lasers on top
            super.draw();
        }

        @Override
        public void drawLight(){
            super.drawLight();
            if(anim.warmup > 0.01f){
                Drawf.light(x, y, size * tilesize * 1.45f * anim.warmup, resolveIndicatorColor(), 0.6f * anim.warmup);
            }
            switch(anim.state){
                case OFFLINE -> {
                    if(idleDrawer != null) idleDrawer.drawLight(this);
                }
                case STARTING -> {
                    if(startupDrawer != null) startupDrawer.drawLight(this);
                }
                case RUNNING -> {
                    if(runningDrawer != null) runningDrawer.drawLight(this);
                }
                case STOPPING -> {
                    if(shutdownDrawer != null) shutdownDrawer.drawLight(this);
                }
            }
            if(overlayDrawer != null){
                overlayDrawer.drawLight(this);
            }
        }
    }
}
