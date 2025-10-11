package com.illumine.barb3tickfishing;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("barb3tickfishing")
public interface Barb3TickFishingConfig extends Config
{
    @ConfigItem(
            keyName = "frequencyMode",
            name = "3Tick Frequency Mode",
            description = "Preferred 3-tick frequency mode.",
            hidden = true
    )
    default ThreeTickFrequencyMode frequencyMode()
    {
        return ThreeTickFrequencyMode.SOMETIMES;
    }

    @ConfigItem(
            keyName = "frequencyMode",
            name = "3Tick Frequency Mode",
            description = "Preferred 3-tick frequency mode.",
            hidden = true
    )
    void setFrequencyMode(ThreeTickFrequencyMode mode);

    @ConfigItem(
            keyName = "herbName",
            name = "Clean Herb Name",
            description = "Name of the clean herb used for the 3-tick method.",
            hidden = true
    )
    default String herbName()
    {
        return Barb3TickRuntimeConfig.DEFAULT_HERB_NAME;
    }

    @ConfigItem(
            keyName = "herbName",
            name = "Clean Herb Name",
            description = "Name of the clean herb used for the 3-tick method.",
            hidden = true
    )
    void setHerbName(String herbName);

    @ConfigItem(
            keyName = "fallbackToNormal",
            name = "Fallback to Normal",
                description = "Switch to normal fishing when 3-tick supplies run out.",
            hidden = true
    )
    default boolean fallbackToNormal()
    {
        return true;
    }

    @ConfigItem(
            keyName = "fallbackToNormal",
            name = "Fallback to Normal",
            description = "Switch to normal fishing when 3-tick supplies run out.",
            hidden = true
    )
    void setFallbackToNormal(boolean enabled);

    @ConfigItem(
            keyName = "allowWorldHop",
            name = "Allow World Hopping",
            description = "Enable timed world hopping.",
            hidden = true
    )
    default boolean allowWorldHop()
    {
        return false;
    }

    @ConfigItem(
            keyName = "allowWorldHop",
            name = "Allow World Hopping",
            description = "Enable timed world hopping.",
            hidden = true
    )
    void setAllowWorldHop(boolean enabled);

    @ConfigItem(
            keyName = "worldHopInterval",
            name = "World Hop Interval (minutes)",
            description = "Minutes between world hops.",
            hidden = true
    )
    default int worldHopIntervalMinutes()
    {
        return 10;
    }

    @ConfigItem(
            keyName = "worldHopInterval",
            name = "World Hop Interval (minutes)",
            description = "Minutes between world hops.",
            hidden = true
    )
    void setWorldHopIntervalMinutes(int minutes);
}
