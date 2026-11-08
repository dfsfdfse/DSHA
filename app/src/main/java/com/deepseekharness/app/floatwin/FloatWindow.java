package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.content.SharedPreferences;
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
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;
import org.json.JSONObject;

/**
 * 富内容悬浮窗：原生窗口 + 本地 HTML 内容。
 *
 * <p>拖动由窗口内一条原生触摸带完成（不是页面事件）：触摸带随窗口一起移动，
 * 手势不会因为窗口位移而中断，位置在松手时持久化。轻点触摸带切换展开。
 */
public final class FloatWindow {
  public interface Callback {
    void onConfirm(boolean allow);

    void onDismiss();
  }

  private static final String BASE_URL = "https://float.dsha.local/";
  private static final String BRIDGE = "DshaFloat";
  private static final String K_Y = "floatwin_y";
  private static final String K_X = "floatwin_x";

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static FrameLayout container;
  private static WebView web;
  private static WindowManager wm;
  private static WindowManager.LayoutParams params;
  private static Callback callback;
  private static String pendingPayload = "";
  private static boolean expanded;
  private static Runnable hideTask;
  private static boolean dragging;
  private static boolean dragMoved;
  private static float downRawX;
  private static float downRawY;
  private static int baseX;
  private static int baseY;

  private FloatWindow() {}

  public static void show(Context context, String payload, Callback cb) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    callback = cb;
    pendingPayload = payload == null ? "" : payload;
    main.post(() -> showOnMain(app));
  }

  public static void update(String payload) {
    if (payload == null) return;
    pendingPayload = payload;
    main.post(
        () -> {
          if (web == null) return;
          web.evaluateJavascript(
              "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(payload) + ")", null);
          resetHideTimer();
        });
  }

  public static void hide(Context context) {
    main.post(FloatWindow::dismiss);
  }

  private static void showOnMain(Context app) {
    if (web == null && !create(app)) return;
    pushPayload();
    resetHideTimer();
  }

  private static boolean create(Context app) {
    wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    if (wm == null) return false;

    WebView view = new WebView(app);
    view.setBackgroundColor(Color.TRANSPARENT);
    view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    view.getSettings().setJavaScriptEnabled(true);
    view.getSettings().setDomStorageEnabled(true);
    view.getSettings().setAllowFileAccess(false);
    view.getSettings().setAllowContentAccess(false);
    view.getSettings().setSupportZoom(false);
    view.getSettings().setTextZoom(100);
    view.setVerticalScrollBarEnabled(false);
    view.setHorizontalScrollBarEnabled(false);
    view.setWebViewClient(new WebViewClient());

    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      WebViewCompat.addWebMessageListener(
          view,
          BRIDGE,
          Collections.singleton(BASE_URL.substring(0, BASE_URL.length() - 1)),
          (source, message, origin, mainFrame, reply) -> {
            if (!mainFrame) return;
            onMessage(message.getData(), reply);
          });
    }

    FrameLayout frame = new FrameLayout(app);
    frame.addView(
        view,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // 顶部拖动带：覆盖标题区，随窗口一起移动，手势稳定。
    View strip = new View(app);
    strip.setBackgroundColor(Color.TRANSPARENT);
    FrameLayout.LayoutParams stripParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, 78));
    stripParams.gravity = Gravity.TOP;
    frame.addView(strip, stripParams);
    strip.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              dragging = true;
              dragMoved = false;
              downRawX = event.getRawX();
              downRawY = event.getRawY();
              baseX = params.x;
              baseY = params.y;
              cancelHide();
              return true;
            case MotionEvent.ACTION_MOVE: {
              int dx = Math.round(event.getRawX() - downRawX);
              int dy = Math.round(event.getRawY() - downRawY);
              if (!dragMoved && Math.abs(dx) + Math.abs(dy) > 6) dragMoved = true;
              if (dragMoved) {
                int screen = app.getResources().getDisplayMetrics().widthPixels;
                int maxX = Math.max(0, screen - params.width);
                params.x = Math.max(0, Math.min(maxX, baseX + dx));
                params.y = Math.max(0, baseY + dy);
                try {
                  wm.updateViewLayout(frame, params);
                } catch (RuntimeException ignored) {
                }
              }
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
              dragging = false;
              if (dragMoved) {
                prefs(app).edit().putInt(K_X, params.x).putInt(K_Y, params.y).apply();
              } else {
                view.evaluateJavascript("window.dshFloat&&window.dshFloat.toggle()", null);
              }
              resetHideTimer();
              return true;
            }
            default:
              return true;
          }
        });

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
    int screen = app.getResources().getDisplayMetrics().widthPixels;
    lp.width = Math.min((int) (screen * 0.94f), 760);
    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
    lp.gravity = Gravity.TOP | Gravity.START;
    SharedPreferences p = prefs(app);
    lp.x = p.getInt(K_X, (screen - lp.width) / 2);
    lp.y = p.getInt(K_Y, dp(app, 64));

    try {
      wm.addView(frame, lp);
    } catch (Throwable error) {
      return false;
    }
    web = view;
    container = frame;
    params = lp;
    view.loadDataWithBaseURL(BASE_URL, readAsset(app, "floatwin/index.html"), "text/html", "utf-8", null);
    return true;
  }

  private static void onMessage(String data, JavaScriptReplyProxy reply) {
    try {
      JSONObject message = new JSONObject(data == null ? "{}" : data);
      String method = message.optString("method", "");
      JSONObject p = message.optJSONObject("params");
      if (p == null) p = new JSONObject();
      switch (method) {
        case "ready":
          pushPayload();
          reply.postMessage("{\"ok\":true}");
          return;
        case "expanded":
          expanded = p.optBoolean("value", false);
          if (expanded) cancelHide();
          else resetHideTimer();
          if (container != null) container.requestLayout();
          return;
        case "resize":
          if (container != null && wm != null && params != null) {
            container.requestLayout();
            try {
              wm.updateViewLayout(container, params);
            } catch (RuntimeException ignored) {
            }
          }
          resetHideTimer();
          return;
        case "activity":
          resetHideTimer();
          return;
        case "confirm": {
          Callback cb = callback;
          if (cb != null) cb.onConfirm(p.optBoolean("allow", false));
          return;
        }
        case "open": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null) {
            android.content.Intent intent =
                ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (intent != null) {
              intent.addFlags(
                  android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                      | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
              try {
                ctx.startActivity(intent);
              } catch (RuntimeException ignored) {
              }
            }
          }
          dismiss();
          return;
        }
        case "dismiss": {
          dismiss();
          Callback cb = callback;
          if (cb != null) cb.onDismiss();
          return;
        }
        default:
          reply.postMessage("{\"ok\":false,\"error\":\"unknown method\"}");
      }
    } catch (Exception ignored) {
    }
  }

  private static void pushPayload() {
    if (web == null) return;
    web.evaluateJavascript(
        "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(withChrome()) + ")", null);
  }

  /** 宿主配置（半透明度）合进载荷，页面只负责显示。 */
  private static String withChrome() {
    try {
      JSONObject payload = new JSONObject(pendingPayload.isEmpty() ? "{}" : pendingPayload);
      Context ctx = web == null ? null : web.getContext();
      if (ctx != null) {
        int alpha = Math.max(20, Math.min(100, prefs(ctx).getInt("floatwin_alpha", 60)));
        payload.put("alpha", alpha);
      }
      return payload.toString();
    } catch (Exception ignored) {
      return pendingPayload;
    }
  }

  private static void resetHideTimer() {
    cancelHide();
    if (expanded || web == null) return;
    int hold = Math.max(2, Math.min(60, prefs(web.getContext()).getInt("overlay_hold_sec", 6)));
    hideTask =
        () -> {
          if (!expanded) dismiss();
        };
    main.postDelayed(hideTask, hold * 1000L);
  }

  private static void cancelHide() {
    if (hideTask != null) {
      main.removeCallbacks(hideTask);
      hideTask = null;
    }
  }

  private static void dismiss() {
    cancelHide();
    expanded = false;
    dragging = false;
    if (container != null && wm != null) {
      try {
        wm.removeViewImmediate(container);
      } catch (Throwable ignored) {
      }
    }
    if (web != null) {
      try {
        web.destroy();
      } catch (Throwable ignored) {
      }
    }
    container = null;
    web = null;
    wm = null;
    params = null;
  }

  private static int dp(Context ctx, int v) {
    return Math.round(v * ctx.getResources().getDisplayMetrics().density);
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
  }

  private static String readAsset(Context context, String path) {
    try (java.io.InputStream in = context.getAssets().open(path);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      return "<html><body style='color:#fff'>floatwin missing</body></html>";
    }
  }
}
