package com.illumine.barb3tickfishing;

import com.tonic.api.threaded.Delays;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.ItemEx;
import com.tonic.queries.InventoryQuery;
import net.runelite.api.gameval.InventoryID;

class SuppliesManager
{
    private final Barb3TickRuntime runtime;
    private String herbName = Barb3TickRuntimeConfig.DEFAULT_HERB_NAME;
    private boolean fallbackTriggered = false;

    SuppliesManager(Barb3TickRuntime runtime)
    {
        this.runtime = runtime;
    }

    void reset()
    {
        herbName = Barb3TickRuntimeConfig.DEFAULT_HERB_NAME;
        fallbackTriggered = false;
    }

    void setHerbName(String herbName)
    {
        if (herbName == null)
        {
            this.herbName = Barb3TickRuntimeConfig.DEFAULT_HERB_NAME;
            return;
        }
        String trimmed = herbName.trim();
        this.herbName = trimmed.isEmpty() ? Barb3TickRuntimeConfig.DEFAULT_HERB_NAME : trimmed;
    }

    String herbName()
    {
        return herbName;
    }

    boolean hasThreeTickSuppliesAvailable()
    {
        return hasItem("Swamp tar") && canObtainCleanHerb();
    }

    boolean ensureSuppliesForActiveMode()
    {
        return ensureSupplies(true);
    }

    boolean ensureSuppliesForUpcomingMode()
    {
        return ensureSupplies(true);
    }

    void handleOutOfSupplies(String missingItem, boolean shouldSwitchToNormal)
    {
        if (fallbackTriggered)
        {
            return;
        }
        if (missingItem == null || missingItem.isBlank())
        {
            missingItem = determineMissingSupply();
        }
        if (missingItem == null || missingItem.isBlank())
        {
            missingItem = "3T supplies";
        }

        if (!shouldSwitchToNormal)
        {
            runtime.log("Stopping, out of 3T supplies: " + missingItem);
            fallbackTriggered = true;
            runtime.requestStop("Out of 3T supplies");
            return;
        }

        runtime.log("Fallback, out of 3T supplies (" + missingItem + "), switching to normal fishing");
        fallbackTriggered = true;
        runtime.switchToPermanentNormalMode();
    }

    String determineMissingSupply()
    {
        if (!hasItem("Swamp tar"))
        {
            return "Swamp tar";
        }
        if (!canObtainCleanHerb())
        {
            return herbName;
        }
        return "";
    }

    private boolean ensureSupplies(boolean attemptClean)
    {
        if (!hasItem("Swamp tar"))
        {
            handleOutOfSupplies("Swamp tar", runtime.config().switchToNormalOnSuppliesOut());
            return false;
        }
        if (!hasItem(herbName))
        {
            if (attemptClean && cleanHerb())
            {
                Delays.wait(200);
                return false;
            }
            handleOutOfSupplies(herbName, runtime.config().switchToNormalOnSuppliesOut());
            return false;
        }
        return true;
    }

    private boolean canObtainCleanHerb()
    {
        if (hasItem(herbName))
        {
            return true;
        }
        ItemEx cleanable = findCleanableHerb();
        return cleanable != null;
    }

    private boolean hasItem(String name)
    {
        ItemEx item = InventoryQuery.fromInventoryId(InventoryID.INV)
                .keepIf(i -> i.getName() != null && i.getName().equalsIgnoreCase(name))
                .first();
        return item != null;
    }

    private boolean cleanHerb()
    {
        ItemEx cleanable = findCleanableHerb();
        if (cleanable != null)
        {
            InventoryAPI.interact(cleanable, "Clean");
            Delays.wait(225);
            return true;
        }
        return false;
    }

    private ItemEx findCleanableHerb()
    {
        return InventoryQuery.fromInventoryId(InventoryID.INV)
                .keepIf(item -> {
                    String name = item.getName();
                    return name != null && name.toLowerCase().contains("grimy");
                })
                .keepIf(item -> item.hasAction("Clean"))
                .first();
    }
}
