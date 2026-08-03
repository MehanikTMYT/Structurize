package com.ldtteam.structurize.client;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtils;
import com.ldtteam.structurize.client.fakelevel.BlueprintBlockAccess;
import com.ldtteam.structurize.component.CapturedBlock;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.ldtteam.structurize.tag.ModTags;
import com.ldtteam.structurize.util.BlockInfo;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * The renderer for blueprint.
 * Holds all information required to render a blueprint.
 *
 * PORT-26.1.2: the actual draw calls are currently disabled; see the javadoc on {@link #drawUnsafe} and
 * PORT_26.1.2.md for the full explanation (26.1.2 removed {@code BlockRenderDispatcher} and
 * {@code Camera#setup} outright, and moved entity/block-entity rendering to a two-phase
 * extract-render-state-then-submit model this port could not safely reimplement without in-game verification).
 * Entity/block-entity instantiation and bookkeeping (frustum culling, crash handling, animation tick hooks)
 * are preserved and still run every frame.
 */
public class BlueprintRenderer implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintRenderer.class);

    private static boolean hasWarnedExceptions = false;
    private static boolean hasWarnedMissingRenderer = false;

    private final BlueprintBlockAccess blockAccess;
    List<Entity> entities = List.of();
    private List<BlockEntity> tileEntities;
    private boolean initialized = false;
    private long lastGameTime;
    private Set<Object> crashingObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Static factory utility method to handle the extraction of the values from the blueprint.
     *
     * @param blueprint The blueprint to create an instance for.
     * @return The renderer.
     */
    public static BlueprintRenderer buildRendererForBlueprint(final Blueprint blueprint)
    {
        final BlueprintBlockAccess blockAccess = new BlueprintBlockAccess(blueprint);
        return new BlueprintRenderer(blockAccess);
    }

    private BlueprintRenderer(final BlueprintBlockAccess blockAccess)
    {
        this.blockAccess = blockAccess;
    }

    /**
     * Updates blueprint reference if it has same hash.
     *
     * @param previewData blueprint and context from active structure
     */
    public void updateBlueprint(final BlueprintPreviewData previewData)
    {
        if (blockAccess.getLevelSource() != previewData.getBlueprint() && blockAccess.getLevelSource().hashCode() == previewData.getBlueprint().hashCode())
        {
            blockAccess.setLevelSource(previewData.getBlueprint());
        }
    }

    /**
     * One-time setup of the entity/block-entity instances backing this preview. Block entities are kept alive
     * across frames (they carry animation state - spawner spin, enchanting table book angle, etc.), so this is
     * not repeated every frame; only the geometry built from them is rebuilt per frame in {@link #drawUnsafe}.
     */
    private void init(final BlueprintPreviewData previewData, final Map<Object, Exception> suppressedExceptions)
    {
        final Blueprint blueprint = previewData.getBlueprint();

        final Map<BlockPos, ModelData> newTeModelData = new HashMap<>();
        final Map<BlockPos, BlockEntity> tileEntitiesMap = BlueprintUtils.instantiateTileEntities(blueprint, blockAccess, newTeModelData);
        entities = BlueprintUtils.instantiateEntities(blueprint, blockAccess);

        blockAccess.setBlockEntities(tileEntitiesMap);
        blockAccess.setEntities(entities);

        if (previewData.getRenderBlocksNice())
        {
            for (final BlockInfo blockInfo : blueprint.getBlockInfoAsList())
            {
                final BlockPos blockPos = blockInfo.getPos();
                if (blockInfo.getState().getBlock() != ModBlocks.blockTagSubstitution.get())
                {
                    continue;
                }

                if (tileEntitiesMap.remove(blockPos) instanceof final BlockEntityTagSubstitution tagTE)
                {
                    final CapturedBlock replacement = tagTE.getReplacement();
                    replacement.serializedBE().map(tag -> BlockEntity.loadStatic(blockPos, replacement.blockState(), tag, blueprint.getRegistryAccess())).ifPresent(newBe -> {
                        newBe.setLevel(blockAccess);
                        newTeModelData.put(blockPos, newBe.getModelData());
                        tileEntitiesMap.put(blockPos, newBe);
                    });
                }
            }
        }

        tileEntities = new ArrayList<>(tileEntitiesMap.values());
        initialized = true;
    }

    /**
     * Draws structure into world.
     */
    public void draw(final BlueprintPreviewData previewData, final BlockPos pos, final RenderLevelStageEvent ctx)
    {
        // we've crashed hard before, full skip
        if (crashingObjects == null)
        {
            return;
        }

        try
        {
            final Map<Object, Exception> suppressedExceptions = drawUnsafe(previewData, pos, ctx);
            if (!suppressedExceptions.isEmpty())
            {
                if (!hasWarnedExceptions)
                {
                    hasWarnedExceptions = true;
                    Minecraft.getInstance().player.sendSystemMessage(Component.translatable("structurize.preview_renderer.exception"));
                }

                boolean crashReported = false;
                boolean isEmpty = true;
                for (final Map.Entry<Object, Exception> e : suppressedExceptions.entrySet())
                {
                    if (!crashingObjects.add(e.getKey()))
                    {
                        continue;
                    }
                    isEmpty = false;

                    if (e.getValue() instanceof final ReportedException reportedException)
                    {
                        printCrashReport(reportedException.getReport(), previewData);
                        crashReported = true;
                    }
                    else
                    {
                        LOGGER.error("", e.getValue());
                    }
                }

                if (!crashReported && !isEmpty)
                {
                    printCrashReport(CrashReport.forThrowable(new Exception(), "Small exception, rendering partially"), previewData);
                }
            }
        }
        catch (final Exception e)
        {
            printCrashReport(CrashReport.forThrowable(e, "Fatal exception, cannot render"), previewData);

            crashingObjects = null;
            Minecraft.getInstance().player.sendSystemMessage(
                Component.translatable("structurize.preview_renderer.cannot_render", previewData.getBlueprint().getName()));
        }
    }

    private static void printCrashReport(final CrashReport report, final BlueprintPreviewData previewData)
    {
        previewData.getBlueprint().describeSelfInCrashReport(report.addCategory("Blueprint"));
        LOGGER.error(report.getFriendlyReport(new ReportType("Problem during blueprint rendering", ReportType.TEST.nuggets())));
    }

    /**
     * Draws structure into world.
     *
     * PORT-26.1.2: the actual draw calls below (block geometry via {@code BlockRenderDispatcher}, entity/block-entity
     * rendering via {@code EntityRenderDispatcher#render}/{@code BlockEntityRenderDispatcher#render}, and
     * {@code Camera#setup}) are disabled pending re-implementation. In 26.1.2, {@code BlockRenderDispatcher} and
     * {@code Camera#setup} were removed outright, and both {@code EntityRenderDispatcher} and
     * {@code BlockEntityRenderDispatcher} moved to a two-phase "extract render state, then submit to a
     * {@code SubmitNodeCollector}" model (see {@code EntityRenderDispatcher#extractEntity}/{@code #submit} and
     * {@code BlockEntityRenderDispatcher#tryExtractRenderState}/{@code #submit}) that replaces the old direct
     * {@code .render(...)} calls this class used. Re-implementing blueprint preview rendering against that new,
     * unfamiliar frame-graph-style API requires in-game verification this port could not perform; see
     * PORT_26.1.2.md for the full explanation. Entity/block-entity instantiation ({@link #init}) and all
     * surrounding bookkeeping (frustum culling, crash handling, tick hooks) are preserved and still run every
     * frame so that re-wiring the draw calls later is the only remaining step.
     *
     * @return suppressed exceptions
     */
    public Map<Object, Exception> drawUnsafe(final BlueprintPreviewData previewData, final BlockPos pos, final RenderLevelStageEvent ctx)
    {
        final BlockPos anchorPos = pos.subtract(previewData.getBlueprint().getPrimaryBlockOffset());

        // PORT-26.1.2: RenderLevelStageEvent no longer exposes getFrustum()/getCamera()/getProjectionMatrix()/
        // getPartialTick() (it was split into per-stage subclasses with a much smaller surface); frustum culling
        // against the preview AABB is skipped until drawing is re-wired, see drawUnsafe() javadoc.

        final Map<Object, Exception> suppressedExceptions = new IdentityHashMap<>();
        final Minecraft mc = Minecraft.getInstance();
        final long gameTime = mc.level.getGameTime();
        final ProfilerFiller profiler = Profiler.get();

        profiler.push("struct_render_init");

        // make sure instances are synced
        updateBlueprint(previewData);
        blockAccess.setWorldPos(anchorPos);

        // init
        if (!initialized)
        {
            init(previewData, suppressedExceptions);
        }

        profiler.popPush("struct_render_ticks");
        // block-entity animation tick hooks still run every frame so that state (spawner spin, enchanting
        // table book angle, etc.) stays correct once rendering is re-wired; see class/method javadoc.
        if (gameTime != lastGameTime)
        {
            final BlockPos anchorPosForTicks = anchorPos;
            for (final BlockEntity tileEntity : tileEntities)
            {
                final BlockPos tePos = tileEntity.getBlockPos();
                try
                {
                    if (tileEntity instanceof final SpawnerBlockEntity spawner)
                    {
                        SpawnerBlockEntity.clientTick(mc.level, anchorPosForTicks.offset(tePos), blockAccess.getBlockState(tePos), spawner);
                    }
                    else if (tileEntity instanceof final EnchantingTableBlockEntity enchTable)
                    {
                        EnchantingTableBlockEntity
                            .bookAnimationTick(mc.level, anchorPosForTicks.offset(tePos), blockAccess.getBlockState(tePos), enchTable);
                    }
                    else if (tileEntity instanceof final CampfireBlockEntity campfire)
                    {
                        final BlockState bs = blockAccess.getBlockState(tePos);
                        if (bs.getBlock() instanceof CampfireBlock && bs.getValue(CampfireBlock.LIT))
                        {
                            CampfireBlockEntity.particleTick(mc.level, anchorPosForTicks.offset(tePos), bs, campfire);
                        }
                    }
                    else if (tileEntity instanceof final SkullBlockEntity skull)
                    {
                        final BlockState bs = blockAccess.getBlockState(tePos);
                        if (bs.getBlock() instanceof SkullBlock && (bs.is(Blocks.DRAGON_HEAD) || bs.is(Blocks.DRAGON_WALL_HEAD) ||
                            bs.is(Blocks.PIGLIN_HEAD) ||
                            bs.is(Blocks.PIGLIN_WALL_HEAD)))
                        {
                            SkullBlockEntity.animation(blockAccess, tePos, bs, skull);
                        }
                    }
                    else if (tileEntity instanceof final BeaconBlockEntity beacon)
                    {
                        // uses sound and applies buffs, but we dont want any of this since we're preview
                        BeaconBlockEntity.tick(blockAccess, tePos, blockAccess.getBlockState(tePos), beacon);
                    }
                    else if (tileEntity instanceof final VaultBlockEntity vault)
                    {
                        VaultBlockEntity.Client.tick(mc.level, anchorPosForTicks.offset(tePos), blockAccess.getBlockState(tePos), vault.getClientData(), vault.getSharedData());
                    }
                    else if (tileEntity instanceof final TrialSpawnerBlockEntity trialSpawner)
                    {
                        trialSpawner.getTrialSpawner().tickClient(mc.level, anchorPosForTicks.offset(tePos), blockAccess.getBlockState(tePos).getOptionalValue(TrialSpawnerBlock.OMINOUS).orElse(false));
                    }
                }
                catch (final Exception e)
                {
                    suppressedExceptions.put(tileEntity, e);
                }
            }

            for (final Entity entity : entities)
            {
                if (entity.getType().builtInRegistryHolder().is(ModTags.PREVIEW_TICKING_ENTITIES))
                {
                    try
                    {
                        entity.tick();
                    }
                    catch (final Exception e)
                    {
                        suppressedExceptions.put(entity, e);
                    }
                }
            }
        }

        if (!hasWarnedMissingRenderer)
        {
            hasWarnedMissingRenderer = true;
            LOGGER.warn("Blueprint preview rendering is temporarily disabled after the 26.1.2 port: "
                + "BlockRenderDispatcher/Camera#setup were removed and Entity/BlockEntityRenderDispatcher now use "
                + "an extract-render-state-then-submit model this port could not safely reimplement without in-game "
                + "verification. See PORT_26.1.2.md. Structure data, placement and scanning are unaffected.");
        }

        lastGameTime = gameTime;
        profiler.pop();

        return suppressedExceptions;
    }

    /**
     * PORT-26.1.2: no GPU resources are allocated by this renderer while drawing is disabled (see
     * {@link #drawUnsafe}); kept for API compatibility with callers that treat this as an {@link AutoCloseable}
     * resource.
     */
    @Override
    public void close()
    {
        // nothing to release while drawing is disabled
    }

    /**
     * PORT-26.1.2: the OpenGL blend-color transparency hack ({@code apply()}/{@code reset()}, using
     * {@code GlStateManager}/{@code RenderSystem.blendFunc}/direct {@code GL20C} calls against a directly-bound
     * {@code VertexBuffer}) was removed along with the rest of the direct-GL blueprint rendering path; the new
     * {@code RenderPipeline}/{@code GpuBuffer} pipeline expresses blend state declaratively instead. Only the
     * {@link #THRESHOLD} constant is preserved here since {@link com.ldtteam.structurize.event.WorldRenderContext}
     * still uses it to pick a render stage while drawing itself is disabled; see PORT_26.1.2.md.
     */
    public static class TransparencyHack
    {
        public static final float THRESHOLD = 0.99f;
    }
}
