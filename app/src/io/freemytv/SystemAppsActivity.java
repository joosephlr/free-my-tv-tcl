package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Lists background processes and their approximate proportional memory use. */
public class SystemAppsActivity extends Activity {
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildView();
        refresh();
    }

    private void buildView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(70, 42, 70, 42);
        page.setBackgroundColor(Color.BLACK);
        TextView title = text(Ui.tr("Apps em segundo plano", "Background apps"), 34, Color.WHITE);
        page.addView(title, new LinearLayout.LayoutParams(-1, 62));
        TextView hint = text(Ui.tr("Memória aproximada por processo", "Approximate memory per process"), 20, 0xFFAAAAAA);
        page.addView(hint, new LinearLayout.LayoutParams(-1, 44));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private void refresh() {
        list.removeAllViews();
        SystemStats.Snapshot snapshot = SystemStats.read(this);
        if (snapshot.processes.isEmpty()) {
            list.addView(text(Ui.tr("Nenhum app em segundo plano foi encontrado.",
                    "No background apps were found."), 24, Color.WHITE));
            return;
        }
        for (SystemStats.Process process : snapshot.processes) {
            TextView row = text(process.label + "\n" + process.packageName + "    "
                    + process.memoryMb + " MB", 23, Color.WHITE);
            row.setPadding(22, 12, 22, 12);
            row.setFocusable(true);
            row.setOnFocusChangeListener((v, focused) -> {
                v.setBackgroundColor(focused ? 0xFFFFFFFF : Color.TRANSPARENT);
                ((TextView) v).setTextColor(focused ? Color.BLACK : Color.WHITE);
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, 74));
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
}
