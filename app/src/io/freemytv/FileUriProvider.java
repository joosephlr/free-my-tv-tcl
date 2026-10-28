package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only content provider used to hand USB files to external media players. */
public class FileUriProvider extends ContentProvider {
    static Uri uri(android.content.Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".files")
                .appendPath(file.getAbsolutePath()).build();
    }

    private File file(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.length() < 2) throw new IllegalArgumentException("empty file path");
        return new File(path.substring(1));
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        File file = file(uri);
        if (!file.isFile()) throw new FileNotFoundException(file.getPath());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        String path = uri.getPath();
        if (path != null && path.toLowerCase().endsWith(".apk")) return "application/vnd.android.package-archive";
        return "*/*";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
}
