package com.ldtteam.structurize.event;

import com.ldtteam.structurize.commands.EntryPoint;
import com.ldtteam.structurize.items.AbstractItemWithPosSelector;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.util.BlockUtils;
import com.ldtteam.structurize.util.IOPool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Class with methods for receiving various forge events
 */
public class EventSubscriber
{
    /**
     * Private constructor to hide implicit public one.
     */
    private EventSubscriber()
    {
        /*
         * Intentionally left empty
         */
    }

    /**
     * Called when world is about to load.
     *
     * @param event event
     */
    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        EntryPoint.register(event.getDispatcher(), event.getCommandSelection());
    }

    @SubscribeEvent
    public static void onWorldTick(final LevelTickEvent.Pre event)
    {
        BlockUtils.checkOrInit();
        if (event.getLevel() instanceof ServerLevel serverLevel)
        {
            Manager.onWorldTick(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(@NotNull final ServerStoppingEvent event)
    {
        IOPool.shutdown();
    }

    /**
     * PORT-26.1.2: replaces the removed {@code Item#canAttackBlock(BlockState, Level, BlockPos, Player)} hook that
     * {@link AbstractItemWithPosSelector} used to override in order to capture the start position instead of
     * breaking the targeted block; see {@link AbstractItemWithPosSelector#onLeftClickBlock}.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event)
    {
        final Player player = event.getEntity();
        if (player.getMainHandItem().getItem() instanceof final AbstractItemWithPosSelector posSelector)
        {
            posSelector.onLeftClickBlock(event.getPos(), player);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
        }
    }
}
