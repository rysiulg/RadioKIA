package pl.radiokia.fm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.LinkedHashSet;

/** Udostępnia zapisane presety wyłącznie zaufanemu pulpitowi RadioKIA. */
public final class PresetData extends ContentProvider {
    private static final String DASHBOARD = "pl.radiokia.preview";
    private static final String SHA = "e0acc5ac9d5a38c77adfce35a82dbd44d0e6ef4c52697ace103977670097c7d5";

    static LinkedHashSet<Integer> read(Context context) {
        LinkedHashSet<Integer> presets = new LinkedHashSet<>();
        String saved = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).getString(Config.PRESETS, "");
        for (String value : saved.split(",")) {
            try {
                int frequency = Integer.parseInt(value);
                if (frequency >= Config.FM_MIN && frequency <= Config.FM_MAX) presets.add(frequency);
            } catch (NumberFormatException ignored) { /* Pusty lub uszkodzony wpis. */ }
        }
        return presets;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"presets".equals(method) || !trusted()) throw new SecurityException("Tylko pulpit RadioKIA");
        LinkedHashSet<Integer> presets = read(getContext());
        int[] channels = new int[Math.min(presets.size(), Config.MAX_PRESETS)];
        int index = 0;
        for (int channel : presets) {
            if (index == channels.length) break;
            channels[index++] = channel;
        }
        Bundle result = new Bundle();
        result.putIntArray("channels", channels);
        return result;
    }

    private boolean trusted() {
        if (!DASHBOARD.equals(getCallingPackage())) return false;
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(DASHBOARD, PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length != 1) return false;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());
            return new BigInteger(SHA, 16).equals(new BigInteger(1, digest));
        } catch (Exception unavailable) { return false; }
    }

    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
