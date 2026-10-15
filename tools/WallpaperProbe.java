import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.*;

/** Read-only probe. Run with app_process, never modifies settings or wallpaper. */
public final class WallpaperProbe {
  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    Class<?> at = Class.forName("android.app.ActivityThread");
    Object thread = at.getMethod("systemMain").invoke(null);
    Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
    WallpaperManager wm = WallpaperManager.getInstance(context);
    System.out.println("uid=" + android.os.Process.myUid() + " system-context user=0");
    for (String name :
        new String[] {
          "android.app.WallpaperManager",
          "android.app.SemWallpaperColors",
          "android.app.SemWallpaperColors$Item",
          "com.samsung.android.wallpaper.utils.WhichChecker"
        }) {
      try {
        Class<?> c = Class.forName(name);
        for (Method m : c.getDeclaredMethods()) {
          if (name.contains("WhichChecker")
              || m.getName().toLowerCase().matches(".*(color|dim|which|mode|local).*"))
            System.out.println("METHOD " + m);
        }
        for (Field f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers())
              && Modifier.isPublic(f.getModifiers())
              && (f.getName().contains("HOMESCREEN") || f.getName().contains("FONT_COLOR")))
            System.out.println("FIELD " + f.getName() + "=" + f.get(null));
        }
      } catch (Throwable e) {
        System.out.println("ERROR " + name + " " + e);
      }
    }
    try {
      System.out.println("INFO " + wm.getWallpaperInfo());
    } catch (Throwable e) {
      System.out.println(e);
    }
    if (args.length > 0 && "--thumbnail".equals(args[0])) {
      try {
        android.graphics.drawable.Drawable d =
            wm.getWallpaperInfo().loadThumbnail(context.getPackageManager());
        if (d != null) {
          int w = Math.max(1, Math.min(1024, d.getIntrinsicWidth()));
          int h = Math.max(1, Math.min(2048, d.getIntrinsicHeight()));
          android.graphics.Bitmap b =
              android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
          d.setBounds(0, 0, w, h);
          d.draw(new android.graphics.Canvas(b));
          try (java.io.FileOutputStream out =
              new java.io.FileOutputStream("/data/local/tmp/oneui-static-thumbnail.png")) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
          }
          b.recycle();
          System.out.println("THUMBNAIL saved from WallpaperInfo.loadThumbnail()");
        }
      } catch (Throwable e) {
        System.out.println("THUMBNAIL ERROR " + e);
      }
    }
    for (int which : new int[] {1, 2}) {
      try {
        print("GET which=" + which, wm.getWallpaperColors(which));
      } catch (Throwable e) {
        System.out.println("GET ERROR " + which + " " + e);
      }
    }
    for (int which : new int[] {5, 6, 9, 17}) {
      try {
        System.out.println(
            "SEM which="
                + which
                + " "
                + wm.getClass().getMethod("semGetWallpaperColors", int.class).invoke(wm, which));
      } catch (Throwable e) {
        System.out.println("SEM ERROR " + which + " " + e);
      }
    }
    // Read Settings via adb shell separately: app_process has no registered app provider token.
    Handler handler = new Handler(Looper.getMainLooper());
    WallpaperManager.OnColorsChangedListener listener =
        (colors, which) -> print("EVENT which=" + which, colors);
    try {
      wm.addOnColorsChangedListener(listener, handler);
    } catch (Throwable e) {
      System.out.println("LISTENER ERROR " + e);
    }
    handler.postDelayed(
        () -> {
          wm.removeOnColorsChangedListener(listener);
          System.out.println("DONE");
          System.exit(0);
        },
        15000);
    Looper.loop();
  }

  static void print(String label, WallpaperColors c) {
    System.out.println(
        label
            + " colors="
            + c
            + (c == null
                ? ""
                : " hints="
                    + c.getColorHints()
                    + " darkText="
                    + ((c.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0)
                    + " darkTheme="
                    + ((c.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0)));
  }
}
