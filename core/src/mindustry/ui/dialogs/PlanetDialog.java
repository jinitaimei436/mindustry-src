package mindustry.ui.dialogs;

import arc.*;
import arc.assets.loaders.TextureLoader.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.content.TechTree.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.game.Objectives.*;
import mindustry.game.SectorInfo.*;
import mindustry.game.*;
import mindustry.game.coop.*;
import mindustry.game.logistics.*;
import mindustry.game.logistics.SectorConveyorMath.*;
import mindustry.game.power.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.game.strategy.core.*;
import mindustry.game.troops.*;
import mindustry.game.troops.TroopAirliftMath.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.graphics.g3d.PlanetGrid.*;
import mindustry.graphics.g3d.*;
import mindustry.input.*;
import mindustry.io.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.type.Planet.*;
import mindustry.ui.*;
import mindustry.world.blocks.campaign.TroopLaunchPad.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static arc.Core.*;
import static mindustry.Vars.*;
import static mindustry.graphics.g3d.PlanetRenderer.*;
import static mindustry.ui.dialogs.PlanetDialog.Mode.*;

public class PlanetDialog extends BaseDialog implements PlanetInterfaceRenderer{
    //if true, enables launching anywhere for testing
    public static boolean debugSelect = false, debugSectorAttackEdit, debugShowNumbers = false;
    public static float sectorShowDuration = 60f * 2.4f;

    public final FrameBuffer buffer = new FrameBuffer(2, 2, true);
    public final LaunchLoadoutDialog loadouts = new LaunchLoadoutDialog();
    public final PlanetRenderer planets = renderer.planets;

    public PlanetParams state = new PlanetParams();
    public float zoom = 1f;
    public @Nullable Sector selected, hovered, launchSector;
    /** Must not be null in planet launch mode. */
    public @Nullable Seq<Planet> launchCandidates;
    public Mode mode = look;
    public boolean launching;
    public Cons<Sector> listener = s -> {};

    public Seq<Sector> newPresets = new Seq<>();
    public float presetShow = 0f;
    public boolean showed = false, sectorsShown;
    public String searchText = "";

    public Table sectorTop = new Table(), notifs = new Table(), expandTable = new Table();
    public Label hoverLabel = new Label("");

    private Texture[] planetTextures;
    private Element mainView;
    private SectorSelectDialog selectDialog = new SectorSelectDialog();
    /** V2.5: Planet Power View overlay state ("电力视野") for cross-sector power grid management. */
    public boolean powerViewMode = false;
    public @Nullable Sector powerLinkSource = null;
    private int lastPowerGridRevision = -1;
    /** V2.8: Global Resource View overlay state ("资源视野"), strictly mutually exclusive with powerViewMode. */
    public boolean resourceViewMode = false;
    private int lastConveyorRevision = -1;
    /** V3.2: Reusable planet-local camera direction vector for zero-allocation hemisphere backface culling. */
    private final Vec3 tmpCamDir = new Vec3();
    /** V3.3: LaunchPad-style cross-sector conveyor sender pairing mode state. */
    private int conveyorSelectSenderPort = -1;
    private final IntSet conveyorAdjacentIds = new IntSet();
    private final Vec3 tmpRingA = new Vec3(), tmpRingB = new Vec3(), tmpRingC = new Vec3(), tmpRingInA = new Vec3(), tmpRingInB = new Vec3();
    /** V3.9: Dedicated Troop Airlift View ("调兵视野"), strictly mutually exclusive with Normal / Power / Resource views. */
    public boolean troopViewMode = false;
    private int lastTroopRevision = -1;
    /** V3.9: Active troop launch pad when selecting target sector & route on the planet map. */
    private @Nullable TroopLaunchPadBuild activeTroopPad = null;
    private final IntSet troopAdjacentIds = new IntSet();

    public PlanetDialog(){
        super("", Styles.fullDialog);

        state.renderer = this;
        state.drawUi = true;

        shouldPause = true;
        state.planet = content.getByName(ContentType.planet, Core.settings.getString("lastplanet", "serpulo"));
        if(state.planet == null) state.planet = Planets.serpulo;

        clampZoom();

        addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode key){
                if(event.targetActor == PlanetDialog.this && (key == KeyCode.escape || key == KeyCode.back || key == Binding.planetMap.value.key)){
                    if(showing() && newPresets.size > 1){
                        //clear all except first, which is the last sector.
                        newPresets.truncate(1);
                    }else if(powerLinkSource != null){
                        powerLinkSource = null;
                        updateSelected();
                    }else if(selected != null){
                        selectSector(null);
                    }else{
                        Core.app.post(() -> hide());
                    }
                    return true;
                }
                return false;
            }
        });

        hoverLabel.setStyle(Styles.outlineLabel);
        hoverLabel.setAlignment(Align.center);

        rebuildButtons();

        onResize(this::rebuildButtons);

        dragged((cx, cy) -> {
            //no multitouch drag
            if(Core.input.getTouches() > 1) return;

            if(showing() && newPresets.peek() != state.planet.getLastSector()) return;

            newPresets.clear();

            Vec3 pos = state.camPos;

            float upV = pos.angle(Vec3.Y);
            float xscale = 9f, yscale = 10f;
            float margin = 1;

            //scale X speed depending on polar coordinate
            float speed = 1f - Math.abs(upV - 90) / 90f;

            pos.rotate(state.camUp, cx / xscale * speed);

            //prevent user from scrolling all the way up and glitching it out
            float amount = cy / yscale;
            amount = Mathf.clamp(upV + amount, margin, 180f - margin) - upV;

            pos.rotate(Tmp.v31.set(state.camUp).rotate(state.camDir, 90), amount);
        });

        addListener(new InputListener(){
            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                if(event.targetActor == PlanetDialog.this){
                    zoom = Mathf.clamp(zoom + amountY / 10f, state.planet.minZoom, state.planet.maxZoom);
                }
                return true;
            }
        });

        addCaptureListener(new ElementGestureListener(){
            float lastZoom = -1f;

            @Override
            public void zoom(InputEvent event, float initialDistance, float distance){
                if(lastZoom < 0){
                    lastZoom = zoom;
                }

                zoom = (Mathf.clamp(initialDistance / distance * lastZoom, state.planet.minZoom, state.planet.maxZoom));
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                lastZoom = zoom;
            }

            @Override
            public void tap(InputEvent event, float x, float y, int count, KeyCode button){
                var hovered = getHoverPlanet(x, y);
                if(hovered != null){
                    var hit = scene.hit(x, y, true);
                    if(hit == mainView){
                        viewPlanet(hovered, false);
                    }
                }
            }
        });

        shown(this::setup);

        //show selection of Erekir/Serpulo campaign if the user has no bases, and hasn't selected yet (essentially a "have they played campaign before" check)
        shown(() -> {
            if(!net.isConnectedClient() && !settings.getBool("campaignselect") && !content.planets().contains(p -> p.sectors.contains(Sector::hasBase))){
                var diag = new BaseDialog("@campaign.select");

                Planet[] selected = {null};
                var group = new ButtonGroup<>();
                group.setMinCheckCount(0);
                state.planet = Planets.sun;
                Planet[] choices = {Planets.serpulo, Planets.erekir};
                int i = 0;
                for(var planet : choices){
                    TextureRegion tex = new TextureRegion(planetTextures[i]);

                    diag.cont.button(b -> {
                        b.top();
                        b.add(planet.localizedName).color(Pal.accent).style(Styles.outlineLabel);
                        b.row();
                        b.image(new TextureRegionDrawable(tex)).grow().scaling(Scaling.fit);
                    }, Styles.togglet, () -> selected[0] = planet).size(mobile ? 220f : 320f).group(group);
                    i ++;
                }

                diag.cont.row();
                diag.cont.label(() -> selected[0] == null ? "@campaign.none" : "@campaign." + selected[0].name).labelAlign(Align.center).style(Styles.outlineLabel).width(440f).wrap().colspan(2);

                diag.buttons.button("@ok", Icon.ok, () -> {
                    state.planet = selected[0];
                    lookAt(state.planet.getStartSector());
                    selectSector(state.planet.getStartSector());
                    settings.put("campaignselect", true);
                    diag.hide();
                }).size(300f, 64f).disabled(b -> selected[0] == null);

                app.post(diag::show);
            }else if(hadSerpuloRemaps && settings.getBoolOnce("serpulo-remaps-notice")){
                var diag = new BaseDialog("@campaign.rework.title");
                diag.cont.add("@campaign.rework.serpulo").labelAlign(Align.center).growX().wrap().maxWidth(500f);
                diag.buttons.button("@ok", Icon.ok, diag::hide).size(280f, 64f);

                app.post(diag::show);
            }
        });

        planetTextures = new Texture[2];
        String[] names = {"sprites/planets/serpulo.png", "sprites/planets/erekir.png"};
        for(int i = 0; i < names.length; i++){
            int fi = i;
            assets.load(names[i], Texture.class, new TextureParameter(){{
                minFilter = magFilter = TextureFilter.linear;
            }}).loaded = t -> planetTextures[fi] = t;
            assets.finishLoadingAsset(names[i]);
        }
    }

    /** show with no limitations, just as a map. */
    @Override
    public Dialog show(){
        if(net.isConnectedClient() && !Vars.state.isCampaign()){
            ui.showInfo("@map.multiplayer");
            return this;
        }

        //view current planet by default
        if(Vars.state.rules.sector != null){
            state.planet = Vars.state.rules.sector.planet;
            settings.put("lastplanet", state.planet.name);
        }

        if(!selectable(state.planet)){
            state.planet = Planets.serpulo;
        }

        mode = look;
        conveyorSelectSenderPort = -1;
        conveyorAdjacentIds.clear();
        activeTroopPad = null;
        troopAdjacentIds.clear();
        rebuildButtons();
        state.otherCamPos = null;
        selected = hovered = launchSector = null;
        powerLinkSource = null;
        launching = false;
        SectorPowerGridManager.get().syncCurrentSectorHubStatus();
        SectorConveyorManager.get().syncCurrentSectorPorts();
        if(Vars.state != null && Vars.state.isCampaign() && Vars.state.hasSector()){
            SectorTroopAirliftManager.get().captureLiveSectorSnapshot(Vars.state.getSector());
        }

        zoom = 1f;
        state.zoom = 1f;
        state.uiAlpha = 0f;
        launchSector = Vars.state.gameOver ? null : Vars.state.getSector();
        presetShow = 0f;
        showed = false;
        listener = s -> {};

        newPresets.clear();

        //announce new presets (skip mutating local singleplayer flags when connected as guest)
        if(!net.isConnectedClient()){
            for(SectorPreset preset : content.sectors()){
                if(preset.unlocked() && preset.sector.preset == preset && !preset.alwaysUnlocked && !preset.sector.info.shown && preset.requireUnlock && !preset.sector.hasBase() && preset.planet == state.planet){
                    newPresets.add(preset.sector);
                    preset.sector.info.shown = true;
                    preset.sector.saveInfo();
                }
            }
        }

        if(newPresets.any()){
            newPresets.add(state.planet.getLastSector());
        }

        newPresets.reverse();
        updateSelected();

        if(state.planet.getLastSector() != null){
            lookAt(state.planet.getLastSector());
        }

        return super.show();
    }

    void rebuildButtons(){
        buttons.clearChildren();

        buttons.bottom();

        if(Core.graphics.isPortrait()){
            buttons.add(sectorTop).colspan(2).fillX().row();
            addBack();
            addRightAction();
        }else{
            float uiWidth = Core.graphics.getWidth() / Scl.scl(1f);
            float maxCenter = Math.max(180f, Math.min(340f, uiWidth - 420f));
            addBack();
            buttons.add().growX();
            buttons.add(sectorTop).minWidth(Math.min(230f, maxCenter)).maxWidth(maxCenter);
            buttons.add().growX();
            addRightAction();
        }
    }

    void addBack(){
        buttons.button("@back", Icon.left, this::hide).size(200f, 54f).pad(2).bottom();
    }

    void addRightAction(){
        if(mode == look && resourceViewMode){
            buttons.button("详情", Icon.info, () -> {
                if(selected != null && selected.hasBase()){
                    showResourceDetails(selected);
                }
            }).size(200f, 54f).disabled(b -> selected == null || !selected.hasBase()).pad(2).bottom();
        }else if(mode == look && powerViewMode){
            buttons.button("详情", Icon.info, () -> {
                if(selected != null && selected.hasBase()){
                    showPowerDetails(selected);
                }
            }).size(200f, 54f).disabled(b -> selected == null || !selected.hasBase()).pad(2).bottom();
        }else if(mode == look && troopViewMode){
            buttons.button("详情", Icon.info, () -> {
                if(selected != null){
                    showTroopSectorDetails(selected);
                }
            }).size(200f, 54f).disabled(b -> selected == null).pad(2).bottom();
        }else if(mode == conveyorSelect){
            buttons.button("断开当前连接", Icon.cancelSmall, () -> {
                if(launchSector != null && conveyorSelectSenderPort > 0){
                    int res = SectorConveyorManager.get().requestUnbind(launchSector.planet, launchSector.id, conveyorSelectSenderPort, true);
                    if(res == -1){
                        ui.showInfoFade("已断开起始端" + conveyorSelectSenderPort + "的连接");
                    }else if(res == 2){
                        ui.showInfoFade("已向房主请求断开连接");
                    }
                    rebuildButtons();
                }
            }).size(200f, 54f).disabled(b -> launchSector == null || conveyorSelectSenderPort <= 0
                || SectorConveyorManager.get().getLinkForPort(launchSector, true, conveyorSelectSenderPort) == null).pad(2).bottom();
        }else if(mode == troopLaunchSelect){
            buttons.button("清除锁定航线", Icon.cancelSmall, () -> {
                if(activeTroopPad != null){
                    activeTroopPad.clearLockedRoute();
                    ui.showInfoFade("已清除该发射台的锁定航线");
                    rebuildButtons();
                }
            }).size(200f, 54f).disabled(b -> activeTroopPad == null || !activeTroopPad.hasLockedRoute()).pad(2).bottom();
        }else{
            buttons.button("@techtree", Icon.tree, () -> ui.research.show()).size(200f, 54f).visible(() -> mode == look).pad(2).bottom();
        }
    }

    public void showOverview(){
        //TODO implement later if necessary
        /*
        sectors.captured = Captured Sectors
        sectors.explored = Explored Sectors
        sectors.production.total = Total Production
        sectors.resources.total = Total Resources
         */
        var dialog = new BaseDialog("@overview");
        dialog.addCloseButton();

        dialog.add("@sectors.captured");
    }

    //TODO not fully implemented, cutscene needed
    public void showPlanetLaunch(Sector sector, Seq<Planet> launchCandidates, Cons<Sector> listener){
        selected = null;
        hovered = null;
        launching = false;
        this.listener = listener;
        this.launchCandidates = (launchCandidates == null ? sector.planet.launchCandidates : launchCandidates);
        launchSector = sector;

        //automatically select next planets;
        if(this.launchCandidates.size == 1){
            state.planet = this.launchCandidates.first();
            state.otherCamPos = sector.planet.position;
            state.otherCamAlpha = 0f;

            //unlock and highlight sector
            var destSec = state.planet.sectors.get(state.planet.startSector);
            var preset = destSec.preset;
            if(preset != null){
                preset.unlock();
            }
            selected = destSec;
        }

        //TODO pan over to correct planet

        //update view to sector
        zoom = 1f;
        state.zoom = 1f;
        state.uiAlpha = 0f;

        mode = planetLaunch;
        powerViewMode = false;
        resourceViewMode = false;
        troopViewMode = false;
        powerLinkSource = null;
        conveyorSelectSenderPort = -1;
        conveyorAdjacentIds.clear();
        activeTroopPad = null;
        troopAdjacentIds.clear();

        rebuildButtons();
        updateSelected();
        rebuildExpand();

        if(sectorTop != null){
            sectorTop.color.a = 0f;
        }

        super.show();
    }

    public void showSelect(Sector sector, Cons<Sector> listener){
        selected = null;
        hovered = null;
        powerViewMode = false;
        resourceViewMode = false;
        troopViewMode = false;
        powerLinkSource = null;
        conveyorSelectSenderPort = -1;
        conveyorAdjacentIds.clear();
        activeTroopPad = null;
        troopAdjacentIds.clear();
        launching = false;
        this.listener = listener;

        //update view to sector
        lookAt(sector);
        zoom = 1f;
        state.zoom = 1f;
        state.uiAlpha = 0f;
        state.otherCamPos = null;
        launchSector = sector;

        mode = select;
        rebuildButtons();

        super.show();
    }

    /** V3.3: Opens LaunchPad-style planet map restricted strictly to the surrounding adjacent sectors for conveyor pairing. */
    public void showConveyorSelect(Sector sector, int senderPortNumber){
        if(sector == null || senderPortNumber <= 0) return;

        selected = null;
        hovered = null;
        powerViewMode = false;
        resourceViewMode = false;
        troopViewMode = false;
        powerLinkSource = null;
        activeTroopPad = null;
        troopAdjacentIds.clear();
        launching = false;
        launchSector = sector;
        conveyorSelectSenderPort = senderPortNumber;

        conveyorAdjacentIds.clear();
        for(Sector near : sector.near()){
            conveyorAdjacentIds.add(near.id);
        }

        SectorConveyorManager.get().syncCurrentSectorPorts();

        lookAt(sector);
        zoom = 1f;
        state.zoom = 1f;
        state.uiAlpha = 0f;
        state.otherCamPos = null;

        mode = conveyorSelect;
        rebuildButtons();
        updateSelected();
        rebuildExpand();

        super.show();
    }

    /** V3.9: Opens planet map to select a cross-sector troop airlift destination and lock its route on {@code pad}. */
    public void showTroopLaunchSelect(Sector origin, TroopLaunchPadBuild pad){
        if(origin == null || pad == null) return;

        selected = null;
        hovered = null;
        powerViewMode = false;
        resourceViewMode = false;
        troopViewMode = false;
        powerLinkSource = null;
        conveyorSelectSenderPort = -1;
        conveyorAdjacentIds.clear();
        launching = false;

        launchSector = origin;
        activeTroopPad = pad;
        troopAdjacentIds.clear();
        for(Sector near : origin.near()){
            troopAdjacentIds.add(near.id);
        }

        SectorTroopAirliftManager.get().captureLiveSectorSnapshot(origin);

        lookAt(origin);
        zoom = 1f;
        state.zoom = 1f;
        state.uiAlpha = 0f;
        state.otherCamPos = null;

        mode = troopLaunchSelect;
        rebuildButtons();
        updateSelected();
        rebuildExpand();

        super.show();
    }

    public void lookAt(Sector sector){
        if(sector.tile == Ptile.empty) return;

        //TODO should this even set `state.planet`? the other lookAt() doesn't, so...
        state.planet = sector.planet;
        sector.planet.lookAt(sector, state.camPos);

        clampZoom();
    }

    public void lookAt(Sector sector, float alpha){
        float len = state.camPos.len();
        state.camPos.slerp(sector.planet.lookAt(sector, Tmp.v33).setLength(len), alpha);
    }

    void clampZoom(){
        zoom = Mathf.clamp(zoom, state.planet.minZoom, state.planet.maxZoom);
    }

    boolean canSelect(Sector sector){
        if(mode == conveyorSelect){
            return launchSector != null && sector.planet == launchSector.planet && conveyorAdjacentIds.contains(sector.id);
        }
        if(mode == troopLaunchSelect){
            if(launchSector == null || activeTroopPad == null || sector.planet != launchSector.planet || sector == launchSector){
                return false;
            }
            if(sector.hasBase()) return true;
            return activeTroopPad.padTier().allowBeachhead && troopAdjacentIds.contains(sector.id);
        }
        if(mode == select) return sector.hasBase() && launchSector != null && sector.planet == launchSector.planet;

        //sectors with addStartingItems = true can't be landed on, as they override the core and items of the interplanetary accelerator
        //at the moment, this is only true of Ground Zero in vanilla
        //TODO: maybe relax these restrictions and add better support for these sorts of "tutorial" starting sectors later
        if(mode == planetLaunch && (sector.hasBase() || (sector.preset != null && sector.preset.addStartingItems))) return false;

        if(sector.planet.generator == null) return false;

        if(mode == look && SectorTroopAirliftManager.get().hasFriendlyBeachhead(sector)) return true;

        if(sector.isShielded()) return false;

        if(sector.hasBase() || sector.id == sector.planet.startSector) return true;

        //preset sectors can only be selected once unlocked
        if(sector.preset != null && sector.preset.requireUnlock){
            TechNode node = sector.preset.techNode;
            return sector.preset.unlocked() || node == null || node.parent == null || (node.parent.content.unlocked() && (!(node.parent.content instanceof SectorPreset preset) || preset.sector.hasBase()));
        }

        return mode == planetLaunch ? sector.planet.generator.allowAcceleratorLanding(sector) : sector.planet.generator.allowLanding(sector);
    }

    @Nullable Sector findLauncher(Sector to){
        if(mode == planetLaunch || to.planet.generator == null) return launchSector;

        Sector actualLaunchSector = (launchSector != null && launchSector.planet == to.planet ? launchSector : null);

        Sector candidate = to.planet.generator.findLaunchCandidate(to, actualLaunchSector);
        return candidate == null && actualLaunchSector != null && (mode == planetLaunch || !actualLaunchSector.isAttacked()) ? actualLaunchSector : candidate;
    }

    boolean showing(){
        return newPresets.any();
    }

    @Override
    public void renderSectors(Planet planet){
        if(mode == conveyorSelect && launchSector != null && launchSector.planet == planet){
            if(state.uiAlpha > 0.01f){
                SectorConveyorLink currentLink = SectorConveyorManager.get().getLinkForPort(launchSector, true, conveyorSelectSenderPort);
                int pairedSecId = currentLink != null ? currentLink.receiverSectorId : -1;

                for(Sector sec : planet.sectors){
                    if(conveyorAdjacentIds.contains(sec.id)){
                        if(sec.id == pairedSecId){
                            planets.drawSelection(sec, Tmp.c1.set(Pal.heal).a(state.uiAlpha * 0.95f), 0.036f, 0.002f);
                        }else if(sec.hasBase()){
                            planets.drawSelection(sec, Tmp.c1.set(Pal.lancerLaser).a(state.uiAlpha * 0.72f), 0.024f, 0.001f);
                        }else{
                            planets.drawSelection(sec, Tmp.c1.set(Color.lightGray).a(state.uiAlpha * 0.45f), 0.018f, 0.001f);
                        }
                    }else if(sec == launchSector){
                        planets.fill(sec, Tmp.c1.set(Pal.accent).a(0.18f * state.uiAlpha), -0.001f);
                        planets.drawSelection(sec, Tmp.c1.set(Pal.accent).a(0.55f * state.uiAlpha), 0.026f, 0.0015f);
                    }else{
                        planets.fill(sec, Tmp.c1.set(0.15f, 0.15f, 0.17f, 0.80f * state.uiAlpha), -0.001f);
                    }
                }

                // Outline the outer hexagonal ring formed by the surrounding 6 adjacent sectors
                drawAdjacentHexRingOutline(planet, launchSector, Tmp.c1.set(Pal.accent).a(state.uiAlpha), 0.055f, 0.0035f);
            }

            if(hovered != null && canSelect(hovered)){
                planets.fill(hovered, hoverColor.write(Tmp.c1).mulA(state.uiAlpha), -0.003f);
                planets.drawBorders(hovered, borderColor, state.uiAlpha);
            }

            planets.batch.flush(Gl.triangles);
            return;
        }

        if(state.uiAlpha > 0.01f){
            for(Sector sec : planet.sectors){
                if(sec == selected) continue;

                if(canSelect(sec) || sec.unlocked() || debugSelect){

                    Color color =
                    sec.hasBase() ? Tmp.c2.set(Team.sharded.color).lerp(Team.crux.color, sec.hasEnemyBase() ? 0.5f : 0f) :
                    sec.preset != null && sec.preset.requireUnlock ?
                        sec.preset.unlocked() ? Tmp.c2.set(Team.derelict.color).lerp(Color.white, Mathf.absin(Time.time, 10f, 1f)) :
                        Color.gray :
                    sec.hasEnemyBase() ? Team.crux.color :
                    null;

                    if(color != null){
                        var destColor = Tmp.c1.set(color).mul(0.8f).a(state.uiAlpha);
                        if(!sec.isCaptured() && sec.preset != null && sec.preset.showHidden){
                            planets.drawSpecialSelection(sec, destColor, 0.026f, -0.001f);
                        }else{
                            planets.drawSelection(sec, destColor, 0.026f, -0.001f);
                        }
                    }
                }else{
                    planets.fill(sec, Tmp.c1.set(shadowColor).mul(1, 1, 1, state.uiAlpha), -0.001f);
                }
            }
        }

        Sector current = Vars.state.getSector() != null && Vars.state.getSector().isBeingPlayed() && Vars.state.getSector().planet == state.planet ? Vars.state.getSector() : null;

        if(current != null){
            planets.fill(current, hoverColor.write(Tmp.c1).mulA(state.uiAlpha), -0.001f);
        }

        //draw hover border
        if(hovered != null){
            planets.fill(hovered, hoverColor.write(Tmp.c1).mulA(state.uiAlpha), -0.003f);
            planets.drawBorders(hovered, borderColor, state.uiAlpha);
        }

        //draw selected borders
        if(selected != null){
            planets.drawSelection(selected, state.uiAlpha);
            planets.drawBorders(selected, borderColor, state.uiAlpha);
        }

        //V2: Unified bright yellow outline for every sector currently occupied by at least one player
        if(state.uiAlpha > 0.01f && net.active()){
            for(Sector sec : planet.sectors){
                if(MultiSectorCoordinator.get().isSectorOccupied(sec)){
                    planets.drawSelection(sec, Tmp.c1.set(Pal.accent).a(state.uiAlpha), 0.042f, 0.002f);
                }
            }
        }

        //V3.9: Troop launch target selection highlights
        if(mode == troopLaunchSelect && launchSector != null && launchSector.planet == planet && state.uiAlpha > 0.01f){
            planets.drawSelection(launchSector, Tmp.c1.set(Pal.accent).a(state.uiAlpha), 0.045f, 0.0028f);
            for(Sector sec : planet.sectors){
                if(sec == launchSector) continue;
                if(sec.hasBase()){
                    boolean isLockedDest = activeTroopPad != null && activeTroopPad.targetSectorId == sec.id;
                    Color col = isLockedDest ? Tmp.c1.set(Pal.lancerLaser).a(state.uiAlpha) : Tmp.c1.set(Pal.heal).a(state.uiAlpha * 0.72f);
                    planets.drawSelection(sec, col, isLockedDest ? 0.045f : 0.032f, 0.002f);
                }else if(troopAdjacentIds.contains(sec.id) && activeTroopPad != null && activeTroopPad.padTier().allowBeachhead){
                    Color bCol = Tmp.c1.set(Pal.remove).a(state.uiAlpha * (0.55f + Mathf.absin(Time.time, 5f, 0.35f)));
                    planets.drawSelection(sec, bCol, 0.038f, 0.0024f);
                }else{
                    planets.fill(sec, Tmp.c1.set(0.06f, 0.06f, 0.08f, 0.50f * state.uiAlpha), 0.001f);
                }
            }
            if(hovered != null && canSelect(hovered)){
                planets.drawSelection(hovered, Tmp.c1.set(Color.white).a(state.uiAlpha), 0.046f, 0.003f);
            }
        }

        //V2.5: Power View highlights for selected hub origin and linkable adjacent hub sectors
        if(powerViewMode && mode == look && state.uiAlpha > 0.01f){
            SectorPowerGridManager pm = SectorPowerGridManager.get();
            if(powerLinkSource != null && powerLinkSource.planet == planet){
                planets.drawSelection(powerLinkSource, Tmp.c1.set(Pal.powerLight).a(state.uiAlpha), 0.048f, 0.003f);
                for(Sector neighbor : powerLinkSource.near()){
                    if(pm.hasHub(neighbor)){
                        boolean linked = pm.areLinked(powerLinkSource, neighbor);
                        Color col = linked
                            ? Tmp.c1.set(Pal.heal).a(state.uiAlpha * 0.9f)
                            : Tmp.c1.set(Pal.accent).a(state.uiAlpha * (0.55f + Mathf.absin(Time.time, 6f, 0.45f)));
                        planets.drawSelection(neighbor, col, 0.038f, 0.0025f);
                    }
                }
            }
        }

        planets.batch.flush(Gl.triangles);
    }

    @Override
    public void renderOverProjections(Planet planet){
        if(mode == troopLaunchSelect && launchSector != null && launchSector.planet == planet){
            if(state.uiAlpha > 0.001f){
                if(activeTroopPad != null && activeTroopPad.hasLockedRoute()){
                    drawTroopRouteArcs(planet, activeTroopPad.lockedRoute, activeTroopPad.launchMode, activeTroopPad.routeMode);
                }
                if(hovered != null && canSelect(hovered)){
                    planets.drawArc(
                        planet, launchSector.tile.v, hovered.tile.v,
                        Tmp.c2.set(hovered.hasBase() ? Pal.heal : Pal.remove).a(state.uiAlpha),
                        Tmp.c3.set(Pal.accent).a(state.uiAlpha),
                        0.32f, 55f, 22
                    );
                }
            }
            return;
        }

        if(mode == conveyorSelect && launchSector != null && launchSector.planet == planet){
            if(state.uiAlpha > 0.001f){
                SectorConveyorLink currentLink = SectorConveyorManager.get().getLinkForPort(launchSector, true, conveyorSelectSenderPort);
                if(currentLink != null && currentLink.receiverSectorId >= 0 && currentLink.receiverSectorId < planet.sectors.size){
                    Sector pairedSec = planet.sectors.get(currentLink.receiverSectorId);
                    planets.drawArc(
                        planet, launchSector.tile.v, pairedSec.tile.v,
                        Tmp.c2.set(Pal.heal).a(state.uiAlpha),
                        Tmp.c3.set(Pal.lancerLaser).a(state.uiAlpha),
                        0.30f, 70f, 24
                    );
                }
                if(hovered != null && canSelect(hovered)){
                    planets.drawArc(
                        planet, launchSector.tile.v, hovered.tile.v,
                        Tmp.c2.set(Pal.accent).a(state.uiAlpha),
                        Tmp.c3.set(Pal.lancerLaser).a(state.uiAlpha),
                        0.32f, 55f, 22
                    );
                }
            }
            return;
        }

        Sector hoverOrSelect = hovered != null ? hovered : selected;
        Sector launchFrom = hoverOrSelect != null && !hoverOrSelect.hasBase() ? findLauncher(hoverOrSelect) : null;

        if(hoverOrSelect != null && !hoverOrSelect.hasBase() && launchFrom != null && hoverOrSelect != launchFrom && canSelect(hoverOrSelect)){
            planets.drawArcLine(planet, launchFrom.tile.v, hoverOrSelect.tile.v);
        }

        if(mode == planetLaunch && launchSector != null && selected != null && hovered == null){
            planets.drawArcLine(planet, launchSector.tile.v, selected.tile.v);
        }

        if(state.uiAlpha > 0.001f){
            for(Sector sec : planet.sectors){

                //draw shield arc
                if(sec.shieldTarget != null && !sec.isCaptured() && !sec.shieldTarget.isCaptured() && (planet.generator.allowLanding(sec) || planet.generator.allowLanding(sec.shieldTarget))){
                    planets.drawArcLine(planet, sec.tile.v, sec.shieldTarget.tile.v, Team.crux.color.write(Tmp.c2).a(state.uiAlpha), Tmp.c3.set(Tmp.c2).mulA(0.5f), 0.3f, 110f, 25, 0.006f);
                }

                if(sec.hasBase()){
                    //draw vulnerable sector attack arc
                    if(planet.campaignRules.sectorInvasion){
                        for(Sector enemy : sec.near()){
                            if(enemy.hasEnemyBase() && (enemy.preset == null || !enemy.preset.requireUnlock)){
                                planets.drawArcLine(planet, enemy.tile.v, sec.tile.v, Team.crux.color.write(Tmp.c2).a(state.uiAlpha), Color.clear, 0.24f, 110f, 25, 0.005f);
                            }
                        }
                    }

                    if(!powerViewMode && selected != null && selected != sec && selected.hasBase()){
                        //imports
                        if(sec.info.destination == selected && sec.info.anyExports()){
                            planets.drawArc(planet, sec.tile.v, selected.tile.v, Color.gray.write(Tmp.c2).a(state.uiAlpha), Pal.accent.write(Tmp.c3).a(state.uiAlpha), 0.4f, 90f, 25);
                        }
                        //exports
                        if(selected.info.destination == sec && selected.info.anyExports()){
                            planets.drawArc(planet, selected.tile.v, sec.tile.v, Pal.place.write(Tmp.c2).a(state.uiAlpha), Pal.accent.write(Tmp.c3).a(state.uiAlpha), 0.4f, 90f, 25);
                        }
                    }
                }
            }

            //V2.5: Render cross-sector power grid connection lines in Power View (with hemisphere backface culling)
            if(powerViewMode && mode == look){
                SectorPowerGridManager pm = SectorPowerGridManager.get();
                PlanetPowerTopology topo = pm.getTopology(planet);
                computeLocalCameraDir(planet, tmpCamDir);
                java.util.Collection<SectorLink> powerLinks = topo.getAllLinks();
                int arcPoints = (powerLinks.size() > 120 || state.zoom > 1.6f) ? 14 : 22;

                for(SectorLink link : powerLinks){
                    if(link.a < 0 || link.a >= planet.sectors.size || link.b < 0 || link.b >= planet.sectors.size) continue;
                    Sector sa = planet.sectors.get(link.a);
                    Sector sb = planet.sectors.get(link.b);
                    if(sa.tile.v.dot(tmpCamDir) < -0.30f && sb.tile.v.dot(tmpCamDir) < -0.30f) continue;
                    if(!pm.hasHub(sa) || !pm.hasHub(sb)) continue;

                    GridAggregate agg = topo.computeAggregate(sa.id);
                    Color cFrom = agg.overloaded
                        ? Tmp.c2.set(Pal.remove).a(state.uiAlpha)
                        : Tmp.c2.set(Pal.powerLight).a(state.uiAlpha);
                    Color cTo = agg.overloaded
                        ? Tmp.c3.set(Pal.accent).a(state.uiAlpha * 0.85f)
                        : Tmp.c3.set(Pal.accent).a(state.uiAlpha * 0.95f);

                    planets.drawArcLine(planet, sa.tile.v, sb.tile.v, cFrom, cTo, 0.22f, 65f, arcPoints, 0.008f);
                }

                // Live hover preview arc when linking from powerLinkSource to an adjacent hub sector
                if(powerLinkSource != null && hovered != null && hovered != powerLinkSource && powerLinkSource.planet == planet && powerLinkSource.near().contains(hovered) && pm.hasHub(hovered)){
                    boolean willUnlink = pm.areLinked(powerLinkSource, hovered);
                    Color previewCol = willUnlink
                        ? Tmp.c2.set(Pal.remove).a(state.uiAlpha)
                        : Tmp.c2.set(Pal.heal).a(state.uiAlpha);
                    planets.drawArcLine(planet, powerLinkSource.tile.v, hovered.tile.v, previewCol, Tmp.c3.set(Pal.powerLight).a(state.uiAlpha), 0.28f, 45f, 20, 0.009f);
                }
            }

            //V2.8: Render directional cross-sector conveyor flow arcs in Resource View ("资源视野")
            if(resourceViewMode && mode == look){
                SectorConveyorManager cm = SectorConveyorManager.get();
                PlanetConveyorTopology ctopo = cm.getTopology(planet);
                computeLocalCameraDir(planet, tmpCamDir);
                java.util.Collection<SectorConveyorLink> conveyorLinks = ctopo.getAllLinks();
                int arcPoints = (conveyorLinks.size() > 120 || state.zoom > 1.6f) ? 14 : 24;

                for(SectorConveyorLink link : conveyorLinks){
                    if(link.senderSectorId < 0 || link.senderSectorId >= planet.sectors.size
                        || link.receiverSectorId < 0 || link.receiverSectorId >= planet.sectors.size){
                        continue;
                    }
                    Sector sa = planet.sectors.get(link.senderSectorId);
                    Sector sb = planet.sectors.get(link.receiverSectorId);
                    if(sa.tile.v.dot(tmpCamDir) < -0.30f && sb.tile.v.dot(tmpCamDir) < -0.30f) continue;
                    if(!sa.hasBase() || !sb.hasBase()) continue;

                    SectorPortRecord sp = ctopo.getPort(link.senderSectorId, true, link.senderPortNumber);
                    boolean paused = sp != null && sp.backpressurePaused;
                    boolean flowing = sp != null && sp.recentTotalRate > 0.01f;
                    Color cFrom = paused
                        ? Tmp.c2.set(Pal.remove).a(state.uiAlpha)
                        : (flowing ? Tmp.c2.set(Pal.heal).a(state.uiAlpha) : Tmp.c2.set(Pal.techBlue).a(state.uiAlpha * 0.85f));
                    Color cTo = paused
                        ? Tmp.c3.set(Pal.accent).a(state.uiAlpha * 0.8f)
                        : Tmp.c3.set(Pal.lancerLaser).a(state.uiAlpha);
                    planets.drawArc(planet, sa.tile.v, sb.tile.v, cFrom, cTo, 0.30f, 72f, arcPoints);
                }
            }

            //V3.9: Render active in-flight cross-sector troop airlift routes in Troop Airlift View ("调兵视野")
            if(troopViewMode && mode == look){
                java.util.List<TroopAirliftMission> missions = SectorTroopAirliftManager.get().getActiveMissions(planet);
                for(int i = 0; i < missions.size(); i++){
                    TroopAirliftMission m = missions.get(i);
                    drawTroopRouteArcs(planet, m.lockedRoute, m.launchMode, m.routeMode);
                }
            }
        }
    }

    /** Draws multi-hop arcs along a locked troop airlift route. */
    private void drawTroopRouteArcs(Planet planet, int[] route, LaunchMode lMode, RouteMode rMode){
        if(route == null || route.length < 2) return;
        Color cFrom = lMode == LaunchMode.BEACHHEAD
            ? Tmp.c2.set(Pal.remove).a(state.uiAlpha)
            : (rMode == RouteMode.SAFE ? Tmp.c2.set(Pal.heal).a(state.uiAlpha) : Tmp.c2.set(Pal.accent).a(state.uiAlpha));
        Color cTo = lMode == LaunchMode.BEACHHEAD
            ? Tmp.c3.set(Pal.accent).a(state.uiAlpha)
            : Tmp.c3.set(Pal.lancerLaser).a(state.uiAlpha);
        for(int i = 0; i < route.length - 1; i++){
            int a = route[i], b = route[i + 1];
            if(a < 0 || a >= planet.sectors.size || b < 0 || b >= planet.sectors.size) continue;
            Sector sa = planet.sectors.get(a);
            Sector sb = planet.sectors.get(b);
            planets.drawArc(planet, sa.tile.v, sb.tile.v, cFrom, cTo, 0.28f, 65f, 18);
        }
    }

    /** Transforms the camera-to-planet unit vector into planet-local coordinates for O(1) dot-product culling. */
    private void computeLocalCameraDir(Planet planet, Vec3 out){
        out.set(planets.cam.position).sub(planet.position).nor().rotate(Vec3.Y, planet.getRotation());
    }

    @Override
    public void renderProjections(Planet planet){
        float iw = 64f/4f;
        boolean drawPowerDots = powerViewMode && mode == look && state.uiAlpha > 0.01f;
        boolean drawResourceDots = resourceViewMode && mode == look && state.uiAlpha > 0.01f;
        boolean drawTroopDots = troopViewMode && mode == look && state.uiAlpha > 0.01f;
        if(drawPowerDots || drawResourceDots || drawTroopDots){
            computeLocalCameraDir(planet, tmpCamDir);
        }

        for(Sector sec : planet.sectors){
            if(sec != hovered){
                var preficon = sec.icon();
                var icon =
                    sec.isAttacked() ? Fonts.getLargeIcon("warning") :
                    !sec.hasBase() && sec.preset != null && ((sec.preset.requireUnlock && sec.preset.unlocked()) || (sec.preset.showHidden && mode == planetLaunch)) && preficon == null ?
                    (sec.preset != null && ((sec.preset.requireUnlock && sec.preset.unlocked()) || sec.preset.showHidden) ? sec.preset.uiIcon : Fonts.getLargeIcon("terrain")) :
                    sec.preset != null && sec.preset.requireUnlock && sec.preset.locked() && sec.preset.techNode != null && (sec.preset.techNode.parent == null || !sec.preset.techNode.parent.content.locked()) ? Fonts.getLargeIcon("lock") :
                    preficon;
                var color = sec.isAttacked() ? Team.sharded.color : Color.white;

                if(icon != null){
                    planets.drawPlane(sec, () -> {
                        //use white for content icons
                        Draw.color(preficon == icon && sec.info.contentIcon != null ? Color.white : color, state.uiAlpha);
                        Draw.rect(icon, 0, 0, iw, iw * icon.height / icon.width);
                    });
                }
            }

            if(debugShowNumbers && (sec.hasEnemyBase() || sec.preset != null)){
                planets.drawPlane(sec, () -> {
                    Fonts.outline.getData().setScale(0.5f);
                    Fonts.outline.setColor(sec.preset == null ? Color.scarlet : Color.white);

                    Fonts.outline.draw(sec.id + "", 0f, 0f, Align.center);
                    Fonts.outline.setColor(Color.white);
                    Fonts.outline.getData().setScale(1f);
                });
            }

            //V2: Floating centered player count badge for every occupied sector
            if(net.active() && state.uiAlpha > 0.01f){
                int playerCount = MultiSectorCoordinator.get().getSectorPlayerCount(sec);
                if(playerCount > 0){
                    planets.drawPlane(sec, () -> {
                        Fonts.outline.getData().setScale(0.75f);
                        Fonts.outline.setColor(Tmp.c1.set(Pal.accent).a(state.uiAlpha));
                        Fonts.outline.draw(String.valueOf(playerCount), 0f, Fonts.outline.getCapHeight() / 2f, Align.center);
                        Fonts.outline.setColor(Color.white);
                        Fonts.outline.getData().setScale(1f);
                    });
                }
            }

            //V2.5: Glowing yellow center dot for every visible sector containing a Surge Sector Power Hub
            if(drawPowerDots && (sec == powerLinkSource || sec.tile.v.dot(tmpCamDir) >= -0.25f) && SectorPowerGridManager.get().hasHub(sec)){
                boolean isSource = sec == powerLinkSource;
                GridAggregate agg = SectorPowerGridManager.get().getGridAggregate(sec);
                planets.drawPlane(sec, () -> {
                    float pulse = Mathf.absin(Time.time, 6f, 1.4f);
                    Color coreCol = agg.overloaded ? Pal.remove : Pal.powerLight;
                    Draw.color(Pal.accent, state.uiAlpha * 0.35f);
                    Fill.circle(0f, 0f, (isSource ? 10.5f : 8.0f) + pulse);
                    Draw.color(coreCol, state.uiAlpha);
                    Fill.circle(0f, 0f, isSource ? 6.2f : 4.8f);
                    if(isSource){
                        Lines.stroke(1.8f);
                        Draw.color(Pal.powerLight, state.uiAlpha);
                        Lines.circle(0f, 0f, 11.5f + pulse * 1.4f);
                    }
                    Draw.reset();
                });
            }

            //V2.8: Glowing cyan node dot for visible sectors with cross-sector conveyor ports
            if(drawResourceDots && (sec == selected || sec.tile.v.dot(tmpCamDir) >= -0.25f) && SectorConveyorManager.get().hasAnyPort(sec)){
                boolean isSel = sec == selected;
                planets.drawPlane(sec, () -> {
                    float pulse = Mathf.absin(Time.time, 6f, 1.3f);
                    Draw.color(Pal.techBlue, state.uiAlpha * 0.35f);
                    Fill.circle(0f, 0f, (isSel ? 10.0f : 7.8f) + pulse);
                    Draw.color(Pal.lancerLaser, state.uiAlpha);
                    Fill.circle(0f, 0f, isSel ? 5.8f : 4.6f);
                    if(isSel){
                        Lines.stroke(1.8f);
                        Draw.color(Pal.heal, state.uiAlpha);
                        Lines.circle(0f, 0f, 11.2f + pulse * 1.3f);
                    }
                    Draw.reset();
                });
            }

            //V3.9: In Troop Airlift View, draw garrison / beachhead status markers (with reserved custom icon slots)
            if(drawTroopDots && (sec == selected || sec.tile.v.dot(tmpCamDir) >= -0.25f)){
                SectorTroopAirliftManager tm = SectorTroopAirliftManager.get();
                boolean beach = tm.hasFriendlyBeachhead(sec);
                if(sec.hasBase() || beach){
                    SectorGarrisonSnapshot gar = tm.getSectorGarrison(sec);
                    boolean isSel = sec == selected;
                    final boolean isBeachhead = beach;
                    final int usedCp = gar.usedCp;
                    final int capCp = gar.commandCap;
                    planets.drawPlane(sec, () -> {
                        float pulse = Mathf.absin(Time.time, 5.5f, 1.3f);
                        String customKey = isBeachhead ? TroopAirliftAssets.UI_AIRLIFT_BEACHHEAD_MARKER : TroopAirliftAssets.UI_AIRLIFT_TARGET_MARKER;
                        if(TroopAirliftAssets.hasSprite(customKey)){
                            Draw.color(Color.white, state.uiAlpha);
                            Draw.rect(Core.atlas.find(customKey), 0f, 0f, isSel ? 18f : 14f, isSel ? 18f : 14f);
                        }else{
                            Color mainCol = isBeachhead ? Pal.remove : (usedCp > 0 ? Color.valueOf("84f491") : Pal.accent);
                            Draw.color(mainCol, state.uiAlpha * 0.32f);
                            Fill.circle(0f, 0f, (isSel ? 10.2f : 7.8f) + pulse);
                            Draw.color(mainCol, state.uiAlpha);
                            Fill.circle(0f, 0f, isSel ? 5.8f : 4.5f);
                        }
                        Fonts.outline.getData().setScale(0.48f);
                        Fonts.outline.setColor(Tmp.c1.set(isBeachhead ? Color.scarlet : Color.white).a(state.uiAlpha));
                        Fonts.outline.draw(usedCp + "/" + capCp, 0f, -9f, Align.center);
                        Fonts.outline.setColor(Color.white);
                        Fonts.outline.getData().setScale(1f);
                        Draw.reset();
                    });
                }
            }

            //V3.3: In conveyorSelect mode, mark adjacent sectors that have receiver ports (green if free, gray if all paired)
            if(mode == conveyorSelect && launchSector != null && launchSector.planet == planet && state.uiAlpha > 0.01f
                && conveyorAdjacentIds.contains(sec.id) && sec.hasBase()){
                PlanetConveyorTopology ctopo = SectorConveyorManager.get().getTopology(planet);
                java.util.List<SectorPortRecord> recvs = ctopo.getSectorPorts(sec.id, false);
                if(!recvs.isEmpty()){
                    boolean hasFree = false;
                    for(int i = 0; i < recvs.size(); i++){
                        if(!ctopo.isPortLinked(sec.id, false, recvs.get(i).portNumber)){
                            hasFree = true;
                            break;
                        }
                    }
                    final boolean freeAvailable = hasFree;
                    planets.drawPlane(sec, () -> {
                        float pulse = Mathf.absin(Time.time, 6f, 1.2f);
                        Draw.color(freeAvailable ? Pal.heal : Pal.gray, state.uiAlpha * 0.35f);
                        Fill.circle(0f, 0f, 8.2f + pulse);
                        Draw.color(freeAvailable ? Pal.lancerLaser : Pal.gray, state.uiAlpha);
                        Fill.circle(0f, 0f, 4.8f);
                        Draw.reset();
                    });
                }
            }
        }

        //V3.9: Draw moving pod markers for in-flight airlift missions in Troop Airlift View
        if(drawTroopDots){
            java.util.List<TroopAirliftMission> missions = SectorTroopAirliftManager.get().getActiveMissions(planet);
            TextureRegion customPodMarker = TroopAirliftAssets.findOrFallback(TroopAirliftAssets.UI_AIRLIFT_POD_MARKER, null);
            for(int i = 0; i < missions.size(); i++){
                TroopAirliftMission m = missions.get(i);
                if(m.lockedRoute.length < 2) continue;
                int totalHops = m.lockedRoute.length - 1;
                float scaled = m.progress() * totalHops;
                int seg = Math.min(totalHops - 1, Math.max(0, (int)Math.floor(scaled)));
                int fromId = m.lockedRoute[seg];
                int toId = m.lockedRoute[seg + 1];
                if(fromId < 0 || fromId >= planet.sectors.size || toId < 0 || toId >= planet.sectors.size) continue;
                Sector sFrom = planet.sectors.get(fromId);
                Sector sTo = planet.sectors.get(toId);
                Sector anchorSec = (scaled - seg) < 0.5f ? sFrom : sTo;
                if(anchorSec.tile.v.dot(tmpCamDir) < -0.25f) continue;
                final int remSecs = Math.max(0, (int)Math.ceil(m.remainingSeconds()));
                final Color col = m.launchMode == LaunchMode.BEACHHEAD ? Pal.remove : Pal.accent;
                planets.drawPlane(anchorSec, () -> {
                    if(customPodMarker != null){
                        Draw.color(Color.white, state.uiAlpha);
                        Draw.rect(customPodMarker, 0f, 10f, 16f, 16f);
                    }else{
                        Draw.color(col, state.uiAlpha);
                        Fill.poly(0f, 10f, 3, 5.5f, 90f);
                    }
                    Fonts.outline.getData().setScale(0.45f);
                    Fonts.outline.setColor(Tmp.c1.set(col).a(state.uiAlpha));
                    Fonts.outline.draw(remSecs + "s", 0f, 20f, Align.center);
                    Fonts.outline.setColor(Color.white);
                    Fonts.outline.getData().setScale(1f);
                    Draw.reset();
                });
            }
        }

        Draw.reset();

        if(hovered != null && state.uiAlpha > 0.01f && ((mode != conveyorSelect && mode != troopLaunchSelect) || canSelect(hovered))){
            planets.drawPlane(hovered, () -> {
                Draw.color(hovered.isAttacked() ? Pal.remove : Color.white, Pal.accent, Mathf.absin(5f, 1f));
                Draw.alpha(state.uiAlpha);

                var icon =
                    hovered.locked() && !canSelect(hovered) && hovered.planet.generator != null ? hovered.planet.generator.getLockedIcon(hovered) :
                    hovered.isAttacked() ? Fonts.getLargeIcon("warning") :
                    hovered.icon();

                if(icon != null){
                    Draw.rect(icon, 0, 0, iw, iw * icon.height / icon.width);
                }

                Draw.reset();
            });
        }

        Draw.reset();
    }

    boolean selectable(Planet planet){
        if(planet.removed) return false;
        if(mode == select || mode == conveyorSelect || mode == troopLaunchSelect) return planet == state.planet;
        if(mode == planetLaunch) return launchSector != null && (launchCandidates.contains(planet) || (planet == launchSector.planet && planet.allowSelfSectorLaunch));
        return (planet.alwaysUnlocked && planet.isLandable()) || planet.sectors.contains(Sector::hasBase) || debugSelect;
    }

    void setup(){
        searchText = "";
        zoom = state.zoom = 1f;
        state.uiAlpha = 1f;
        ui.minimapfrag.hide();

        clearChildren();

        margin(0f);

        stack(
        mainView = new Element(){
            {
                //add listener to the background rect, so it doesn't get unnecessary touch input
                addListener(new ElementGestureListener(){
                    @Override
                    public void tap(InputEvent event, float x, float y, int count, KeyCode button){
                        if(showing() || button != KeyCode.mouseLeft) return;

                        //V3.3: LaunchPad-style adjacent-sector conveyor pairing selection
                        if(mode == conveyorSelect){
                            if(hovered != null && canSelect(hovered)){
                                showConveyorTargetReceivers(hovered);
                            }
                            return;
                        }

                        //V3.9: Troop airlift destination & route confirmation
                        if(mode == troopLaunchSelect){
                            if(hovered != null && canSelect(hovered)){
                                showTroopLaunchConfirmDialog(hovered);
                            }
                            return;
                        }

                        //V3.0: Power View direct two-node click to connect / disconnect & click-to-inspect
                        if(powerViewMode && mode == look){
                            SectorPowerGridManager pm = SectorPowerGridManager.get();
                            if(hovered != null && pm.hasHub(hovered)){
                                if(powerLinkSource == null){
                                    powerLinkSource = hovered;
                                    selected = hovered;
                                    updateSelected();
                                    return;
                                }else if(powerLinkSource == hovered){
                                    powerLinkSource = null;
                                    selected = hovered;
                                    updateSelected();
                                    return;
                                }else if(powerLinkSource.planet == hovered.planet && powerLinkSource.near().contains(hovered)){
                                    int res = pm.requestToggleLink(state.planet, powerLinkSource, hovered);
                                    if(res == 2){
                                        ui.showInfoFade("已向房主请求变更电网连线");
                                    }
                                    selected = hovered;
                                    powerLinkSource = null;
                                    updateSelected();
                                    return;
                                }else{
                                    // Clicked another non-adjacent hub sector: switch link origin
                                    powerLinkSource = hovered;
                                    selected = hovered;
                                    updateSelected();
                                    return;
                                }
                            }else{
                                powerLinkSource = null;
                                selected = (hovered != null && (canSelect(hovered) || debugSelect)) ? hovered : null;
                                updateSelected();
                                return;
                            }
                        }

                        //Resource View / Troop Airlift View click-to-inspect only (prevents accidental double-tap sector entry)
                        if((resourceViewMode || troopViewMode) && mode == look){
                            selected = hovered;
                            updateSelected();
                            return;
                        }

                        if(hovered != null && selected == hovered && count == 2 && canPlaySector(hovered)){
                            playSelected();
                        }

                        if(hovered != null && (canSelect(hovered) || debugSelect)){
                            selected = hovered;
                        }

                        if(selected != null){
                            updateSelected();
                        }
                    }

                    @Override
                    public void touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        super.touchDown(event, x, y, pointer, button);

                        var hovered = PlanetDialog.this.hovered;

                        if(debugSectorAttackEdit && hovered != null){
                            if(button == KeyCode.mouseRight){
                                if(input.shift()){
                                    hovered.generateEnemyBase = !hovered.generateEnemyBase;
                                }else{
                                    selectDialog.show(state.planet, result -> {
                                        for(var other : state.planet.sectors){
                                            if(other.preset == result){
                                                other.preset = null;
                                            }
                                        }
                                        hovered.preset = result;
                                    });
                                }
                            }
                        }
                    }
                });
            }

            @Override
            public void act(float delta){
                if(scene.getDialog() == PlanetDialog.this && (scene.getHoverElement() == null || !scene.getHoverElement().isDescendantOf(e -> e instanceof ScrollPane))){
                    scene.setScrollFocus(PlanetDialog.this);

                    if(debugSectorAttackEdit){
                        int timeShift = input.keyDown(KeyCode.rightBracket) ? 1 : input.keyDown(KeyCode.leftBracket) ? -1 : 0;
                        if(timeShift != 0){
                            universe.setSeconds(universe.secondsf() + timeShift * Time.delta * 2.5f);
                        }

                        if(input.keyTap(KeyCode.r)){
                            state.planet.reloadMeshAsync();
                        }
                    }

                    if(debugSectorAttackEdit && input.ctrl() && input.keyTap(KeyCode.s)){
                        try{
                            PlanetData data = new PlanetData();
                            IntSeq attack = new IntSeq();
                            for(var sector : state.planet.sectors){
                                if((sector.preset == null || !sector.preset.requireUnlock) && sector.generateEnemyBase){
                                    attack.add(sector.id);
                                }

                                if(sector.preset != null && sector.preset.requireUnlock){
                                    data.presets.put(sector.preset.name, sector.id);
                                }
                            }
                            Log.info("Saving sectors for @: @ presets, @ procedural attack sectors", state.planet.name, data.presets.size, attack.size);
                            data.attackSectors = attack.toArray();
                            files.local("planets/" + state.planet.name + ".json").writeString(JsonIO.write(data));

                            ui.showInfoFade("@editor.saved");
                        }catch(Exception e){
                            Log.err(e);
                        }
                    }
                }

                if(powerViewMode){
                    int rev = SectorPowerGridManager.get().getUiRevision();
                    if(rev != lastPowerGridRevision){
                        lastPowerGridRevision = rev;
                        if(selected != null){
                            updateSelected();
                        }
                    }
                }
                if(resourceViewMode){
                    int rev = SectorConveyorManager.get().getUiRevision();
                    if(rev != lastConveyorRevision){
                        lastConveyorRevision = rev;
                        if(selected != null){
                            updateSelected();
                        }
                    }
                }
                if(troopViewMode){
                    int rev = SectorTroopAirliftManager.get().getUiRevision();
                    if(rev != lastTroopRevision){
                        lastTroopRevision = rev;
                        if(selected != null){
                            updateSelected();
                        }
                    }
                }

                super.act(delta);
            }

            @Override
            public void draw(){
                planets.render(state);
            }
        },
        //info text
        new Table(t -> {
            t.touchable = Touchable.disabled;
            t.top();
            t.label(() -> mode == select ? "@sectors.select" :
                mode == planetLaunch ? "@sectors.launchselect" :
                mode == conveyorSelect ? "选择相邻区块配对 · 起始端" + conveyorSelectSenderPort :
                mode == troopLaunchSelect ? ("选择跨区块调兵目标 · " + (activeTroopPad != null ? activeTroopPad.padTier().displayName : "")) : "").style(Styles.outlineLabel).color(Pal.accent);
        }),
        buttons,

        // planet selection
        new Table(t -> {
            t.top().left();
            ScrollPane pane = new ScrollPane(null, Styles.smallPane);
            t.add(pane).colspan(2).row();
            t.button("@campaign.difficulty", Icon.bookSmall, () -> {
                Vars.ui.campaignRules.show(state.planet);
            }).margin(12f).size(208f, 40f).padTop(12f).visible(() -> state.planet.allowCampaignRules && mode != planetLaunch && !net.isConnectedClient()).row();
            t.button("电力视野", Icon.powerSmall, Styles.flatTogglet, () -> {
                if(!powerViewMode && !isPowerViewUnlocked()){
                    ui.showInfoFade("需先解锁「巨浪区块电力枢纽」");
                    return;
                }
                powerViewMode = !powerViewMode;
                powerLinkSource = null;
                selected = null;
                if(powerViewMode){
                    resourceViewMode = false;
                    troopViewMode = false;
                    SectorPowerGridManager.get().syncCurrentSectorHubStatus();
                    lastPowerGridRevision = SectorPowerGridManager.get().getUiRevision();
                }
                rebuildButtons();
                updateSelected();
            }).margin(12f).size(208f, 40f).padTop(6f).checked(b -> powerViewMode).visible(() -> mode == look).row();
            t.button("资源视野", Icon.boxSmall, Styles.flatTogglet, () -> {
                if(!resourceViewMode && !isResourceViewUnlocked()){
                    ui.showInfoFade("需先解锁「区块间传送带」");
                    return;
                }
                resourceViewMode = !resourceViewMode;
                selected = null;
                if(resourceViewMode){
                    powerViewMode = false;
                    troopViewMode = false;
                    powerLinkSource = null;
                    SectorConveyorManager.get().syncCurrentSectorPorts();
                    lastConveyorRevision = SectorConveyorManager.get().getUiRevision();
                }
                rebuildButtons();
                updateSelected();
            }).margin(12f).size(208f, 40f).padTop(4f).checked(b -> resourceViewMode).visible(() -> mode == look).row();
            Drawable troopViewIcon = TroopAirliftAssets.drawableOrFallback(TroopAirliftAssets.UI_TROOP_VIEW_ICON, Icon.commandRallySmall);
            t.button("调兵视野", troopViewIcon, Styles.flatTogglet, () -> {
                if(!troopViewMode && !isTroopViewUnlocked()){
                    ui.showInfoFade("需先在科技树解锁任一「兵员发射台」");
                    return;
                }
                troopViewMode = !troopViewMode;
                selected = null;
                if(troopViewMode){
                    powerViewMode = false;
                    resourceViewMode = false;
                    powerLinkSource = null;
                    if(Vars.state != null && Vars.state.isCampaign() && Vars.state.hasSector()){
                        SectorTroopAirliftManager.get().captureLiveSectorSnapshot(Vars.state.getSector());
                    }
                    lastTroopRevision = SectorTroopAirliftManager.get().getUiRevision();
                }
                rebuildButtons();
                updateSelected();
            }).margin(12f).size(208f, 40f).padTop(4f).checked(b -> troopViewMode).visible(() -> mode == look).row();
            t.add().height(64f); //padding for close button
            Table starsTable = new Table(Styles.black);
            pane.setWidget(starsTable);
            pane.setScrollingDisabled(true, false);

            int starCount = 0;
            for(Planet star : content.planets()){
                if(star.solarSystem != star || !content.planets().contains(p -> p.solarSystem == star && selectable(p))) continue;

                starCount++;
                if(starCount > 1) starsTable.add(star.localizedName).padLeft(10f).padBottom(10f).padTop(10f).left().width(190f).row();
                Table planetTable = new Table();
                planetTable.margin(4f); //less padding
                starsTable.add(planetTable).left().row();
                for(Planet planet : content.planets()){
                    if(planet.solarSystem == star && selectable(planet)){
                        Button planetButton = planetTable.button(planet.localizedName, Icon.icons.get(planet.icon + "Small", Icon.icons.get(planet.icon, Icon.commandRallySmall)), Styles.flatTogglet, () -> {
                            viewPlanet(planet, false);
                        }).width(200).height(40).update(bb -> bb.setChecked(state.planet == planet)).with(w -> w.marginLeft(10f)).get();
                        planetButton.getChildren().get(1).setColor(planet.iconColor);
                        planetButton.setColor(planet.iconColor);
                        planetTable.background(Tex.pane).row();
                    }
                }
            }
        }).visible(() -> mode != select && mode != conveyorSelect && mode != troopLaunchSelect),

        new Table(c -> expandTable = c)).grow();
        rebuildExpand();
    }

    void rebuildExpand(){
        Table c = expandTable;
        c.clear();
        c.visible(() -> !(graphics.isPortrait() && mobile) && mode != planetLaunch && mode != conveyorSelect && mode != troopLaunchSelect);
        if(state.planet.sectors.contains(Sector::hasBase)){
            int attacked = state.planet.sectors.count(Sector::isAttacked);

            //sector notifications & search
            c.top().right();
            c.defaults().width(290f);

            c.button(bundle.get("sectorlist") +
            (attacked == 0 ? "" : "\n[red]⚠[lightgray] " + bundle.format("sectorlist.attacked", "[red]" + attacked + "[]")),
            Icon.downOpen, Styles.squareTogglet, () -> sectorsShown = !sectorsShown)
            .height(60f).checked(b -> {
                Image image = (Image)b.getCells().first().get();
                image.setDrawable(sectorsShown ? Icon.upOpen : Icon.downOpen);
                return sectorsShown;
            }).with(t -> t.left().margin(7f)).with(t -> t.getLabelCell().grow().left()).row();

            c.collapser(t -> {
                t.background(Styles.black8);

                notifs = t;
                rebuildList();
            }, false, () -> sectorsShown).padBottom(64f).row();
        }
    }

    void rebuildList(){
        if(notifs == null) return;

        notifs.clear();

        var all = state.planet.sectors.select(Sector::hasBase);
        all.sort(Structs.comps(Structs.comparingBool(s -> !s.isAttacked()), Structs.comparingInt(s -> s.save == null ? 0 : -(int)s.save.meta.timePlayed)));

        notifs.pane(p -> {
            Runnable[] readd = {null};

            p.table(s -> {
                s.image(Icon.zoom).padRight(4);
                s.field(searchText, t -> {
                    searchText = t;
                    readd[0].run();
                }).growX().height(50f);
            }).growX().row();

            Table con = p.table().growX().get();
            con.touchable = Touchable.enabled;

            readd[0] = () -> {
                con.clearChildren();
                for(Sector sec : all){
                    if(sec.hasBase() && (searchText.isEmpty() || sec.name().toLowerCase().contains(searchText.toLowerCase()))){
                        con.button(t -> {
                            t.marginRight(10f);
                            t.left();
                            t.defaults().growX();

                            t.table(head -> {
                                head.left().defaults();

                                if(sec.isAttacked()){
                                    head.image(Icon.warningSmall).update(i -> {
                                        i.color.set(Pal.accent).lerp(Pal.remove, Mathf.absin(Time.globalTime, 9f, 1f));
                                    }).padRight(4f);
                                }else if(sec.preset != null && sec.preset.requireUnlock){
                                    head.image(sec.preset.uiIcon).size(iconSmall).padRight(4f);
                                }

                                String ic = sec.iconChar() == null ? "" : sec.iconChar() + " ";

                                head.add(ic + sec.name()).growX().wrap(true);
                            }).growX().row();

                            if(sec.isAttacked()){
                                addSurvivedInfo(sec, t, true);
                            }
                        }, Styles.underlineb, () -> {
                            lookAt(sec);
                            selected = sec;
                            updateSelected();
                        }).margin(8f).marginLeft(13f).marginBottom(6f).marginTop(6f).padBottom(3f).padTop(3f).growX().checked(b -> selected == sec).row();
                        //for resources: .tooltip(sec.info.resources.toString("", u -> u.emoji()))
                    }
                }

                if(con.getChildren().isEmpty()){
                    con.add("@none.found").pad(10f);
                }
            };

            readd[0].run();
        }).grow().scrollX(false);
    }

    public Planet getHoverPlanet(float mouseX, float mouseY){
        Planet hoverPlanet = null;
        float nearest = Float.POSITIVE_INFINITY;

        for(Planet planet : content.planets()){
            Ray r = planets.cam.getPickRay(mouseX, mouseY);

            // get planet we're hovering over
            Vec3 intersect = planet.intersect(r, outlineRad * planet.radius);

            if(intersect != null && selectable(planet) && intersect.dst(r.origin) < nearest){
                nearest = intersect.dst(r.origin);
                hoverPlanet = planet;
            }
        }

        return hoverPlanet;
    }

    public void viewPlanet(Planet planet, boolean pan){
        if(state.planet != planet){
            selected = null;
            powerLinkSource = null;
            if(pan){
                state.otherCamPos = state.planet.position;
                state.otherCamAlpha = 0;
            }
            newPresets.clear();
            state.planet = planet;

            clampZoom();

            selected = null;
            updateSelected();
            rebuildExpand();
            settings.put("lastplanet", planet.name);
        }
    }

    @Override
    public void draw(){
        boolean doBuffer = color.a < 0.99f;

        if(doBuffer){
            buffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
            buffer.begin(Color.clear);
        }

        super.draw();

        if(doBuffer){
            buffer.end();

            Draw.color(color);
            Draw.rect(Draw.wrap(buffer.getTexture()), width/2f, height/2f, width, -height);
            Draw.color();
        }
    }

    @Override
    public void act(float delta){
        super.act(delta);

        //update lerp
        if(state.otherCamPos != null){
            state.otherCamAlpha = Mathf.lerpDelta(state.otherCamAlpha, 1f, 0.05f);
            state.camPos.set(0f, camLength, 0.1f);

            if(Mathf.equal(state.otherCamAlpha, 1f, 0.01f)){
                //TODO change zoom too
                state.camPos.set(Tmp.v31.set(state.otherCamPos).slerp(state.planet.position, state.otherCamAlpha).add(state.camPos).sub(state.planet.position));

                state.otherCamPos = null;
                //announce new sector
                if(!state.planet.sectors.isEmpty()){
                    newPresets.add(state.planet.sectors.get(state.planet.startSector));
                }

            }
        }

        //fade in sector dialog after panning
        if(sectorTop != null && state.otherCamPos == null){
            sectorTop.color.a = Mathf.lerpDelta(sectorTop.color.a, 1f, 0.1f);
        }

        if(hovered != null && state.planet.hasGrid()){
            addChild(hoverLabel);
            hoverLabel.toFront();
            hoverLabel.touchable = Touchable.disabled;
            hoverLabel.color.a = state.uiAlpha;

            Vec3 pos = hovered.planet.project(hovered, planets.cam, Tmp.v31);
            hoverLabel.setPosition(pos.x - Core.scene.marginLeft, pos.y - Core.scene.marginBottom, Align.center);

            hoverLabel.getText().setLength(0);
            if(hovered != null){
                StringBuilder tx = hoverLabel.getText();
                if(!canSelect(hovered)){
                    if(mode == conveyorSelect || mode == troopLaunchSelect){
                        if(hovered == launchSector){
                            tx.append("[accent]").append(hovered.name()).append(" [lightgray](当前区块)");
                        }
                    }else if(!(hovered.preset == null && !hovered.planet.allowLaunchToNumbered)){
                        if(mode == planetLaunch){
                            tx.append("[gray]").append(Iconc.cancel);
                        }else if(hovered.planet.generator != null && mode != select){
                            hovered.planet.generator.getLockedText(hovered, tx);
                        }
                    }
                }else{
                    tx.append("[accent][[ [white]").append(hovered.name()).append("[accent] ]");
                    if(mode == troopLaunchSelect){
                        tx.append(hovered.hasBase() ? " [#84f491](常规调动)" : " [scarlet](抢滩登陆)");
                    }
                }
            }
            hoverLabel.invalidateHierarchy();
        }else{
            hoverLabel.remove();
        }

        if(launching && selected != null){
            lookAt(selected, 0.1f);
        }

        if(showing()){
            Sector to = newPresets.peek();

            presetShow += Time.delta;

            lookAt(to, 0.11f);
            zoom = 0.75f;

            if(presetShow >= 20f && !showed && newPresets.size > 1){
                showed = true;
                ui.announce(Iconc.lockOpen + " [accent]" + to.name(), 2f);
            }

            if(presetShow > sectorShowDuration){
                newPresets.pop();
                showed = false;
                presetShow = 0f;
            }
        }

        if(state.planet.hasGrid()){
            hovered = Core.scene.getDialog() == this ? state.planet.getSector(planets.cam.getMouseRay(), PlanetRenderer.outlineRad * state.planet.radius) : null;
        }else if(state.planet.isLandable()){
            boolean wasNull = selected == null;
            //always have the first sector selected.
            //TODO better support for multiple sectors in gridless planets?
            hovered = selected = state.planet.sectors.first();

            //autoshow
            if(wasNull){
                updateSelected();
            }
        }else{
            hovered = selected = null;
        }

        state.zoom = Mathf.lerpDelta(state.zoom, zoom, 0.4f);
        state.uiAlpha = Mathf.lerpDelta(state.uiAlpha, Mathf.num(state.zoom < 1.9f), 0.1f);
    }

    /** Normal View sector detail dialog: strictly basic campaign & map resource info (no power/logistics clutter). */
    void showStats(Sector sector){
        BaseDialog dialog = new BaseDialog(sector.name());

        dialog.cont.pane(c -> {
            c.defaults().padBottom(5);

            if(sector.preset != null && sector.preset.description != null){
                c.add(sector.preset.displayDescription()).width(420f).wrap().left().row();
            }

            if(sector.save != null){
                c.add(Core.bundle.get("sectors.time") + " [accent]" + sector.save.getPlayTime()).left().row();
            }

            if(sector.info.attempts > 0){
                c.add(Core.bundle.get("sectors.attempts") + " [accent]" + sector.info.attempts).left().row();
            }

            if(sector.info.waves && sector.hasBase()){
                c.add(Core.bundle.get("sectors.wave") + " [accent]" + sector.info.wave).left().row();
            }

            if(sector.isAttacked() || !sector.hasBase()){
                c.add(Core.bundle.get("sectors.threat") + " [accent]" + sector.displayThreat()).left().row();
            }

            if(sector.save != null && sector.info.resources.any()){
                c.add("@sectors.resources").left().row();
                c.table(t -> {
                    for(UnlockableContent uc : sector.info.resources){
                        if(uc == null) continue;
                        t.image(uc.uiIcon).scaling(Scaling.fit).padRight(3).size(iconSmall);
                    }
                }).padLeft(10f).left().row();
            }

            boolean lockdown = sector.isAttacked() && !sector.isBeingPlayed();

            if(lockdown){
                c.add(UI.formatIcons(bundle.get("sector.lockdown"))).wrap().fillX().padBottom(10f).row();
            }
        });

        dialog.addCloseButton();

        if(sector.hasBase() && (!net.isConnectedClient() || MultiSectorCoordinator.get().canLocalGuestAbandon())){
            dialog.buttons.button("@sector.abandon", Icon.cancel, () -> abandonSectorConfirm(sector, dialog::hide));
        }

        dialog.show();
    }

    public void abandonSectorConfirm(Sector sector, Runnable listener){
        ui.showConfirm("@sector.abandon.confirm", () -> {
            if(listener != null) listener.run();

            if(net.isConnectedClient()){
                MultiSectorCoordinator.get().requestGuestAbandonSector(sector);
                updateSelected();
                rebuildList();
                return;
            }

            if(sector.isBeingPlayed()){
                hide();
                //after dialog is hidden
                Time.runTask(7f, () -> {
                    //force game over in a more dramatic fashion
                    for(var core : player.team().cores().copy()){
                        core.kill();
                    }
                });
            }else{
                sector.info.items.clear();
                sector.info.hasCore = false;
                sector.info.production.clear();
                sector.saveInfo();
                SectorPowerGridManager.get().onSectorLost(sector);
                SectorConveyorManager.get().onSectorLost(sector);
            }
            updateSelected();
            rebuildList();
        });
    }

    void addSurvivedInfo(Sector sector, Table table, boolean wrap){
        if(!wrap){
            table.add("@sectors.underattack").wrapLabel(wrap).row();
        }
    }

    public void selectSector(Sector sector){
        selected = sector;
        updateSelected();
    }

    void updateSelected(){
        Sector sector = selected;
        Table stable = sectorTop;

        if(sector == null || mode == conveyorSelect || mode == troopLaunchSelect){
            stable.clear();
            stable.visible = false;
            return;
        }
        stable.visible = true;

        float x = stable.getX(Align.center), y = stable.getY(Align.center);
        stable.clear();
        stable.background(Styles.black6);

        if(mode == look && resourceViewMode){
            buildResourceSectorPanel(stable, sector);
        }else if(mode == look && powerViewMode){
            buildPowerSectorPanel(stable, sector);
        }else if(mode == look && troopViewMode){
            buildTroopSectorPanel(stable, sector);
        }else{
            buildNormalSectorPanel(stable, sector);
        }

        stable.pack();
        stable.setPosition(x, y, Align.center);

        stable.act(0f);
    }

    /** Normal View bottom panel: campaign status, minerals, basic stats button, and launch/play button. */
    private void buildNormalSectorPanel(Table stable, Sector sector){
        stable.table(title -> {
            title.add("[accent]" + sector.name() + (debugSelect && (sector.info.name != null || sector.preset != null) ? " [lightgray](" + sector.id + ")" : "")).padLeft(3);
            if(!net.isConnectedClient() && (sector.preset == null || (!sector.preset.requireUnlock))){
                title.add().growX();

                title.button(Icon.pencilSmall, Styles.clearNonei, () -> {
                   ui.showTextInput("@sectors.rename", "@name", 32, sector.name(), v -> {
                       sector.setName(v);
                       updateSelected();
                       rebuildList();
                   });
                }).size(40f).padLeft(4);
            }

            var icon = sector.info.contentIcon != null ?
                new TextureRegionDrawable(sector.info.contentIcon.uiIcon) :
                Icon.icons.get(sector.info.icon + "Small");

            title.button(icon == null ? Icon.noneSmall : icon, Styles.clearNonei, iconSmall, () -> {
                //TODO use IconSelectDialog
                new Dialog(""){{
                    closeOnBack();
                    setFillParent(true);

                    Runnable refresh = () -> {
                        sector.saveInfo();
                        hide();
                        updateSelected();
                        rebuildList();
                    };

                    cont.pane(t -> {
                        resized(true, () -> {
                            t.clearChildren();
                            t.marginRight(19f);
                            t.defaults().size(48f);

                            t.button(Icon.none, Styles.squareTogglei, () -> {
                                sector.info.icon = null;
                                sector.info.contentIcon = null;
                                refresh.run();
                            }).checked(sector.info.icon == null && sector.info.contentIcon == null);

                            int cols = (int)Math.min(20, Core.graphics.getWidth() / Scl.scl(52f));

                            int i = 1;
                            for(var key : accessibleIcons){
                                var value = Icon.icons.get(key);

                                t.button(value, Styles.squareTogglei, () -> {
                                    sector.info.icon = key;
                                    sector.info.contentIcon = null;
                                    refresh.run();
                                }).checked(key.equals(sector.info.icon));

                                if(++i % cols == 0) t.row();
                            }

                            for(ContentType ctype : defaultContentIcons){
                                t.row();
                                t.image().colspan(cols).growX().width(Float.NEGATIVE_INFINITY).height(3f).color(Pal.accent);
                                t.row();

                                i = 0;
                                for(UnlockableContent u : content.getBy(ctype).<UnlockableContent>as()){
                                    if(!u.isHidden() && u.unlocked()){
                                        t.button(new TextureRegionDrawable(u.uiIcon), Styles.squareTogglei, iconMed, () -> {
                                            sector.info.icon = null;
                                            sector.info.contentIcon = u;
                                            refresh.run();
                                        }).checked(sector.info.contentIcon == u);

                                        if(++i % cols == 0) t.row();
                                    }
                                }
                            }
                        });
                    });
                    buttons.button("@back", Icon.left, this::hide).size(210f, 64f);
                }}.show();
            }).size(40f).tooltip("@sector.changeicon");
        }).row();

        stable.image().color(Pal.accent).fillX().height(3f).pad(3f).row();

        boolean locked = isLocked(sector);

        if(locked){
            stable.table(r -> {
                r.add("@complete").colspan(2).left();
                r.row();
                for(Objective o : sector.preset.techNode.objectives){
                    if(o.complete()) continue;

                    r.add("> " + o.display()).color(Color.lightGray).left();
                    r.image(o.complete() ? Icon.ok : Icon.cancel, o.complete() ? Color.lightGray : Color.scarlet).padLeft(3);
                    r.row();
                }
            }).row();
        }else if(!sector.hasBase()){
            stable.add(Core.bundle.get("sectors.threat") + " [accent]" + sector.displayThreat()).row();
        }

        if(sector.isAttacked()){
            addSurvivedInfo(sector, stable, false);
        }else if(sector.hasBase() && sector.planet.campaignRules.sectorInvasion && sector.near().contains(s -> s.hasEnemyBase() && (s.preset == null || !s.preset.requireUnlock))){
            stable.add("@sectors.vulnerable");
            stable.row();
        }else if(!sector.hasBase() && sector.hasEnemyBase()){
            stable.add("@sectors.enemybase");
            stable.row();
        }

        if(sector.save != null && sector.info.resources.any()){
            stable.table(t -> {
                t.add("@sectors.resources").padRight(4);
                for(UnlockableContent c : sector.info.resources){
                    if(c == null) continue; //apparently this is possible.
                    t.image(c.uiIcon).padRight(3).scaling(Scaling.fit).size(iconSmall);
                }
            }).padLeft(10f).fillX().row();
        }

        stable.row();

        if(net.active()){
            int occCount = MultiSectorCoordinator.get().getSectorPlayerCount(sector);
            if(occCount > 0){
                stable.add("[accent]当前区块玩家数: " + occCount).row();
            }
        }

        if(sector.hasBase()){
            stable.button("@stats", Icon.info, Styles.cleart, () -> showStats(sector)).height(40f).fillX().row();
        }

        if((sector.hasBase() && mode == look) || canSelect(sector) || (sector.preset != null && sector.preset.alwaysUnlocked) || debugSelect){
            if(Vars.showSectorSubmissions){
                String link = SectorSubmissions.getSectorThread(sector);
                if(link != null){
                    stable.button("@sectors.viewsubmission", Icon.link, () -> {
                        Core.app.openURI(link);
                    }).growX().height(54f).minWidth(170f).padTop(2f).row();
                }
            }

            boolean hasBeachhead = mode == look && SectorTroopAirliftManager.get().hasFriendlyBeachhead(sector);
            if(hasBeachhead){
                stable.add("[#ffd37f]抢滩部队已就位（无核心）").padTop(2f).row();
            }

            boolean noCandidate = !hasBeachhead && hasNoCandidate(sector);
            boolean guestNoPerm = net.isConnectedClient() && mode == look && !sector.isBeingPlayed() && (
                sector.hasBase()
                    ? !MultiSectorCoordinator.get().canLocalGuestSwitchSector()
                    : !MultiSectorCoordinator.get().canLocalGuestLaunchCore()
            );

            stable.button(
                mode == select ? "@sectors.select" :
                sector.isBeingPlayed() ? "@sectors.resume" :
                guestNoPerm ? "房主未开放权限" :
                hasBeachhead ? "进入抢滩" :
                sector.hasBase() ? "@sectors.go" :
                locked ? "@locked" :
                noCandidate ? "@sectors.nolaunchcandidate" :
                "@sectors.launch",
                ((locked && !hasBeachhead) || guestNoPerm) ? Icon.lock : Icon.play, this::playSelected).growX().height(54f).minWidth(170f).padTop(4).disabled((locked && !hasBeachhead) || noCandidate || guestNoPerm);
        }
    }

    /** Resource View bottom panel: strictly sector name + per-minute net production with cross-sector net transfer suffix. */
    private void buildResourceSectorPanel(Table stable, Sector sector){
        stable.add("[accent]" + sector.name() + (debugSelect && (sector.info.name != null || sector.preset != null) ? " [lightgray](" + sector.id + ")" : "")).pad(4f).row();
        stable.image().color(Pal.accent).fillX().height(3f).pad(3f).row();

        if(!sector.hasBase()){
            stable.add("[lightgray]尚未占领该区块").pad(8f).row();
            return;
        }

        java.util.List<ResourceFlowEntry> allFlows = collectSectorResourceFlows(sector);
        java.util.List<ResourceFlowEntry> activeFlows = new java.util.ArrayList<>();
        for(int i = 0; i < allFlows.size(); i++){
            ResourceFlowEntry entry = allFlows.get(i);
            if(Math.abs(entry.localNetPerSec * 60f) >= 0.5f || Math.abs(entry.crossNetPerSec() * 60f) >= 0.5f){
                activeFlows.add(entry);
            }
        }

        if(activeFlows.isEmpty()){
            stable.add("[lightgray]暂无物资净产量变动").pad(8f).row();
            return;
        }

        stable.add("[lightgray]物资净产量 (/分)").left().padLeft(8f).padTop(2f).padBottom(2f).row();

        Cons<Table> fillGrid = grid -> {
            grid.left().margin(4f);
            int col = 0;
            for(int i = 0; i < activeFlows.size(); i++){
                ResourceFlowEntry e = activeFlows.get(i);
                grid.table(cell -> {
                    cell.left();
                    cell.image(e.item.uiIcon).size(16f).padRight(3f);
                    cell.add(formatSignedPerMin(e.localNetPerSec) + formatCrossNetSuffixPerMin(e.crossNetPerSec())).left();
                }).left().padRight(10f).padBottom(3f);
                if(++col % 2 == 0){
                    grid.row();
                }
            }
        };

        if(activeFlows.size() > 6){
            stable.pane(Styles.smallPane, fillGrid)
                .scrollX(false)
                .maxHeight(Core.graphics.isPortrait() ? 150f : 130f)
                .growX().padLeft(4f).padRight(4f).padBottom(4f).row();
        }else{
            stable.table(fillGrid).left().growX().padLeft(4f).padRight(4f).padBottom(4f).row();
        }
    }

    /** Power View bottom panel: strictly sector name + inter-sector grid totals (or local power if unlinked). */
    private void buildPowerSectorPanel(Table stable, Sector sector){
        stable.add("[accent]" + sector.name() + (debugSelect && (sector.info.name != null || sector.preset != null) ? " [lightgray](" + sector.id + ")" : "")).pad(4f).row();
        stable.image().color(Pal.accent).fillX().height(3f).pad(3f).row();

        if(!sector.hasBase()){
            stable.add("[lightgray]尚未占领该区块").pad(8f).row();
            return;
        }

        if(SectorPowerGridManager.get().hasHub(sector)){
            buildPowerGridSummary(stable, sector);
        }else{
            buildLocalPowerSummary(stable, sector);
        }
    }

    boolean hasNoCandidate(Sector sector){
        return sector != sector.planet.getStartSector() && !sector.hasBase() && findLauncher(sector) == null;
    }

    boolean isLocked(Sector sector){
        return sector.preset != null && sector.preset.locked() && !sector.hasBase() && sector.preset.techNode != null;
    }

    boolean canPlaySector(Sector sector){
        if(net.isConnectedClient() && mode == look && !sector.isBeingPlayed()){
            boolean permOk = sector.hasBase()
                ? MultiSectorCoordinator.get().canLocalGuestSwitchSector()
                : MultiSectorCoordinator.get().canLocalGuestLaunchCore();
            if(!permOk) return false;
        }
        return !isLocked(sector) && !hasNoCandidate(sector);
    }

    void playSelected(){
        if(selected == null) return;

        Sector sector = selected;

        if(sector.isBeingPlayed()){
            //already at this sector
            hide();
            return;
        }

        if(sector.preset != null && sector.preset.requireUnlock && sector.preset.locked() && sector.preset.techNode != null && !sector.hasBase()){
            return;
        }

        //V2: Multiplayer guest routes sector switch & core launch through Host authority
        if(net.isConnectedClient() && mode == look){
            if(sector.hasBase()){
                MultiSectorCoordinator.get().requestGuestSwitchSector(sector);
            }else{
                MultiSectorCoordinator.get().requestGuestLaunchSector(findLauncher(sector), sector);
            }
            hide();
            return;
        }

        boolean shouldHide = true;

        //save before launch.
        if(control.saves.getCurrent() != null && Vars.state.isGame() && mode != select && !net.isConnectedClient()){
            try{
                control.saves.getCurrent().save();
            }catch(Throwable e){
                e.printStackTrace();
                ui.showException("[accent]" + Core.bundle.get("savefail"), e);
            }
        }

        if(mode == look && !sector.hasBase()){
            // V3.9: Direct coreless beachhead entry skips LaunchLoadoutDialog so no core is spawned.
            if(SectorTroopAirliftManager.get().hasFriendlyBeachhead(sector)){
                hide();
                control.playSector(sector);
                return;
            }
            shouldHide = false;
            Sector from = findLauncher(sector);

            if(from == null){
                //clear loadout information, so only the basic loadout gets used
                universe.clearLoadoutInfo();
                //free launch.
                control.playSector(sector);
            }else{
                CoreBlock block = sector.allowLaunchSchematics() ? (from.info.bestCoreType instanceof CoreBlock b ? b : (CoreBlock)from.planet.defaultCore) : (CoreBlock)from.planet.defaultCore;

                loadouts.show(block, from, sector, () -> {
                    var loadout = universe.getLastLoadout();
                    var schemCore = loadout.findCore();
                    from.removeItems(loadout.requirements());
                    from.removeItems(universe.getLaunchResources());

                    Events.fire(new SectorLaunchLoadoutEvent(sector, from, loadout));

                    CoreBuild core = player.team().core();
                    if(core == null || settings.getBool("skipcoreanimation")){
                        //just... go there
                        control.playSector(from, sector);
                        //hide only after load screen is shown
                        Time.runTask(8f, this::hide);
                    }else{
                        //hide immediately so launch sector is visible
                        hide();

                        //allow planet dialog to finish hiding before actually launching
                        Time.runTask(5f, () -> {
                            Runnable doLaunch = () -> {
                                renderer.showLaunch(core);
                                //run with less delay, as the loading animation is delayed by several frames
                                Time.runTask(core.launchDuration() - 8f, () -> control.playSector(from, sector));
                            };

                            //load launchFrom sector right before launching so animation is correct
                            if(!from.isBeingPlayed()){
                                //run *after* the loading animation is done
                                Time.runTask(9f, doLaunch);
                                control.playSector(from);
                            }else{
                                doLaunch.run();
                            }
                        });
                    }
                });
            }
        }else if(mode == select || mode == planetLaunch){
            listener.get(sector);
        }else{
            //sector should have base here
            control.playSector(sector);
        }

        if(shouldHide) hide();
    }

    private boolean isPowerViewUnlocked(){
        if(debugSelect) return true;
        if(Blocks.surgeSectorPowerHub != null && Blocks.surgeSectorPowerHub.unlockedNow()) return true;
        return SectorPowerGridManager.get().getTopology(state.planet).getHubSectorIds().size() > 0;
    }

    private void buildPowerGridSummary(Table stable, Sector sector){
        SectorPowerGridManager pm = SectorPowerGridManager.get();
        if(!pm.hasHub(sector)) return;

        GridAggregate agg = pm.getGridAggregate(sector);
        long prodSec = Math.round(agg.totalProduced * 60f);
        long needSec = Math.round(agg.totalNeeded * 60f);
        long netSec = prodSec - needSec;
        String netText = (netSec >= 0 ? "[#84f491]+" : "[scarlet]-") + UI.formatAmount(Math.abs(netSec)) + "/s";
        int satPct = Math.round(agg.satisfaction * 100f);

        stable.table(card -> {
            card.left().margin(6f);

            card.table(hdr -> {
                hdr.left();
                hdr.image(Icon.powerSmall).color(agg.overloaded ? Pal.remove : Pal.powerLight).size(16f).padRight(4f);
                hdr.add("[#ffd37f]区块间电网 [white](" + agg.sectorCount() + "区块)" + (agg.overloaded ? " [scarlet]过载" : "")).left();
            }).left().growX().padBottom(3f).row();

            card.add("[lightgray]发电 [white]+" + UI.formatAmount(prodSec) + "/s  [lightgray]消耗 [white]-" + UI.formatAmount(needSec) + "/s").left().row();
            card.add("[lightgray]净值 " + netText + "  [lightgray]供电 [white]" + satPct + "%").left().padBottom(4f).row();

            card.add(new Bar(
                () -> "储存 " + UI.formatAmount((long)agg.totalBatteryStored) + " / " + UI.formatAmount((long)agg.totalBatteryCapacity),
                () -> agg.overloaded ? Pal.remove : Pal.powerBar,
                () -> agg.totalBatteryCapacity > 0.01f ? Mathf.clamp(agg.totalBatteryStored / agg.totalBatteryCapacity) : 0f
            )).growX().height(18f).row();
        }).growX().pad(2f).row();
    }

    private void buildLocalPowerSummary(Table stable, Sector sector){
        LocalPowerSnapshot snap = SectorPowerGridManager.get().getLocalPowerSnapshot(sector);
        long prodSec = Math.round(snap.producedPerSec);
        long needSec = Math.round(snap.neededPerSec);
        long netSec = prodSec - needSec;
        String netText = (netSec >= 0 ? "[#84f491]+" : "[scarlet]-") + UI.formatAmount(Math.abs(netSec)) + "/s";
        float sat = snap.satisfaction();
        boolean overloaded = snap.neededPerSec > 0.0001f && sat < 0.999f;
        int satPct = Math.round(sat * 100f);

        stable.table(card -> {
            card.left().margin(6f);

            card.table(hdr -> {
                hdr.left();
                hdr.image(Icon.powerSmall).color(overloaded ? Pal.remove : Pal.gray).size(16f).padRight(4f);
                hdr.add("[lightgray]本地电网 [gray](未接入跨区电网)" + (overloaded ? " [scarlet]电力不足" : "")).left();
            }).left().growX().padBottom(3f).row();

            card.add("[lightgray]发电 [white]+" + UI.formatAmount(prodSec) + "/s  [lightgray]消耗 [white]-" + UI.formatAmount(needSec) + "/s").left().row();
            card.add("[lightgray]净值 " + netText + "  [lightgray]供电 [white]" + satPct + "%").left().padBottom(4f).row();

            card.add(new Bar(
                () -> "储存 " + UI.formatAmount((long)snap.batteryStored) + " / " + UI.formatAmount((long)snap.batteryCapacity),
                () -> overloaded ? Pal.remove : Pal.powerBar,
                () -> snap.batteryCapacity > 0.01f ? Mathf.clamp(snap.batteryStored / snap.batteryCapacity) : 0f
            )).growX().height(18f).row();
        }).growX().pad(2f).row();
    }

    /** Power View detail dialog: per-sector local power metrics + connected grid members & adjacent link toggles. */
    private void showPowerDetails(Sector sector){
        if(sector == null || !sector.hasBase()) return;
        SectorPowerGridManager pm = SectorPowerGridManager.get();
        PlanetPowerTopology topo = pm.getTopology(sector.planet);
        boolean hasHub = pm.hasHub(sector);

        float localProdSec;
        float localNeedSec;
        float localStored;
        float localCap;
        GridAggregate agg = null;

        if(hasHub){
            SectorPowerNode node = topo.getNode(sector.id);
            localProdSec = node != null ? node.produced * 60f : 0f;
            localNeedSec = node != null ? node.needed * 60f : 0f;
            localStored = node != null ? node.batteryStored : 0f;
            localCap = node != null ? node.batteryCapacity : 0f;
            agg = pm.getGridAggregate(sector);
        }else{
            LocalPowerSnapshot snap = pm.getLocalPowerSnapshot(sector);
            localProdSec = snap.producedPerSec;
            localNeedSec = snap.neededPerSec;
            localStored = snap.batteryStored;
            localCap = snap.batteryCapacity;
        }

        long localProd = Math.round(localProdSec);
        long localNeed = Math.round(localNeedSec);
        long localNet = localProd - localNeed;
        boolean localDeficit = localNeed > localProd && localStored <= 0.01f;
        final float fLocalStored = localStored;
        final float fLocalCap = localCap;
        final boolean fLocalDeficit = localDeficit;

        BaseDialog dialog = new BaseDialog(sector.name() + " · 电力详情");
        dialog.cont.pane(c -> {
            c.left().top().margin(6f);
            c.defaults().left().padBottom(6f);

            // Section 1: This single sector's own power metrics
            c.add("[#ffd37f]本区块电力").left().row();
            c.table(Styles.black6, card -> {
                card.left().margin(8f);
                card.defaults().left().padBottom(3f);

                card.add("[lightgray]发电量： [white]+" + UI.formatAmount(localProd) + "/s").row();
                card.add("[lightgray]消耗量： [white]-" + UI.formatAmount(localNeed) + "/s").row();

                String roleHint = "";
                if(hasHub && agg != null && agg.sectorCount() > 1){
                    if(localNet > 0){
                        roleHint = " [lightgray](向跨区电网净输出)";
                    }else if(localNet < 0){
                        roleHint = " [lightgray](从跨区电网净吸取)";
                    }
                }
                String netStr = (localNet >= 0 ? "[#84f491]+" : "[scarlet]-") + UI.formatAmount(Math.abs(localNet)) + "/s" + roleHint;
                card.add("[lightgray]本区净功率： " + netStr).row();

                card.add("[lightgray]储存量 / 储存总量： [white]" + UI.formatAmount((long)fLocalStored) + " / " + UI.formatAmount((long)fLocalCap)).padBottom(4f).row();
                card.add(new Bar(
                    () -> "本区蓄电 " + UI.formatAmount((long)fLocalStored) + " / " + UI.formatAmount((long)fLocalCap),
                    () -> fLocalDeficit ? Pal.remove : Pal.powerBar,
                    () -> fLocalCap > 0.01f ? Mathf.clamp(fLocalStored / fLocalCap) : 0f
                )).minWidth(280f).growX().height(20f).row();
            }).growX().row();

            // Section 2: Inter-sector power grid details
            if(hasHub && agg != null){
                final GridAggregate fAgg = agg;
                long gProd = Math.round(fAgg.totalProduced * 60f);
                long gNeed = Math.round(fAgg.totalNeeded * 60f);
                long gNet = gProd - gNeed;
                int gSatPct = Math.round(fAgg.satisfaction * 100f);

                c.add("[#ffd37f]所属跨区电网 [white](" + fAgg.sectorCount() + "个区块)" + (fAgg.overloaded ? " [scarlet]过载" : "")).left().padTop(4f).row();
                c.table(Styles.black6, gridCard -> {
                    gridCard.left().margin(8f);
                    gridCard.defaults().left().padBottom(3f);

                    gridCard.add("[lightgray]整网发电： [white]+" + UI.formatAmount(gProd) + "/s  [lightgray]整网消耗： [white]-" + UI.formatAmount(gNeed) + "/s").row();
                    gridCard.add("[lightgray]整网净值： " + (gNet >= 0 ? "[#84f491]+" : "[scarlet]-") + UI.formatAmount(Math.abs(gNet)) + "/s  [lightgray]供电率： [white]" + gSatPct + "%").padBottom(4f).row();
                    gridCard.add(new Bar(
                        () -> "整网蓄电 " + UI.formatAmount((long)fAgg.totalBatteryStored) + " / " + UI.formatAmount((long)fAgg.totalBatteryCapacity),
                        () -> fAgg.overloaded ? Pal.remove : Pal.powerBar,
                        () -> fAgg.totalBatteryCapacity > 0.01f ? Mathf.clamp(fAgg.totalBatteryStored / fAgg.totalBatteryCapacity) : 0f
                    )).minWidth(280f).growX().height(20f).row();

                    if(fAgg.sectorIds.length > 0){
                        gridCard.add("[lightgray]电网成员：").padTop(4f).row();
                        for(int i = 0; i < fAgg.sectorIds.length; i++){
                            int memberId = fAgg.sectorIds[i];
                            if(memberId < 0 || memberId >= sector.planet.sectors.size) continue;
                            Sector member = sector.planet.sectors.get(memberId);
                            SectorPowerNode mn = topo.getNode(memberId);
                            long mNet = mn != null ? Math.round((mn.produced - mn.needed) * 60f) : 0L;
                            String prefix = (memberId == sector.id) ? "[accent]• " : "[white]• ";
                            String mNetStr = (mNet >= 0 ? "[#84f491]+" : "[scarlet]-") + UI.formatAmount(Math.abs(mNet)) + "/s";
                            gridCard.add(prefix + member.name() + "  " + mNetStr).padLeft(8f).row();
                        }
                    }
                }).growX().row();

                // Adjacent hub link management (especially convenient on mobile portrait)
                java.util.List<Sector> adjacentHubs = new java.util.ArrayList<>();
                for(Sector near : sector.near()){
                    if(pm.hasHub(near)){
                        adjacentHubs.add(near);
                    }
                }
                java.util.Collections.sort(adjacentHubs, (a, b) -> Integer.compare(a.id, b.id));

                c.add("[#ffd37f]相邻电力枢纽连线").left().padTop(4f).row();
                c.table(Styles.black6, adjCard -> {
                    adjCard.left().margin(8f);
                    if(adjacentHubs.isEmpty()){
                        adjCard.add("[lightgray]相邻区块暂无已建造电力枢纽的基地").left();
                    }else{
                        for(int i = 0; i < adjacentHubs.size(); i++){
                            Sector near = adjacentHubs.get(i);
                            boolean linked = pm.areLinked(sector, near);
                            adjCard.add("[white]" + near.name()).left().minWidth(130f).padRight(10f);
                            adjCard.add(linked ? "[#84f491]已连接" : "[lightgray]未连接").left().minWidth(64f).padRight(10f);
                            adjCard.button(linked ? "[scarlet]断开" : "[#84f491]连接", linked ? Icon.cancelSmall : Icon.link, Styles.cleart, () -> {
                                int res = pm.requestToggleLink(sector.planet, sector, near);
                                if(res == 2){
                                    ui.showInfoFade("已向房主请求变更电网连线");
                                }
                                dialog.hide();
                                updateSelected();
                                showPowerDetails(sector);
                            }).minWidth(96f).height(36f).row();
                        }
                    }
                }).growX().row();
            }else{
                c.add("[lightgray]跨区电网状态").left().padTop(4f).row();
                c.table(Styles.black6, infoCard -> {
                    infoCard.left().margin(8f);
                    infoCard.add("[lightgray]本区块尚未建造「巨浪区块电力枢纽」，当前仅本地独立供电。").width(340f).wrap().left().row();
                    infoCard.add("[gray]建造电力枢纽后，可在电力视野中与相邻枢纽区块互联，共享发电与蓄电。").width(340f).wrap().left().padTop(4f).row();
                }).growX().row();
            }
        }).minWidth(360f).maxHeight(520f);

        dialog.addCloseButton();
        dialog.show();
    }

    private boolean isResourceViewUnlocked(){
        if(debugSelect) return true;
        if(Blocks.basicSectorConveyorSender != null && Blocks.basicSectorConveyorSender.unlockedNow()) return true;
        if(Blocks.basicSectorConveyorReceiver != null && Blocks.basicSectorConveyorReceiver.unlockedNow()) return true;
        if(Blocks.advancedSectorConveyorSender != null && Blocks.advancedSectorConveyorSender.unlockedNow()) return true;
        if(Blocks.advancedSectorConveyorReceiver != null && Blocks.advancedSectorConveyorReceiver.unlockedNow()) return true;
        return !SectorConveyorManager.get().getTopology(state.planet).getPortSectorIds().isEmpty();
    }

    /** Per-item resource breakdown for a single selected sector (rates stored in items/sec, formatted per minute). */
    private static class ResourceFlowEntry{
        final Item item;
        final float localGrossPerSec;
        final float localConsPerSec;
        final float localNetPerSec;
        final float crossInPerSec;
        final float crossOutPerSec;

        ResourceFlowEntry(Item item, float localGrossPerSec, float localConsPerSec, float localNetPerSec, float crossInPerSec, float crossOutPerSec){
            this.item = item;
            this.localGrossPerSec = localGrossPerSec;
            this.localConsPerSec = localConsPerSec;
            this.localNetPerSec = localNetPerSec;
            this.crossInPerSec = crossInPerSec;
            this.crossOutPerSec = crossOutPerSec;
        }

        float crossNetPerSec(){
            return crossInPerSec - crossOutPerSec;
        }
    }

    private java.util.List<ResourceFlowEntry> collectSectorResourceFlows(Sector sector){
        java.util.List<ResourceFlowEntry> out = new java.util.ArrayList<>();
        if(sector == null || !sector.hasBase() || content == null) return out;

        // 1. Cross-sector conveyor incoming & outgoing rates (items/sec)
        java.util.Map<Integer, Float> convIn = new java.util.HashMap<>();
        java.util.Map<Integer, Float> convOut = new java.util.HashMap<>();
        PlanetConveyorTopology topo = SectorConveyorManager.get().getTopology(sector.planet);
        java.util.List<SectorPortRecord> ports = topo.getAllSectorPortsReadOnly(sector.id);
        for(int i = 0; i < ports.size(); i++){
            SectorPortRecord port = ports.get(i);
            java.util.Map<Integer, Float> target = port.isSender ? convOut : convIn;
            for(java.util.Map.Entry<Integer, Float> e : port.itemRatesPerSec.entrySet()){
                if(e.getValue() > 0.001f){
                    Float prev = target.get(e.getKey());
                    target.put(e.getKey(), (prev == null ? 0f : prev) + e.getValue());
                }
            }
        }

        // 2. Background SectorStrategyProfile (exact local gross & consumption separated from imports/exports)
        SectorStrategyProfile profile = null;
        if(!sector.isBeingPlayed()){
            if(universe != null && universe.strategy != null && sector.planet != null){
                profile = universe.strategy.getEngine(sector.planet).sectors.get(sector.id);
            }
            if(profile == null && sector.info != null){
                profile = sector.info.strategyProfile;
            }
        }
        boolean useProfile = !sector.isBeingPlayed() && profile != null
            && (!profile.nodes.isEmpty() || !profile.solvedExportRates.isEmpty() || !profile.solvedImportRates.isEmpty());

        java.util.Map<String, Float> localGrossMap = null;
        java.util.Map<String, Float> localConsMap = null;
        if(useProfile){
            localGrossMap = new java.util.HashMap<>();
            localConsMap = new java.util.HashMap<>();
            for(int i = 0; i < profile.nodes.size(); i++){
                ProductionNode node = profile.nodes.get(i);
                float eff = Math.max(0f, node.solvedEfficiency);
                if(eff <= 1e-5f) continue;
                for(java.util.Map.Entry<String, Float> o : node.outputsPerSec.entrySet()){
                    float rate = o.getValue() * eff;
                    if(rate > 1e-5f){
                        Float prev = localGrossMap.get(o.getKey());
                        localGrossMap.put(o.getKey(), (prev == null ? 0f : prev) + rate);
                    }
                }
                for(java.util.Map.Entry<String, Float> in : node.inputsPerSec.entrySet()){
                    float rate = in.getValue() * eff;
                    if(rate > 1e-5f){
                        Float prev = localConsMap.get(in.getKey());
                        localConsMap.put(in.getKey(), (prev == null ? 0f : prev) + rate);
                    }
                }
            }
        }

        // 3. Merge per item in deterministic content order
        for(Item item : content.items()){
            int itemId = (int)item.id;
            Float cInObj = convIn.get(itemId);
            Float cOutObj = convOut.get(itemId);
            float cIn = cInObj != null ? cInObj : 0f;
            float cOut = cOutObj != null ? cOutObj : 0f;

            float localGross;
            float localCons;
            float localNet;
            float padIn;
            float padOut;

            if(useProfile){
                Float gObj = localGrossMap.get(item.name);
                Float kObj = localConsMap.get(item.name);
                localGross = gObj != null ? gObj : 0f;
                localCons = kObj != null ? kObj : 0f;
                localNet = localGross - localCons;
                Float pInObj = profile.solvedImportRates.get(item.name);
                Float pOutObj = profile.solvedExportRates.get(item.name);
                padIn = pInObj != null ? pInObj : 0f;
                padOut = pOutObj != null ? pOutObj : 0f;
            }else{
                ExportStat rawStat = sector.info.rawProduction.get(item);
                ExportStat prodStat = sector.info.production.get(item);
                ExportStat impStat = sector.info.imports.get(item);
                ExportStat expStat = sector.info.export.get(item);
                padIn = impStat != null ? Math.max(0f, impStat.mean) : 0f;
                padOut = expStat != null ? Math.max(0f, expStat.mean) : 0f;
                float rawMean = rawStat != null ? Math.max(0f, rawStat.mean) : 0f;
                localGross = Math.max(0f, rawMean - padIn);
                localNet = prodStat != null ? prodStat.mean : 0f;
                localCons = Math.max(0f, localGross - localNet);
            }

            float totalCrossIn = cIn + padIn;
            float totalCrossOut = cOut + padOut;

            if(Math.abs(localGross * 60f) >= 0.5f
                || Math.abs(localCons * 60f) >= 0.5f
                || Math.abs(localNet * 60f) >= 0.5f
                || totalCrossIn * 60f >= 0.5f
                || totalCrossOut * 60f >= 0.5f){
                out.add(new ResourceFlowEntry(item, localGross, localCons, localNet, totalCrossIn, totalCrossOut));
            }
        }
        return out;
    }

    private static String formatSignedPerMin(float perSec){
        long perMin = Math.round(perSec * 60f);
        if(perMin > 0){
            return "[#84f491]+" + UI.formatAmount(perMin) + "/分";
        }else if(perMin < 0){
            return "[scarlet]-" + UI.formatAmount(-perMin) + "/分";
        }
        return "[lightgray]0/分";
    }

    private static String formatCrossNetSuffixPerMin(float crossNetPerSec){
        long crossMin = Math.round(crossNetPerSec * 60f);
        if(crossMin > 0){
            return " [#84f491](+" + UI.formatAmount(crossMin) + ")";
        }else if(crossMin < 0){
            return " [#ffd37f](-" + UI.formatAmount(-crossMin) + ")";
        }
        return "";
    }

    /** Resource View detail dialog: cross-sector conveyor pairing, core storage & capacity, and per-item production/consumption/net breakdown. */
    private void showResourceDetails(Sector sector){
        if(sector == null || !sector.hasBase()) return;

        BaseDialog dialog = new BaseDialog(sector.name() + " · 资源详情");
        dialog.cont.pane(c -> {
            c.left().top().margin(6f);
            c.defaults().left().padBottom(6f);

            // Section 1: Read-only cross-sector conveyor status & live flow rates (pairing is performed on sender blocks)
            if(SectorConveyorManager.get().hasAnyPort(sector)){
                buildResourceConveyorDetailSection(c, sector);
            }else{
                c.add("[#a9d8ff]区块间传送带").left().row();
                c.table(Styles.black6, emptyCard -> {
                    emptyCard.left().margin(8f);
                    emptyCard.add("[lightgray]本区块尚未建造跨区块传送带端口").left();
                }).growX().row();
            }

            // Section 2: Core storage & capacity
            CoreBuild liveCore = (sector.isBeingPlayed() && Vars.state != null && Vars.state.rules != null && Vars.state.rules.defaultTeam != null)
                ? Vars.state.rules.defaultTeam.core()
                : null;
            int capacity = liveCore != null ? liveCore.storageCapacity : sector.info.storageCapacity;
            ItemSeq storedItems = new ItemSeq();
            if(liveCore != null && content != null){
                for(Item item : content.items()){
                    int amt = liveCore.items.get(item);
                    if(amt > 0) storedItems.set(item, amt);
                }
            }else{
                ItemSeq secItems = sector.items();
                for(ItemStack stack : secItems){
                    if(stack.amount > 0) storedItems.set(stack.item, stack.amount);
                }
            }

            c.add("[accent]核心库存 [lightgray](单项上限 " + UI.formatAmount(Math.max(0, capacity)) + ")").left().padTop(4f).row();
            c.table(Styles.black6, storeCard -> {
                storeCard.left().margin(8f);
                if(storedItems.total <= 0){
                    storeCard.add("[lightgray]当前无库存物品").left();
                }else{
                    int col = 0;
                    for(ItemStack stack : storedItems){
                        if(stack.amount <= 0) continue;
                        storeCard.image(stack.item.uiIcon).size(18f).padRight(3f);
                        storeCard.add("[white]" + UI.formatAmount(stack.amount)).left().padRight(10f);
                        if(++col % 4 == 0){
                            storeCard.row();
                        }
                    }
                }
            }).growX().row();

            // Section 3: Detailed per-item production, consumption, local net, and cross-sector net (per minute)
            java.util.List<ResourceFlowEntry> flows = collectSectorResourceFlows(sector);
            c.add("[accent]物资产出、消耗与净产量明细 [lightgray](每分钟)").left().padTop(4f).row();
            c.table(Styles.black6, flowCard -> {
                flowCard.left().margin(8f);
                flowCard.defaults().left().padBottom(4f);
                if(flows.isEmpty()){
                    flowCard.add("[lightgray]当前无活跃物资产出、消耗或跨区运输").left();
                }else{
                    flowCard.add("[lightgray]物资").minWidth(76f).padRight(8f);
                    flowCard.add("[lightgray]产出").minWidth(64f).padRight(8f);
                    flowCard.add("[lightgray]消耗").minWidth(64f).padRight(8f);
                    flowCard.add("[lightgray]本地净产").minWidth(74f).padRight(8f);
                    flowCard.add("[lightgray]跨区净收发").minWidth(74f).row();

                    for(int i = 0; i < flows.size(); i++){
                        ResourceFlowEntry e = flows.get(i);
                        long grossMin = Math.round(e.localGrossPerSec * 60f);
                        long consMin = Math.round(e.localConsPerSec * 60f);
                        long crossMin = Math.round(e.crossNetPerSec() * 60f);

                        flowCard.table(ic -> {
                            ic.left();
                            ic.image(e.item.uiIcon).size(18f).padRight(4f);
                            ic.add("[white]" + e.item.localizedName).left();
                        }).left().padRight(8f);
                        flowCard.add(grossMin > 0 ? "[#84f491]+" + UI.formatAmount(grossMin) : "[gray]0").left().padRight(8f);
                        flowCard.add(consMin > 0 ? "[scarlet]-" + UI.formatAmount(consMin) : "[gray]0").left().padRight(8f);
                        flowCard.add(formatSignedPerMin(e.localNetPerSec)).left().padRight(8f);
                        String crossStr = crossMin > 0
                            ? "[#84f491]+" + UI.formatAmount(crossMin) + "/分"
                            : (crossMin < 0 ? "[#ffd37f]-" + UI.formatAmount(-crossMin) + "/分" : "[gray]—");
                        flowCard.add(crossStr).left().row();
                    }
                }
            }).growX().row();
        }).minWidth(380f).maxHeight(520f);

        dialog.addCloseButton();
        dialog.show();
    }

    /**
     * Read-only 3-column conveyor status table rendered inside Resource View's detail dialog
     * (pairing is performed directly on in-world sender blocks via {@link #showConveyorSelect}).
     */
    private void buildResourceConveyorDetailSection(Table container, Sector sector){
        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(sector.planet);
        java.util.List<SectorPortRecord> ports = topo.getAllSectorPortsReadOnly(sector.id);
        if(ports.isEmpty()) return;

        container.add("[#a9d8ff]区块间传送带").left().padTop(4f).padBottom(4f).row();

        container.table(Styles.black6, grid -> {
            grid.left().margin(8f);
            grid.defaults().padBottom(4f);

            for(SectorPortRecord port : ports){
                SectorConveyorLink link = port.isSender
                    ? topo.getLinkForSender(sector.id, port.portNumber)
                    : topo.getLinkForReceiver(sector.id, port.portNumber);

                String portColor = port.tier == SectorConveyorMath.TIER_SENIOR ? "[#8ca9e8]" : "[accent]";
                String portName = portColor + (port.isSender ? "起始端" : "接收端") + port.portNumber;
                String relation = port.isSender ? "[lightgray]连接至" : "[lightgray]接收自";

                String targetLabel;
                if(link != null){
                    int peerSecId = port.isSender ? link.receiverSectorId : link.senderSectorId;
                    int peerPortNum = port.isSender ? link.receiverPortNumber : link.senderPortNumber;
                    String peerName = (peerSecId >= 0 && peerSecId < sector.planet.sectors.size)
                        ? sector.planet.sectors.get(peerSecId).name()
                        : ("区块" + peerSecId);
                    targetLabel = "[white]" + peerName + " · " + (port.isSender ? "接收端" : "起始端") + peerPortNum;
                }else{
                    targetLabel = "[lightgray]未配对";
                }

                grid.add(portName).left().minWidth(72f).padRight(14f);
                grid.add(relation).center().minWidth(52f).padRight(14f);
                grid.add(targetLabel).left().minWidth(170f).padRight(8f);

                if(link != null){
                    if(port.isSender && port.backpressurePaused){
                        grid.add("[scarlet]满载").left();
                    }else{
                        grid.table(flow -> buildPortFlowIcons(flow, port)).left();
                    }
                }else{
                    grid.add();
                }
                grid.row();
            }
        }).left().growX().padBottom(6f).row();
    }

    private void buildPortFlowIcons(Table flow, SectorPortRecord port){
        flow.left();
        if(content == null || port.itemRatesPerSec.isEmpty()){
            return;
        }
        int shown = 0;
        for(java.util.Map.Entry<Integer, Float> e : port.itemRatesPerSec.entrySet()){
            if(e.getValue() <= 0.001f) continue;
            Item item = content.item(e.getKey());
            if(item == null) continue;
            long perMin = Math.round(e.getValue() * 60f);
            flow.image(item.uiIcon).size(16f).padRight(2f);
            flow.add("[lightgray]" + UI.formatAmount(perMin) + "/分").padRight(6f);
            if(++shown >= 2) break;
        }
    }

    /** Draws a continuous glowing outer border outlining the ring of surrounding adjacent sectors around {@code center}. */
    private void drawAdjacentHexRingOutline(Planet planet, Sector center, Color color, float stroke, float length){
        if(center == null || center.planet != planet) return;
        float arad = (outlineRad + length) * planet.radius;

        for(Sector neighbor : center.near()){
            Ptile tile = neighbor.tile;
            if(tile == null || tile.corners == null || tile.tiles == null) continue;
            int count = tile.corners.length;
            tmpRingC.set(tile.v).scl(arad);

            for(int i = 0; i < count; i++){
                Ptile adj = i < tile.tiles.length ? tile.tiles[i] : null;
                if(adj != null && (adj.id == center.id || conveyorAdjacentIds.contains(adj.id))){
                    continue;
                }
                Corner curr = tile.corners[i];
                Corner next = tile.corners[(i + 1) % count];

                tmpRingA.set(curr.v).scl(arad);
                tmpRingB.set(next.v).scl(arad);

                float dstA = tmpRingA.dst(tmpRingC);
                float dstB = tmpRingB.dst(tmpRingC);
                tmpRingInA.set(tmpRingA).sub(tmpRingC).setLength(Math.max(0.001f, dstA - stroke)).add(tmpRingC);
                tmpRingInB.set(tmpRingB).sub(tmpRingC).setLength(Math.max(0.001f, dstB - stroke)).add(tmpRingC);

                planets.batch.quad(tmpRingA, tmpRingB, tmpRingInB, tmpRingInA, color);
            }
        }
    }

    /**
     * V3.3: Shows all receiver ports in the clicked adjacent sector for pairing with {@link #conveyorSelectSenderPort}.
     * Already-paired receivers are shown in gray and display a notice ("该接收端已被配对") when tapped.
     */
    private void showConveyorTargetReceivers(Sector targetSector){
        if(launchSector == null || targetSector == null || conveyorSelectSenderPort <= 0) return;

        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(launchSector.planet);
        SectorPortRecord senderRec = topo.getPort(launchSector.id, true, conveyorSelectSenderPort);
        SectorConveyorLink senderLink = topo.getLinkForSender(launchSector.id, conveyorSelectSenderPort);

        BaseDialog picker = new BaseDialog(targetSector.name() + " · 选择接收端");
        picker.cont.pane(list -> {
            list.left().top().margin(6f);

            if(!targetSector.hasBase()){
                list.add("[lightgray]该区块尚未占领，暂无可配对的接收端").pad(12f).row();
                return;
            }

            java.util.List<SectorPortRecord> receivers = topo.getSectorPorts(targetSector.id, false);
            if(receivers.isEmpty()){
                list.add("[lightgray]该区块暂无跨区块传送带接收端").pad(12f).row();
                return;
            }

            for(int i = 0; i < receivers.size(); i++){
                SectorPortRecord recv = receivers.get(i);
                SectorConveyorLink recvLink = topo.getLinkForReceiver(targetSector.id, recv.portNumber);
                String tierLabel = recv.tier == SectorConveyorMath.TIER_SENIOR ? "高级" : "初级";
                float effRate = senderRec != null ? Math.min(senderRec.transferRate, recv.transferRate) : recv.transferRate;
                long effPerMin = Math.round(effRate * 60f);

                if(recvLink != null){
                    boolean pairedToThis = recvLink.senderSectorId == launchSector.id
                        && recvLink.senderPortNumber == conveyorSelectSenderPort;
                    String grayLabel = "[gray]接收端" + recv.portNumber + " (" + tierLabel + ")  "
                        + (pairedToThis ? "已被配对 · 当前起始端" : "已被配对");

                    list.table(row -> {
                        row.left();
                        row.button(b -> {
                            b.left().marginLeft(8f);
                            b.image(Icon.lock).color(Pal.gray).size(18f).padRight(8f);
                            b.add(grayLabel).left();
                        }, Styles.cleart, () -> {
                            ui.showInfoFade("该接收端已被配对");
                        }).left().growX().height(42f);
                        if(pairedToThis){
                            row.button("[scarlet]断开", Icon.cancelSmall, Styles.cleart, () -> {
                                int res = cm.requestUnbind(launchSector.planet, launchSector.id, conveyorSelectSenderPort, true);
                                if(res == -1){
                                    ui.showInfoFade("已断开连接");
                                }else if(res == 2){
                                    ui.showInfoFade("已向房主请求断开连接");
                                }
                                picker.hide();
                                rebuildButtons();
                            }).width(88f).height(42f).padLeft(6f);
                        }
                    }).left().growX().padBottom(4f).row();
                }else{
                    String availLabel = "[white]接收端" + recv.portNumber + " (" + tierLabel + ")  [lightgray]" + UI.formatAmount(effPerMin) + "/分";
                    list.button(availLabel, Icon.link, Styles.cleart, () -> {
                        if(senderLink != null){
                            cm.requestUnbind(launchSector.planet, launchSector.id, conveyorSelectSenderPort, true);
                        }
                        int res = cm.requestBind(
                            launchSector.planet,
                            launchSector.id,
                            conveyorSelectSenderPort,
                            targetSector.id,
                            recv.portNumber
                        );
                        if(res == 1){
                            ui.showInfoFade("已配对至 " + targetSector.name() + " · 接收端" + recv.portNumber);
                            picker.hide();
                            PlanetDialog.this.hide();
                        }else if(res == 2){
                            ui.showInfoFade("已向房主请求建立连接");
                            picker.hide();
                            PlanetDialog.this.hide();
                        }else{
                            ui.showInfoFade("该接收端已被配对");
                            picker.hide();
                            showConveyorTargetReceivers(targetSector);
                        }
                    }).left().growX().height(42f).padBottom(4f).row();
                }
            }
        }).minWidth(360f).maxHeight(380f);
        picker.addCloseButton();
        picker.show();
    }

    // ==================== V3.9 CROSS-SECTOR TROOP AIRLIFT UI ====================

    private boolean isTroopViewUnlocked(){
        if(debugSelect) return true;
        return (Blocks.lowTroopLaunchPad != null && Blocks.lowTroopLaunchPad.unlockedNow())
            || (Blocks.highTroopLaunchPad != null && Blocks.highTroopLaunchPad.unlockedNow())
            || (Blocks.specialTroopLaunchPad != null && Blocks.specialTroopLaunchPad.unlockedNow());
    }

    /** V3.9 Troop Airlift View bottom panel: strictly sector name + garrison CP/units + in-flight summary. */
    private void buildTroopSectorPanel(Table stable, Sector sector){
        stable.add("[accent]" + sector.name() + (debugSelect && (sector.info.name != null || sector.preset != null) ? " [lightgray](" + sector.id + ")" : "")).pad(4f).row();
        stable.image().color(Pal.accent).fillX().height(3f).pad(3f).row();

        SectorTroopAirliftManager tm = SectorTroopAirliftManager.get();
        SectorGarrisonSnapshot gar = tm.getSectorGarrison(sector);
        boolean beachhead = tm.hasFriendlyBeachhead(sector);
        java.util.ArrayList<TroopAirliftMission> missions = tm.getMissionsInvolvingSector(sector);
        java.util.List<PendingArrivalBatch> pending = tm.getPlanetState(sector.planet).getPendingArrivals(sector.id);

        if(sector.hasBase() || beachhead){
            String statusTag = beachhead ? "[#ffd37f]抢滩驻军（无核心）[]" : "[#84f491]己方驻防区块[]";
            stable.add(statusTag).padTop(2f).row();
            String cpColor = gar.usedCp > gar.commandCap ? "[scarlet]" : "[accent]";
            stable.add("指挥点数：" + cpColor + gar.usedCp + " / " + gar.commandCap + "[] 点  [lightgray](" + gar.livingUnits + " 台 · " + gar.formationCount + " 编队)").pad(3f).row();
        }else{
            boolean unreasonable = sector.preset != null && sector.preset.difficulty == 13;
            ThreatTier tt = TroopAirliftMath.classifyThreat(sector.threat, unreasonable);
            String stateStr = sector.hasEnemyBase() ? "[scarlet]敌方区块[]" : "[lightgray]未占领区块[]";
            stable.add(stateStr + " ｜ 防空威胁：[accent]" + tt.displayName + "[]").pad(4f).row();
        }

        if(!pending.isEmpty()){
            int pUnits = 0, pCp = 0;
            for(int i = 0; i < pending.size(); i++){
                pUnits += pending.get(i).livingCount();
                pCp += pending.get(i).livingCp();
            }
            stable.add("[#84f491]待展开抵达：" + pending.size() + " 批（" + pUnits + " 台 / " + pCp + " 点）").pad(2f).row();
        }

        if(!missions.isEmpty()){
            int inCount = 0, outCount = 0;
            float soonestIn = Float.MAX_VALUE;
            for(int i = 0; i < missions.size(); i++){
                TroopAirliftMission m = missions.get(i);
                if(m.targetSectorId == sector.id){
                    inCount++;
                    if(m.remainingSeconds() < soonestIn) soonestIn = m.remainingSeconds();
                }else{
                    outCount++;
                }
            }
            String inStr = inCount > 0 ? ("驶入 " + inCount + " 批（最近 " + Math.max(0, (int)Math.ceil(soonestIn)) + "s）") : "";
            String outStr = outCount > 0 ? ("驶出 " + outCount + " 批") : "";
            String sep = (!inStr.isEmpty() && !outStr.isEmpty()) ? " ｜ " : "";
            stable.add("[accent]在途航线：" + inStr + sep + outStr).pad(3f).row();
        }else if(!sector.hasBase() && !beachhead && pending.isEmpty()){
            stable.add("[lightgray]暂无在途调兵批次").pad(2f).row();
        }
    }

    /** V3.9 Troop Airlift View detail dialog opened from the right-bottom "详情" button. */
    private void showTroopSectorDetails(Sector sector){
        if(sector == null) return;
        SectorTroopAirliftManager tm = SectorTroopAirliftManager.get();
        SectorGarrisonSnapshot gar = tm.getSectorGarrison(sector);
        java.util.ArrayList<TroopAirliftMission> missions = tm.getMissionsInvolvingSector(sector);
        java.util.List<PendingArrivalBatch> pending = tm.getPlanetState(sector.planet).getPendingArrivals(sector.id);

        BaseDialog dlg = new BaseDialog(sector.name() + " · 调兵与驻军详情");
        dlg.addCloseButton();
        dlg.cont.pane(t -> {
            t.top().left().defaults().left().growX().padBottom(6f);

            t.table(Tex.button, card -> {
                card.left().defaults().left().pad(2f);
                card.add("[accent]指挥点数与核心构成").row();
                card.add("指挥上限：[accent]" + gar.commandCap + " 点[]（基础 20 + 小核 " + gar.shardCores
                    + "×20 + 中核 " + gar.foundationCores + "×30 + 大核 " + gar.nucleusCores + "×40）").wrap().width(440f).row();
                card.add("当前驻军占用：[accent]" + gar.usedCp + " / " + gar.commandCap + " 点[] ｜ 存活单位 " + gar.livingUnits + " 台 ｜ 编队 " + gar.formationCount + " 支").row();
                String waterStr = gar.deepWaterState == 1 ? "[#84f491]有深水[]" : (gar.deepWaterState == 0 ? "[scarlet]无深水（海军无法落地）[]" : "[lightgray]未勘测[]");
                card.add("深水地形勘测：" + waterStr).row();
            }).growX().row();

            t.table(Tex.button, tiers -> {
                tiers.left().defaults().left().pad(2f);
                tiers.add("[accent]各阶驻军分布").row();
                tiers.add("核心机(0点)：" + gar.tierCounts[0]
                    + "  ｜  T1(1点)：" + gar.tierCounts[1]
                    + "  ｜  T2(2点)：" + gar.tierCounts[2]).row();
                tiers.add("T3(4点)：" + gar.tierCounts[3]
                    + "  ｜  T4(10点)：" + gar.tierCounts[4]
                    + "  ｜  T5(20点)：" + gar.tierCounts[5]).row();
            }).growX().row();

            if(!pending.isEmpty()){
                t.add("[accent]已抵达待展开批次（切入区块后生效）").padTop(4f).row();
                for(int i = 0; i < pending.size(); i++){
                    PendingArrivalBatch b = pending.get(i);
                    String originName = (b.originSectorId >= 0 && b.originSectorId < sector.planet.sectors.size)
                        ? sector.planet.sectors.get(b.originSectorId).name() : ("#" + b.originSectorId);
                    t.table(Tex.button, r -> {
                        r.left();
                        r.add((b.launchMode == LaunchMode.BEACHHEAD ? "[scarlet][抢滩][] " : "[#84f491][常规][] ")
                            + "来自 " + originName + "：" + b.livingCount() + " 台 / " + b.livingCp() + " 点").left();
                    }).growX().row();
                }
            }

            t.add("[accent]相关在途调兵航线（" + missions.size() + " 批）").padTop(4f).row();
            if(missions.isEmpty()){
                t.add("[lightgray]当前无驶向或驶离该区块的在途批次。").row();
            }else{
                for(int i = 0; i < missions.size(); i++){
                    TroopAirliftMission m = missions.get(i);
                    String fromName = (m.originSectorId >= 0 && m.originSectorId < sector.planet.sectors.size)
                        ? sector.planet.sectors.get(m.originSectorId).name() : ("#" + m.originSectorId);
                    String toName = (m.targetSectorId >= 0 && m.targetSectorId < sector.planet.sectors.size)
                        ? sector.planet.sectors.get(m.targetSectorId).name() : ("#" + m.targetSectorId);
                    String dir = m.targetSectorId == sector.id ? "[#84f491]驶入[]" : "[accent]驶出[]";
                    String modeStr = m.launchMode == LaunchMode.BEACHHEAD ? "抢滩" : (m.routeMode == RouteMode.SAFE ? "安全航道" : "快速航道");
                    t.table(Tex.button, r -> {
                        r.left().defaults().left().pad(2f);
                        r.add(dir + " " + fromName + " → " + toName + "（" + modeStr + " · " + m.hops() + " 跳）").row();
                        r.add("[lightgray]载荷：" + m.livingUnitCount() + " 台 / " + m.livingCp() + " 点 ｜ 剩余时间：[accent]"
                            + Math.max(0, (int)Math.ceil(m.remainingSeconds())) + " 秒[]").row();
                    }).growX().row();
                }
            }
        }).width(480f).maxHeight(520f);
        dlg.show();
    }

    /** V3.9: Pre-launch route selection, AA risk preview, naval warning, and lock/launch dialog. */
    private void showTroopLaunchConfirmDialog(Sector target){
        if(launchSector == null || activeTroopPad == null || target == null) return;
        final Sector origin = launchSector;
        final TroopLaunchPadBuild pad = activeTroopPad;
        final PadTier padTier = pad.padTier();
        final LaunchMode lMode = target.hasBase() ? LaunchMode.REGULAR : LaunchMode.BEACHHEAD;

        if(lMode == LaunchMode.BEACHHEAD && !padTier.allowBeachhead){
            ui.showInfoFade(padTier.displayName + "禁止执行抢滩登陆");
            return;
        }

        SectorTroopAirliftManager tm = SectorTroopAirliftManager.get();
        final java.util.HashMap<Integer, SectorNodeInfo> graph = tm.buildPlanetGraph(origin.planet);
        final int[] safeRoute = lMode == LaunchMode.REGULAR
            ? TroopAirliftMath.findRoute(graph, origin.id, target.id, RouteMode.SAFE) : null;
        final int[] fastRoute = lMode == LaunchMode.REGULAR
            ? TroopAirliftMath.findRoute(graph, origin.id, target.id, RouteMode.FAST)
            : new int[]{origin.id, target.id};

        if(fastRoute == null || fastRoute.length < 2){
            ui.showInfoFade("无法计算前往 " + target.name() + " 的有效航线");
            return;
        }

        final RouteMode[] chosenRouteMode = new RouteMode[]{
            (lMode == LaunchMode.REGULAR && safeRoute != null) ? RouteMode.SAFE : RouteMode.FAST
        };

        BaseDialog dlg = new BaseDialog("跨区块调兵航线确认 · " + origin.name() + " → " + target.name());
        dlg.addCloseButton();

        Table contentTable = new Table();
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            contentTable.clear();
            contentTable.top().left().defaults().left().growX().padBottom(6f);

            // Reserved custom launch preview banner artwork slot
            TroopAirliftAssets.addOptionalUiArtSlot(contentTable, TroopAirliftAssets.UI_TROOP_PREVIEW_BANNER, 450f, 52f);

            int[] activeRoute = (lMode == LaunchMode.REGULAR && chosenRouteMode[0] == RouteMode.SAFE && safeRoute != null)
                ? safeRoute : fastRoute;
            int hops = Math.max(1, activeRoute.length - 1);
            int cp = pad.boardedCp();
            float minSpd = TroopAirliftMath.computeMinPayloadSpeed(pad.boardedUnits);
            float travelSec = TroopAirliftMath.computeTotalTravelSeconds(padTier, hops, minSpd, lMode);
            double distMult = TroopAirliftMath.distanceMultiplier(hops);
            String payloadErr = TroopAirliftMath.validatePayload(padTier, lMode, pad.boardedUnits);
            RouteRiskPreview risk = TroopAirliftMath.previewRouteRisk(graph, activeRoute, pad.boardedUnits);

            // Mode & route selector
            contentTable.table(Tex.button, top -> {
                top.left().defaults().left().pad(2f);
                top.add("发射台：[accent]" + padTier.displayName + "[] ｜ 模式："
                    + (lMode == LaunchMode.BEACHHEAD ? "[scarlet]抢滩登陆（消耗×2 / 耗时×2 / 上限×0.5）[]" : "[#84f491]常规调动[]")).row();

                if(lMode == LaunchMode.REGULAR){
                    top.table(btns -> {
                        btns.left().defaults().height(40f).padRight(8f);
                        btns.button("安全航道" + (safeRoute != null ? (" (" + (safeRoute.length - 1) + "跳)") : " (不可达)"),
                            Styles.flatTogglet, () -> {
                                if(safeRoute != null){
                                    chosenRouteMode[0] = RouteMode.SAFE;
                                    rebuild[0].run();
                                }
                            }).width(205f).checked(chosenRouteMode[0] == RouteMode.SAFE).disabled(safeRoute == null);
                        btns.button("快速航道 (" + (fastRoute.length - 1) + "跳)",
                            Styles.flatTogglet, () -> {
                                chosenRouteMode[0] = RouteMode.FAST;
                                rebuild[0].run();
                            }).width(205f).checked(chosenRouteMode[0] == RouteMode.FAST);
                    }).padTop(4f).row();
                    if(safeRoute == null){
                        top.add("[lightgray]安全航道已置灰：不存在全程经由己方未交战区块的连通路径。").wrap().width(430f).row();
                    }
                }else{
                    top.add("[lightgray]抢滩目标为直接相邻区块（1 跳），接管无核心区块时将自动附身最高阶单位。").wrap().width(430f).row();
                }
            }).growX().row();

            // Travel time & AA risk preview
            contentTable.table(Tex.button, mid -> {
                mid.left().defaults().left().pad(2f);
                mid.add("[accent]航程与防空风险预估[]（起飞即锁定航线，中途不改道）").row();
                mid.add("航线跳数：[accent]" + hops + " 跳[] ｜ 距离倍率：[accent]" + Strings.fixed((float)distMult, 2)
                    + "×[] ｜ 预计总耗时：[accent]" + Strings.fixed(travelSec, 1) + " 秒[]").row();
                if(risk.hostileSectorCount == 0){
                    mid.add("[#84f491]沿途无敌方或交战区块，预计防空损耗：0%（无击落风险）").row();
                }else{
                    String riskColor = risk.hasShootdownRisk ? "[scarlet]" : "[accent]";
                    String tierName = risk.highestThreat != null ? risk.highestThreat.displayName : "未知";
                    mid.add(riskColor + "沿途飞越 " + risk.hostileSectorCount + " 个危险区块（最高威胁：" + tierName
                        + "），预计血量损耗：" + Math.round(risk.predictedHpLossRatio * 100f) + "%[]").row();
                    mid.add(risk.hasShootdownRisk
                        ? ("[scarlet]警告：存在空中击落风险（预估击毁 " + risk.predictedCasualties + " / " + risk.totalUnits + " 台），幸存者将承受 45 秒轨道受创！")
                        : "[#ffd37f]受防空打击后幸存单位落地将附加 45 秒「轨道受创」（受治疗-50%、护甲-20%）。").wrap().width(430f).row();
                }

                // Non-blocking red warning if payload contains naval units & target has no deep water
                boolean hasNaval = false;
                for(int i = 0; i < pad.boardedUnits.size(); i++){
                    if(pad.boardedUnits.get(i).naval){
                        hasNaval = true;
                        break;
                    }
                }
                if(hasNaval && tm.isKnownWithoutDeepWater(target)){
                    mid.add("[scarlet]警告：舱内包含海军单位，但目标区块已勘测无深水区域！海军降落时将因无合法水域落点而爆炸销毁（非阻塞提示，仍可发射）。").wrap().width(430f).row();
                }

                if(payloadErr != null){
                    mid.add("[scarlet]当前舱内载荷校验未通过：" + payloadErr).wrap().width(430f).row();
                }
            }).growX().row();

            // Material consumption comparison (conveyor-supplied)
            contentTable.table(Tex.button, costCard -> {
                costCard.left().defaults().left().pad(2f);
                costCard.add("[accent]发射所需材料（传送带实体供料）").row();
                java.util.LinkedHashMap<String, Integer> req = TroopAirliftMath.computeLaunchCost(
                    padTier, Math.max(cp, padTier.fixedLaunchCost ? 20 : 0), hops, lMode
                );
                for(java.util.Map.Entry<String, Integer> e : req.entrySet()){
                    Item item = content.item(e.getKey());
                    int need = e.getValue().intValue();
                    int have = item != null ? pad.items.get(item) : 0;
                    String col = have >= need ? "[#84f491]" : "[scarlet]";
                    costCard.table(r -> {
                        r.left();
                        if(item != null) r.image(item.uiIcon).size(20f).padRight(6f);
                        r.add((item != null ? item.localizedName : e.getKey()) + "：" + col + have + " / " + need);
                    }).row();
                }
            }).growX().row();

            // Confirm buttons
            contentTable.table(btns -> {
                btns.left().defaults().height(46f).padRight(8f);
                btns.button("锁定航线", Icon.ok, () -> {
                    pad.lockRoute(target.id, lMode, chosenRouteMode[0], activeRoute);
                    ui.showInfoFade("已锁定航线 → " + target.name() + "（" + hops + " 跳），请等待传送带供料与电力蓄能完成后起飞");
                    dlg.hide();
                    PlanetDialog.this.hide();
                }).width(210f);

                btns.button("锁定并立即发射", Icon.play, () -> {
                    pad.lockRoute(target.id, lMode, chosenRouteMode[0], activeRoute);
                    if(pad.tryLaunch()){
                        dlg.hide();
                        PlanetDialog.this.hide();
                    }else{
                        rebuild[0].run();
                    }
                }).width(210f);
            }).growX().row();
        };

        rebuild[0].run();
        dlg.cont.pane(contentTable).width(490f).maxHeight(560f);
        dlg.show();
    }

    public enum Mode{
        /** Look around for existing sectors. Can only deploy. */
        look,
        /** Select a sector for some purpose. */
        select,
        /** Launch between planets. */
        planetLaunch,
        /** Select an adjacent sector's receiver port for a cross-sector conveyor sender. */
        conveyorSelect,
        /** V3.9: Select a cross-sector troop airlift destination & lock route for a TroopLaunchPad. */
        troopLaunchSelect
    }
}
