package com.deepseekharness.app.floatwin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 悬浮窗里 WebView 选文件用的透明壳 Activity。
 *
 * <p>应用级 WebView（悬浮窗）没有 Activity 上下文，拿不到 startActivityForResult 的回执；
 * 这里只负责弹出系统文件选择器，把结果交回 {@link FloatWindow#deliverPickedFile(Uri)}。
 * 选择器取消或失败也必须回一个 null，否则网页那边的上传永远等不到结果。
 */
public final class FloatPickerActivity extends Activity {
  private static final int REQUEST = 41;
  static final String EXTRA_TYPE = "picker_type";
  static final String EXTRA_FILE_MODE = "picker_file_mode";

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) return;
    try {
      Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      String type = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TYPE);
      intent.setType(type == null || type.isEmpty() ? "*/*" : type);
      startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST);
    } catch (RuntimeException error) {
      FloatWindow.deliverPickedFile(null);
      finish();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
    boolean fileMode =
        getIntent() != null && getIntent().getBooleanExtra(EXTRA_FILE_MODE, false);
    if (uri != null && fileMode) {
      FloatWindow.deliverPickedFileAttachment(uri);
    } else if (uri != null && getIntent() != null && getIntent().hasExtra(EXTRA_TYPE)) {
      FloatWindow.deliverPickedImage(uri);
    } else {
      FloatWindow.deliverPickedFile(uri);
    }
    finish();
  }
}
