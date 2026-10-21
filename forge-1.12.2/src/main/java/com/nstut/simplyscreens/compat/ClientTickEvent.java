package com.nstut.simplyscreens.compat;
import net.minecraft.client.Minecraft;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
public final class ClientTickEvent {
 public static final Event CLIENT_POST=new Event();
 public static final class Event {
  private final List<Consumer<Minecraft>> listeners=new CopyOnWriteArrayList<>();
  public void register(Consumer<Minecraft> listener) { listeners.add(listener); }
  public void fire(Minecraft client) { for(Consumer<Minecraft> listener:listeners)listener.accept(client); }
 }
}
