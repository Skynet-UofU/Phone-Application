package com.example.testing;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;

import com.exlyo.gmfmt.FloatingMarkerTitlesOverlay;
import com.exlyo.gmfmt.MarkerInfo;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Map;

import static com.example.testing.TerminalFragment.NAME;
import static com.example.testing.TerminalFragment.SHARED_PREFS;
import static com.example.testing.TerminalFragment.begginningDrone;
import static com.example.testing.TerminalFragment.endingDrone;
import static com.example.testing.TerminalFragment.goto_Drone;
import static com.example.testing.TerminalFragment.land_Drone;
import static com.example.testing.TerminalFragment.rth_Drone;
import static com.example.testing.TerminalFragment.send_sem;
import static com.example.testing.TerminalFragment.socket;
import static com.example.testing.TerminalFragment.takeoff_Drone;

/**
 * Created by User on 10/2/2017.
 */

public class GoogleMapView extends AppCompatActivity implements OnMapReadyCallback,
        GoogleApiClient.OnConnectionFailedListener{

    private FloatingMarkerTitlesOverlay floatingMarkersOverlay;

    private static boolean isActive = false;
    private String name;

    public static boolean isActivityVisible() {
        return isActive;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActive = false;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
//        Toast.makeText(this, "Map is Ready", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "onMapReady: map is ready");
        mMap = googleMap;

        //Initializing FloatingMarkerTitlesOverlay
        floatingMarkersOverlay = findViewById(R.id.map_floating_markers_overlay);
        floatingMarkersOverlay.setSource(googleMap);

        if (mLocationPermissionsGranted) {

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            mMap.setMyLocationEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(true);

            FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(this);
            locationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if(location != null) {
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(),
                            location.getLongitude()), 18F));
                    updateLocation(name, location.getLatitude(), location.getLongitude());
                }
            });
            init();
        }
    }

    private static final String TAG = "MapActivity";

    private static final String FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String COURSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1234;
    private static final float DEFAULT_ZOOM = 15f;

    private LocationManager locationManager;
    private LocationListener locationListener;

    //vars
    private Boolean mLocationPermissionsGranted = false;
    private FusedLocationProviderClient mFusedLocationProviderClient;
    private GoogleMap mMap = null;
    private boolean gotNewLoc = false;
    private Boolean mapLocInit = false;
    private String data;
    private ImageButton centerDrone;
    private ImageButton takeoffDrone;
    private ImageButton rthDrone;
    private ImageButton landDrone;

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String loc = intent.getStringExtra("loc");
        try {
            JSONObject locObj = new JSONObject(loc);
            updateLocation(locObj.getString("id"), locObj.getDouble("lat"), locObj.getDouble("lon"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private boolean sendDroneCommand(int type) {
        String str_cmd = "";
        try {
            String str = "";
            switch(type) {
                case 0:
                    str = begginningDrone + takeoff_Drone + name + ";" + endingDrone;
                    str_cmd = "take off";
                    break;
                case 1:
                    str = begginningDrone + rth_Drone + name + ";" + endingDrone;
                    str_cmd = "return to home";
                    break;
                case 2:
                    str = begginningDrone + land_Drone + name + ";" + endingDrone;
                    str_cmd = "land";
                    break;
                case 3:
                    Loc center = locations.contains("Center") ? locations.get("Center") : locations.get(name);
                    if(center != null) {
                        str = begginningDrone + goto_Drone + name + ";" + center.lat + ":" + center.lon + endingDrone;
                        str_cmd = "center";
                    }
                    break;
                default:
                    break;
            }

            byte[] data = str.getBytes();
            send_sem.acquire();
            socket.write(data);
            send_sem.release();
            Toast.makeText(this, "Sent drone command: '" + str_cmd + "'", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            send_sem.release();
            Toast.makeText(this, "Failed to send drone command: '" + str_cmd + "'", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.map_view);

        SharedPreferences sharedPreferences = this.getSharedPreferences(SHARED_PREFS,MODE_PRIVATE);
        name = sharedPreferences.getString(NAME, "");

        getLocationPermission();
        isActive = true;

        takeoffDrone = findViewById(R.id.takeoffDroneBtn);
        takeoffDrone.setOnClickListener(l -> {
            sendDroneCommand(0);
        });

        rthDrone = findViewById(R.id.rthDroneBtn);
        rthDrone.setOnClickListener(l -> {
            sendDroneCommand(1);
        });

        landDrone = findViewById(R.id.landDroneBtn);
        landDrone.setOnClickListener(l -> {
            sendDroneCommand(2);
        });

        centerDrone = findViewById(R.id.centerDroneBtn);
        centerDrone.setOnClickListener(l -> {
            sendDroneCommand(3);
        });

        // use this as a backup, just in case the other service gets killed when we start a new activity
//        //location setup
//        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
//        locationListener = new LocationListener() {
//            @Override
//            public void onLocationChanged(Location location) {
//                gotNewLoc = true;
//                if(!mapLocInit) {
//                  mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(),
//                          location.getLongitude()), 18F));
//                  mapLocInit = true;
//                }
//            }
//
//            @Override
//            public void onStatusChanged(String provider, int status, Bundle extras) {
//
//            }
//
//            @Override
//            public void onProviderEnabled(String provider) {
//
//            }
//
//            @Override
//            public void onProviderDisabled(String provider) {
//
//            }
//        };
//        locationManager.requestLocationUpdates("gps",10000,0, locationListener);
    }


    private void init(){
        Log.d(TAG, "init: initializing");
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//        if (requestCode == PLACE_PICKER_REQUEST) {
//            if (resultCode == RESULT_OK) {
//                Place place = PlacePicker.getPlace(this, data);
//
//                PendingResult<PlaceBuffer> placeResult = Places.GeoDataApi
//                        .getPlaceById(mGoogleApiClient, place.getId());
//                placeResult.setResultCallback(mUpdatePlaceDetailsCallback);
//            }
//        }
    }

    private void initMap(){
        Log.d(TAG, "initMap: initializing map");
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);

        mapFragment.getMapAsync(GoogleMapView.this);
    }

    private void getLocationPermission(){
        Log.d(TAG, "getLocationPermission: getting location permissions");
        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};

        if(ContextCompat.checkSelfPermission(this.getApplicationContext(),
                FINE_LOCATION) == PackageManager.PERMISSION_GRANTED){
            if(ContextCompat.checkSelfPermission(this.getApplicationContext(),
                    COURSE_LOCATION) == PackageManager.PERMISSION_GRANTED){
                mLocationPermissionsGranted = true;
                initMap();
            }else{
                ActivityCompat.requestPermissions(this,
                        permissions,
                        LOCATION_PERMISSION_REQUEST_CODE);
            }
        }else{
            ActivityCompat.requestPermissions(this,
                    permissions,
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult: called.");
        mLocationPermissionsGranted = false;

        switch(requestCode){
            case LOCATION_PERMISSION_REQUEST_CODE:{
                if(grantResults.length > 0){
                    for(int i = 0; i < grantResults.length; i++){
                        if(grantResults[i] != PackageManager.PERMISSION_GRANTED){
                            mLocationPermissionsGranted = false;
                            Log.d(TAG, "onRequestPermissionsResult: permission failed");
                            return;
                        }
                    }
                    Log.d(TAG, "onRequestPermissionsResult: permission granted");
                    mLocationPermissionsGranted = true;
                    //initialize our map
                    initMap();
                }
            }
        }
    }

    public class Loc {

        private double lat;
        private double lon;
        private String id;

        public Loc(String gps_id, double _lat, double _lon) {
            lat = _lat;
            lon = _lon;
            id = gps_id;
        }

        public boolean sameLoc(Loc other) {
            return lat == other.lat && lon == other.lon;
        }

        public boolean isUnique(ArrayList<Loc> locs) {
            boolean isUnique = true;
            for(Loc other : locs) {
                if(sameLoc(other)) {
                    isUnique = false;
                }
            }
            return isUnique;
        }
    }

    public void updateLocation(String id, double lat, double lon) {
        if(locations == null) {
            locations = new Hashtable<>();
        }
        locations.put(id + "", new Loc(id, lat, lon));
        updateMap();
    }

    public void updateMap() {
        if(mMap != null) {
            int id = 0;
            mMap.clear();
            floatingMarkersOverlay.clearMarkers();
            for (Map.Entry<String, Loc> entry : locations.entrySet()) {
                String key = entry.getKey();
                Loc value = entry.getValue();
                LatLng location = new LatLng(value.lat, value.lon);
                MarkerInfo mi = new MarkerInfo(location, value.id, Color.RED);
                mMap.addMarker(new MarkerOptions().position((mi.getCoordinates())));

                floatingMarkersOverlay.addMarker(id, mi);
            }
        }
    }

    private Hashtable<String, Loc> locations;
}