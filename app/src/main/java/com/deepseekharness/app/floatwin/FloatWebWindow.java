package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通用 WebView 悬浮窗：一个 id 一扇窗，可同时开多个。
 *
 * <p>与聊天悬浮窗（{@link FloatWindow}）分开：这里只负责「把某个页面悬浮出来」，
 * 位置/尺寸/透明度都可由调用方指定，也支持传 html 直接渲染（UI 稿预览用）。
 * 每扇窗都能拖动、右下角缩放、右上角关闭，彼此独立。
 */
public final class FloatWebWindow {
  private static final String BASE_URL = "https://float.dsha.local/preview/";
  private static final int MIN_W_DP = 160;
  private static final int MIN_H_DP = 120;
  private static final int HEAD_DP = 28;

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final Map<String, FloatWebWindow> OPEN = new LinkedHashMap<>();

  private final String id;
  private final Context app;
  private final FrameLayout root;
  private final WebView web;
  private final WindowManager wm;
  private final WindowManager.LayoutParams params;

  private boolean moving;
  private boolean dragMoved;
  private float downX;
  private float downY;
  private int baseX;
  private int baseY;
  private int baseW;
  private int baseH;

  private FloatWebWindow(String id, Context app, String url, String html) {
    this.id = id;
    this.app = app;
    this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);

    web = new WebView(app);
    web.setBackgroundColor(Color.WHITE);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.setWebViewClient(new WebViewClient());

    root = new FrameLayout(app);
    root.addView(
        web,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(bar(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEAD_DP)));

    WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
    lp.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    lp.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    lp.format = PixelFormat.TRANSLUCENT;
    int screenW = app.getResources().getDisplayMetrics().widthPixels;
    int screenH = app.getResources().getDisplayMetrics().heightPixels;
    lp.width = Math.min(screenW, dp(320));
    lp.height = Math.min(screenH, dp(420));
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = Math.max(0, (screenW - lp.width) / 2);
    lp.y = Math.max(0, (screenH - lp.height) / 3);
    params = lp;

    if (html != null && !html.isEmpty()) {
      web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
    } else if (url != null && !url.isEmpty()) {
      web.loadUrl(url);
    }
  }

  public static void open(
      Context context,
      String id,
      String url,
      String html,
      int x,
      int y,
      int w,
      int h,
      int alpha) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    final String key = id == null || id.isEmpty() ? "win-" + System.currentTimeMillis() : id;
    main.post(
        () -> {
          FloatWebWindow existing = OPEN.get(key);
          if (existing != null) {
            existing.load(url, html);
            return;
          }
          FloatWebWindow win = new FloatWebWindow(key, app, url, html);
          win.apply(x, y, w, h, alpha);
          try {
            win.wm.addView(win.root, win.params);
          } catch (Throwable error) {
            return;
          }
          OPEN.put(key, win);
        });
  }

  public static void close(String id) {
    main.post(
        () -> {
          FloatWebWindow win = id == null ? null : OPEN.remove(id);
          if (win != null) win.destroy();
        });
  }

  public static void closeAll() {
    main.post(
        () -> {
          for (FloatWebWindow win : OPEN.values()) win.destroy();
          OPEN.clear();
        });
  }

  public static String list() {
    JSONArray ids = new JSONArray();
    for (String id : OPEN.keySet()) ids.put(id);
    return ids.toString();
  }

  private void load(String url, String html) {
    try {
      if (html != null && !html.isEmpty())
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
      else if (url != null && !url.isEmpty()) web.loadUrl(url);
    } catch (Throwable ignored) {
    }
  }

  private void apply(int x, int y, int w, int h, int alpha) {
    int screenW = app.getResources().getDisplayMetrics().widthPixels;
    int screenH = app.getResources().getDisplayMetrics().heightPixels;
    if (w > 0) params.width = Math.max(dp(MIN_W_DP), Math.min(screenW, w));
    if (h > 0) params.height = Math.max(dp(MIN_H_DP), Math.min(screenH, h));
    params.x = clamp(x < 0 ? params.x : x, 0, Math.max(0, screenW - params.width));
    params.y = clamp(y < 0 ? params.y : y, 0, Math.max(0, screenH - params.height));
    float a = Math.max(0.2f, Math.min(1f, (alpha <= 0 ? 100 : alpha) / 100f));
    root.setAlpha(a);
  }

  /** 顶部拖动条 + 关闭按钮，最省事的一层原生控件。 */
  private View bar() {
    FrameLayout bar = new FrameLayout(app);
    View strip = new View(app);
    strip.setBackgroundColor(0x33000000);
    bar.addView(
        strip,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    android.widget.TextView title = new android.widget.TextView(app);
    title.setText(id);
    title.setTextSize(11);
    title.setTextColor(0xFFFFFFFF);
    title.setPadding(dp(8), 0, dp(40), 0);
    title.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(
        title,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    android.widget.TextView close = new android.widget.TextView(app);
    close.setText("×");
    close.setTextSize(14);
    close.setTextColor(0xFFFFFFFF);
    close.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(28), dp(28));
    cp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
    bar.addView(close, cp);
    close.setOnClickListener(v -> {
      FloatWebWindow win = OPEN.remove(id);
      if (win != null) win.destroy();
    });

    bar.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              moving = true;
              dragMoved = false;
              downX = event.getRawX();
              downY = event.getRawY();
              baseX = params.x;
              baseY = params.y;
              return true;
            case MotionEvent.ACTION_MOVE: {
              int dx = Math.round(event.getRawX() - downX);
              int dy = Math.round(event.getRawY() - downY);
              if (!dragMoved && Math.abs(dx) + Math.abs(dy) > 6) dragMoved = true;
              if (dragMoved) {
                int screenW = app.getResources().getDisplayMetrics().widthPixels;
                int screenH = app.getResources().getDisplayMetrics().heightPixels;
                params.x = clamp(baseX + dx, 0, Math.max(0, screenW - params.width));
                params.y = clamp(baseY + dy, 0, Math.max(0, screenH - params.height));
                try {
                  if (wm != null && root.isAttachedToWindow()) wm.updateViewLayout(root, params);
                } catch (Throwable ignored) {
                }
              }
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              moving = false;
              return true;
            default:
              return true;
          }
        });
    return bar;
  }

  private void destroy() {
    try {
      if (wm != null) wm.removeViewImmediate(root);
    } catch (Throwable ignored) {
    }
    try {
      web.destroy();
    } catch (Throwable ignored) {
    }
  }

  private static int clamp(int value, int min, int max) {
    if (max < min) return min;
    return Math.max(min, Math.min(max, value));
  }

  private int dp(int value) {
    return Math.round(value * app.getResources().getDisplayMetrics().density);
  }
}
