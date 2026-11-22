package app.aitranslator.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Настройки и общее состояние запуска (ярлык ↔ служба) */
final class Prefs {
    static final String URL = "url";
    static final String OPEN_LABELS = "open_labels";
    static final String EXPAND_LABELS = "expand_labels";
    static final String ARMED_AT = "armed_at";
    static final String ARMED_UNTIL = "armed_until";
    static final String LAST_RESULT = "last_result";
    static final String SEEN = "seen";
    static final String AUTO_PERMS = "auto_perms";
    static final String ALLOW_LABELS = "allow_labels";

    static final String WARMUP_LABEL = "подготовить микрофон и камеру";
    static final String DEFAULT_ALLOW = "Разрешить, Allow";

    static final String DEFAULT_OPEN = "Открыть, Open";
    static final String DEFAULT_EXPAND = "Развернуть, Во весь экран, Полноэкранный режим, Открыть в полноэкранном режиме, На весь экран, Expand, Full screen, Fullscreen, Enter full screen, Enter fullscreen, Maximize";

    /** Сколько служба ждёт кнопки после нажатия на ярлык */
    static final long ARM_WINDOW_MS = 20_000;

    static final String[] GEMINI_PACKAGES = { "com.google.android.apps.bard", "com.google.android.googlequicksearchbox" };

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    static String url(Context c) { return get(c).getString(URL, "").trim(); }

    static List<String> labels(Context c, String key, String def) {
        List<String> out = new ArrayList<>();
        for (String s : get(c).getString(key, def).split("[,;\\n]")) {
            String n = normalize(s);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    static String normalize(CharSequence s) {
        if (s == null) return "";
        return s.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00A0]+", " ").replaceAll("^[\\s.…:]+|[\\s.…:]+$", "");
    }

    /** Ярлык нажат: следующие 20 секунд служба ищет кнопки */
    static void arm(Context c) {
        long now = System.currentTimeMillis();
        get(c).edit().putLong(ARMED_AT, now).putLong(ARMED_UNTIL, now + ARM_WINDOW_MS)
                .putString(LAST_RESULT, "Запущено, жду кнопки…").putString(SEEN, "").apply();
    }

    /** Продлить ожидание: после «Развернуть» ещё нужно дождаться окна разрешений */
    static void extend(Context c, long ms, String result) {
        get(c).edit().putLong(ARMED_UNTIL, System.currentTimeMillis() + ms).putString(LAST_RESULT, result).apply();
    }

    static void disarm(Context c, String result) {
        get(c).edit().putLong(ARMED_UNTIL, 0).putString(LAST_RESULT, result).apply();
    }

    static final String AUTO_MANAGED = "auto_managed";
    static final String ADB_COMMAND = "adb shell pm grant app.aitranslator.launcher android.permission.WRITE_SECURE_SETTINGS";

    /** Выдано ли через ADB разрешение менять системные настройки (включать/выключать свою службу) */
    static boolean canToggleService(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Включить свою службу «Специальных возможностей» (только с разрешением из ADB) */
    static boolean enableService(Context c) {
        if (!canToggleService(c)) return false;
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, AutoClickService.class).flattenToString();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.isEmpty()) list = me;
            else if (!serviceEnabled(c)) list = list + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean serviceEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(c, AutoClickService.class);
        for (String s : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }
}
