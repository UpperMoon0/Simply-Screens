package com.nstut.simplyscreens.compat;
import net.minecraft.server.MinecraftServer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
public final class LifecycleEvent {
 public static final Event SERVER_BEFORE_START=new Event(), SERVER_STOPPING=new Event(), SERVER_STOPPED=new Event();
 public static final class Event {
  private final List<Consumer<MinecraftServer>> listeners=new CopyOnWriteArrayList<>();
  public void register(Consumer<MinecraftServer> listener) { listeners.add(listener); }
  public void fire(MinecraftServer server) { for(Consumer<MinecraftServer> listener:listeners)listener.accept(server); }
 }
}
