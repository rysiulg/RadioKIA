package pl.radiokia.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Przypisanie przycisku dostępne wyłącznie w aktywnym Pulpicie. */
final class ClimateShortcut {
    private static final String KEY="climateShortcut";
    private static final long TOPWAY_REPEAT_MS=350;
    private static final String FIRST_PRESS="Naciśnij fizyczny przycisk CLIMATE.";
    private final Activity host;
    private final SharedPreferences preferences;
    private final Runnable toggle;
    private AlertDialog pairing;
    private String binding,firstPress;
    private int lastTopwayKey;
    private long lastTopwayTime;

    ClimateShortcut(Activity host,SharedPreferences preferences,Runnable toggle) {
        this.host=host; this.preferences=preferences; this.toggle=toggle;
        binding=preferences.getString(KEY,"");
    }

    void pair() {
        if (pairing!=null) return;
        firstPress=null;
        AlertDialog.Builder builder=new AlertDialog.Builder(host).setTitle("Przypisz przycisk CLIMATE")
            .setMessage(FIRST_PRESS).setNegativeButton("Anuluj",null);
        if (!binding.isEmpty()) builder.setNeutralButton("Usuń przypisanie",(dialog,which) -> save(""));
        pairing=builder.create();
        pairing.setOnKeyListener((dialog,code,event) -> onAndroidKey(event));
        pairing.setOnDismissListener(dialog -> { if (pairing==dialog) { pairing=null; firstPress=null; } });
        pairing.show();
    }

    void dismissPairing() {
        if (pairing!=null) pairing.dismiss();
    }

    boolean onAndroidKey(KeyEvent event) {
        int code=event.getKeyCode();
        if (event.isSystem() || KeyEvent.isModifierKey(code) || (code==KeyEvent.KEYCODE_UNKNOWN && event.getScanCode()==0)) return false;
        String key="android:"+code+":"+event.getScanCode();
        if (event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled()) press(key);
        return binding.equals(key) || pairing!=null;
    }

    void onTopwayKey(int code) {
        String key="topway:"+code;
        if (pairing==null && !binding.equals(key)) return;
        long now=SystemClock.elapsedRealtime();
        boolean repeated=lastTopwayKey==code && now-lastTopwayTime<TOPWAY_REPEAT_MS;
        lastTopwayKey=code; lastTopwayTime=now;
        if (!repeated) press(key);
    }

    private void press(String key) {
        if (pairing==null) {
            if (binding.equals(key)) toggle.run();
            return;
        }
        if (firstPress==null) {
            firstPress=key;
            pairing.setMessage("Naciśnij ten sam CLIMATE jeszcze raz.");
        } else if (firstPress.equals(key)) {
            save(key);
            pairing.dismiss();
        }
    }

    private void save(String key) {
        binding=key;
        if (key.isEmpty()) preferences.edit().remove(KEY).apply();
        else {
            preferences.edit().putString(KEY,key).apply();
            android.widget.Toast.makeText(host,"Przypisano przycisk CLIMATE",android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
