package mindustry.world.blocks.distribution;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.logistics.*;
import mindustry.game.logistics.SectorConveyorMath.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.meta.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * V2.8 Directional Cross-Sector Conveyor Port block ("区块间传送起始端 / 接收端").
 *
 * Covers all four variants:
 * - Junior Sender   (3x3, back-center 1-tile input,  cap 20, intake 60/s, send 2/s,    0 power)
 * - Junior Receiver (3x3, front-center 1-tile output, cap 20, recv 60/s,   release 2/s, 0 power)
 * - Senior Sender   (4x4, back-center 2-tile input,  cap 40, intake 60/s, send 4/s,    240/s power)
 * - Senior Receiver (4x4, front-center 2-tile output, cap 40, recv 60/s,   release 4/s, 0 power)
 *
 * Sprite slots (pre-reserved without animation state machine):
 * - {@code <name>} (base sprite; falls back cleanly to {@link #fallbackSpriteName})
 * - {@code <name>-top} (optional rotated overlay)
 * - {@code <name>-arrow} (optional rotated direction arrow)
 */
public class SectorConveyorPort extends Block{
    /** True for Sender ("起始端"), false for Receiver ("接收端"). */
    public boolean isSender = true;
    /** {@link SectorConveyorMath#TIER_JUNIOR} or {@link SectorConveyorMath#TIER_SENIOR}. */
    public int portTier = SectorConveyorMath.TIER_JUNIOR;
    /** Cross-sector send rate (sender) or local release rate (receiver) in items/sec. */
    public float transferRate = SectorConveyorMath.JUNIOR_RATE;
    /** Local intake ceiling (sender) or cross-sector intake ceiling (receiver) in items/sec. */
    public float intakeRate = SectorConveyorMath.PORT_INTAKE_RATE;
    /** Safe fallback sprite name until custom textures are bundled. */
    public String fallbackSpriteName = "plastanium-conveyor";
    public Color portAccentColor = Pal.accent;

    // Pre-reserved optional static sprite layers
    public TextureRegion topRegion;
    public TextureRegion arrowRegion;
    public boolean usingFallbackSprite = false;

    public SectorConveyorPort(String name){
        super(name);
        rotate = true;
        update = true;
        solid = true;
        destructible = true;
        hasItems = true;
        acceptsItems = true;
        unloadable = false;
        sync = true;
        group = BlockGroup.transportation;
    }

    @Override
    public void init(){
        acceptsItems = isSender;
        itemCapacity = SectorConveyorMath.capacityForTier(portTier);
        transferRate = SectorConveyorMath.rateForTier(portTier);
        super.init();
    }

    @Override
    public void load(){
        super.load();
        if(region == null || !region.found()){
            region = Core.atlas.find(fallbackSpriteName, Core.atlas.find("router"));
            usingFallbackSprite = true;
        }else{
            usingFallbackSprite = false;
        }
        topRegion = Core.atlas.find(name + "-top");
        arrowRegion = Core.atlas.find(name + "-arrow");
    }

    @Override
    public void loadIcon(){
        super.loadIcon();
        if(fullIcon == null || !fullIcon.found()){
            fullIcon = Core.atlas.find(fallbackSpriteName + "-full", Core.atlas.find(fallbackSpriteName, Core.atlas.find("router")));
        }
        if(uiIcon == null || !uiIcon.found()){
            uiIcon = fullIcon;
        }
    }

    @Override
    public void setStats(){
        super.setStats();
        if(isSender){
            stats.add(Stat.input, intakeRate, StatUnit.itemsSecond);
            stats.add(Stat.output, transferRate, StatUnit.itemsSecond);
            stats.add(Stat.abilities, "每区块初级与高级起始端合计限建 4 座；仅背面居中 " + (size == 4 ? "2 格" : "1 格") + "接收物品，支持多物品混传与满载无损背压");
        }else{
            stats.add(Stat.input, intakeRate, StatUnit.itemsSecond);
            stats.add(Stat.output, transferRate, StatUnit.itemsSecond);
            stats.add(Stat.abilities, "接收端不限建造数量；仅正面居中 " + (size == 4 ? "2 格" : "1 格") + "释放物品，可在星球资源视野与相邻起始端 1 对 1 绑定");
        }
    }

    @Override
    public void setBars(){
        super.setBars();
        addBar("portInfo", entity -> new Bar(
            () -> {
                if(!(entity instanceof SectorConveyorPortBuild b)) return isSender ? "起始端" : "接收端";
                String prefix = (isSender ? "起始端 #" : "接收端 #") + (b.portNumber > 0 ? b.portNumber : "?");
                if(!state.isCampaign() || !state.hasSector()) return prefix + " (非战役模式)";
                Sector sec = state.getSector();
                SectorConveyorLink link = SectorConveyorManager.get().getLinkForPort(sec, isSender, b.portNumber);
                if(link == null){
                    return prefix + " · 未配对 (前往资源视野绑定)";
                }
                int peerSecId = isSender ? link.receiverSectorId : link.senderSectorId;
                int peerPort = isSender ? link.receiverPortNumber : link.senderPortNumber;
                String peerName = (peerSecId >= 0 && peerSecId < sec.planet.sectors.size)
                    ? sec.planet.sectors.get(peerSecId).name()
                    : ("区块" + peerSecId);
                SectorPortRecord rec = SectorConveyorManager.get().getPort(sec, isSender, b.portNumber);
                String extra = (isSender && rec != null && rec.backpressurePaused) ? " [满载暂停]" : "";
                return prefix + (isSender ? " → " : " ← ") + peerName + (isSender ? " 接收端#" : " 起始端#") + peerPort + extra;
            },
            () -> {
                if(entity instanceof SectorConveyorPortBuild b && state.isCampaign() && state.hasSector()){
                    SectorPortRecord rec = SectorConveyorManager.get().getPort(state.getSector(), isSender, b.portNumber);
                    if(isSender && rec != null && rec.backpressurePaused) return Pal.remove;
                    if(SectorConveyorManager.get().getLinkForPort(state.getSector(), isSender, b.portNumber) != null){
                        return portAccentColor;
                    }
                }
                return Pal.gray;
            },
            () -> {
                if(entity instanceof SectorConveyorPortBuild b){
                    return itemCapacity <= 0 ? 0f : Mathf.clamp(b.getBufferedCount() / (float)itemCapacity);
                }
                return 0f;
            }
        ));
    }

    /** Counts ALL placed & under-construction cross-sector senders (Junior + Senior combined) for this team. */
    public static int countTeamSenders(@Nullable Team team, @Nullable Tile ignoreTile){
        if(team == null) return 0;
        int count = 0;

        Block[] senderBlocks = new Block[]{
            Blocks.basicSectorConveyorSender,
            Blocks.advancedSectorConveyorSender
        };

        for(Block sb : senderBlocks){
            if(sb == null) continue;
            Seq<Building> existing = team.data().getBuildings(sb);
            for(int i = 0; i < existing.size; i++){
                Building b = existing.get(i);
                if(b != null && b.isValid() && (ignoreTile == null || b.tile != ignoreTile)){
                    count++;
                }
            }
            Seq<Building> constructing = team.data().getBuildings(ConstructBlock.get(sb.size));
            for(int i = 0; i < constructing.size; i++){
                Building b = constructing.get(i);
                if(b instanceof ConstructBuild cb && cb.current == sb && (ignoreTile == null || b.tile != ignoreTile)){
                    count++;
                }
            }
        }
        return count;
    }

    /** Finds the lowest unused positive port number among live buildings (used in non-campaign / fallback). */
    public static int findLowestLocalPortNumber(@Nullable Team team, boolean isSender, @Nullable Building ignore){
        if(team == null) return 1;
        Set<Integer> used = new HashSet<>();
        Block[] targets = isSender
            ? new Block[]{Blocks.basicSectorConveyorSender, Blocks.advancedSectorConveyorSender}
            : new Block[]{Blocks.basicSectorConveyorReceiver, Blocks.advancedSectorConveyorReceiver};
        for(Block blk : targets){
            if(blk == null) continue;
            Seq<Building> existing = team.data().getBuildings(blk);
            for(int i = 0; i < existing.size; i++){
                Building b = existing.get(i);
                if(b instanceof SectorConveyorPortBuild pb && pb != ignore && pb.isValid() && pb.portNumber > 0){
                    used.add(pb.portNumber);
                }
            }
        }
        int num = 1;
        while(used.contains(num)){
            num++;
        }
        return num;
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation){
        if(!super.canPlaceOn(tile, team, rotation)) return false;
        if(isSender && countTeamSenders(team, tile) >= SectorConveyorMath.MAX_SENDERS_PER_SECTOR){
            return false;
        }
        return true;
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        Tile tile = world.tile(x, y);
        if(isSender && tile != null && countTeamSenders(player.team(), tile) >= SectorConveyorMath.MAX_SENDERS_PER_SECTOR){
            drawPlaceText("每个区块初级与高级起始端合计限建 4 座", x, y, false);
            return;
        }
        super.drawPlace(x, y, rotation, valid);

        // Highlight the exact active input (back-center) or output (front-center) tiles and direction
        int[][] slots = SectorConveyorMath.computePortFaceTiles(x, y, size, rotation, !isSender);
        Draw.color(isSender ? Pal.accent : Pal.heal, 0.85f);
        Lines.stroke(1.4f);
        for(int[] slot : slots){
            Lines.square(slot[0] * tilesize, slot[1] * tilesize, tilesize * 0.42f);
        }
        float cx = x * tilesize + offset;
        float cy = y * tilesize + offset;
        Drawf.arrow(cx, cy, cx + Geometry.d4x(rotation) * tilesize * (size * 0.45f), cy + Geometry.d4y(rotation) * tilesize * (size * 0.45f), tilesize * 0.4f, 3f, isSender ? Pal.accent : Pal.heal);
        Draw.reset();
    }

    public class SectorConveyorPortBuild extends Building{
        /** Auto-assigned 1-based port number within this sector & role (persisted in saves). */
        public int portNumber = -1;
        /** Fallback FIFO queue used only outside campaign mode (e.g. sandbox testing). */
        private final ArrayDeque<Integer> localSandboxFifo = new ArrayDeque<>();
        /** Fractional intake budget for 60 items/sec local intake ceiling. */
        private float intakeCarry = 1f;
        /** Fractional local release budget for receiver's 2/s or 4/s output rate. */
        private float releaseCarry = 0f;
        /** Alternates between the 2 front-center output tiles on 4x4 Senior Receivers. */
        private int outputSlotCursor = 0;

        @Override
        public void created(){
            super.created();
            // Do not allocate/register during world loading before read() restores saved portNumber
            if(world == null || !world.isGenerating()){
                ensureRegistered();
            }
        }

        @Override
        public void placed(){
            super.placed();
            if(world == null || !world.isGenerating()){
                ensureRegistered();
            }
        }

        private boolean isCampaignOwned(){
            return state != null && state.isCampaign() && state.hasSector() && team == state.rules.defaultTeam;
        }

        private void ensureRegistered(){
            if(isCampaignOwned()){
                Sector sec = state.getSector();
                SectorConveyorManager mgr = SectorConveyorManager.get();
                if(portNumber <= 0){
                    int allocated = mgr.allocatePortNumber(sec, isSender);
                    if(allocated > 0){
                        portNumber = allocated;
                    }
                }
                if(portNumber > 0){
                    SectorPortRecord rec = mgr.registerLivePort(sec, portNumber, isSender, portTier);
                    if(rec != null){
                        syncItemModuleFromRecord(rec);
                    }
                }
            }else if(portNumber <= 0){
                portNumber = findLowestLocalPortNumber(team, isSender, this);
            }
        }

        @Override
        public void onRemoved(){
            super.onRemoved();
            // Never delete persisted cross-sector port links during map/sector teardown or generation
            if(world != null && world.isGenerating()) return;
            if(isCampaignOwned() && portNumber > 0){
                SectorConveyorManager.get().unregisterLivePort(state.getSector(), portNumber, isSender);
            }
        }

        public int getBufferedCount(){
            if(isCampaignOwned() && portNumber > 0){
                SectorPortRecord rec = SectorConveyorManager.get().getPort(state.getSector(), isSender, portNumber);
                if(rec != null) return rec.bufferedCount();
            }
            return localSandboxFifo.size();
        }

        /** Mirrors the ordered FIFO queue counts into vanilla {@code items} so bars & inspect UI stay accurate. */
        public void syncItemModuleFromRecord(@Nullable SectorPortRecord rec){
            if(items == null) return;
            items.clear();
            if(rec == null || content == null) return;
            for(int itemId : rec.fifo){
                Item it = content.item(itemId);
                if(it != null){
                    items.add(it, 1);
                }
            }
        }

        private void syncItemModuleFromLocalFifo(){
            if(items == null || content == null) return;
            items.clear();
            for(int itemId : localSandboxFifo){
                Item it = content.item(itemId);
                if(it != null){
                    items.add(it, 1);
                }
            }
        }

        /** Verifies whether {@code source} physically touches our exact back-center 1 (3x3) or 2 (4x4) input tile(s). */
        private boolean isSourceAtValidInputSlot(@Nullable Building source){
            if(source == null || source == this) return false;
            int[][] inputSlots = SectorConveyorMath.computePortFaceTiles(tileX(), tileY(), size, rotation, false);
            for(int[] slot : inputSlots){
                Tile t = world.tile(slot[0], slot[1]);
                if(t != null && t.build == source){
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            if(!isSender || item == null) return false;
            if(!isSourceAtValidInputSlot(source)) return false;
            if(intakeCarry < 1f) return false;
            return getBufferedCount() < itemCapacity;
        }

        @Override
        public void handleItem(Building source, Item item){
            if(!isSender || item == null) return;
            if(getBufferedCount() >= itemCapacity) return;

            intakeCarry = Math.max(0f, intakeCarry - 1f);
            if(isCampaignOwned()){
                ensureRegistered();
                SectorPortRecord rec = SectorConveyorManager.get().getPort(state.getSector(), true, portNumber);
                if(rec != null && rec.enqueue((int)item.id)){
                    rec.recordAcceptedItem((int)item.id);
                    if(items != null) items.add(item, 1);
                }
            }else{
                if(localSandboxFifo.size() < itemCapacity){
                    localSandboxFifo.addLast((int)item.id);
                    if(items != null) items.add(item, 1);
                }
            }
        }

        @Override
        public void updateTile(){
            float dtSec = delta() / 60f;
            if(dtSec <= 0f) return;

            // Replenish 60/s intake allowance (capped at 1 per tick burst so intake never exceeds 60/s)
            intakeCarry = Math.min(2f, intakeCarry + intakeRate * dtSec);

            if(isCampaignOwned()){
                ensureRegistered();
                Sector sec = state.getSector();
                SectorConveyorManager mgr = SectorConveyorManager.get();

                if(isSender){
                    // Connected multiplayer clients defer cross-sector transfers to the authoritative Host
                    int moved = 0;
                    if(net == null || !net.isConnectedClient()){
                        float eff = consumesPower ? efficiency : 1f;
                        moved = mgr.stepLiveSender(sec, portNumber, dtSec, eff);
                    }
                    SectorPortRecord rec = mgr.getPort(sec, true, portNumber);
                    if(rec != null && (moved > 0 || (items != null && items.total() != rec.bufferedCount()))){
                        syncItemModuleFromRecord(rec);
                    }
                }else{
                    mgr.stepLiveReceiverWindow(sec, portNumber, dtSec);
                    SectorPortRecord rec = mgr.getPort(sec, false, portNumber);
                    if(rec != null){
                        updateReceiverRelease(rec, dtSec);
                        if(items != null && items.total() != rec.bufferedCount()){
                            syncItemModuleFromRecord(rec);
                        }
                    }
                }
            }else if(!isSender && !localSandboxFifo.isEmpty()){
                releaseCarry = Math.min(releaseCarry + transferRate * dtSec, Math.max(1f, transferRate));
                while(releaseCarry >= 1f && !localSandboxFifo.isEmpty()){
                    int headId = localSandboxFifo.peekFirst();
                    Item item = content.item(headId);
                    if(item == null){
                        localSandboxFifo.pollFirst();
                        continue;
                    }
                    if(tryEmitFrontCenter(item)){
                        localSandboxFifo.pollFirst();
                        releaseCarry -= 1f;
                    }else{
                        break;
                    }
                }
                syncItemModuleFromLocalFifo();
            }
        }

        private void updateReceiverRelease(SectorPortRecord rec, float dtSec){
            if(rec.isEmpty()){
                releaseCarry = Math.min(releaseCarry, 1f);
                return;
            }
            releaseCarry = Math.min(releaseCarry + transferRate * dtSec, Math.max(1f, transferRate));
            while(releaseCarry >= 1f && !rec.isEmpty()){
                int headId = rec.peek();
                Item item = content.item(headId);
                if(item == null){
                    rec.dequeue();
                    continue;
                }
                if(tryEmitFrontCenter(item)){
                    rec.dequeue();
                    releaseCarry -= 1f;
                }else{
                    // Downstream belt blocked -> retain item cleanly in receiver buffer (backpressure!)
                    break;
                }
            }
        }

        /** Pushes one item strictly out of the front-center 1 (3x3) or 2 (4x4) tile(s). */
        private boolean tryEmitFrontCenter(Item item){
            int[][] outSlots = SectorConveyorMath.computePortFaceTiles(tileX(), tileY(), size, rotation, true);
            int n = outSlots.length;
            for(int i = 0; i < n; i++){
                int idx = (outputSlotCursor + i) % n;
                Tile targetTile = world.tile(outSlots[idx][0], outSlots[idx][1]);
                Building target = targetTile == null ? null : targetTile.build;
                if(target != null && target != this && target.team == team && target.acceptItem(this, item)){
                    target.handleItem(this, item);
                    outputSlotCursor = (idx + 1) % n;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void draw(){
            float drawRot = rotdeg();
            if(usingFallbackSprite){
                Draw.rect(region, x, y, size * tilesize, size * tilesize, drawRot);
            }else{
                Draw.rect(region, x, y, drawRot);
            }
            if(topRegion != null && topRegion.found()){
                Draw.rect(topRegion, x, y, drawRot);
            }
            if(arrowRegion != null && arrowRegion.found()){
                Draw.rect(arrowRegion, x, y, drawRot);
            }

            // Direction & port indicator overlay (subtle and clean for fallback & identification)
            float prevZ = Draw.z();
            Draw.z(Layer.blockOver);
            boolean linked = isCampaignOwned() && portNumber > 0
                && SectorConveyorManager.get().getLinkForPort(state.getSector(), isSender, portNumber) != null;
            Color badgeColor = linked ? portAccentColor : Pal.gray;
            if(isCampaignOwned() && isSender && portNumber > 0){
                SectorPortRecord rec = SectorConveyorManager.get().getPort(state.getSector(), true, portNumber);
                if(rec != null && rec.backpressurePaused){
                    badgeColor = Pal.remove;
                }
            }

            if(usingFallbackSprite){
                Draw.color(badgeColor, 0.85f);
                Lines.stroke(1.3f);
                Lines.square(x, y, size * tilesize * 0.36f);
                float dx = Geometry.d4x(rotation) * (size * tilesize * 0.28f);
                float dy = Geometry.d4y(rotation) * (size * tilesize * 0.28f);
                Drawf.arrow(x - dx * 0.6f, y - dy * 0.6f, x + dx, y + dy, tilesize * 0.35f, 2.5f, badgeColor);
                Draw.reset();
            }

            // Draw compact DPI-safe port number label ("发1" / "收1") so players identify ports cleanly on all screens
            if(portNumber > 0 && Fonts.outline != null){
                Font font = Fonts.outline;
                boolean prevInts = font.usesIntegerPositions();
                font.setUseIntegerPositions(false);
                font.getData().setScale(1f / 4f / Scl.scl(1f));
                font.setColor(badgeColor);
                font.draw((isSender ? "发" : "收") + portNumber, x, y + font.getCapHeight() * 0.5f, Align.center);
                font.setColor(Color.white);
                font.getData().setScale(1f);
                font.setUseIntegerPositions(prevInts);
            }

            Draw.reset();
            Draw.z(prevZ);
        }

        @Override
        public byte version(){
            return 1;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.i(portNumber);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            if(revision >= 1){
                portNumber = read.i();
            }
            // In non-campaign maps (e.g. sandbox), rebuild local FIFO from persisted ItemModule
            if(!isCampaignOwned() && items != null){
                localSandboxFifo.clear();
                items.each((item, amount) -> {
                    for(int i = 0; i < amount && localSandboxFifo.size() < itemCapacity; i++){
                        localSandboxFifo.addLast((int)item.id);
                    }
                });
            }
        }
    }
}
