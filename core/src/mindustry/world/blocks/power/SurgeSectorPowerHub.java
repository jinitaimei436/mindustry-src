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
 * - Safe sprite fallback to {@code surge-tower} if custom artwork has not yet been bundled.
 */
public class SurgeSectorPowerHub extends PowerNode{
    public Color hubGlowColor = Pal.accent;

    public SurgeSectorPowerHub(String name){
        super(name);
        size = 4;
        laserRange = 20f;
        maxNodes = 20;
        consumesPower = false;
        outputsPower = false;
        hasPower = true;
        update = true;
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
        }
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
                if(!state.isCampaign() || !state.hasSector()) return "跨区电网: 非战役模式";
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                if(agg.sectorCount() <= 1){
                    return "跨区电网: 单区独立 (前往星球电力视野连线)";
                }
                long netPerSec = Math.round(agg.netBalancePerTick * 60f);
                String sign = netPerSec >= 0 ? "+" : "";
                return "跨区电网: " + agg.sectorCount() + "区块 (" + sign + UI.formatAmount(netPerSec) + "/秒)";
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

        @Override
        public void created(){
            super.created();
            registerIfCampaign();
        }

        @Override
        public void placed(){
            super.placed();
            registerIfCampaign();
        }

        private void registerIfCampaign(){
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                SectorPowerGridManager.get().onHubActive(state.getSector(), this);
            }
        }

        @Override
        public void onRemoved(){
            super.onRemoved();
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                // Only unregister if no other hub remains for this team in this sector
                if(!hasExistingHub(team, tile)){
                    SectorPowerGridManager.get().onHubRemoved(state.getSector());
                }
            }
        }

        @Override
        public void draw(){
            // Draw base building + power lines
            Draw.rect(region, x, y, size * tilesize, size * tilesize);

            // Subtle golden beacon core indicating inter-sector grid status
            if(state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam){
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(state.getSector());
                Color glow = agg.overloaded ? Pal.remove : hubGlowColor;
                float pulse = 0.55f + Mathf.absin(Time.time, 8f, 0.35f);
                Draw.z(Layer.blockOver);
                Draw.color(glow, pulse);
                Fill.circle(x, y, 4.5f + Mathf.absin(Time.time, 8f, 1.5f));
                Lines.stroke(1.4f);
                Lines.circle(x, y, 9.5f + Mathf.absin(Time.time, 10f, 2f));
                Draw.reset();
            }

            super.draw();
        }
    }
}
