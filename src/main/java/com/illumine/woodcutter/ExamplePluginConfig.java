package com.illumine.woodcutter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("exampleplugin")
public interface ExamplePluginConfig extends Config
{
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
