package com.nstut.simplyscreens.forge;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
public final class ClientProxy extends CommonProxy {
 private boolean uiCompleted;
 @Override public void receive(com.nstut.simplyscreens.compat.NetworkManager.Envelope packet) {
  Minecraft client=Minecraft.getMinecraft();com.nstut.simplyscreens.compat.NetworkManager.receiveClient(packet,new com.nstut.simplyscreens.compat.NetworkManager.PacketContext() {
   public net.minecraft.entity.player.EntityPlayer getPlayer() { return client.player; }public void queue(Runnable action) { client.addScheduledTask(action); }
  });
 }
 @Override public void initialize() {
  if(!net.minecraftforge.fml.common.Loader.isModLoaded("openui_mc"))throw new IllegalStateException("Simply Screens clients require OpenUI MC 0.0.15 or newer for Forge 1.12.2");
  com.nstut.simplyscreens.testing.LiveJoinTestProtocol.startProbe();
  com.nstut.simplyscreens.network.PacketRegistries.registerS2CPackets();
  com.nstut.simplyscreens.helpers.ClientImageManager.initialize();
  ClientRegistry.bindTileEntitySpecialRenderer(ScreenBlockEntity.class,new com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer());
  ModelLoader.setCustomModelResourceLocation(com.nstut.simplyscreens.items.ItemRegistries.SCREEN,0,new ModelResourceLocation("simply_screens:screen","inventory"));
  net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
 }
 @Override public void open(net.minecraft.util.math.BlockPos pos) { Minecraft.getMinecraft().displayGuiScreen(new com.nstut.simplyscreens.client.screens.ImageLoadScreen(pos)); }
 @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
  if(event.phase!=TickEvent.Phase.END)return;
  Minecraft client=Minecraft.getMinecraft();com.nstut.simplyscreens.compat.ClientTickEvent.CLIENT_POST.fire(client);
  com.nstut.simplyscreens.client.testing.LegacyMediaClientProbe.validateActiveLifecycle(client);
  if(Boolean.getBoolean("simplyscreens.personalSettingsTest")) { com.nstut.simplyscreens.client.testing.PersonalSettingsProbe.tick(client);return; }
  if(!com.nstut.simplyscreens.testing.LiveJoinTestProtocol.isEnabled() || client.player==null || client.world==null)return;
  if(!uiCompleted) { if(!com.nstut.simplyscreens.client.testing.UiSmokeTest.tick(client))return;uiCompleted=true; }
  if(!com.nstut.simplyscreens.client.testing.LegacyMediaClientProbe.tick(client))return;
  com.nstut.simplyscreens.testing.LiveJoinTestProtocol.markCompleted();
  if(com.nstut.simplyscreens.testing.LiveJoinTestProtocol.passed() && com.nstut.simplyscreens.testing.LiveJoinTestProtocol.markReported()) {
   com.nstut.simplyscreens.SimplyScreens.LOGGER.info(com.nstut.simplyscreens.testing.LiveJoinTestProtocol.PASS_MARKER);
   com.nstut.simplyscreens.testing.LiveJoinTestProtocol.stopClient(client::shutdown);
  }
 }
 @SubscribeEvent public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) { Minecraft.getMinecraft().addScheduledTask(()->{com.nstut.simplyscreens.helpers.ClientImageManager.clearCache();com.nstut.simplyscreens.client.ClientServerConfig.reset();}); }
}
