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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
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
 * PORT(26.1): blocks are baked once per blueprint with ModelBlockRenderer#tesselateBlock (BlockRenderDispatcher is gone)
 * and replayed each frame into the moving-block render types; block entities and entities go through the 26.1
 * extract/submit model and are flushed via the feature dispatcher. Not ported yet: fluids, frustum culling.
 */
public class BlueprintRenderer implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintRenderer.class);

    private static boolean hasWarnedExceptions = false;

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
        bakeBlocks(previewData, bakeAnchor, suppressedExceptions);
        initialized = true;
    }

    /**
     * One baked block quad of the preview: the quad, where its block sits inside the blueprint, and the colour/light
     * the block renderer computed for it (copied - the renderer reuses its {@link QuadInstance}).
     */
    private record PreviewQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance)
    {
    }

    /** Baked block geometry per chunk layer, built once in {@link #init} like the 1.21 vertex buffers were. */
    /** World position of the blueprint origin when the blocks were baked; only biome tint depends on it. */
    private BlockPos bakeAnchor = BlockPos.ZERO;

    private final Map<ChunkSectionLayer, List<PreviewQuad>> bakedLayers = new EnumMap<>(ChunkSectionLayer.class);

    /**
     * PORT(26.1): replaces the 1.21 {@code BlockRenderDispatcher#renderBatched} bake into per-layer VertexBuffers.
     * {@code ModelBlockRenderer#tesselateBlock} hands every quad to a {@link BlockQuadOutput} that already knows its
     * layer, so the per-render-type loop is gone; the quads are kept and replayed each frame in {@link #drawBlocks}.
     * Fluids are not baked yet (the liquid renderer moved to the section compiler).
     */
    private void bakeBlocks(final BlueprintPreviewData previewData, final BlockPos anchor, final Map<Object, Exception> suppressedExceptions)
    {
        bakedLayers.clear();
        final Minecraft mc = Minecraft.getInstance();
        final ModelBlockRenderer renderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
        final BlueprintRenderView view = new BlueprintRenderView(blockAccess, anchor);
        final double alpha = Structurize.getConfig().getClient().rendererTransparency.get();
        final int alphaColor = alpha > 0 && alpha < TransparencyHack.THRESHOLD ? ARGB.white((float) alpha) : -1;

        blockAccess.setSolidSubstitutionOverride(previewData.getSolidSubstitutionOverride());
        blockAccess.setRenderBlocksNiceOverride(previewData.getRenderBlocksNice());
        try
        {
            for (final BlockInfo blockInfo : previewData.getBlueprint().getBlockInfoAsList())
            {
                final BlockPos blockPos = blockInfo.getPos();
                final BlockState state = blockAccess.prepareBlockStateForRendering(blockInfo.getState(), blockPos);
                if (state.getRenderShape() != RenderShape.MODEL)
                {
                    continue;
                }

                try
                {
                    final BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
                    final BlockQuadOutput output = (x, y, z, quad, instance) -> {
                        final QuadInstance copy = new QuadInstance();
                        for (int vertex = 0; vertex < 4; vertex++)
                        {
                            copy.setColor(vertex, ARGB.multiply(instance.getColor(vertex), alphaColor));
                            copy.setLightCoords(vertex, instance.getLightCoords(vertex));
                        }
                        copy.setOverlayCoords(instance.overlayCoords());
                        bakedLayers.computeIfAbsent(quad.materialInfo().layer(), l -> new ArrayList<>()).add(new PreviewQuad(x, y, z, quad, copy));
                    };
                    renderer.tesselateBlock(output, blockPos.getX(), blockPos.getY(), blockPos.getZ(), view, blockPos, state, model, state.getSeed(blockPos));
                }
                catch (final Exception e)
                {
                    suppressedExceptions.put(blockInfo, e);
                }
            }
        }
        finally
        {
            blockAccess.setSolidSubstitutionOverride(null);
            blockAccess.setRenderBlocksNiceOverride(Structurize.getConfig().getClient().renderPlaceholdersNice.get());
        }
    }

    /**
     * Replays the baked quads. With transparency on, everything goes through the translucent moving-block type (the
     * 1.21 blend-colour hack is gone; the alpha is baked into the vertex colours instead).
     */
    private void drawBlocks(final PoseStack poseStack, final MultiBufferSource.BufferSource buffers)
    {
        final double alpha = Structurize.getConfig().getClient().rendererTransparency.get();
        final boolean translucent = alpha > 0 && alpha < TransparencyHack.THRESHOLD;

        for (final ChunkSectionLayer layer : ChunkSectionLayer.values())
        {
            final List<PreviewQuad> quads = bakedLayers.get(layer);
            if (quads == null || quads.isEmpty())
            {
                continue;
            }

            final RenderType type = translucent || layer == ChunkSectionLayer.TRANSLUCENT ? RenderTypes.translucentMovingBlock()
                : layer == ChunkSectionLayer.CUTOUT ? RenderTypes.cutoutMovingBlock() : RenderTypes.solidMovingBlock();
            final VertexConsumer consumer = buffers.getBuffer(type);
            for (final PreviewQuad quad : quads)
            {
                poseStack.pushPose();
                poseStack.translate(quad.x(), quad.y(), quad.z());
                consumer.putBakedQuad(poseStack.last(), quad.quad(), quad.instance());
                poseStack.popPose();
            }
            buffers.endBatch(type);
        }
    }

    /**
     * PORT(26.1): block entities and entities only extract and submit now. Same approach as vanilla's one-off GUI
     * entity draws: extract, submit into the feature dispatcher's node storage, flush it right away.
     */
    private void drawBlockEntitiesAndEntities(final PoseStack poseStack, final float partialTick, final Map<Object, Exception> suppressedExceptions)
    {
        final Minecraft mc = Minecraft.getInstance();
        final BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        final EntityRenderDispatcher entityDispatcher = mc.getEntityRenderDispatcher();
        final FeatureRenderDispatcher features = mc.gameRenderer.getFeatureRenderDispatcher();
        final CameraRenderState camera = new CameraRenderState();
        boolean submitted = false;

        for (final BlockEntity blockEntity : tileEntities)
        {
            try
            {
                final var renderer = beDispatcher.getRenderer(blockEntity);
                if (renderer == null || !blockEntity.hasLevel() || !blockEntity.getType().isValid(blockEntity.getBlockState()))
                {
                    continue;
                }
                final BlockEntityRenderState state = renderer.createRenderState();
                renderer.extractRenderState(blockEntity, state, partialTick, Vec3.ZERO, null);
                poseStack.pushPose();
                poseStack.translate(state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ());
                beDispatcher.submit(state, poseStack, features.getSubmitNodeStorage(), camera);
                poseStack.popPose();
                submitted = true;
            }
            catch (final Exception e)
            {
                suppressedExceptions.put(blockEntity, e);
            }
        }

        for (final Entity entity : entities)
        {
            try
            {
                final EntityRenderState state = entityDispatcher.extractEntity(entity, partialTick);
                final Vec3 pos = entity.position();
                entityDispatcher.submit(state, camera, pos.x(), pos.y(), pos.z(), poseStack, features.getSubmitNodeStorage());
                submitted = true;
            }
            catch (final Exception e)
            {
                suppressedExceptions.put(entity, e);
            }
        }

        if (submitted)
        {
            features.renderAllFeatures();
        }
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
     * PORT(26.1): see the class javadoc for how drawing works on the 26.1 pipeline.
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
            bakeAnchor = anchorPos;
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

        profiler.popPush("struct_render_draw");
        final Vec3 camera = mc.gameRenderer.getMainCamera().position();
        final PoseStack poseStack = ctx.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(anchorPos.getX() - camera.x(), anchorPos.getY() - camera.y(), anchorPos.getZ() - camera.z());
        try
        {
            drawBlocks(poseStack, mc.renderBuffers().bufferSource());
            drawBlockEntitiesAndEntities(poseStack, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), suppressedExceptions);
        }
        finally
        {
            poseStack.popPose();
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
        bakedLayers.clear();
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
