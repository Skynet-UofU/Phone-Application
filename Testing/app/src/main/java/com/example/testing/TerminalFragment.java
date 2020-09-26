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
import android.widget.EditText;
import android.widget.ImageButton;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

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
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
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
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        ImageButton test_one_button = (ImageButton) view.findViewById(R.id.test_1);
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
        ImageButton test_two_button = (ImageButton) view.findViewById(R.id.test_2);
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

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
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
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Locations");
            String location_string = "";
            Set<String> keys = locations.keySet();
            for(String key : keys)
            {
                location_string += key + ": " + locations.get(key).lat + ", " + locations.get(key).lon + "\r\n";
            }
            builder.setMessage(location_string);
            builder.create().show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void updateCenterLocation() {
        Set<String> keys = locations.keySet();
        double total_lat = 0;
        double total_lon = 0;
        for(String key : keys)
        {
            if(!key.equals("Center")) {
                total_lat += locations.get(key).lat;
                total_lon += locations.get(key).lon;
            }
        }
        int count = keys.size() - (keys.contains("Center") ? 1 : 0);
        //Give the Center a dummy value for its id
        Loc loc = new Loc("-1",total_lat/count, total_lon/count);
        locations.put("Center", loc);
    }

    private void updateLocations(Loc loc) {
        locations.put(loc.id + "", loc);
        updateCenterLocation();
    }

    public void sendGPS(double lat, double lon)
    {
        if(connected != Connected.True) {
            return;
        }
        try {
            Loc loc = new Loc(myID, lat, lon);
            locations.put("My location", loc);
            updateCenterLocation();
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
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Enter your name below.");
            final EditText input = new EditText(getContext());
            builder.setView(input);
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    myID = input.getText().toString();
                    // Do something with value!
                }
            });
            builder.show();
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
            if(receive_sem.tryAcquire()) {
                System.arraycopy(gps_data.getBytes(), 0, receivedMessage, 0, gps_data.getBytes().length);
                receive_sem.release();
                receivedBytesIndex = gps_data.length();
            }
            else {
                receivedBytesIndex = 0;
            }
            if(!myID.equals(gps_id))
            {
                forwardMessage((begginningGPS + location_data + endingGPS).getBytes());
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
            if(receive_sem.tryAcquire()) {
                System.arraycopy(gps_data.getBytes(), 0, receivedMessage, 0, gps_data.getBytes().length);
                receive_sem.release();
                receivedBytesIndex = gps_data.length();
            }
            else {
                receivedBytesIndex = 0;
            }

            if(!sender.contains(myID) && !target.equals("" + myID)) {
                forwardMessage((messageStart + original_message.replace(sender, sender + "," + myID) + messageEnd).getBytes());
            }
            if((target.equals("" + myID) || target.equals(allTargets)) && checkSender(sender.split(",")[0])) {
                return decryptedString;
            }
        }
        return null;
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
        if(receive_sem.tryAcquire()) {
            System.arraycopy(data, 0, receivedBytes, receivedBytesIndex, data.length);
            receivedBytesIndex += data.length;
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
