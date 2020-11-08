package com.example.testing;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

import static android.content.Context.MODE_PRIVATE;

public class TerminalFragment extends Fragment implements  ServiceConnection, SerialListener {

    private class Loc {

        private double lat;
        private double lon;
        private String id;
        public Loc() {

        }
        public Loc(String gps_id, double _lat, double _lon) {
            lat = _lat;
            lon = _lon;
            id = gps_id;
        }
    }

    private static String begginningGPS = "GPS_DATA:";
    private static String endingGPS = "Done:";
    private static String messageEnd = "End:";
    private static String messageStart = "START:";
    private static int MAXKEPT = 1000;

    private MapView mapView;

    private Semaphore send_sem = new Semaphore(1);
    private Semaphore receive_sem = new Semaphore(1);

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private String newline = "\r\n";

    private TextView receiveText;

    private SerialSocket socket;
    private SerialService service;
    private boolean initialStart = true;
    private Connected connected = Connected.False;

    private TextView sendText;

    private Aes256Class aes256Class;
    private byte[] receivedBytes;
    private int receivedBytesIndex = 0;
    private String myID = "Bob";
    private final String allTargets = "-1";
    private String myTarget = allTargets;


    private Hashtable<String, Loc> locations;
    private Hashtable<String, ArrayList<String>> timestamps;
    public static final String SHARED_PREFS = "sharedPrefs";
    public static final String NAME = "NAME";
    private static final String TEXT = "TEXT";
    private Hashtable<String, Boolean> confirmStrings;
    private Thread checkConfirmation;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
        aes256Class = new Aes256Class();
        receivedBytes = new byte[1024];
        locations = new Hashtable<String, Loc>();
        timestamps = new Hashtable<String, ArrayList<String>>();
        confirmStrings = new Hashtable<String, Boolean>();
        checkConfirmation = new Thread(new Runnable() {
            public void run() {
                Hashtable<String, Integer> counts = new Hashtable<String, Integer>();
                while(true) {
                    try {
                        for(Hashtable.Entry<String, Boolean> entry : confirmStrings.entrySet()) {
                            if(!entry.getValue()) {
                                //Note this isn't amazing because the target could change within the 5 seconds. Find a better solution if we keep this.
                                forwardMessage(entry.getKey().getBytes());
                                if(counts.containsKey(entry.getKey())) {
                                    int val = counts.get(entry.getKey()) + 1;
                                    counts.put(entry.getKey(), val);
                                    if(val > 5) {
                                        counts.remove(entry.getKey());
                                        confirmStrings.put(entry.getKey(), true);
                                        status("String: '" + get_message(entry.getKey()) + "' was not confirmed as received in network");
                                    }
                                } else {
                                    counts.put(entry.getKey(), 1);
                                }

                            }
                            else {
                                confirmStrings.remove(entry.getKey());
                            }
                        }

                        checkConfirmation.sleep(1000);
                    } catch (Exception e) {

                    }
                }
            }
        });
        checkConfirmation.start();

        // This callback will only be called when MyFragment is at least Started.
        /*OnBackPressedCallback callback = new OnBackPressedCallback(true) { //enabled by default
            @Override
            public void handleOnBackPressed() {
                // Handle the back button event
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, callback);*/
    }

    //Possible feature to add, needs some work to make it more useful and better looking.
    private void loadText() {
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences(SHARED_PREFS,MODE_PRIVATE);
        String text = sharedPreferences.getString(TEXT, "");
        receiveText.setTextColor(getResources().getColor(R.color.colorStatusText)); // set as default color to reduce number of spans
        receiveText.setText(text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
    }

    //Possible feature to add, needs some work to make it more useful and better looking.
    private void saveText() {
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences(SHARED_PREFS,MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(TEXT,receiveText.getText().toString());
        editor.commit();
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        //saveText();
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service !=null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        mapView = (MapView) view.findViewById(R.id.mapView);
        mapView.setVisibility(View.INVISIBLE);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        //loadText();
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));


        Button test_one_button = (Button) view.findViewById(R.id.test_1);
        test_one_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle("Enter your target below.");
                final EditText input = new EditText(getContext());
                builder.setView(input);
                builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        myTarget = input.getText().toString();
                    }
                });
                builder.show();
            }
        });
        Button test_two_button = (Button) view.findViewById(R.id.test_2);
        test_two_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                myTarget = allTargets;
            }
        });

        return view;
    }

    private float convertToRelative(double point, double min, double range) {
        return (float)((point - min)/range * 1000);
    }

    private void convertLocationToPoint() {
        Canvas mapCanvas = new Canvas();
        Set<String> loc_keys = locations.keySet();
        //float[] points = new float[loc_keys.size() * 2];
        Paint myPaint = new Paint();
        myPaint.setColor(Color.BLACK);
        myPaint.setStyle(Paint.Style.STROKE);
        myPaint.setStrokeJoin(Paint.Join.ROUND);
        myPaint.setStrokeWidth(4f);
        double[] lats = new double[loc_keys.size()];
        double[] lons = new double[loc_keys.size()];
        int i = 0;
        double minLat = 90;
        double maxLat = -90;
        double minLon = 180;
        double maxLon = -180;
        for(String key : loc_keys) {
            lats[i] = locations.get(key).lat;
            lons[i] = locations.get(key).lon;
            if(minLat > lats[i] ) {
                minLat = lats[i];
            }
            if(maxLat < lats[i]) {
                maxLat = lats[i];
            }
            if(minLon > lons[i]) {
                minLon = lons[i];
            }
            if(maxLon < lons[i]) {
                maxLon = lons[i];
            }
            i++;
        }
        double latDistance = 0.1;
        double lonDistance = 0.1;
        if(latDistance < maxLat - minLat) {
            latDistance = maxLat - minLat;
        }
        if(lonDistance < maxLon - minLon) {
            lonDistance = maxLon - minLon;
        }
        for(String key : loc_keys) {
            float relLat = convertToRelative(locations.get(key).lat, minLat, latDistance);
            float relLon = convertToRelative(locations.get(key).lon, minLon, lonDistance);
            mapCanvas.drawText(key,relLat,relLon+3, myPaint);
            mapCanvas.drawPoint(relLat, relLon, myPaint);
        }
        //getLayoutInflater().inflate(R.id.map, mapCanvas.);
        //getLayoutInflater().inflate
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }
    
    private boolean isConnected() {
        boolean connected;
        ConnectivityManager connectivityManager = (ConnectivityManager)getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState() == NetworkInfo.State.CONNECTED ||
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState() == NetworkInfo.State.CONNECTED) {
            //we are connected to a network
            connected = true;
        }
        else
            connected = false;
        return connected;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id ==R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } /*else if(id == R.id.messageIndividual) {

        } */else if( id == R.id.map) {
//            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
//            builder.setTitle("Locations");
//            String location_string = "";
//            Set<String> keys = locations.keySet();
//            for(String key : keys)
//            {
//                location_string += key + ": " + locations.get(key).lat + ", " + locations.get(key).lon + "\r\n";
//            }
//            builder.setMessage(location_string);
//            builder.create().show();
            if(isConnected()) {
                ((MainActivity) getActivity()).openMap();
            }
            else {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)mapView.getLayoutParams();
                if(mapView.getVisibility() == View.VISIBLE) {
                    params.height=1;
                    params.width=1;
                    mapView.setLayoutParams(params);
                    mapView.setVisibility(View.INVISIBLE);
                } else {
                    hideKeyboard(getActivity());
                    params.height = LinearLayout.LayoutParams.MATCH_PARENT;
                    params.width = LinearLayout.LayoutParams.MATCH_PARENT;
                    mapView.setLayoutParams(params);
                    mapView.setVisibility(View.VISIBLE);
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    public static void hideKeyboard(Activity activity) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        //Find the currently focused view, so we can grab the correct window token from it.
        View view = activity.getCurrentFocus();
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = new View(activity);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public static void revealKeyboard(Activity activity) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        //Find the currently focused view, so we can grab the correct window token from it.
        View view = activity.getCurrentFocus();
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = new View(activity);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void updateCenterLocation() {
        Set<String> keys = locations.keySet();
        double total_lat = 0;
        double total_lon = 0;
        for(String key : keys)
        {
            if(!key.equals("Center") && !key.equals("My location")) {
                total_lat += locations.get(key).lat;
                total_lon += locations.get(key).lon;
            }
        }
        int count = keys.size() - (keys.contains("Center") ? 1 : 0) - (keys.contains("My location") ? 1 : 0);
        //Give the Center a dummy value for its id
        Loc loc = new Loc("Center",total_lat/count, total_lon/count);
        locations.put("Center", loc);
        if(null != mapView) {
            mapView.updateLocation(loc.id, loc.lat, loc.lon);
        }
    }

    private void updateLocations(Loc loc) {
        locations.put(loc.id + "", loc);
        updateCenterLocation();
        if(null != mapView) {
            mapView.updateLocation(loc.id, loc.lat, loc.lon);
        }
    }

    public void sendGPS(double lat, double lon)
    {
        if(connected != Connected.True) {
            return;
        }
        try {
            Loc loc = new Loc(myID, lat, lon);
            updateLocations(loc);
            locations.put("My location", loc);
            //updateCenterLocation();
            String str = begginningGPS + myID + "," + lat + "," + lon + endingGPS;
            SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            byte[] data = (str + newline).getBytes();
            //byte[] data = aes256Class.makeAes((str).getBytes(), Cipher.ENCRYPT_MODE);
            byte[] data = str.getBytes();
            send_sem.acquire();
            socket.write(data);
            send_sem.release();
        } catch (Exception e) {
            onSerialIoError(e);
            send_sem.release();
        }
    }

    private void forwardMessage(byte[] message) {
        try {
            send_sem.acquire();
            socket.write(message);
            send_sem.release();
        } catch (Exception e) {
            onSerialIoError(e);
            send_sem.release();
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            //Temporary way to save a name so others can see who is sending it.
            //Make this saveable and put in the opening of the app for the first time.
            SharedPreferences sharedPreferences = getActivity().getSharedPreferences(SHARED_PREFS,MODE_PRIVATE);
            String name = sharedPreferences.getString(NAME, "");
            if(name.equals("")) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle("Enter your name below.");
                final EditText input = new EditText(getContext());
                builder.setView(input);
                builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        myID = input.getText().toString();
                        // Do something with value!
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(NAME,myID);
                        editor.commit();
                    }
                });
                builder.show();

            } else {
                myID = name;
            }
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            String deviceName = device.getName() != null ? device.getName() : device.getAddress();
            status("connecting...");
            connected = Connected.Pending;
            socket = new SerialSocket();
            service.connect(this, "Connected to " + deviceName);
            socket.connect(getContext(), service, device);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
        socket.disconnect();
        socket = null;
    }

    private byte[] sendProtocol(String originalMessage, String target) {
        //add who it is to be sent to
        Long tsLong = System.currentTimeMillis();
        String ts = tsLong.toString();
        String appendedTarget = messageStart + myID + "/" + ts +  ":" + target + ":" +  originalMessage + messageEnd;
        //uncomment below to enable encryption
        //byte[] temp = aes256Class.makeAes((appendedTarget).getBytes(), Cipher.ENCRYPT_MODE);
        byte[] temp = appendedTarget.getBytes();
        //The commented code below was to append the target before the message, mostly for encryption purposes
        /*byte[] retBytes = new byte[2 + temp.length];
        retBytes[0] = target;
        retBytes[1] = ' ';
        System.arraycopy(temp, 0, retBytes, 2, temp.length);*/
        confirmStrings.put(appendedTarget, false);
        return temp;
    }

    private String receiveProtocol(byte[] receivedMessage) {
        byte[] possible_gps = new byte[receivedMessage.length];
        System.arraycopy(receivedMessage, 0, possible_gps, 0, possible_gps.length);
        String gps_data = new String(possible_gps);
        Pattern gps_pattern = Pattern.compile(begginningGPS +"(.*?)" + endingGPS);
        Matcher matcher = gps_pattern.matcher(gps_data);
        Pattern message_pattern = Pattern.compile(messageStart + "(.*?)" + messageEnd);
        Matcher message_matcher = message_pattern.matcher(gps_data);
        if(matcher.find())
        {
            String location_data = matcher.group(1);
            String[] words = location_data.split(",");
            String gps_id = words[0];
            double lat = Double.parseDouble(words[1]);
            double lon = Double.parseDouble(words[2]);
            Loc loc = new Loc(gps_id, lat, lon);
            updateLocations(loc);
            //remove the processed data
            if((matcher = gps_pattern.matcher(gps_data)).find())
            {
                location_data = matcher.group(1);
                String removal = begginningGPS + location_data + endingGPS;
                gps_data = gps_data.replace(removal,"");
            }
            try {
            if(receive_sem.tryAcquire(1, TimeUnit.SECONDS)) {
                System.arraycopy(gps_data.getBytes(), 0, receivedBytes, 0, gps_data.getBytes().length);
                receivedBytesIndex = gps_data.length();
                receive_sem.release();
            }
            else {
                //receivedBytesIndex = 0;
                receive_sem.release();
            } } catch(Exception e) {
                receive_sem.release();
            }
            // Updated to have a mesh network fix
            if(!gps_id.contains(myID))
            {
                forwardMessage((begginningGPS + location_data.replace(gps_id, gps_id + "/" + myID) + endingGPS).getBytes());
            }
            return "";
        }
        else if(message_matcher.find())
        {
            String original_message = message_matcher.group(1);
            //This replace actually seems to be useless. I may refactor the code to remove this.
            String message = original_message.replace(messageStart, "").replace(messageEnd,"");
            String sender = message.substring(0, message.indexOf(':'));
            String afterSender = message.substring(message.indexOf(':') + 1);
            String target = afterSender.substring(0, afterSender.indexOf(':'));
            message = afterSender.substring(afterSender.indexOf(':') + 1);
            //encrypted code
            //byte[] encryptedData = new byte[receivedMessage.length - 2];
            //System.arraycopy(receivedMessage, 2, encryptedData, 0, encryptedData.length);
            //byte[] data = aes256Class.makeAes(encryptedData, Cipher.DECRYPT_MODE);
            //encryption code done

            //non-encrypted code
            //byte[] data = new byte[receivedMessage.length - 2];
            //System.arraycopy(receivedMessage, 2, data, 0, data.length);
            //non-encrypted code done

            byte[] data = message.getBytes();
            String decryptedString = sender.split("/")[0] + ": " + new String(data);
            //TODO fix the sendProtocol so it doesn't do this to make comparisons easier
            //String changeTarget = "" + target;
            //remove the processed data
            if((message_matcher = message_pattern.matcher(gps_data)).find())
            {
                String middle = message_matcher.group(1);
                String removal = messageStart + middle + messageEnd;
                gps_data = gps_data.replace(removal,"");
            }
            try {
            if(receive_sem.tryAcquire(1, TimeUnit.SECONDS)) {
                System.arraycopy(gps_data.getBytes(), 0, receivedBytes, 0, gps_data.getBytes().length);
                receivedBytesIndex = gps_data.length();
                receive_sem.release();
            }
            else {
                //receivedBytesIndex = 0;
                receive_sem.release();
            } } catch (Exception e) {
                receive_sem.release();
            }

            String persons = sender.split("/")[0];
            if(!persons.contains(myID)) { // Removed  && !target.equals("" + myID) because we now look for confirmation that messages were received.
                forwardMessage((messageStart + original_message.replace(persons, persons + "," + myID) + messageEnd).getBytes());
            }
            else if(isFirstID(myID, persons)) {
                confirmStrings.put(recreateOriginalMessage(message, sender.split("/")[1],target), true);
            }
            if((target.equals("" + myID) || target.equals(allTargets)) && checkSender(sender.split(",")[0]) && !sender.contains(myID)) {
                return decryptedString.trim() + "\n"; //make sure to append exactly one newline
            }
        }
        return null;
    }

    private String get_message(String withPattern) {
        String message = withPattern.replace(messageStart, "").replace(messageEnd,"");
        //String ts = message.substring(0, message.indexOf(':')).split("/")[1];
        String afterSender = message.substring(message.indexOf(':') + 1);
        //String target = afterSender.substring(0, afterSender.indexOf(':'));
        message = afterSender.substring(afterSender.indexOf(':') + 1);
        return message; // + "with timestamp: " + ts; // or I can specify who it was sent to
    }

    private boolean isFirstID(String id, String senders) {
        return id.equals(senders.split(",")[0]);
    }

    private String recreateOriginalMessage(String originalMessage, String ts, String target) {
        return messageStart + myID + "/" + ts +  ":" + target + ":" +  originalMessage + messageEnd;
        //message
        //return message.split(",")[0];
    }

    private boolean checkSender(String sender) {
        boolean newTimestamp = true;
        String senderID = sender.split("/")[0];
        String timestamp = sender.split("/")[1];
        if(timestamps.containsKey(senderID)) {
            ArrayList<String> entries = timestamps.get(senderID);
            if(entries.contains(timestamp)) {
                newTimestamp = false;
            }
            else {
                entries.add(timestamp);
                if(entries.size() > MAXKEPT) {
                    entries = (ArrayList<String>)entries.subList(MAXKEPT/2,entries.size());
                }
                timestamps.put(senderID, entries);
            }
        }
        else {
            ArrayList<String> newEntry = new ArrayList<>();
            newEntry.add(timestamp);
            timestamps.put(senderID, newEntry);
        }
        return newTimestamp;
    }

    private byte[] lastSent;
    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {

            SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
//            byte[] data = (str + newline).getBytes();
            //byte[] data = aes256Class.makeAes((str).getBytes(), Cipher.ENCRYPT_MODE);
            byte[] data = sendProtocol(str, myTarget);
            lastSent = data;
            send_sem.acquire();
            socket.write(data);
            sendText.setText("");
            send_sem.release();
        } catch (Exception e) {
            onSerialIoError(e);
            send_sem.release();
        }
    }

    private void receive(byte[] data) {
        try {
            if (receive_sem.tryAcquire(1, TimeUnit.SECONDS)) {
                System.arraycopy(data, 0, receivedBytes, receivedBytesIndex, data.length);
                receivedBytesIndex += data.length;
                receive_sem.release();
            } else {
                receive_sem.release();
            }
        } catch(Exception e) {
            receive_sem.release();
        }
        byte[] encryptedData = new byte[receivedBytesIndex];
        System.arraycopy(receivedBytes, 0, encryptedData, 0, receivedBytesIndex);

        try {
            //String temp = new String(aes256Class.makeAes(encryptedData, Cipher.DECRYPT_MODE));
            String temp = receiveProtocol(encryptedData);
            //if you have successfully decrypted the data, reset the index
            receiveText.append(temp);
        } catch(Exception e) {
            //Do nothing, we have only received part of the message and need the whole message to decrypt it properly.
            receive_sem.release();
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
