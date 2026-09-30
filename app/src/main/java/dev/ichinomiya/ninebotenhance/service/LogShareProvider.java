package dev.ichinomiya.ninebotenhance.service;

import dev.ichinomiya.ninebotenhance.core.LogArchive;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Not exported. Android grants only the selected log URI to the sharesheet recipient. */
public final class LogShareProvider extends ContentProvider {
    public static final String AUTHORITY = dev.ichinomiya.ninebotenhance.ipc.Flavor.AUTHORITY + ".logs";
    private static LogArchive archive;
    static synchronized LogArchive archive(Context context) {
        if (archive == null) {
            Context app = context.getApplicationContext();
            archive = new LogArchive(new File(app.getCacheDir(), "shared-logs"), file -> {
                try { app.revokeUriPermission(uri(file), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (RuntimeException ignored) { /* The file has already been removed; stale grants cannot open it. */ }
            });
        }
        return archive;
    }
    static Uri uri(File file) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build(); }
    @Override public boolean onCreate() { archive(getContext()); return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1
                || uri.getQuery() != null || uri.getFragment() != null) throw new FileNotFoundException("无效日志地址");
        try { return archive(getContext()).resolve(uri.getLastPathSegment(), System.currentTimeMillis()); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file; try { file = file(uri); } catch (FileNotFoundException e) { throw new IllegalArgumentException(e.getMessage()); }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("日志只能读取");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
