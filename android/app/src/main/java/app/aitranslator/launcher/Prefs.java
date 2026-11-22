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

    static final String DEFAULT_OPEN = "Открыть, Open";
    static final String DEFAULT_EXPAND = "Развернуть, Во весь экран, Полноэкранный режим, Expand, Full screen, Fullscreen, Maximize";

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

    static void disarm(Context c, String result) {
        get(c).edit().putLong(ARMED_UNTIL, 0).putString(LAST_RESULT, result).apply();
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
