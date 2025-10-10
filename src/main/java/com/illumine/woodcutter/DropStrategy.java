package com.illumine.woodcutter;

import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.ItemContainerEx;
import com.tonic.data.ItemEx;
import com.tonic.util.ClickManagerUtil;
import net.runelite.api.gameval.InventoryID;

import java.util.List;
import java.util.function.Predicate;

public enum DropStrategy
{
    DROP_FULL(v -> InventoryAPI.isFull()),
    DROP_EACH(v -> InventoryAPI.contains("Logs"))
    ;

    private final Predicate<Void> condition;

    DropStrategy(Predicate<Void> condition)
    {
        this.condition = condition;
    }

    public boolean process()
    {
        if(condition.test(null))
        {
            dropLogs();
            return true;
        }
        return false;
    }

    private void dropLogs() {
        ItemContainerEx container = new ItemContainerEx(InventoryID.INV);
        List<ItemEx> items = container.getAll("Logs");
        for(ItemEx item : items) {
            ClickManagerUtil.queueClickBox(item);
            InventoryAPI.interact(item, "Drop");
        }
    }
}
