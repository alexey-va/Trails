package ru.ruscrafting.trails.api;

import java.util.UUID;
import org.bukkit.Location;

/**
 * Authoritative plugin-owned walking actor, including a packet-only NPC.
 * All methods are called on the server thread. The owner must invalidate this
 * instance on retirement/replacement and must return its actual current position,
 * never a planned destination. No native entity or online player is fabricated.
 */
public interface TrailsFootTrafficActor {
    UUID uniqueId();
    Location location();
    boolean isValid();
}
