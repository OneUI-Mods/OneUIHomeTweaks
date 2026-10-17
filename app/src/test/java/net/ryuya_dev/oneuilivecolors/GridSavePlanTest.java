package net.ryuya_dev.oneuilivecolors;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class GridSavePlanTest {
  private GridSavePlan.Entry e(int id, int page, int oldX, int x) {
    return new GridSavePlan.Entry(id, page, oldX, 61, x, 61, "1x1:scale1:rotation0");
  }

  @Test
  public void previewIdentityDoesNotDependOnModelEnumerationOrder() {
    var a = e(1, 34, 7, 9);
    var b = e(2, 35, 9, 9);
    assertEquals(
        new GridSavePlan(List.of(a, b)).fingerprint(),
        new GridSavePlan(List.of(b, a)).fingerprint());
  }

  @Test
  public void sourceOrPageChangesInvalidateUserConfirmation() {
    String before = new GridSavePlan(List.of(e(1, 34, 7, 9))).fingerprint();
    assertNotEquals(before, new GridSavePlan(List.of(e(1, 34, 8, 9))).fingerprint());
    assertNotEquals(before, new GridSavePlan(List.of(e(1, 35, 7, 9))).fingerprint());
  }

  @Test
  public void countsIncludeOnlyCoordinatesThatWillBeWritten() {
    var p = new GridSavePlan(List.of(e(1, 34, 7, 9), e(2, 35, 9, 9), e(3, 34, 1, 9)));
    assertEquals(2, p.changed());
    assertEquals(1, p.pages());
  }

  @Test
  public void duplicateIdentitiesAreRejectedBeforeNativeWrites() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GridSavePlan(List.of(e(1, 34, 7, 9), e(1, 35, 1, 9))));
  }
}
