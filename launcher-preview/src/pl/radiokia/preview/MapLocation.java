package pl.radiokia.preview;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/** Dostarcza bieżącą pozycję mapie. Trasę rejestruje CarOBD. */
final class MapLocation implements LocationListener {
    private final LocationManager manager;
    private final Runnable changed;
    private boolean active;
    private Location position;

    MapLocation(Context context, Runnable changed) {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.changed = changed;
    }

    boolean start() {
        if (active) return true;
        if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            active = true;
            return true;
        } catch (SecurityException | IllegalArgumentException unavailable) { return false; }
    }

    void stop() {
        if (!active) return;
        active = false;
        manager.removeUpdates(this);
    }

    Location position() { return position; }
    double altitude() {
        if (!active || position==null || !position.hasAltitude()) return Double.NaN;
        long age=SystemClock.elapsedRealtimeNanos()-position.getElapsedRealtimeNanos();
        return age>=0 && age<=6_500_000_000L ? position.getAltitude() : Double.NaN;
    }

    @Override public void onLocationChanged(Location point) {
        if (!active || point==null || (point.hasAccuracy() && point.getAccuracy()>70)) return;
        position=new Location(point);
        changed.run();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { changed.run(); }
    @Override public void onProviderDisabled(String provider) { stop(); changed.run(); }
}
