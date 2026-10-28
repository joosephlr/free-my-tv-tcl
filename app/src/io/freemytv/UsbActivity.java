package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A small read-only browser for mounted USB volumes. */
public class UsbActivity extends Activity {
    private static final int READ_FILES = 42;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView pathView;
    private File current;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildView();
        if (needsPermission()) requestPermissions(readPermissions(), READ_FILES);
        else if (!broadStorageAccess()) requestBroadStorageAccess();
        else showRoots();
    }

    private boolean broadStorageAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void requestBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !broadStorageAccess()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception e) {
                AppLog.w("couldn't open storage access settings: " + e.getMessage());
            }
        }
        showRoots();
    }

    private void buildView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(64, 34, 64, 34);
        page.setBackgroundColor(Color.BLACK);

        TextView title = text(Ui.tr("Pendrive", "USB drive"), 34, Color.WHITE);
        page.addView(title, new LinearLayout.LayoutParams(-1, 58));
        pathView = text("", 20, 0xFFAAAAAA);
        page.addView(pathView, new LinearLayout.LayoutParams(-1, 48));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private boolean needsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    private String[] readPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] {Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO};
        }
        return new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!broadStorageAccess()) requestBroadStorageAccess();
        else showRoots();
    }

    private void showRoots() {
        current = null;
        pathView.setText(Ui.tr("Volumes disponíveis", "Available volumes"));
        List<File> roots = new ArrayList<>();
        StorageManager storageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (storageManager != null) {
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                if (!volume.isRemovable() || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                File root = volume.getDirectory();
                if (root != null && root.isDirectory()) roots.add(root);
            }
        }
        File storageRoot = new File("/storage");
        if (roots.isEmpty()) {
            File[] children = storageRoot.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !"emulated".equals(child.getName()) && !"self".equals(child.getName())) {
                        roots.add(child);
                    }
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            if (!seen.add(roots.get(i).getAbsolutePath())) roots.remove(i);
        }
        if (roots.isEmpty()) {
            addMessage(Ui.tr("Conecte um pendrive e tente novamente.", "Connect a USB drive and try again."));
            return;
        }
        Collections.sort(roots, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File root : roots) addEntry(root, root.getName(), true);
        focusFirst();
    }

    private void showDirectory(File directory) {
        current = directory;
        pathView.setText(directory.getPath());
        list.removeAllViews();
        addEntry(directory.getParentFile() == null || directory.getParentFile().getPath().equals("/storage")
                        ? null : directory.getParentFile(),
                Ui.tr("..  Voltar", "..  Back"), true);
        File[] files = directory.listFiles();
        if (files == null) {
            addMessage(Ui.tr("Não foi possível ler esta pasta.", "This folder could not be read."));
            return;
        }
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? 1 : -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File file : files) if (file.canRead()) addEntry(file, file.getName(), file.isDirectory());
        focusFirst();
    }

    private void addEntry(File file, String label, boolean directory) {
        TextView entry = text((directory ? "▸  " : "    ") + label, 25, Color.WHITE);
        entry.setFocusable(true);
        entry.setFocusableInTouchMode(true);
        entry.setPadding(22, 0, 22, 0);
        entry.setBackgroundColor(0xFF151515);
        entry.setOnFocusChangeListener((v, focused) -> {
            TextView focusedEntry = (TextView) v;
            focusedEntry.setBackgroundColor(focused ? 0xFFE5E5EA : 0xFF151515);
            focusedEntry.setTextColor(focused ? Color.BLACK : Color.WHITE);
            if (focused) scroll.post(() -> scroll.smoothScrollTo(0,
                    Math.max(0, focusedEntry.getTop() - scroll.getHeight() / 3)));
        });
        entry.setOnClickListener(v -> {
            if (file == null) showRoots();
            else if (directory) showDirectory(file);
            else openFile(file);
        });
        list.addView(entry, new LinearLayout.LayoutParams(-1, 58));
    }

    private void focusFirst() {
        if (list.getChildCount() == 0) return;
        list.getChildAt(0).requestFocus();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    @Override public void onBackPressed() {
        if (current != null) {
            File parent = current.getParentFile();
            if (parent == null || "/storage".equals(parent.getPath())) showRoots();
            else showDirectory(parent);
        } else {
            super.onBackPressed();
        }
    }

    private void addMessage(String message) {
        list.removeAllViews();
        list.addView(text(message, 24, 0xFFCCCCCC), new LinearLayout.LayoutParams(-1, 70));
    }

    private void openFile(File file) {
        Uri uri = FileUriProvider.uri(this, file);
        boolean apk = file.getName().toLowerCase().endsWith(".apk");
        Intent intent = new Intent(apk ? Intent.ACTION_INSTALL_PACKAGE : Intent.ACTION_VIEW)
                .setDataAndType(uri, mime(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            AppLog.w("couldn't open USB file " + file.getName() + ": " + e.getMessage());
            addMessage(Ui.tr("Não há app compatível para este arquivo.", "No compatible app for this file."));
        }
    }

    private static String mime(File file) {
        String n = file.getName().toLowerCase();
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")) return "video/*";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac")) return "audio/*";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) return "image/*";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "*/*";
    }
}
