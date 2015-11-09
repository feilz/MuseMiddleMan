/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2015
 */

package com.interaxon.test.libmuse;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.Image;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View.OnClickListener;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;


import com.interaxon.libmuse.ConnectionState;
import com.interaxon.libmuse.Eeg;
import com.interaxon.libmuse.LibMuseVersion;
import com.interaxon.libmuse.Muse;
import com.interaxon.libmuse.MuseArtifactPacket;
import com.interaxon.libmuse.MuseConnectionListener;
import com.interaxon.libmuse.MuseConnectionPacket;
import com.interaxon.libmuse.MuseDataListener;
import com.interaxon.libmuse.MuseDataPacket;
import com.interaxon.libmuse.MuseDataPacketType;
import com.interaxon.libmuse.MuseFileFactory;
import com.interaxon.libmuse.MuseFileWriter;
import com.interaxon.libmuse.MuseManager;
import com.interaxon.libmuse.MusePreset;
import com.interaxon.libmuse.MuseVersion;

import org.apache.http.HttpResponse;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;


/**
 * In this simple example MainActivity implements 2 MuseHeadband listeners
 * and updates UI when data from Muse is received. Similarly you can implement
 * listers for other data or register same listener to listen for different type
 * of data.
 * For simplicity we create Listeners as inner classes of MainActivity. We pass
 * reference to MainActivity as we want listeners to update UI thread in this
 * example app.
 * You can also connect multiple muses to the same phone and register same
 * listener to listen for data from different muses. In this case you will
 * have to provide synchronization for data members you are using inside
 * your listener.
 *
 * Usage instructions:
 * 1. Enable bluetooth on your device
 * 2. Pair your device with muse
 * 3. Run this project
 * 4. Press Refresh. It should display all paired Muses in Spinner
 * 5. Make sure Muse headband is waiting for connection and press connect.
 * It may take up to 10 sec in some cases.
 * 6. You should see EEG and accelerometer data as well as connection status,
 * Version information and MuseElements (alpha, beta, theta, delta, gamma waves)
 * on the screen.
 */
public class MainActivity extends Activity implements OnClickListener {
    boolean isPlaying=false;
    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<Activity> activityRef;
        ConnectionListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(MuseConnectionPacket p) {
            final ConnectionState current = p.getCurrentConnectionState();
            final String status = current.toString();
            final String full = "Muse " + p.getSource().getMacAddress() +
                                " " + p.getPreviousConnectionState().toString() +
                            " -> " + current;
            Log.i("Muse Headband", full);
            Activity activity = activityRef.get();
            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView statusText =
                                (TextView) findViewById(R.id.statusView);
                        statusText.setText(status);
                        TextView museVersionText =
                                (TextView) findViewById(R.id.version);
                        if (current == ConnectionState.CONNECTED) {
                            MuseVersion museVersion = muse.getMuseVersion();
                            String version = museVersion.getFirmwareType() +
                                 " - " + museVersion.getFirmwareVersion() +
                                 " - " + Integer.toString(
                                    museVersion.getProtocolVersion());
                            museVersionText.setText(version);
                        } else {
                            museVersionText.setText(R.string.undefined);
                        }
                    }
                });
            }
        }
    }

    class DataListener extends MuseDataListener {

        final WeakReference<Activity> activityRef;
        private MuseFileWriter fileWriter;

        DataListener(final WeakReference<Activity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(MuseDataPacket p) {
            switch (p.getPacketType()) {
                case EEG:
                    yay.add(new Xx(p.getValues(), p.getTimestamp(), "EEG"));
                    if (yay.size()>=220){
                        try{
                            Runnable r = new PostData(yay);
                            exec.execute(r);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        yay = new ArrayList<>();
                    }
                    break;
                case BATTERY:
                    fileWriter.addDataPacket(1, p);
                    if (fileWriter.getBufferedMessagesSize() > 8096)
                        fileWriter.flush();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void receiveMuseArtifactPacket(MuseArtifactPacket p) {
            if (p.getHeadbandOn() && p.getBlink()) {
                Log.i("Artifacts", "blink");
            }
        }

        public void setFileWriter(MuseFileWriter fileWriter) {
            this.fileWriter = fileWriter;
        }

        List<Xx> yay = new ArrayList<>();
    }
    class Xx {
        public ArrayList<Double> data;
        public long timestamp;
        public String type;
        public Xx (ArrayList<Double> info, long timestamps, String types){
            data = info;
            timestamp=timestamps;
            type=types;
        }
    }
    class PostData  implements Runnable {
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httppost = new HttpPost("http://696a623f.ngrok.io/datablock");
        List<Xx> datalista;

        public PostData(List<Xx> datalista) {
            this.datalista=datalista;
        }
        public void postdata(JSONObject object) throws IOException {
            StringEntity se = new StringEntity(object.toString(), "utf-8");
            httppost.setEntity(se);
            try {
                HttpResponse httpresponse = httpclient.execute(httppost);
                String responsebody = EntityUtils.toString(httpresponse.getEntity());
                if (responsebody.equals("panic")){
                    try{
                        Runnable r = new Panic(true);
                        exec.execute(r);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                Log.d("myapp", "response" + responsebody);
            } catch (ClientProtocolException e) {
                e.printStackTrace();
            }
        }
        public JSONObject createObject(List<Xx> lst){
            JSONObject json = new JSONObject();
            try {
                for (Xx x: lst) {
                    json.accumulate("timestamps", x.timestamp);
                    json.accumulate("tp9", String.valueOf(x.data.get(Eeg.TP9.ordinal())));
                    json.accumulate("tp10", String.valueOf(x.data.get(Eeg.TP10.ordinal())));
                    json.accumulate("fp2", String.valueOf(x.data.get(Eeg.FP2.ordinal())));
                    json.accumulate("fp1", String.valueOf(x.data.get(Eeg.FP1.ordinal())));
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            try {
                json.accumulate("type","EEG");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return json;
        }
        @Override
        public void run() {
            try {
                postdata(createObject(datalista));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    class Panic implements Runnable, OnClickListener {
        boolean panic;
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        Ringtone r = RingtoneManager.getRingtone(getApplicationContext(),notification);
        public Panic(boolean val){
            panic=val;
        }
        public void notification(){
            if (!isPlaying)
            {
                r.play();
                TextView asd = (TextView)findViewById(R.id.statusView);
                asd.setOnClickListener(this);
                ImageButton play = (ImageButton)findViewById(R.id.statusbtn);
                play.setImageResource(R.drawable.panic);
                isPlaying=true;
            }

        }
        public void run(){
            try{
                notification();
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        @Override
        public void onClick(View v) {
            r.stop();
            isPlaying=false;
            ImageButton play = (ImageButton)findViewById(R.id.statusbtn);
            play.setImageResource(R.drawable.play);
        }
    }
    private Muse muse = null;
    private ConnectionListener connectionListener = null;
    private DataListener dataListener = null;
    private boolean dataTransmission = true;
    private MuseFileWriter fileWriter = null;
    private Executor exec = new ThreadPoolExecutor(3,10,20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(100));

    public MainActivity() {
        WeakReference<Activity> weakActivity =
                                new WeakReference<Activity>(this);
        connectionListener = new ConnectionListener(weakActivity);
        dataListener = new DataListener(weakActivity);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button refreshButton = (Button) findViewById(R.id.refresbtn);
        refreshButton.setOnClickListener(this);
        ImageButton connectButton = (ImageButton) findViewById(R.id.statusbtn);
        connectButton.setOnClickListener(this);
        ImageButton panicButton = (ImageButton) findViewById(R.id.status2);
        panicButton.setOnClickListener(this);
        Button pauseButton = (Button) findViewById(R.id.pause);
        pauseButton.setOnClickListener(this);
        TextView view = (TextView)findViewById(R.id.statusView);
        view.setOnClickListener(this);

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        fileWriter = MuseFileFactory.getMuseFileWriter(
                     new File(dir, "new_muse_file.muse"));
        Log.i("Muse Headband", "libmuse version=" + LibMuseVersion.SDK_VERSION);
        fileWriter.addAnnotationString(1, "MainActivity onCreate");
        dataListener.setFileWriter(fileWriter);
    }

    @Override
    public void onClick(View v) {
        Spinner musesSpinner = (Spinner) findViewById(R.id.muses_spinner);
        if (v.getId() == R.id.refresbtn) {
            MuseManager.refreshPairedMuses();
            List<Muse> pairedMuses = MuseManager.getPairedMuses();
            List<String> spinnerItems = new ArrayList<>();
            for (Muse m: pairedMuses) {
                String dev_id = m.getName() + "-" + m.getMacAddress();
                Log.i("Muse Headband", dev_id);
                spinnerItems.add(dev_id);
            }
            ArrayAdapter<String> adapterArray = new ArrayAdapter<> (
                    this, android.R.layout.simple_spinner_item, spinnerItems);
            musesSpinner.setAdapter(adapterArray);
        }
        else if (v.getId() == R.id.statusbtn) {
            isPlaying=false;
            List<Muse> pairedMuses = MuseManager.getPairedMuses();
            if (pairedMuses.size() < 1 ||
                musesSpinner.getAdapter().getCount() < 1) {
                Log.w("Muse Headband", "There is nothing to connect to");
            }
            else {
                muse = pairedMuses.get(musesSpinner.getSelectedItemPosition());
                ConnectionState state = muse.getConnectionState();
                if (state == ConnectionState.CONNECTED ||
                    state == ConnectionState.CONNECTING) {
                    Log.w("Muse Headband",
                    "doesn't make sense to connect second time to the same muse");
                    return;
                }
                configureLibrary();
                fileWriter.open();
                fileWriter.addAnnotationString(1, "Connect clicked");
                try {
                    muse.runAsynchronously();
                } catch (Exception e) {
                    Log.e("Muse Headband", e.toString());
                }
            }
        }
        else if (v.getId() == R.id.pause) {
            try{
                Runnable r = new Panic(true);
                exec.execute(r);
            } catch (Exception e){
                e.printStackTrace();
            }

            /*dataTransmission = !dataTransmission;
            if (muse != null) {
                muse.enableDataTransmission(dataTransmission);
            }*/
        }
        else if (v.getId()==R.id.status2) {
            ImageButton panic = (ImageButton)findViewById(R.id.status2);
            ImageButton play = (ImageButton)findViewById(R.id.statusbtn);
            panic.setVisibility(View.GONE);
            play.setVisibility(View.VISIBLE);
        }
    }

    private void configureLibrary() {
        muse.registerConnectionListener(connectionListener);
        muse.registerDataListener(dataListener,
                                   MuseDataPacketType.EEG);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.ARTIFACTS);
        muse.registerDataListener(dataListener,
                                  MuseDataPacketType.BATTERY);
        muse.setPreset(MusePreset.PRESET_14);
        muse.enableDataTransmission(dataTransmission);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
