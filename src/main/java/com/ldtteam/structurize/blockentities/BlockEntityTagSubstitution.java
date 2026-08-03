package com.ldtteam.structurize.blockentities;

import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.ldtteam.structurize.api.Log;
import com.ldtteam.structurize.component.CapturedBlock;
import com.ldtteam.structurize.component.ModDataComponents;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * The block entity for BlockTagSubstitution
 */
public class BlockEntityTagSubstitution extends BlockEntity implements IBlueprintDataProviderBE
{
    public static final String CAPTURED_BLOCK_TAG = "captured_block";
    /**
     * Up to 1.21.1
     */
    public static final String CAPTURED_BLOCK_TAG_OLD = "replacement";

    /**
     * The schematic name of the block.
     */
    private String schematicName = "";

    /**
     * Corner positions of schematic, relative to te pos.
     */
    private BlockPos corner1 = BlockPos.ZERO;
    private BlockPos corner2 = BlockPos.ZERO;

    /**
     * Map of block positions relative to TE pos and string tags
     */
    private Map<BlockPos, List<String>> tagPosMap = new HashMap<>();

    /**
     * Structure pack name.
     */
    private String packName;

    /**
     * Structure pack path.
     */
    private String inPackPath;

    /**
     * Replacement block.
     */
    private CapturedBlock replacement = CapturedBlock.EMPTY;

    public BlockEntityTagSubstitution(final BlockPos pos, final BlockState state)
    {
        super( ModBlockEntities.TAG_SUBSTITUTION.get(), pos, state);
    }

    @Override
    public String getSchematicName()
    {
        return schematicName;
    }

    @Override
    public void setSchematicName(final String name)
    {
        schematicName = name;
    }

    @Override
    public Map<BlockPos, List<String>> getPositionedTags()
    {
        return tagPosMap;
    }

    @Override
    public void setPositionedTags(final Map<BlockPos, List<String>> positionedTags)
    {
        tagPosMap = positionedTags;
        setChanged();
    }

    @Override
    public Tuple<BlockPos, BlockPos> getSchematicCorners()
    {
        if (corner1 == BlockPos.ZERO || corner2 == BlockPos.ZERO)
        {
            return new Tuple<>(worldPosition, worldPosition);
        }

        return new Tuple<>(corner1, corner2);
    }

    @Override
    public void setSchematicCorners(final BlockPos pos1, final BlockPos pos2)
    {
        corner1 = pos1;
        corner2 = pos2;
    }

    @Override
    public BlockPos getTilePos()
    {
        return worldPosition;
    }

    /**
     * @return the replacement block details
     */
    @NotNull
    public CapturedBlock getReplacement()
    {
        return this.replacement;
    }

    @Override
    public void loadAdditional( @NotNull final ValueInput input)
    {
        super.loadAdditional(input);
        final HolderLookup.Provider provider = input.lookup();

        final CompoundTag blueprintDataWrapper = new CompoundTag();
        input.read(IBlueprintDataProviderBE.TAG_BLUEPRINTDATA, CompoundTag.CODEC)
            .ifPresent(blueprintData -> blueprintDataWrapper.put(IBlueprintDataProviderBE.TAG_BLUEPRINTDATA, blueprintData));
        IBlueprintDataProviderBE.super.readSchematicDataFromNBT(blueprintDataWrapper);

        final Optional<CompoundTag> oldNbtOpt = input.read(CAPTURED_BLOCK_TAG_OLD, CompoundTag.CODEC);
        if (oldNbtOpt.isPresent())
        {
            final CompoundTag oldNbt = oldNbtOpt.get();
            replacement = new CapturedBlock(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, oldNbt.getCompoundOrEmpty("b")),
                Optional.of(oldNbt.getCompoundOrEmpty("e")),
                oldNbt.contains("i")
                    ? ItemStack.CODEC.parse(provider.createSerializationContext(NbtOps.INSTANCE), oldNbt.getCompoundOrEmpty("i"))
                        .resultOrPartial(error -> Log.getLogger().warn("Failed to parse legacy captured item: " + error))
                        .orElse(ItemStack.EMPTY)
                    : ItemStack.EMPTY);
        }
        else
        {
            replacement = input.read(CAPTURED_BLOCK_TAG, CapturedBlock.CODEC).orElse(CapturedBlock.EMPTY);
        }
    }

    public static CapturedBlock deserializeReplacement(final CompoundTag compound, final DynamicOps<Tag> dynamicOps)
    {
        if (compound.getCompoundOrEmpty(CAPTURED_BLOCK_TAG).isEmpty())
        {
            return CapturedBlock.EMPTY;
        }
        return CapturedBlock.CODEC.parse(dynamicOps, compound.get(CAPTURED_BLOCK_TAG)).resultOrPartial(error -> {
            Log.getLogger()
                .error("Parsing {} with data {}: {}", ModBlockEntities.TAG_SUBSTITUTION.getRegisteredName(), compound, error);
            Log.getLogger().error("", new RuntimeException());
        }).orElse(CapturedBlock.EMPTY);
    }

    @Override
    public void saveAdditional(@NotNull final ValueOutput output)
    {
        super.saveAdditional(output);

        final CompoundTag blueprintDataWrapper = new CompoundTag();
        writeSchematicDataToNBT(blueprintDataWrapper);
        output.store(IBlueprintDataProviderBE.TAG_BLUEPRINTDATA, CompoundTag.CODEC, blueprintDataWrapper.getCompoundOrEmpty(IBlueprintDataProviderBE.TAG_BLUEPRINTDATA));

        // this is still needed even with data components as of 1.21
        output.store(CAPTURED_BLOCK_TAG, CapturedBlock.CODEC, replacement);
    }

    public static void serializeReplacement(final CompoundTag compound, final DynamicOps<Tag> dynamicOps, final CapturedBlock replacement)
    {
        compound.put(CAPTURED_BLOCK_TAG, CapturedBlock.CODEC.encodeStart(dynamicOps, replacement).getOrThrow());
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setPackName(final String packName)
    {
        this.packName = packName;
    }

    @Override
    public void setBlueprintPath(final String inPackPath)
    {
        this.inPackPath = inPackPath;
    }

    @Override
    public String getPackName()
    {
        return packName;
    }

    @Override
    public String getBlueprintPath()
    {
        return inPackPath;
    }

    @NotNull
    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider provider)
    {
        return saveCustomOnly(provider);
    }

    @Override
    protected void applyImplicitComponents(final DataComponentGetter componentInput)
    {
        super.applyImplicitComponents(componentInput);
        replacement = componentInput.getOrDefault(ModDataComponents.CAPTURED_BLOCK, CapturedBlock.EMPTY);
    }

    @Override
    protected void collectImplicitComponents(final DataComponentMap.Builder componentBuilder)
    {
        super.collectImplicitComponents(componentBuilder);
        componentBuilder.set(ModDataComponents.CAPTURED_BLOCK, replacement);
    }

    @Override
    public void removeComponentsFromTag(final ValueOutput output)
    {
        output.discard(CAPTURED_BLOCK_TAG);
    }
}
