package vn.herotran.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

public class LocaleHelper {
    private static final String TAG = "LocaleHelper";

    public static void enforceEnglish(Context context) {
        try {
            Locale current = Locale.getDefault();
            if (current != null && "en".equalsIgnoreCase(current.getLanguage())) {
                Log.d(TAG, "Current locale is already English: " + current);
                return;
            }
            Log.d(TAG, "Enforcing English locale (current is " + current + ")...");

            Locale target = Locale.US;
            Class<?> amnClz = Class.forName("android.app.ActivityManagerNative");
            Method getDefaultMethod = amnClz.getMethod("getDefault");
            Object am = getDefaultMethod.invoke(null);

            Method getConfigurationMethod = am.getClass().getMethod("getConfiguration");
            Configuration config = (Configuration) getConfigurationMethod.invoke(am);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                LocaleList locales = new LocaleList(target);
                LocaleList.setDefault(locales);
                config.setLocales(locales);
            }
            config.locale = target;

            try {
                Field f = config.getClass().getField("userSetLocale");
                f.setBoolean(config, true);
            } catch (Throwable ignored) {
            }

            // Standard Xiaomi TV / Android TV method
            try {
                Method updateConfigurationMethod = am.getClass().getMethod("updateConfiguration", Configuration.class);
                updateConfigurationMethod.invoke(am, config);
                Log.i(TAG, "updateConfiguration succeeded");
            } catch (Throwable t) {
                Log.w(TAG, "updateConfiguration error", t);
            }

            // Also try updatePersistentConfiguration if allowed
            try {
                Method updatePersistent = am.getClass().getMethod("updatePersistentConfiguration", Configuration.class);
                updatePersistent.invoke(am, config);
                Log.i(TAG, "updatePersistentConfiguration succeeded");
            } catch (Throwable ignored) {
            }

            Log.i(TAG, "Successfully enforced English locale!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to enforce English locale", t);
        }
    }
}
