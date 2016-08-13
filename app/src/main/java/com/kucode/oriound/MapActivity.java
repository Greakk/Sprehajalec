package com.kucode.oriound;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.overlays.Marker;
import org.osmdroid.bonuspack.overlays.Polyline;
import org.osmdroid.bonuspack.routing.MapQuestRoadManager;
import org.osmdroid.bonuspack.routing.Road;
import org.osmdroid.bonuspack.routing.RoadManager;
import org.osmdroid.bonuspack.routing.RoadNode;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.NetworkLocationIgnorer;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.DirectedLocationOverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MapActivity extends ActionBarActivity implements SensorEventListener, LocationListener {
    // - VARIABLES -
    private MapView map;
    private LocationManager locationManager;
    private DirectedLocationOverlay currentLocationOverlay;
    private AddressResultReceiver addressResultReceiver;
    private RelativeLayout progressBarWrapper;
    private LinearLayout buttonsWrapper;
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private ShakeManager shakeManager;

    public static final String TAG = "MapActivity";

    // - DATA VARIABLES -
    protected Location mCurrentLocation;
    protected String destinationAddress;
    protected boolean fetchAddressRunning = false;
    protected boolean mNavigationRunning = false;
    protected boolean navigationMode;
    private Road mRoad;
    private RoadNode mNextNode;
    private int mDistance;
    private String mInstruction;
    private int mDirection;

    // - OnCreate, OnResume, OnPause -
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.map_activity_layout);

        buttonsWrapper = (LinearLayout)findViewById(R.id.mapButtonsWrapper);
        progressBarWrapper = (RelativeLayout)findViewById(R.id.progressBarWrapper);

        // Checking if map is called with destination address
        String inDestinationAddress = getIntent().getStringExtra("destination_address");
        if (inDestinationAddress != null && !inDestinationAddress.isEmpty()) {
            navigationMode = true;
            destinationAddress = inDestinationAddress;

            // Setting progress bar to active until route is found
            progressBarWrapper.setVisibility(View.VISIBLE);
        }

        Typeface tf = Typeface.createFromAsset(getAssets(), Constants.FONT_PATH);
        ((Button)findViewById(R.id.current_location_button)).setTypeface(tf);
        ((Button)findViewById(R.id.current_instruction_button)).setTypeface(tf);

        // Setting volume control stream to media
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Initializing components
        initializeMap();

        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        shakeManager = new ShakeManager();
        shakeManager.setListener(new ShakeManager.ShakeListener() {

            @Override
            public void onShake() {
                buttonsWrapper.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        boolean providerEnabled = startLocationUpdates();
        currentLocationOverlay.setEnabled(providerEnabled);
        sensorManager.registerListener(shakeManager, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override protected void onPause() {
        sensorManager.unregisterListener(shakeManager);
        locationManager.removeUpdates(this);
        super.onPause();
    }

    // - Initializing map and it's components -
    private void initializeMap() {
        // - Map -
        map = (MapView) this.findViewById(R.id.map);
        //map.setBuiltInZoomControls(true);
        map.setMultiTouchControls(true);
        // Tiles for the map, can be changed
        map.setTileSource(TileSourceFactory.MAPNIK);

        // - Map controller -
        IMapController mapController = map.getController();
        mapController.setZoom(19);

        // - Map overlays -
        CustomResourceProxy crp = new CustomResourceProxy(getApplicationContext());
        currentLocationOverlay = new DirectedLocationOverlay(this, crp);

        map.getOverlays().add(currentLocationOverlay);

        // - Location -
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) != null) {
            onLocationChanged(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
        }
        else if (locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) != null) {
            onLocationChanged(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
        }
        else {
            currentLocationOverlay.setEnabled(false);
        }
    }

    // - LOCATION -
    boolean startLocationUpdates() {
        boolean providerExists = false;
        for (String locationProvider : locationManager.getProviders(true)) {
            locationManager.requestLocationUpdates(locationProvider, 2*1000, 0.0f, this);
            providerExists = true;
        }
        return providerExists;
    }

    // - Location changed listener -
    private final NetworkLocationIgnorer networkLocationIgnorer = new NetworkLocationIgnorer();
    long mLastTime = 0; // milliseconds
    @Override public void onLocationChanged(Location location) {
        mCurrentLocation = location;

        long currentTime = System.currentTimeMillis();

        if (networkLocationIgnorer.shouldIgnore(location.getProvider(), currentTime))
            return;

        double dT = currentTime - mLastTime;
        if (dT < 100.0){
            return;
        };

        mLastTime = currentTime;

        if (!currentLocationOverlay.isEnabled()){
            // Location for the first time -> enable current location overlay
            currentLocationOverlay.setEnabled(true);
        }

        map.getController().animateTo(new GeoPoint(location));
        currentLocationOverlay.setLocation(new GeoPoint(location));
        currentLocationOverlay.setBearing(location.getBearing());
        currentLocationOverlay.setAccuracy((int) location.getAccuracy());
        map.invalidate();

        // If in navigation mode
        if (navigationMode) {
            if(!mNavigationRunning) {
                // Location for the first time -> get road
                mNavigationRunning = true;

                ArrayList<GeoPoint> wayPoints = new ArrayList<GeoPoint>();
                // Start location
                wayPoints.add(new GeoPoint(location));
                // Destination location
                wayPoints.add(getLocationFromAddress(destinationAddress));

                new RoadTask().execute(wayPoints);
            }
            else if (mNextNode != null) {
                GeoPoint currentGeoPoint = new GeoPoint(mCurrentLocation);

                int distance = currentGeoPoint.distanceTo(mNextNode.mLocation);
                int direction = mNextNode.mManeuverType;

                // Set new instruction
                setInstruction(distance, direction);

                if ( distance < 1 ) {
                    // Node reached, get next node
                    //if (mRoad.mNodes.size() >= )
                    mNextNode = mRoad.mNodes.get(1);
                }
            }
        }
    }

    /*

NONE	0	No maneuver occurs here.
STRAIGHT	1	Continue straight.
BECOMES	2	No maneuver occurs here. Road name changes.
SLIGHT_LEFT	3	Make a slight left.
LEFT	4	Turn left.
SHARP_LEFT	5	Make a sharp left.
SLIGHT_RIGHT	6	Make a slight right.
RIGHT	7	Turn right.
SHARP_RIGHT	8	Make a sharp right.
STAY_LEFT	9	Stay left.
STAY_RIGHT	10	Stay right.
STAY_STRAIGHT	11	Stay straight.
UTURN	12	Make a U-turn.
UTURN_LEFT	13	Make a left U-turn.
UTURN_RIGHT	14	Make a right U-turn.
     */

    public void setInstruction (int distance, int direction) {
        String instruction = "Čez " + distance + " metrov " + direction;

        Button button = (Button)findViewById(R.id.current_instruction_button);
        button.setText(instruction);
        button.setVisibility(View.VISIBLE);
    }

    public GeoPoint getLocationFromAddress(String strAddress){

        Geocoder geocoder = new Geocoder(this);

        try {
            List<Address> address = geocoder.getFromLocationName(strAddress,1);
            if (address == null) {
                return null;
            }
            Address location = address.get(0);
            location.getLatitude();
            location.getLongitude();

            return new GeoPoint((int) (location.getLatitude() * 1E6),
                    (int) (location.getLongitude() * 1E6));
        }
        catch (IOException e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
    }

    // - Sensor listeners -
    @Override
    public void onSensorChanged(SensorEvent event) {

    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        currentLocationOverlay.setAccuracy(accuracy);
        map.invalidate();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    // - Road -
    private class RoadTask extends AsyncTask<Object, Void, Road> {

        protected Road doInBackground(Object... params) {
            ArrayList<GeoPoint> waypoints = (ArrayList<GeoPoint>)params[0];
            RoadManager roadManager = new MapQuestRoadManager(Constants.MAPQUEST_API_KEY);
            roadManager.addRequestOption("routeType=pedestrian");

            if(checkInternetConnection(getBaseContext())) {
                try {
                    Road road = roadManager.getRoad(waypoints);
                    return road;
                }
                catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                    return null;
                }
            }
            else {
                Log.e(TAG,"No connection");
                return null;
            }
        }

        protected void onPostExecute(Road result) {
            mRoad = result;
            mNextNode = result.mNodes.get(0);
            showRoadOnMap(result);
            progressBarWrapper.setVisibility(View.GONE);
        }
    }

    public Boolean checkInternetConnection(Context con){
        ConnectivityManager connectivityManager= null;
        NetworkInfo wifiInfo, mobileInfo;

        try{
            connectivityManager = (ConnectivityManager) con.getSystemService(Context.CONNECTIVITY_SERVICE);
            wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            mobileInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if(wifiInfo.isConnected() || mobileInfo.isConnected())
            {
                return true;
            }
        }
        catch(Exception e){
            Log.e(TAG, e.getMessage());
        }

        return false;
    }

    public void showRoadOnMap(Road road) {
        if (road == null){
            Log.e(TAG, "Road is null");
        } else{
            if (road.mStatus != Road.STATUS_OK){
                Log.e(TAG, "Road status " + road.mStatus);
            }else{
                Polyline roadOverlay = RoadManager.buildRoadOverlay(road, this);
                map.getOverlays().add(roadOverlay);

                for (RoadNode node : road.mNodes) {
                    Marker nodeMarker = new Marker(map);
                    nodeMarker.setPosition(node.mLocation);
                    nodeMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                    nodeMarker.setIcon(getResources().getDrawable(R.drawable.ic_node_2));
                    nodeMarker.setInfoWindow(null);
                    map.getOverlays().add(nodeMarker);
                }

                //map.getOverlays().remove(currentLocationOverlay);
                //map.getOverlays().add(currentLocationOverlay);

                map.invalidate();

                RoadNode node = road.mNodes.get(0);

                Button button = (Button)findViewById(R.id.current_instruction_button);
                String instructions = (node.mInstructions==null ? "" : node.mInstructions);
                button.setText(instructions);
                button.setVisibility(View.VISIBLE);
            }
        }
    }

    // Text transform for TTS
    private String transformText(String text) {
        // TODO: Transform text for tts
        StringBuilder sb = new StringBuilder();
        text = text.toLowerCase();
        for(int i =0; i<text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'c' :
                    sb.append("ts");
                    break;
                case 'č' :
                    sb.append("ch");
                    break;
                case 'š' :
                    sb.append("sh");
                    break;
                case 'ž' :
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    // OTHER
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    // ADDRESS FETCH
    // Starting FetchAddressIntentService
    protected void startFetchAddressIS() {
        Intent intent = new Intent(this, FetchAddressIntentService.class);
        addressResultReceiver = new AddressResultReceiver(new Handler());
        intent.putExtra(Constants.RECEIVER, addressResultReceiver);
        intent.putExtra(Constants.LOCATION_DATA_EXTRA, mCurrentLocation);
        startService(intent);
    }

    // Address receiver for FetchAddressIntentService
    class AddressResultReceiver extends ResultReceiver {
        public AddressResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            Intent serviceIntent = new Intent(getBaseContext(), TTSService.class);
            if (resultCode == Constants.SUCCESS_RESULT) {
                serviceIntent.putExtra("text", (resultData.getString(Constants.RESULT_DATA_KEY)));
                startService(serviceIntent);
            }
            else if(resultCode == Constants.FAILURE_RESULT) {
                serviceIntent.putExtra("text", ("Naslov ni najden"));
                startService(serviceIntent);
            }
            fetchAddressRunning = false;
        }
    }

    // TRENUTNA LOKACIJA
    public void onCurrentStreetClick(View view) {
        buttonsWrapper.setVisibility(View.GONE);
        if(mCurrentLocation !=null && !fetchAddressRunning) {
            fetchAddressRunning = true;
            startFetchAddressIS();
        }
    }

    // - Trenutno navodilo za pot -
    public void onDirectionClick(View view) {
        buttonsWrapper.setVisibility(View.GONE);
        Intent serviceIntent = new Intent(getBaseContext(), TTSService.class);
        serviceIntent.putExtra("text", ((Button)view).getText());
        startService(serviceIntent);
    }
}