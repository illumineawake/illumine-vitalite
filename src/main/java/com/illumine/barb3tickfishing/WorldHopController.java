package com.illumine.barb3tickfishing;

import com.tonic.api.threaded.Delays;
import com.tonic.api.threaded.WorldsAPI;
import com.tonic.queries.WorldQuery;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldRegion;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

class WorldHopController
{
    interface Host
    {
        Client getClient();

        void log(String message);

        void onWorldHopSuccess();
    }

    private final Host host;

    private boolean hopEnabled = false;
    private int hopIntervalMinutes = 10;
    private long nextHopAtMs = 0L;
    private boolean shouldHop = false;
    private int currentWorldId = -1;
    private WorldRegion homeRegion = null;

    WorldHopController(Host host)
    {
        this.host = host;
    }

    void reset()
    {
        hopEnabled = false;
        hopIntervalMinutes = 10;
        nextHopAtMs = 0L;
        shouldHop = false;
        currentWorldId = -1;
        homeRegion = null;
    }

    void initialize(boolean enabled, int intervalMinutes)
    {
        hopEnabled = enabled;
        hopIntervalMinutes = Math.max(1, intervalMinutes);
        World current = WorldsAPI.getCurrentWorld();
        if (current != null)
        {
            currentWorldId = current.getId();
            homeRegion = current.getRegion();
        }
        if (hopEnabled)
        {
            scheduleNextHop();
        }
        else
        {
            clearHopSchedule();
        }
    }

    void updateHopDue(long now)
    {
        if (hopEnabled && nextHopAtMs > 0L && now >= nextHopAtMs)
        {
            shouldHop = true;
        }
    }

    boolean canWorldHop(boolean canBreak)
    {
        return hopEnabled && shouldHop && canBreak;
    }

    void scheduleNextHop()
    {
        if (!hopEnabled)
        {
            clearHopSchedule();
            return;
        }
        long baseMillis = Math.max(1, hopIntervalMinutes) * 60_000L;
        double factor = ThreadLocalRandom.current().nextDouble(0.85, 1.15);
        nextHopAtMs = System.currentTimeMillis() + (long) (baseMillis * factor);
        shouldHop = false;
    }

    void clearHopSchedule()
    {
        nextHopAtMs = 0L;
        shouldHop = false;
    }

    boolean performWorldHop()
    {
        World current = WorldsAPI.getCurrentWorld();
        if (current == null)
        {
            host.log("Unable to resolve current world for hopping");
            return false;
        }
        currentWorldId = current.getId();
        homeRegion = current.getRegion();

        List<World> candidates = new WorldQuery()
                .isP2p()
                .isMainGame()
                .notPvp()
                .notSkillTotalWorlds()
                .collect()
                .stream()
                .filter(world -> world.getId() != currentWorldId)
                .filter(world -> homeRegion == null || world.getRegion() == homeRegion)
                .collect(Collectors.toList());

        if (candidates.isEmpty())
        {
            host.log("No eligible worlds available for hopping");
            return false;
        }

        Collections.shuffle(candidates);
        Client client = host.getClient();
        for (World candidate : candidates)
        {
            int targetId = candidate.getId();
            host.log("Attempting to hop to world " + targetId);
            WorldsAPI.hop(candidate);

            boolean hopped = waitForWorld(client, targetId);
            if (hopped)
            {
                currentWorldId = targetId;
                homeRegion = candidate.getRegion();
                host.onWorldHopSuccess();
                host.log("World-hop: switched to world " + targetId);
                return true;
            }
        }
        host.log("World-hop attempts failed");
        return false;
    }

    void setHopEnabled(boolean enabled)
    {
        hopEnabled = enabled;
        if (enabled)
        {
            scheduleNextHop();
        }
        else
        {
            clearHopSchedule();
        }
    }

    void setHopIntervalMinutes(int minutes)
    {
        hopIntervalMinutes = Math.max(1, minutes);
        if (hopEnabled)
        {
            scheduleNextHop();
        }
    }

    int hopIntervalMinutes()
    {
        return hopIntervalMinutes;
    }

    String formatTimeToHop()
    {
        if (!hopEnabled || nextHopAtMs <= 0L)
        {
            return "—";
        }
        long remaining = nextHopAtMs - System.currentTimeMillis();
        if (remaining <= 0)
        {
            return "00:00";
        }
        long totalSeconds = remaining / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    String formatCurrentWorld()
    {
        if (currentWorldId <= 0)
        {
            World current = WorldsAPI.getCurrentWorld();
            if (current != null)
            {
                currentWorldId = current.getId();
            }
        }
        if (currentWorldId <= 0)
        {
            return "—";
        }
        return "W" + currentWorldId;
    }

    private boolean waitForWorld(Client client, int targetWorld)
    {
        long timeout = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < timeout)
        {
            GameState state = client.getGameState();
            if (state == GameState.LOGGED_IN && client.getWorld() == targetWorld)
            {
                return true;
            }
            Delays.wait(200);
        }
        return false;
    }
}
