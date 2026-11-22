package app.aitranslator.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * После нажатия на ярлык (и только в течение 20 секунд) ищет в Gemini кнопку «Открыть» у Canvas,
 * нажимает её, затем «Развернуть». Видит только приложение Gemini (см. res/xml/auto_click.xml).
 */
public class AutoClickService extends AccessibilityService {
    private static final long MIN_GAP_AFTER_CLICK_MS = 700;
    /** Если «Открыть» не видно столько времени, Canvas, вероятно, уже открыт — можно сразу «Развернуть» */
    private static final long EXPAND_WITHOUT_OPEN_AFTER_MS = 6000;
    private static final int MAX_DEPTH = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanPosted;
    private long session;          // момент запуска ярлыка, к которому относится текущее состояние
    private boolean openClicked;
    private boolean expanded;
    private long warmupAt;          // когда нажали скрытую кнопку «Подготовить микрофон и камеру»
    private long expandedAt;
    private int warmTaps;
    private boolean splashLiftedForAllow;
    private int allowTaps;
    private long lastAllowTapAt;
    private String warmupMethod = "";
    private String expandResult = "";
    private String openedLabel = "";
    private long lastClickAt;
    private final Set<String> seen = new LinkedHashSet<>();

    private final Runnable scan = () -> {
        scanPosted = false;
        scanNow();
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isArmed()) schedule(120);
    }

    @Override
    public void onInterrupt() {}

    /* ---------- Заставка поверх Gemini, пока идут нажатия ---------- */

    /** Запущенная служба (в том же процессе) — через неё ярлык показывает заставку */
    static AutoClickService instance;
    private View splash;
    private final Runnable splashFailsafe = () -> {
        hideSplash(0);
        turnOffIfAutoManaged(600);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        removeSplashNow();
        return super.onUnbind(intent);
    }

    /**
     * Заставка во весь экран: иконка, название, индикатор загрузки. Касания проходят сквозь неё,
     * поэтому нажатия службы попадают в Gemini. Слой «Специальных возможностей» — отдельного разрешения не нужно
     */
    void showSplash() {
        handler.removeCallbacks(splashFailsafe);
        handler.postDelayed(splashFailsafe, Prefs.ARM_WINDOW_MS + 16_000); // страховка: не висеть вечно
        if (splash != null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1B1C20"));
        int iconSize = Math.round(112 * d);

        // Мягкое синее свечение за логотипом — «дышит» вместе с ним
        View glow = new View(this);
        android.graphics.drawable.GradientDrawable glowShape = new android.graphics.drawable.GradientDrawable();
        glowShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        glowShape.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientRadius(iconSize * 1.1f);
        glowShape.setColors(new int[] { Color.parseColor("#594C8BF5"), Color.parseColor("#004C8BF5") });
        glow.setBackground(glowShape);
        int glowSize = Math.round(iconSize * 2.4f);
        root.addView(glow, new FrameLayout.LayoutParams(glowSize, glowSize, Gravity.CENTER));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher_art);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = 32 * d;
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        icon.setClipToOutline(true);
        icon.setElevation(10 * d);
        root.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        // Появление: проявляется с лёгкой «пружинкой»
        icon.setAlpha(0f);
        icon.setScaleX(0.82f);
        icon.setScaleY(0.82f);
        glow.setAlpha(0f);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                .withEndAction(() -> startBreathing(icon, glow)).start();
        glow.animate().alpha(0.7f).setDuration(600).start();
        splashIcon = icon;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
            splash = root;
        } catch (RuntimeException ignored) {
            // Не получилось показать — работаем без заставки
        }
    }

    /** Плавно убрать заставку через delayMs (дать переводчику дорисоваться под ней) */
    void hideSplash(long delayMs) {
        handler.removeCallbacks(splashFailsafe);
        handler.postDelayed(() -> {
            View v = splash;
            if (v == null) return;
            stopBreathing();
            // Уход: логотип чуть приближается и растворяется вместе с фоном — как открытие приложения
            if (splashIcon != null) splashIcon.animate().scaleX(1.12f).scaleY(1.12f).setDuration(260)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
            v.animate().alpha(0f).setDuration(260).withEndAction(this::removeSplashNow).start();
        }, delayMs);
    }

    private View splashIcon;
    private android.animation.AnimatorSet breathing;

    /** Пока идёт загрузка, логотип медленно «дышит», а свечение за ним мягко пульсирует */
    private void startBreathing(View icon, View glow) {
        if (splash == null) return;
        android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.06f);
        android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.06f);
        android.animation.ObjectAnimator ga = android.animation.ObjectAnimator.ofFloat(glow, View.ALPHA, 0.7f, 1f);
        android.animation.ObjectAnimator gs = android.animation.ObjectAnimator.ofFloat(glow, View.SCALE_X, 1f, 1.12f);
        android.animation.ObjectAnimator gsy = android.animation.ObjectAnimator.ofFloat(glow, View.SCALE_Y, 1f, 1.12f);
        for (android.animation.ObjectAnimator a : new android.animation.ObjectAnimator[] { sx, sy, ga, gs, gsy }) {
            a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        }
        breathing = new android.animation.AnimatorSet();
        breathing.playTogether(sx, sy, ga, gs, gsy);
        breathing.setDuration(1100);
        breathing.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathing.start();
    }

    private void stopBreathing() {
        if (breathing != null) breathing.cancel();
        breathing = null;
    }

    private void removeSplashNow() {
        stopBreathing();
        splashIcon = null;
        View v = splash;
        splash = null;
        if (v == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v); } catch (RuntimeException ignored) {}
    }

    /** Закончить запуск: записать итог и убрать заставку */
    private void finish(String result, long hideDelayMs) {
        Prefs.disarm(this, result);
        session = 0;
        hideSplash(hideDelayMs);
        turnOffIfAutoManaged(hideDelayMs + 600);
    }

    /**
     * Службу включало само приложение (разрешение из ADB) — выключаем её, когда всё сделано:
     * так она не мешает банкам и другим приложениям, которые не любят включённые «Специальные возможности»
     */
    private void turnOffIfAutoManaged(long delayMs) {
        if (!Prefs.get(this).getBoolean(Prefs.AUTO_MANAGED, false) || !Prefs.canToggleService(this)) return;
        handler.postDelayed(() -> {
            if (isArmed()) return; // за это время снова нажали на иконку — пусть работает
            removeSplashNow();
            try { disableSelf(); } catch (RuntimeException ignored) {}
        }, delayMs);
    }

    private boolean isArmed() {
        return System.currentTimeMillis() < Prefs.get(this).getLong(Prefs.ARMED_UNTIL, 0);
    }

    private void schedule(long delayMs) {
        if (scanPosted) return;
        scanPosted = true;
        handler.postDelayed(scan, delayMs);
    }

    private void scanNow() {
        SharedPreferences p = Prefs.get(this);
        long now = System.currentTimeMillis();
        long until = p.getLong(Prefs.ARMED_UNTIL, 0);
        long armedAt = p.getLong(Prefs.ARMED_AT, 0);
        if (now >= until) {
            if (session != 0 && session == armedAt) {
                session = 0;
                if (p.getString(Prefs.LAST_RESULT, "").startsWith("Запущено")) {
                    if (expanded) {
                        finish(expandResult + (warmupAt > 0
                                ? (allowTaps > 0 ? "; «Разрешить» нажато " + allowTaps + " раз(а), но окно не закрылось" : "; окно разрешений не появилось (" + warmupMethod + ")")
                                : "; кнопка подготовки микрофона не найдена и касание не удалось — обновите код переводчика в Canvas"), 0);
                        return;
                    }
                    finish(openClicked
                            ? "Нажато «" + openedLabel + "», но кнопка «Развернуть» не найдена — впишите её подпись из списка ниже"
                            : "Кнопки не найдены за 20 секунд — сверьте подписи со списком ниже", 0);
                }
            }
            hideSplash(0);
            return;
        }
        if (session != armedAt) { // новый запуск ярлыка
            session = armedAt;
            openClicked = false;
            expanded = false;
            warmupAt = 0;
            warmTaps = 0;
            splashLiftedForAllow = false;
            allowTaps = 0;
            lastAllowTapAt = 0;
            warmupMethod = "";
            lastClickAt = 0;
            seen.clear();
        }
        if (now - lastClickAt < MIN_GAP_AFTER_CLICK_MS) {
            schedule(MIN_GAP_AFTER_CLICK_MS - (now - lastClickAt) + 50);
            return;
        }

        // Развернули — дальше доступ к микрофону и камере: скрытая кнопка переводчика, затем «Разрешить» в окне Gemini
        if (expanded) {
            handlePermissions(now);
            return;
        }

        List<String> openLabels = Prefs.labels(this, Prefs.OPEN_LABELS, Prefs.DEFAULT_OPEN);
        List<String> expandLabels = Prefs.labels(this, Prefs.EXPAND_LABELS, Prefs.DEFAULT_EXPAND);
        Found found = new Found();
        for (AccessibilityNodeInfo root : roots()) walk(root, 0, openLabels, expandLabels, found);
        p.edit().putString(Prefs.SEEN, String.join(" · ", seen)).apply();

        // Переводчик уже развёрнут во весь экран (запуск ярлыка повторно): кнопок нет — сразу к разрешениям
        // (через 1,5 с после запуска — чтобы верхняя панель Canvas, если она есть, успела появиться)
        if (!openClicked && !found.canvasScreen && found.translatorVisible && now - armedAt > 1500) {
            expandResult = "Переводчик уже был открыт";
            if (!p.getBoolean(Prefs.AUTO_PERMS, true)) {
                finish(expandResult, 300);
                return;
            }
            expanded = true;
            expandedAt = now;
            Prefs.extend(this, 15_000, expandResult + "; прошу доступ к микрофону и камере…");
            schedule(300);
            return;
        }

        // Уже на экране Canvas (вкладки «Код» / «Предварительный просмотр») — «Открыть» не нужно, сразу разворачиваем
        boolean mayExpand = openClicked || found.canvasScreen || (found.open == null && now - armedAt > EXPAND_WITHOUT_OPEN_AFTER_MS);
        // Подписи у значка нет — на экране Canvas ищем кнопку по месту (через секунду после «Открыть», когда экран встал)
        if (found.expand == null && mayExpand && found.canvasScreen && now - lastClickAt > 1000) {
            found.expand = expandByPosition(found.topButtons);
        }
        if (found.expand != null && mayExpand) {
            if (click(found.expand)) {
                String expLabel = labelOf(found.expand);
                if (expLabel.isEmpty()) expLabel = "значок справа вверху";
                expandResult = (openClicked ? "Нажато «" + openedLabel + "», затем «" : "Нажато «") + expLabel
                        + "» за " + (now - armedAt) / 100 / 10.0 + " с";
                if (!p.getBoolean(Prefs.AUTO_PERMS, true)) {
                    finish(expandResult, 700); // дать Canvas развернуться под заставкой
                    return;
                }
                expanded = true;
                expandedAt = now;
                lastClickAt = now;
                Prefs.extend(this, 15_000, expandResult + "; прошу доступ к микрофону и камере…");
                schedule(800);
                return;
            }
        } else if (found.open != null && !openClicked) {
            if (click(found.open)) {
                openClicked = true;
                openedLabel = labelOf(found.open);
                lastClickAt = now;
                p.edit().putString(Prefs.LAST_RESULT, "Запущено: нажато «" + openedLabel + "», жду «Развернуть»…").apply();
            }
        }
        // События приходят не всегда — проверяем ещё раз, пока не истекло время
        schedule(400);
    }

    /**
     * После разворачивания: коснуться «пальцем» скрытой полоски переводчика (служебное нажатие Gemini не принимает),
     * дождаться окна «Требуется разрешение» и коснуться «Разрешить». Успех — только когда окно закрылось
     */
    private void handlePermissions(long now) {
        List<AccessibilityNodeInfo> roots = roots();

        List<String> allow = Prefs.labels(this, Prefs.ALLOW_LABELS, Prefs.DEFAULT_ALLOW);
        Found f = new Found();
        for (AccessibilityNodeInfo r : roots) collectDialog(r, 0, allow, f);
        boolean dialog = f.permissionText && f.allow != null;

        if (allowTaps > 0 && !dialog) {
            // После «Разрешить» переводчик перезагружается — пусть сделает это под заставкой
            finish(expandResult + "; доступ к микрофону и камере разрешён"
                    + (splashLiftedForAllow ? " (заставку пришлось убрать на время нажатия)" : ""), 1500);
            return;
        }
        if (dialog) {
            if (allowTaps < 3 && now - lastAllowTapAt > 1200) {
                // Окно разрешений может не принимать касания, пока поверх что-то нарисовано (защита Android).
                // Первое касание — сквозь заставку; не сработало — убираем её на мгновение, нажимаем и возвращаем
                if (allowTaps >= 1 && splash != null) {
                    splashLiftedForAllow = true;
                    removeSplashNow();
                    AccessibilityNodeInfo target = f.allow;
                    handler.postDelayed(() -> {
                        tapCenter(target);
                        handler.postDelayed(this::showSplash, 500);
                    }, 120);
                    allowTaps++;
                    lastAllowTapAt = now;
                } else if (tapCenter(f.allow)) {
                    allowTaps++;
                    lastAllowTapAt = now;
                }
            }
            schedule(400);
            return;
        }

        // Касались полоски дважды, а окна разрешений так и нет — значит, доступ уже есть
        if (warmTaps >= 2 && now - warmupAt > 2500) {
            finish(expandResult + "; окно разрешений не появилось — похоже, доступ уже есть", 200);
            return;
        }
        // Окна пока нет: касаемся полоски; если окно не появилось — ещё раз через 3 секунды
        if (warmTaps < 2 && (warmupAt == 0 || now - warmupAt > 3000)) {
            AccessibilityNodeInfo warm = null;
            for (AccessibilityNodeInfo r : roots) if ((warm = findLabel(r, Prefs.WARMUP_LABEL, 0)) != null) break;
            if (tapWarmupStrip(warm, roots)) {
                warmupAt = now;
                warmTaps++;
                warmupMethod = warm != null ? "касание по кнопке" : "касание у левого края";
            }
        }
        schedule(400);
    }

    /** Касание «пальцем» по центру узла */
    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null);
    }

    /** Коснуться прозрачной полоски переводчика: по её месту, а если её не видно — у левого края области Canvas */
    private boolean tapWarmupStrip(AccessibilityNodeInfo warm, List<AccessibilityNodeInfo> roots) {
        Rect r = new Rect();
        if (warm != null) warm.getBoundsInScreen(r);
        float x, y;
        if (!r.isEmpty() && r.height() > 10) {
            x = r.exactCenterX();
            y = r.exactCenterY();
        } else {
            Rect web = null;
            for (AccessibilityNodeInfo root : roots) {
                Rect found = largestWebView(root, 0);
                if (found != null && (web == null || found.height() * found.width() > web.height() * web.width())) web = found;
            }
            if (web == null) return false;
            float density = getResources().getDisplayMetrics().density;
            x = web.left + density * 1.5f;
            y = web.exactCenterY();
        }
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null);
    }

    private Rect largestWebView(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > MAX_DEPTH) return null;
        Rect best = null;
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().contains("WebView")) {
            best = new Rect();
            n.getBoundsInScreen(best);
            return best; // самый внешний WebView — дальше не спускаемся
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            Rect c = largestWebView(n.getChild(i), depth + 1);
            if (c != null && (best == null || c.width() * c.height() > best.width() * best.height())) best = c;
        }
        return best;
    }

    /** Найти узел с подписью — даже скрытый (кнопка переводчика размером 1 px) */
    private AccessibilityNodeInfo findLabel(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > MAX_DEPTH) return null;
        if (label.equals(labelOf(n))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findLabel(n.getChild(i), label, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private void collectDialog(AccessibilityNodeInfo n, int depth, List<String> allow, Found f) {
        if (n == null || depth > MAX_DEPTH) return;
        if (n.isVisibleToUser()) {
            String label = labelOf(n);
            if (label.contains("разрешени") || label.contains("permission")) f.permissionText = true;
            if (f.allow == null && allow.contains(label)) f.allow = n;
        }
        for (int i = 0; i < n.getChildCount(); i++) collectDialog(n.getChild(i), depth + 1, allow, f);
    }

    private static final class Found {
        boolean permissionText;
        AccessibilityNodeInfo allow;
        AccessibilityNodeInfo open;    // последняя подходящая — у самого свежего Canvas в чате
        AccessibilityNodeInfo expand;  // первая подходящая
        boolean canvasScreen;          // вверху вкладки «Код» / «Предварительный просмотр» — открыт экран Canvas
        boolean translatorVisible;     // на экране сам переводчик (его скрытая кнопка «Подготовить микрофон и камеру»)
        final List<AccessibilityNodeInfo> topButtons = new ArrayList<>();
    }

    /** Вкладки вверху экрана Canvas — по ним понятно, что это Canvas, а не обычный чат */
    private static final java.util.Set<String> CANVAS_TABS = new java.util.HashSet<>(java.util.Arrays.asList(
            "код", "code", "предварительный просмотр", "предпросмотр", "preview"));

    /**
     * Кнопка «во весь экран» на экране Canvas — значок без подписи. Справа вверху: […][⤢][⋮].
     * Берём кнопку, стоящую сразу слева от самой правой («⋮»)
     */
    private AccessibilityNodeInfo expandByPosition(List<AccessibilityNodeInfo> top) {
        int w = getResources().getDisplayMetrics().widthPixels;
        List<AccessibilityNodeInfo> right = new ArrayList<>();
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : top) {
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.width() < w * 0.3f && r.centerX() > w * 0.6f) right.add(n);
        }
        if (right.size() < 2) return null;
        right.sort((a, b) -> {
            Rect ra = new Rect(), rb = new Rect();
            a.getBoundsInScreen(ra);
            b.getBoundsInScreen(rb);
            return Integer.compare(rb.centerX(), ra.centerX()); // справа налево
        });
        return right.get(1);
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && isGemini(r.getPackageName())) out.add(r);
            }
        } catch (RuntimeException ignored) {}
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && isGemini(r.getPackageName())) out.add(r);
        }
        return out;
    }

    private static boolean isGemini(CharSequence pkg) {
        if (pkg == null) return false;
        for (String g : Prefs.GEMINI_PACKAGES) if (g.contentEquals(pkg)) return true;
        return false;
    }

    private void walk(AccessibilityNodeInfo n, int depth, List<String> openLabels, List<String> expandLabels, Found found) {
        if (n == null || depth > MAX_DEPTH) return;
        // Скрытая кнопка переводчика прозрачная — Android может считать её невидимой, поэтому проверяем без этого условия
        if (Prefs.WARMUP_LABEL.equals(labelOf(n))) found.translatorVisible = true;
        if (n.isVisibleToUser()) {
            boolean top = inTopBar(n);
            String label = labelOf(n);
            if (!label.isEmpty()) {
                if (label.length() <= 40 && (n.isClickable() || hasClickableParent(n)) && seen.size() < 60) seen.add(label);
                // «Открыть» Canvas — только в ленте чата: вверху кнопка меню «Открыть панель навигации»
                if (!top && matches(label, openLabels)) found.open = n;
                // «Развернуть» — в верхней полосе экрана Canvas
                if (found.expand == null && matches(label, expandLabels)) found.expand = n;
                if (top && CANVAS_TABS.contains(label)) found.canvasScreen = true;
            }
            if (top && n.isClickable()) found.topButtons.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, openLabels, expandLabels, found);
    }

    /** Верхняя полоса экрана (меню, заголовок, «новый чат») — там нужных кнопок нет, ничего не нажимаем */
    private boolean inTopBar(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        return r.bottom > 0 && r.bottom < screenH * 0.12f;
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        String t = Prefs.normalize(n.getText());
        if (!t.isEmpty()) return t;
        return Prefs.normalize(n.getContentDescription());
    }

    private static boolean hasClickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 3; i++, p = p.getParent()) if (p.isClickable()) return true;
        return false;
    }

    /**
     * Только точное совпадение подписи. Раньше подходило и начало подписи — и служба нажимала кнопку меню
     * «Открыть панель навигации» вместо «Открыть» у Canvas
     */
    private static boolean matches(String label, List<String> wanted) {
        if (NAV_WORDS.matcher(label).find()) return false;
        for (String w : wanted) if (label.equals(w)) return true;
        return false;
    }

    private static final java.util.regex.Pattern NAV_WORDS =
            java.util.regex.Pattern.compile("навигац|меню|navigation|menu|drawer|боков");

    private boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo c = node; c != null; c = c.getParent()) {
            if (c.isClickable()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
        }
        // Не нажалось как кнопка — касаемся середины, как пальцем
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription tap = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return dispatchGesture(tap, null, null);
    }
}
