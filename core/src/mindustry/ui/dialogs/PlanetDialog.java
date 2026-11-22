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

        rebuildButtons();
        mode = look;
        state.otherCamPos = null;
        selected = hovered = launchSector = null;
        powerLinkSource = null;
        launching = false;
        SectorPowerGridManager.get().syncCurrentSectorHubStatus();
        SectorConveyorManager.get().syncCurrentSectorPorts();

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
            addTech();
        }else{
            addBack();
            buttons.add().growX();
            buttons.add(sectorTop).minWidth(230f);
            buttons.add().growX();
            addTech();
        }
    }

    void addBack(){
        buttons.button("@back", Icon.left, this::hide).size(200f, 54f).pad(2).bottom();
    }

    void addTech(){
        buttons.button("@techtree", Icon.tree, () -> ui.research.show()).size(200f, 54f).visible(() -> mode == look).pad(2).bottom();
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
        powerLinkSource = null;

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
        powerLinkSource = null;
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
        if(mode == select) return sector.hasBase() && launchSector != null && sector.planet == launchSector.planet;

        //sectors with addStartingItems = true can't be landed on, as they override the core and items of the interplanetary accelerator
        //at the moment, this is only true of Ground Zero in vanilla
        //TODO: maybe relax these restrictions and add better support for these sorts of "tutorial" starting sectors later
        if(mode == planetLaunch && (sector.hasBase() || (sector.preset != null && sector.preset.addStartingItems))) return false;

        if(sector.planet.generator == null) return false;

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

                    if(selected != null && selected != sec && selected.hasBase()){
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

            //V2.5: Render cross-sector power grid connection lines in Power View
            if(powerViewMode && mode == look){
                SectorPowerGridManager pm = SectorPowerGridManager.get();
                PlanetPowerTopology topo = pm.getTopology(planet);
                for(SectorLink link : topo.getAllLinks()){
                    if(link.a < 0 || link.a >= planet.sectors.size || link.b < 0 || link.b >= planet.sectors.size) continue;
                    Sector sa = planet.sectors.get(link.a);
                    Sector sb = planet.sectors.get(link.b);
                    if(!pm.hasHub(sa) || !pm.hasHub(sb)) continue;

                    GridAggregate agg = topo.computeAggregate(sa.id);
                    Color cFrom = agg.overloaded
                        ? Tmp.c2.set(Pal.remove).a(state.uiAlpha)
                        : Tmp.c2.set(Pal.powerLight).a(state.uiAlpha);
                    Color cTo = agg.overloaded
                        ? Tmp.c3.set(Pal.accent).a(state.uiAlpha * 0.85f)
                        : Tmp.c3.set(Pal.accent).a(state.uiAlpha * 0.95f);

                    planets.drawArcLine(planet, sa.tile.v, sb.tile.v, cFrom, cTo, 0.22f, 65f, 22, 0.008f);
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
                for(SectorConveyorLink link : ctopo.getAllLinks()){
                    if(link.senderSectorId < 0 || link.senderSectorId >= planet.sectors.size
                        || link.receiverSectorId < 0 || link.receiverSectorId >= planet.sectors.size){
                        continue;
                    }
                    Sector sa = planet.sectors.get(link.senderSectorId);
                    Sector sb = planet.sectors.get(link.receiverSectorId);
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
                    planets.drawArc(planet, sa.tile.v, sb.tile.v, cFrom, cTo, 0.30f, 72f, 24);
                }
            }
        }
    }

    @Override
    public void renderProjections(Planet planet){
        float iw = 64f/4f;

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

            //V2.5: Glowing yellow center dot for every sector containing a Surge Sector Power Hub in Power View
            if(powerViewMode && mode == look && state.uiAlpha > 0.01f && SectorPowerGridManager.get().hasHub(sec)){
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

            //V2.8: Glowing cyan node dot for sectors with cross-sector conveyor ports in Resource View
            if(resourceViewMode && mode == look && state.uiAlpha > 0.01f && SectorConveyorManager.get().hasAnyPort(sec)){
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
        }

        Draw.reset();

        if(hovered != null && state.uiAlpha > 0.01f){
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
        if(mode == select) return planet == state.planet;
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

                        //V2.5: Power View click-to-link / click-to-unlink interaction on yellow hub dots
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
                                    if(res == 1){
                                        ui.showInfoFade("已连接跨区块电网: " + powerLinkSource.name() + " ↔ " + hovered.name());
                                    }else if(res == -1){
                                        ui.showInfoFade("已断开跨区块电网连接: " + powerLinkSource.name() + " × " + hovered.name());
                                    }else if(res == 2){
                                        ui.showInfoFade("已向房主请求变更跨区块电网连线");
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
                                if(hovered != null && (canSelect(hovered) || debugSelect)){
                                    selected = hovered;
                                }
                                if(selected != null){
                                    updateSelected();
                                }
                                return;
                            }
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
            t.label(() ->
                mode == select ? "@sectors.select" :
                mode == planetLaunch ? "@sectors.launchselect" :
                powerViewMode ? (powerLinkSource != null
                    ? "电力视野 · 已选起点 [" + powerLinkSource.name() + "] — 点击相邻黄色光点建立/断开连接"
                    : "电力视野 · 点击区块中心黄色光点，再点击相邻区块光点连接电网") :
                resourceViewMode ? "资源视野 · 点击区块查看区块间传送带明细并配对相邻端口（与电力视野互斥）" :
                ""
            ).style(Styles.outlineLabel).color(Pal.accent);
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
                    ui.showInfoFade("需先在科技树解锁「巨浪区块电力枢纽」以开启电力视野");
                    return;
                }
                powerViewMode = !powerViewMode;
                powerLinkSource = null;
                if(powerViewMode){
                    // Mutual exclusion: Power View and Resource View cannot be open simultaneously
                    resourceViewMode = false;
                    SectorPowerGridManager.get().syncCurrentSectorHubStatus();
                    lastPowerGridRevision = SectorPowerGridManager.get().getUiRevision();
                    ui.showInfoFade("已开启电力视野：点击相邻黄色光点连接区块间电网");
                }
                updateSelected();
            }).margin(12f).size(208f, 40f).padTop(6f).checked(b -> powerViewMode).visible(() -> mode == look).row();
            t.button("资源视野", Icon.boxSmall, Styles.flatTogglet, () -> {
                if(!resourceViewMode && !isResourceViewUnlocked()){
                    ui.showInfoFade("需先解锁任意「区块间传送带」端点建筑以开启资源视野");
                    return;
                }
                resourceViewMode = !resourceViewMode;
                if(resourceViewMode){
                    // Mutual exclusion: Power View and Resource View cannot be open simultaneously
                    powerViewMode = false;
                    powerLinkSource = null;
                    SectorConveyorManager.get().syncCurrentSectorPorts();
                    lastConveyorRevision = SectorConveyorManager.get().getUiRevision();
                    ui.showInfoFade("已开启资源视野：选中区块查看端口详情并进行相邻 1 对 1 配对");
                }
                updateSelected();
            }).margin(12f).size(208f, 40f).padTop(4f).checked(b -> resourceViewMode).visible(() -> mode == look).row();
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
        }).visible(() -> mode != select),

        new Table(c -> expandTable = c)).grow();
        rebuildExpand();
    }

    void rebuildExpand(){
        Table c = expandTable;
        c.clear();
        c.visible(() -> !(graphics.isPortrait() && mobile) && mode != planetLaunch);
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
                    if(!(hovered.preset == null && !hovered.planet.allowLaunchToNumbered)){
                        if(mode == planetLaunch){
                            tx.append("[gray]").append(Iconc.cancel);
                        }else if(hovered.planet.generator != null && mode != select){
                            hovered.planet.generator.getLockedText(hovered, tx);
                        }
                    }
                }else{
                    tx.append("[accent][[ [white]").append(hovered.name()).append("[accent] ]");
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

    void displayItems(Table c, ObjectMap<Item, ExportStat> stats, String name){
        displayItems(c, stats, name, t -> {});
    }

    void displayItems(Table c, ObjectMap<Item, ExportStat> stats, String name, Cons<Table> builder){
        Table t = new Table().left();

        int i = 0;
        for(var item : content.items()){
            var stat = stats.get(item);
            if(stat == null) continue;
            int total = (int)(stat.mean * 60);
            if(total > 1){
                t.image(item.uiIcon).padRight(3);
                t.add(UI.formatAmount(total) + " " + Core.bundle.get("unit.perminute")).color(Color.lightGray).padRight(3);
                if(++i % 3 == 0){
                    t.row();
                }
            }
        }

        if(t.getChildren().any()){
            c.defaults().left();
            c.add(name).row();
            builder.get(c);
            c.add(t).padLeft(10f).row();
        }
    }

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

            //production
            displayItems(c, sector.info.production, "@sectors.production");

            //export
            displayItems(c, sector.info.export, "@sectors.export", t -> {
                if(sector.info.destination != null && sector.info.destination.hasBase()){
                    String ic = sector.info.destination.iconChar();
                    t.add(Iconc.rightOpen + " " + (ic == null || ic.isEmpty() ? "" : ic + " ") + sector.info.destination.name()).padLeft(10f).row();
                }
            });

            //import
            if(sector.hasBase()){
                displayItems(c, sector.info.imports, "@sectors.import", t -> {
                    sector.info.eachImport(sector.planet, other -> {
                        String ic = other.iconChar();
                        t.add(Iconc.rightOpen + " " + (ic == null || ic.isEmpty() ? "" : ic + " ") + other.name()).padLeft(10f).row();
                    });
                });
            }

            ItemSeq items = sector.items();

            //stored resources
            if(sector.hasBase() && items.total > 0){

                c.add("@sectors.stored").left().row();
                c.table(t -> {
                    t.left();

                    t.table(res -> {

                        int i = 0;
                        for(ItemStack stack : items){
                            res.image(stack.item.uiIcon).padRight(3);
                            res.add(UI.formatAmount(Math.max(stack.amount, 0))).color(Color.lightGray);
                            if(++i % 4 == 0){
                                res.row();
                            }
                        }
                    }).padLeft(10f);
                }).left().row();
            }

            //V2.8: Cross-sector conveyor port details inside sector stats dialog
            if(sector.hasBase() && (resourceViewMode || SectorConveyorManager.get().hasAnyPort(sector))){
                c.add("[#a9d8ff]区块间传送带端口明细").left().padTop(8f).row();
                buildResourceConveyorSummary(c, sector, true, () -> {
                    dialog.hide();
                    showStats(sector);
                    updateSelected();
                });
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

        if(sector == null){
            stable.clear();
            stable.visible = false;
            return;
        }
        stable.visible = true;

        float x = stable.getX(Align.center), y = stable.getY(Align.center);
        stable.clear();
        stable.background(Styles.black6);

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

        //V2.5: Cross-Sector Power Grid summary card in Power View (or whenever selected sector has a hub)
        if(mode == look && (powerViewMode || (!resourceViewMode && SectorPowerGridManager.get().hasHub(sector)))){
            buildPowerGridSummary(stable, sector);
        }

        //V2.8: Global Resource View cross-sector conveyor summary & 1-to-1 port pairing card
        if(mode == look && (resourceViewMode || (!powerViewMode && SectorConveyorManager.get().hasAnyPort(sector)))){
            buildResourceConveyorSummary(stable, sector, resourceViewMode, this::updateSelected);
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

            boolean noCandidate = hasNoCandidate(sector);
            boolean guestNoPerm = net.isConnectedClient() && mode == look && !sector.isBeingPlayed() && (
                sector.hasBase()
                    ? !MultiSectorCoordinator.get().canLocalGuestSwitchSector()
                    : !MultiSectorCoordinator.get().canLocalGuestLaunchCore()
            );

            stable.button(
                mode == select ? "@sectors.select" :
                sector.isBeingPlayed() ? "@sectors.resume" :
                guestNoPerm ? "房主未开放权限" :
                sector.hasBase() ? "@sectors.go" :
                locked ? "@locked" :
                noCandidate ? "@sectors.nolaunchcandidate" :
                "@sectors.launch",
                (locked || guestNoPerm) ? Icon.lock : Icon.play, this::playSelected).growX().height(54f).minWidth(170f).padTop(4).disabled(locked || noCandidate || guestNoPerm);
        }

        stable.pack();
        stable.setPosition(x, y, Align.center);

        stable.act(0f);
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
        boolean hasHub = pm.hasHub(sector);

        stable.table(Styles.black6, card -> {
            card.left().margin(6f);
            if(!hasHub){
                card.add("[lightgray]本区块尚未建造「巨浪区块电力枢纽」").left().row();
                return;
            }

            GridAggregate agg = pm.getGridAggregate(sector);
            long prodSec = Math.round(agg.totalProduced * 60f);
            long needSec = Math.round(agg.totalNeeded * 60f);
            long netSec = prodSec - needSec;
            String netColor = netSec >= 0 ? "[#84f491]+" : "[scarlet]";
            int satPct = Math.round(agg.satisfaction * 100f);

            card.table(hdr -> {
                hdr.left();
                hdr.image(Icon.powerSmall).color(agg.overloaded ? Pal.remove : Pal.powerLight).size(18f).padRight(4f);
                hdr.add("[#ffd37f]区块间电网 [white](" + agg.sectorCount() + " 区块)" + (agg.overloaded ? " [scarlet][过载]" : "")).left();
            }).left().growX().padBottom(3f).row();

            card.add("[lightgray]总产出: [white]+" + UI.formatAmount(prodSec) + "/秒  [lightgray]总消耗: [white]-" + UI.formatAmount(needSec) + "/秒").left().row();
            card.add("[lightgray]净功率: " + netColor + UI.formatAmount(netSec) + "/秒  [lightgray]供电率: [white]" + satPct + "%").left().row();
            card.add("[lightgray]总储存: [white]" + UI.formatAmount((long)agg.totalBatteryStored) + " / " + UI.formatAmount((long)agg.totalBatteryCapacity)).left().padBottom(4f).row();

            card.add(new Bar(
                () -> "共享蓄电 " + (agg.totalBatteryCapacity > 0.01f ? Math.round(Mathf.clamp(agg.totalBatteryStored / agg.totalBatteryCapacity) * 100f) + "%" : "0%"),
                () -> agg.overloaded ? Pal.remove : Pal.powerBar,
                () -> agg.totalBatteryCapacity > 0.01f ? Mathf.clamp(agg.totalBatteryStored / agg.totalBatteryCapacity) : 0f
            )).growX().height(18f).padBottom(4f).row();

            if(powerViewMode){
                boolean isOrigin = powerLinkSource == sector;
                card.button(isOrigin ? "取消连线起点" : "设为连线起点", Icon.link, Styles.cleart, () -> {
                    powerLinkSource = isOrigin ? null : sector;
                    updateSelected();
                }).growX().height(34f).padBottom(2f).row();

                var linkedIds = pm.getTopology(sector.planet).getLinkedNeighbors(sector.id);
                if(!linkedIds.isEmpty()){
                    card.add("[lightgray]已连相邻区块:").left().padTop(2f).row();
                    for(int nid : linkedIds){
                        if(nid < 0 || nid >= sector.planet.sectors.size) continue;
                        Sector neighbor = sector.planet.sectors.get(nid);
                        card.table(linkRow -> {
                            linkRow.left();
                            linkRow.add("[accent]• " + neighbor.name()).left().growX();
                            linkRow.button("[scarlet]断开", Icon.cancelSmall, Styles.cleart, () -> {
                                pm.requestToggleLink(sector.planet, sector, neighbor);
                                updateSelected();
                            }).height(30f).minWidth(72f);
                        }).growX().row();
                    }
                }
            }
        }).growX().padTop(4f).padBottom(4f).row();
    }

    private boolean isResourceViewUnlocked(){
        if(debugSelect) return true;
        if(Blocks.basicSectorConveyorSender != null && Blocks.basicSectorConveyorSender.unlockedNow()) return true;
        if(Blocks.basicSectorConveyorReceiver != null && Blocks.basicSectorConveyorReceiver.unlockedNow()) return true;
        if(Blocks.advancedSectorConveyorSender != null && Blocks.advancedSectorConveyorSender.unlockedNow()) return true;
        if(Blocks.advancedSectorConveyorReceiver != null && Blocks.advancedSectorConveyorReceiver.unlockedNow()) return true;
        return !SectorConveyorManager.get().getTopology(state.planet).getPortSectorIds().isEmpty();
    }

    private void buildResourceConveyorSummary(Table container, Sector sector, boolean allowPairing, Runnable refreshAction){
        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(sector.planet);
        java.util.List<SectorPortRecord> ports = topo.getAllSectorPorts(sector.id);

        container.table(Styles.black6, card -> {
            card.left().margin(6f);

            int senderCount = topo.countSendersInSector(sector.id);
            int recvCount = topo.countReceiversInSector(sector.id);

            card.table(hdr -> {
                hdr.left();
                hdr.image(Icon.boxSmall).color(Pal.lancerLaser).size(18f).padRight(4f);
                hdr.add("[#a9d8ff]区块间传送带 [white](起始 " + senderCount + "/4 · 接收 " + recvCount + ")").left();
            }).left().growX().padBottom(4f).row();

            if(ports.isEmpty()){
                card.add("[lightgray]本区块尚未建造区块间传送带起始端或接收端").left().row();
                return;
            }

            for(SectorPortRecord port : ports){
                String tierTag = port.tier == SectorConveyorMath.TIER_SENIOR ? "[#8ca9e8]高级" : "[accent]初级";
                SectorConveyorLink link = port.isSender
                    ? topo.getLinkForSender(sector.id, port.portNumber)
                    : topo.getLinkForReceiver(sector.id, port.portNumber);

                String detailText;
                if(link != null){
                    int peerSecId = port.isSender ? link.receiverSectorId : link.senderSectorId;
                    int peerPortNum = port.isSender ? link.receiverPortNumber : link.senderPortNumber;
                    String peerName = (peerSecId >= 0 && peerSecId < sector.planet.sectors.size)
                        ? sector.planet.sectors.get(peerSecId).name()
                        : ("区块" + peerSecId);
                    String flow = SectorConveyorManager.formatItemFlowSummary(port);
                    String pauseTag = (port.isSender && port.backpressurePaused) ? " [scarlet][满载暂停]" : "";
                    if(port.isSender){
                        detailText = tierTag + "[white] 本区块起始端" + port.portNumber
                            + " 发送到 [" + peerName + "] 接收端" + peerPortNum
                            + " 的 " + flow + pauseTag
                            + " [lightgray](缓存 " + port.bufferedCount() + "/" + port.capacity + ")";
                    }else{
                        detailText = tierTag + "[white] 本区块接收端" + port.portNumber
                            + " 接收到来自 [" + peerName + "] 起始端" + peerPortNum
                            + " 的 " + flow
                            + " [lightgray](缓存 " + port.bufferedCount() + "/" + port.capacity + ")";
                    }
                }else{
                    detailText = tierTag + "[white] 本区块" + (port.isSender ? "起始端" : "接收端") + port.portNumber
                        + " [lightgray]· 未配对 (缓存 " + port.bufferedCount() + "/" + port.capacity + ")";
                }

                card.table(row -> {
                    row.left();
                    row.add(detailText).left().wrap().width(allowPairing ? 290f : 330f).padRight(6f);
                    if(allowPairing){
                        if(link != null){
                            row.button("[scarlet]断开", Icon.cancelSmall, Styles.cleart, () -> {
                                int res = cm.requestUnbind(sector.planet, sector.id, port.portNumber, port.isSender);
                                if(res == -1) ui.showInfoFade("已断开跨区块传送带链接");
                                else if(res == 2) ui.showInfoFade("已向房主请求断开跨区块传送带链接");
                                if(refreshAction != null) refreshAction.run();
                            }).height(30f).minWidth(68f);
                        }else{
                            row.button("[accent]配对", Icon.link, Styles.cleart, () -> {
                                showPortPairingDialog(sector, port, refreshAction);
                            }).height(30f).minWidth(68f);
                        }
                    }
                }).left().growX().padBottom(3f).row();
            }
        }).growX().padTop(4f).padBottom(4f).row();
    }

    private void showPortPairingDialog(Sector sector, SectorPortRecord sourcePort, @Nullable Runnable onChanged){
        SectorConveyorManager cm = SectorConveyorManager.get();
        java.util.List<SectorPortRecord> candidates = cm.getAvailableAdjacentTargets(sector, sourcePort.isSender);

        String roleTitle = sourcePort.isSender
            ? "为本区块起始端" + sourcePort.portNumber + " 选择相邻接收端"
            : "为本区块接收端" + sourcePort.portNumber + " 选择相邻起始端";

        BaseDialog picker = new BaseDialog(roleTitle);
        picker.cont.pane(list -> {
            list.left().top().margin(6f);
            if(candidates.isEmpty()){
                list.add("[lightgray]当前所有相邻已占领区块中均无空闲的" + (sourcePort.isSender ? "接收端" : "起始端") + "可配对。").wrap().width(420f).pad(10f);
                return;
            }
            for(SectorPortRecord target : candidates){
                if(target.sectorId < 0 || target.sectorId >= sector.planet.sectors.size) continue;
                Sector targetSec = sector.planet.sectors.get(target.sectorId);
                String tierName = target.tier == SectorConveyorMath.TIER_SENIOR ? "高级" : "初级";
                float effRate = Math.min(sourcePort.transferRate, target.transferRate);
                String label = "[" + targetSec.name() + "] " + tierName + (target.isSender ? "起始端" : "接收端") + target.portNumber
                    + "  [lightgray](限速 " + Strings.autoFixed(effRate, 0) + "个/秒 · 容量 " + target.capacity + ")";

                list.button(label, Icon.link, Styles.cleart, () -> {
                    int res = sourcePort.isSender
                        ? cm.requestBind(sector.planet, sector.id, sourcePort.portNumber, target.sectorId, target.portNumber)
                        : cm.requestBind(sector.planet, target.sectorId, target.portNumber, sector.id, sourcePort.portNumber);
                    if(res == 1){
                        ui.showInfoFade("已建立 1 对 1 跨区块传送带链接");
                    }else if(res == 2){
                        ui.showInfoFade("已向房主请求建立跨区块传送带链接");
                    }else{
                        ui.showInfoFade("无法配对：目标端口已被占用或区块不相邻");
                    }
                    picker.hide();
                    if(onChanged != null) onChanged.run();
                }).left().growX().height(42f).padBottom(4f).row();
            }
        }).minWidth(460f).maxHeight(360f);
        picker.addCloseButton();
        picker.show();
    }

    public enum Mode{
        /** Look around for existing sectors. Can only deploy. */
        look,
        /** Select a sector for some purpose. */
        select,
        /** Launch between planets. */
        planetLaunch
    }
}
