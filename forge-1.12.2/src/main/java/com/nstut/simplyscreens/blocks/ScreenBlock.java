package com.nstut.simplyscreens.blocks;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.ServerTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.SoundType;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
/** All six mount directions survive metadata; visual anchor state is tile-derived.
 * Native setResistance multiplies by three, then getExplosionResistance divides
 * by five: 10 preserves the maintained modern explosion resistance of 6. */
public final class ScreenBlock extends Block {
 public static final PropertyDirection FACING=PropertyDirection.create("facing");
 public static final PropertyInteger STATE=PropertyInteger.create("state",0,2);
 public static final int STATE_CHILD=0,STATE_ANCHOR=1,STATE_ERROR=2;
 public ScreenBlock() { super(Material.IRON);setRegistryName("simply_screens","screen");setTranslationKey("simply_screens.screen");setHardness(3.5F);setResistance(10);setSoundType(SoundType.METAL);setHarvestLevel("pickaxe",1);setCreativeTab(com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries.CREATIVE_TAB);setDefaultState(blockState.getBaseState().withProperty(FACING,EnumFacing.NORTH).withProperty(STATE,STATE_ANCHOR)); }
 @Override protected BlockStateContainer createBlockState() { return new BlockStateContainer(this,FACING,STATE); }
 @Override public int getMetaFromState(IBlockState state) { return state.getValue(FACING).getIndex(); }
 @Override public IBlockState getStateFromMeta(int metadata) { return getDefaultState().withProperty(FACING,EnumFacing.byIndex(metadata%6)); }
 @Override public IBlockState getActualState(IBlockState state,net.minecraft.world.IBlockAccess world,BlockPos pos) { TileEntity tile=world.getTileEntity(pos);return tile instanceof ScreenBlockEntity?state.withProperty(STATE,((ScreenBlockEntity)tile).getAnchorPos()==null?STATE_ERROR:((ScreenBlockEntity)tile).isAnchor()?STATE_ANCHOR:STATE_CHILD):state; }
 @Override public boolean hasTileEntity(IBlockState state) { return true; }
 @Override public TileEntity createTileEntity(World world,IBlockState state) { return new ScreenBlockEntity(); }
 @Override public boolean isOpaqueCube(IBlockState state) { return false; }
 @Override public boolean isFullCube(IBlockState state) { return false; }
 @Override public IBlockState getStateForPlacement(World world,BlockPos pos,EnumFacing face,float x,float y,float z,int meta,EntityLivingBase placer,EnumHand hand) { net.minecraft.util.math.Vec3d look=placer.getLookVec();return getDefaultState().withProperty(FACING,EnumFacing.getFacingFromVector((float)-look.x,(float)-look.y,(float)-look.z)); }
 @Override public void onBlockPlacedBy(World world,BlockPos pos,IBlockState state,EntityLivingBase placer,ItemStack stack) {
  super.onBlockPlacedBy(world,pos,state,placer,stack);
  if(!world.isRemote) { TileEntity tile=world.getTileEntity(pos);if(placer instanceof EntityPlayer && tile instanceof ScreenBlockEntity)((ScreenBlockEntity)tile).claimPlaced(placer.getUniqueID());ServerTickScheduler.schedule(()->refresh(world,pos,state.getValue(FACING))); }
 }
 @Override public boolean onBlockActivated(World world,BlockPos pos,IBlockState state,EntityPlayer player,EnumHand hand,EnumFacing face,float x,float y,float z) { if(world.isRemote)com.nstut.simplyscreens.forge.SimplyScreensForge.proxy.open(pos);return true; }
 @Override public void breakBlock(World world,BlockPos pos,IBlockState state) {
  if(!world.isRemote) { TileEntity tile=world.getTileEntity(pos);BlockPos anchor=tile instanceof ScreenBlockEntity?((ScreenBlockEntity)tile).getAnchorPos():null;
   if(tile instanceof ScreenBlockEntity && ((ScreenBlockEntity)tile).isAnchor()) { com.nstut.simplyscreens.ScreenRegistry.unregisterScreen(world,pos,((ScreenBlockEntity)tile).getScreenId());((ScreenBlockEntity)tile).findNewAnchor(); }
   ServerTickScheduler.schedule(()->{ if(anchor!=null)refreshAt(world,anchor,state.getValue(FACING));refresh(world,pos,state.getValue(FACING)); });
  }super.breakBlock(world,pos,state);
 }
 private static void refresh(World world,BlockPos pos,EnumFacing face) { for(EnumFacing side:EnumFacing.values())refreshAt(world,pos.offset(side),face);refreshAt(world,pos,face); }
 private static void refreshAt(World world,BlockPos pos,EnumFacing facing) {
  if(!(world instanceof WorldServer))return;
  net.minecraft.world.chunk.Chunk chunk=((WorldServer)world).getChunkProvider().getLoadedChunk(pos.getX()>>4,pos.getZ()>>4);if(chunk==null)return;
  TileEntity tile=chunk.getTileEntity(pos,net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK);
  if(tile instanceof ScreenBlockEntity && world.getBlockState(pos).getValue(FACING)==facing) { ScreenBlockEntity anchor=((ScreenBlockEntity)tile).getAnchorEntity();if(anchor!=null)anchor.updateScreenStructure(); }
 }
}
