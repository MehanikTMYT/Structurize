package com.ldtteam.structurize.event;

import com.ldtteam.structurize.blockentities.ModBlockEntities;
import com.ldtteam.structurize.client.*;
import com.ldtteam.structurize.api.Log;
import com.ldtteam.structurize.api.constants.Constants;
import com.ldtteam.structurize.items.ItemStackTooltip;
import com.ldtteam.structurize.items.ModItems;
import com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.ContainerPlacementHandler;
import com.ldtteam.structurize.storage.ClientStructurePackLoader;
import com.ldtteam.structurize.util.WorldRenderMacros;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;

public class ClientLifecycleSubscriber
{
    @SubscribeEvent
    public static void onRegisterReloadListeners(final AddClientReloadListenersEvent event)
    {
        // Upstream #855: start loading client structure packs here instead of FMLClientSetupEvent
        // (1.21.1: RegisterClientReloadListenersEvent; 26.1: AddClientReloadListenersEvent).
        ClientStructurePackLoader.onClientLoading();

        event.addListener(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "blueprint_renderer_cache"), new SimplePreparableReloadListener<>()
        {
            @Override
            protected Object prepare(final ResourceManager manager, final ProfilerFiller profiler)
            {
                return new Object();
            }

            @Override
            protected void apply(final Object source, final ResourceManager manager, final ProfilerFiller profiler)
            {
                Log.getLogger().debug("Clearing blueprint renderer cache.");
                BlueprintHandler.getInstance().clearCache();
            }
        });
    }

    // PORT-26.1.2: ItemBlockRenderTypes.setRenderLayer(...) was removed; per-block render type is now declared
    // declaratively via "render_type" in the block model JSON (see blocksubstitution.json).

    // PORT-26.1.2: "structurize:overlaid" geometry loader registration removed along with
    // OverlaidGeometry/OverlaidBakedModel/OverlaidModelLoader; see PORT_26.1.2.md.

    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(ModBlockEntities.TAG_SUBSTITUTION.get(), TagSubstitutionRenderer::new);
    }

    @SubscribeEvent
    public static void registerTooltips(final RegisterClientTooltipComponentFactoriesEvent event)
    {
        event.register(ItemStackTooltip.class, ClientItemStackTooltip::new);
    }

    @SubscribeEvent
    public static void registerKeys(final RegisterKeyMappingsEvent event)
    {
        ModKeyMappings.register(event);
    }

    @SubscribeEvent
    public static void registerGlobablRenderBuffers(final RegisterRenderBuffersEvent event)
    {
        WorldRenderMacros.RenderTypes.registerBuffer(event);
    }

    // PORT-26.1.2: IClientItemExtensions#getCustomRenderer()/BlockEntityWithoutLevelRenderer were removed;
    // custom held/GUI item rendering now goes through the new ItemModel/ItemStackRenderState pipeline instead.
    // Re-registering the tag-substitution tool's custom item renderer against that API was out of scope for
    // this port; see PORT_26.1.2.md and TagSubstitutionRenderer.
}
