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
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

/**
 * 富内容悬浮窗：原生窗口 + 本地 HTML 内容。
 *
 * <p>窗口尺寸固定后由页面自适应填满：整个窗口常驻，不会因为等待自动消失，只有用户点「关闭」才移除。
 * 拖动与缩放都由窗口内的原生触摸层完成（不是页面事件），手势不会因为窗口位移而中断：
 * 顶部一条触摸带负责移动，右下角与右/下边缘负责改变宽高，位置与尺寸在松手时持久化。
 * 所有拖动与缩放都限制在屏幕范围内，窗口不会被拖出屏幕或压在屏幕外。
 */
public final class FloatWindow {
  public interface Callback {
    void onConfirm(boolean allow);

    void onDismiss();
  }

  private static final String BASE_URL = "https://float.dsha.local/";
  private static final String BRIDGE = "DshaFloat";
  private static final String K_X = "floatwin_x";
  private static final String K_Y = "floatwin_y";
  private static final String K_W = "floatwin_w";
  private static final String K_H = "floatwin_h";

  private static final int HEAD_DP = 30;
  private static final int HANDLE_DP = 28;
  private static final int EDGE_DP = 12;
  /** 容器内与悬浮窗交换数据的两个文件（宿主直接读写 rootfs，不依赖 3090 桥）。 */
  private static final String GUEST_STATE = "float-state.json";

  private static final String GUEST_INBOX = "float-inbox.json";
  private static final int MIN_W_DP = 200;
  private static final int MIN_H_DP = 140;

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static Context appCtx;
  private static FrameLayout container;
  private static WebView web;
  private static WindowManager wm;
  private static WindowManager.LayoutParams params;
  private static Callback callback;
  private static String pendingPayload = "";

  private static Context watchCtx;
  private static boolean watching;
  private static long stateStamp = -1L;

  /** true = 悬浮窗里跑的是 dsh 真实页面（输入栏/附件/语音全是原生的）。 */
  private static boolean realPage;
  /** 用户点过「关闭」：别在同一个 Web 会话里反复自动弹出。 */
  private static boolean userClosed;
  private static String lastWebUrl = "";
  private static android.webkit.ValueCallback<android.net.Uri[]> pendingFileChooser;
  private static boolean pendingImage;
  /** 录音状态：true=正在录；用 dsh 的 speechToText 转写，不走系统识别。 */
  private static boolean recording;
  private static android.media.AudioRecord recorder;
  private static java.io.ByteArrayOutputStream pcm;
  private static long voiceSeq;
  private static long lastVoiceResult;

  private static final String GUEST_VOICE = "float-voice.json";
  private static final String GUEST_VOICE_RESULT = "float-voice-result.json";

  private static boolean moving;
  private static boolean resizing;
  private static boolean dragMoved;
  private static float downRawX;
  private static float downRawY;
  private static int baseX;
  private static int baseY;
  private static int baseW;
  private static int baseH;

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
          if (web == null || realPage) return;
          web.evaluateJavascript(
              "window.dshFloat&&window.dshFloat.onData(" + JSONObject.quote(payload) + ")", null);
        });
  }

  /**
   * 开始轮询容器内的状态文件：dsh 插件把会话消息写进 /root/.dsh/float-state.json，
   * 宿主读它来显示/更新悬浮窗。走文件而不是 3090 桥，是因为与应用共存的另一个实例
   * 可能占着 3090，跨实例的 HTTP 推送会落到别人身上（表现为「悬浮窗不出现」）。
   */
  public static void startGuestWatch(Context context) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          watchCtx = app;
          if (watching) return;
          watching = true;
          main.postDelayed(FloatWindow::pollOnce, 600);
        });
  }

  private static void pollOnce() {
    try {
      pollReal();
    } catch (Throwable ignored) {
    }
    try {
      pollGuest();
    } catch (Throwable ignored) {
    }
    try {
      if (watchCtx != null) pollVoiceResult(watchCtx);
    } catch (Throwable ignored) {
    }
    if (watching) main.postDelayed(FloatWindow::pollOnce, 900);
  }

  /** dsh 一起好就把真实页面塞进悬浮窗 —— 输入栏、附件、模型、语音都是原生的。 */
  private static void pollReal() {
    // 悬浮窗只从入口打开：这里不再自动弹窗，也不再切到 dsh 真页面。
  }

  private static String webUrl(Context ctx) {
    try {
      com.deepseekharness.app.core.HarnessController hc =
          com.deepseekharness.app.core.HarnessController.get(ctx);
      if (hc == null) return null;
      String url = hc.getWebAuthUrl();
      if (url == null || url.length() < 8 || !url.startsWith("http")) return null;
      return url;
    } catch (Throwable error) {
      return null;
    }
  }

  /** 用户重新启动 dsh：允许这次启动完成后再次自动弹出（清掉「用户关过」的记忆）。 */
  public static void resetAutoShow() {
    main.post(
        () -> {
          userClosed = false;
          lastWebUrl = "";
        });
  }

  /** 打开自绘聊天窗（入口按钮用）：绝不加载 dsh 页面，先用容器里最新的状态渲染。 */
  public static void showCustom(Context context) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          if (!com.deepseekharness.app.OverlayController.permitted(app)) return;
          if (web != null && realPage) dismiss();
          if (web == null && !create(app, true)) return;
          realPage = false;
          userClosed = false;
          File state = guestFile(app, GUEST_STATE);
          String json = state != null && state.isFile() ? readText(state) : null;
          if (json != null && !json.isEmpty() && !json.contains("\"clear\"")) {
            pendingPayload = json;
            update(json);
          } else if (web != null) {
            web.evaluateJavascript("window.dshFloat&&window.dshFloat.onData({tasks:[]})", null);
          }
        });
  }

  /** 用 dsh 真实页面填充悬浮窗；窗口不存在就先建一个。 */
  public static void showRealPage(Context context) {
    if (context == null) return;
    final Context app = context.getApplicationContext();
    main.post(
        () -> {
          String url = webUrl(app);
          if (url == null) return;
          if (web != null && realPage) return;
          if (web != null) dismiss();
          if (!create(app, false)) return;
          realPage = true;
          userClosed = false;
          try {
            web.loadUrl(url);
          } catch (Throwable ignored) {
          }
        });
  }

  private static void pollGuest() {
    Context ctx = watchCtx;
    if (ctx == null) return;
    SharedPreferences p = prefs(ctx);
    if (!p.getBoolean("floatwin_enabled", true)) return;
    if (!com.deepseekharness.app.OverlayController.permitted(ctx)) return;
    File state = guestFile(ctx, GUEST_STATE);
    if (state == null || !state.isFile()) return;
    long stamp = state.lastModified() * 1000L + Math.min(state.length(), 999);
    if (stamp == stateStamp) return;
    String json = readText(state);
    if (json == null || json.isEmpty()) return;
    if (json.contains("\"clear\"") && json.contains("true")) {
      stateStamp = stamp;
      dismiss();
      return;
    }
    stateStamp = stamp;
    if (web == null) return;  // 只有窗口已经打开时才更新；不再自动弹
    feed(ctx, json);
  }

  /** 把插件写来的整份状态交给页面（窗口没开就开）。 */
  private static void feed(Context ctx, String json) {
    if (realPage) return;
    if (web == null) {
      show(ctx, json, null);
      return;
    }
    pendingPayload = json;
    update(json);
  }

  /** 写入一条要发给 agent 的消息，插件轮询该文件并投递到会话。 */
  private static void writeInbox(Context ctx, String session, String text, String image) {
    try {
      File inbox = guestFile(ctx, GUEST_INBOX);
      if (inbox == null) return;
      JSONObject message =
          new JSONObject()
              .put("seq", System.currentTimeMillis())
              .put("session", session == null ? "" : session)
              .put("text", text == null ? "" : text)
              .put("image", image == null ? "" : image);
      File dir = inbox.getParentFile();
      if (dir != null) dir.mkdirs();
      File tmp = new File(inbox.getPath() + ".tmp");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
        out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
      }
      if (!tmp.renameTo(inbox)) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(inbox)) {
          out.write(message.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
    } catch (Throwable ignored) {
    }
  }

  /** rootfs 里的 /root/.dsh/<name>；环境没起来时返回 null。 */
  private static File guestFile(Context ctx, String name) {
    try {
      com.deepseekharness.app.core.HarnessController hc =
          com.deepseekharness.app.core.HarnessController.get(ctx);
      if (hc == null || hc.getProot() == null) return null;
      File rootfs = hc.getProot().getRootfsDir();
      if (rootfs == null) return null;
      return new File(new File(new File(rootfs, "root"), ".dsh"), name);
    } catch (Throwable error) {
      return null;
    }
  }

  private static String readText(File file) {
    try (java.io.FileInputStream in = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
        if (out.size() > 262144) break;
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (Throwable error) {
      return null;
    }
  }

  /** 窗口当前是否开着（入口打开的那扇）。 */
  public static boolean isOpen() {
    return web != null;
  }

  public static void hide(Context context) {
    main.post(FloatWindow::dismiss);
  }

  private static void showOnMain(Context app) {
    if (realPage) return;
    if (web == null && !create(app, true)) return;
    pushPayload();
  }

  private static boolean create(Context app, boolean loadCustomPage) {
    wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    if (wm == null) return false;
    appCtx = app;

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
    view.setWebChromeClient(
        new android.webkit.WebChromeClient() {
          @Override
          public void onPermissionRequest(final android.webkit.PermissionRequest request) {
            try {
              for (String resource : request.getResources()) {
                if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                  request.grant(new String[] {resource});
                  return;
                }
              }
            } catch (Throwable ignored) {
            }
            try {
              request.deny();
            } catch (Throwable ignored) {
            }
          }

          @Override
          public boolean onShowFileChooser(
              WebView view,
              android.webkit.ValueCallback<android.net.Uri[]> callback,
              FileChooserParams params) {
            pendingFileChooser = callback;
            try {
              android.content.Intent intent =
                  new android.content.Intent(app, FloatPickerActivity.class);
              intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
              app.startActivity(intent);
            } catch (Throwable error) {
              deliverPickedFile(null);
            }
            return true;
          }
        });

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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    // 顶部拖动带：覆盖标题区，随窗口一起移动，手势稳定。
    View strip = transparent(app);
    FrameLayout.LayoutParams stripParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, HEAD_DP));
    stripParams.gravity = Gravity.TOP;
    stripParams.rightMargin = dp(app, 72);
    frame.addView(strip, stripParams);
    attachMove(strip, frame);

    // 悬浮窗自己的按钮：右下角缩放由触摸层负责，这里只放「关闭 / 回到应用」。
    FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(app, 26), dp(app, 26));
    closeParams.gravity = Gravity.TOP | Gravity.END;
    closeParams.topMargin = dp(app, 4);
    closeParams.rightMargin = dp(app, 6);
    frame.addView(overlayButton(app, "×"), closeParams);
    frame.getChildAt(frame.getChildCount() - 1)
        .setOnClickListener(
            v -> {
              userClosed = true;
              dismiss();
            });

    FrameLayout.LayoutParams openParams = new FrameLayout.LayoutParams(dp(app, 26), dp(app, 26));
    openParams.gravity = Gravity.TOP | Gravity.END;
    openParams.topMargin = dp(app, 4);
    openParams.rightMargin = dp(app, 36);
    frame.addView(overlayButton(app, "↗"), openParams);
    frame.getChildAt(frame.getChildCount() - 1)
        .setOnClickListener(v -> openApp());

    // 边缘缩放：右边缘、下边缘与右下角，宽高由原生手势直接改窗口尺寸。
    View rightEdge = transparent(app);
    FrameLayout.LayoutParams rightParams =
        new FrameLayout.LayoutParams(dp(app, EDGE_DP), ViewGroup.LayoutParams.MATCH_PARENT);
    rightParams.gravity = Gravity.END | Gravity.TOP;
    rightParams.bottomMargin = dp(app, EDGE_DP);
    frame.addView(rightEdge, rightParams);
    attachResize(rightEdge, frame, true, false);

    View bottomEdge = transparent(app);
    FrameLayout.LayoutParams bottomParams =
        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(app, EDGE_DP));
    bottomParams.gravity = Gravity.BOTTOM | Gravity.START;
    bottomParams.rightMargin = dp(app, EDGE_DP);
    frame.addView(bottomEdge, bottomParams);
    attachResize(bottomEdge, frame, false, true);

    View corner = transparent(app);
    FrameLayout.LayoutParams cornerParams = new FrameLayout.LayoutParams(dp(app, HANDLE_DP), dp(app, HANDLE_DP));
    cornerParams.gravity = Gravity.BOTTOM | Gravity.END;
    frame.addView(corner, cornerParams);
    attachResize(corner, frame, true, true);

    frame.setAlpha(alphaOf(app));

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
    int screenW = screenW(app);
    int screenH = screenH(app);
    SharedPreferences p = prefs(app);
    int defaultW = Math.min((int) (screenW * 0.94f), 760);
    int defaultH = Math.min(dp(app, 340), (int) (screenH * 0.55f));
    lp.width = Math.max(dp(app, MIN_W_DP), Math.min(screenW, p.getInt(K_W, defaultW)));
    lp.height = Math.max(dp(app, MIN_H_DP), Math.min(screenH, p.getInt(K_H, defaultH)));
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = clamp(p.getInt(K_X, (screenW - lp.width) / 2), 0, Math.max(0, screenW - lp.width));
    lp.y = clamp(p.getInt(K_Y, dp(app, 64)), 0, Math.max(0, screenH - lp.height));

    try {
      wm.addView(frame, lp);
    } catch (Throwable error) {
      return false;
    }
    web = view;
    container = frame;
    params = lp;
    if (loadCustomPage) {
      view.loadDataWithBaseURL(
          BASE_URL, readAsset(app, "floatwin/index.html"), "text/html", "utf-8", null);
    }
    return true;
  }

  /** 半透明度（0.2–1），与设置里的 floatwin_alpha 一致。 */
  private static float alphaOf(Context app) {
    try {
      int alpha = Math.max(20, Math.min(100, prefs(app).getInt("floatwin_alpha", 60)));
      return alpha / 100f;
    } catch (Throwable error) {
      return 0.6f;
    }
  }

  private static android.widget.TextView overlayButton(Context app, String label) {
    android.widget.TextView view = new android.widget.TextView(app);
    view.setText(label);
    view.setTextSize(13);
    view.setTextColor(Color.WHITE);
    view.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0x66000000);
    bg.setCornerRadius(dp(app, 13));
    view.setBackground(bg);
    return view;
  }

  private static void openApp() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx != null) {
      try {
        android.content.Intent intent =
            ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (intent != null) {
          intent.addFlags(
              android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                  | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          ctx.startActivity(intent);
        }
      } catch (Throwable ignored) {
      }
    }
    dismiss();
  }

  private static View transparent(Context app) {
    View v = new View(app);
    v.setBackgroundColor(Color.TRANSPARENT);
    return v;
  }

  /** 顶部触摸带：移动窗口，松手持久化；轻点切换最新一条消息。 */
  private static void attachMove(View strip, FrameLayout frame) {
    strip.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              moving = true;
              dragMoved = false;
              downRawX = event.getRawX();
              downRawY = event.getRawY();
              baseX = params.x;
              baseY = params.y;
              return true;
            case MotionEvent.ACTION_MOVE: {
              if (params == null) return true;
              int dx = Math.round(event.getRawX() - downRawX);
              int dy = Math.round(event.getRawY() - downRawY);
              if (!dragMoved && Math.abs(dx) + Math.abs(dy) > 6) dragMoved = true;
              if (dragMoved) {
                params.x = clamp(baseX + dx, 0, Math.max(0, screenW(appCtx) - params.width));
                params.y = clamp(baseY + dy, 0, Math.max(0, screenH(appCtx) - params.height));
                updateLayout(frame);
              }
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
              moving = false;
              if (dragMoved) persist();
              else if (web != null) {
                web.evaluateJavascript("window.dshFloat&&window.dshFloat.toggle()", null);
              }
              return true;
            }
            default:
              return true;
          }
        });
  }

  /** 缩放：horizontal 改宽，vertical 改高；始终保持窗口在屏幕内且不小于最小尺寸。 */
  private static void attachResize(View handle, FrameLayout frame, boolean horizontal, boolean vertical) {
    handle.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              resizing = true;
              downRawX = event.getRawX();
              downRawY = event.getRawY();
              baseW = params.width;
              baseH = params.height;
              baseX = params.x;
              baseY = params.y;
              return true;
            case MotionEvent.ACTION_MOVE: {
              if (params == null) return true;
              int dx = Math.round(event.getRawX() - downRawX);
              int dy = Math.round(event.getRawY() - downRawY);
              if (horizontal) {
                int maxW = Math.max(dp(appCtx, MIN_W_DP), screenW(appCtx) - baseX);
                params.width = clamp(baseW + dx, dp(appCtx, MIN_W_DP), maxW);
              }
              if (vertical) {
                int maxH = Math.max(dp(appCtx, MIN_H_DP), screenH(appCtx) - baseY);
                params.height = clamp(baseH + dy, dp(appCtx, MIN_H_DP), maxH);
              }
              params.x = clamp(params.x, 0, Math.max(0, screenW(appCtx) - params.width));
              params.y = clamp(params.y, 0, Math.max(0, screenH(appCtx) - params.height));
              updateLayout(frame);
              return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              resizing = false;
              persist();
              return true;
            default:
              return true;
          }
        });
  }

  private static void updateLayout(View frame) {
    if (wm == null || params == null || frame == null) return;
    try {
      wm.updateViewLayout(frame, params);
    } catch (RuntimeException ignored) {
    }
  }

  private static void persist() {
    if (params == null || appCtx == null) return;
    prefs(appCtx)
        .edit()
        .putInt(K_X, params.x)
        .putInt(K_Y, params.y)
        .putInt(K_W, params.width)
        .putInt(K_H, params.height)
        .apply();
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
        case "busy":
        case "expanded":
        case "activity":
          return;
        case "resize":
          if (container != null && wm != null && params != null) {
            updateLayout(container);
          }
          return;
        case "voice": {
          startVoice();
          return;
        }
        case "pickImage": {
          pickImage();
          return;
        }
        case "send": {
          Context ctx = web == null ? null : web.getContext();
          if (ctx != null)
            writeInbox(
                ctx,
                p.optString("session", ""),
                p.optString("text", ""),
                p.optString("image", ""));
          return;
        }
        case "focus": {
          setFocusable(p.optBoolean("value", true));
          return;
        }
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

  /** 页面要输入时把窗口切成可获焦，否则键盘弹不出来；失焦后切回不抢焦点。 */
  private static void setFocusable(boolean on) {
    if (params == null || wm == null || container == null) return;
    if (on) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    try {
      wm.updateViewLayout(container, params);
    } catch (RuntimeException ignored) {
    }
    if (web == null) return;
    if (on) {
      web.requestFocus();
    } else {
      try {
        InputMethodManager imm =
            (InputMethodManager) web.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
      } catch (Throwable ignored) {
      }
    }
  }

  /** 语音：点一下开始录音，再点一下停止并把 WAV 交给容器里的 speechToText 转写。 */
  private static void startVoice() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    if (recording) {
      stopAndTranscribe();
      return;
    }
    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('没有麦克风权限')");
      return;
    }
    try {
      int rate = 16000;
      int min = android.media.AudioRecord.getMinBufferSize(
          rate,
          android.media.AudioFormat.CHANNEL_IN_MONO,
          android.media.AudioFormat.ENCODING_PCM_16BIT);
      recorder =
          new android.media.AudioRecord(
              android.media.MediaRecorder.AudioSource.MIC,
              rate,
              android.media.AudioFormat.CHANNEL_IN_MONO,
              android.media.AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min, rate));
      recorder.startRecording();
      pcm = new java.io.ByteArrayOutputStream();
      recording = true;
      evaluate("window.dshFloat&&window.dshFloat.onVoiceState('listening')");
      Thread thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[3200];
                while (recording) {
                  int read = recorder == null ? -1 : recorder.read(buffer, 0, buffer.length);
                  if (read <= 0) break;
                  pcm.write(buffer, 0, read);
                  if (pcm.size() > 16000 * 2 * 40) break;  // 最长 40 秒
                }
              },
              "float-voice");
      thread.setDaemon(true);
      thread.start();
    } catch (Throwable error) {
      recording = false;
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('录音启动失败')");
    }
  }

  /** 停止录音，把 PCM 包成 WAV 交给插件转写。 */
  private static void stopAndTranscribe() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    recording = false;
    try {
      if (recorder != null) {
        recorder.stop();
        recorder.release();
      }
    } catch (Throwable ignored) {
    }
    recorder = null;
    final byte[] pcmBytes = pcm == null ? new byte[0] : pcm.toByteArray();
    pcm = null;
    if (pcmBytes.length < 3200) {
      evaluate("window.dshFloat&&window.dshFloat.onVoiceError('说话时间太短')");
      return;
    }
    evaluate("window.dshFloat&&window.dshFloat.onVoiceState('transcribing')");
    Thread writer =
        new Thread(
            () -> {
              try {
                java.io.ByteArrayOutputStream wav = new java.io.ByteArrayOutputStream();
                int dataLen = pcmBytes.length;
                int rate = 16000;
                wav.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, 36 + dataLen);
                wav.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                wav.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, 16);
                writeLe16(wav, 1);
                writeLe16(wav, 1);
                writeLe32(wav, rate);
                writeLe32(wav, rate * 2);
                writeLe16(wav, 2);
                writeLe16(wav, 16);
                wav.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeLe32(wav, dataLen);
                wav.write(pcmBytes, 0, dataLen);
                String audio =
                    android.util.Base64.encodeToString(wav.toByteArray(), android.util.Base64.NO_WRAP);
                voiceSeq = System.currentTimeMillis();
                File file = guestFile(ctx, GUEST_VOICE);
                if (file == null || file.getParentFile() == null) return;
                file.getParentFile().mkdirs();
                File tmp = new File(file.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                  out.write(
                      new JSONObject().put("seq", voiceSeq).put("audio", audio).toString()
                          .getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  out.getFD().sync();
                }
                if (!tmp.renameTo(file)) tmp.renameTo(file);
              } catch (Throwable error) {
                main.post(
                    () ->
                        evaluate(
                            "window.dshFloat&&window.dshFloat.onVoiceError('语音发送失败')"));
              }
            },
            "float-voice-upload");
    writer.setDaemon(true);
    writer.start();
  }

  private static void writeLe32(java.io.ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write((value >> 8) & 0xFF);
    out.write((value >> 16) & 0xFF);
    out.write((value >> 24) & 0xFF);
  }

  private static void writeLe16(java.io.ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write((value >> 8) & 0xFF);
  }

  /** 插件转写完成的文本；没有新结果就什么都不做。 */
  private static void pollVoiceResult(Context ctx) {
    File result = guestFile(ctx, GUEST_VOICE_RESULT);
    if (result == null || !result.isFile()) return;
    long stamp = result.lastModified();
    if (stamp == lastVoiceResult) return;
    lastVoiceResult = stamp;
    String json = readText(result);
    if (json == null || json.isEmpty()) return;
    try {
      JSONObject data = new JSONObject(json);
      String text = data.optString("text", "");
      String error = data.optString("error", "");
      if (!error.isEmpty()) {
        evaluate("window.dshFloat&&window.dshFloat.onVoiceError(" + JSONObject.quote(error) + ")");
      } else if (!text.isEmpty()) {
        evaluate("window.dshFloat&&window.dshFloat.onVoice(" + JSONObject.quote(text) + ")");
      }
    } catch (Throwable ignored) {
    }
  }

  /** 选一张图，写进容器后把路径交给页面当附件引用。 */
  private static void pickImage() {
    Context ctx = web == null ? null : web.getContext();
    if (ctx == null) return;
    pendingImage = true;
    try {
      android.content.Intent intent =
          new android.content.Intent(ctx, FloatPickerActivity.class)
              .putExtra(FloatPickerActivity.EXTRA_TYPE, "image/*")
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Throwable error) {
      pendingImage = false;
    }
  }

  static void deliverPickedImage(android.net.Uri uri) {
    pendingImage = false;
    Context ctx = web == null ? null : web.getContext();
    if (uri == null || ctx == null) return;
    try {
      File attach = guestFile(ctx, "float-attach");
      if (attach == null) return;
      if (!attach.isDirectory() && !attach.mkdirs()) return;
      File target = new File(attach, "img-" + System.currentTimeMillis() + ".jpg");
      try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
          java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
        if (in == null) return;
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      String dataUrl = "";
      if (target.length() > 0 && target.length() <= 1800000L) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(target)) {
          byte[] bytes = new byte[(int) target.length()];
          int read = 0;
          while (read < bytes.length) {
            int step = in.read(bytes, read, bytes.length - read);
            if (step <= 0) break;
            read += step;
          }
          dataUrl =
              "data:image/jpeg;base64,"
                  + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
        }
      }
      evaluate(
          "window.dshFloat&&window.dshFloat.onImage("
              + JSONObject.quote("/root/.dsh/float-attach/" + target.getName())
              + ","
              + JSONObject.quote(dataUrl)
              + ")");
    } catch (Throwable ignored) {
    }
  }

  private static void evaluate(String js) {
    try {
      if (web != null) web.evaluateJavascript(js, null);
    } catch (Throwable ignored) {
    }
  }

  private static void dismiss() {
    recording = false;
    try {
      if (recorder != null) {
        recorder.stop();
        recorder.release();
      }
    } catch (Throwable ignored) {
    }
    recorder = null;
    if (web != null) userClosed = true;
    realPage = false;
    pendingFileChooser = null;
    moving = false;
    resizing = false;
    dragMoved = false;
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

  /** 文件选择器返回：把 Uri 交回 WebView 的上传回调。 */
  static void deliverPickedFile(android.net.Uri uri) {
    android.webkit.ValueCallback<android.net.Uri[]> callback = pendingFileChooser;
    pendingFileChooser = null;
    if (callback == null) return;
    try {
      callback.onReceiveValue(uri == null ? null : new android.net.Uri[] {uri});
    } catch (Throwable ignored) {
    }
  }

  private static int screenW(Context ctx) {
    return ctx.getResources().getDisplayMetrics().widthPixels;
  }

  private static int screenH(Context ctx) {
    return ctx.getResources().getDisplayMetrics().heightPixels;
  }

  private static int clamp(int value, int min, int max) {
    if (max < min) return min;
    return Math.max(min, Math.min(max, value));
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
