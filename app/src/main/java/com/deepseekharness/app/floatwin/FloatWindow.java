package com.deepseekharness.app.floatwin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;
import org.json.JSONObject;

/**
 * 富内容悬浮窗：原生只负责窗口、拖动与生命周期，内容由本地 HTML 渲染。
 *
 * <p>数据与控制走一条 JSON 消息通道（WebView &lt;-&gt; 原生），方法名与参数都是普通 JSON，
 * 传输层不包含业务判断；要加交互只需在 {@link #onMessage} 里加一个分支，要换 UI 只改
 * {@code assets/floatwin/index.html}。
 *
 * <p>性能约定：WebView 只在首次显示时创建并复用，不重复加载；更新只做一次
 * {@code evaluateJavascript}，由页面里的 rAF 合帧，避免每个 token 触发一次布局。
 */
public final class FloatWindow {
  /** 业务回调：页面上的交互回传到这里。 */
  public interface Callback {
    void onConfirm(boolean allow);

    void onDismiss();
  }

  private static final String BASE_URL = "https://float.dsha.local/";
  private static final String BRIDGE = "DshaFloat";
  private static final String K_Y = "floatwin_y";

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static WebView web;
  private static WindowManager wm;
  private static WindowManager.LayoutParams params;
  private static View root;
  private static Callback handler;
  private static int dragBaseY;
  private static float dragStartScreenY;
  private static boolean dragging;

  private FloatWindow() {}

  public static void show(Context context, String payload, Callback callback) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    handler = callback;
    main.post(() -> showOnMain(app, payload));
  }

  public static void update(String payload) {
    if (payload == null) return;
    main.post(
        () -> {
          if (web == null) return;
          web.evaluateJavascript(
              "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(payload) + ")", null);
        });
  }

  public static void hide(Context context) {
    main.post(FloatWindow::dismiss);
  }

  private static void showOnMain(Context app, String payload) {
    if (web == null) {
      if (!create(app)) return;
    }
    try {
      if (root != null && root.getWindowToken() == null) wm.addView(root, params);
    } catch (Throwable ignored) {
      return;
    }
    update(payload);
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
    lp.width =
        Math.min(
            (int) (app.getResources().getDisplayMetrics().widthPixels * 0.92f),
            720 * app.getResources().getDisplayMetrics().densityDpi / 160);
    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    SharedPreferences prefs =
        app.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    lp.y = prefs.getInt(K_Y, (int) (72 * app.getResources().getDisplayMetrics().density));

    try {
      wm.addView(view, lp);
    } catch (Throwable error) {
      return false;
    }
    web = view;
    root = view;
    params = lp;
    String html = readAsset(app, "floatwin/index.html");
    view.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
    return true;
  }

  /** JSON 消息分发：页面只管发方法名，原生只管执行。 */
  private static void onMessage(String data, androidx.webkit.JavaScriptReplyProxy reply) {
    try {
      JSONObject message = new JSONObject(data == null ? "{}" : data);
      String method = message.optString("method", "");
      JSONObject p = message.optJSONObject("params");
      if (p == null) p = new JSONObject();
      switch (method) {
        case "ready":
          reply.postMessage("{\"ok\":true}");
          return;
        case "drag": {
          float dy = (float) p.optDouble("dy", 0);
          if (!dragging) {
            dragging = true;
            dragBaseY = params.y;
            dragStartScreenY = 0;
          }
          if (params == null || root == null || wm == null) return;
          int y = Math.max(0, dragBaseY + (int) dy);
          params.y = y;
          try {
            wm.updateViewLayout(root, params);
          } catch (RuntimeException ignored) {
          }
          return;
        }
        case "dragEnd": {
          dragging = false;
          if (root == null || params == null) return;
          root.getContext()
              .getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
              .edit()
              .putInt(K_Y, params.y)
              .apply();
          return;
        }
        case "confirm": {
          Callback h = handler;
          if (h != null) h.onConfirm(p.optBoolean("allow", false));
          return;
        }
        case "dismiss": {
          dismiss();
          Callback h = handler;
          if (h != null) h.onDismiss();
          return;
        }
        default:
          reply.postMessage("{\"ok\":false,\"error\":\"unknown method\"}");
      }
    } catch (Exception ignored) {
    }
  }

  private static void dismiss() {
    if (root != null && wm != null) {
      try {
        wm.removeViewImmediate(root);
      } catch (Throwable ignored) {
      }
    }
    if (web != null) {
      try {
        web.destroy();
      } catch (Throwable ignored) {
      }
    }
    web = null;
    root = null;
    wm = null;
    params = null;
    dragging = false;
  }

  private static String readAsset(Context context, String path) {
    try (java.io.InputStream in = context.getAssets().open(path);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      return "<html><body style='color:#fff;background:#111'>floatwin missing</body></html>";
    }
  }
}
