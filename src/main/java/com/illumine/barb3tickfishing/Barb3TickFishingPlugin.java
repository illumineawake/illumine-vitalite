package com.illumine.barb3tickfishing;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.ActorAPI;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.PlayerAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.ItemEx;
import com.tonic.queries.PlayerQuery;
import com.tonic.services.GameManager;
import com.tonic.util.ClickManagerUtil;
import com.tonic.util.VitaPlugin;
import com.tonic.queries.InventoryQuery;
import com.tonic.queries.NpcQuery;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

@PluginDescriptor(
        name = "illu 3Tick Barb Fishing",
        description = "3-tick barbarian fishing with smart mode scheduling",
        tags = {"fishing", "barbarian", "3tick", "vitalite", "illumine"}
)
public class Barb3TickFishingPlugin extends VitaPlugin implements WorldHopController.Host {
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Client client;

    private NavigationButton navButton;
    private Barb3TickFishingSidePanel panel;
    private Barb3TickFishingConfig config;
    private Barb3TickRuntimeConfig runtimeConfig;
    private ModeScheduler modeScheduler;
    private boolean schedulerInitialized = false;
    private long startTimeMs = 0L;
    private long currentModeEnteredAtMs = 0L;
    private long threeTickAccumulatedMs = 0L;

    private WorldPoint targetTile = null;
    private static final List<DropPattern> DROP_PATTERNS = List.of(
            DropPattern.LEFT_TO_RIGHT,
            DropPattern.RIGHT_TO_LEFT,
            DropPattern.TOP_TO_BOTTOM,
            DropPattern.RANDOM
    );
    private DropPattern selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
    private int playerNameLengthForPattern = 0;

    private WorldHopController worldHopController;

    @Provides
    Barb3TickFishingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(Barb3TickFishingConfig.class);
    }

    @Override
    protected void startUp() {
        panel = injector.getInstance(Barb3TickFishingSidePanel.class);
        config = injector.getInstance(Barb3TickFishingConfig.class);
        runtimeConfig = new Barb3TickRuntimeConfig();
        runtimeConfig.applyFromConfig(config);
        modeScheduler = null;
        resetSimpleCycle();
        worldHopController = new WorldHopController(this);
        worldHopController.reset();

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

        navButton = NavigationButton.builder()
                .tooltip("illu 3Tick Barb Fishing")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
        panel.attachPlugin(this);
        updatePanelStatus();
    }

    @Override
    protected void shutDown() {
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        if (panel != null) {
            panel.shutdown();
            panel = null;
        }
        modeScheduler = null;
        runtimeConfig = null;
        resetSimpleCycle();
        if (worldHopController != null) {
            worldHopController.reset();
            worldHopController = null;
        }
    }

    @Override
    public void loop() {
        if (panel == null || !panel.isRunning()) {
            return;
        }

        ensureSchedulerReady();

        long now = System.currentTimeMillis();
        if (worldHopController != null) {
            worldHopController.updateHopDue(now);
            if (worldHopController.canWorldHop(true)) {
                boolean hopped = worldHopController.performWorldHop();
                worldHopController.scheduleNextHop();
                if (!hopped) {
                    resetSimpleCycle();
                    Delays.wait(ThreadLocalRandom.current().nextInt(400, 800));
                }
                updatePanelStatus();
                return;
            }
        }
        if (modeScheduler.modeExpiresAtMs() > 0 && now >= modeScheduler.modeExpiresAtMs()) {
            modeScheduler.onWindowExpired();
        }

        processQueuedSwitchIfNeeded();

        if (modeScheduler.tickFishing()) {
            executeThreeTickCycle();
        } else {
            executeNormalCycle();
        }

        updatePanelStatus();
    }

    private void executeThreeTickCycle() {
        if (!attemptClickFishingSpot()) {
            Delays.tick();
            return;
        }

        Delays.tick(2);

        if (panel == null || !panel.isRunning()) {
            return;
        }

        if (!attemptCombineAndDrop()) {
            Delays.tick(1);
        }
    }

    private void executeNormalCycle() {
        if (InventoryAPI.isFull()) {
            dropAllLeapingFish();
            return;
        }

        Actor interacting = client.getLocalPlayer().getInteracting();

        if (interacting != null) {
            return;
        }

        if (!attemptClickFishingSpot()) {
            Delays.tick();
        }
    }

    @Override
    public Client getClient() {
        return client;
    }

    public Barb3TickFishingConfig getConfig() {
        return config;
    }

    @Override
    public void log(String message) {
        Logger.info("[illu3TBarb] " + "[" + GameManager.getTickCount() + "]" + message);
    }

    @Override
    public void onWorldHopSuccess() {
        resetSimpleCycle();
    }

    void onFrequencyModeChanged(ThreeTickFrequencyMode mode) {
        ensureRuntimeConfigPresent();
        runtimeConfig.setFrequencyMode(mode);
        if (modeScheduler != null) {
            finalizeActiveThreeTick(System.currentTimeMillis());
            modeScheduler.reset();
        }
        schedulerInitialized = false;
        log("Frequency mode changed to " + mode);
        updatePanelStatus();
    }

    void onHerbNameChanged(String herbName) {
        ensureRuntimeConfigPresent();
        runtimeConfig.setHerbName(herbName);
        log("Herb name set to " + herbName);
        updatePanelStatus();
    }

    void onFallbackChanged(boolean enabled) {
        ensureRuntimeConfigPresent();
        runtimeConfig.setSwitchToNormalOnSuppliesOut(enabled);
        log("Fallback to normal fishing " + (enabled ? "enabled" : "disabled"));
        updatePanelStatus();
    }

    void onWorldHopToggle(boolean enabled) {
        if (worldHopController != null) {
            int interval = Math.max(1, config != null ? config.worldHopIntervalMinutes() : 10);
            worldHopController.setHopIntervalMinutes(interval);
            worldHopController.setHopEnabled(enabled);
        }
        log("World hopping " + (enabled ? "enabled" : "disabled"));
        updatePanelStatus();
    }

    void onHopIntervalChanged(int minutes) {
        if (worldHopController != null) {
            worldHopController.setHopIntervalMinutes(Math.max(1, minutes));
        }
        log("World hop interval set to " + minutes + " minute(s)");
        updatePanelStatus();
    }

    void onStartRequested() {
        if (panel != null) {
            panel.setRunning(true);
        }
        ensureRuntimeConfigPresent();
        runtimeConfig.applyFromConfig(config);
        if (modeScheduler == null) {
            modeScheduler = new ModeScheduler(runtimeConfig, this::log);
        }
        modeScheduler.reset();
        modeScheduler.initialiseMode();
        schedulerInitialized = true;
        long now = System.currentTimeMillis();
        startTimeMs = now;
        currentModeEnteredAtMs = now;
        threeTickAccumulatedMs = 0L;
        resetSimpleCycle();
        if (worldHopController != null) {
            worldHopController.initialize(config.allowWorldHop(), config.worldHopIntervalMinutes());
        }
        updatePanelStatus();
        log("Start requested");
    }

    void onStopRequested() {
        if (panel != null) {
            panel.setRunning(false);
        }
        long now = System.currentTimeMillis();
        finalizeActiveThreeTick(now);
        if (modeScheduler != null) {
            modeScheduler.reset();
        }
        schedulerInitialized = false;
        startTimeMs = 0L;
        currentModeEnteredAtMs = 0L;
        threeTickAccumulatedMs = 0L;
        resetSimpleCycle();
        if (worldHopController != null) {
            worldHopController.reset();
        }
        updatePanelStatus();
        log("Stop requested");
    }

    private boolean attemptClickFishingSpot() {
        NPC spot = findFishingSpot();
        if (spot == null) {
            log("No fishing spot available");
            return false;
        }
        targetTile = spot.getWorldLocation();
        ClickManagerUtil.queueClickBox(spot);
        NpcAPI.interact(spot, "Use-rod");
        log("Fishing: clicked spot (simple)");
        return true;
    }

    private boolean attemptCombineAndDrop() {
        ItemEx tar = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName("Swamp tar")
                .first();
        ItemEx herb = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName(config.herbName())
                .first();

        if (tar == null || herb == null) {
            log("Missing tar or herb for combine");
            return false;
        }

        InventoryAPI.useOn(tar, herb);
        log("Combining: used tar on herb (simple)");
        dropOneLeapingFish();
        return true;
    }

    private NPC findFishingSpot() {
        if (targetTile != null) {
            NPC locked = new NpcQuery()
                    .withName("Fishing spot")
                    .withAction("Use-rod")
                    .atLocation(targetTile)
                    .first();
            if (locked != null) {
                return locked;
            }
        }

        NPC nearest = new NpcQuery()
                .withName("Fishing spot")
                .withAction("Use-rod")
                .nearest();
        if (nearest != null) {
            targetTile = nearest.getWorldLocation();
        }
        return nearest;
    }

    private void dropOneLeapingFish() {
        ItemEx fish = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withNameContains("Leaping")
                .first();
        if (fish != null) {
            InventoryAPI.interact(fish, "Drop");
            log("t=" + client.getTickCount() + " dropping: dropped one leaping fish (simple)");
        }
    }

    private void dropAllLeapingFish() {
        List<ItemEx> leapingFish = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withNameContains("Leaping")
                .collect();
        if (leapingFish.isEmpty()) {
            return;
        }

        DropPattern pattern = resolveDropPatternForPlayer();
        List<ItemEx> dropOrder = pattern.orderItems(leapingFish);
        int dropped = 0;
        for (ItemEx fish : dropOrder) {
            if (fish == null) {
                continue;
            }
            InventoryAPI.interact(fish, "Drop");
            dropped++;
            Delays.wait(ThreadLocalRandom.current().nextInt(25, 500));
        }
        if (dropped > 0) {
            Delays.wait(ThreadLocalRandom.current().nextInt(50, 3000));
            log("Normal: dropped " + dropped + " leaping fish using " + pattern.displayName());
        }
    }

    private DropPattern resolveDropPatternForPlayer() {
        String rawName = client.getLocalPlayer().getName();
        if (rawName == null || rawName.isEmpty()) {
            selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
            playerNameLengthForPattern = 0;
            return selectedDropPattern;
        }
        int length = rawName.length();
        if (length != playerNameLengthForPattern) {
            playerNameLengthForPattern = length;
            int index = Math.floorMod(length, DROP_PATTERNS.size());
            selectedDropPattern = DROP_PATTERNS.get(index);
            log("Normal: drop pattern set to " + selectedDropPattern.displayName());
        }
        return selectedDropPattern;
    }

    private void resetSimpleCycle() {
        targetTile = null;
        selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
        playerNameLengthForPattern = 0;
    }

    private void ensureRuntimeConfigPresent() {
        if (runtimeConfig == null) {
            runtimeConfig = new Barb3TickRuntimeConfig();
            if (config != null) {
                runtimeConfig.applyFromConfig(config);
            }
        }
    }

    private void ensureSchedulerReady() {
        ensureRuntimeConfigPresent();
        if (modeScheduler == null) {
            modeScheduler = new ModeScheduler(runtimeConfig, this::log);
        }
        if (!schedulerInitialized) {
            modeScheduler.reset();
            modeScheduler.initialiseMode();
            schedulerInitialized = true;
            if (panel != null && panel.isRunning()) {
                currentModeEnteredAtMs = System.currentTimeMillis();
            }
        }
    }

    private void processQueuedSwitchIfNeeded() {
        if (modeScheduler == null || !modeScheduler.switchQueued()) {
            return;
        }
        FishingMode nextMode = modeScheduler.tickFishing() ? FishingMode.NORMAL : FishingMode.THREE_TICK;
        applyScheduledMode(nextMode);
        modeScheduler.clearQueue();
        resetSimpleCycle();
    }

    private void applyScheduledMode(FishingMode nextMode) {
        if (modeScheduler == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (modeScheduler.tickFishing() && currentModeEnteredAtMs > 0L) {
            threeTickAccumulatedMs += Math.max(0L, now - currentModeEnteredAtMs);
        }
        modeScheduler.setFishingMode(nextMode);
        currentModeEnteredAtMs = now;
    }

    private void finalizeActiveThreeTick(long now) {
        if (modeScheduler != null && modeScheduler.tickFishing() && currentModeEnteredAtMs > 0L) {
            threeTickAccumulatedMs += Math.max(0L, now - currentModeEnteredAtMs);
        }
    }

    private void updatePanelStatus() {
        if (panel == null) {
            return;
        }
        boolean running = panel.isRunning();
        String modeLabel;
        if (running && modeScheduler != null) {
            modeLabel = modeScheduler.tickFishing() ? "3Tick" : "Normal";
        } else {
            modeLabel = "Idle";
        }

        Barb3TickFishingSidePanel.StatusSnapshot snapshot = new Barb3TickFishingSidePanel.StatusSnapshot(
                modeLabel,
                getFrequencyDisplay(),
                formatThreeTickShare(),
                formatSwitchCountdown(),
                formatWorld(),
                formatNextHop(),
                formatSuppliesStatus()
        );
        panel.updateStatus(snapshot);
    }

    private String getFrequencyDisplay() {
        ensureRuntimeConfigPresent();
        ThreeTickFrequencyMode currentMode = runtimeConfig.frequencyMode();
        if (currentMode == ThreeTickFrequencyMode.RANDOM && modeScheduler != null) {
            ThreeTickFrequencyMode profile = modeScheduler.activeRandomProfile();
            String current = (panel != null && panel.isRunning() && modeScheduler.tickFishing()) ? "3T" : "Normal";
            String profLabel = (profile == null) ? "—" : profile.shortLabel();
            return "Random(" + current + " " + profLabel + ")";
        }
        return currentMode.label();
    }

    private String formatThreeTickShare() {
        if (startTimeMs <= 0L) {
            return "0.0%";
        }
        long now = System.currentTimeMillis();
        long total = Math.max(1L, now - startTimeMs);
        long threeTickTime = threeTickAccumulatedMs;
        if (modeScheduler != null && modeScheduler.tickFishing() && currentModeEnteredAtMs > 0L) {
            threeTickTime += Math.max(0L, now - currentModeEnteredAtMs);
        }
        double share = Math.max(0.0, Math.min(100.0, (double) threeTickTime / (double) total * 100.0));
        return String.format(Locale.ENGLISH, "%.1f%%", share);
    }

    private String formatSwitchCountdown() {
        if (panel == null || !panel.isRunning() || modeScheduler == null || runtimeConfig == null) {
            return "N/A";
        }
        if (!runtimeConfig.switchingEnabled()) {
            return "N/A";
        }
        long remaining = modeScheduler.modeExpiresAtMs() - System.currentTimeMillis();
        if (remaining <= 0L) {
            return "0s";
        }
        return formatDurationShort(remaining);
    }

    private String formatWorld() {
        if (worldHopController != null) {
            String label = worldHopController.formatCurrentWorld();
            if (!"—".equals(label) && label != null && !label.isBlank()) {
                return label.startsWith("W") ? label.substring(1) : label;
            }
        }
        if (client == null || client.getLocalPlayer() == null) {
            return "—";
        }
        int world = client.getWorld();
        return world > 0 ? Integer.toString(world) : "—";
    }

    private String formatNextHop() {
        if (panel == null || !panel.isRunning() || worldHopController == null) {
            return "N/A";
        }
        String countdown = worldHopController.formatTimeToHop();
        if (countdown == null || countdown.isBlank() || "—".equals(countdown)) {
            return "N/A";
        }
        if ("00:00".equals(countdown)) {
            return "0s";
        }
        return countdown;
    }

    private String formatSuppliesStatus() {
        if (panel == null || !panel.isRunning() || client == null || client.getLocalPlayer() == null) {
            return "—";
        }
        ensureRuntimeConfigPresent();
        if (runtimeConfig.frequencyMode() == ThreeTickFrequencyMode.NEVER) {
            return "N/A";
        }
        ItemEx tar = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName("Swamp tar")
                .first();
        ItemEx herb = InventoryQuery.fromInventoryId(InventoryID.INV)
                .withName(runtimeConfig.herbName())
                .first();
        return (tar != null && herb != null) ? "OK" : "Check";
    }

    private static String formatDurationShort(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ENGLISH, "%dh%02dm", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.ENGLISH, "%dm%02ds", minutes, seconds);
        }
        return String.format(Locale.ENGLISH, "%ds", seconds);
    }

    private enum DropPattern {
        LEFT_TO_RIGHT("left to right") {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items) {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator.comparingInt(ItemEx::getSlot));
                return ordered;
            }
        },
        RIGHT_TO_LEFT("right to left") {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items) {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator.comparingInt(ItemEx::getSlot).reversed());
                return ordered;
            }
        },
        TOP_TO_BOTTOM("top to bottom") {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items) {
                List<ItemEx> ordered = new ArrayList<>(items);
                ordered.sort(Comparator
                        .comparingInt((ItemEx item) -> inventoryRow(item.getSlot()))
                        .thenComparingInt((ItemEx item) -> inventoryColumn(item.getSlot())));
                return ordered;
            }
        },
        RANDOM("random") {
            @Override
            List<ItemEx> orderItems(List<ItemEx> items) {
                List<ItemEx> ordered = new ArrayList<>(items);
                Collections.shuffle(ordered, ThreadLocalRandom.current());
                return ordered;
            }
        };

        private final String displayName;

        DropPattern(String displayName) {
            this.displayName = displayName;
        }

        abstract List<ItemEx> orderItems(List<ItemEx> items);

        String displayName() {
            return displayName;
        }

        private static int inventoryRow(int slot) {
            return slot / 4;
        }

        private static int inventoryColumn(int slot) {
            return slot % 4;
        }
    }
}
