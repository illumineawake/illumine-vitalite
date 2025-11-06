package com.illumine.woodcutter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("exampleplugin")
public interface ExamplePluginConfig extends Config
{
    // Reachability logger configuration
    @ConfigItem(
            keyName = "targetType",
            name = "Target Type",
            description = "NPC or Object to query for reachability"
    )
    default TargetType getTargetType() { return TargetType.OBJECT; }
    @ConfigItem(
            keyName = "targetType",
            name = "Target Type",
            description = "NPC or Object to query for reachability"
    )
    void setTargetType(TargetType type);

    @ConfigItem(
            keyName = "targetName",
            name = "Target Name Contains",
            description = "Substring to search (case-insensitive)"
    )
    default String getTargetName() { return ""; }
    @ConfigItem(
            keyName = "targetName",
            name = "Target Name Contains",
            description = "Substring to search (case-insensitive)"
    )
    void setTargetName(String name);

    @ConfigItem(
            keyName = "logIntervalMs",
            name = "Log Interval (ms)",
            description = "Minimum ms between identical logs"
    )
    default int getLogIntervalMs() { return 2000; }
    @ConfigItem(
            keyName = "logIntervalMs",
            name = "Log Interval (ms)",
            description = "Minimum ms between identical logs"
    )
    void setLogIntervalMs(int ms);

    @ConfigItem(
            keyName = "logEdgeDebug",
            name = "Log Edge Debug",
            description = "Log source-tile edge block flags when unreachable",
            position = 11
    )
    default boolean getLogEdgeDebug() { return false; }
    @ConfigItem(
            keyName = "logEdgeDebug",
            name = "Log Edge Debug",
            description = "Log source-tile edge block flags when unreachable",
            position = 11
    )
    void setLogEdgeDebug(boolean v);

    // Legacy fields retained for compatibility (not used)
    @ConfigItem(
            keyName = "dropStrategy",
            name = "Drop Strategy",
            description = "Choose your item drop strategy",
            hidden = true
    )
    default DropStrategy getDropStrategy()
    {
        return DropStrategy.DROP_FULL;
    }

    @ConfigItem(
            keyName = "dropStrategy",
            name = "Drop Strategy",
            description = "Choose your item drop strategy",
            hidden = true
    )
    void setDropStrategy(DropStrategy strategy);
}
