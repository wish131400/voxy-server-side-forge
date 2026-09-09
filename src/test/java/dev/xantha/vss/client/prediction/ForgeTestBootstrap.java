package dev.xantha.vss.client.prediction;

/** Populate Forge listener lists that ModLauncher normally initializes in game. */
final class ForgeTestBootstrap {
    static void prepare() {
        try {
            net.minecraftforge.fml.loading.LoadingModList.get().setBrokenFiles(java.util.List.of());
            prepareEvent(net.minecraftforge.network.NetworkEvent.class);
            for (var type : net.minecraftforge.network.NetworkEvent.class.getDeclaredClasses())
                if (net.minecraftforge.eventbus.api.Event.class.isAssignableFrom(type)) prepareEvent(type);
            for (var method : dev.xantha.vss.networking.client.VSSClientNetworking.class.getDeclaredMethods())
                if (method.isAnnotationPresent(net.minecraftforge.eventbus.api.SubscribeEvent.class))
                    prepareEvent(method.getParameterTypes()[0]);
            var field = net.minecraftforge.fml.loading.FMLLoader.class.getDeclaredField("loadingModList");
            field.setAccessible(true);
            field.set(null, net.minecraftforge.fml.loading.LoadingModList.get());
            var layers = net.minecraftforge.fml.loading.FMLLoader.class.getDeclaredField("moduleLayerManager");
            layers.setAccessible(true);
            layers.set(null, (cpw.mods.modlauncher.api.IModuleLayerManager) layer -> java.util.Optional.of(ModuleLayer.boot()));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void prepareEvent(Class<?> type) throws ReflectiveOperationException {
        if (type == net.minecraftforge.eventbus.api.Event.class) return;
        prepareEvent(type.getSuperclass());
        var method = net.minecraftforge.eventbus.api.EventListenerHelper.class
                .getDeclaredMethod("getListenerListInternal", Class.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, type, true);
    }
}
