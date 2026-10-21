package dev.xantha.vss.networking.server.command;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.xantha.vss.config.VSSServerConfig;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.fml.loading.FMLPaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VSSChunkyLimitCommandsTest {
    @BeforeAll static void initialize() {
        if (FMLPaths.GAMEDIR.get() == null)
            FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "chunky-limit-command-tests"));
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        VSSServerCommands.register(dispatcher);
        return dispatcher;
    }

    @Test void bothRootsAcceptEnglishChineseAndShorthandForms() {
        var dispatcher = dispatcher();
        for (String root : new String[] {"vss chunky", "vss 预生成"})
            for (String name : new String[] {"limit", "限速", "队列上限"})
                for (String suffix : new String[] {"", " get", " 查看", " 0", " 32", " set 32",
                        " 设置 32", " unlimited", " 不限"}) {
                    String input = root + " " + name + suffix;
                    var parsed = dispatcher.parse(input, VSSServerCommandsTest.source(2));
                    assertFalse(parsed.getReader().canRead(), input + " => " + parsed.getExceptions());
                    assertNotNull(parsed.getContext().getCommand(), input);
                }
    }

    @Test void settersUpdateTheLiveConfigAndSaveWithoutAnActiveJob() throws Exception {
        var dispatcher = dispatcher();
        var config = VSSServerConfig.CONFIG;
        int previousLimit = config.chunkyQueueLimit;
        int ordinaryConcurrency = config.generationConcurrencyLimitGlobal;
        boolean ordinaryEnabled = config.enableChunkGeneration;
        var path = FMLPaths.CONFIGDIR.get().resolve("vss-server-config.json");
        byte[] previousContents = Files.readAllBytes(path);
        String[] commands = {"vss chunky limit set 32", "vss 预生成 限速 设置 16",
                "vss chunky limit 64", "vss 预生成 队列上限 0",
                "vss chunky limit set 2147483647", "vss chunky limit unlimited",
                "vss 预生成 限速 32", "vss 预生成 限速 不限"};
        int[] expected = {32, 16, 64, 0, Integer.MAX_VALUE, 0, 32, 0};
        try {
            for (int i = 0; i < commands.length; i++) {
                assertEquals(1, dispatcher.execute(commands[i], VSSServerCommandsTest.source(2)));
                assertEquals(expected[i], config.chunkyQueueLimit, commands[i]);
                var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                assertEquals(expected[i], saved.get("chunkyQueueLimit").getAsInt(), commands[i]);
                assertEquals(ordinaryConcurrency, config.generationConcurrencyLimitGlobal);
                assertEquals(ordinaryEnabled, config.enableChunkGeneration);
            }
        } finally {
            config.chunkyQueueLimit = previousLimit;
            Files.write(path, previousContents);
        }
    }

    @Test void gettersDoNotChangeOrSaveSettings() throws Exception {
        var dispatcher = dispatcher();
        int previousLimit = VSSServerConfig.CONFIG.chunkyQueueLimit;
        var path = FMLPaths.CONFIGDIR.get().resolve("vss-server-config.json");
        byte[] previousContents = Files.readAllBytes(path);
        for (String input : new String[] {"vss chunky limit", "vss chunky limit get",
                "vss 预生成 限速", "vss 预生成 限速 查看", "vss 预生成 队列上限 查看"})
            assertEquals(1, dispatcher.execute(input, VSSServerCommandsTest.source(2)), input);
        assertEquals(previousLimit, VSSServerConfig.CONFIG.chunkyQueueLimit);
        assertArrayEquals(previousContents, Files.readAllBytes(path));
    }

    @Test void invalidValuesAndNonOperatorsCannotChangeTheLimit() throws Exception {
        var dispatcher = dispatcher();
        int previousLimit = VSSServerConfig.CONFIG.chunkyQueueLimit;
        var path = FMLPaths.CONFIGDIR.get().resolve("vss-server-config.json");
        byte[] previousContents = Files.readAllBytes(path);
        for (String input : new String[] {"vss chunky limit -1", "vss chunky limit set 2147483648",
                "vss 预生成 限速 设置 -1", "vss chunky limit 1.5"})
            assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute(input, VSSServerCommandsTest.source(2)), input);
        for (String input : new String[] {"vss chunky limit", "vss chunky limit 0",
                "vss 预生成 限速 设置 32", "vss 预生成 限速 不限"})
            assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute(input, VSSServerCommandsTest.source(1)), input);
        assertEquals(previousLimit, VSSServerConfig.CONFIG.chunkyQueueLimit);
        assertArrayEquals(previousContents, Files.readAllBytes(path));
    }

    @Test void tabCompletionIncludesBothLanguages() throws Exception {
        var dispatcher = dispatcher();
        var source = VSSServerCommandsTest.source(2);
        Set<String> names = dispatcher.getCompletionSuggestions(dispatcher.parse("vss chunky ", source))
                .get(5, TimeUnit.SECONDS).getList().stream().map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("limit", "限速", "队列上限")));
        Set<String> actions = dispatcher.getCompletionSuggestions(dispatcher.parse("vss 预生成 限速 ", source))
                .get(5, TimeUnit.SECONDS).getList().stream().map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
        assertTrue(actions.containsAll(Set.of("get", "set", "unlimited", "查看", "设置", "不限")));
    }
}
