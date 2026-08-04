package com.ldtteam.structurize.items;

import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import static com.ldtteam.structurize.api.constants.Constants.GROUNDSTYLE_RELATIVE;
/**
import net.minecraft.world.item.Item.Properties;

 * Class handling the buildTool item.
 */
public class ItemBuildTool extends AbstractItemStructurize
{
    /**
     * Instantiates the buildTool on load.
     */
    public ItemBuildTool()
    {
        this(new Properties());
    }

    public ItemBuildTool(final Properties properties)
    {
        super("sceptergold", properties.stacksTo(1));
    }

    @Override
    @SuppressWarnings("resource")
    public InteractionResult useOn(final UseOnContext context)
    {
        if (context.getLevel().isClientSide())
        {
            openBuildToolWindow(context.getClickedPos().relative(context.getClickedFace()), GROUNDSTYLE_RELATIVE, context.getLevel().registryAccess());
        }
        return InteractionResult.SUCCESS;
    }

        @Override
    public InteractionResult use(final Level worldIn, final Player playerIn, final InteractionHand handIn)
    {
        if (worldIn.isClientSide())
        {
            openBuildToolWindow(null, GROUNDSTYLE_RELATIVE, worldIn.registryAccess());
        }

        return InteractionResult.SUCCESS;
    }

    private static void openBuildToolWindow(final BlockPos pos, final int groundstyle, final HolderLookup.Provider provider)
    {
        if (Minecraft.getInstance().screen != null)
        {
            return;
        }

        new WindowExtendedBuildTool(pos, groundstyle, null, WindowExtendedBuildTool.BLOCK_BLUEPRINT_REQUIREMENT, provider).open();
    }

    @Override
    public ItemStackTemplate getCraftingRemainder(final ItemInstance instance)
    {
        //we want to return the build tool when use for crafting
        return new ItemStackTemplate(this);
    }
}
