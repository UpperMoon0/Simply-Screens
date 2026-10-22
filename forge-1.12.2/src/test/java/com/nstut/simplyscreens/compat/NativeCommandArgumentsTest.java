package com.nstut.simplyscreens.compat;
import com.nstut.simplyscreens.api.ScreenCommands;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
/** Quotes retain Screen IDs with whitespace without granting command authority. */
public class NativeCommandArgumentsTest {
 @Test public void quotedScreenId() { assertEquals(Arrays.asList("info","Cinema Lobby"),ScreenCommands.parseArguments("info \"Cinema Lobby\"")); }
 @Test public void escapedQuoteAndBackslash() { assertEquals(Arrays.asList("status","A \"B\" \\ C"),ScreenCommands.parseArguments("status \"A \\\"B\\\" \\\\ C\"")); }
 @Test public void emptyQuotedArgumentIsNotSilentlyDropped() { assertEquals(Arrays.asList("set","","image"),ScreenCommands.parseArguments("set \"\" image")); }
 @Test(expected=IllegalArgumentException.class) public void unterminatedQuoteRejected() { ScreenCommands.parseArguments("info \"Lobby"); }
 @Test(expected=IllegalArgumentException.class) public void danglingEscapeRejected() { ScreenCommands.parseArguments("info \"Lobby\\"); }
}
