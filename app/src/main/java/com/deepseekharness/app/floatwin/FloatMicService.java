package com.deepseekharness.app.floatwin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 悬浮窗麦克风前台服务（type=microphone），两种用途共用一个服务：
 * <ul>
 *   <li>rec：悬浮窗录一段语音，录完即停；</li>
 *   <li>wake：常驻语音唤醒（{@link FloatWake}），直到用户关闭。</li>
 * </ul>
 *
 * <p>Android 11+ 对后台应用的麦克风只给全零数据，悬浮窗本身也不算“前台”。必须在
 * {@link FloatMicActivity} 处于前台时启动本服务并 startForeground(MICROPHONE)，之后
 * Activity 关掉、应用回到后台，麦克风权限也随本服务保留。
 */
public final class FloatMicService extends Service {
  static final String EXTRA_MODE = "mode";
  static final String MODE_REC = "rec";
  static final String MODE_WAKE = "wake";
  private static final String ACTION_WAKE_OFF = "com.deepseekharness.app.floatwin.WAKE_OFF";
  private static final int NOTIF_ID = 4107;
  private static final String CHANNEL = "float_mic";

  /** startForeground(MICROPHONE) 成功、服务还在：录音可直接开始，不必再走透明壳。 */
  static volatile boolean foreground;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_WAKE_OFF.equals(intent.getAction())) {
      FloatWindow.setWakeEnabled(getApplicationContext(), false);
      return START_NOT_STICKY;
    }
    String mode = intent == null ? MODE_REC : intent.getStringExtra(EXTRA_MODE);
    boolean wake = MODE_WAKE.equals(mode);
    boolean ok;
    try {
      startForegroundCompat(wake || FloatWake.isRunning());
      ok = true;
    } catch (Throwable error) {
      ok = false;
    }
    foreground = ok;
    if (wake) {
      if (ok) FloatWake.start(this);
      FloatWindow.onWakeService(ok);
      if (!ok) stopSelf();
    } else {
      FloatWindow.onMicService(ok);
      if (!ok) stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void startForegroundCompat(boolean wake) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
      NotificationChannel ch =
          new NotificationChannel(CHANNEL, "悬浮窗语音", NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
    Notification.Builder b =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true);
    if (wake) {
      b.setContentTitle("语音唤醒已开启")
          .setContentText("说「" + FloatWake.words(this) + "」唤起 RCHA 悬浮窗");
      PendingIntent off =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, FloatMicService.class).setAction(ACTION_WAKE_OFF),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      b.addAction(new Notification.Action.Builder(null, "关闭语音唤醒", off).build());
    } else {
      b.setContentTitle("RCHA 正在听").setContentText("悬浮窗语音输入，说完会自动结束");
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    } else {
      startForeground(NOTIF_ID, b.build());
    }
  }

  /** 一段录音结束：唤醒没开就整个停掉服务。 */
  static void recDone(Context ctx) {
    if (FloatWake.isRunning()) return;
    stop(ctx);
  }

  static void stop(Context ctx) {
    foreground = false;
    try {
      ctx.stopService(new Intent(ctx, FloatMicService.class));
    } catch (Throwable ignored) {
    }
  }

  @Override
  public void onDestroy() {
    foreground = false;
    FloatWake.stop();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
