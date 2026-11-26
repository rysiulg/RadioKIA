package pl.radiokia.preview;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Interaktywna mapa pozycji z radia, z kaflami OSM tylko dla oglądanego kadru. */
final class LiveMap extends View {
    private static final int TILE=256, DEFAULT_ZOOM=15;
    private static final ExecutorService HTTP=Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> TILES=new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Set<String> LOADING=new HashSet<>();
    private static Network tileNetwork;
    private static final Map<String,Long> BACKOFF=new LinkedHashMap<String,Long>(64,0.75F,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> entry) { return size()>128; }
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path markerPath=new Path();
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback networkChanges=new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { post(LiveMap.this::refreshBackground); }
        @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c) { post(LiveMap.this::refreshBackground); }
        @Override public void onLost(Network n) { post(LiveMap.this::refreshBackground); }
    };
    private Network activeNetwork;
    private boolean watching;
    private long redrawAt=Long.MAX_VALUE;
    private final Runnable redrawTiles=() -> { redrawAt=Long.MAX_VALUE; invalidate(); };
    private double[] roadMarkers=new double[0];
    private boolean showRoadMarkers=true;
    private Location position;
    private boolean follow=true;
    private final GestureDetector pan;
    private boolean pinching;
    private double pinchSpan,pinchX,pinchY;
    private boolean ready;
    private volatile boolean background,attached;
    private double cx,cy;
    private double zoom=DEFAULT_ZOOM;

    LiveMap(Context context) {
        super(context);
        setContentDescription("Interaktywna mapa GPS");
        connectivity=context.getSystemService(ConnectivityManager.class);
        pan=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy) {
                if (pinching || position==null) return false;
                follow=false;
                double world=world(); cx+=dx/world; cy=Math.max(0,Math.min(1,cy+dy/world)); invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                follow=false; zoomBy(1,e.getX(),e.getY()); return true;
            }
        });
    }
    void refreshBackground() {
        if (!attached || connectivity==null) return;
        Network network=connectivity.getActiveNetwork();
        NetworkCapabilities caps=network==null ? null : connectivity.getNetworkCapabilities(network);
        // Działające połączenie VPN nie zawsze zgłasza INTERNET i VALIDATED.
        boolean online=network!=null && (caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
        if (background==online && Objects.equals(activeNetwork,network)) return;
        activeNetwork=network; background=online;
        if (online) synchronized (LOADING) {
            if (!Objects.equals(tileNetwork,network)) { tileNetwork=network; BACKOFF.clear(); }
        }
        if (online && HttpResponseCache.getInstalled()==null) {
            try { HttpResponseCache.install(new File(getContext().getCacheDir(),"osm"),48L*1024*1024); }
            catch (IOException e) { /* Pozostaje cache w RAM. */ }
        }
        invalidate();
    }
    private void zoomBy(int delta,float focusX,float focusY) {
        zoomAround(delta,focusX,focusY,focusX,focusY);
    }
    private void zoomAround(double delta,double fromX,double fromY,double toX,double toY) {
        double prior=world(), midX=getWidth()/2.0, midY=getHeight()/2.0;
        zoom=Math.min(18,Math.max(2,zoom+delta));
        double current=world();
        cx+=(fromX-midX)/prior-(toX-midX)/current;
        cy=Math.max(0,Math.min(1,cy+(fromY-midY)/prior-(toY-midY)/current));
        invalidate();
    }
    void roadMarkers(double[] markers) { roadMarkers=markers; invalidate(); }
    void showRoadMarkers(boolean show) { showRoadMarkers=show; invalidate(); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached=true; refreshBackground();
        if (connectivity!=null) { connectivity.registerDefaultNetworkCallback(networkChanges); watching=true; }
    }
    @Override protected void onDetachedFromWindow() {
        attached=false; background=false; activeNetwork=null;
        removeCallbacks(redrawTiles); redrawAt=Long.MAX_VALUE;
        if (watching) { connectivity.unregisterNetworkCallback(networkChanges); watching=false; }
        super.onDetachedFromWindow();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pinching=false; pinchSpan=0;
                if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (!pinching) {
                    MotionEvent cancel=MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    pan.onTouchEvent(cancel); cancel.recycle();
                }
                pinching=true; pinchSpan=0;
                if (event.getPointerCount()==2) pinch(event);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinchSpan=0;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && event.getPointerCount()==2) pinch(event);
                break;
            default: break;
        }
        if (!pinching) pan.onTouchEvent(event);
        return true;
    }
    // Używamy rozstawu od drugiego dotknięcia, także gdy palce zaczynają blisko siebie.
    private void pinch(MotionEvent event) {
        double firstX=event.getX(0), secondX=event.getX(1), firstY=event.getY(0), secondY=event.getY(1);
        double span=Math.max(8*getResources().getDisplayMetrics().density,Math.hypot(firstX-secondX,firstY-secondY));
        double focusX=(firstX+secondX)/2, focusY=(firstY+secondY)/2;
        if (pinchSpan>0 && position!=null) {
            follow=false;
            zoomAround(Math.log(span/pinchSpan)/Math.log(2),pinchX,pinchY,focusX,focusY);
        }
        pinchSpan=span; pinchX=focusX; pinchY=focusY;
    }
    private static double x(double longitude) { return (longitude+180)/360; }
    private static double y(double latitude) {
        double sine=Math.sin(Math.toRadians(Math.max(-85,Math.min(85,latitude))));
        return 0.5-Math.log((1+sine)/(1-sine))/(4*Math.PI);
    }
    private double world() { return TILE*Math.pow(2,zoom); }
    private double wrap(double value) { return value-Math.rint(value); }
    void position(Location fix) {
        if (fix==null) return;
        position=fix;
        if (follow || !ready) center();
        invalidate();
    }
    void resetView() {
        zoom=DEFAULT_ZOOM;
        center();
    }
    private void center() {
        follow=true;
        if (position==null) return;
        cx=x(position.getLongitude()); cy=y(position.getLatitude());
        ready=true;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff14283a);
        int w=getWidth(), h=getHeight();
        paint.setColor(0xff294359); paint.setStrokeWidth(1);
        for (int i=0;i<w;i+=40) canvas.drawLine(i,0,i,h,paint);
        for (int i=0;i<h;i+=40) canvas.drawLine(0,i,w,i,paint);
        if (position==null) {
            paint.setColor(0xffd8e8f6); paint.setTextSize(17*getResources().getDisplayMetrics().density);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Oczekiwanie na pozycję GPS",w/2f,h/2f,paint);
            paint.setTextAlign(Paint.Align.LEFT);
        } else {
            if (!ready) center();
            if (background) drawBackground(canvas,w,h);
            double world=world();
            if (showRoadMarkers && zoom>=9) drawRoadMarkers(canvas,w,h,world);
            float px=(float)(w/2.0+wrap(x(position.getLongitude())-cx)*world);
            float py=(float)(h/2.0+(y(position.getLatitude())-cy)*world);
            paint.setColor(0xff0b1b2d); canvas.drawCircle(px,py,12,paint);
            paint.setColor(0xff75bfff); canvas.drawCircle(px,py,8,paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(px,py,3,paint);
        }
        if (background && position!=null) {
            String source="© OpenStreetMap contributors";
            paint.setTextSize(15); paint.setColor(0xee112232);
            canvas.drawRect(w-paint.measureText(source)-16,h-28,w,h,paint);
            paint.setColor(Color.WHITE); canvas.drawText(source,w-paint.measureText(source)-8,h-8,paint);
        }
    }
    private void drawRoadMarkers(Canvas canvas,int w,int h,double world) {
        float radius=6*getResources().getDisplayMetrics().density;
        for (int i=0;i<roadMarkers.length;i+=3) {
            float px=(float)(w/2.0+wrap(x(roadMarkers[i+2])-cx)*world);
            if (px<-radius || px>w+radius) continue;
            float py=(float)(h/2.0+(y(roadMarkers[i+1])-cy)*world);
            if (py<-radius || py>h+radius) continue;
            paint.setColor(0xff132333);
            canvas.drawCircle(px,py,radius+2,paint);
            if (roadMarkers[i]==0) {
                paint.setColor(0xffff6961); canvas.drawCircle(px,py,radius,paint);
            } else {
                paint.setColor(0xffffc36f);
                markerPath.reset(); markerPath.moveTo(px,py-radius); markerPath.lineTo(px+radius,py);
                markerPath.lineTo(px,py+radius); markerPath.lineTo(px-radius,py); markerPath.close();
                canvas.drawPath(markerPath,paint);
            }
        }
    }
    private void drawBackground(Canvas canvas,int w,int h) {
        double left=cx*world()-w/2.0, top=cy*world()-h/2.0;
        int tileZoom=(int)Math.floor(zoom), side=1<<tileZoom;
        double tileSize=world()/side;
        int x0=(int)Math.floor(left/tileSize), y0=Math.max(0,(int)Math.floor(top/tileSize));
        int x1=(int)Math.floor((left+w)/tileSize), y1=Math.min(side-1,(int)Math.floor((top+h)/tileSize));
        paint.setColor(Color.WHITE);
        for (int tx=x0;tx<=x1;tx++) for (int ty=y0;ty<=y1;ty++) {
            String key=tileZoom+"/"+Math.floorMod(tx,side)+"/"+ty;
            Bitmap tile=TILES.get(key);
            RectF dest=new RectF((float)(tx*tileSize-left),(float)(ty*tileSize-top),
                (float)((tx+1)*tileSize-left),(float)((ty+1)*tileSize-top));
            if (tile!=null) canvas.drawBitmap(tile,null,dest,paint);
            else {
                // Kafel z poprzedniego poziomu utrzymuje podkład do czasu pobrania nowego.
                if (tileZoom>2) {
                    int parentX=Math.floorMod(tx,side)/2, parentY=ty/2;
                    Bitmap parent=TILES.get((tileZoom-1)+"/"+parentX+"/"+parentY);
                    if (parent!=null) {
                        int halfX=parent.getWidth()/2, halfY=parent.getHeight()/2;
                        int srcX=Math.floorMod(tx,2)*halfX, srcY=(ty%2)*halfY;
                        canvas.drawBitmap(parent,new Rect(srcX,srcY,srcX+halfX,srcY+halfY),dest,paint);
                    }
                }
                request(key);
            }
        }
    }
    private void retryTilesAt(long at) {
        if (at>=redrawAt) return;
        redrawAt=at; removeCallbacks(redrawTiles);
        postDelayed(redrawTiles,Math.max(0,at-SystemClock.uptimeMillis()));
    }
    private void request(String key) {
        synchronized (LOADING) {
            long now=SystemClock.uptimeMillis(), retryAt=BACKOFF.getOrDefault(key,0L);
            if (now<retryAt) { retryTilesAt(retryAt); return; }
            if (LOADING.size()>=24 || !LOADING.add(key)) { retryTilesAt(now+500); return; }
        }
        HTTP.execute(() -> {
            Bitmap tile=null; HttpURLConnection http=null;
            boolean attempted=false;
            try {
                if (attached && background) {
                    attempted=true;
                    http=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                    http.setInstanceFollowRedirects(false); http.setConnectTimeout(4500); http.setReadTimeout(4500);
                    http.setRequestProperty("User-Agent","RadioKIA-Pulpit/0.3 (https://github.com/rysiulg/RadioKIA)");
                    if (http.getResponseCode()==200) try (InputStream in=http.getInputStream()) { tile=BitmapFactory.decodeStream(in); }
                }
            } catch (IOException ignored) {
                // Pozycja GPS i zapisane pomiary pozostają dostępne na siatce.
            } finally {
                if (http!=null) http.disconnect();
                synchronized (LOADING) {
                    if (tile!=null) TILES.put(key,tile);
                    else if (attempted && attached && background) BACKOFF.put(key,SystemClock.uptimeMillis()+45000);
                    LOADING.remove(key);
                }
                if (attached) postInvalidate();
            }
        });
    }
}
