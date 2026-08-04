package com.ldtteam.structurize.items;

import com.ldtteam.structurize.api.constants.Constants;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Class to register items to Structurize
 */
public final class ModItems
{
    private ModItems() { /* prevent construction */ }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Constants.MOD_ID);

    /*
     *  Items
     */

    public static final DeferredItem<ItemBuildTool> buildTool;
    public static final DeferredItem<ItemShapeTool> shapeTool;
    public static final DeferredItem<ItemScanTool>  scanTool;
    public static final DeferredItem<ItemTagTool>   tagTool;
    public static final DeferredItem<ItemCaliper>  caliper;
    public static final DeferredItem<ItemTagSubstitution> blockTagSubstitution;

    static
    {
        buildTool = ITEMS.registerItem(
            "sceptergold",
            ItemBuildTool::new);

        shapeTool = ITEMS.registerItem(
            "shapetool",
            ItemShapeTool::new);

        scanTool = ITEMS.registerItem(
            "sceptersteel",
            ItemScanTool::new);

        tagTool = ITEMS.registerItem(
            "sceptertag",
            ItemTagTool::new);

        caliper = ITEMS.registerItem(
            "caliper",
            ItemCaliper::new);

        blockTagSubstitution = ITEMS.registerItem(
            "blocktagsubstitution",
            ItemTagSubstitution::new);
    }
}
