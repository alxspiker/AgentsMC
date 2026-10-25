package dev.agentsmc;

/**
 * Single source of truth for whether an agent body is really in the world.
 * Citizens' NPC.isSpawned() requires the NMS handle to be "valid" (tracked by the world); after a restart
 * the 0.6.3 auto-respawn ran before the world/chunk finished registering the entity, so the player entity is
 * visible but handle.valid stays false: isSpawned() flips between true/false, so spawn refused ("already
 * spawned") while list/heartbeat reported spawned:false / online:false.
 */
final class AgentBodyState {
    enum Status { LIVE, NEEDS_RESYNC, STALE, ABSENT }
    private AgentBodyState() {}

    /**
     * @param hasEntity Citizens holds an entity reference
     * @param citizensSpawned NPC.isSpawned()
     * @param entityAlive entity not dead/removed
     * @param inLoadedWorld entity's world exists and its chunk is loaded
     */
    static Status classify(boolean hasEntity, boolean citizensSpawned, boolean entityAlive, boolean inLoadedWorld) {
        if (!hasEntity) return Status.ABSENT;
        // 0.6.6: an existing, non-dead entity is online. Chunk-loaded state and Citizens' isSpawned flag are ignored
        // for presence (an entity in an unloaded chunk still exists); isSpawned only distinguishes LIVE vs NEEDS_RESYNC.
        if (!entityAlive) return Status.STALE;
        return citizensSpawned ? Status.LIVE : Status.NEEDS_RESYNC;
    }
    static boolean present(Status s) { return s == Status.LIVE || s == Status.NEEDS_RESYNC; }
}
