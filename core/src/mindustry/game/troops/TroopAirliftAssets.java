package mindustry.game.troops;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;

/**
 * Centralized UI / Building Sprite / Frame Animation Slot Registry for the
 * Serpulo Cross-Sector Troop Airlift System (v3.9).
 *
 * All custom building artwork, status icons, and non-procedural UI graphics/animations
 * are resolved through this registry using {@code Core.atlas.has(...)}.
 *
 * When custom PNG assets are NOT yet bundled, every slot falls back cleanly to safe
 * programmatic rendering or built-in Mindustry icons with zero missing-sprite errors.
 * When custom assets are dropped into {@code assets-raw/sprites/} using the exact keys
 * listed below, they activate automatically without modifying any logic code.
 *
 * ==================== RESERVED ASSET & ANIMATION SLOT TABLE ====================
 *
 * 1. Three Troop Launch Pads (prefix {@code <pad>} =
 *    {@code low-troop-launch-pad} / {@code high-troop-launch-pad} / {@code special-troop-launch-pad}):
 *    - {@code <pad>}                    : Static base sprite (3x3 / 4x4 / 5x5)
 *    - {@code <pad>-top}                : Static top deck / rim overlay
 *    - {@code <pad>-light}              : Ready / status light sprite
 *    - {@code <pad>-pod}                : Transport pod sprite
 *    - {@code <pad>-bay-0 .. N}         : Boarding bay hatch open/close animation frames
 *    - {@code <pad>-charge}             : Static power-charge glow overlay
 *    - {@code <pad>-charge-0 .. N}      : Power-charge energy ring animation frames
 *    - {@code <pad>-launch-0 .. N}      : Launch lift-off pad animation frames
 *    - {@code <pad>-pod-flame-0 .. N}   : Pod thruster flame animation frames
 *    - {@code <pad>-cooldown-0 .. N}    : Post-launch heat vent / cooldown animation frames
 *
 * 2. Status Effect Icons:
 *    - {@code status-landing-deploy}    : 4s "着陆展开" debuff icon
 *    - {@code status-orbital-trauma}    : 45s "轨道受创" debuff icon
 *
 * 3. Non-Procedural UI & UI Animation Slots:
 *    - {@code ui-troop-view-icon}       : PlanetDialog left-sidebar "调兵视野" button icon
 *    - {@code ui-airlift-pod-marker}    : PlanetDialog in-flight airlift marker sprite
 *    - {@code ui-airlift-beachhead-marker} : Beachhead target sector badge icon
 *    - {@code ui-airlift-target-marker} : Regular target sector badge icon
 *    - {@code ui-troop-pad-header}      : Pad configuration dialog header artwork banner
 *    - {@code ui-troop-bay-slot}        : Pad bay unit card custom slot frame
 *    - {@code ui-troop-launch-btn-0..N} : Animated launch button highlight frames
 *    - {@code ui-troop-preview-banner}  : Pre-launch risk preview dialog header banner
 *    - {@code ui-airlift-banner-bg}     : 60s HUD arrival countdown banner custom background
 *    - {@code ui-airlift-banner-anim-0..N} : 60s HUD arrival countdown scan/pulse frames
 *    - {@code ui-airlift-lost-icon}     : "编队迷失" alert icon
 */
public final class TroopAirliftAssets{

    // Status effect sprite keys
    public static final String STATUS_LANDING_DEPLOY = "status-landing-deploy";
    public static final String STATUS_ORBITAL_TRAUMA = "status-orbital-trauma";

    // UI static & animation slot keys
    public static final String UI_TROOP_VIEW_ICON = "ui-troop-view-icon";
    public static final String UI_AIRLIFT_POD_MARKER = "ui-airlift-pod-marker";
    public static final String UI_AIRLIFT_BEACHHEAD_MARKER = "ui-airlift-beachhead-marker";
    public static final String UI_AIRLIFT_TARGET_MARKER = "ui-airlift-target-marker";
    public static final String UI_TROOP_PAD_HEADER = "ui-troop-pad-header";
    public static final String UI_TROOP_BAY_SLOT = "ui-troop-bay-slot";
    public static final String UI_TROOP_LAUNCH_BTN_ANIM = "ui-troop-launch-btn";
    public static final String UI_TROOP_PREVIEW_BANNER = "ui-troop-preview-banner";
    public static final String UI_AIRLIFT_BANNER_BG = "ui-airlift-banner-bg";
    public static final String UI_AIRLIFT_BANNER_ANIM = "ui-airlift-banner-anim";
    public static final String UI_AIRLIFT_LOST_ICON = "ui-airlift-lost-icon";

    /** Maximum frame count probed for any "<prefix>-0..N" custom animation slot. */
    public static final int MAX_ANIM_FRAMES = 32;

    private TroopAirliftAssets(){
    }

    /** Returns true if a custom sprite with the given exact atlas key has been supplied. */
    public static boolean hasSprite(@Nullable String atlasKey){
        return atlasKey != null && Core.atlas != null && Core.atlas.has(atlasKey);
    }

    /** Finds a custom sprite if present in atlas, otherwise returns {@code fallback} (may be null). */
    public static @Nullable TextureRegion findOrFallback(@Nullable String atlasKey, @Nullable TextureRegion fallback){
        if(hasSprite(atlasKey)){
            return Core.atlas.find(atlasKey);
        }
        return fallback;
    }

    /**
     * Loads a sequential frame animation from {@code basePrefix + "-0"}, {@code basePrefix + "-1"}, ...
     * Returns an empty array if no custom frames have been placed in the atlas yet.
     */
    public static TextureRegion[] loadFrameSequence(@Nullable String basePrefix){
        if(basePrefix == null || Core.atlas == null) return new TextureRegion[0];
        Seq<TextureRegion> list = new Seq<>(TextureRegion.class);
        for(int i = 0; i < MAX_ANIM_FRAMES; i++){
            String key = basePrefix + "-" + i;
            if(Core.atlas.has(key)){
                list.add(Core.atlas.find(key));
            }else{
                break;
            }
        }
        return list.toArray();
    }

    /** Picks a frame by normalized progress [0..1], or null if {@code frames} is empty. */
    public static @Nullable TextureRegion pickProgressFrame(@Nullable TextureRegion[] frames, float progress01){
        if(frames == null || frames.length == 0) return null;
        int idx = Mathf.clamp((int)(Mathf.clamp(progress01) * frames.length), 0, frames.length - 1);
        return frames[idx];
    }

    /** Picks a looping frame using elapsed seconds and playback FPS, or null if {@code frames} is empty. */
    public static @Nullable TextureRegion pickLoopFrame(@Nullable TextureRegion[] frames, float timeSeconds, float fps){
        if(frames == null || frames.length == 0) return null;
        int idx = ((int)Math.floor(Math.max(0f, timeSeconds) * Math.max(1f, fps))) % frames.length;
        return frames[idx];
    }

    /** Returns a TextureRegionDrawable for {@code atlasKey} if installed, otherwise {@code fallback}. */
    public static Drawable drawableOrFallback(@Nullable String atlasKey, Drawable fallback){
        if(hasSprite(atlasKey)){
            return new TextureRegionDrawable(Core.atlas.find(atlasKey));
        }
        return fallback;
    }

    /**
     * Reserves a UI static artwork slot inside {@code parent}.
     * Renders the custom sprite when installed; occupies 0 space cleanly when absent.
     */
    public static boolean addOptionalUiArtSlot(Table parent, @Nullable String atlasKey, float width, float height){
        if(parent == null || !hasSprite(atlasKey)) return false;
        parent.image(Core.atlas.find(atlasKey)).size(width, height).padBottom(4f).row();
        return true;
    }

    /**
     * Reserves a UI looping frame-animation slot inside {@code parent} (keys {@code prefix-0..N}).
     * Activates automatically when frames exist; occupies 0 space when absent.
     */
    public static boolean addOptionalUiAnimSlot(Table parent, @Nullable String framePrefix, float fps, float width, float height){
        if(parent == null) return false;
        TextureRegion[] frames = loadFrameSequence(framePrefix);
        if(frames.length == 0) return false;
        TextureRegionDrawable drawable = new TextureRegionDrawable(frames[0]);
        Image img = new Image(drawable);
        img.update(() -> {
            TextureRegion cur = pickLoopFrame(frames, Time.time / 60f, fps);
            if(cur != null){
                drawable.setRegion(cur);
            }
        });
        parent.add(img).size(width, height).padBottom(4f).row();
        return true;
    }
}
