package com.example.testing

import android.annotation.SuppressLint

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng

class Map : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var map: GoogleMap
    var location: Location? = null
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0
    var locationManager: LocationManager? = null
    lateinit var locationListener: LocationListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.map_view)
        var mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        //location setup
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if(location != null) { val latLng = LatLng(location.latitude, location.longitude)
                    map?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13F))
                }
            }

            override fun onStatusChanged(
                    provider: String,
                    status: Int,
                    extras: Bundle
            ) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    private fun centerLoc() {
        try {
            var loc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc == null) {
                // Request location updates
                locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L,
                        0f,
                        locationListener
                )
            } else {
                var location = LatLng(loc.latitude, loc.longitude)
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 13F))
            }
        } catch (ex: SecurityException) {
            Log.d("myTag", "Security Exception, no location available")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(p0: GoogleMap?) {
        map = p0!!
        map.isMyLocationEnabled = true;
        centerLoc()
    }
}