package com.ldtteam.structurize.blocks.schematic;

import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.ldtteam.structurize.blocks.interfaces.IAnchorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.NotNull;
import javax.annotation.Nullable;

/**
 * This block is a substitution block (it disappears on normal build) but stores blueprint data (mostly tags) during scan.
 */
public class BlockTagSubstitution extends BlockSubstitution implements IAnchorBlock, EntityBlock
{
    public BlockTagSubstitution(final BlockBehaviour.Properties properties)
    {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final @NotNull BlockPos blockPos, final @NotNull BlockState blockState)
    {
        return new BlockEntityTagSubstitution(blockPos, blockState);
    }

    @NotNull
    @Override
    @SuppressWarnings("deprecation")
    public ItemStack getCloneItemStack(@NotNull final LevelReader level,
        @NotNull final BlockPos pos,
        @NotNull final BlockState blockState,
        final boolean includeData)
    {
        return cloneItemStack(super.getCloneItemStack(level, pos, blockState, includeData), level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData, Player player)
    {
        return cloneItemStack(super.getCloneItemStack(level, pos, state, includeData, player), level, pos);
    }

    private ItemStack cloneItemStack(final ItemStack stack, LevelReader level, BlockPos pos)
    {
        if (level.getBlockEntity(pos) instanceof final BlockEntityTagSubstitution entity)
        {
            // PORT-26.1.2: BlockEntity#saveToItem(ItemStack, RegistryAccess) was removed in favour of writing
            // custom data to a TagValueOutput and handing it to BlockItem#setBlockEntityData; see PORT_26.1.2.md.
            final TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            entity.saveCustomOnly(output);
            BlockItem.setBlockEntityData(stack, entity.getType(), output);
        }
        return stack;
    }
}
