package com.illumine.barb3tickfishing;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.ActorAPI;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.PlayerAPI;
import com.tonic.api.game.MovementAPI;
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
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@PluginDescriptor(
        name = "illu 3Tick Barb Fishing",
        description = "3-tick barbarian fishing with smart mode scheduling and Vitalite UI integration.",
        tags = {"fishing", "barbarian", "3tick", "vitalite", "illumine"}
)
public class Barb3TickFishingPlugin extends VitaPlugin {
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

    private WorldPoint targetTile = null;
    private static final List<DropPattern> DROP_PATTERNS = List.of(
            DropPattern.LEFT_TO_RIGHT,
            DropPattern.RIGHT_TO_LEFT,
            DropPattern.TOP_TO_BOTTOM,
            DropPattern.RANDOM
    );
    private DropPattern selectedDropPattern = DropPattern.LEFT_TO_RIGHT;
    private int playerNameLengthForPattern = 0;

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

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

        navButton = NavigationButton.builder()
                .tooltip("illu 3Tick Barb Fishing")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
        panel.attachPlugin(this);
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
    }

    @Override
    public void loop() {
        if (panel == null || !panel.isRunning()) {
            return;
        }

        ensureSchedulerReady();

        long now = System.currentTimeMillis();
        if (modeScheduler.modeExpiresAtMs() > 0 && now >= modeScheduler.modeExpiresAtMs()) {
            modeScheduler.onWindowExpired();
        }

        processQueuedSwitchIfNeeded();

        if (!modeScheduler.tickFishing()) {
            executeNormalCycle();
            return;
        }

        executeThreeTickCycle();
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

    public Client getClient() {
        return client;
    }

    public Barb3TickFishingConfig getConfig() {
        return config;
    }

    void log(String message) {
        Logger.info("[illu3TBarb] " + "[" + GameManager.getTickCount() + "]" + message);
    }

    void onFrequencyModeChanged(ThreeTickFrequencyMode mode) {
        ensureRuntimeConfigPresent();
        runtimeConfig.setFrequencyMode(mode);
        if (modeScheduler != null) {
            modeScheduler.reset();
        }
        schedulerInitialized = false;
        log("Frequency mode changed to " + mode);
    }

    void onHerbNameChanged(String herbName) {
        log("Herb name set to " + herbName);
    }

    void onFallbackChanged(boolean enabled) {
        ensureRuntimeConfigPresent();
        runtimeConfig.setSwitchToNormalOnSuppliesOut(enabled);
        log("Fallback to normal fishing " + (enabled ? "enabled" : "disabled"));
    }

    void onWorldHopToggle(boolean enabled) {
        log("World hopping " + (enabled ? "enabled" : "disabled"));
    }

    void onHopIntervalChanged(int minutes) {
        log("World hop interval set to " + minutes + " minute(s)");
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
        resetSimpleCycle();
        log("Start requested");
    }

    void onStopRequested() {
        if (panel != null) {
            panel.setRunning(false);
        }
        if (modeScheduler != null) {
            modeScheduler.reset();
        }
        schedulerInitialized = false;
        resetSimpleCycle();
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
            Delays.wait(ThreadLocalRandom.current().nextInt(25, 221));
        }
        if (dropped > 0) {
            Delays.wait(ThreadLocalRandom.current().nextInt(50, 3001));
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
        }
    }

    private void processQueuedSwitchIfNeeded() {
        if (modeScheduler == null || !modeScheduler.switchQueued()) {
            return;
        }
        FishingMode nextMode = modeScheduler.tickFishing() ? FishingMode.NORMAL : FishingMode.THREE_TICK;
        modeScheduler.setFishingMode(nextMode);
        modeScheduler.clearQueue();
        resetSimpleCycle();
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
