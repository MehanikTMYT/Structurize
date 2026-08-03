package com.ldtteam.structurize.blueprints.v1;

import com.ldtteam.structurize.client.BlueprintBlockInfoTransformHandler;
import com.ldtteam.structurize.client.BlueprintEntityInfoTransformHandler;
import com.ldtteam.structurize.api.Log;
import com.ldtteam.structurize.util.BlockEntityInfo;
import com.ldtteam.structurize.util.BlockInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.neoforged.neoforge.model.data.ModelData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility functions for blueprints.
 */
public final class BlueprintUtils
{
    private BlueprintUtils()
    {
        throw new IllegalArgumentException("Utils class");
    }

    /**
     * Creates a list of tileentities located in the blueprint, placed inside that blueprints block access world.
     *
     * @param blueprint   The blueprint whos tileentities need to be instantiated.
     * @param beLevel The blueprint world.
     * @return A list of tileentities in the blueprint.
     */
    public static Map<BlockPos, BlockEntity> instantiateTileEntities(final Blueprint blueprint, final Level beLevel, final Map<BlockPos, ModelData> teModelData)
    {
        return blueprint.getBlockInfoAsList()
            .stream()
            .map(blockInfo -> BlueprintBlockInfoTransformHandler.getInstance().Transform(blockInfo))
            .filter(BlockInfo::hasTileEntityData)
            .map(blockInfo -> {
                @Nullable
                final BlockEntity be = constructTileEntity(blockInfo, beLevel, blueprint.getRegistryAccess());
                if (be != null)
                {
                    teModelData.put(blockInfo.getPos(), be.getModelData());
                    return new BlockEntityInfo(blockInfo.getPos(), be);
                }
                else
                {
                    Log.getLogger().error("TileEntity creation failed for: " + blueprint + " " + blockInfo.getPos());
                }
                return null;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(BlockEntityInfo::pos, BlockEntityInfo::blockEntity));
    }

    /**
     * Creates a list of entities located in the blueprint, placed inside that blueprints block access world.
     *
     * @param blueprint   The blueprint whos entities need to be instantiated.
     * @param entityLevel The blueprints world.
     * @return A list of entities in the blueprint
     */
    public static List<Entity> instantiateEntities(final Blueprint blueprint, final Level entityLevel)
    {
        return blueprint.getEntitiesAsList()
            .stream()
            .map(entityInfo -> BlueprintEntityInfoTransformHandler.getInstance().Transform(entityInfo))
            .map(entityInfo -> constructEntity(entityInfo, entityLevel))
            .filter(Objects::nonNull)
            .toList();
    }

    @Nullable
    public static BlockEntity constructTileEntity(final BlockInfo info, final Level beLevel, final HolderLookup.Provider provider)
    {
        if (info == null || info.getTileEntityData() == null) return null;

        final String entityId = info.getTileEntityData().getStringOr("id", "");

        try
        {
            final CompoundTag compound = info.getTileEntityData().copy();
            compound.putInt("x", info.getPos().getX());
            compound.putInt("y", info.getPos().getY());
            compound.putInt("z", info.getPos().getZ());

            final BlockState blockState = info.getState();
            final BlockEntity entity = BlockEntity.loadStatic(info.getPos(), Objects.requireNonNull(blockState), compound, provider);

            if (entity != null)
            {
                if (!entity.getType().isValid(blockState))
                {
                    Log.getLogger().error("TileEntity " + entityId + " does not accept blockState: " + blockState);
                    return null;
                }

                if (beLevel != null)
                {
                    entity.setLevel(beLevel);
                }
            }
            return entity;
        }
        catch (final Exception ex)
        {
            Log.getLogger().error("Could not create tile entity: " + entityId + " with nbt: " + info.toString(), ex);
            return null;
        }
    }

    @Nullable
    private static Entity constructEntity(@Nullable final CompoundTag info, final Level entityLevel)
    {
        if (info == null) return null;

        final String entityId = info.getStringOr("id", "");

        try
        {
            final CompoundTag compound = info.copy();
            compound.store("UUID", UUIDUtil.CODEC, UUID.randomUUID());
            final ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, entityLevel.registryAccess(), compound);
            final Optional<Entity> entity = EntityType.create(input, entityLevel, EntitySpawnReason.LOAD);

            if (entity.isPresent())
            {
                final Entity theEntity = entity.get();

                // prevent ticking rotations
                theEntity.setOldPosAndRot();
                if (theEntity instanceof LivingEntity lentity)
                {
                    lentity.yHeadRotO = lentity.yHeadRot;
                    lentity.yBodyRotO = lentity.yBodyRot;
                }

                return theEntity;
            }
            return null;
        }
        catch (final Exception ex)
        {
            Log.getLogger().error("Could not create entity: " + entityId + " with nbt: " + info.toString(), ex);
            return null;
        }
    }
}
