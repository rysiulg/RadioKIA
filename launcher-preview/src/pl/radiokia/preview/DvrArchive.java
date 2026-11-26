package pl.radiokia.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Odtwarzanie korzysta z aplikacji rejestratora i z katalogu kopii wskazanego w Androidzie. */
final class DvrArchive {
    static final int FOLDER_REQUEST=43;
    private static final String APP="com.fvsm.camera", SERVICE=APP+".service.ForegroundService";
    private static final String SEND="HUIYING_BROADCAST_SEND", RECEIVE="HUIYING_BROADCAST_RECV";
    private static final String FILE="EXTRA_FILE", MEDIA="EXTRA_MEDIA_TYPE", TYPE="KEY_TYPE";
    private static final String STORAGE="com.android.externalstorage.documents", FOLDER="primary:uvccameramjpeg/VIDEO", PREF="dvrCopyFolder";
    private static final int LIST=2, VIDEO=1, OP_LIST=7, OP_COPY=8, CAMERA_FRONT=0;
    private static final int VID=0x0951, PID=0x1666;
    private static final long LIST_TIMEOUT=15000, COPY_TIMEOUT=180000;
    private static final Pattern VIDEO_NAME=Pattern.compile("[A-Za-z0-9_.-]{1,60}\\.[mM][pP]4");
    private final Activity host;
    private final SharedPreferences preferences;
    private final Consumer<Uri> playback;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { receive(intent); }
    };
    private AlertDialog waiting;
    private int expectedMedia;
    private boolean listening;
    private String folderSelection, copying;

    DvrArchive(Activity host,SharedPreferences preferences,Consumer<Uri> playback) {
        this.host=host; this.preferences=preferences; this.playback=playback;
    }

    void open() {
        new AlertDialog.Builder(host).setTitle("Rejestrator USB")
            .setItems(new String[]{"Na żywo","Nagrania z karty"},(dialog,which) -> {
                if (which==0) openRecorder(); else loadList();
            }).setNegativeButton("Wróć",null).show();
    }

    private void openRecorder() {
        Intent launch=host.getPackageManager().getLaunchIntentForPackage(APP);
        if (launch==null) { notice("Brak aplikacji High definition recorder."); return; }
        try { host.startActivity(launch); }
        catch (ActivityNotFoundException | SecurityException unavailable) { notice("Nie można otworzyć aplikacji rejestratora."); }
    }

    private boolean usbPresent() {
        UsbManager usb=host.getSystemService(UsbManager.class);
        if (usb==null) return false;
        for (UsbDevice device:usb.getDeviceList().values())
            if (device.getVendorId()==VID && device.getProductId()==PID) return true;
        return false;
    }

    private void loadList() {
        if (!usbPresent()) { help("Android nie wykrywa zgodnego rejestratora na USB."); return; }
        try {
            await(LIST,"Odczyt nagrań z karty",LIST_TIMEOUT);
            host.startForegroundService(new Intent(SERVICE).setClassName(APP,SERVICE));
            timer.postDelayed(() -> host.sendBroadcast(command(OP_LIST).putExtra("EXTRA_PLAY_BACK_TYPE",CAMERA_FRONT)),800);
        } catch (RuntimeException unavailable) {
            stopWaiting(); help("Nie można uruchomić odczytu nagrań z rejestratora.");
        }
    }

    private Intent command(int operation) {
        return new Intent(SEND).setPackage(APP).putExtra(TYPE,1003).putExtra("EXTRA_OP",operation);
    }

    private void await(int media,String title,long timeout) {
        stopWaiting();
        IntentFilter replies=new IntentFilter(RECEIVE);
        if (Build.VERSION.SDK_INT>=33) host.registerReceiver(receiver,replies,Context.RECEIVER_EXPORTED);
        else host.registerReceiver(receiver,replies);
        listening=true; expectedMedia=media;
        waiting=new AlertDialog.Builder(host).setTitle(title)
            .setNegativeButton("Wróć",(dialog,which) -> stopWaiting()).setOnCancelListener(dialog -> stopWaiting()).show();
        timer.postDelayed(() -> { stopWaiting(); help("Rejestrator nie odpowiedział na żądanie."); },timeout);
    }

    void stopWaiting() {
        timer.removeCallbacksAndMessages(null);
        if (listening) { host.unregisterReceiver(receiver); listening=false; }
        expectedMedia=0; copying=null;
        if (waiting!=null) { waiting.dismiss(); waiting=null; }
    }

    private void receive(Intent reply) {
        if (!listening || !RECEIVE.equals(reply.getAction())) return;
        try {
            if (reply.getIntExtra(TYPE,0)!=1004 || reply.getIntExtra(MEDIA,0)!=expectedMedia) return;
            if (expectedMedia==LIST) receiveList(reply); else receiveVideo(reply);
        } catch (RuntimeException invalid) {
            stopWaiting(); help("Rejestrator zwrócił nieprawidłową odpowiedź.");
        }
    }

    private void receiveList(Intent reply) {
        ArrayList<?> entries=reply.getStringArrayListExtra("EXTRA_PLAY_BACK_LIST");
        TreeSet<String> names=new TreeSet<>(Collections.reverseOrder());
        if (reply.getBooleanExtra("EXTRA_STATUS",false) && entries!=null)
            for (Object entry:entries) if (entry instanceof String && validName((String)entry)) names.add((String)entry);
        stopWaiting();
        if (names.isEmpty()) {
            int error=reply.getIntExtra("EXTRA_ERROR_CODE",0);
            help(error==201 ? "Aplikacja rejestratora czeka na dostęp do kamery USB." :
                error==202 ? "Rejestrator nie wykrywa karty pamięci." : "Rejestrator nie zwrócił nagrań z przedniej kamery.");
            return;
        }
        String[] videos=names.toArray(new String[0]);
        new AlertDialog.Builder(host).setTitle("Nagrania z karty · przód")
            .setItems(videos,(dialog,index) -> chooseVideo(videos[index]))
            .setNegativeButton("Wróć",null).show();
    }

    private void chooseVideo(String name) {
        if (!validName(name)) return;
        Uri folder=Uri.parse(preferences.getString(PREF,""));
        if (validFolder(folder) && host.getContentResolver().getPersistedUriPermissions().stream()
            .anyMatch(permission -> permission.isReadPermission() && permission.getUri().equals(folder))) {
            playOrCopy(folder,name);
            return;
        }
        new AlertDialog.Builder(host).setTitle("Dostęp do nagrań")
            .setMessage("Wskaż folder uvccameramjpeg/VIDEO w pamięci radia. Tam aplikacja rejestratora zapisuje kopie nagrań.")
            .setNegativeButton("Wróć",null).setPositiveButton("Wybierz folder",(dialog,which) -> pickFolder(name)).show();
    }

    private void pickFolder(String name) {
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri(STORAGE,FOLDER));
        try { folderSelection=name; host.startActivityForResult(pick,FOLDER_REQUEST); }
        catch (ActivityNotFoundException missing) { folderSelection=null; help("Android nie udostępnia wyboru folderu."); }
    }

    void folderPicked(int result,Intent data) {
        String name=folderSelection; folderSelection=null;
        if (result!=Activity.RESULT_OK || data==null || name==null) return;
        Uri folder=data.getData();
        if (!validFolder(folder)) { chooseVideo(name); return; }
        try { host.getContentResolver().takePersistableUriPermission(folder,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException denied) { help("Android nie przyznał dostępu do folderu nagrań."); return; }
        preferences.edit().putString(PREF,folder.toString()).apply();
        playOrCopy(folder,name);
    }

    private void playOrCopy(Uri folder,String name) {
        Uri cached=findVideo(folder,name);
        if (cached!=null) { playback.accept(cached); return; }
        if (!usbPresent()) { help("Rejestrator został odłączony."); return; }
        try {
            await(VIDEO,"Pobieranie nagrania z karty",COPY_TIMEOUT);
            copying=name;
            host.sendBroadcast(command(OP_COPY).putExtra("EXTRA_PLAY_BACK_NAME",name));
        } catch (RuntimeException unavailable) {
            stopWaiting(); help("Nie można pobrać nagrania.");
        }
    }

    private void receiveVideo(Intent reply) {
        String path=reply.getStringExtra(FILE);
        if (path!=null && !path.substring(path.lastIndexOf('/')+1).equals(copying)) return;
        Uri folder=Uri.parse(preferences.getString(PREF,""));
        String name=copying;
        boolean success=reply.getBooleanExtra("EXTRA_STATUS",false) && path!=null;
        stopWaiting();
        Uri video=success && validFolder(folder) && validName(name) ? findVideo(folder,name) : null;
        if (video!=null) playback.accept(video); else help("Nie udało się odczytać kopii nagrania.");
    }

    private Uri findVideo(Uri tree,String name) {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor rows=host.getContentResolver().query(children,columns,null,null,null)) {
            while (rows!=null && rows.moveToNext())
                if (name.equals(rows.getString(1)) && rows.getLong(2)>0)
                    return DocumentsContract.buildDocumentUriUsingTree(tree,rows.getString(0));
        } catch (RuntimeException unavailable) { }
        return null;
    }

    private static boolean validName(String name) {
        return name!=null && VIDEO_NAME.matcher(name).matches() && !name.startsWith(".");
    }

    private static boolean validFolder(Uri uri) {
        return uri!=null && STORAGE.equals(uri.getAuthority()) && DocumentsContract.isTreeUri(uri) &&
            FOLDER.equalsIgnoreCase(DocumentsContract.getTreeDocumentId(uri));
    }

    private void help(String detail) {
        new AlertDialog.Builder(host).setTitle("Rejestrator USB").setMessage(detail)
            .setPositiveButton("Aplikacja rejestratora",(dialog,which) -> openRecorder())
            .setNegativeButton("Wróć",null).show();
    }

    private void notice(String detail) {
        new AlertDialog.Builder(host).setTitle("Rejestrator USB").setMessage(detail).setPositiveButton("Zamknij",null).show();
    }
}
