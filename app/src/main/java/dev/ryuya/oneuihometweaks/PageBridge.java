package dev.ryuya.oneuihometweaks;

import android.app.WallpaperManager;
import android.os.IBinder;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;

/** Sends standard launcher offsets; never synthesizes swipe gestures in the wallpaper. */
final class PageBridge {
  private volatile boolean enabled;
  private WeakReference<ViewGroup> last = new WeakReference<>(null);
  private boolean pending, failed;
  private Method scrollForPage, cellType, spaceInfo, currentState, visibleCount;
  private Class<?> cells;
  private Method indexedCellType;
  private PageBridge freeGrid;
  private boolean isFreeGrid;
  private boolean discovering, discoveryFailed;
  private WeakReference<android.app.Activity> homeActivity = new WeakReference<>(null);
  private String lastReport = "";
  private java.util.function.Consumer<ViewGroup> freeGridObserver;

  void setFreeGridObserver(java.util.function.Consumer<ViewGroup> observer) {
    freeGridObserver = observer;
  }

  void install(ClassLoader loader) throws Exception {
    if (!DexEvidence.workspaceVerified)
      throw new IllegalStateException("Workspace scroll DEX evidence absent");
    Class<?> workspace =
        Class.forName(
            "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
            false,
            loader);
    cells =
        Class.forName(
            "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceCellLayoutContainer",
            false,
            loader);
    scrollForPage = workspace.getMethod("getScrollForPage", int.class);
    cellType = cells.getMethod("getCellType");
    spaceInfo = workspace.getMethod("getHoneySpaceInfo");
    visibleCount = workspace.getMethod("getVisibleCount");
    currentState = workspace.getDeclaredMethod("getCurrentState");
    currentState.setAccessible(true);
    ModernHook.Callback events =
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable()) request((ViewGroup) p.thisObject);
          }
        };
    List<ModernHook.Handle> installed = new ArrayList<>();
    try {
      installed.add(
          ModernHook.hookMethod(
              workspace.getDeclaredMethod("notifyScrollX", int.class, int.class, int.class),
              events));
      installed.add(
          ModernHook.hookMethod(workspace.getDeclaredMethod("onAttachedToWindow"), events));
      installed.add(
          ModernHook.hookMethod(workspace.getDeclaredMethod("onEndPageScrolling"), events));
    } catch (Throwable t) {
      for (var h : installed) h.unhook();
      throw t;
    }
    Class<?> base = Class.forName("com.honeyspace.ui.common.FastRecyclerView", false, loader);
    Class<?> plugin =
        Class.forName("com.honeyspace.common.plugin.PluginContextWrapper", false, loader);
    ModernHook.hookAllConstructors(
        plugin,
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.hasThrowable()
                || freeGrid != null
                || p.args.length != 3
                || !(p.args[0] instanceof android.content.Context ctx)
                || !(p.args[2] instanceof ClassLoader pluginLoader)
                || !"com.samsung.android.app.homestar".equals(ctx.getPackageName())) return;
            try {
              String name =
                  "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridFastRecyclerView";
              DexEvidence.verifyScrollContract(ctx.getApplicationInfo().sourceDir, name);
              PageBridge bridge = new PageBridge();
              bridge.isFreeGrid = true;
              bridge.freeGridObserver = freeGridObserver;
              bridge.installFreeGrid(Class.forName(name, false, pluginLoader));
              freeGrid = bridge;
              bridge.homeActivity = homeActivity;
              bridge.setEnabled(enabled);
            } catch (Throwable t) {
              HookEntry.log("FREEGRID_PAGE_UNSUPPORTED " + t);
            }
          }
        });
    ModernHook.hookMethod(
        plugin.getDeclaredMethod("getClassLoader"),
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.hasThrowable()
                || freeGrid != null
                || discovering
                || discoveryFailed
                || !(p.getResult() instanceof ClassLoader pluginLoader)
                || !(p.thisObject instanceof android.content.Context ctx)
                || !"com.samsung.android.app.homestar".equals(ctx.getPackageName())) return;
            discovering = true;
            try {
              String name =
                  "com.honeyspace.ui.honeypots.freegrid.presentation.FreeGridFastRecyclerView";
              DexEvidence.verifyScrollContract(ctx.getApplicationInfo().sourceDir, name);
              PageBridge bridge = new PageBridge();
              bridge.isFreeGrid = true;
              bridge.freeGridObserver = freeGridObserver;
              bridge.installFreeGrid(Class.forName(name, false, pluginLoader));
              freeGrid = bridge;
              bridge.homeActivity = homeActivity;
              bridge.setEnabled(enabled);
            } catch (Throwable t) {
              discoveryFailed = true;
              HookEntry.log("FREEGRID_PAGE_UNSUPPORTED " + t);
            } finally {
              discovering = false;
            }
          }
        });
    // Verified caller contains the actual virtual scroll callback; undo possible ART inlining.
    for (Method method : base.getDeclaredMethods())
      if ("swipeScrollToWithOverScroll".equals(method.getName()) && !ModernHook.deoptimize(method))
        throw new IllegalStateException("Failed to deoptimize workspace scroll caller");
    HookEntry.log(
        "PAGE_HOOK_READY standard offsets; includes dynamically loaded Home Up discovery");
  }

  private void installFreeGrid(Class<?> workspace) throws Exception {
    scrollForPage = workspace.getMethod("getScrollForPage", int.class);
    spaceInfo = workspace.getMethod("getHoneySpaceInfo");
    visibleCount = workspace.getMethod("getVisibleCount");
    currentState = workspace.getDeclaredMethod("getCurrentState");
    currentState.setAccessible(true);
    indexedCellType = workspace.getDeclaredMethod("getCellType", int.class);
    indexedCellType.setAccessible(true);
    List<ModernHook.Handle> installed = new ArrayList<>();
    ModernHook.Callback events =
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable()) request((ViewGroup) p.thisObject);
          }
        };
    try {
      installed.add(
          ModernHook.hookMethod(
              workspace.getDeclaredMethod("notifyScrollX", int.class, int.class, int.class),
              events));
      installed.add(
          ModernHook.hookMethod(workspace.getDeclaredMethod("onAttachedToWindow"), events));
      installed.add(
          ModernHook.hookMethod(workspace.getDeclaredMethod("onEndPageScrolling"), events));
    } catch (Throwable t) {
      for (var h : installed) h.unhook();
      throw t;
    }
    HookEntry.log("FREEGRID_PAGE_HOOK_READY runtime class + DEX verified");
  }

  void setEnabled(boolean value) {
    boolean before = enabled;
    enabled = value;
    if (!isFreeGrid && before && !value) restoreNativePosition();
    if (freeGrid != null) freeGrid.setEnabled(value);
    ViewGroup view = last.get();
    if (value && view != null) request(view);
  }

  private void restoreNativePosition() {
    android.app.Activity owner = homeActivity.get();
    if (owner == null || owner.isDestroyed()) return;
    try {
      IBinder token = owner.getWindow().getDecorView().getWindowToken();
      if (token == null) return;
      WallpaperManager wm = WallpaperManager.getInstance(owner);
      if (wm.getWallpaperInfo() == null) return;
      float x =
          android.provider.Settings.System.getFloat(
              owner.getContentResolver(), "wallpaper_x_offset", 0.5f);
      float y =
          android.provider.Settings.System.getFloat(
              owner.getContentResolver(), "wallpaper_y_offset", 0.5f);
      wm.setWallpaperOffsetSteps(-1f, -1f);
      wm.setWallpaperOffsets(token, x, y);
      HookEntry.log("PAGE_NATIVE_POSITION_RESTORED");
    } catch (Throwable t) {
      HookEntry.log("PAGE_RESTORE_ERROR " + t.getClass().getSimpleName());
    }
  }

  void setHomeWindow(android.app.Activity activity) {
    if (!"com.sec.android.app.launcher.Launcher".equals(activity.getClass().getName())) return;
    homeActivity = new WeakReference<>(activity);
    if (freeGrid != null) freeGrid.homeActivity = homeActivity;
    ViewGroup view = last.get();
    if (view != null) request(view);
    if (freeGrid != null && freeGrid.last.get() != null) freeGrid.request(freeGrid.last.get());
  }

  private boolean visible() {
    ViewGroup view = last.get();
    return view != null && view.isAttachedToWindow() && view.isShown();
  }

  private void request(ViewGroup view) {
    android.app.Activity owner = homeActivity.get();
    if (owner != null && !owner.isDestroyed()) {
      IBinder expected = owner.getWindow().getDecorView().getWindowToken();
      if (expected != null && view.getWindowToken() != expected) return;
    }
    last = new WeakReference<>(view);
    if (isFreeGrid && freeGridObserver != null) freeGridObserver.accept(view);
    if (!enabled || failed || pending) return;
    pending = true;
    view.postOnAnimation(
        () -> {
          pending = false;
          if (enabled && !failed) send(view);
        });
  }

  private void send(ViewGroup view) {
    try {
      android.app.Activity owner = homeActivity.get();
      // Remote launcher previews build their own workspace. Never send their offsets to home.
      if (owner == null
          || owner.isDestroyed()
          || !owner.hasWindowFocus()
          || view.getWindowToken() != owner.getWindow().getDecorView().getWindowToken()) return;
      if (!isFreeGrid && freeGrid != null && freeGrid.visible()) return;
      if (!view.isAttachedToWindow()
          || !view.isShown()
          || view.getDisplay() == null
          || view.getDisplay().getDisplayId() != 0) return;
      Object info = spaceInfo.invoke(view);
      if ((boolean) info.getClass().getMethod("isDexSpace").invoke(info)
          || (int) visibleCount.invoke(view) != 1) return;
      if (!"com.honeyspace.sdk.HomeScreen$Normal"
          .equals(currentState.invoke(view).getClass().getName())) return;
      List<Integer> positions = new ArrayList<>();
      for (int i = 0; i < view.getChildCount(); i++) {
        View child = view.getChildAt(i);
        if (indexedCellType != null
            ? "CELL_LAYOUT".equals(((Enum<?>) indexedCellType.invoke(view, i)).name())
            : cells.isInstance(child)
                && "CELL_LAYOUT".equals(((Enum<?>) cellType.invoke(child)).name()))
          positions.add((Integer) scrollForPage.invoke(view, i));
      }
      if (positions.isEmpty()) return;
      int[] array = new int[positions.size()];
      for (int i = 0; i < array.length; i++) array[i] = positions.get(i);
      if (array.length > 1 && array[0] == array[array.length - 1]) return; // not laid out yet
      float offset = PagePolicy.offset(view.getScrollX(), array),
          step = array.length > 1 ? 1f / (array.length - 1) : 0f;
      IBinder token = view.getWindowToken();
      if (token == null) return;
      WallpaperManager wm = WallpaperManager.getInstance(view.getContext());
      wm.setWallpaperOffsetSteps(step, 0f);
      wm.setWallpaperOffsets(token, offset, 0.5f);
      float rank = offset * Math.max(0, array.length - 1);
      if (Math.abs(rank - Math.round(rank)) < 0.001f) {
        String report =
            (isFreeGrid ? "FREEGRID_PAGE_OFFSET pages=" : "PAGE_OFFSET pages=")
                + array.length
                + " rank="
                + Math.round(rank)
                + " x="
                + offset
                + " xStep="
                + step;
        if (!report.equals(lastReport)) {
          HookEntry.log(report);
          lastReport = report;
        }
      }
    } catch (Throwable t) {
      failed = true;
      HookEntry.log("PAGE_DISABLED " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }
}
