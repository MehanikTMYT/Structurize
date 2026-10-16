package com.ldtteam.structurize.client;

import com.ldtteam.structurize.client.fakelevel.BlueprintBlockAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * PORT(26.1): a blueprint seen as something the block renderer can draw from.
 * <p>
 * {@code BlockAndTintGetter} became client-only in 26.1 and {@code Level} no longer implements it, so the fake level
 * behind a blueprint is not one any more. Blocks, block entities, light and model data are the blueprint's own; biome
 * tint and the dimension's cardinal lighting come from the real client level at the preview's position, as they did
 * when the fake level was handed to the 1.21 block renderer directly.
 *
 * @param access the blueprint's fake level
 * @param anchor world position of the blueprint's origin, for biome tint lookups
 */
public record BlueprintRenderView(BlueprintBlockAccess access, BlockPos anchor) implements BlockAndTintGetter
{
    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos)
    {
        return access.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(final BlockPos pos)
    {
        return access.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(final BlockPos pos)
    {
        return access.getFluidState(pos);
    }

    @Override
    public int getHeight()
    {
        return access.getHeight();
    }

    @Override
    public int getMinY()
    {
        return access.getMinY();
    }

    @Override
    public LevelLightEngine getLightEngine()
    {
        return access.getLightEngine();
    }

    @Override
    public int getBrightness(final LightLayer layer, final BlockPos pos)
    {
        return access.getBrightness(layer, pos);
    }

    @Override
    public int getRawBrightness(final BlockPos pos, final int darkening)
    {
        return access.getRawBrightness(pos, darkening);
    }

    @Override
    public ModelData getModelData(final BlockPos pos)
    {
        return access.getModelData(pos);
    }

    @Override
    public CardinalLighting cardinalLighting()
    {
        return Minecraft.getInstance().level != null ? Minecraft.getInstance().level.cardinalLighting() : CardinalLighting.DEFAULT;
    }

    @Override
    public int getBlockTint(final BlockPos pos, final ColorResolver color)
    {
        return Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getBlockTint(anchor.offset(pos), color) : -1;
    }
}
