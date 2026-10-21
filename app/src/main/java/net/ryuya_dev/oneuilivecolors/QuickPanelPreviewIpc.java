package net.ryuya_dev.oneuilivecolors;

/** Private Binder protocol. Only the module app may request a surface from SystemUI. */
final class QuickPanelPreviewIpc {
  static final String DESCRIPTOR = "net.ryuya_dev.oneuilivecolors.quickpanel.preview";
  static final int CREATE = android.os.IBinder.FIRST_CALL_TRANSACTION;
  static final int RELEASE = CREATE + 1;
  static final String REGISTER = "qs_preview_register", BACKEND = "qs_preview_backend";
  static final int MAX_PIXELS = 4096;
}
