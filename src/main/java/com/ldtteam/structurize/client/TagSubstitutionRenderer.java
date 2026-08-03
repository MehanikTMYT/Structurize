package com.ldtteam.structurize.client;

import com.ldtteam.structurize.blockentities.BlockEntityTagSubstitution;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tag anchor renderer; renders replacement block model inside of anchor "overlay" model.
 *
 * PORT-26.1.2: block-entity rendering was redesigned around a two-phase "extract render state, then submit to a
 * {@link SubmitNodeCollector}" model ({@link BlockEntityRenderer#extractRenderState}/{@link BlockEntityRenderer#submit},
 * replacing the old direct {@code render(...)} call this class used) and {@code BlockEntityWithoutLevelRenderer}
 * plus {@code IClientItemExtensions#getCustomRenderer()} (used for the held/GUI item preview) were removed
 * outright, with custom item rendering moving to the new {@code ItemModel}/{@code ItemStackRenderState} pipeline.
 * Re-implementing both against these unfamiliar, untested APIs was out of scope for this port; see
 * PORT_26.1.2.md. The captured replacement block is still stored/serialized correctly
 * ({@link BlockEntityTagSubstitution}) - only its in-world and held-item visual rendering is affected.
 */
public class TagSubstitutionRenderer implements BlockEntityRenderer<BlockEntityTagSubstitution, BlockEntityRenderState>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TagSubstitutionRenderer.class);
    private static boolean hasWarned = false;

    private static TagSubstitutionRenderer INSTANCE;

    public static TagSubstitutionRenderer getInstance()
    {
        return INSTANCE;
    }

    public TagSubstitutionRenderer(@NotNull final BlockEntityRendererProvider.Context context)
    {
        INSTANCE = this;
    }

    @Override
    public BlockEntityRenderState createRenderState()
    {
        return new BlockEntityRenderState();
    }

    @Override
    public void submit(final BlockEntityRenderState state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera)
    {
        if (!hasWarned)
        {
            hasWarned = true;
            LOGGER.warn("Tag substitution replacement-block rendering is temporarily disabled after the 26.1.2 port: "
                + "block-entity rendering now uses an extract-render-state-then-submit model this port could not "
                + "safely reimplement without in-game verification. See PORT_26.1.2.md. Placement, scanning and "
                + "the stored replacement data are unaffected.");
        }
    }
}
