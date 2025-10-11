package com.illumine.barb3tickfishing;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.game.GameAPI;
import com.tonic.api.game.MovementAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.ItemEx;
import com.tonic.services.GameManager;
import com.tonic.util.ClickManagerUtil;
import com.tonic.util.VitaPlugin;
import com.tonic.queries.InventoryQuery;
import com.tonic.queries.NpcQuery;
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
import java.util.Objects;

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
    private Barb3TickRuntime runtime;

    private WorldPoint targetTile = null;

    @Provides
    Barb3TickFishingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(Barb3TickFishingConfig.class);
    }

    @Override
    protected void startUp() {
        panel = injector.getInstance(Barb3TickFishingSidePanel.class);
        config = injector.getInstance(Barb3TickFishingConfig.class);
        runtime = new Barb3TickRuntime(this, panel, config);
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
        runtime = null;
        resetSimpleCycle();
    }

    @Override
    public void loop() {
        if (panel == null || !panel.isRunning()) {
            return;
        }

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
        if (runtime != null) {
            runtime.onFrequencyModeChanged(mode);
        }
        log("Frequency mode changed to " + mode);
    }

    void onHerbNameChanged(String herbName) {
        if (runtime != null) {
            runtime.onHerbNameChanged(herbName);
        }
        log("Herb name set to " + herbName);
    }

    void onFallbackChanged(boolean enabled) {
        if (runtime != null) {
            runtime.onFallbackChanged(enabled);
        }
        log("Fallback to normal fishing " + (enabled ? "enabled" : "disabled"));
    }

    void onWorldHopToggle(boolean enabled) {
        if (runtime != null) {
            runtime.onWorldHopToggle(enabled);
        }
        log("World hopping " + (enabled ? "enabled" : "disabled"));
    }

    void onHopIntervalChanged(int minutes) {
        if (runtime != null) {
            runtime.onHopIntervalChanged(minutes);
        }
        log("World hop interval set to " + minutes + " minute(s)");
    }

    void onStartRequested() {
        if (panel != null) {
            panel.setRunning(true);
        }
        resetSimpleCycle();
        log("Start requested");
    }

    void onStopRequested() {
        if (panel != null) {
            panel.setRunning(false);
        }
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

    private void resetSimpleCycle() {
        targetTile = null;
    }
}
