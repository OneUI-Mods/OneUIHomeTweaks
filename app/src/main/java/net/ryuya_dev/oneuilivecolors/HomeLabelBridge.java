package net.ryuya_dev.oneuilivecolors;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.Layout;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;

/** Samples only the live wallpaper layer, then applies contrast separately to each home label. */
final class HomeLabelBridge {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<TextView, Saved> saved = new WeakHashMap<>();
  private WeakReference<Activity> home = new WeakReference<>(null);
  private Handler worker;
  private Class<?> iconClass, finderClass;
  private Method getColor, setColor, screenshot;
  private Object windowService;
  private boolean enabled, failed, busy, ownTextColor, geometryBlocked;
  private int initialReveals, initialFallbacks;
  private long generation;
  private String state = "unavailable", lastLog = "";
  private Runnable changed;
  private final Runnable sample = this::sample;
  private final Runnable expirePending = this::expirePending;
  private final ViewTreeObserver.OnGlobalLayoutListener layout = this::invalidate;
  private final ViewTreeObserver.OnScrollChangedListener scroll = this::invalidate;
  private final ViewTreeObserver.OnWindowFocusChangeListener focus =
      focused -> {
        if (focused) invalidate();
        else cancel();
      };

  void install(Context context, ClassLoader loader, Handler background, Runnable report)
      throws Exception {
    if (context.checkSelfPermission("android.permission.READ_FRAME_BUFFER")
        != PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("No existing wallpaper capture permission");
    Class<?> icon = Class.forName("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader);
    if (!TextView.class.isAssignableFrom(icon))
      throw new IllegalStateException("Icon is not TextView");
    getColor = icon.getMethod("getCustomLabelColor");
    setColor = icon.getMethod("setCustomLabelColor", Integer.class);
    if (getColor.getReturnType() != Integer.class || setColor.getReturnType() != void.class)
      throw new IllegalStateException("Custom label contract changed");
    windowService =
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null);
    screenshot = Class.forName("android.view.IWindowManager").getMethod("screenshotWallpaper");
    if (screenshot.getReturnType() != Bitmap.class)
      throw new IllegalStateException("Wallpaper capture contract changed");
    iconClass = icon;
    worker = background;
    changed = report;
    try {
      finderClass =
          Class.forName("com.honeyspace.ui.common.pageindicator.StrokeTextView", false, loader);
      if (!TextView.class.isAssignableFrom(finderClass))
        throw new IllegalStateException("Finder type changed");
      Class<?> indicator =
          Class.forName("com.honeyspace.ui.common.pageindicator.PageIndicatorView", false, loader);
      Class<?> binding =
          Class.forName(
              "com.honeyspace.ui.common.databinding.IndicatorSearchLayoutBinding", false, loader);
      Method updateFinder = indicator.getDeclaredMethod("updateFinderButton", binding);
      if (updateFinder.getReturnType() != void.class || !ModernHook.deoptimize(updateFinder))
        throw new IllegalStateException("Finder color caller cannot be verified/deoptimized");
      // Actual PageIndicatorView.updateFinderButton calls this exact overload. Only tracked Finder
      // instances are intercepted; preserve Samsung's newer native request for later restoration.
      ModernHook.hookMethod(
          TextView.class.getMethod("setTextColor", int.class),
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              if (!enabled || ownTextColor || Looper.myLooper() != Looper.getMainLooper()) return;
              Saved original = saved.get(p.thisObject);
              if (original == null || !original.finder || original.applied == null) return;
              original.textColors = ColorStateList.valueOf((int) p.args[0]);
              p.args[0] = original.applied;
            }
          });
    } catch (Throwable t) {
      finderClass = null;
      HookEntry.log("LOCAL_FINDER_UNSUPPORTED " + t);
    }
    // Native style updates can replace the displayed text color without changing customLabelColor.
    Class<?> style = Class.forName("com.honeyspace.sdk.source.entity.ItemStyle", false, loader);
    ModernHook.hookMethod(
        icon.getMethod("setItemStyle", style),
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable() && enabled) invalidate();
          }
        });
    ModernHook.hookMethod(
        icon.getDeclaredMethod("onDraw", Canvas.class),
        new ModernHook.Callback() {
          @Override
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (!enabled
                || failed
                || geometryBlocked
                || Looper.myLooper() != Looper.getMainLooper()) return;
            TextView view = (TextView) p.thisObject;
            Activity activity = home.get();
            if (!usable(activity)
                || view.getRootView() != activity.getWindow().getDecorView()
                || view.getRootView().getHeight() <= view.getRootView().getWidth()
                || !homeIcon(view)
                || view.getText().length() == 0
                || saved.containsKey(view)) return;
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible)) return;
            try {
              Saved original = remember(view);
              original.original = (Integer) getColor.invoke(view);
              original.pending = true;
              original.applied = Color.TRANSPARENT;
              // Only the label is delayed; icon graphics stay visible. Never paint a native white
              // label on a previously unseen page before the first local contrast result is ready.
              setColor.invoke(view, Integer.valueOf(Color.TRANSPARENT));
              invalidate();
            } catch (Throwable t) {
              disable(t);
            }
          }
        });
    state = "off";
    HookEntry.log("LOCAL_LABEL_READY wallpaper-only screenshot; native per-icon color API");
  }

  void setHome(Activity activity) {
    if (!"com.sec.android.app.launcher.Launcher".equals(activity.getClass().getName())) return;
    Activity old = home.get();
    if (old != activity) {
      if (old != null) detach(old);
      home = new WeakReference<>(activity);
      ViewTreeObserver tree = activity.getWindow().getDecorView().getViewTreeObserver();
      tree.addOnGlobalLayoutListener(layout);
      tree.addOnScrollChangedListener(scroll);
      tree.addOnWindowFocusChangeListener(focus);
    }
    invalidate();
  }

  void paused(Activity activity) {
    if (home.get() == activity) cancel();
  }

  void destroyed(Activity activity) {
    if (home.get() != activity) return;
    detach(activity);
    home.clear();
    cancel();
    restore();
  }

  private void detach(Activity activity) {
    ViewTreeObserver tree = activity.getWindow().getDecorView().getViewTreeObserver();
    if (!tree.isAlive()) return;
    tree.removeOnGlobalLayoutListener(layout);
    tree.removeOnScrollChangedListener(scroll);
    tree.removeOnWindowFocusChangeListener(focus);
  }

  void setEnabled(boolean value) {
    value &= iconClass != null && !failed;
    if (value == enabled) return;
    enabled = value;
    cancel();
    if (value) {
      update("waiting");
      invalidate();
    } else {
      restore();
      if (!failed) update("off");
    }
  }

  String diagnostic() {
    return state;
  }

  /** A finite burst after events catches page/image fades without a permanent polling loop. */
  void invalidate() {
    if (!enabled || failed) return;
    cancel();
    main.postDelayed(sample, 180);
    main.postDelayed(sample, 400);
    main.postDelayed(sample, 750);
    main.postDelayed(sample, 1200);
    main.postDelayed(sample, 1800);
    main.postDelayed(sample, 2600);
    main.postDelayed(expirePending, 3000);
  }

  private void cancel() {
    generation++;
    main.removeCallbacks(sample);
    main.removeCallbacks(expirePending);
  }

  private Saved remember(TextView view) {
    Saved original = saved.get(view);
    if (original != null) return original;
    original = new Saved();
    original.attach =
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View v) {
            if (!homeIcon(v) && !finder(v)) restore((TextView) v);
          }

          @Override
          public void onViewDetachedFromWindow(View v) {}
        };
    saved.put(view, original);
    view.addOnAttachStateChangeListener(original.attach);
    return original;
  }

  private void expirePending() {
    if (!usable(home.get())) return;
    int count = 0;
    try {
      for (TextView view : new ArrayList<>(saved.keySet())) {
        Saved original = saved.get(view);
        if (original == null || !original.pending) continue;
        if (Objects.equals(getColor.invoke(view), original.applied))
          setColor.invoke(view, original.original);
        original.applied = null;
        original.pending = false;
        // Retain this entry to prevent hide/retry loops when capture never becomes available.
        count++;
      }
      initialFallbacks += count;
      if (count > 0) HookEntry.log("LOCAL_LABEL_INITIAL_FALLBACK count=" + count);
    } catch (Throwable t) {
      disable(t);
    }
  }

  private void reveal(TextView view, Saved original, int color) {
    original.pending = false;
    initialReveals++;
    if (original.animator != null) original.animator.cancel();
    ValueAnimator fade = ValueAnimator.ofInt(0, 255);
    original.animator = fade;
    fade.setDuration(120);
    setTextColor(view, color & 0x00ffffff);
    fade.addUpdateListener(
        animation -> {
          if (enabled && saved.get(view) == original)
            setTextColor(view, ((int) animation.getAnimatedValue() << 24) | (color & 0x00ffffff));
        });
    fade.start();
  }

  private boolean usable(Activity activity) {
    if (!enabled
        || failed
        || activity == null
        || activity.isDestroyed()
        || !activity.hasWindowFocus()
        || activity.isInMultiWindowMode()
        || activity.isInPictureInPictureMode()) return false;
    View root = activity.getWindow().getDecorView();
    KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
    return root.isAttachedToWindow()
        && root.isShown()
        && root.getDisplay() != null
        && root.getDisplay().getDisplayId() == 0
        && keyguard != null
        && !keyguard.isKeyguardLocked();
  }

  private void sample() {
    Activity activity = home.get();
    if (busy || !usable(activity)) return;
    try {
      Rect screen = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
      if (screen.left != 0
          || screen.top != 0
          || screen.width() <= 0
          || screen.height() <= screen.width()) {
        restore();
        update("geometry-skipped");
        return;
      }
      List<Target> targets = new ArrayList<>();
      collect(activity.getWindow().getDecorView(), screen, targets);
      // Offscreen pages retain their last color. Restoring native white while sliding a page out
      // produced a visible white flash when it came back. Restore only on reparenting or disable.
      for (TextView view : new ArrayList<>(saved.keySet()))
        if (view.getParent() != null && !homeIcon(view) && !finder(view)) restore(view);
      if (targets.isEmpty()) {
        update("waiting");
        return;
      }
      long epoch = generation;
      busy = true;
      worker.post(() -> capture(targets, screen, epoch));
    } catch (Throwable t) {
      disable(t);
    }
  }

  private void collect(View view, Rect screen, List<Target> targets) throws Exception {
    if (!view.isShown() || view.getAlpha() < 0.99f) return;
    boolean finder = finder(view);
    if ((iconClass.isInstance(view) && homeIcon(view)) || finder) {
      TextView text = (TextView) view;
      Layout textLayout = text.getLayout();
      if (text.getText().length() == 0 || textLayout == null || textLayout.getLineCount() == 0)
        return;
      // TextView total padding includes the compound drawable above Samsung's app label.
      Matrix matrix = new Matrix();
      view.transformMatrixToGlobal(matrix);
      List<RectF> regions = new ArrayList<>();
      Rect visible = new Rect();
      if (!view.getGlobalVisibleRect(visible)) return;
      for (int i = 0; i < (finder ? 1 : textLayout.getLineCount()); i++) {
        RectF line;
        if (finder) {
          // StrokeTextView draws at compoundPaddingLeft/getBaseline, not Layout's padded line box.
          // Its fixed-height view is shorter than the font line box on this Samsung font.
          if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) return;
          Rect ink = new Rect();
          String title = text.getText().toString();
          text.getPaint().getTextBounds(title, 0, title.length(), ink);
          line = new RectF(ink);
          line.offset(text.getCompoundPaddingLeft(), text.getBaseline());
        } else {
          line =
              new RectF(
                  textLayout.getLineLeft(i),
                  textLayout.getLineTop(i),
                  textLayout.getLineRight(i),
                  textLayout.getLineBottom(i));
          line.offset(
              text.getTotalPaddingLeft() - text.getScrollX(),
              text.getTotalPaddingTop() - text.getScrollY());
        }
        matrix.mapRect(line);
        RectF clipped = new RectF(line);
        if (!clipped.intersect(new RectF(visible))
            || !clipped.intersect(new RectF(screen))
            || clipped.width() < line.width() * 0.95f
            || clipped.height() < line.height() * 0.95f) return;
        float margin = text.getResources().getDisplayMetrics().density;
        line.inset(-2 * margin, -margin);
        if (line.intersect(new RectF(screen)) && line.width() > 1 && line.height() > 1)
          regions.add(line);
      }
      if (!regions.isEmpty()) {
        Saved prior = saved.get(text);
        Target target = new Target(text, regions, prior == null ? null : prior.applied);
        target.finder = finder;
        if (finder) finderBackground(text, target);
        targets.add(target);
      }
    } else if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), screen, targets);
    }
  }

  private boolean finder(View view) {
    if (finderClass == null
        || !(finderClass.isInstance(view)
            || (view instanceof TextView
                && finderClass.getName().equals(view.getClass().getName())))
        || view.getId() == View.NO_ID) return false;
    try {
      if (!"finderTextView".equals(view.getResources().getResourceEntryName(view.getId())))
        return false;
      for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent())
        for (Class<?> type = p.getClass(); type != null; type = type.getSuperclass())
          if ("com.honeyspace.ui.common.pageindicator.PageIndicatorView".equals(type.getName()))
            return true;
    } catch (android.content.res.Resources.NotFoundException ignored) {
    }
    return false;
  }

  /** Draw only the Finder pill's backgrounds, never the text or child icons, over the wallpaper. */
  private void finderBackground(TextView view, Target target) {
    RectF union = new RectF(target.regions.get(0));
    for (RectF rect : target.regions) union.union(rect);
    Rect bounds = new Rect();
    union.roundOut(bounds);
    Bitmap overlay = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
    try {
      List<View> chain = new ArrayList<>();
      for (View v = view; v != null; v = v.getParent() instanceof View parent ? parent : null) {
        chain.add(v);
        if ("indicator_search_icon_layout".equals(resourceName(v))) break;
      }
      Collections.reverse(chain);
      Canvas canvas = new Canvas(overlay);
      for (View v : chain) {
        Drawable background = v.getBackground();
        if (background == null) continue;
        int checkpoint = canvas.save();
        canvas.translate(-bounds.left, -bounds.top);
        Matrix matrix = new Matrix();
        v.transformMatrixToGlobal(matrix);
        canvas.concat(matrix);
        background.draw(canvas);
        canvas.restoreToCount(checkpoint);
      }
      target.overlay = new int[bounds.width() * bounds.height()];
      overlay.getPixels(target.overlay, 0, bounds.width(), 0, 0, bounds.width(), bounds.height());
      target.overlayBounds = bounds;
    } finally {
      overlay.recycle();
    }
  }

  private String resourceName(View view) {
    try {
      return view.getResources().getResourceEntryName(view.getId());
    } catch (android.content.res.Resources.NotFoundException ignored) {
      return "";
    }
  }

  private void setTextColor(TextView view, int color) {
    ownTextColor = true;
    try {
      view.setTextColor(color);
    } finally {
      ownTextColor = false;
    }
  }

  private boolean homeIcon(View view) {
    boolean workspace = false;
    for (ViewParent parent = view.getParent();
        parent instanceof View;
        parent = parent.getParent()) {
      String name = parent.getClass().getName();
      if (name.contains(".applist.")
          || name.contains(".verticalapplist.")
          || name.contains(".folder.")) return false;
      if (name.startsWith("com.honeyspace.ui.honeypots.workspace.presentation.")
          || name.startsWith("com.honeyspace.ui.honeypots.freegrid.presentation.")
          || name.startsWith("com.honeyspace.ui.honeypots.hotseat.presentation.")) workspace = true;
    }
    return workspace;
  }

  private void capture(List<Target> targets, Rect screen, long epoch) {
    Bitmap raw = null, bitmap = null;
    Throwable failure = null;
    String unavailable = null;
    try {
      raw = (Bitmap) screenshot.invoke(windowService);
      if (raw == null) unavailable = "waiting";
      else if (raw.getWidth() != screen.width() || raw.getHeight() != screen.height())
        unavailable =
            "geometry-skipped"; // Do not guess crop/offset coordinates of oversized surfaces.
      else {
        float ratio = Math.min(1f, 1024f / raw.getHeight());
        bitmap =
            Bitmap.createBitmap(
                Math.max(1, Math.round(raw.getWidth() * ratio)),
                Math.max(1, Math.round(raw.getHeight() * ratio)),
                Bitmap.Config.ARGB_8888);
        new Canvas(bitmap)
            .drawBitmap(
                raw,
                null,
                new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        for (Target target : targets) {
          List<Integer> pixels = new ArrayList<>();
          for (RectF region : target.regions) {
            int left = Math.max(0, (int) (region.left * bitmap.getWidth() / screen.width()));
            int right =
                Math.min(
                    bitmap.getWidth(),
                    (int) Math.ceil(region.right * bitmap.getWidth() / screen.width()));
            int top = Math.max(0, (int) (region.top * ratio));
            int bottom = Math.min(bitmap.getHeight(), (int) Math.ceil(region.bottom * ratio));
            for (int y = top; y < bottom; y += Math.max(1, (bottom - top) / 8))
              for (int x = left; x < right; x += Math.max(1, (right - left) / 24)) {
                int color = bitmap.getPixel(x, y);
                if (target.overlay != null) {
                  Rect bounds = target.overlayBounds;
                  int ox =
                      Math.max(
                          0,
                          Math.min(
                              bounds.width() - 1,
                              Math.round((x + 0.5f) * screen.width() / bitmap.getWidth())
                                  - bounds.left));
                  int oy =
                      Math.max(
                          0,
                          Math.min(
                              bounds.height() - 1, Math.round((y + 0.5f) / ratio) - bounds.top));
                  color =
                      androidx.core.graphics.ColorUtils.compositeColors(
                          target.overlay[oy * bounds.width() + ox], color);
                }
                pixels.add(color);
              }
          }
          target.pixels = pixels.stream().mapToInt(Integer::intValue).toArray();
          target.color = LocalContrast.choose(target.pixels, target.previous);
        }
      }
    } catch (Throwable t) {
      failure = t;
    } finally {
      if (bitmap != null) bitmap.recycle();
      if (raw != null) raw.recycle();
    }
    Throwable error = failure;
    String skip = unavailable;
    main.post(
        () -> {
          busy = false;
          if (epoch != generation || !usable(home.get())) return;
          if (error != null) {
            disable(error);
            return;
          }
          if (skip != null) {
            // A temporarily hidden wallpaper during a page transition is not a compatibility error.
            if (!"waiting".equals(skip)) {
              geometryBlocked = true;
              restore();
            }
            update(skip);
            return;
          }
          try {
            geometryBlocked = false;
            int black = 0, white = 0;
            String finderState = finderClass == null ? "unavailable" : "not-visible";
            for (Target target : targets) {
              TextView view = target.view.get();
              if (view == null || !view.isAttachedToWindow() || !(homeIcon(view) || finder(view)))
                continue;
              if (target.color == null) {
                restore(view);
                remember(view); // Unknown pixels use native text, without repeatedly hiding it.
                continue;
              }
              Saved original = remember(view);
              boolean settled = LocalContrast.settled(original.pixels, target.pixels);
              original.pixels = target.pixels;
              // Publish a changed color only after two matching, settled local observations.
              if (settled && Objects.equals(original.candidate, target.color)) {
                original.finder = target.finder;
                if (target.finder) {
                  if (original.applied == null || view.getCurrentTextColor() != original.applied)
                    original.textColors = view.getTextColors();
                  setTextColor(view, target.color);
                  tintSearchIcon(view, original, target.color);
                } else {
                  Integer current = (Integer) getColor.invoke(view);
                  if (original.applied == null || !Objects.equals(current, original.applied))
                    original.original = current;
                  if (!Objects.equals(current, target.color)) setColor.invoke(view, target.color);
                  else if (view.getCurrentTextColor() != target.color)
                    setTextColor(view, target.color);
                }
                original.applied = target.color;
                if (original.pending) reveal(view, original, target.color);
              }
              original.candidate = target.color;
              if (target.finder) finderState = "waiting";
              if (original.applied != null && !original.pending) {
                if (target.finder)
                  finderState =
                      (original.applied == LocalContrast.BLACK ? "black" : "white")
                          + (original.appliedSearchTint != null ? "+icon" : "");
                else if (original.applied == LocalContrast.BLACK) black++;
                else white++;
              }
            }
            update(
                (black + white > 0 ? "active" : "waiting")
                    + " black="
                    + black
                    + " white="
                    + white
                    + " finder="
                    + finderState
                    + " initial="
                    + initialReveals
                    + " fallback="
                    + initialFallbacks);
          } catch (Throwable t) {
            disable(t);
          }
        });
  }

  private void tintSearchIcon(TextView text, Saved original, int color) {
    if (!(text.getParent() instanceof ViewGroup parent)) return;
    for (int i = 0; i < parent.getChildCount(); i++) {
      View child = parent.getChildAt(i);
      if (!(child instanceof ImageView image) || !"searchIcon".equals(resourceName(child)))
        continue;
      ImageView previous = original.search == null ? null : original.search.get();
      if (previous != image) {
        restoreSearchIcon(original);
        original.search = new WeakReference<>(image);
        original.searchTint = image.getImageTintList();
        original.appliedSearchTint = null;
      } else if (!Objects.equals(image.getImageTintList(), original.appliedSearchTint)) {
        original.searchTint = image.getImageTintList();
      }
      ColorStateList next = ColorStateList.valueOf(color);
      if (!Objects.equals(image.getImageTintList(), next)) image.setImageTintList(next);
      original.appliedSearchTint = next;
      return;
    }
  }

  private void restoreSearchIcon(Saved original) {
    ImageView image = original.search == null ? null : original.search.get();
    if (image != null
        && original.appliedSearchTint != null
        && Objects.equals(image.getImageTintList(), original.appliedSearchTint))
      image.setImageTintList(original.searchTint);
    original.search = null;
    original.appliedSearchTint = null;
  }

  private void restore(TextView view) {
    Saved original = saved.remove(view);
    if (original == null) return;
    if (original.animator != null) original.animator.cancel();
    restoreSearchIcon(original);
    if (original.attach != null) view.removeOnAttachStateChangeListener(original.attach);
    if (original.applied == null) return;
    try {
      if (original.finder) {
        if (view.getCurrentTextColor() == original.applied && original.textColors != null)
          view.setTextColor(original.textColors);
      } else if (Objects.equals(getColor.invoke(view), original.applied))
        setColor.invoke(view, original.original);
    } catch (Throwable t) {
      HookEntry.log("LOCAL_LABEL_RESTORE_ERROR " + t.getClass().getSimpleName());
    }
  }

  private void restore() {
    for (TextView view : new ArrayList<>(saved.keySet())) restore(view);
  }

  private void disable(Throwable t) {
    failed = true;
    enabled = false;
    cancel();
    restore();
    update("error:" + t.getClass().getSimpleName());
    HookEntry.log("LOCAL_LABEL_DISABLED " + t);
  }

  private void update(String next) {
    if (next.equals(state)) return;
    state = next;
    if (!next.equals(lastLog)) {
      HookEntry.log("LOCAL_LABEL " + next);
      lastLog = next;
    }
    if (changed != null) changed.run();
  }

  private static final class Saved {
    Integer original, applied, candidate;
    ColorStateList textColors;
    WeakReference<ImageView> search;
    ColorStateList searchTint, appliedSearchTint;
    ValueAnimator animator;
    View.OnAttachStateChangeListener attach;
    int[] pixels;
    boolean finder, pending;
  }

  private static final class Target {
    final WeakReference<TextView> view;
    final List<RectF> regions;
    final Integer previous;
    Integer color;
    int[] pixels, overlay;
    Rect overlayBounds;
    boolean finder;

    Target(TextView view, List<RectF> regions, Integer previous) {
      this.view = new WeakReference<>(view);
      this.regions = regions;
      this.previous = previous;
    }
  }
}
