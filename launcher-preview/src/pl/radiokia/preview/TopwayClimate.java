package pl.radiokia.preview;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.function.Consumer;

/** Odczyt stanu klimatyzacji z interfejsu MCU, zgodny z SystemUI dla tego TS7. */
final class TopwayClimate implements AutoCloseable {
    private static final short CLIMATE_STATE=0x0509;
    private static final String CLIENT="RadioKIA climate";
    private final Consumer<State> update;
    private final Handler handler=new Handler(Looper.getMainLooper(),this::receive);
    private Class<?> api;
    private Object client;

    static final class State {
        final boolean active,ac,automatic,recirculation;
        final int fan,left,right;
        State(byte[] data) {
            active=(data[0]&0x80)!=0; ac=(data[0]&0x40)!=0;
            automatic=(data[0]&8)!=0; recirculation=(data[0]&0x20)!=0;
            fan=data[1]&0xf;
            left=data[2]&255; right=data[3]&255;
        }
        static double temperature(int field,boolean fahrenheitSource) {
            if (field<1 || field>46) return Double.NaN;
            double value=14+(field-1)*.5;
            return fahrenheitSource ? (value-32)/1.8 : value;
        }
    }

    TopwayClimate(Consumer<State> update) { this.update=update; }

    boolean start() {
        if (client!=null) return true;
        if (Build.VERSION.SDK_INT!=Build.VERSION_CODES.O_MR1) return false;
        try {
            api=Class.forName("android.tw.john.TWUtil");
            // Osobny obiekt, by nie przejąć singletona TWUtil używanego przez tuner.
            client=api.getConstructor().newInstance();
            if ((Integer)api.getMethod("open",short[].class).invoke(client,(Object)new short[]{CLIMATE_STATE})!=0) {
                close(); return false;
            }
            api.getMethod("addHandler",String.class,Handler.class).invoke(client,CLIENT,handler);
            api.getMethod("start").invoke(client);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { close(); return false; }
    }
    /** 0xff jest żądaniem odczytu stanu używanym przez fabryczne MainAirModel.reqAirInfo. */
    boolean requestState() {
        if (client==null) return false;
        try { return (Integer)api.getMethod("write",int.class,int.class).invoke(client,(int)CLIMATE_STATE,0xff)>=0; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    private boolean receive(Message message) {
        if (client!=null && (message.what&0xffff)==CLIMATE_STATE && message.obj instanceof byte[]) {
            byte[] state=(byte[])message.obj;
            if (state.length==16) update.accept(new State(state));
        }
        return true;
    }
    @Override public void close() {
        Object owned=client;
        client=null; handler.removeCallbacksAndMessages(null);
        if (owned==null) return;
        try { api.getMethod("removeHandler",String.class).invoke(owned,CLIENT); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
        try { api.getMethod("stop").invoke(owned); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
        try { api.getMethod("close").invoke(owned); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
    }
}
