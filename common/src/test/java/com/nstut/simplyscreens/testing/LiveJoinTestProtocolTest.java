package com.nstut.simplyscreens.testing;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LiveJoinTestProtocolTest {
 @Test void elapsedWatchdogInvokesFailureNotSuccess() {
  AtomicBoolean failed=new AtomicBoolean();
  LiveJoinTestProtocol.waitForShutdown(0,()->failed.set(true));
  assertTrue(failed.get());
 }
 @Test void interruptedWatchdogPreservesInterruptWithoutReportingPass() {
  AtomicBoolean failed=new AtomicBoolean();
  try {
   Thread.currentThread().interrupt();
   LiveJoinTestProtocol.waitForShutdown(1,()->failed.set(true));
   assertTrue(Thread.currentThread().isInterrupted());assertFalse(failed.get());
  } finally { Thread.interrupted(); }
 }
}
