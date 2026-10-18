package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecentsFocusPolicyTest {
  private final RecentsFocusPolicy.Target task =
      new RecentsFocusPolicy.Target(42, 10123, 0, 0, 1, 1);

  @Test
  public void staleGestureCannotRestoreNewerTask() {
    var policy = new RecentsFocusPolicy();
    Object old = new Object(), next = new Object();
    assertTrue(policy.begin(old, task, 1));
    assertTrue(policy.begin(next, task, 2));
    assertNull(policy.end(old));
    assertSame(next, policy.current().owner());
    assertEquals(2, policy.current().mode());
  }

  @Test
  public void ownerDeathEndsOnlyItsOwnLease() {
    var policy = new RecentsFocusPolicy();
    Object owner = new Object();
    policy.begin(owner, task, 1);
    assertNotNull(policy.end(owner));
    assertNull(policy.current());
    assertNull(policy.end(owner));
  }

  @Test
  public void invalidCandidateDoesNotReplaceValidLease() {
    var policy = new RecentsFocusPolicy();
    Object owner = new Object();
    policy.begin(owner, task, 1);
    assertFalse(
        policy.begin(new Object(), new RecentsFocusPolicy.Target(43, 10124, 0, 0, 2, 1), 1));
    assertSame(owner, policy.current().owner());
  }

  @Test
  public void workProfileAndExternalDisplayCannotBeTargets() {
    assertFalse(new RecentsFocusPolicy.Target(42, 110123, 1, 0, 1, 1).eligible());
    assertFalse(new RecentsFocusPolicy.Target(42, 10123, 0, 2, 1, 1).eligible());
  }

  @Test
  public void systemHomePipAndSplitAreExcluded() {
    assertFalse(new RecentsFocusPolicy.Target(42, 1000, 0, 0, 1, 1).eligible());
    assertFalse(new RecentsFocusPolicy.Target(42, 10123, 0, 0, 1, 2).eligible());
    assertFalse(new RecentsFocusPolicy.Target(42, 10123, 0, 0, 2, 1).eligible());
    assertFalse(new RecentsFocusPolicy.Target(42, 10123, 0, 0, 6, 1).eligible());
  }

  @Test
  public void corruptModesFailToNativeWithoutReplacingLease() {
    var policy = new RecentsFocusPolicy();
    Object owner = new Object();
    policy.begin(owner, task, 1);
    for (int mode : new int[] {-1, 0, 3, Integer.MAX_VALUE}) {
      assertFalse(policy.begin(new Object(), task, mode));
      assertSame(owner, policy.current().owner());
    }
  }

  @Test
  public void arbitraryAppSystemShellAndRootCannotUseBackend() {
    for (int uid : new int[] {0, 1000, 2000, 10125, 110123, 99001})
      assertFalse(RecentsFocusPolicy.allowedUid(uid, 10123, 10124, false));
  }

  @Test
  public void launcherCannotArmButModuleCan() {
    assertTrue(RecentsFocusPolicy.allowedUid(10124, 10123, 10124, false));
    assertFalse(RecentsFocusPolicy.allowedUid(10124, 10123, 10124, true));
    assertTrue(RecentsFocusPolicy.allowedUid(10123, 10123, 10124, true));
  }

  @Test
  public void preserveRequiresExistingNativeRetention() {
    assertTrue(RecentsFocusPolicy.canOverride(1, false));
    assertTrue(RecentsFocusPolicy.canOverride(1, true));
    assertTrue(RecentsFocusPolicy.canOverride(2, true));
    assertFalse(RecentsFocusPolicy.canOverride(2, false));
    assertFalse(RecentsFocusPolicy.canOverride(0, true));
  }

  @Test
  public void matchingUidInAnotherUserIsRejected() {
    assertFalse(RecentsFocusPolicy.allowedUid(110123, 110123, 110124, false));
  }
}
