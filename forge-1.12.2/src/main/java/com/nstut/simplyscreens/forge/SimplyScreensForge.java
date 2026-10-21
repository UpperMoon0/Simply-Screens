package com.nstut.simplyscreens.forge;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
@Mod(modid="simply_screens",name="Simply Screens",useMetadata=true,acceptedMinecraftVersions="[1.12.2]",dependencies="required-after:packetweave@[0.1.4,);after:openui_mc@[0.0.15,)")
public final class SimplyScreensForge {
 @SidedProxy(clientSide="com.nstut.simplyscreens.forge.ClientProxy",serverSide="com.nstut.simplyscreens.forge.CommonProxy") public static CommonProxy proxy;
 private MinecraftServer server;
 @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) {
  MinecraftForge.EVENT_BUS.register(this);com.nstut.simplyscreens.compat.NetworkManager.init();SimplyScreens.initCommon();
  GameRegistry.registerTileEntity(com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity.class,new net.minecraft.util.ResourceLocation("simply_screens","screen"));proxy.initialize();
 }
 @SubscribeEvent public void blocks(RegistryEvent.Register<Block> event) { event.getRegistry().register(com.nstut.simplyscreens.blocks.BlockRegistries.SCREEN.get()); }
 @SubscribeEvent public void recipes(RegistryEvent.Register<net.minecraft.item.crafting.IRecipe> event) {
  if(!net.minecraftforge.fml.common.Loader.isModLoaded("patchouli"))return;
  net.minecraft.item.Item guide=net.minecraft.item.Item.getByNameOrId("patchouli:guide_book");if(guide==null)return;
  net.minecraft.item.ItemStack result=new net.minecraft.item.ItemStack(guide);net.minecraft.nbt.NBTTagCompound tag=new net.minecraft.nbt.NBTTagCompound();tag.setString("patchouli:book","simply_screens:guide");result.setTagCompound(tag);
  net.minecraft.util.NonNullList<net.minecraft.item.crafting.Ingredient> ingredients=net.minecraft.util.NonNullList.from(net.minecraft.item.crafting.Ingredient.EMPTY,net.minecraft.item.crafting.Ingredient.fromItem(net.minecraft.init.Items.BOOK),net.minecraft.item.crafting.Ingredient.fromItem(net.minecraft.init.Items.QUARTZ));
  event.getRegistry().register(new net.minecraft.item.crafting.ShapelessRecipes("simply_screens",result,ingredients).setRegistryName("simply_screens","guide_book"));
 }
 @SubscribeEvent public void items(RegistryEvent.Register<Item> event) { event.getRegistry().register(com.nstut.simplyscreens.items.ItemRegistries.SCREEN); }
 @Mod.EventHandler public void starting(FMLServerAboutToStartEvent event) { server=event.getServer();com.nstut.simplyscreens.compat.LifecycleEvent.SERVER_BEFORE_START.fire(server); }
 @Mod.EventHandler public void started(FMLServerStartingEvent event) { SimplyScreens.initializeScreens(com.nstut.simplyscreens.compat.NativeWorld.root(event.getServer()));event.registerServerCommand(new com.nstut.simplyscreens.api.ScreenCommands()); }
 @Mod.EventHandler public void stopped(FMLServerStoppedEvent event) { if(server!=null)com.nstut.simplyscreens.compat.LifecycleEvent.SERVER_STOPPED.fire(server);server=null; }
 @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) { if(event.phase==TickEvent.Phase.END && server!=null)com.nstut.simplyscreens.compat.TickEvent.SERVER_POST.fire(server); }
 @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) { com.nstut.simplyscreens.helpers.ServerImageManager.cancelUploads(event.player.getUniqueID()); }
 @SubscribeEvent public void unload(WorldEvent.Unload event) { if(!event.getWorld().isRemote)com.nstut.simplyscreens.helpers.ScreenAccessService.clearLevel(event.getWorld()); }
 @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) { if(event.player instanceof net.minecraft.entity.player.EntityPlayerMP)com.nstut.simplyscreens.network.PacketRegistries.sendToPlayer((net.minecraft.entity.player.EntityPlayerMP)event.player,new com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket(com.nstut.simplyscreens.Config.DISABLE_UPLOAD,com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE)); }
}
