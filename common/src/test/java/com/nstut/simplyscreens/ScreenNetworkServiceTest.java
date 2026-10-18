package com.nstut.simplyscreens;

import com.nstut.simplyscreens.ScreenNetworkService.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScreenNetworkServiceTest {
    final UUID owner = UUID.randomUUID(), friend = UUID.randomUUID(), stranger = UUID.randomUUID(), image = UUID.randomUUID();
    final Actor admin = new Actor(stranger, true), user = new Actor(owner, false), trusted = new Actor(friend, false);
    final String key = ScreenNetworkService.network("station");
    ScreenNetworkService create() {
        var service = new ScreenNetworkService(); assertTrue(service.createOwned(key, owner, image)); return service;
    }
    @Test void separatesViewingControlManagementAndAutomation() {
        var s = create();
        assertTrue(s.allowed(key, Actor.automation(), Permission.VIEW));
        assertFalse(s.allowed(key, Actor.automation(), Permission.CONTROL));
        assertFalse(s.allowed(key, trusted, Permission.CONTROL));
        assertTrue(s.access(key, user, 0, "TRUST", friend.toString()));
        assertTrue(s.access(key, user, 1, "CONTROL", "TRUSTED"));
        assertTrue(s.allowed(key, trusted, Permission.CONTROL));
        assertFalse(s.allowed(key, trusted, Permission.MANAGE));
        assertFalse(s.access(key, trusted, 2, "TRANSFER", friend.toString()));
        assertFalse(s.allowed(key, Actor.automation(), Permission.CONTROL));
    }
    @Test void viewAndControlPoliciesAreIndependentAndRevocable() {
        var s = create();
        assertTrue(s.access(key, user, 0, "VIEW", "OWNER_ONLY"));
        assertFalse(s.allowed(key, trusted, Permission.VIEW));
        assertTrue(s.access(key, user, 1, "CONTROL", "PUBLIC"));
        assertTrue(s.allowed(key, Actor.automation(), Permission.CONTROL));
        assertFalse(s.allowed(key, Actor.automation(), Permission.VIEW));
        assertFalse(s.allowed(key, Actor.automation(), Permission.MANAGE));
        assertTrue(s.allowed(key, admin, Permission.MANAGE));
    }
    @Test void trustedUsersCanSelectButCannotGrantPermissions() {
        var s=create(); s.access(key,user,0,"TRUST",friend.toString()); s.access(key,user,1,"CONTROL","TRUSTED");
        assertTrue(s.select(key,trusted,null,2));
        assertNull(s.snapshot(key).image());
        assertFalse(s.access(key,trusted,3,"TRUST",stranger.toString()));
        assertTrue(s.access(key,user,3,"UNTRUST",friend.toString()));
        assertFalse(s.select(key,trusted,image,4));
    }
    @Test void staleWritesAndNoOpSelectionCannotAdvanceOrOverwriteState() {
        var s=create();
        assertTrue(s.select(key,user,image,0)); assertEquals(0,s.snapshot(key).revision());
        assertTrue(s.select(key,user,null,0));
        assertFalse(s.select(key,user,image,0));
        assertFalse(s.access(key,user,0,"CONTROL","PUBLIC"));
        assertNull(s.snapshot(key).image()); assertEquals(1,s.snapshot(key).revision());
    }
    @Test void ownershipTransferClearsFormerTrustAndManagement() {
        var s=create(); s.access(key,user,0,"TRUST",stranger.toString());
        assertTrue(s.access(key,user,1,"TRANSFER",friend.toString()));
        assertTrue(s.snapshot(key).trusted().isEmpty());
        assertFalse(s.allowed(key,user,Permission.MANAGE));
        assertFalse(s.allowed(key,user,Permission.CONTROL));
        assertTrue(s.allowed(key,trusted,Permission.MANAGE));
    }
    @Test void snapshotsAreImmutableAndInputsAreValidated() {
        var s=create();
        assertThrows(UnsupportedOperationException.class,()->s.snapshot(key).trusted().add(friend));
        assertFalse(s.access(key,user,0,"TRUST","invalid"));
        assertFalse(s.access(key,user,0,"VIEW","invalid"));
        assertEquals(0,s.snapshot(key).revision());
        assertThrows(IllegalArgumentException.class,()->s.ensure("network/",null,null));
        assertThrows(IllegalArgumentException.class,()->ScreenNetworkService.network("invalid space"));
    }
    @Test void trustListIsBounded() {
        var s=create();
        for (int i=0;i<64;i++) assertTrue(s.access(key,user,i,"TRUST",UUID.randomUUID().toString()));
        assertFalse(s.access(key,user,64,"TRUST",friend.toString()));
        assertEquals(64,s.snapshot(key).trusted().size());
    }
    @Test void migratesLegacyImageAndOwnerWithoutClaimingUnownedNetworks(@TempDir Path root) {
        var s=new ScreenNetworkService();
        s.init(root,Map.of("station",image,"old",image),Map.of("station",owner));
        assertEquals(image,s.snapshot(key).image()); assertEquals(owner,s.snapshot(key).owner());
        String legacy=ScreenNetworkService.network("old");
        assertFalse(s.claim(legacy,trusted)); assertTrue(s.claim(legacy,admin));
        assertTrue(s.allowed(legacy,Actor.automation(),Permission.VIEW));
    }
    @Test void emptyNetworksAndPoliciesSurviveRestartAndDoNotResurrectLegacyImages(@TempDir Path root) {
        var s=create(); s.init(root,Map.of("station",image),Map.of("station",owner));
        assertTrue(s.select(key,user,null,0)); s.access(key,user,1,"VIEW","TRUSTED");
        var restart=new ScreenNetworkService(); restart.init(root,Map.of("station",image),Map.of("station",stranger));
        assertNull(restart.snapshot(key).image()); assertEquals(owner,restart.snapshot(key).owner());
        assertEquals(Policy.TRUSTED,restart.snapshot(key).view()); assertEquals(2,restart.snapshot(key).revision());
    }
    @Test void standaloneDisplayHasSeparatePersistentOwnership(@TempDir Path root) {
        var s=new ScreenNetworkService(); s.init(root,Map.of(),Map.of());
        String display=ScreenNetworkService.display(UUID.randomUUID());
        assertTrue(s.createOwned(display,owner,image));
        assertFalse(s.allowed(display,trusted,Permission.CONTROL));
        var restart=new ScreenNetworkService(); restart.init(root,Map.of(),Map.of());
        assertEquals(owner,restart.snapshot(display).owner()); assertEquals(image,restart.snapshot(display).image());
    }
    @Test void corruptPrimaryRecoversValidatedBackupAndDoubleCorruptionFailsClosed(@TempDir Path root) throws Exception {
        var s=new ScreenNetworkService(); s.init(root,Map.of("station",image),Map.of("station",owner)); s.save();
        Files.writeString(root.resolve("screen_networks.json"),"broken");
        var recovered=new ScreenNetworkService(); recovered.init(root,Map.of(),Map.of());
        assertEquals(owner,recovered.snapshot(key).owner());
        Files.writeString(root.resolve("screen_networks.json"),"broken");
        Files.writeString(root.resolve("screen_networks.json.bak"),"broken");
        assertThrows(IllegalStateException.class,()->new ScreenNetworkService().init(root,Map.of("station",image),Map.of()));
    }
    @Test void failedPersistenceRollsBackMutation(@TempDir Path root) throws Exception {
        var s=new ScreenNetworkService(); s.init(root,Map.of("station",image),Map.of("station",owner));
        Files.createDirectory(root.resolve("screen_networks.json.tmp"));
        assertThrows(IllegalStateException.class,()->s.select(key,user,null,0));
        assertEquals(image,s.snapshot(key).image()); assertEquals(0,s.snapshot(key).revision());
    }
    @Test void switchingWorldsDoesNotLeakNetworks(@TempDir Path root) {
        var s=new ScreenNetworkService(); s.init(root.resolve("a"),Map.of("station",image),Map.of("station",owner));
        s.init(root.resolve("b"),Map.of(),Map.of()); assertNull(s.snapshot(key));
        s.init(root.resolve("a"),Map.of(),Map.of()); assertEquals(owner,s.snapshot(key).owner());
    }
    @Test void physicalMembershipAndOverridesSurviveAnchorlessReload(@TempDir Path root) {
        var s=new ScreenNetworkService(); s.init(root,Map.of("station",image),Map.of("station",owner));
        UUID display=UUID.randomUUID();
        s.updateDisplay(display,"station",true,false);
        var restart=new ScreenNetworkService(); restart.init(root,Map.of(),Map.of());
        assertEquals(new DisplayState("station",true,false),restart.displayState(display));
        restart.updateDisplay(display,"other",false,true);
        var again=new ScreenNetworkService(); again.init(root,Map.of(),Map.of());
        assertEquals("other",again.displayState(display).screenId());
        assertFalse(again.displayState(display).override()); assertTrue(again.displayState(display).aspect());
    }
    @Test void splitsInheritOwnershipAndTrustButHaveIndependentContent() {
        var s=create(); s.access(key,user,0,"TRUST",friend.toString()); s.access(key,user,1,"CONTROL","TRUSTED");
        String split=ScreenNetworkService.display(s.forkDisplay(key,image));
        assertEquals(owner,s.snapshot(split).owner()); assertTrue(s.snapshot(split).trusted().contains(friend));
        assertTrue(s.select(split,trusted,null,0));
        assertEquals(image,s.snapshot(key).image()); assertNull(s.snapshot(split).image());
        assertTrue(s.access(split,user,1,"TRANSFER",friend.toString()));
        assertEquals(owner,s.snapshot(key).owner());
    }
    @Test void legacyAnchorIdentitySurvivesRelinkAndRestart(@TempDir Path root) {
        var s=new ScreenNetworkService(); s.init(root,Map.of("station",image),Map.of("station",owner));
        UUID display=UUID.randomUUID(); String anchor="minecraft:overworld/-42,100,4096";
        s.registerAnchor(anchor,display); s.updateDisplay(display,"station",false,true);
        s.updateDisplay(display,"changed",false,true);
        var restart=new ScreenNetworkService(); restart.init(root,Map.of(),Map.of());
        assertEquals("changed",restart.displayState(restart.displayAt(anchor)).screenId());
    }
    @Test void blankAndInvalidNamesCannotBypassPersistentDisplayOwnership() {
        var helper=new ScreenRegistryHelper(org.slf4j.LoggerFactory.getLogger(getClass()));
        assertFalse(helper.canWriteScreenId("",friend,false));
        assertFalse(helper.canWriteScreenId("invalid name",friend,true));
    }
}
