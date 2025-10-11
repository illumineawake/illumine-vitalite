package com.illumine.barb3tickfishing;

import com.tonic.api.entities.NpcAPI;
import com.tonic.api.game.MovementAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.TabsAPI;
import com.tonic.data.ItemEx;
import com.tonic.queries.InventoryQuery;
import com.tonic.queries.NpcQuery;
import com.tonic.util.ClickManagerUtil;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static com.illumine.barb3tickfishing.FishingMode.NORMAL;
import static com.illumine.barb3tickfishing.FishingMode.THREE_TICK;

class Barb3TickRuntime
{

    private enum NextAction
    {
        CLICK_SPOT,
        WAIT_FOR_COMBINE,
        COMBINE_HERB,
        DROP_ONE
    }

    private static final List<DropPattern> DROP_PATTERNS = List.of(
            DropPattern.LEFT_TO_RIGHT,
            DropPattern.RIGHT_TO_LEFT,
            DropPattern.TOP_TO_BOTTOM,
            DropPattern.RANDOM
    );

    private final Barb3TickFishingPlugin plugin;
    private final Barb3TickFishingSidePanel panel;
    private final Barb3TickRuntimeConfig runtimeConfig = new Barb3TickRuntimeConfig();
    private final ModeScheduler modeScheduler = new ModeScheduler(runtimeConfig, this::log);
    private final SuppliesManager suppliesManager = new SuppliesManager(this);
    private final WorldHopController worldHopController = new WorldHopController(this);

    private NPC currentFishSpot = null;
    private WorldPoint targetSpotTile = null;
    private WorldPoint scriptStartTile = null;

    private NextAction nextAction = NextAction.CLICK_SPOT;
    private long actionGateTick = -1L;
    private String lastSpotSource = "";
    private long tickCount = 0L;
    private long startTimeMs = 0L;
    private long currentModeEnteredAtMs = 0L;
    private long threeTickAccumulatedMs = 0L;
    private long lastKnownGameTick = -1L;
    private long combineAtTick = -1L;

    private DropPattern selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
    private int playerNameLengthForPattern = 0;

    private boolean running = false;
    private boolean startInitializationComplete = false;

    Barb3TickRuntime(Barb3TickFishingPlugin plugin, Barb3TickFishingSidePanel panel, Barb3TickFishingConfig persistedConfig)
    {
        this.plugin = plugin;
        this.panel = panel;
        runtimeConfig.applyFromConfig(persistedConfig);
        suppliesManager.setHerbName(runtimeConfig.herbName());
    }

    void start()
    {
        if (running)
        {
            return;
        }
        if (!hasLevelRequirements())
        {
            log("Stopping script: Barbarian Fishing level requirements not met. You need all of: 48 Fishing, 15 Strength, 15 Agility.");
            panel.setRunning(false);
            return;
        }
        log("Starting script");
        resetState();
        runtimeConfig.applyFromConfig(plugin.getConfig());
        suppliesManager.setHerbName(runtimeConfig.herbName());
        worldHopController.initialize(plugin.getConfig().allowWorldHop(), plugin.getConfig().worldHopIntervalMinutes());
        modeScheduler.initialiseMode();
        long now = System.currentTimeMillis();
        startTimeMs = now;
        currentModeEnteredAtMs = now;
        threeTickAccumulatedMs = 0L;
        Player player = client().getLocalPlayer();
        if (player != null)
        {
            scriptStartTile = player.getWorldLocation();
            selectedDropPattern = resolveDropPatternForPlayer(player);
            log("Dropping pattern set to " + selectedDropPattern.displayName() + " (name length=" + playerNameLengthForPattern + ")");
        }
        running = true;
        startInitializationComplete = true;
        TabsAPI.open(com.tonic.data.Tab.INVENTORY_TAB);
    }

    void stop()
    {
        if (!running)
        {
            return;
        }
        log("Stopped script");
        running = false;
        if (modeScheduler.tickFishing() && currentModeEnteredAtMs > 0)
        {
            threeTickAccumulatedMs += Math.max(0L, System.currentTimeMillis() - currentModeEnteredAtMs);
        }
        worldHopController.reset();
        runtimeConfig.reset();
        suppliesManager.reset();
        modeScheduler.reset();
        resetState();
    }

    void loop()
    {
        if (!running)
        {
            return;
        }
        if (!ensureClientReady())
        {
            return;
        }

        updateTickCount();
        long now = System.currentTimeMillis();
        worldHopController.updateHopDue(now);

        if (!modeScheduler.switchQueued() && modeScheduler.modeExpiresAtMs() > 0 && now >= modeScheduler.modeExpiresAtMs())
        {
            modeScheduler.onWindowExpired();
        }

        String coreMissing = missingCoreItem();
        if (!coreMissing.isBlank())
        {
            log("Stopping script: Missing item " + coreMissing);
            requestStop("Missing core item");
            return;
        }

        if (worldHopController.canWorldHop(canBreak()))
        {
            if (worldHopController.performWorldHop())
            {
                worldHopController.scheduleNextHop();
            }
            else
            {
                worldHopController.scheduleNextHop();
            }
            updatePanelStatus();
            return;
        }

        boolean tickFishing = modeScheduler.tickFishing();
        if (!tickFishing)
        {
            if (runtimeConfig.switchingEnabled() && !suppliesManager.hasThreeTickSuppliesAvailable())
            {
                suppliesManager.handleOutOfSupplies(suppliesManager.determineMissingSupply(), runtimeConfig.switchToNormalOnSuppliesOut());
            }
            handleNormalMode();
            updatePanelStatus();
            return;
        }

        if (!suppliesManager.ensureSuppliesForActiveMode())
        {
            updatePanelStatus();
            return;
        }

        switch (nextAction)
        {
            case CLICK_SPOT:
                handleClickSpot();
                break;
            case WAIT_FOR_COMBINE:
                handleWaitForCombine();
                break;
            case COMBINE_HERB:
                handleCombineHerb();
                break;
            case DROP_ONE:
                handleDropOne();
                break;
        }
        updatePanelStatus();
    }

    void onFrequencyModeChanged(ThreeTickFrequencyMode mode)
    {
        runtimeConfig.setFrequencyMode(mode);
        modeScheduler.refreshScheduleAfterConfigChange();
    }

    void onHerbNameChanged(String herbName)
    {
        runtimeConfig.setHerbName(herbName);
        suppliesManager.setHerbName(runtimeConfig.herbName());
    }

    void onFallbackChanged(boolean enabled)
    {
        runtimeConfig.setSwitchToNormalOnSuppliesOut(enabled);
    }

    void onWorldHopToggle(boolean enabled)
    {
        worldHopController.setHopEnabled(enabled);
        if (enabled)
        {
            worldHopController.scheduleNextHop();
        }
    }

    void onHopIntervalChanged(int minutes)
    {
        worldHopController.setHopIntervalMinutes(minutes);
    }

    Barb3TickRuntimeConfig config()
    {
        return runtimeConfig;
    }

    ModeScheduler modeScheduler()
    {
        return modeScheduler;
    }

    SuppliesManager suppliesManager()
    {
        return suppliesManager;
    }

    WorldHopController worldHopController()
    {
        return worldHopController;
    }

    Barb3TickFishingPlugin plugin()
    {
        return plugin;
    }

    Barb3TickFishingSidePanel panel()
    {
        return panel;
    }

    void log(String message)
    {
        plugin.log(message);
    }

    Client client()
    {
        return plugin.getClient();
    }

    void switchToPermanentNormalMode()
    {
        runtimeConfig.setFrequencyMode(ThreeTickFrequencyMode.NEVER);
        modeScheduler.clearQueue();
        applyMode(NORMAL);
        modeScheduler.refreshScheduleAfterConfigChange();
        nextAction = NextAction.CLICK_SPOT;
        combineAtTick = -1L;
        Barb3TickFishingConfig persisted = plugin.getConfig();
        if (persisted != null)
        {
            persisted.setFrequencyMode(ThreeTickFrequencyMode.NEVER);
        }
        SwingUtilities.invokeLater(() -> panel.setSelectedFrequencyMode(ThreeTickFrequencyMode.NEVER));
    }

    void onWorldHopSuccess()
    {
        targetSpotTile = null;
        currentFishSpot = null;
        lastSpotSource = "";
        nextAction = NextAction.CLICK_SPOT;
        actionGateTick = tickCount;
    }

    void requestStop(String reason)
    {
        log("Stopping script: " + reason);
        panel.setRunning(false);
        stop();
    }

    boolean isTickFishing()
    {
        return modeScheduler.tickFishing();
    }

    private void resetState()
    {
        currentFishSpot = null;
        targetSpotTile = null;
        scriptStartTile = null;
        nextAction = NextAction.CLICK_SPOT;
        actionGateTick = -1L;
        lastSpotSource = "";
        tickCount = 0L;
        startTimeMs = 0L;
        currentModeEnteredAtMs = 0L;
        threeTickAccumulatedMs = 0L;
        lastKnownGameTick = -1L;
        combineAtTick = -1L;
        selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
        playerNameLengthForPattern = 0;
    }

    private boolean ensureClientReady()
    {
        Client client = client();
        return client != null && client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
    }

    private void updateTickCount()
    {
        long gameTick = com.tonic.services.GameManager.getTickCount();
        tickCount = gameTick;
        lastKnownGameTick = gameTick;
        if (!startInitializationComplete)
        {
            return;
        }
        if (scriptStartTile == null)
        {
            Player player = client().getLocalPlayer();
            if (player != null)
            {
                scriptStartTile = player.getWorldLocation();
                selectedDropPattern = resolveDropPatternForPlayer(player);
                log("Dropping pattern set to " + selectedDropPattern.displayName() + " (name length=" + playerNameLengthForPattern + ")");
            }
        }
    }

    private void updatePanelStatus()
    {
        if (!startInitializationComplete)
        {
            return;
        }
        Barb3TickFishingSidePanel.StatusSnapshot snapshot = new Barb3TickFishingSidePanel.StatusSnapshot(
                isTickFishing() ? "3Tick" : "Normal",
                getFrequencyDisplay(),
                formatThreeTickShare(),
                formatSwitchCountdown(),
                worldHopController.formatCurrentWorld(),
                worldHopController.formatTimeToHop(),
                suppliesManager.hasThreeTickSuppliesAvailable() ? "OK" : "Check"
        );
        panel.updateStatus(snapshot);
    }

    private String formatSwitchCountdown()
    {
        if (!runtimeConfig.switchingEnabled())
        {
            return "N/A";
        }
        return formatMs(modeScheduler.modeExpiresAtMs() - System.currentTimeMillis());
    }

    private String getFrequencyDisplay()
    {
        if (runtimeConfig.frequencyMode() == ThreeTickFrequencyMode.RANDOM)
        {
            ThreeTickFrequencyMode profile = modeScheduler.activeRandomProfile();
            String current = modeScheduler.tickFishing() ? "3T" : "Normal";
            String prof = (profile == null) ? "—" : profile.shortLabel();
            return "Random(" + current + " " + prof + ")";
        }
        return runtimeConfig.frequencyMode().label();
    }

    private String formatThreeTickShare()
    {
        if (startTimeMs <= 0)
        {
            return "0.0%";
        }
        long now = System.currentTimeMillis();
        long total = Math.max(1L, now - startTimeMs);
        long threeTickTime = threeTickAccumulatedMs;
        if (modeScheduler.tickFishing() && currentModeEnteredAtMs > 0)
        {
            threeTickTime += Math.max(0L, now - currentModeEnteredAtMs);
        }
        double share = (double) threeTickTime / (double) total * 100.0;
        if (share < 0.0)
        {
            share = 0.0;
        }
        return String.format(Locale.ENGLISH, "%.1f%%", share);
    }

    private void applyMode(FishingMode mode)
    {
        long now = System.currentTimeMillis();
        boolean wasThreeTick = modeScheduler.tickFishing();
        if (wasThreeTick && currentModeEnteredAtMs > 0)
        {
            threeTickAccumulatedMs += Math.max(0L, now - currentModeEnteredAtMs);
        }
        modeScheduler.setFishingMode(mode);
        log("Mode switched to " + mode.name().toLowerCase(Locale.ENGLISH));
        currentModeEnteredAtMs = now;
    }

    private boolean toggleMode()
    {
        if (!runtimeConfig.switchingEnabled())
        {
            return false;
        }
        boolean switchingToThreeTick = !modeScheduler.tickFishing();
        if (switchingToThreeTick && !suppliesManager.ensureSuppliesForUpcomingMode())
        {
            return false;
        }
        applyMode(modeScheduler.tickFishing() ? NORMAL : THREE_TICK);
        return true;
    }

    private void consumeSwitchQueueAfterClick()
    {
        if (!modeScheduler.switchQueued())
        {
            return;
        }
        if (!toggleMode())
        {
            return;
        }
        modeScheduler.clearQueue();
        if (modeScheduler.tickFishing())
        {
            combineAtTick = tickCount + 2;
            nextAction = NextAction.WAIT_FOR_COMBINE;
        }
        else
        {
            combineAtTick = -1L;
            nextAction = NextAction.CLICK_SPOT;
        }
    }

    private void handleClickSpot()
    {
        if (tickCount <= actionGateTick)
        {
            return;
        }
        boolean success = clickFishingSpot();
        if (success)
        {
            consumeSwitchQueueAfterClick();
            if (modeScheduler.tickFishing())
            {
                combineAtTick = tickCount + 2;
                actionGateTick = tickCount;
                nextAction = NextAction.WAIT_FOR_COMBINE;
            }
            else
            {
                combineAtTick = -1L;
                actionGateTick = tickCount;
                nextAction = NextAction.CLICK_SPOT;
            }
        }
        else
        {
            handleClickSpotFailure();
        }
    }

    private void handleWaitForCombine()
    {
        if (!modeScheduler.tickFishing())
        {
            nextAction = NextAction.COMBINE_HERB;
            actionGateTick = tickCount - 1;
            combineAtTick = -1L;
            handleCombineHerb();
            return;
        }
        if (combineAtTick <= 0)
        {
            combineAtTick = tickCount + 2;
        }
        if (tickCount < combineAtTick)
        {
            return;
        }
        nextAction = NextAction.COMBINE_HERB;
        actionGateTick = tickCount - 1;
        combineAtTick = -1L;
        handleCombineHerb();
    }

    private void handleCombineHerb()
    {
        if (tickCount <= actionGateTick)
        {
            return;
        }
        TabsAPI.open(com.tonic.data.Tab.INVENTORY_TAB);
        String herbName = suppliesManager.herbName();
        ItemEx tar = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName("Swamp tar")
                .first();
        ItemEx herb = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName(herbName)
                .first();
        boolean success = tar != null && herb != null;
        if (success)
        {
            InventoryAPI.useOn(tar, herb);
            log("t=" + tickCount + " combining: used tar on herb");
            nextAction = NextAction.DROP_ONE;
            Delays.wait(ThreadLocalRandom.current().nextInt(20, 36));
            handleDropOne();
        }
        combineAtTick = -1L;
        actionGateTick = tickCount;
    }

    private void handleDropOne()
    {
        nextAction = NextAction.CLICK_SPOT;
        boolean success = dropOneLeapingFish();
        if (success)
        {
            log("t=" + tickCount + " dropping: dropped one leaping fish");
        }
        combineAtTick = -1L;
        actionGateTick = tickCount;
    }

    private void handleNormalMode()
    {
        if (tickCount <= actionGateTick)
        {
            return;
        }
        if (InventoryAPI.isFull())
        {
            randomizedDropAllLeapingFish();
            actionGateTick = tickCount;
            return;
        }

        Player player = client().getLocalPlayer();
        if (player != null && player.getAnimation() != -1 && currentFishSpot != null)
        {
            return;
        }

        boolean success = clickFishingSpot();
        if (success)
        {
            consumeSwitchQueueAfterClick();
            actionGateTick = tickCount;
        }
        else
        {
            handleClickSpotFailure();
            actionGateTick = tickCount;
        }
    }

    private boolean clickFishingSpot()
    {
        currentFishSpot = findSpotAtTargetOrNearest();
        if (currentFishSpot == null)
        {
            log("No fishing spot found");
            return false;
        }
        targetSpotTile = currentFishSpot.getWorldLocation();

        Player local = client().getLocalPlayer();
        if (local == null)
        {
            return false;
        }
        if (targetSpotTile.distanceTo(local.getWorldLocation()) >= 5)
        {
            log("Moved far away from target spot, repositioning");
            return false;
        }

        ClickManagerUtil.queueClickBox(currentFishSpot);
        NpcAPI.interact(currentFishSpot, "Use-rod");
        log("t=" + tickCount + " fishing: clicked spot (source=" + lastSpotSource + ")");
        if (local.getWorldLocation().distanceTo(targetSpotTile) > 1)
        {
            Delays.waitUntil(() -> {
                Player player = client().getLocalPlayer();
                return player != null && player.getWorldLocation().distanceTo(targetSpotTile) <= 1;
            }, 1500);
        }
        return true;
    }

    private NPC findSpotAtTargetOrNearest()
    {
        if (targetSpotTile != null)
        {
            NPC locked = new NpcQuery()
                    .withName("Fishing spot")
                    .withAction("Use-rod")
                    .atLocation(targetSpotTile)
                    .first();
            if (locked != null)
            {
                lastSpotSource = "locked";
                return locked;
            }
        }

        NPC nearest = new NpcQuery()
                .withName("Fishing spot")
                .withAction("Use-rod")
                .sortNearest()
                .first();
        if (nearest != null)
        {
            targetSpotTile = nearest.getWorldLocation();
            lastSpotSource = "nearest";
        }
        else
        {
            lastSpotSource = "none";
        }
        return nearest;
    }

    private void handleClickSpotFailure()
    {
        Player local = client().getLocalPlayer();
        if (local == null)
        {
            return;
        }
        if (local.getAnimation() != -1 && currentFishSpot == null)
        {
            stepToAdjacentTile();
            Delays.wait(ThreadLocalRandom.current().nextInt(1000, 5001));
            return;
        }
        if (local.getAnimation() == -1 && (currentFishSpot == null))
        {
            if (scriptStartTile == null)
            {
                scriptStartTile = local.getWorldLocation();
            }
            if (!local.getWorldLocation().equals(scriptStartTile))
            {
                MovementAPI.walkToWorldPoint(scriptStartTile);
                log("t=" + tickCount + " moving: returning to start tile " + scriptStartTile);
            }
            Delays.wait(ThreadLocalRandom.current().nextInt(500, 5001));
            return;
        }
        if (currentFishSpot != null)
        {
            MovementAPI.walkTowards(currentFishSpot.getWorldLocation());
        }
    }

    private void randomizedDropAllLeapingFish()
    {
        List<ItemEx> leapingFish = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withNameContains("Leaping")
                .collect();
        if (leapingFish.isEmpty())
        {
            return;
        }
        List<ItemEx> dropOrder = selectedDropPattern.orderItems(leapingFish);
        int dropCount = 0;
        for (ItemEx fish : dropOrder)
        {
            if (fish == null)
            {
                continue;
            }
            InventoryAPI.interact(fish, "Drop");
            dropCount++;
            Delays.wait(ThreadLocalRandom.current().nextInt(25, 221));
        }
        Delays.wait(ThreadLocalRandom.current().nextInt(50, 3001));
        if (dropCount > 0)
        {
            log("t=" + tickCount + " dropping: dropped " + dropCount + " leaping fish using " + selectedDropPattern.displayName());
        }
    }

    private boolean dropOneLeapingFish()
    {
        int count = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withNameContains("Leaping")
                .count();
        if (count <= 1)
        {
            return false;
        }
        ItemEx fish = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withNameContains("Leaping")
                .first();
        if (fish == null)
        {
            return false;
        }
        InventoryAPI.interact(fish, "Drop");
        Delays.wait(ThreadLocalRandom.current().nextInt(45, 121));
        return true;
    }

    private boolean stepToAdjacentTile()
    {
        Player local = client().getLocalPlayer();
        if (local == null)
        {
            return false;
        }
        WorldPoint me = local.getWorldLocation();
        int plane = me.getPlane();
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] offset : offsets)
        {
            WorldPoint candidate = new WorldPoint(me.getX() + offset[0], me.getY() + offset[1], plane);
            MovementAPI.walkToWorldPoint(candidate);
            log("Stepping to nearby tile: " + candidate);
            Delays.tick(1);
            return true;
        }
        return false;
    }

    private boolean hasLevelRequirements()
    {
        Client client = client();
        return client.getRealSkillLevel(Skill.FISHING) >= 48
                && client.getRealSkillLevel(Skill.STRENGTH) >= 15
                && client.getRealSkillLevel(Skill.AGILITY) >= 15;
    }

    private String missingCoreItem()
    {
        if (!hasItem("Feather"))
        {
            return "Feather";
        }
        if (!hasItem("Barbarian rod"))
        {
            return "Barbarian rod";
        }
        return "";
    }

    private boolean hasItem(String name)
    {
        ItemEx item = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName(name)
                .first();
        return item != null;
    }

    private boolean canBreak()
    {
        return nextAction == NextAction.WAIT_FOR_COMBINE || nextAction == NextAction.COMBINE_HERB || !modeScheduler.tickFishing();
    }

    private DropPattern resolveDropPatternForPlayer(Player player)
    {
        playerNameLengthForPattern = 0;
        String rawName = player.getName();
        if (rawName == null || rawName.isEmpty())
        {
            return DROP_PATTERNS.get(0);
        }
        playerNameLengthForPattern = rawName.length();
        int patternIndex = Math.floorMod(playerNameLengthForPattern, DROP_PATTERNS.size());
        return DROP_PATTERNS.get(patternIndex);
    }

    private static String formatMs(long msRemaining)
    {
        if (msRemaining <= 0)
        {
            return "00:00";
        }
        long totalSeconds = msRemaining / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private enum DropPattern
    {
        LEFT_TO_RIGHT("left to right")
        {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items)
            {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator.comparingInt(ItemEx::getSlot));
                return ordered;
            }
        },
        RIGHT_TO_LEFT("right to left")
        {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items)
            {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator.comparingInt(ItemEx::getSlot).reversed());
                return ordered;
            }
        },
        TOP_TO_BOTTOM("top to bottom")
        {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items)
            {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator
                        .comparingInt((ItemEx item) -> inventoryRow(item.getSlot()))
                        .thenComparingInt((ItemEx item) -> inventoryColumn(item.getSlot())));
                return ordered;
            }
        },
        RANDOM("random")
        {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items)
            {
                List<ItemEx> ordered = new ArrayList<>(items);
                Collections.shuffle(ordered, ThreadLocalRandom.current());
                return ordered;
            }
        };

        private final String displayName;

        DropPattern(String displayName)
        {
            this.displayName = displayName;
        }

        abstract List<ItemEx> orderItems(List<ItemEx> items);

        String displayName()
        {
            return displayName;
        }

        private static int inventoryRow(int slot)
        {
            return slot / 4;
        }

        private static int inventoryColumn(int slot)
        {
            return slot % 4;
        }
    }
}
