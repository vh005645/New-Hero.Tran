package vn.herotran.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

public class VoiceHelper {
    private static final String TAG = "VoiceHelper";

    private static final String SERVICE_NAME = "vn.herotran.launcher/vn.herotran.launcher.VoiceAccessibilityService";
    private static final String SERVICE_NAME_SHORT = "vn.herotran.launcher/.VoiceAccessibilityService";
    private static final String LEGACY_SERVICE_NAME = "vn.herotran.launcher/vn.herotran.redmilauncher.VoiceAccessibilityService";
    private static final String KATNISS_INTERACTION = "com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService";
    private static final String KATNISS_RECOGNITION = "com.google.android.katniss/.search.serviceapi.KatnissRecognitionService";
    private static volatile boolean sRebinding = false;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public static void ensureVoiceConfigured(final Context context) {
        try {
            final ContentResolver cr = context.getContentResolver();
            final String pkg = context.getPackageName();
            final String serviceName = pkg + "/vn.herotran.launcher.VoiceAccessibilityService";
            final String serviceNameShort = pkg + "/.VoiceAccessibilityService";

            // 1. Kích hoạt accessibility
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }

            String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current != null) {
                if (current.contains(LEGACY_SERVICE_NAME)) {
                    current = current.replace(LEGACY_SERVICE_NAME, serviceName);
                }
                if (current.contains("vn.herotran.launcher/vn.herotran.launcher.VoiceAccessibilityService")) {
                    current = current.replace("vn.herotran.launcher/vn.herotran.launcher.VoiceAccessibilityService", serviceName);
                }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current);
            }

            if (current == null || (!current.contains(serviceName) && !current.contains(serviceNameShort))) {
                String newVal = (current == null || current.trim().isEmpty())
                        ? serviceName
                        : current + ":" + serviceName;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newVal);
            } else if ((!VoiceAccessibilityService.isRunning() || VoiceAccessibilityService.getInstance() == null) && !sRebinding) {
                // Nếu tên service đã có trong danh sách nhưng thực tế chưa chạy (bị hệ thống đưa vào mCrashedServices),
                // BẮT BUỘC phải đặt về rỗng, chờ 150ms để ContentObserver của hệ thống kích hoạt xóa mCrashedServices,
                // sau đó mới ghi lại chuỗi service để Android ép bind lại hoàn toàn!
                sRebinding = true;
                final String targetServices = current;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
                Log.i(TAG, "Cleared enabled_accessibility_services, scheduling re-bind in 150ms...");
                sHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, targetServices);
                            Log.i(TAG, "Re-enabled accessibility services: " + targetServices);
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to restore accessibility service string", t);
                        } finally {
                            sRebinding = false;
                        }
                    }
                }, 150);
            }

            // 2. Cố định Google Katniss làm trợ lý giọng nói mặc định (chỉ ghi khi giá trị bị thay đổi)
            putSecureStringIfChanged(cr, "assistant", KATNISS_INTERACTION);
            putSecureStringIfChanged(cr, "voice_interaction_service", KATNISS_INTERACTION);
            putSecureStringIfChanged(cr, "voice_recognition_service", KATNISS_RECOGNITION);
        } catch (Throwable t) {
            Log.e(TAG, "ensureVoiceConfigured error", t);
        }
    }

    private static void putSecureStringIfChanged(ContentResolver cr, String key, String expected) {
        String cur = Settings.Secure.getString(cr, key);
        if (!expected.equals(cur)) {
            Settings.Secure.putString(cr, key, expected);
        }
    }
}
