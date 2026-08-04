package com.ldtteam.structurize.items;

import com.ldtteam.structurize.client.gui.WindowShapeTool;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;

public class ItemShapeTool extends AbstractItemStructurize
{
    /**
     * Sets the name, creative tab, and registers the item.
     */
    public ItemShapeTool()
    {
        this(new Properties());
    }

    public ItemShapeTool(final Properties properties)
    {
        super("shapetool", properties.stacksTo(1));
    }

    @Override
    @SuppressWarnings("resource")
    public InteractionResult useOn(final UseOnContext context)
    {
        if (context.getLevel().isClientSide())
        {
            new WindowShapeTool(context.getClickedPos().relative(context.getClickedFace()), context.getLevel().registryAccess()).open();
        }

        return InteractionResult.SUCCESS;
    }

        @Override
    public InteractionResult use(final Level worldIn, final Player playerIn, final InteractionHand hand)
    {
        if (worldIn.isClientSide())
        {
            new WindowShapeTool(null, worldIn.registryAccess()).open();
        }

        return InteractionResult.SUCCESS;
    }


    @Override
    public ItemStackTemplate getCraftingRemainder(final ItemInstance instance)
    {
        //we want to return the shape tool when use for crafting
        return new ItemStackTemplate(this);
    }
}
