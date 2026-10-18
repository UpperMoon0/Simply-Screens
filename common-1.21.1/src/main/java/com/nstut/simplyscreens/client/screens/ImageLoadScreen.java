package com.nstut.simplyscreens.client.screens;

import com.nstut.openui.api.ButtonWidget;
import com.nstut.openui.api.HStack;
import com.nstut.openui.api.UIComponent;
import com.nstut.openui.api.Ui;
import com.nstut.openui.api.VStack;
import com.nstut.openui.controls.Badge;
import com.nstut.openui.controls.Card;
import com.nstut.openui.controls.Dialog;
import com.nstut.openui.controls.EmptyState;
import com.nstut.openui.controls.IconWidget;
import com.nstut.openui.controls.Toast;
import com.nstut.openui.layout.Alignment;
import com.nstut.openui.layout.Justification;
import com.nstut.openui.overlay.OverlayHandle;
import com.nstut.openui.state.Computed;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import com.nstut.openui.state.Subscription;
import com.nstut.simplyscreens.ImageImportSupport;
import com.nstut.simplyscreens.ScreenRegistryHelper;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.ui.SimplyScreensUiScreen;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.DownloadImageFromUrlC2SPacket;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RemoveImageC2SPacket;
import com.nstut.simplyscreens.network.RequestImageListC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenAspectRatioC2SPacket;
import com.nstut.simplyscreens.network.UpdateScreenSelectedImageC2SPacket;
import com.nstut.simplyscreens.network.UploadImageChunkC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ImageLoadScreen extends SimplyScreensUiScreen {
    private static final java.util.concurrent.atomic.AtomicLong PLAYLIST_REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong();
    private final Signal<com.nstut.simplyscreens.playlist.PlaylistWire.View> playlistView = Signals.of(null);
    private final Signal<List<com.nstut.simplyscreens.playlist.PlaylistWire.Summary>> savedPlaylists = Signals.of(List.of());
    private record SlideRow(int index, com.nstut.simplyscreens.playlist.PlaylistWire.Slide slide, long revision, UUID playlist) { }
    private final Signal<List<SlideRow>> savedSlides = Signals.of(List.of()), queuedSlides = Signals.of(List.of());
    private final Signal<Boolean> savedSlidesEmpty = Signals.of(true), queuedSlidesEmpty = Signals.of(true);
    private UUID selectedPlaylist;
    private long pendingPlaylistRequest;
    private boolean playlistMutationPending;
    private int playlistRefreshTicks;
    private boolean playlistDirty;
    private static Component pt(String key, Object... args) { return Component.translatable("gui.simplyscreens.playlist." + key, args); }
    private boolean canControlPlaylist() { return playlistView.get() != null && playlistView.get().control() && !playlistMutationPending; }
    public void updatePlaylist(BlockPos pos, String json) {
        if (!blockEntityPos.equals(pos)) return;
        var view = com.nstut.simplyscreens.playlist.PlaylistWire.View.parse(json);
        if (view.requestId() != pendingPlaylistRequest) return;
        playlistMutationPending = false; pendingPlaylistRequest = 0;
        selectedPlaylist = view.selected(); playlistView.set(view); savedPlaylists.set(view.playlists());
        var rows = new ArrayList<SlideRow>();
        for (int i=0;i<view.slides().size();i++) rows.add(new SlideRow(i,view.slides().get(i),view.libraryRevision(),view.selected()));
        savedSlides.set(List.copyOf(rows)); savedSlidesEmpty.set(rows.isEmpty()); rows.clear();
        for (int i=0;i<view.queue().size();i++) rows.add(new SlideRow(i,view.queue().get(i),view.playbackRevision(),null));
        queuedSlides.set(List.copyOf(rows)); queuedSlidesEmpty.set(rows.isEmpty());
        if (!view.success()) showError(pt("rejected"));
    }
    private void playlistAction(String action, UUID image, int index, int destination, long duration, String value) {
        if (playlistMutationPending || (!"GET".equals(action) && !canControlPlaylist())) return;
        var v = playlistView.get();
        pendingPlaylistRequest = PLAYLIST_REQUEST_IDS.incrementAndGet();
        playlistMutationPending = !"GET".equals(action); playlistRefreshTicks = 0;
        var request = new com.nstut.simplyscreens.playlist.PlaylistWire.Request(v == null ? "" : v.key(),
                v == null ? 0 : v.networkRevision(), v == null ? 0 : v.libraryRevision(), v == null ? 0 : v.playbackRevision(),
                pendingPlaylistRequest, action, selectedPlaylist, image, index, destination, duration, value);
        PacketRegistries.sendToServer(new com.nstut.simplyscreens.network.ScreenPlaylistC2SPacket(blockEntityPos, request.json()));
    }
    private void playlistAction(String action) { playlistAction(action, null, -1, -1, 10000, ""); }
    @Override public void tick() {
        super.tick();
        ++playlistRefreshTicks;
        // A lost/rejected packet cannot leave mutations disabled forever; ordinary refresh never cancels a pending write.
        if (playlistRefreshTicks >= 80) { playlistMutationPending = false; pendingPlaylistRequest = 0; }
        if (!playlistMutationPending && (playlistDirty || playlistRefreshTicks >= 20)) {
            playlistDirty = false; playlistAction("GET");
        }
    }
    private String slideName(UUID image) {
        if (image == null) return pt("nothing_playing").getString();
        return images.get().stream().filter(i -> i.imageId().equals(image)).map(ImageEntry::displayName)
                .findFirst().orElse(image.toString().substring(0,8));
    }
    private String selectedPlaylistName() {
        return savedPlaylists.get().stream().filter(p -> p.id().equals(selectedPlaylist))
                .map(p -> p.name()+" ("+p.count()+")").findFirst().orElse(pt("choose").getString());
    }
    private com.nstut.openui.controls.ContextMenu playlistMenu() {
        var client=Minecraft.getInstance(); var window=client.getWindow();
        return Ui.contextMenu((int)(client.mouseHandler.xpos()*width/window.getScreenWidth()),
                (int)(client.mouseHandler.ypos()*height/window.getScreenHeight()));
    }
    private UIComponent playlistHelp(String key) {
        return height < 300 ? Ui.column() : Ui.text(pt(key)).wrap();
    }
    private UIComponent buildPlaylistBrowser() {
        return Ui.column(Ui.row(
                Ui.button(() -> Component.literal(selectedPlaylistName()), () -> choosePlaylist(null)).ghost().small().flex().tooltip(pt("edit_help")).key("playlist.selector"),
                new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.PLAY,
                        pt("play"), () -> playlistAction("PLAY")).enabledWhen(() -> canControlPlaylist() && !savedSlides.get().isEmpty()),
                new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.PLUS,
                        pt("create"), () -> playlistNameDialog("CREATE")).enabledWhen(this::canControlPlaylist),
                new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.MORE,
                        pt("actions"), () -> playlistMenu().item(pt("rename"), () -> playlistNameDialog("RENAME"))
                        .item(pt("duplicate"), () -> playlistNameDialog("DUPLICATE"))
                        .separator().item(pt("delete"), this::confirmDeletePlaylist).show(uiRuntime().overlays()))
                        .enabledWhen(() -> canControlPlaylist() && selectedPlaylist != null)).gap(4).key("playlist.toolbar"),
                playlistHelp("edit_help"),
                Ui.switcher(savedSlidesEmpty)
                        .when(true, () -> Ui.emptyState(pt("empty")).action(pt("browse"), () -> tab.set(Tab.GALLERY)))
                        .when(false, () -> Ui.list(savedSlides, row -> buildSlideRow(row, false)).key(row -> row.index()).itemHeight(28).gap(2).flex()).flex()).gap(6);
    }
    private UIComponent buildSlideRow(SlideRow row, boolean queue) {
        return Ui.row(Ui.text(Component.literal(Integer.toString(row.index()+1))).width(20),
                Ui.text(Component.literal(slideName(row.slide().image()))).marquee().flex(),
                Ui.text(Component.literal(row.slide().durationMillis()/1000+"s")),
                new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.MORE,
                        pt("slide_actions"), () -> {
                            var menu = playlistMenu();
                            if (!queue) menu.item(pt("duration"), () -> durationDialog(row));
                            if (row.index()>0) menu.item(pt("up"), () -> playlistRowAction(row,queue,queue?"MOVE_QUEUE":"MOVE",row.index()-1,0));
                            if (row.index()+1<(queue?queuedSlides.get():savedSlides.get()).size())
                                menu.item(pt("down"), () -> playlistRowAction(row,queue,queue?"MOVE_QUEUE":"MOVE",row.index()+1,0));
                            menu.separator().item(pt("remove"), () -> playlistRowAction(row,queue,queue?"REMOVE_QUEUE":"REMOVE",-1,0));
                            menu.show(uiRuntime().overlays());
                        }).enabledWhen(this::canControlPlaylist)).gap(5).align(Alignment.CENTER);
    }
    private boolean rowCurrent(SlideRow row, boolean queue) {
        var view=playlistView.get();
        return view!=null && (queue ? row.revision()==view.playbackRevision() :
                row.revision()==view.libraryRevision() && java.util.Objects.equals(row.playlist(),selectedPlaylist));
    }
    private void playlistRowAction(SlideRow row, boolean queue, String action, int destination, long duration) {
        if (!rowCurrent(row,queue)) { showError(pt("rejected")); return; }
        playlistAction(action,null,row.index(),destination,duration,"");
    }
    private UIComponent buildPlaylistQueue() {
        return Ui.column(Ui.row(Ui.heading(pt("queue")).flex(),
                Ui.button(pt("clear_queue"), () -> playlistAction("CLEAR_QUEUE")).ghost().small()).gap(6),
                playlistHelp("queue_help"),
                Ui.switcher(queuedSlidesEmpty)
                        .when(true, () -> Ui.emptyState(pt("queue_empty")))
                        .when(false, () -> Ui.list(queuedSlides,row -> buildSlideRow(row,true)).key(row -> row.index()).itemHeight(28).gap(2).flex()).flex(),
                Ui.text(() -> Component.literal(pt("upcoming").getString()+": "+(playlistView.get()==null?"":playlistView.get().upcoming().stream()
                        .map(this::slideName).collect(java.util.stream.Collectors.joining(", "))))).marquee()).gap(6);
    }
    private void galleryPlaylistActions(UUID image) {
        if (!canControlPlaylist()) return;
        playlistMenu().item(pt("add"), () -> choosePlaylist(image))
                .item(pt("queue_next"), () -> playlistAction("QUEUE_NEXT",image,-1,-1,10000,""))
                .item(pt("queue_last"), () -> playlistAction("QUEUE_LAST",image,-1,-1,10000,""))
                .separator().item(pt("announce"), () -> playlistAction("ANNOUNCE",image,-1,-1,10000,""))
                .show(uiRuntime().overlays());
    }
    private void choosePlaylist(UUID addImage) {
        if (playlistMutationPending) return;
        Signal<String> query=Signals.of(""); OverlayHandle[] handle={null};
        var matches=Signals.computed(() -> savedPlaylists.get().stream().filter(p -> p.name().toLowerCase(Locale.ROOT)
                .contains(query.get().trim().toLowerCase(Locale.ROOT))).toList());
        var list=Ui.list(matches,p -> Ui.button(Component.literal(p.name()+" ("+p.count()+")"), () -> {
            selectedPlaylist=p.id(); handle[0].close();
            if(addImage==null) playlistAction("GET"); else playlistAction("ADD",addImage,-1,-1,10000,"");
        }).ghost().small()).key(p -> p.id()).itemHeight(26).gap(2).height(Math.max(40,Math.min(160,height-150)));
        Card dialog=Ui.card(Ui.column(Ui.heading(pt(addImage==null?"choose":"add")),
                Ui.textField(query).placeholder(pt("search").getString()).maxLength(64).fillWidth(), list,
                Ui.button(pt("create"), () -> {handle[0].close();playlistNameDialog("CREATE");}).ghost().small(),
                Ui.button(Component.translatable("gui.simplyscreens.cancel"), () -> handle[0].close()).ghost()).gap(6)).padding(12);
        dialog.width(Math.max(120,Math.min(320,width-32))); handle[0]=Dialog.show(uiRuntime().overlays(),dialog,true,true,matches::close);
    }
    private void playlistNameDialog(String action) {
        UUID target=selectedPlaylist; long revision=playlistView.get()==null?-1:playlistView.get().libraryRevision();
        String old=savedPlaylists.get().stream().filter(p -> p.id().equals(target)).map(p -> p.name()).findFirst().orElse("");
        textPlaylistDialog(pt(action.toLowerCase(Locale.ROOT)), "CREATE".equals(action)?"":old, pt("name_help"), value -> {
            String name=value.trim(); if(name.isBlank()||name.length()>64 || playlistView.get()==null || playlistView.get().libraryRevision()!=revision) return false;
            selectedPlaylist=target; playlistAction(action,null,-1,-1,0,name); return true;
        });
    }
    private void durationDialog(SlideRow row) {
        UUID target=selectedPlaylist;
        textPlaylistDialog(pt("duration"),Long.toString(row.slide().durationMillis()/1000),pt("duration_help"), value -> {
            try { long seconds=Long.parseLong(value.trim());if(seconds<1||seconds>86400)return false;
                if (!rowCurrent(row,false)) return false;
                selectedPlaylist=target; playlistRowAction(row,false,"DURATION",-1,seconds*1000);return true;
            } catch(NumberFormatException bad) {return false;}
        });
    }
    private void textPlaylistDialog(Component title,String initial,Component help,java.util.function.Predicate<String> submit) {
        Signal<String> input=Signals.of(initial);Signal<String> error=Signals.of("");OverlayHandle[] handle={null};
        Card dialog=Ui.card(Ui.column(Ui.heading(title),Ui.text(help).wrap(),Ui.textField(input).maxLength(64).fillWidth(),Ui.text(error).wrap(),
                Ui.row(Ui.button(Component.translatable("gui.simplyscreens.cancel"),()->handle[0].close()).ghost(),
                        Ui.button(pt("save"),()->{if(submit.test(input.get()))handle[0].close();else error.set(pt("invalid").getString());}).primary()).gap(6)).gap(8)).padding(12);
        dialog.width(Math.max(120,Math.min(320,width-32)));handle[0]=Dialog.show(uiRuntime().overlays(),dialog);
    }
    private void confirmDeletePlaylist() {
        UUID target=selectedPlaylist;OverlayHandle[] handle={null};
        Card dialog=Ui.card(Ui.column(Ui.heading(pt("delete")),Ui.text(pt("delete_help",selectedPlaylistName())).wrap(),
                Ui.row(Ui.button(Component.translatable("gui.simplyscreens.cancel"),()->handle[0].close()).ghost(),
                        Ui.button(pt("delete"),()->{selectedPlaylist=target;playlistAction("DELETE");handle[0].close();}).danger()).gap(6)).gap(8)).padding(12);
        dialog.width(Math.max(120,Math.min(320,width-32)));handle[0]=Dialog.show(uiRuntime().overlays(),dialog);
    }
    private boolean playlistPlaying() {
        var view=playlistView.get();
        return view!=null && java.util.Set.of("PLAYING", "ANNOUNCEMENT").contains(view.status());
    }
    private UIComponent buildPlaylistTransport() {
        UIComponent controls = Ui.row(transportIcon(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.PREVIOUS,"previous","PREVIOUS"),
                        new com.nstut.simplyscreens.client.ui.ScreenIconButton(
                                () -> playlistPlaying()?
                                        com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.PAUSE:com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.PLAY,
                                () -> pt(playlistPlaying()?"pause":"resume"),
                                () -> playlistAction(playlistPlaying()?"PAUSE":"RESUME")).enabledWhen(this::canControlPlaylist),
                        transportIcon(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.STOP,"stop","STOP"),
                        transportIcon(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.NEXT,"next","NEXT"),
                        transportIcon(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.RESTART,"restart","RESTART"),
                        new com.nstut.simplyscreens.client.ui.ScreenIconButton(() -> com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.REPEAT,
                                () -> Component.literal(pt("repeat").getString()+": "+(playlistView.get()==null?"NONE":playlistView.get().repeat())), () -> {var v=playlistView.get();if(v!=null)playlistAction("REPEAT",null,-1,-1,0,
                                        switch(v.repeat()){case "NONE"->"LIST";case "LIST"->"CURRENT";default->"NONE";});}).enabledWhen(this::canControlPlaylist),
                        new com.nstut.simplyscreens.client.ui.ScreenIconButton(() -> com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.SHUFFLE,
                                () -> Component.literal(pt("shuffle").getString()+": "+(playlistView.get()!=null&&playlistView.get().shuffle())), () -> {var v=playlistView.get();if(v!=null)playlistAction("SHUFFLE",null,-1,-1,0,Boolean.toString(!v.shuffle()));}).enabledWhen(this::canControlPlaylist),
                        new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.MORE,
                                pt("actions"), () -> playlistMenu().item(pt("cancel_announcement"),()->playlistAction("CANCEL_ANNOUNCEMENT")).show(uiRuntime().overlays())).enabledWhen(this::canControlPlaylist)
                ).gap(4).align(Alignment.CENTER);
        if (height < 300) return controls.key("playlist.transport");
        return Ui.column(Ui.text(() -> Component.literal(playlistView.get()==null?pt("loading").getString():
                        playlistView.get().status()+" · "+slideName(playlistView.get().currentImage())+" · "+playlistView.get().offsetMillis()/1000+"s")).marquee(), controls,
                Ui.text(() -> Component.literal(playlistView.get()==null?"":pt("repeat").getString()+": "+playlistView.get().repeat()+" · "+
                        pt("shuffle").getString()+": "+playlistView.get().shuffle())).marquee()).gap(3).key("playlist.transport");
    }
    private UIComponent transportIcon(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon icon,String label,String action) {
        return new com.nstut.simplyscreens.client.ui.ScreenIconButton(icon,pt(label),()->playlistAction(action)).enabledWhen(this::canControlPlaylist);
    }

    private static final int CHUNK_SIZE = com.nstut.packetweave.api.ChunkedTransfer.CHUNK_BYTES;
    private static final int PANEL_WIDTH = 560;

    private enum Tab { GALLERY, PLAYLIST, QUEUE, SETTINGS, IMPORT }
    private enum GalleryState { EMPTY, NO_MATCHES, RESULTS }
    private record ImageEntry(String displayName, UUID imageId) { }
    private record ImageRow(ImageEntry image, boolean selected, boolean displayed) { }

    private final BlockPos blockEntityPos;
    private final Signal<Tab> tab = Signals.of(Tab.GALLERY);
    private final Signal<String> search = Signals.of("");
    private final Signal<List<ImageEntry>> images = Signals.of(List.of());
    private final Signal<UUID> selectedImageId = Signals.of(null);
    private final Signal<UUID> displayedImageId = Signals.of(null);
    private final Signal<String> screenId = Signals.of("");
    private final Signal<Boolean> maintainAspectRatio = Signals.of(true);
    private final Signal<String> imageUrl = Signals.of("");
    private final Signal<Component> status = Signals.of(Component.empty());

    private final Computed<List<ImageRow>> filteredImages = Signals.computed(() -> {
        String query = search.get().trim().toLowerCase(Locale.ROOT);
        UUID selected = selectedImageId.get();
        UUID displayed = displayedImageId.get();
        return images.get().stream()
                .filter(image -> query.isEmpty() || image.displayName().toLowerCase(Locale.ROOT).contains(query))
                .map(image -> new ImageRow(image, image.imageId().equals(selected), image.imageId().equals(displayed)))
                .toList();
    });
    private final Computed<GalleryState> galleryState = Signals.computed(() -> {
        if (images.get().isEmpty()) return GalleryState.EMPTY;
        return filteredImages.get().isEmpty() ? GalleryState.NO_MATCHES : GalleryState.RESULTS;
    });
    private final Computed<String> noMatchesText = Signals.computed(() ->
            Component.translatable("gui.simplyscreens.gallery.no_matches", search.get()).getString());

    private final Signal<Double> personalViewDistance = Signals.of((double)com.nstut.simplyscreens.client.ui.SimplyScreensUiPreferences.getViewDistance());
    private Subscription displaySubscription = Subscription.EMPTY;
    private Subscription aspectSubscription = Subscription.EMPTY;
    private Subscription settingsSubscription = Subscription.EMPTY;
    private boolean applyingRemoteState;
    private static final java.util.concurrent.atomic.AtomicLong LINK_REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong();
    private long pendingLinkRequest;
    private String accessKey = "";
    private long accessRevision;
    private final Signal<String> accessSummary = Signals.of("");
    private final Signal<Boolean> localOverride = Signals.of(false);
    private final Signal<com.nstut.simplyscreens.ScreenAccessSnapshot> accessView = Signals.of(null);
    private final Signal<Boolean> accessManager = Signals.of(false);
    private final Signal<Boolean> accessUnclaimed = Signals.of(true);
    private final Signal<List<UUID>> trustedPlayers = Signals.of(List.of());
    private final Signal<Boolean> trustedPlayersEmpty = Signals.of(true);
    private final Signal<String> viewPolicy = Signals.of("PUBLIC");
    private final Signal<String> controlPolicy = Signals.of("OWNER_ONLY");

    public void updateAccess(BlockPos pos, String json) {
        if (!blockEntityPos.equals(pos)) return;
        var snapshot = com.nstut.simplyscreens.ScreenAccessSnapshot.fromJson(json);
        if (snapshot.requestId() != 0 && snapshot.requestId() != pendingLinkRequest) return;
        accessKey = snapshot.key(); accessRevision = snapshot.state().revision();
        accessManager.set(snapshot.manage()); localOverride.set(snapshot.localOverride());
        viewPolicy.set(snapshot.state().view().name()); controlPolicy.set(snapshot.state().control().name());
        accessView.set(snapshot);
        accessUnclaimed.set(snapshot.state().owner() == null);
        trustedPlayers.set(snapshot.state().trusted().stream().sorted().toList());
        trustedPlayersEmpty.set(trustedPlayers.get().isEmpty());
        accessSummary.set(snapshot.state().owner() == null
                ? accessText("unclaimed").getString() : playerName(snapshot.state().owner()));
        if (snapshot.requestId() != 0 && snapshot.requestId() == pendingLinkRequest) {
            pendingLinkRequest = 0;
            screenId.set(snapshot.key().startsWith("network/") ? snapshot.key().substring("network/".length()) : "");
            if (snapshot.success()) status.set(Component.translatable("gui.simplyscreens.status.linked"));
        }
        if (!snapshot.success()) showError(Component.translatable("gui.simplyscreens.access.denied"));
    }
    protected void accessAction(String action, String value) {
        PacketRegistries.sendToServer(new com.nstut.simplyscreens.network.ScreenAccessC2SPacket(
                blockEntityPos, accessKey, accessRevision, action, value, "LINK".equals(action) ? pendingLinkRequest : 0));
    }
    private static Component accessText(String key, Object... args) {
        return Component.translatable("gui.simplyscreens.access." + key, args);
    }
    private String playerName(UUID id) {
        var client = Minecraft.getInstance();
        if (client != null && client.player != null && id.equals(client.player.getUUID())) return accessText("you").getString();
        var snapshot = accessView.get();
        return snapshot == null ? id.toString() : snapshot.playerName(id);
    }
    private UIComponent buildAccess() {
        return Ui.column(
                Ui.heading(accessText("title")),
                Ui.row(Ui.text(accessText("owner")), Ui.text(accessSummary).marquee().flex(),
                        Ui.switcher(accessManager).when(true, () -> Ui.switcher(accessUnclaimed)
                                .when(true, () -> Ui.button(accessText("claim"), () -> accessAction("CLAIM", "")).small().tooltip(accessText("claim.help")))
                                .when(false, () -> Ui.button(Component.literal("..."), () -> playerPolicyDialog(true))
                                        .ghost().small().tooltip(accessText("transfer_help"))))).gap(6),
                buildPolicyControl("VIEW", "view_label", viewPolicy),
                buildPolicyControl("CONTROL", "control_label", controlPolicy),
                Ui.row(Ui.text(() -> accessText("trusted_count", accessView.get() == null ? 0 : accessView.get().state().trusted().size()))
                                .marquee().flex(),
                        Ui.switcher(accessManager).when(true, () -> Ui.button(accessText("manage"), this::manageTrustedPlayers)
                                .ghost().small().tooltip(accessText("trusted_help")).key("settings.trusted"))).gap(6),
                Ui.text(accessText("help")).wrap(),
                Ui.switcher(accessManager).when(true, () -> Ui.button(() -> accessText("override",
                        Component.translatable(localOverride.get() ? "options.on" : "options.off")),
                        () -> accessAction("OVERRIDE", Boolean.toString(!localOverride.get()))).small()
                        .tooltip(accessText("override.help")))
        ).gap(8).key("settings.access");
    }
    private UIComponent buildPolicyControl(String action, String label, Signal<String> signal) {
        return Ui.row(Ui.text(accessText(label)).marquee().flex().tooltip(accessText(label + ".help")),
                Ui.switcher(accessManager)
                        .when(true, () -> Ui.button(() -> policyLabel(signal.get()), () -> chooseAccessMode(action))
                                .ghost().small().tooltip(accessText(label + ".help")).key("settings.access_" + action.toLowerCase(Locale.ROOT)))
                        .when(false, () -> Ui.text(() -> policyLabel(signal.get())).marquee().flex().tooltip(accessText(label + ".help")))
        ).gap(6).tooltip(accessText("policy_help"));
    }
    private void chooseAccessMode(String action) {
        if (!accessManager.get()) return;
        var client = Minecraft.getInstance();
        var mouse = client.mouseHandler;
        var window = client.getWindow();
        var menu = Ui.contextMenu((int) (mouse.xpos() * width / window.getScreenWidth()),
                (int) (mouse.ypos() * height / window.getScreenHeight()));
        for (var policy : com.nstut.simplyscreens.ScreenNetworkService.Policy.values()) {
            menu.item(policyLabel(policy.name()), () -> selectPolicy(action, policy.name()));
        }
        menu.show(uiRuntime().overlays());
    }
    void selectPolicy(String action, String policy) {
        if (!accessManager.get()) return;
        com.nstut.simplyscreens.ScreenNetworkService.Policy.valueOf(policy);
        policySignal(action).set(policy);
        accessAction(action, policySignal(action).get());
    }
    private static Component policyLabel(String policy) {
        return Component.translatable("gui.simplyscreens.access.policy." + policy.toLowerCase(Locale.ROOT));
    }

    private Signal<String> policySignal(String action) {
        return switch (action) {
            case "VIEW" -> viewPolicy;
            case "CONTROL" -> controlPolicy;
            default -> throw new IllegalArgumentException("Unknown policy: " + action);
        };
    }
    private void playerPolicyDialog(boolean transfer) {
        if (!accessManager.get()) return;
        Signal<String> input = Signals.of("");
        Signal<String> error = Signals.of("");
        Card dialog = Ui.card().elevated(true).outlined(true).padding(12);
        OverlayHandle[] handle = { null };
        dialog.addChild(Ui.column(
                Ui.heading(accessText(transfer ? "change_owner" : "add_trusted")),
                Ui.text(accessText(transfer ? "transfer_help" : "player_help")).wrap(),
                Ui.textField(input).maxLength(36).placeholder(accessText("player_placeholder").getString()).fillWidth().tooltip(accessText("player_help")),
                Ui.text(error).wrap(),
                Ui.row(Ui.button(Component.translatable("gui.simplyscreens.cancel"), () -> {
                    if (handle[0] != null) handle[0].close();
                }).ghost(), Ui.button(accessText(transfer ? "transfer" : "add"), () -> {
                    String value = input.get().trim();
                    if (com.nstut.simplyscreens.ScreenPlayerInput.resolve(value, name -> new UUID(0, 0)) == null) {
                        error.set(accessText("invalid_player").getString()); return;
                    }
                    if (handle[0] != null) handle[0].close();
                    accessAction(transfer ? "TRANSFER" : "TRUST", value);
                }).primary().tooltip(accessText(transfer ? "transfer_help" : "player_help"))).gap(6)).gap(8));
        dialog.width(Math.min(320, Math.max(160, width - 40)));
        handle[0] = Dialog.show(uiRuntime().overlays(), dialog);
    }
    private void manageTrustedPlayers() {
        if (!accessManager.get()) return;
        Card dialog = Ui.card().elevated(true).outlined(true).padding(12);
        OverlayHandle[] handle = { null };
        dialog.addChild(Ui.column(
                Ui.heading(accessText("manage_trusted")),
                Ui.text(accessText("trusted_help")).wrap(),
                Ui.switcher(trustedPlayersEmpty)
                        .when(true, () -> Ui.text(accessText("none_trusted")))
                        .when(false, () -> Ui.list(trustedPlayers, id -> Ui.row(
                                Ui.text(playerName(id)).marquee().flex().tooltip(id.toString()),
                                Ui.button(accessText("remove"), () -> accessAction("UNTRUST", id.toString())).ghost().small().tooltip(accessText("remove.help"))
                        ).gap(6)).key(UUID::toString).itemHeight(24).gap(3)
                                .height(Math.min(160, Math.max(24, Math.min(trustedPlayers.get().size() * 27, height - 180))))),
                Ui.row(Ui.button(accessText("add_trusted"), () -> {
                    if (accessView.get() != null && accessView.get().state().trusted().size() >= 64) {
                        showError(accessText("trust_limit")); return;
                    }
                    if (handle[0] != null) handle[0].close();
                    playerPolicyDialog(false);
                }).ghost().tooltip(accessText("player_help")), Ui.button(Component.translatable("gui.simplyscreens.close"), () -> {
                    if (handle[0] != null) handle[0].close();
                }).ghost()).gap(6)).gap(8));
        dialog.width(Math.min(320, Math.max(160, width - 40)));
        handle[0] = Dialog.show(uiRuntime().overlays(), dialog);
    }

    public ImageLoadScreen(BlockPos blockEntityPos) {
        super(Component.translatable("gui.simplyscreens.screen.title"));
        this.blockEntityPos = blockEntityPos;
    }

    @Override
    protected void init() {
        aspectSubscription.close();
        displaySubscription.close();
        fetchDataFromBlockEntity();
        super.init();
        aspectSubscription = maintainAspectRatio.subscribe(value -> {
            if (applyingRemoteState || !hasAnchor()) return;
            PacketRegistries.sendToServer(new UpdateScreenAspectRatioC2SPacket(blockEntityPos, value));
        });
        displaySubscription = personalViewDistance.subscribe(value -> {
            if (!com.nstut.simplyscreens.client.ui.SimplyScreensUiPreferences.setViewDistance((int)Math.round(value)))
                showError(Component.translatable("gui.simplyscreens.personal.save_failed"));
        });
        bindSettingsRefresh();
        refreshImageList();
        accessAction("GET", "");
        playlistAction("GET");
    }

    void bindSettingsRefresh() {
        settingsSubscription.close();
        settingsSubscription = tab.subscribe(value -> {
            if (value == Tab.SETTINGS) accessAction("GET", "");
        });
    }

    @Override
    protected UIComponent buildUI() {
        UIComponent views = Ui.switcher(tab)
                .when(Tab.GALLERY, this::buildGallery).when(Tab.PLAYLIST, this::buildPlaylistBrowser)
                .when(Tab.QUEUE, this::buildPlaylistQueue).when(Tab.SETTINGS, this::buildSettings)
                .when(Tab.IMPORT, this::buildImport).flex();
        UIComponent navigation;
        if (Math.min(PANEL_WIDTH, width - 48) >= 440) {
            VStack sidebar = Ui.column().gap(5);
            for (Tab destination : Tab.values()) sidebar.child(Ui.button(
                    Component.translatable("gui.simplyscreens.tab."+destination.name().toLowerCase(Locale.ROOT)),
                    () -> tab.set(destination)).ghost().small());
            navigation = Ui.row(sidebar.width(86), Ui.divider().width(1).fillHeight(), views).gap(10).flex();
        } else {
            navigation = Ui.column(Ui.tabs(tab)
                    .tab(Tab.GALLERY, Component.translatable("gui.simplyscreens.tab.gallery"))
                    .tab(Tab.PLAYLIST, pt("tab")).tab(Tab.QUEUE, pt("queue"))
                    .tab(Tab.SETTINGS, Component.translatable("gui.simplyscreens.tab.settings")), views).gap(6).flex();
        }
        VStack content = Ui.column(buildHeader(), Ui.divider(), navigation, Ui.divider(), buildPlaylistTransport(),
                Ui.text(() -> status.get()).wrap().maxLines(2)).gap(height < 300 ? 4 : 6);
        content.height(Math.max(180, Math.min(440, height - (height < 300 ? 32 : 48))));
        content.fillWidth();

        Card shell = Ui.card(content)
                .outlined(true)
                .elevated(true)
                .hoverable(false)
                .padding(height < 300 ? 8 : 12);
        shell.fillWidth();
        shell.maxWidth(PANEL_WIDTH);
        return Ui.responsive(context -> context.height() < 150
                ? Ui.padding(6, Ui.scroll(shell))
                : Ui.padding(height < 300 ? 8 : 12, Ui.stack(shell).align(Alignment.CENTER, Alignment.CENTER)));
    }

    private UIComponent buildHeader() {
        if (height < 300) return Ui.row(Ui.text(this::displayedImageName).marquee().flex(), buildThemeToggle()).gap(8).align(Alignment.CENTER);
        return Ui.row(
                Ui.column(
                        Ui.heading(Component.translatable("gui.simplyscreens.screen.title")),
                        Ui.text(this::displayedImageName)
                ).gap(2).flex(),
                buildThemeToggle()
        ).gap(8).align(Alignment.CENTER).justify(Justification.SPACE_BETWEEN);
    }

    private UIComponent buildGallery() {
        UIComponent searchField = Ui.textField(search)
                .placeholder(Component.translatable("gui.simplyscreens.screen.search.placeholder").getString())
                .maxLength(128)
                .flex();
        ButtonWidget refresh = Ui.button(
                Component.translatable("gui.simplyscreens.screen.refresh"), this::refreshImageList)
                .ghost().small();
        return Ui.column(
                Ui.row(searchField, refresh, Ui.button(Component.literal("+"), () -> tab.set(Tab.IMPORT)).ghost().small().tooltip(Component.translatable("gui.simplyscreens.screen.add_image"))).gap(6).align(Alignment.CENTER),
                Ui.switcher(galleryState)
                        .when(GalleryState.EMPTY, this::buildEmptyGallery)
                        .when(GalleryState.NO_MATCHES, this::buildNoMatches)
                        .when(GalleryState.RESULTS, this::buildGalleryResults)
                        .flex()
        ).gap(8);
    }

    private UIComponent buildEmptyGallery() {
        EmptyState empty = Ui.emptyState(Component.translatable("gui.simplyscreens.gallery.empty"));
        if (!ClientServerConfig.disableUpload() || !ClientServerConfig.disableUrlDownload()) {
            empty.action(Component.translatable("gui.simplyscreens.screen.add_image"), () -> tab.set(Tab.IMPORT));
        }
        return empty;
    }

    private UIComponent buildNoMatches() {
        return Ui.column(
                Ui.text(noMatchesText).wrap(),
                Ui.button(Component.translatable("gui.simplyscreens.screen.clear_search"), () -> search.set(""))
                        .ghost().small()
        ).gap(6);
    }

    private UIComponent buildGalleryResults() {
        return Ui.responsive(context -> Ui.list(filteredImages, this::buildImageRow)
                .key(row -> row.image().imageId())
                // Stacked thumbnail/actions need 30 + 5 + 24 + 16 padding pixels.
                .itemHeight(context.width() < 301 ? 78 : 60)
                .gap(6)
                .fillWidth()
                .fillHeight());
    }

    private UIComponent buildImageRow(ImageRow row) {
        ImageEntry image = row.image();
        Card card = Ui.card().outlined(true).padding(8).selected(row.selected());
        HStack actions = Ui.row().gap(5).align(Alignment.CENTER);
        if (row.displayed()) {
            actions.child(Ui.badge(Component.translatable("gui.simplyscreens.gallery.displayed"), Badge.Variant.SUCCESS));
        } else if (row.selected()) {
            actions.child(Ui.badge(Component.translatable("gui.simplyscreens.gallery.selected"), Badge.Variant.NEUTRAL));
        }
        if (!row.displayed()) {
            actions.child(Ui.button(Component.translatable("gui.simplyscreens.screen.select"),
                    () -> selectImage(image.imageId()))
                    .primary().small());
        }
        actions.child(Ui.button(Component.translatable("gui.simplyscreens.screen.remove"),
                () -> confirmRemove(image)).danger().small());
        actions.child(new com.nstut.simplyscreens.client.ui.ScreenIconButton(com.nstut.simplyscreens.client.ui.ScreenIconButton.Icon.MORE, pt("slide_actions"), () -> galleryPlaylistActions(image.imageId())).enabledWhen(this::canControlPlaylist));
        IconWidget thumbnail = IconWidget.custom(30,
                graphics -> ClientImageManager.renderThumbnail(graphics, image.imageId(), 30));
        UIComponent label = Ui.column(
                Ui.text(Component.literal(image.displayName())).ellipsis(),
                Ui.text(Component.literal(image.imageId().toString())).ellipsis()
        ).gap(2).flex();
        card.addChild(Ui.responsive(context -> context.width() < 285
                ? Ui.column(Ui.row(thumbnail, label).gap(6).align(Alignment.CENTER), actions).gap(5)
                : Ui.row(thumbnail, label, actions).gap(8).align(Alignment.CENTER)));
        return card;
    }

    private UIComponent buildPersonalSettings() {
        Component help = Component.translatable("gui.simplyscreens.personal.view_distance.help");
        return Ui.card(Ui.column(
            Ui.heading(Component.translatable("gui.simplyscreens.personal.title")),
            Ui.text(Component.translatable("gui.simplyscreens.personal.scope")).wrap(),
            Ui.row(Ui.text(Component.translatable("gui.simplyscreens.personal.view_distance")).wrap().flex().tooltip(help),
                Ui.text(() -> Component.literal(Math.round(personalViewDistance.get()) + " blocks")).tooltip(help)).gap(6).align(Alignment.CENTER),
            Ui.slider(personalViewDistance, 8, 512).fillWidth().tooltip(help).key("settings.view_distance")
        ).gap(6)).outlined(true).padding(10).key("settings.personal");
    }

    private UIComponent buildSettings() {
        UIComponent idField = Ui.textField(screenId)
                .placeholder(Component.translatable("gui.simplyscreens.screen.id.placeholder").getString())
                .maxLength(ScreenRegistryHelper.MAX_SCREEN_ID_LENGTH)
                .tooltip(Component.translatable("gui.simplyscreens.screen.id.tooltip")).flex();
        UIComponent link = Ui.button(
                Component.translatable("gui.simplyscreens.screen.link"), this::onLinkScreenId).primary()
                .tooltip(Component.translatable("gui.simplyscreens.screen.link.tooltip"));
        Card card = Ui.card().outlined(true).padding(12);
        card.addChild(Ui.column(
                Ui.text(Component.translatable("gui.simplyscreens.screen.id.label")),
                Ui.row(idField, link).gap(6).align(Alignment.CENTER),
                Ui.divider(),
                Ui.row(
                        Ui.toggle(maintainAspectRatio).tooltip(Component.translatable("gui.simplyscreens.screen.maintain_aspect.tooltip")),
                        Ui.text(Component.translatable("gui.simplyscreens.screen.maintain_aspect"))
                                .tooltip(Component.translatable("gui.simplyscreens.screen.maintain_aspect.tooltip"))
                ).gap(7).align(Alignment.CENTER),
                Ui.text(Component.translatable("gui.simplyscreens.screen.maintain_aspect.help"))
                        .wrap()
                        .maxLines(3)
        ).gap(8).child(Ui.divider()).child(buildAccess()));
        return Ui.scroll(Ui.column(buildPersonalSettings(), card).gap(10)).key("settings.content");
    }

    private UIComponent buildImport() {
        VStack content = Ui.column().gap(10);
        if (!ClientServerConfig.disableUpload()) {
            content.child(Ui.card(Ui.column(
                    Ui.heading(Component.translatable("gui.simplyscreens.import.computer")),
                    Ui.text(Component.translatable("gui.simplyscreens.import.computer.help")).wrap().maxLines(3),
                    Ui.button(Component.translatable("gui.simplyscreens.screen.upload"), this::onUploadFromComputer)
                            .primary()
            ).gap(6)).outlined(true).padding(12));
        }
        if (!ClientServerConfig.disableUrlDownload()) {
            content.child(Ui.card(Ui.column(
                    Ui.heading(Component.translatable("gui.simplyscreens.import.url")),
                    Ui.textField(imageUrl)
                            .placeholder(Component.translatable("gui.simplyscreens.screen.url.placeholder").getString())
                            .maxLength(2048)
                            .fillWidth(),
                    Ui.button(Component.translatable("gui.simplyscreens.screen.url.load"), this::onDownloadFromUrl)
                            .secondary()
            ).gap(6)).outlined(true).padding(12));
        }
        if (ClientServerConfig.disableUpload() && ClientServerConfig.disableUrlDownload()) {
            content.child(Ui.emptyState(Component.translatable("gui.simplyscreens.import.disabled")));
        }
        return Ui.scroll(content);
    }

    private void selectImage(UUID imageId) {
        if (imageId == null || !hasAnchor()) return;
        selectedImageId.set(imageId);
        PacketRegistries.sendToServer(new UpdateScreenSelectedImageC2SPacket(blockEntityPos, imageId));
        displayedImageId.set(imageId);
        status.set(Component.translatable("gui.simplyscreens.status.selected"));
    }

    private void onLinkScreenId() {
        if (!hasAnchor()) return;
        String normalized = ScreenRegistryHelper.normalizeScreenId(screenId.get());
        if (!screenId.get().isBlank() && normalized.isEmpty()) {
            showError(Component.translatable("gui.simplyscreens.screen.id.error.invalid"));
            return;
        }
        linkScreenId(normalized);
    }

    void linkScreenId(String normalized) {
        pendingLinkRequest = LINK_REQUEST_IDS.incrementAndGet();
        status.set(Component.translatable("gui.simplyscreens.status.linking"));
        accessAction("LINK", normalized);
    }

    private void confirmRemove(ImageEntry image) {
        Card dialog = Ui.card().elevated(true).outlined(true).padding(14);
        OverlayHandle[] handle = { null };
        ButtonWidget cancel = Ui.button(Component.translatable("gui.simplyscreens.cancel"),
                () -> { if (handle[0] != null) handle[0].close(); }).ghost();
        ButtonWidget remove = Ui.button(Component.translatable("gui.simplyscreens.screen.remove"), () -> {
            if (handle[0] != null) handle[0].close();
            PacketRegistries.sendToServer(new RemoveImageC2SPacket(image.imageId()));
            status.set(Component.translatable("gui.simplyscreens.status.removing", image.displayName()));
        }).danger();
        dialog.addChild(Ui.column(
                Ui.heading(Component.translatable("gui.simplyscreens.remove.confirm.title")),
                Ui.text(Component.translatable("gui.simplyscreens.remove.confirm.body", image.displayName()))
                        .wrap()
                        .maxLines(3),
                Ui.row(cancel, remove).gap(6)
        ).gap(10));
        dialog.width(250).minHeight(90);
        handle[0] = Dialog.show(uiRuntime().overlays(), dialog);
    }

    private void onUploadFromComputer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.flip();
            String filePath = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("gui.simplyscreens.screen.dialog.select_image").getString(),
                    "", filters,
                    Component.translatable("gui.simplyscreens.screen.dialog.image_files").getString(), false);
            if (filePath == null) return;
            if (!ImageImportSupport.isSupportedFile(filePath)) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.type"));
                return;
            }
            uploadFile(Paths.get(filePath));
        }
    }

    private void uploadFile(Path path) {
        try {
            long fileSize = Files.size(path);
            if (fileSize <= 0 || fileSize > ClientServerConfig.maxUploadSize()) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.size",
                        ClientServerConfig.maxUploadSize() / 1024 / 1024));
                return;
            }
            byte[] data = Files.readAllBytes(path);
            if (data.length > ClientServerConfig.maxUploadSize()) {
                showError(Component.translatable("gui.simplyscreens.screen.upload.error.size",
                        ClientServerConfig.maxUploadSize() / 1024 / 1024));
                return;
            }
            UUID transactionId = UUID.randomUUID();
            com.nstut.packetweave.api.ChunkedTransfer.send(data, CHUNK_SIZE, (index, totalChunks, chunk) ->
                    PacketRegistries.sendToServer(new UploadImageChunkC2SPacket(
                            blockEntityPos, transactionId, index, totalChunks, chunk,
                            index == 0 ? path.getFileName().toString() : null)));
            tab.set(Tab.GALLERY);
            status.set(Component.translatable("gui.simplyscreens.status.uploading"));
        } catch (java.io.IOException | SecurityException exception) {
            SimplyScreens.LOGGER.error("Failed to read image file", exception);
            showError(Component.translatable("gui.simplyscreens.screen.upload.error.read"));
        }
    }

    private void onDownloadFromUrl() {
        String url = imageUrl.get().trim();
        if (!ImageImportSupport.isHttpUrl(url)) {
            showError(Component.translatable("gui.simplyscreens.screen.url.error.invalid"));
            return;
        }
        PacketRegistries.sendToServer(new DownloadImageFromUrlC2SPacket(
                blockEntityPos, url, ImageImportSupport.fileNameFromUrl(url)));
        tab.set(Tab.GALLERY);
        status.set(Component.translatable("gui.simplyscreens.status.downloading"));
    }

    private void showError(Component message) {
        status.set(message);
        if (uiRuntime() != null) {
            Toast.show(uiRuntime().overlays(), Toast.error(
                    Component.translatable("gui.simplyscreens.error").getString(), message.getString()));
        }
    }

    private Component displayedImageName() {
        UUID displayed = displayedImageId.get();
        if (displayed == null) return Component.translatable("gui.simplyscreens.gallery.none_displayed");
        for (ImageEntry image : images.get()) {
            if (image.imageId().equals(displayed)) {
                return Component.translatable("gui.simplyscreens.gallery.now_displaying", image.displayName());
            }
        }
        return Component.translatable("gui.simplyscreens.gallery.now_displaying", displayed.toString());
    }

    private boolean hasAnchor() {
        if (Minecraft.getInstance().level == null) return false;
        BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(blockEntityPos);
        return blockEntity instanceof ScreenBlockEntity screen && screen.getAnchorEntity() != null;
    }

    private void fetchDataFromBlockEntity() {
        applyingRemoteState = true;
        try {
            if (Minecraft.getInstance().level == null) return;
            BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(blockEntityPos);
            if (blockEntity instanceof ScreenBlockEntity screen) {
                ScreenBlockEntity anchor = screen.getAnchorEntity();
                if (anchor != null) {
                    displayedImageId.set(anchor.getImageId());
                    selectedImageId.set(anchor.getImageId());
                    screenId.set(anchor.getScreenId() != null ? anchor.getScreenId() : "");
                    maintainAspectRatio.set(anchor.isMaintainAspectRatio());
                }
            }
        } finally {
            applyingRemoteState = false;
        }
    }

    public void updateImageList(List<ImageMetadata> metadata) {
        List<ImageEntry> next = new ArrayList<>();
        if (metadata != null) {
            for (ImageMetadata image : metadata) {
                try {
                    next.add(new ImageEntry(image.getName(), UUID.fromString(image.getId())));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        images.set(List.copyOf(next));
        UUID selected = selectedImageId.get();
        boolean selectionExists = selected != null && next.stream().anyMatch(image -> image.imageId().equals(selected));
        if (!selectionExists) {
            UUID displayed = displayedImageId.get();
            selectedImageId.set(next.stream().anyMatch(image -> image.imageId().equals(displayed)) ? displayed : null);
        }
        status.set(Component.empty());
    }

    public void refreshImageList() {
        PacketRegistries.sendToServer(new RequestImageListC2SPacket());
    }

    public boolean matchesScreenUpdate(BlockPos position, BlockPos anchorPosition) {
        return blockEntityPos.equals(position) || blockEntityPos.equals(anchorPosition);
    }

    /** Tab selector for the opt-in live-join UI smoke test. */
    public void smokeSelectTab(int index) {
        Tab[] values = Tab.values();
        tab.set(values[Math.floorMod(index, values.length)]);
    }

    public void updateScreenState(UUID imageId, boolean maintainAspectRatio, String screenId) {
        if (!java.util.Objects.equals(displayedImageId.get(), imageId) || !java.util.Objects.equals(this.screenId.get(), screenId))
            playlistDirty = true;
        applyingRemoteState = true;
        try {
            displayedImageId.set(imageId);
            selectedImageId.set(imageId);
            this.maintainAspectRatio.set(maintainAspectRatio);
            this.screenId.set(screenId != null ? screenId : "");
        } finally {
            applyingRemoteState = false;
        }
    }

    @Override
    public void removed() {
        aspectSubscription.close();
        displaySubscription.close();
        settingsSubscription.close();
        galleryState.close();
        filteredImages.close();
        super.removed();
    }
}
