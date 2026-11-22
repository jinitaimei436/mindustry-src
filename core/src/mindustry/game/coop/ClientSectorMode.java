package mindustry.game.coop;

/**
 * Tracks how a player participates in V2 multi-sector campaign multiplayer.
 */
public enum ClientSectorMode{
    /** Host player running the authoritative host sector and campaign coordinator. */
    HOST,
    /** Guest currently co-located in the same sector as the host using native server sync. */
    CO_LOCATED,
    /** Guest currently uploading/downloading a sector archive during handoff or switch. */
    TRANSFERRING,
    /** Guest locally simulating an independent sector while synced with the host control channel. */
    DETACHED_LOCAL
}
