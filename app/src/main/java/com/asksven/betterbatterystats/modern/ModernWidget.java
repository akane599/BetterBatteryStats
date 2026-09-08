package com.asksven.betterbatterystats.modern;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import com.asksven.betterbatterystats.R;
import com.asksven.betterbatterystats.widgetproviders.AppWidget;
import com.asksven.betterbatterystats.widgetproviders.TextAppWidget;
import java.text.DateFormat;
import java.util.Date;

/** Widgets display a timestamped snapshot; opening BBS is the explicit refresh action. */
public final class ModernWidget {
    private ModernWidget() { }

    public static void updateAll(Context context, BatterySnapshot snapshot) {
        String text = snapshot == null ? context.getString(R.string.modern_widget_empty)
                : "BBS · Last snapshot\nAwake (screen off) " + StatsFormat.duration(snapshot.screenOffAwakeMs())
                + "\nOn battery " + StatsFormat.duration(snapshot.batteryRealtimeMs)
                + "\n" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(snapshot.capturedAtMs));
        context.getSharedPreferences("modern-widget", Context.MODE_PRIVATE).edit().putString("text", text).apply();
        render(context, AppWidget.class);
        render(context, TextAppWidget.class);
    }

    public static void render(Context context, Class<?> provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        if (ids.length == 0) return;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.modern_widget);
        views.setTextViewText(R.id.modern_widget_text, context.getSharedPreferences("modern-widget", Context.MODE_PRIVATE)
                .getString("text", context.getString(R.string.modern_widget_empty)));
        Intent open = new Intent(context, ModernStatsActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.modern_widget_root, pending);
        manager.updateAppWidget(ids, views);
    }
}
