package ru.ruscrafting.trails.api;

import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;

/**
 * Trails-owned Bukkit service for observed native NPC footsteps. The Trails plugin owns these API
 * classes at runtime; consumers depend on the contract JAR with compileOnly and resolve this service
 * through ServicesManager.
 */
public interface TrailsFootTrafficService {
    /**
     * Current walking multiplier of one already-loaded ground block, on the server thread.
     * Returns 1 outside enabled worlds, while metadata is not ready, or without a tracked trail.
     * Read-only: never prepares metadata or loads chunks. Consumers use the same value for routing
     * and their native NPC movement. Older providers retain ordinary speed through this default.
     */
    default double speedMultiplier(Block ground) { return 1.0; }

    /**
     * Records one horizontal block crossing by a living entity carrying Citizens' {@code NPC}
     * metadata. Call on the server thread, once per observed step, with the actual before/after
     * coordinates; stationary samples, samples longer than 1.5 blocks, and destinations that do not
     * match the NPC's current position are ignored. Reset the caller's previous position after a
     * teleport. Locations are copied before asynchronous metadata preparation.
     *
     * <p>The authority must be a real online player in the same world, authorized by the caller to
     * manage this NPC. Normal Trails world, permission, progression, widening and protection rules
     * apply, with protection events and block-change logs attributed to that authority. NPC traffic
     * has normal walking weight, no player preferences, speed changes, sounds or particles.
     *
     * <p>Completion means that the step was processed or ignored, not that the existing asynchronous
     * chunk journal has flushed. Pending steps retain the existing metadata/foreign-change barriers,
     * recheck actor, authority and runtime validity, and fail exceptionally on storage/admission
     * failures. Cancelling an observer does not undo an accepted step. This method never loads chunks.
     *
     * @throws IllegalStateException when called outside the server thread
     */
    CompletionStage<StepResult> recordStep(Entity actualNpc, Player authority, Location from, Location to);

    enum StepResult {
        /** Unavailable runtime, invalid authority/actor, stale context or a non-walking sample. */
        IGNORED,
        /** No configured progression, a chance miss, or protection veto. */
        NO_CHANGE,
        /** The ordinary Trails walk counter advanced without replacing a block. */
        COUNTED,
        /** The ordinary Trails pipeline advanced a material or widened a popular trail. */
        CHANGED
    }
}
