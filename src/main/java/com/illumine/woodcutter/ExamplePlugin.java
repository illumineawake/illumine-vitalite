package com.illumine.woodcutter;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.data.TileObjectEx;
import com.tonic.queries.NpcQuery;
import com.tonic.queries.TileObjectQuery;
import com.tonic.util.VitaPlugin;
import com.tonic.api.game.SceneAPI;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.CollisionDataFlag;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

@PluginDescriptor(
        name = "Illumine Reachability Logger",
        description = "Logs whether the configured NPC or object is reachable and its location.",
        tags = {"illumine", "vita", "reach", "logger"}
)
public class ExamplePlugin extends VitaPlugin {

    @Inject
    private Client client;

    @Inject
    private ExamplePluginConfig config;

    private long lastLogTs = 0L;
    private String lastSummary = null;

    @Provides
    ExamplePluginConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ExamplePluginConfig.class);
    }

    @Override
    protected void startUp() {
        // No UI; passive logger
    }

    @Override
    protected void shutDown() {
        // Nothing to clean up
    }

    @Override
    public void loop() {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

//         Rate limit identical logs
        int intervalMs = Math.max(250, config.getLogIntervalMs());
        long now = System.currentTimeMillis();
        if (now - lastLogTs < intervalMs) {
            return;
        }

        String name = safe(config.getTargetName());
        if (name.isEmpty()) return;

        WorldPoint from = client.getLocalPlayer().getWorldLocation();

        if (config.getTargetType() == TargetType.OBJECT) {
            TileObjectEx obj = new TileObjectQuery<>()
                    .withNameContains(name)
                    .sortNearest()
                    .first();

            if (obj == null) {
                logOnce(now, String.format("[Reach] type=OBJECT name=\"%s\" not found", name));
                return;
            }

            // Use object's world area; already reflects rotation extents
            var area = obj.getWorldArea();
            var target = IllumineReach.fromWorldArea(area, false);
            WorldPoint approach = IllumineReach.findApproach(from, target);
            boolean reachable = approach != null;
            WorldPoint to = obj.getWorldLocation();
            boolean sceneReach = SceneAPI.isReachable(from, to);
            if (reachable) {
                logOnce(now, String.format(
                        "[Reach] type=OBJECT name=\"%s\" loc=(%d,%d,%d) reachable=true approach=(%d,%d,%d) sceneReachable=%s",
                        name, to.getX(), to.getY(), to.getPlane(), approach.getX(), approach.getY(), approach.getPlane(), sceneReach));
            } else {
                logOnce(now, String.format(
                        "[Reach] type=OBJECT name=\"%s\" loc=(%d,%d,%d) reachable=false sceneReachable=%s",
                        name, to.getX(), to.getY(), to.getPlane(), sceneReach));
                if (config.getLogEdgeDebug()) debugEdges(from, area);
            }
            return;
        }

        // NPC branch
        NPC npc = new NpcQuery()
                .withNameContains(name)
                .sortNearest()
                .first();

        if (npc == null) {
            logOnce(now, String.format("[Reach] type=NPC name=\"%s\" not found", name));
            return;
        }

        // Actors use exclusive rectangle reach (must be adjacent and unblocked)
        var area = npc.getWorldArea();
        var target = IllumineReach.fromWorldArea(area, true);
        WorldPoint approach = IllumineReach.findApproach(from, target);
        boolean reachable = approach != null;
        WorldPoint to = npc.getWorldLocation();
        boolean sceneReach = SceneAPI.isReachable(from, to);
        if (reachable) {
            logOnce(now, String.format(
                    "[Reach] type=NPC name=\"%s\" loc=(%d,%d,%d) reachable=true approach=(%d,%d,%d) sceneReachable=%s",
                    name, to.getX(), to.getY(), to.getPlane(), approach.getX(), approach.getY(), approach.getPlane(), sceneReach));
        } else {
            logOnce(now, String.format(
                    "[Reach] type=NPC name=\"%s\" loc=(%d,%d,%d) reachable=false sceneReachable=%s",
                    name, to.getX(), to.getY(), to.getPlane(), sceneReach));
            if (config.getLogEdgeDebug()) debugEdges(from, area);
        }
    }

    private void logOnce(long now, String summary) {
        if (!summary.equals(lastSummary)) {
            Logger.info(summary);
            lastSummary = summary;
            lastLogTs = now;
        } else if (now - lastLogTs >= Math.max(250, config != null ? config.getLogIntervalMs() : 2000)) {
            Logger.info(summary);
            lastLogTs = now;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private void debugEdges(WorldPoint src, net.runelite.api.coords.WorldArea area) {
        int sx = src.getX();
        int sy = src.getY();
        int p = src.getPlane();
        int flags = IllumineReach.tileFlags(sx, sy, p);
        boolean bE = (flags & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
        boolean bW = (flags & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
        boolean bN = (flags & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
        boolean bS = (flags & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;

        WorldPoint base = area.toWorldPoint();
        int dx = base.getX();
        int dy = base.getY();
        int east = dx + area.getWidth() - 1;
        int north = dy + area.getHeight() - 1;

        String side = "none";
        if (sx == dx - 1 && sy >= dy && sy <= north) side = "WEST";
        else if (sx == east + 1 && sy >= dy && sy <= north) side = "EAST";
        else if (sy + 1 == dy && sx >= dx && sx <= east) side = "SOUTH";
        else if (sy == north + 1 && sx >= dx && sx <= east) side = "NORTH";

        Logger.info(String.format(
                "[EdgeDebug] src=(%d,%d,%d) side=%s flags: E=%s W=%s N=%s S=%s mask=%d",
                sx, sy, p, side, bE, bW, bN, bS, 0));
    }
}
