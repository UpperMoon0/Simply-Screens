package com.nstut.simplyscreens.api;
import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.*;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.compat.NativeWorld;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import java.util.*;
/** Native 1.12 command adapter for the same actor-aware control service. */
public final class ScreenCommands extends CommandBase {
 public String getName() { return "simplyscreens"; }
 public List<String> getAliases() { return Collections.singletonList("screens"); }
 public String getUsage(ICommandSender sender) { return "/screens list|info|status|set|clear|play|pause|resume|stop|next|previous|restart|media|display|reconcile"; }
 public int getRequiredPermissionLevel() { return 2; }
 private static void reply(ICommandSender sender,String line) { sender.sendMessage(new TextComponentString(line)); }
 private static ScreenNetworkService.Actor actor(ICommandSender sender) { return sender instanceof EntityPlayerMP?ScreenAccessService.actor((EntityPlayerMP)sender):new ScreenNetworkService.Actor(null,true); }
 public void execute(MinecraftServer server,ICommandSender sender,String[] original)throws CommandException {
  if(!sender.canUseCommand(2,getName()))throw new CommandException("commands.generic.permission");
  if(!ScreenRegistry.isInitialized()||ScreenPlaylistRuntime.service()==null)throw new CommandException("Screens are not ready yet.");
  List<String> args;try { args=parseArguments(String.join(" ",original)); }catch(IllegalArgumentException invalid) { throw new WrongUsageException(invalid.getMessage()); }
  if(args.isEmpty()) { reply(sender,getUsage(sender));return; }
  String action=args.get(0).toLowerCase(Locale.ROOT);ScreenNetworkService.Actor actor=actor(sender);
  try {
   if(action.equals("list")) {
    List<ScreenControlService.Status> all=ScreenApi.control().list(actor);int start=page(args,all.size());reply(sender,"Screen networks/displays ("+all.size()+" total), page "+(start/10+1)+":");
    for(int i=start;i<Math.min(all.size(),start+10);i++) { ScreenControlService.Status s=all.get(i);reply(sender,"  "+s.key()+" - "+s.playbackStatus()+", image="+s.image()); }return;
   }
   if(action.equals("media")) {
    List<ImageMetadata> all=new ArrayList<>(ServerImageManager.getImageList(server));all.sort(Comparator.comparing(ImageMetadata::getName).thenComparing(ImageMetadata::getId));int start=page(args,all.size());reply(sender,"Stored images ("+all.size()+" total), page "+(start/10+1)+":");
    for(int i=start;i<Math.min(all.size(),start+10);i++) { ImageMetadata media=all.get(i);reply(sender,"  "+media.getId()+" - "+media.getName()+" (owner "+media.getOwnerUUID()+")"); }return;
   }
   if(action.equals("display")) {
    require(args,5);String dimension=args.get(1);BlockPos pos=new BlockPos(parseInt(args.get(2),-30000000,30000000),parseInt(args.get(3),-2048,2048),parseInt(args.get(4),-30000000,30000000));
    for(WorldServer world:server.worlds)if(NativeWorld.dimension(world).equals(dimension)) {
     net.minecraft.world.chunk.Chunk chunk=world.getChunkProvider().getLoadedChunk(pos.getX()>>4,pos.getZ()>>4);
     net.minecraft.tileentity.TileEntity tile=chunk==null?null:chunk.getTileEntity(pos,net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK);
     if(tile instanceof ScreenBlockEntity) { ScreenBlockEntity screen=(ScreenBlockEntity)tile;ScreenNetworkService.State state=ScreenRegistry.networks().snapshot(screen.contentKey());reply(sender,"Display "+dimension+" "+pos+"\n  key="+screen.contentKey()+", display="+screen.getDisplayId()+"\n  image="+screen.getResolvedImageId()+", override="+screen.isLocalImageOverride()+"\n  size="+screen.getScreenWidth()+"x"+screen.getScreenHeight()+", owner="+(state==null?null:state.owner()));return; }break;
    }throw new CommandException("Display absent or chunk unloaded; chunks were not force-loaded.");
   }
   if(args.size()<2)throw new WrongUsageException(getUsage(sender));String key=ScreenTarget.parse(args.get(1));if(ScreenRegistry.networks().snapshot(key)==null)throw new CommandException("Unknown Screen ID. Use /screens list.");
   if(action.equals("info")||action.equals("status")) {
    require(args,2);
    ScreenControlService.Status s=ScreenApi.control().status(key,actor);if(s==null)throw new CommandException("Screen state unavailable.");
    reply(sender,key+"\n  owner="+s.owner()+", image="+s.image()+"\n  view="+s.viewPolicy()+", control="+s.controlPolicy()+"\n  network revision="+s.networkRevision()+", playback="+s.playbackStatus()+"\n  slide="+s.slideIndex()+", elapsed="+s.offsetMillis()+"ms, playback revision="+s.playbackRevision());return;
   }
   boolean done;
   switch(action) {
    case "set":case "clear":
     UUID image=null;if(action.equals("clear"))require(args,2);if(action.equals("set")) { require(args,3);image=UUID.fromString(args.get(2)); }
     done=ScreenApi.control().select(actor,key,image,id->ScreenApi.canUseImage(server,actor,id));if(done)ScreenAccessService.refreshLoadedContent(key);break;
    case "play":
     require(args,4);done=ScreenApi.control().play(actor,key,UUID.fromString(args.get(2)),UUID.fromString(args.get(3)),id->ScreenApi.canUseImage(server,actor,id));if(done)ScreenPlaylistRuntime.reconcile(key);break;
    case "pause":case "resume":case "stop":case "next":case "previous":case "restart":
     require(args,2);done=ScreenApi.control().transport(actor,key,action.toUpperCase(Locale.ROOT));if(done)ScreenPlaylistRuntime.reconcile(key);break;
    case "reconcile":
     require(args,2);if(ScreenPlaylistRuntime.service().managesContent(key))ScreenPlaylistRuntime.reconcile(key);else ScreenAccessService.refreshLoadedContent(key);done=true;break;
    default:throw new WrongUsageException(getUsage(sender));
   }
   if(!done)throw new CommandException("Screen operation rejected. Check ID, permission, media ownership and playback state.");
   SimplyScreens.LOGGER.info("Screen admin operation by {}: {} {}",sender.getName(),action,key);reply(sender,action+" "+key);
  } catch(IllegalArgumentException invalid) { throw new CommandException("Invalid Screen ID, UUID or argument: "+invalid.getMessage()); }
 }
 private static void require(List<String> args,int count)throws WrongUsageException { if(args.size()!=count)throw new WrongUsageException("Incorrect argument count. Use /screens for usage."); }
 private static int page(List<String> args,int count)throws CommandException { if(args.size()>2)throw new WrongUsageException("Use /screens list [page] or /screens media [page].");int page=args.size()==2?parseInt(args.get(1),1,10000):1;int start=(page-1)*10;if(start>=count&&count>0)throw new CommandException("Page out of range; "+count+" entries.");return start; }
 public static List<String> parseArguments(String input) {
  List<String> result=new ArrayList<>();StringBuilder token=new StringBuilder();boolean quoted=false,escaped=false,active=false;
  for(int i=0;i<input.length();i++) { char c=input.charAt(i);if(escaped) { token.append(c);escaped=false;active=true; }else if(c=='\\'&&quoted)escaped=true;else if(c=='"') { quoted=!quoted;active=true; }else if(Character.isWhitespace(c)&&!quoted) { if(active) { result.add(token.toString());token.setLength(0);active=false; } }else { token.append(c);active=true; } }
  if(quoted||escaped)throw new IllegalArgumentException("Unclosed quoted Screen ID");if(active)result.add(token.toString());return result;
 }
 @Override public List<String> getTabCompletions(MinecraftServer server,ICommandSender sender,String[] args,BlockPos target) {
  if(!sender.canUseCommand(2,getName()))return Collections.emptyList();
  if(args.length==1)return getListOfStringsMatchingLastWord(args,"list","info","status","set","clear","play","pause","resume","stop","next","previous","restart","media","display","reconcile");
  if(args.length==2&&"display".equals(args[0])) { List<String> dimensions=new ArrayList<>();for(WorldServer world:server.worlds)dimensions.add(NativeWorld.dimension(world));return getListOfStringsMatchingLastWord(args,dimensions); }
  if(args.length==2&&ScreenRegistry.isInitialized()&&!Arrays.asList("list","media").contains(args[0])) { List<String> ids=new ArrayList<>();for(String key:ScreenRegistry.networks().keys()) { String name=key.startsWith("network/")?key.substring(8):key;ids.add(name.contains(" ")?"\""+name.replace("\\","\\\\").replace("\"","\\\"")+"\"":name); }return getListOfStringsMatchingLastWord(args,ids); }return Collections.emptyList();
 }
}
