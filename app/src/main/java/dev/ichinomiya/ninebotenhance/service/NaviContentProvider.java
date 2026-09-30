package dev.ichinomiya.ninebotenhance.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import dev.ichinomiya.ninebotenhance.core.CallerPolicy;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.navi.NaviHub;

/**
 * Cross-app channel for the navigation apps to hand their turn-by-turn state to the module process. ColorOS blocks a background
 * app from binding another app's service, but a content-provider {@code call} is resolved differently and goes through, so the
 * navigation apps publish here instead of binding {@link FrameBridgeService}. Only the navigation apps may call, and only to
 * publish navigation state or a log line; nothing here touches capture, root or the vehicle.
 */
public final class NaviContentProvider extends ContentProvider {
    public static final String AUTHORITY=dev.ichinomiya.ninebotenhance.ipc.Flavor.AUTHORITY+".navi";
    public static final String METHOD_PUBLISH="publish",METHOD_REPORT="report",METHOD_SNAPSHOT="snapshot";
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        boolean self=uid==android.os.Process.myUid();
        if(!self&&!CallerPolicy.naviApp(packages))throw new SecurityException("仅允许导航应用发布导航数据");
        if(METHOD_PUBLISH.equals(method)){NaviHub.get().publish(extras);return null;}
        if(METHOD_REPORT.equals(method)){if(extras!=null)Diagnostics.add(extras.getString("message",""));return null;}
        if(METHOD_SNAPSHOT.equals(method))return NaviHub.get().snapshot();
        return null;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
}
