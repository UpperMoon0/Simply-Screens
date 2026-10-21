package com.nstut.simplyscreens.api;

import com.mojang.brigadier.CommandDispatcher;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScreenCommandsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @TempDir Path root;
    @AfterEach void cleanup() { ScreenPlaylistRuntime.shutdown(); }
    @Test void bothCommandRootsAcceptUnquotedNamespacedDimensions() throws Exception {
        ScreenRegistry.init(root);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        ScreenCommands.register(dispatcher);
        var server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of());
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        for (String command : List.of("screens", "simplyscreens")) {
            assertEquals(0, dispatcher.execute(command + " display minecraft:the_nether 10 70 -42", source));
        }
        verify(source, times(2)).sendFailure(any());
    }
    @Test void ordinaryPlayersCannotEnterEitherAdminCommandRoot() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        ScreenCommands.register(dispatcher);
        var server = mock(MinecraftServer.class);
        var playerList = mock(net.minecraft.server.players.PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(source.getPlayer()).thenReturn(mock(ServerPlayer.class));
        assertFalse(dispatcher.getRoot().getChild("screens").canUse(source));
        assertFalse(dispatcher.getRoot().getChild("simplyscreens").canUse(source));
    }
}
