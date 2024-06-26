package me.ccrama.Trails.listeners;

import com.jeff_media.customblockdata.CustomBlockData;
import me.ccrama.Trails.Trails;
import me.ccrama.Trails.configs.Language;
import me.ccrama.Trails.objects.Link;

import me.ccrama.Trails.util.RoadUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import de.diddiz.LogBlock.Actor;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class MoveEventListener implements Listener {


    private final NamespacedKey walksKey;
    private final NamespacedKey trailNameKey;
    private final Trails main;
    public static final HashMap<UUID, Booster> speedBoostedPlayers = new HashMap<>();
    public static BukkitTask boosterTask;

    public MoveEventListener(Trails plugin) {
        this.main = plugin;
        this.walksKey = new NamespacedKey(plugin, "w");
        this.trailNameKey = new NamespacedKey(plugin, "n");
    }

    @EventHandler
    public void walk(PlayerMoveEvent e) {
        if(e.getTo() == null || e.getPlayer().isFlying()){
            return;
        }
        if (e.getFrom().getBlock().equals(e.getTo().getBlock())){
            return;
        }

        if (!Trails.config.allWorldsEnabled && !Trails.config.enabledWorlds.contains(e.getTo().getWorld().getName())){
            //System.out.println("World is disabled");
            return;
        }


        if(Trails.roadMap.containsKey(e.getPlayer())){
            createRoad(e.getPlayer(), e.getFrom(), e.getTo());
            return;
        }

        Block block = e.getFrom().subtract(0.0D, 0.1D, 0.0D).getBlock();
        Link link = getLink(block);
        Player p = e.getPlayer();
        speedBoost(p, link, block);

        if ((Trails.config.sneakBypass && e.getPlayer().isSneaking()) || link == null){
            //System.out.println("Sneak or link == null");
            return;
        }
        if ((!Trails.config.usePermission && main.getToggles().isDisabled(p.getUniqueId().toString())) || (Trails.config.usePermission && !p.hasPermission("trails.create-trails"))) {
            //System.out.println("Disabled trails");
            return;
        }

        if (checkConditions(p, block.getLocation())) makePath(p, block, link, false);
        //System.out.println("Check conditions: "+checkConditions(p, block.getLocation()));
    }

    public void makePath(Player p, Block block, Link link, boolean skip) {
        if (!skip) {
            double foo = Math.random() * 100.0D;
            double bar = link.chanceOccurance();
            if (p.isSprinting()) bar *= Trails.config.runModifier;
            if (foo > bar) return;

            PersistentDataContainer container = new CustomBlockData(block, main);
            Integer walked = container.get(walksKey, PersistentDataType.INTEGER);
            if (walked == null) walked = 0;
            //System.out.println(walked);
            if (walked >= link.decayNumber() && link.getNext() != null) {
                container.set(walksKey, PersistentDataType.INTEGER, 0);
                container.set(trailNameKey, PersistentDataType.STRING, link.getTrailName() + ":" + link.getNext().identifier());
                try {
                    this.changeNext(p, block, link);
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            } else container.set(walksKey, PersistentDataType.INTEGER, walked + 1);
        } else {
            if (link.getNext() != null) {
                PersistentDataContainer container = new CustomBlockData(block, main);
                container.set(walksKey, PersistentDataType.INTEGER, 0);
                container.set(trailNameKey, PersistentDataType.STRING, link.getTrailName() + ":" + link.getNext().identifier());
                try {
                    this.changeNext(p, block, link);
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void changeNext(Player p, Block block, Link link) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Material type = block.getType();
        BlockState state = block.getState();
        BlockData data = block.getBlockData();
        if (link.getNext() != null) {
            Material nextMat = link.getNext().getMat();
            block.setType(nextMat);
            block.getState().update(true);
            //log block changes in LogBlock and CoreProtect
            if (main.getLbHook() != null && Trails.config.logBlock) {
                main.getLbHook().getLBConsumer().queueBlockReplace(new Actor(p.getName()), state, block.getState());
            }
            if (main.getCpHook() != null && Trails.config.coreProtect) {
                main.getCpHook().getAPI().logRemoval(p.getName(), block.getLocation(), type, data);
                main.getCpHook().getAPI().logPlacement(p.getName(), block.getLocation(), nextMat, block.getBlockData());
            }
            main.triggerUpdate(block.getLocation());
        }
    }

    public Link getLink(Block block) {
        ArrayList<Link> links = Trails.config.getLinksConfig().getLinks().getFromMat(block.getType());
        Link link = null;
        PersistentDataContainer container = null;

        if (block.getType() == Material.AIR) return null;
        if (links != null && links.size() == 1) {
            link = links.get(0);
        } else if (links != null) {
            container = new CustomBlockData(block, main);
            if (!container.has(trailNameKey, PersistentDataType.STRING)) {
                Link minLink = null;
                ArrayList<Link> startLinks = new ArrayList<>();
                for (Link link1 : links) {
                    if (minLink == null || link1.identifier() < minLink.identifier()) minLink = link1;
                    if (link1.identifier() == 0) startLinks.add(link1);
                }
                if (startLinks.size() > 1) {
                    Random random = new Random();
                    return startLinks.get(random.nextInt(startLinks.size()));
                } else if (startLinks.size() == 0 && Trails.config.strictLinks) return null;
                link = minLink;
            } else {
                String[] blockTrailName = container.get(trailNameKey, PersistentDataType.STRING).split(":");
                Integer id = null;
                try {
                    id = Integer.parseInt(blockTrailName[1]);
                } catch (Exception ignored) {
                }
                link = links.get(0);
                for (Link lnk : links) {
                    if (lnk.getTrailName().equals(blockTrailName[0])) {
                        if (id != null && lnk.identifier() == id) {
                            link = lnk;
                            break;
                        }
                        if (lnk.identifier() < link.identifier()) link = lnk;
                    }
                }
            }
        }

        if (Trails.config.strictLinks) {
            if (container == null) container = new CustomBlockData(block, main);
            String s = container.get(trailNameKey, PersistentDataType.STRING);
            if (s == null && link != null && link.getPrevious() != null) return null;
        }

        return link;
    }

    public void speedBoost(Player p, Link link, Block block) {
        PersistentDataContainer container = null;
        if (main.getToggles().isBoost(p.getUniqueId().toString()) || (Trails.config.usePermissionBoost && p.hasPermission("trails.boost"))) {
            boolean changeImmediately = false;
            Booster booster = speedBoostedPlayers.get(p.getUniqueId());
            float targetSpeed = 0.2F;

            // If block material is in one of the links
            if (link != null) {
                // In case you are on a trail material but not on an actual trail
                if (Trails.config.onlyTrails) {
                    container = new CustomBlockData(block, main);
                    if (container.has(trailNameKey, PersistentDataType.STRING)) {
                        targetSpeed = 0.2F * link.getSpeedBoost();
                    } else changeImmediately = Trails.config.immediatelyRemoveBoost;
                } else targetSpeed = 0.2F * link.getSpeedBoost();
            } else changeImmediately = Trails.config.immediatelyRemoveBoost;
            if (p.getWalkSpeed() != targetSpeed) {
                if (booster != null) {
                    booster.setTargetSpeed(targetSpeed, changeImmediately);
                } else {
                    Booster playerBooster = new Booster(targetSpeed, p, changeImmediately);
                    speedBoostedPlayers.put(p.getUniqueId(), playerBooster);
                    if (boosterTask == null || boosterTask.isCancelled()) boosterTask = new BukkitRunnable() {
                        @Override
                        public void run() {
                            ArrayList<UUID> toRemove = new ArrayList<>();
                            for (Map.Entry<UUID, Booster> entry : speedBoostedPlayers.entrySet()) {
                                Player player = entry.getValue().getPlayer();
                                if (entry.getValue().immediately) player.setWalkSpeed(entry.getValue().targetSpeed);
                                else if (entry.getValue().getTargetSpeed() > player.getWalkSpeed())
                                    player.setWalkSpeed(Math.min(player.getWalkSpeed() + Trails.config.speedBoostStep, entry.getValue().getTargetSpeed()));
                                else
                                    player.setWalkSpeed(Math.max(player.getWalkSpeed() - Trails.config.speedBoostStep, entry.getValue().getTargetSpeed()));

                                if (player.getWalkSpeed() == entry.getValue().getTargetSpeed())
                                    toRemove.add(entry.getKey());
                            }

                            for (UUID uuid : toRemove) removeBoostedPlayer(uuid, false);
                        }
                    }.runTaskTimer(main, 0L, Trails.config.speedBoostInterval);
                }
            }
        }
    }

    public boolean checkConditions(Player p, Location location) {
        // Check towny conditions
        if (main.getTownyHook() != null) {
            if (main.getTownyHook().isWilderness(p)) {
                if (!main.getTownyHook().isPathsInWilderness()) {
                    if (Trails.config.sendDenyMessage)
                        sendDelayedMessage(p);
                    return false;
                }
            }

            if (main.getTownyHook().isTownyPathsPerms()) {
                if (main.getTownyHook().isInHomeNation(p) && !main.getTownyHook().hasNationPermission(p) && !main.getTownyHook().isInHomeTown(p)) {
                    if (Trails.config.sendDenyMessage)
                        sendDelayedMessage(p);
                    return false;
                }
                if (main.getTownyHook().isInHomeTown(p) && !main.getTownyHook().hasTownPermission(p)) {
                    if (Trails.config.sendDenyMessage)
                        sendDelayedMessage(p);
                    return false;
                }
            } else {
                if (main.getTownyHook().isInOtherNation(p)) {
                    if (Trails.config.sendDenyMessage)
                        sendDelayedMessage(p);
                    return false;
                }
                if (main.getTownyHook().isInOtherTown(p)) {
                    if (Trails.config.sendDenyMessage)
                        sendDelayedMessage(p);
                    return false;
                }
            }
        }
        // Check Lands conditions
        if (main.getLandsHook() != null) {
            //System.out.println("Lands: "+main.getLandsHook().hasTrailsFlag(p, location));
            if (!main.getLandsHook().hasTrailsFlag(p, location)) {
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        // Check GriefPrevention conditions
        if (main.getGpHook() != null) {
            //System.out.println("GP: "+main.getGpHook().canMakeTrails(p, location));
            if (!main.getGpHook().canMakeTrails(p, location)) {
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        // Check worldguard conditions
        if (main.getWorldGuardHook() != null && Trails.config.wgIntegration) {
            //System.out.println("WG: "+main.getWorldGuardHook().canCreateTrails(p, location));
            if (!main.getWorldGuardHook().canCreateTrails(p, location)) {
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        // Check PlayerPlot conditions
        if (main.getPlayerPlotHook() != null && Trails.config.playerPlotIntegration) {
            //System.out.println("PlayerPlot: "+main.getPlayerPlotHook().canMakeTrails(p, location));
            if (!main.getPlayerPlotHook().canMakeTrails(p, location)) {
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        // Check RedProtect conditions
        if (main.getRedProtectHook() != null && Trails.config.redProtect) {
            //System.out.println("WG: "+main.getRedProtectHook().canBuild(p, location));
            if (!main.getRedProtectHook().canBuild(p, location)) {
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        //Check residence
        if(main.getResidenceHook() != null && Trails.config.residence){
            //System.out.println("WG: "+main.getResidenceHook().canCreateTrails(location, p));
            if(!main.getResidenceHook().canCreateTrails(location, p)){
                if (Trails.config.sendDenyMessage)
                    sendDelayedMessage(p);
                return false;
            }
        }
        return true;
    }

    private void sendDelayedMessage(Player p) {
        if (!main.messagePlayers.contains(p.getUniqueId())) {
            p.sendMessage(Language.getString("messages.cantCreateTrails", null, p));
            main.messagePlayers.add(p.getUniqueId());
            Bukkit.getScheduler().runTaskLater(main, () -> delayWGMessage(p.getUniqueId()), 20L * Trails.config.messageInterval);
        }
    }

    private void delayWGMessage(UUID id) {
        main.messagePlayers.remove(id);
    }

    public static void removeBoostedPlayer(UUID uuid, boolean defaultSpeed) {
        Player player = Trails.getInstance().getServer().getPlayer(uuid);
        if (player != null && defaultSpeed) player.setWalkSpeed(0.2F);
        speedBoostedPlayers.remove(uuid);
        if (speedBoostedPlayers.size() == 0 && boosterTask != null && !boosterTask.isCancelled()) {
            boosterTask.cancel();
        }
    }

    public static void disableBoostTask() {
        if (boosterTask == null || boosterTask.isCancelled()) return;
        for (Booster booster : speedBoostedPlayers.values()) {
            Player player = booster.getPlayer();
            if (player == null) continue;
            player.setWalkSpeed(0.2F);
        }
        boosterTask.cancel();
    }

    public void createRoad(Player player, Location from, Location to){
        Location locBelow = firstSolid(to);
        if(locBelow == null) return;
        CustomBlockData toData = new CustomBlockData(locBelow.getBlock(), main);
        if(toData.has(new NamespacedKey(main, "r"))){
            System.out.println("Already road");
            return;
        }

        int x1 = from.getBlockX();
        int x2 = to.getBlockX();
        int z1 = from.getBlockZ();
        int z2 = to.getBlockZ();
        RoadUtils.Direction direction = null;
        if(x1 > x2) direction = RoadUtils.Direction.WEST;
        else if (x1 < x2) direction = RoadUtils.Direction.EAST;
        else if(z1 > z2) direction = RoadUtils.Direction.NORTH;
        else if (z1 < z2) direction = RoadUtils.Direction.SOUTH;
        if(direction != null) RoadUtils.placeRoad(player, locBelow, direction, Trails.roadMap.get(player));
    }

    public Location firstSolid(Location location){
        Location loc = location.clone();
        loc.add(0,-0.05, 0);
        if(loc.getBlock().getType().isSolid()) return loc;
        for(int i=0;i<5;i++){
            if(loc.add(0,-1,0).getBlock().getType().isSolid()) return loc;
        }
        return null;
    }

    public static class Booster {
        float targetSpeed;
        Player player;
        boolean immediately;

        public Booster(float targetSpeed, Player player) {
            this.targetSpeed = targetSpeed;
            this.player = player;
            immediately = false;
        }

        public Booster(float targetSpeed, Player player, boolean immediately) {
            this.targetSpeed = targetSpeed;
            this.player = player;
            this.immediately = immediately;
        }

        public float getTargetSpeed() {
            return targetSpeed;
        }

        public Player getPlayer() {
            return player;
        }

        public void setTargetSpeed(float targetSpeed) {
            this.targetSpeed = targetSpeed;
        }

        public void setTargetSpeed(float targetSpeed, boolean immediately) {
            this.targetSpeed = targetSpeed;
            this.immediately = immediately;
        }

        public void setPlayer(Player player) {
            this.player = player;
        }
    }

}
	