package com.deepseekharness.app.floatwin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 悬浮窗录音期间的 microphone 前台服务。
 *
 * <p>Android 11+ 对后台应用的麦克风只给全零数据；悬浮窗本身不算“前台”。必须在
 * {@link FloatMicActivity} 处于前台时启动本服务并 startForeground(MICROPHONE)，
 * 之后 Activity 关掉、应用回到后台，麦克风权限也随本服务保留。录音结束即停止。
 */
public final class FloatMicService extends Service {
  private static final int NOTIF_ID = 4107;
  private static final String CHANNEL = "float_mic";

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    boolean ok = false;
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
        NotificationChannel ch =
            new NotificationChannel(CHANNEL, "悬浮窗语音", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
      }
      Notification n =
          new Notification.Builder(this, CHANNEL)
              .setSmallIcon(android.R.drawable.ic_btn_speak_now)
              .setContentTitle("RCHA 正在听")
              .setContentText("悬浮窗语音输入，说完会自动结束")
              .setOngoing(true)
              .build();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      } else {
        startForeground(NOTIF_ID, n);
      }
      ok = true;
    } catch (Throwable error) {
      ok = false;
    }
    FloatWindow.onMicService(ok);
    if (!ok) stopSelf();
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
