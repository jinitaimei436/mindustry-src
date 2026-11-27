package mindustry.world.blocks.campaign;

import arc.*;
import arc.audio.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.ai.*;
import mindustry.ai.formation.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.troops.*;
import mindustry.game.troops.TroopAirliftMath.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static mindustry.Vars.*;

/**
 * Three-tier Cross-Sector Troop Launch Pad (V3.9):
 * - Low Pad (3x3): T1-T3, regular 20 CP / beachhead 10 CP, 240 power/s x 10s, 45s cooldown.
 * - High Pad (4x4): T1-T4, regular 40 CP / beachhead 20 CP, 600 power/s x 12s, 75s cooldown.
 * - Special Pad (5x5): T5 only (fixed 1 unit, 20 CP, no formation, no beachhead), 2500 power/s x 15s, 150s cooldown.
 *
 * Units selected from the pad UI march into the pad and board its internal bay before launch.
 * Launch materials MUST be physically supplied via conveyors into the pad's internal storage.
 *
 * Building artwork & animation slots are resolved via {@link TroopAirliftAssets} using
 * {@code <blockName>}, {@code <blockName>-top}, {@code <blockName>-light}, {@code <blockName>-pod},
 * {@code <blockName>-bay-0..N}, {@code <blockName>-charge}, {@code <blockName>-charge-0..N},
 * {@code <blockName>-launch-0..N}, {@code <blockName>-pod-flame-0..N}, and
 * {@code <blockName>-cooldown-0..N}, with safe programmatic fallbacks when custom art is absent.
 */
public class TroopLaunchPad extends Block{
    public final PadTier padTier;
    public Sound launchSound = Sounds.padLaunch;
    public Color accentColor = Pal.accent;

    // Reserved static & animation texture slots (loaded in load() with safe fallbacks)
    public TextureRegion baseRegion;
    public @Nullable TextureRegion topRegion;
    public @Nullable TextureRegion lightRegion;
    public TextureRegion podRegion;
    public @Nullable TextureRegion chargeGlowRegion;
    public TextureRegion[] bayFrames = new TextureRegion[0];
    public TextureRegion[] chargeFrames = new TextureRegion[0];
    public TextureRegion[] launchFrames = new TextureRegion[0];
    public TextureRegion[] podFlameFrames = new TextureRegion[0];
    public TextureRegion[] cooldownFrames = new TextureRegion[0];

    public TroopLaunchPad(String name, PadTier padTier){
        super(name);
        this.padTier = padTier;
        this.size = padTier.footprintSize;
        this.localizedName = padTier.displayName;
        this.description = buildDefaultDescription(padTier);

        hasItems = true;
        hasPower = true;
        solid = true;
        update = true;
        configurable = true;
        separateItemCapacity = true;
        itemCapacity = padTier == PadTier.SPECIAL ? 8000 : 4000;
        flags = EnumSet.of(BlockFlag.launchPad);

        consumePower(padTier.chargePowerPerSec / 60f);
    }

    private static String buildDefaultDescription(PadTier tier){
        if(tier == PadTier.SPECIAL){
            return "跨区块特种兵员发射台（5x5）：仅允许单台 T5 单位登舱，禁止编队与抢滩登陆。需传送带输入发射材料并完成蓄能后起飞。";
        }
        return "跨区块" + tier.displayName + "（" + tier.footprintSize + "x" + tier.footprintSize + "）：允许 T"
            + tier.minUnitTier + "–T" + tier.maxUnitTier + " 部队登舱，常规上限 " + tier.regularCapCp
            + " 点 / 抢滩上限 " + tier.beachheadCapCp + " 点。需传送带输入发射材料并完成蓄能后起飞。";
    }

    @Override
    public void load(){
        super.load();
        TextureRegion fallbackBase = Core.atlas.find("launch-pad", Core.atlas.find("clear"));
        TextureRegion fallbackPod = Core.atlas.find("launchpod", Core.atlas.find("clear"));

        baseRegion = TroopAirliftAssets.findOrFallback(name, fallbackBase);
        topRegion = TroopAirliftAssets.findOrFallback(name + "-top", null);
        lightRegion = TroopAirliftAssets.findOrFallback(name + "-light", null);
        podRegion = TroopAirliftAssets.findOrFallback(name + "-pod", fallbackPod);
        chargeGlowRegion = TroopAirliftAssets.findOrFallback(name + "-charge", null);

        bayFrames = TroopAirliftAssets.loadFrameSequence(name + "-bay");
        chargeFrames = TroopAirliftAssets.loadFrameSequence(name + "-charge");
        launchFrames = TroopAirliftAssets.loadFrameSequence(name + "-launch");
        podFlameFrames = TroopAirliftAssets.loadFrameSequence(name + "-pod-flame");
        cooldownFrames = TroopAirliftAssets.loadFrameSequence(name + "-cooldown");

        if(!TroopAirliftAssets.hasSprite(name)){
            fullIcon = uiIcon = fallbackBase;
        }
    }

    @Override
    public TextureRegion[] icons(){
        return new TextureRegion[]{baseRegion != null ? baseRegion : uiIcon};
    }

    @Override
    public boolean outputsItems(){
        return false;
    }

    @Override
    public void setStats(){
        super.setStats();
        stats.add(Stat.launchTime, padTier.cooldownSeconds, StatUnit.seconds);
    }

    @Override
    public void setBars(){
        super.setBars();

        addBar("troop-cp", (TroopLaunchPadBuild b) -> new Bar(
            () -> "载荷 " + b.boardedCp() + "/" + b.activeModeCap() + " 点",
            () -> Pal.accent,
            () -> {
                int cap = Math.max(1, b.activeModeCap());
                return Mathf.clamp((float)b.boardedCp() / cap);
            }
        ));

        addBar("troop-charge", (TroopLaunchPadBuild b) -> new Bar(
            () -> b.cooldownTicks > 0f
                ? "发射冷却 " + Strings.fixed(b.cooldownTicks / 60f, 1) + "s"
                : "电力蓄能 " + (int)(b.chargeFraction() * 100f) + "%",
            () -> b.cooldownTicks > 0f ? Pal.remove : Pal.powerBar,
            () -> b.cooldownTicks > 0f ? Mathf.clamp(b.cooldownTicks / (padTier.cooldownSeconds * 60f)) : b.chargeFraction()
        ));

        addBar("troop-items", (TroopLaunchPadBuild b) -> new Bar(
            () -> "供料 " + (int)(b.materialReadiness() * 100f) + "%",
            () -> Pal.items,
            () -> b.materialReadiness()
        ));
    }

    public class TroopLaunchPadBuild extends Building{
        /** Units physically boarded inside this pad's transport bay. */
        public final ArrayList<TroopUnitSnapshot> boardedUnits = new ArrayList<TroopUnitSnapshot>();
        /** Live unit IDs currently summoned and marching toward this pad to board. */
        public final IntSeq summonedUnitIds = new IntSeq();

        /** Locked route parameters configured from PlanetDialog. */
        public int targetSectorId = -1;
        public LaunchMode launchMode = LaunchMode.REGULAR;
        public RouteMode routeMode = RouteMode.SAFE;
        public int[] lockedRoute = new int[0];

        /** Power charge ticks [0 .. chargeSeconds * 60]. */
        public float chargeTicks = 0f;
        /** Post-launch cooldown ticks [0 .. cooldownSeconds * 60]. */
        public float cooldownTicks = 0f;

        /** Visual animation states (driven for custom sprite slots & programmatic fallback). */
        public float bayAnimProgress = 0f;
        public float launchAnimTicks = 0f;
        private float summonRepathTimer = 0f;

        public PadTier padTier(){
            return padTier;
        }

        public int boardedCp(){
            return TroopAirliftMath.computePayloadCp(boardedUnits);
        }

        public int activeModeCap(){
            int cap = padTier.capForMode(launchMode);
            return cap > 0 ? cap : padTier.regularCapCp;
        }

        public float chargeFraction(){
            float req = Math.max(1f, padTier.chargeSeconds * 60f);
            return Mathf.clamp(chargeTicks / req);
        }

        public boolean isCharged(){
            return chargeTicks >= padTier.chargeSeconds * 60f - 0.5f;
        }

        public boolean hasLockedRoute(){
            return targetSectorId >= 0 && lockedRoute != null && lockedRoute.length >= 2;
        }

        public int lockedHops(){
            return hasLockedRoute() ? Math.max(1, lockedRoute.length - 1) : 1;
        }

        /** Returns the required launch items for current boarded payload and locked route. */
        public LinkedHashMap<String, Integer> currentRequiredItems(){
            int cp = boardedCp();
            int effectiveCp = (cp <= 0 && !padTier.fixedLaunchCost) ? 0 : cp;
            return TroopAirliftMath.computeLaunchCost(padTier, effectiveCp, lockedHops(), launchMode);
        }

        /** Checks whether conveyor-supplied internal items satisfy all required launch materials. */
        public boolean hasRequiredMaterials(){
            if(!hasLockedRoute() || boardedUnits.isEmpty()) return false;
            LinkedHashMap<String, Integer> req = currentRequiredItems();
            for(Map.Entry<String, Integer> e : req.entrySet()){
                int need = e.getValue().intValue();
                if(need <= 0) continue;
                Item item = content.item(e.getKey());
                if(item == null || items.get(item) < need) return false;
            }
            return true;
        }

        /** Normalized [0..1] readiness ratio across required launch materials. */
        public float materialReadiness(){
            if(boardedUnits.isEmpty() && !hasLockedRoute()) return 0f;
            LinkedHashMap<String, Integer> req = hasLockedRoute()
                ? currentRequiredItems()
                : TroopAirliftMath.computeLaunchCost(padTier, Math.max(1, boardedCp()), 1, LaunchMode.REGULAR);
            int totalNeed = 0;
            int totalHave = 0;
            for(Map.Entry<String, Integer> e : req.entrySet()){
                int need = e.getValue().intValue();
                if(need <= 0) continue;
                Item item = content.item(e.getKey());
                totalNeed += need;
                if(item != null){
                    totalHave += Math.min(need, items.get(item));
                }
            }
            if(totalNeed <= 0) return 0f;
            return Mathf.clamp((float)totalHave / (float)totalNeed);
        }

        @Override
        public int getMaximumAccepted(Item item){
            if(item == null || !padTier.acceptsItemName(item.name)) return 0;
            int cap = itemCapacity;
            if(hasLockedRoute()){
                LinkedHashMap<String, Integer> req = currentRequiredItems();
                Integer need = req.get(item.name);
                if(need != null && need.intValue() > cap){
                    cap = need.intValue();
                }
            }
            return cap;
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            if(item == null || !padTier.acceptsItemName(item.name)) return false;
            int max = getMaximumAccepted(item);
            if(hasLockedRoute()){
                LinkedHashMap<String, Integer> req = currentRequiredItems();
                Integer need = req.get(item.name);
                if(need != null && need.intValue() > 0){
                    return items.get(item) < need.intValue();
                }
            }
            return items.get(item) < max;
        }

        @Override
        public boolean shouldConsume(){
            return cooldownTicks <= 0f && !isCharged() && (!boardedUnits.isEmpty() || hasLockedRoute());
        }

        @Override
        public void updateTile(){
            // Cooldown & launch visual animation progression
            if(cooldownTicks > 0f){
                cooldownTicks = Math.max(0f, cooldownTicks - Time.delta);
            }
            if(launchAnimTicks > 0f){
                launchAnimTicks = Math.max(0f, launchAnimTicks - Time.delta);
            }

            // Power charging
            if(shouldConsume() && efficiency > 0.001f){
                chargeTicks = Math.min(padTier.chargeSeconds * 60f, chargeTicks + edelta());
            }

            // Bay hatch animation smoothly opens while units are summoned to board
            float targetBay = summonedUnitIds.size > 0 ? 1f : 0f;
            bayAnimProgress = Mathf.approachDelta(bayAnimProgress, targetBay, 0.06f);

            // Guide summoned units toward pad and absorb into bay on arrival
            updateSummonedBoarding();
        }

        private void updateSummonedBoarding(){
            if(summonedUnitIds.isEmpty() || Groups.unit == null) return;
            summonRepathTimer += Time.delta;
            boolean repathNow = summonRepathTimer >= 25f;
            if(repathNow) summonRepathTimer = 0f;

            float boardRadius = size * tilesize * 0.85f + tilesize * 1.5f;

            for(int i = summonedUnitIds.size - 1; i >= 0; i--){
                int uid = summonedUnitIds.items[i];
                Unit u = Groups.unit.getByID(uid);
                if(u == null || !u.isValid() || u.dead || u.team != team){
                    summonedUnitIds.removeIndex(i);
                    continue;
                }
                if(!canAcceptUnitIntoBay(u)){
                    summonedUnitIds.removeIndex(i);
                    continue;
                }

                float dist = u.dst(x, y);
                if(dist <= boardRadius + u.hitSize * 0.6f){
                    boardUnitNow(u);
                    summonedUnitIds.removeIndex(i);
                }else if(repathNow && u.controller() instanceof CommandAI ai){
                    if(ai.command == null || ai.command.switchToMove){
                        ai.command(UnitCommand.moveCommand);
                    }
                    ai.commandPosition(Tmp.v1.set(x, y));
                }
            }
        }

        /** Checks whether a live unit can currently board this pad's bay. */
        public boolean canAcceptUnitIntoBay(@Nullable Unit u){
            if(u == null || !u.isValid() || u.dead || u.team != team) return false;
            if(u.isPlayer()) return false;
            int tier = SectorTroopAirliftManager.classifyUnitTier(u.type);
            if(tier < 1 || !padTier.acceptsUnitTier(tier)) return false;

            if(padTier == PadTier.SPECIAL){
                if(!boardedUnits.isEmpty()) return false;
                if(FormationManager.get().getFormationOf(u) != null) return false;
                return tier == 5;
            }
            int cp = TroopAirliftMath.commandPointsForTier(tier);
            int maxCap = padTier.regularCapCp;
            return boardedCp() + cp <= maxCap;
        }

        private void boardUnitNow(Unit u){
            TroopUnitSnapshot snap = SectorTroopAirliftManager.snapshotUnit(u);
            if(padTier == PadTier.SPECIAL){
                snap.formationGroupId = 0;
            }
            boardedUnits.add(snap);
            Fx.spawn.at(x, y);
            u.remove();
        }

        /** Summons a single unit to march into this pad. */
        public boolean summonUnit(Unit u){
            if(!canAcceptUnitIntoBay(u)) return false;
            if(summonedUnitIds.contains(u.id)) return true;
            summonedUnitIds.add(u.id);
            if(u.controller() instanceof CommandAI ai){
                if(ai.command == null || ai.command.switchToMove){
                    ai.command(UnitCommand.moveCommand);
                }
                ai.commandQueue.clear();
                ai.commandPosition(Tmp.v1.set(x, y));
            }
            return true;
        }

        /** Summons all living members of a TacticalFormation to march into this pad. */
        public int summonFormation(TacticalFormation formation){
            if(formation == null || !padTier.allowFormation) return 0;
            Seq<Unit> members = new Seq<>();
            FormationManager.get().getLivingMembers(formation, members);
            if(members.isEmpty()) return 0;

            int neededCp = 0;
            for(int i = 0; i < members.size; i++){
                Unit m = members.get(i);
                int tier = SectorTroopAirliftManager.classifyUnitTier(m.type);
                if(!padTier.acceptsUnitTier(tier)) return 0;
                neededCp += TroopAirliftMath.commandPointsForTier(tier);
            }
            if(boardedCp() + neededCp > padTier.regularCapCp) return 0;

            int summoned = 0;
            for(int i = 0; i < members.size; i++){
                if(summonUnit(members.get(i))){
                    summoned++;
                }
            }
            return summoned;
        }

        /** Safely unloads all boarded units around this pad and restores their formations. */
        public int unloadAllBoardedUnits(){
            summonedUnitIds.clear();
            if(boardedUnits.isEmpty() || world == null || world.tiles == null) return 0;

            IntMap<Seq<Unit>> formGroups = new IntMap<>();
            int unloaded = 0;
            for(int i = 0; i < boardedUnits.size(); i++){
                TroopUnitSnapshot snap = boardedUnits.get(i);
                if(snap == null || !snap.alive) continue;
                UnitType type = content.unit(snap.unitTypeName);
                if(type == null) continue;

                float ang = (i * 55f) % 360f;
                float rad = size * tilesize * 0.75f + tilesize * (1.2f + (i / 6) * 1.1f);
                float sx = Mathf.clamp(x + Angles.trnsx(ang, rad), tilesize * 2f, world.unitWidth() - tilesize * 2f);
                float sy = Mathf.clamp(y + Angles.trnsy(ang, rad), tilesize * 2f, world.unitHeight() - tilesize * 2f);

                Unit u = type.create(team);
                u.set(sx, sy);
                u.rotation = ang;
                u.health = Math.max(1f, Math.min(u.maxHealth, snap.health));
                u.add();
                Fx.spawn.at(sx, sy);

                if(snap.formationGroupId > 0){
                    Seq<Unit> grp = formGroups.get(snap.formationGroupId);
                    if(grp == null){
                        grp = new Seq<>();
                        formGroups.put(snap.formationGroupId, grp);
                    }
                    grp.add(u);
                }
                unloaded++;
            }
            boardedUnits.clear();

            for(Seq<Unit> grp : formGroups.values()){
                if(grp.size >= 2){
                    FormationManager.get().createFormationFromUnits(team, grp, false);
                }
            }
            return unloaded;
        }

        /** Locks a verified route from PlanetDialog onto this launch pad. */
        public void lockRoute(int targetId, LaunchMode mode, RouteMode rMode, int[] route){
            this.targetSectorId = targetId;
            this.launchMode = mode == null ? LaunchMode.REGULAR : mode;
            this.routeMode = rMode == null ? RouteMode.SAFE : rMode;
            if(route != null){
                this.lockedRoute = new int[route.length];
                System.arraycopy(route, 0, this.lockedRoute, 0, route.length);
            }else{
                this.lockedRoute = new int[0];
            }
        }

        public void clearLockedRoute(){
            this.targetSectorId = -1;
            this.launchMode = LaunchMode.REGULAR;
            this.routeMode = RouteMode.SAFE;
            this.lockedRoute = new int[0];
        }

        /** Returns null if ready to launch immediately, or a concise Chinese blocking reason. */
        public @Nullable String checkLaunchBlocker(){
            if(state == null || !state.isCampaign() || !state.hasSector()){
                return "仅可在战役模式中使用跨区块调兵";
            }
            Sector origin = state.getSector();
            if(origin == null || !origin.hasBase()){
                return "起始区块必须拥有己方核心";
            }
            if(cooldownTicks > 0f){
                return "发射台冷却中（剩余 " + Strings.fixed(cooldownTicks / 60f, 1) + " 秒）";
            }
            String payloadErr = TroopAirliftMath.validatePayload(padTier, launchMode, boardedUnits);
            if(payloadErr != null) return payloadErr;
            if(!hasLockedRoute()){
                return "请先点击星图选择并锁定目标航线";
            }
            if(!isCharged()){
                return "电力蓄能尚未完成（" + (int)(chargeFraction() * 100f) + "%）";
            }
            if(!hasRequiredMaterials()){
                return "传送带供料不足，请等待所需材料输送齐备";
            }
            return null;
        }

        /** Attempts to launch the boarded troops along the locked route. */
        public boolean tryLaunch(){
            String blocker = checkLaunchBlocker();
            if(blocker != null){
                if(!headless && ui != null){
                    ui.showInfoFade(blocker);
                }
                return false;
            }

            Sector origin = state.getSector();
            Sector target = (targetSectorId >= 0 && targetSectorId < origin.planet.sectors.size)
                ? origin.planet.sectors.get(targetSectorId) : null;
            if(target == null){
                if(!headless && ui != null) ui.showInfoFade("目标区块无效");
                return false;
            }

            // Deduct conveyor-supplied materials
            LinkedHashMap<String, Integer> req = currentRequiredItems();
            for(Map.Entry<String, Integer> e : req.entrySet()){
                int need = e.getValue().intValue();
                if(need <= 0) continue;
                Item item = content.item(e.getKey());
                if(item != null){
                    items.remove(item, need);
                }
            }

            TroopAirliftMission mission = SectorTroopAirliftManager.get().dispatchMission(
                origin, target, padTier, launchMode, routeMode, lockedRoute, boardedUnits
            );
            if(mission == null){
                if(!headless && ui != null) ui.showInfoFade("调兵发射失败，请重新核对航线与载荷");
                return false;
            }

            boardedUnits.clear();
            summonedUnitIds.clear();
            chargeTicks = 0f;
            cooldownTicks = padTier.cooldownSeconds * 60f;
            launchAnimTicks = 90f;

            launchSound.at(x, y, 1f + Mathf.range(0.08f));
            Fx.launchPod.at(x, y);
            Effect.shake(3.5f, 3.5f, this);

            if(!headless && ui != null){
                ui.showInfoFade("跨区块调兵已起飞 → " + target.name() + "（预计 " + Math.round(mission.totalDurationSeconds) + " 秒抵达）");
            }
            return true;
        }

        @Override
        public void onDeconstructed(@Nullable Unit builder){
            unloadAllBoardedUnits();
            super.onDeconstructed(builder);
        }

        @Override
        public void onDestroyed(){
            unloadAllBoardedUnits();
            super.onDestroyed();
        }

        // ==================== RENDERING & RESERVED ANIMATION HOOKS ====================

        @Override
        public void draw(){
            drawBaseLayer();
            drawBayAnimation(bayAnimProgress);
            drawChargeAnimation(chargeFraction());
            drawPodAndLaunchAnimation(launchAnimTicks > 0f ? (1f - launchAnimTicks / 90f) : 0f);
            drawCooldownAnimation(cooldownTicks > 0f ? Mathf.clamp(cooldownTicks / (padTier.cooldownSeconds * 60f)) : 0f);
            Draw.reset();
        }

        protected void drawBaseLayer(){
            float drawSize = size * tilesize;
            if(baseRegion != null){
                Draw.rect(baseRegion, x, y, drawSize, drawSize);
            }
            if(topRegion != null){
                Draw.rect(topRegion, x, y, drawSize, drawSize);
            }
            // Subtle tier rim border when using fallback placeholder base
            if(!TroopAirliftAssets.hasSprite(name)){
                Draw.color(padTier == PadTier.SPECIAL ? Color.valueOf("f3e979")
                    : padTier == PadTier.HIGH ? Pal.accent : Color.valueOf("84f491"));
                Lines.stroke(1.4f);
                Lines.rect(x - drawSize / 2f + 1.5f, y - drawSize / 2f + 1.5f, drawSize - 3f, drawSize - 3f);
                Draw.color();
            }
        }

        /** Reserved animation slot: {@code <pad>-bay-0..N}. */
        protected void drawBayAnimation(float progress){
            if(bayFrames.length > 0){
                TextureRegion f = TroopAirliftAssets.pickProgressFrame(bayFrames, progress);
                if(f != null){
                    Draw.rect(f, x, y, size * tilesize, size * tilesize);
                }
            }else if(progress > 0.01f){
                Draw.color(Pal.accent, 0.45f * progress);
                Lines.stroke(1.5f);
                Lines.circle(x, y, size * tilesize * (0.45f + 0.15f * Mathf.absin(Time.time, 6f, 1f)));
                Draw.color();
            }
        }

        /** Reserved animation slot: {@code <pad>-charge-0..N} / {@code <pad>-charge}. */
        protected void drawChargeAnimation(float chargeFrac){
            if(chargeFrac <= 0.01f) return;
            if(chargeFrames.length > 0){
                TextureRegion f = TroopAirliftAssets.pickProgressFrame(chargeFrames, chargeFrac);
                if(f != null){
                    Draw.alpha(0.35f + 0.65f * chargeFrac);
                    Draw.rect(f, x, y, size * tilesize, size * tilesize);
                    Draw.color();
                }
            }else if(chargeGlowRegion != null){
                Draw.color(Pal.powerLight, 0.25f + 0.75f * chargeFrac);
                Draw.rect(chargeGlowRegion, x, y, size * tilesize, size * tilesize);
                Draw.color();
            }else{
                Draw.color(isCharged() ? Color.valueOf("84f491") : Pal.powerBar, 0.65f);
                Lines.stroke(1.8f);
                Lines.arc(x, y, size * tilesize * 0.36f, chargeFrac, 90f);
                Draw.color();
            }

            if(lightRegion != null){
                Draw.color(isCharged() ? Color.valueOf("84f491") : Pal.accent, 0.4f + 0.6f * chargeFrac);
                Draw.rect(lightRegion, x, y, size * tilesize, size * tilesize);
                Draw.color();
            }
        }

        /** Reserved animation slot: {@code <pad>-pod}, {@code <pad>-launch-0..N}, {@code <pad>-pod-flame-0..N}. */
        protected void drawPodAndLaunchAnimation(float launchProgress){
            if(launchAnimTicks > 0f){
                if(launchFrames.length > 0){
                    TextureRegion lf = TroopAirliftAssets.pickProgressFrame(launchFrames, launchProgress);
                    if(lf != null){
                        Draw.rect(lf, x, y, size * tilesize, size * tilesize);
                    }
                }
                float liftY = y + Interp.pow3In.apply(launchProgress) * 140f;
                float alpha = Math.max(0f, 1f - launchProgress * 0.9f);
                Draw.z(Layer.effect + 0.01f);
                if(podFlameFrames.length > 0){
                    TextureRegion ff = TroopAirliftAssets.pickLoopFrame(podFlameFrames, Time.time / 60f, 16f);
                    if(ff != null){
                        Draw.alpha(alpha);
                        Draw.rect(ff, x, liftY - 12f);
                    }
                }else{
                    Draw.color(Pal.engine, alpha);
                    Fill.circle(x, liftY - 8f, 6f + (1f - launchProgress) * 5f);
                }
                Draw.color(Color.white, alpha);
                if(podRegion != null){
                    Draw.rect(podRegion, x, liftY);
                }
                Draw.color();
            }else if(!boardedUnits.isEmpty() && podRegion != null){
                Drawf.shadow(x, y, size * tilesize * 0.65f);
                Draw.rect(podRegion, x, y);
            }
        }

        /** Reserved animation slot: {@code <pad>-cooldown-0..N}. */
        protected void drawCooldownAnimation(float cooldownFrac){
            if(cooldownFrac <= 0.01f) return;
            if(cooldownFrames.length > 0){
                TextureRegion cf = TroopAirliftAssets.pickProgressFrame(cooldownFrames, 1f - cooldownFrac);
                if(cf != null){
                    Draw.alpha(0.75f);
                    Draw.rect(cf, x, y, size * tilesize, size * tilesize);
                    Draw.color();
                }
            }else{
                Draw.color(Pal.remove, 0.35f);
                Lines.stroke(1.6f);
                Lines.arc(x, y, size * tilesize * 0.42f, cooldownFrac, 90f);
                Draw.color();
            }
        }

        // ==================== IN-WORLD UI & CONFIGURATION ====================

        @Override
        public void display(Table table){
            super.display(table);
            if(!state.isCampaign() || team != player.team()) return;
            table.row();
            table.label(() -> {
                String destStr = "未锁定";
                if(hasLockedRoute() && state.hasSector() && targetSectorId < state.getSector().planet.sectors.size){
                    Sector t = state.getSector().planet.sectors.get(targetSectorId);
                    destStr = "[accent]" + t.name() + "[](" + (launchMode == LaunchMode.BEACHHEAD ? "抢滩" : (routeMode == RouteMode.SAFE ? "安全" : "快速")) + "·" + lockedHops() + "跳)";
                }
                return "舱内：" + boardedUnits.size() + "台 (" + boardedCp() + "点) | 目标：" + destStr;
            }).pad(4f).wrap().width(240f).left();
        }

        @Override
        public boolean shouldShowConfigure(Player player){
            return state.isCampaign();
        }

        @Override
        public void buildConfiguration(Table table){
            if(!state.isCampaign() || net.client()){
                deselect();
                return;
            }

            table.button(Icon.units, Styles.cleari, () -> {
                showBayManagementDialog();
                deselect();
            }).size(44f).tooltip("舱室登舱管理");

            table.button(Icon.upOpen, Styles.cleari, () -> {
                if(state.hasSector() && ui != null && ui.planet != null){
                    ui.planet.showTroopLaunchSelect(state.getSector(), this);
                }
                deselect();
            }).size(44f).tooltip("星图选择发射目标与航线");

            table.button(Icon.play, Styles.cleari, () -> {
                tryLaunch();
                deselect();
            }).size(44f).tooltip("确认跨区块发射");
        }

        /** Opens the pad's internal boarding & launch readiness management dialog. */
        public void showBayManagementDialog(){
            BaseDialog dialog = new BaseDialog(padTier.displayName + " · 舱室与供料管理");
            dialog.addCloseButton();

            Table body = new Table();
            Runnable[] rebuild = new Runnable[1];
            rebuild[0] = () -> {
                body.clear();
                body.top().left().defaults().left().growX().padBottom(6f);

                // Reserved non-procedural UI header art slot
                TroopAirliftAssets.addOptionalUiArtSlot(body, TroopAirliftAssets.UI_TROOP_PAD_HEADER, 440f, 56f);

                // Summary card
                body.table(Tex.button, sum -> {
                    sum.left().defaults().left().pad(2f);
                    sum.add("[accent]" + padTier.displayName + "[]（允许 T" + padTier.minUnitTier + "–T" + padTier.maxUnitTier + "）").row();
                    String capInfo = padTier.allowBeachhead
                        ? ("常规上限 " + padTier.regularCapCp + " 点 ｜ 抢滩上限 " + padTier.beachheadCapCp + " 点")
                        : ("固定 1 台 T5（20 点，禁止编队与抢滩）");
                    sum.add("[lightgray]" + capInfo).row();
                    sum.label(() -> "已登舱：[accent]" + boardedUnits.size() + " 台 / " + boardedCp() + " 点[]"
                        + " ｜ 召回途中：" + summonedUnitIds.size + " 台"
                        + " ｜ 最慢移速：" + Strings.fixed(TroopAirliftMath.computeMinPayloadSpeed(boardedUnits), 2)).row();
                    sum.label(() -> {
                        if(cooldownTicks > 0f){
                            return "[scarlet]发射冷却中：剩余 " + Strings.fixed(cooldownTicks / 60f, 1) + " 秒";
                        }
                        return "电力蓄能：" + (isCharged() ? "[#84f491]100% 就绪" : ("[accent]" + (int)(chargeFraction() * 100f) + "%"))
                            + "（" + (int)padTier.chargePowerPerSec + "/s × " + (int)padTier.chargeSeconds + "s）";
                    }).row();
                }).growX().row();

                // Material supply status (conveyor-fed)
                body.table(Tex.button, mat -> {
                    mat.left().defaults().left().pad(2f);
                    mat.add("[accent]传送带供料状态[]（必须通过传送带向本发射台输入）").row();
                    if(!hasLockedRoute()){
                        mat.add("[lightgray]尚未锁定目标航线。点击下方「星图选点」后将自动计算精确超线性消耗。").wrap().width(440f).row();
                    }else{
                        LinkedHashMap<String, Integer> req = currentRequiredItems();
                        for(Map.Entry<String, Integer> e : req.entrySet()){
                            Item item = content.item(e.getKey());
                            int need = e.getValue().intValue();
                            int have = item != null ? items.get(item) : 0;
                            String color = have >= need ? "[#84f491]" : "[scarlet]";
                            mat.table(r -> {
                                r.left();
                                if(item != null) r.image(item.uiIcon).size(20f).padRight(6f);
                                r.add((item != null ? item.localizedName : e.getKey()) + "：" + color + have + " / " + need);
                            }).row();
                        }
                    }
                }).growX().row();

                // Boarded units list
                body.add("[accent]舱内已登舱部队（" + boardedUnits.size() + " 台）").padTop(4f).row();
                if(boardedUnits.isEmpty()){
                    body.add("[lightgray]舱室为空，请在下方选择编队或单兵登舱。").row();
                }else{
                    Drawable slotBg = TroopAirliftAssets.drawableOrFallback(TroopAirliftAssets.UI_TROOP_BAY_SLOT, Tex.button);
                    body.table(slotBg, list -> {
                        list.left().defaults().left().pad(2f);
                        for(int i = 0; i < boardedUnits.size(); i++){
                            TroopUnitSnapshot snap = boardedUnits.get(i);
                            UnitType ut = content.unit(snap.unitTypeName);
                            String formTag = snap.formationGroupId > 0 ? (" [accent][编队#" + snap.formationGroupId + "][]") : " [lightgray][单兵][]";
                            list.table(r -> {
                                r.left();
                                if(ut != null) r.image(ut.uiIcon).size(22f).padRight(6f);
                                r.add((ut != null ? ut.localizedName : snap.unitTypeName)
                                    + " T" + snap.tier + " (" + snap.commandPoints + "点) HP "
                                    + Math.round(snap.health) + "/" + Math.round(snap.maxHealth) + formTag);
                            }).row();
                        }
                    }).growX().row();
                }

                // Action bar for boarded units + route + launch
                body.table(acts -> {
                    acts.left().defaults().height(42f).padRight(6f);
                    acts.button("全部卸舱", Icon.cancel, () -> {
                        int n = unloadAllBoardedUnits();
                        if(ui != null) ui.showInfoFade("已卸舱释放 " + n + " 台单位");
                        rebuild[0].run();
                    }).width(135f).disabled(b -> boardedUnits.isEmpty() && summonedUnitIds.isEmpty());

                    acts.button("星图选点", Icon.upOpen, () -> {
                        dialog.hide();
                        if(state.hasSector() && ui != null && ui.planet != null){
                            ui.planet.showTroopLaunchSelect(state.getSector(), this);
                        }
                    }).width(140f);

                    acts.button("确认发射", Icon.play, () -> {
                        if(tryLaunch()){
                            dialog.hide();
                        }else{
                            rebuild[0].run();
                        }
                    }).width(140f);
                }).growX().row();

                // Reserved optional launch button animated flourish slot
                TroopAirliftAssets.addOptionalUiAnimSlot(body, TroopAirliftAssets.UI_TROOP_LAUNCH_BTN_ANIM, 10f, 180f, 24f);

                // Selectable formations in current sector
                body.add("[accent]本区战术编队登舱").padTop(6f).row();
                if(!padTier.allowFormation){
                    body.add("[scarlet]特种兵员发射台禁止搭载编队，仅支持单台 T5 单兵。").row();
                }else{
                    Seq<TacticalFormation> forms = new Seq<>();
                    FormationManager.get().getTeamFormations(team, forms);
                    if(forms.isEmpty()){
                        body.add("[lightgray]当前区块暂无已组建的战术编队。").row();
                    }else{
                        for(int i = 0; i < forms.size; i++){
                            TacticalFormation f = forms.get(i);
                            Seq<Unit> members = new Seq<>();
                            FormationManager.get().getLivingMembers(f, members);
                            int fCp = 0;
                            boolean tierOk = !members.isEmpty();
                            for(int j = 0; j < members.size; j++){
                                int t = SectorTroopAirliftManager.classifyUnitTier(members.get(j).type);
                                if(!padTier.acceptsUnitTier(t)) tierOk = false;
                                fCp += TroopAirliftMath.commandPointsForTier(t);
                            }
                            boolean fits = tierOk && (boardedCp() + fCp <= padTier.regularCapCp);
                            final TacticalFormation formRef = f;
                            final int formCp = fCp;
                            body.table(Tex.button, row -> {
                                row.left();
                                row.add("编队 #" + f.id + "（" + members.size + " 台 / " + formCp + " 点）"
                                    + (tierOk ? "" : " [scarlet][含超阶单位][]")).growX().left();
                                row.button("召回登舱", Icon.add, () -> {
                                    int cnt = summonFormation(formRef);
                                    if(ui != null) ui.showInfoFade("已召回编队 #" + formRef.id + "（" + cnt + " 台）前往登舱");
                                    rebuild[0].run();
                                }).size(120f, 38f).disabled(!fits);
                            }).growX().row();
                        }
                    }
                }

                // Selectable solo units in current sector
                body.add("[accent]本区空闲单兵登舱").padTop(6f).row();
                Seq<Unit> soloCandidates = new Seq<>();
                if(Groups.unit != null){
                    for(Unit u : Groups.unit){
                        if(u == null || !u.isValid() || u.dead || u.team != team || u.isPlayer()) continue;
                        if(FormationManager.get().getFormationOf(u) != null) continue;
                        int t = SectorTroopAirliftManager.classifyUnitTier(u.type);
                        if(t < 1 || !padTier.acceptsUnitTier(t)) continue;
                        soloCandidates.add(u);
                    }
                }
                if(soloCandidates.isEmpty()){
                    body.add("[lightgray]当前无符合本发射台阶数要求的空闲单兵。").row();
                }else{
                    int shown = Math.min(soloCandidates.size, 30);
                    for(int i = 0; i < shown; i++){
                        Unit u = soloCandidates.get(i);
                        int tier = SectorTroopAirliftManager.classifyUnitTier(u.type);
                        int cp = TroopAirliftMath.commandPointsForTier(tier);
                        boolean alreadySummoned = summonedUnitIds.contains(u.id);
                        boolean canBoard = canAcceptUnitIntoBay(u);
                        final Unit unitRef = u;
                        body.table(Tex.button, row -> {
                            row.left();
                            row.image(unitRef.type.uiIcon).size(24f).padRight(6f);
                            row.add(unitRef.type.localizedName + " T" + tier + " (" + cp + "点) HP "
                                + Math.round(unitRef.health) + "/" + Math.round(unitRef.maxHealth)).growX().left();
                            row.button(alreadySummoned ? "召回中" : "召回登舱", Icon.add, () -> {
                                summonUnit(unitRef);
                                if(ui != null) ui.showInfoFade("已召回 " + unitRef.type.localizedName + " 前往发射台登舱");
                                rebuild[0].run();
                            }).size(120f, 38f).disabled(alreadySummoned || !canBoard);
                        }).growX().row();
                    }
                    if(soloCandidates.size > shown){
                        body.add("[lightgray]仅展示前 " + shown + " 台符合条件的单兵（共 " + soloCandidates.size + " 台）").row();
                    }
                }
            };

            rebuild[0].run();
            dialog.cont.pane(body).width(490f).maxHeight(540f).growY();
            dialog.buttons.button("刷新", Icon.refresh, () -> rebuild[0].run()).size(140f, 54f);
            dialog.show();
        }

        // ==================== SAVE / LOAD PERSISTENCE ====================

        @Override
        public byte version(){
            return 1;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.f(chargeTicks);
            write.f(cooldownTicks);
            write.i(targetSectorId);
            write.b((byte)launchMode.ordinal());
            write.b((byte)routeMode.ordinal());
            write.i(lockedRoute.length);
            for(int i = 0; i < lockedRoute.length; i++){
                write.i(lockedRoute[i]);
            }
            write.i(boardedUnits.size());
            for(int i = 0; i < boardedUnits.size(); i++){
                TroopUnitSnapshot s = boardedUnits.get(i);
                write.str(s.unitTypeName);
                write.i(s.tier);
                write.i(s.commandPoints);
                write.f(s.health);
                write.f(s.maxHealth);
                write.f(s.speed);
                write.bool(s.flying);
                write.bool(s.naval);
                write.i(s.formationGroupId);
                write.bool(s.orbitalDamaged);
                write.bool(s.alive);
            }
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            boardedUnits.clear();
            summonedUnitIds.clear();
            if(revision >= 1){
                chargeTicks = read.f();
                cooldownTicks = read.f();
                targetSectorId = read.i();
                launchMode = LaunchMode.fromOrdinal(read.b());
                routeMode = RouteMode.fromOrdinal(read.b());
                int rLen = Math.max(0, read.i());
                lockedRoute = new int[rLen];
                for(int i = 0; i < rLen; i++){
                    lockedRoute[i] = read.i();
                }
                int uLen = Math.max(0, read.i());
                for(int i = 0; i < uLen; i++){
                    TroopUnitSnapshot s = new TroopUnitSnapshot();
                    s.unitTypeName = read.str();
                    s.tier = read.i();
                    s.commandPoints = read.i();
                    s.health = read.f();
                    s.maxHealth = read.f();
                    s.speed = read.f();
                    s.flying = read.bool();
                    s.naval = read.bool();
                    s.formationGroupId = read.i();
                    s.orbitalDamaged = read.bool();
                    s.alive = read.bool();
                    boardedUnits.add(s);
                }
            }
        }
    }
}
