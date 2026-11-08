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

  private android.widget.LinearLayout menu;
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
    root.addView(menuPanel(), menuLayoutParams());
    root.addView(resizeHandle(), resizeLayoutParams());

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

  private android.content.SharedPreferences prefs() {
    return app.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
  }

  /** 关窗/松手时把位置尺寸按 id 记下来，下次同 id 打开还原。 */
  private void saveGeometry() {
    try {
      prefs()
          .edit()
          .putInt("fw_" + id + "_x", params.x)
          .putInt("fw_" + id + "_y", params.y)
          .putInt("fw_" + id + "_w", params.width)
          .putInt("fw_" + id + "_h", params.height)
          .apply();
    } catch (Throwable ignored) {
    }
  }

  private void apply(int x, int y, int w, int h, int alpha) {
    int screenW = app.getResources().getDisplayMetrics().widthPixels;
    int screenH = app.getResources().getDisplayMetrics().heightPixels;
    android.content.SharedPreferences p = prefs();
    if (w > 0) params.width = w;
    else params.width = Math.max(dp(MIN_W_DP), Math.min(screenW, p.getInt("fw_" + id + "_w", params.width)));
    if (h > 0) params.height = h;
    else params.height = Math.max(dp(MIN_H_DP), Math.min(screenH, p.getInt("fw_" + id + "_h", params.height)));
    params.width = Math.max(dp(MIN_W_DP), Math.min(screenW, params.width));
    params.height = Math.max(dp(MIN_H_DP), Math.min(screenH, params.height));
    int px = x < 0 ? p.getInt("fw_" + id + "_x", params.x) : x;
    int py = y < 0 ? p.getInt("fw_" + id + "_y", params.y) : y;
    params.x = clamp(px, 0, Math.max(0, screenW - params.width));
    params.y = clamp(py, 0, Math.max(0, screenH - params.height));
    float a = Math.max(0.2f, Math.min(1f, (alpha <= 0 ? 100 : alpha) / 100f));
    root.setAlpha(a);
  }

  /** 顶部一层：透明拖动区 + 右上角两个半透明小圆钮（≡ / ×），尽量不挡页面。 */
  private View bar() {
    FrameLayout bar = new FrameLayout(app);
    View strip = new View(app);
    bar.addView(
        strip,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    android.widget.TextView settings = pill("≡");
    FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(24), dp(24));
    sp.gravity = Gravity.TOP | Gravity.END;
    sp.topMargin = dp(3);
    sp.rightMargin = dp(30);
    bar.addView(settings, sp);
    settings.setOnClickListener(
        v -> {
          if (menu != null)
            menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

    android.widget.TextView close = pill("×");
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(24), dp(24));
    cp.gravity = Gravity.TOP | Gravity.END;
    cp.topMargin = dp(3);
    cp.rightMargin = dp(4);
    bar.addView(close, cp);
    close.setOnClickListener(
        v -> {
          FloatWebWindow win = OPEN.remove(id);
          if (win != null) win.destroy();
        });

    strip.setOnTouchListener(
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
              if (dragMoved) saveGeometry();
              return true;
            default:
              return true;
          }
        });
    return bar;
  }

  private android.widget.TextView pill(String label) {
    android.widget.TextView view = new android.widget.TextView(app);
    view.setText(label);
    view.setTextSize(12);
    view.setTextColor(0xCCFFFFFF);
    view.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0x55000000);
    bg.setCornerRadius(dp(12));
    view.setBackground(bg);
    return view;
  }

  /** 右上角 ≡ 展开的设置面板：透明度滑杆。 */
  private View menuPanel() {
    menu = new android.widget.LinearLayout(app);
    menu.setOrientation(android.widget.LinearLayout.VERTICAL);
    android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
    card.setColor(0xF2161A22);
    card.setCornerRadius(dp(12));
    card.setStroke(dp(1), 0x1FFFFFFF);
    menu.setBackground(card);
    menu.setPadding(dp(10), dp(8), dp(10), dp(8));
    menu.setVisibility(View.GONE);

    android.widget.TextView label = new android.widget.TextView(app);
    label.setText("透明度 " + Math.round(rootAlpha() * 100) + "%");
    label.setTextSize(11);
    label.setTextColor(0xFFE6EAF2);
    menu.addView(label);

    android.widget.SeekBar bar = new android.widget.SeekBar(app);
    bar.setMax(80);
    bar.setProgress(Math.round(rootAlpha() * 100) - 20);
    bar.setOnSeekBarChangeListener(
        new android.widget.SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(android.widget.SeekBar seekBar, int value, boolean fromUser) {
            int alpha = 20 + value;
            label.setText("透明度 " + alpha + "%");
            root.setAlpha(alpha / 100f);
          }

          @Override
          public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

          @Override
          public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
    menu.addView(bar);

    android.widget.TextView reset = new android.widget.TextView(app);
    reset.setText("重置位置与大小");
    reset.setTextSize(11);
    reset.setTextColor(0xCC7AA2FF);
    reset.setPadding(0, dp(8), 0, 0);
    reset.setOnClickListener(
        v -> {
          try {
            prefs().edit()
                .remove("fw_" + id + "_x")
                .remove("fw_" + id + "_y")
                .remove("fw_" + id + "_w")
                .remove("fw_" + id + "_h")
                .apply();
          } catch (Throwable ignored) {
          }
          int screenW = app.getResources().getDisplayMetrics().widthPixels;
          int screenH = app.getResources().getDisplayMetrics().heightPixels;
          params.width = Math.min(screenW, dp(320));
          params.height = Math.min(screenH, dp(420));
          params.x = Math.max(0, (screenW - params.width) / 2);
          params.y = Math.max(0, (screenH - params.height) / 3);
          try {
            if (wm != null && root.isAttachedToWindow()) wm.updateViewLayout(root, params);
          } catch (Throwable ignored) {
          }
          menu.setVisibility(View.GONE);
        });
    menu.addView(reset);

    android.widget.TextView hint = new android.widget.TextView(app);
    hint.setText("拖右下角改宽高 · 顶部拖动移动");
    hint.setTextSize(10);
    hint.setTextColor(0x80FFFFFF);
    hint.setPadding(0, dp(6), 0, 0);
    menu.addView(hint);
    return menu;
  }

  private float rootAlpha() {
    float a = root.getAlpha();
    return a <= 0.01f ? 1f : a;
  }

  private FrameLayout.LayoutParams menuLayoutParams() {
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.gravity = Gravity.TOP | Gravity.END;
    lp.topMargin = dp(HEAD_DP + 2);
    lp.rightMargin = dp(4);
    return lp;
  }

  /** 右下角缩放抓手。 */
  private View resizeHandle() {
    View handle = new View(app);
    handle.setBackgroundColor(0x33000000);
    handle.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              downX = event.getRawX();
              downY = event.getRawY();
              baseW = params.width;
              baseH = params.height;
              return true;
            case MotionEvent.ACTION_MOVE: {
              int screenW = app.getResources().getDisplayMetrics().widthPixels;
              int screenH = app.getResources().getDisplayMetrics().heightPixels;
              params.width =
                  clamp(baseW + Math.round(event.getRawX() - downX), dp(MIN_W_DP),
                      Math.max(dp(MIN_W_DP), screenW - params.x));
              params.height =
                  clamp(baseH + Math.round(event.getRawY() - downY), dp(MIN_H_DP),
                      Math.max(dp(MIN_H_DP), screenH - params.y));
              try {
                if (wm != null && root.isAttachedToWindow()) wm.updateViewLayout(root, params);
              } catch (Throwable ignored) {
              }
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              saveGeometry();
              return true;
            default:
              return true;
          }
        });
    return handle;
  }

  private FrameLayout.LayoutParams resizeLayoutParams() {
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(20), dp(20));
    lp.gravity = Gravity.BOTTOM | Gravity.END;
    return lp;
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
