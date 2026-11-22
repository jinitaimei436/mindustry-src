package mindustry.game.coop;

import arc.util.*;

import java.io.*;
import java.util.*;

/**
 * Host-configurable guest permissions for V2 multi-sector campaign multiplayer.
 * Supports global defaults plus optional per-guest (UUID) overrides.
 */
public class CampaignPermissions{
    /** Allow guests to switch to existing captured/base sectors on the planet map. */
    public boolean allowGuestSectorSwitch = true;
    /** Allow guests to launch cores into uncaptured sectors. */
    public boolean allowGuestCoreLaunch = false;
    /** Allow guests to unlock nodes in the shared tech tree. */
    public boolean allowGuestResearch = true;
    /** Allow guests to abandon sectors (disabled by default to prevent accidents). */
    public boolean allowGuestAbandon = false;

    /** Optional per-player overrides keyed by player UUID. */
    public final HashMap<String, GuestOverride> overrides = new HashMap<>();

    public static class GuestOverride{
        public @Nullable Boolean allowSectorSwitch;
        public @Nullable Boolean allowCoreLaunch;
        public @Nullable Boolean allowResearch;
        public @Nullable Boolean allowAbandon;

        public boolean isEmpty(){
            return allowSectorSwitch == null && allowCoreLaunch == null && allowResearch == null && allowAbandon == null;
        }

        public GuestOverride copy(){
            GuestOverride c = new GuestOverride();
            c.allowSectorSwitch = allowSectorSwitch;
            c.allowCoreLaunch = allowCoreLaunch;
            c.allowResearch = allowResearch;
            c.allowAbandon = allowAbandon;
            return c;
        }
    }

    /** Resolved permission set for one specific guest client. */
    public static class EffectivePermissions{
        public boolean canSwitchSector;
        public boolean canLaunchCore;
        public boolean canResearch;
        public boolean canAbandon;

        public EffectivePermissions(){
        }

        public EffectivePermissions(boolean canSwitchSector, boolean canLaunchCore, boolean canResearch, boolean canAbandon){
            this.canSwitchSector = canSwitchSector;
            this.canLaunchCore = canLaunchCore;
            this.canResearch = canResearch;
            this.canAbandon = canAbandon;
        }

        public void write(DataOutputStream out) throws IOException{
            out.writeBoolean(canSwitchSector);
            out.writeBoolean(canLaunchCore);
            out.writeBoolean(canResearch);
            out.writeBoolean(canAbandon);
        }

        public static EffectivePermissions read(DataInputStream in) throws IOException{
            return new EffectivePermissions(
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean()
            );
        }
    }

    public boolean canSwitchSector(@Nullable String uuid, boolean isAdmin){
        if(isAdmin) return true;
        GuestOverride ov = uuid == null ? null : overrides.get(uuid);
        return ov != null && ov.allowSectorSwitch != null ? ov.allowSectorSwitch : allowGuestSectorSwitch;
    }

    public boolean canLaunchCore(@Nullable String uuid, boolean isAdmin){
        if(isAdmin) return true;
        GuestOverride ov = uuid == null ? null : overrides.get(uuid);
        return ov != null && ov.allowCoreLaunch != null ? ov.allowCoreLaunch : allowGuestCoreLaunch;
    }

    public boolean canResearch(@Nullable String uuid, boolean isAdmin){
        if(isAdmin) return true;
        GuestOverride ov = uuid == null ? null : overrides.get(uuid);
        return ov != null && ov.allowResearch != null ? ov.allowResearch : allowGuestResearch;
    }

    public boolean canAbandon(@Nullable String uuid, boolean isAdmin){
        if(isAdmin) return true;
        GuestOverride ov = uuid == null ? null : overrides.get(uuid);
        return ov != null && ov.allowAbandon != null ? ov.allowAbandon : allowGuestAbandon;
    }

    public EffectivePermissions effectiveFor(@Nullable String uuid, boolean isAdmin){
        return new EffectivePermissions(
            canSwitchSector(uuid, isAdmin),
            canLaunchCore(uuid, isAdmin),
            canResearch(uuid, isAdmin),
            canAbandon(uuid, isAdmin)
        );
    }

    public GuestOverride getOrCreateOverride(String uuid){
        return overrides.computeIfAbsent(uuid, k -> new GuestOverride());
    }

    public void clearOverride(String uuid){
        overrides.remove(uuid);
    }

    public CampaignPermissions copy(){
        CampaignPermissions c = new CampaignPermissions();
        c.allowGuestSectorSwitch = allowGuestSectorSwitch;
        c.allowGuestCoreLaunch = allowGuestCoreLaunch;
        c.allowGuestResearch = allowGuestResearch;
        c.allowGuestAbandon = allowGuestAbandon;
        for(Map.Entry<String, GuestOverride> entry : overrides.entrySet()){
            if(entry.getValue() != null && !entry.getValue().isEmpty()){
                c.overrides.put(entry.getKey(), entry.getValue().copy());
            }
        }
        return c;
    }
}
