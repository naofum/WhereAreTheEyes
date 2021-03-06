package org.daylightingsociety.wherearetheeyes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.location.*;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mapbox.mapboxsdk.MapboxAccountManager;
import com.mapbox.mapboxsdk.annotations.Marker;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.constants.Style;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;

public class MainActivity extends Activity {
    private static ImageButton camera = null;
    private static MapView mapView = null;
    private static GPS gps = null;
    private static LinearLayout scorebar = null;
    private static TextView usernameLabel = null;
    private static TextView cameraScore = null;
    private static TextView verificationScore = null;
    private static Score score = null;
    private static final int LOCATION_PERMS_REQUEST = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Location l = null;
        camera = (ImageButton) findViewById(R.id.camera);
        mapView = (MapView) findViewById(R.id.map);
        scorebar = (LinearLayout) findViewById(R.id.scoreBar);
        usernameLabel = (TextView) findViewById(R.id.username);
        cameraScore = (TextView) findViewById(R.id.camera_score);
        verificationScore = (TextView) findViewById(R.id.verification_score);
        score = new Score(this, cameraScore, verificationScore);

        Log.d("Main", "I solemnly swear that I am up to no good...");


        // Request location permissions if needed, and pull in last known location if possible
        acquireLocationPerms();

        // Set up the GPS to receive location updates
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        gps = new GPS(mapView);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, gps);
            boolean netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (netEnabled)
                l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            // The user has already been alerted that we need permissions.
            // They'll just have to fix the problem.
        }


        // Tell the GPS to pull in pins for our last known location,
        // if we have any idea of where we are at all.
        if( l != null )
            gps.onLocationChanged(l);

        // Configure our access token and tileset for the mapView
        // In the future MapboxAccountManager will be necessary, but for now only setAccessToken
        // seems to work, so we'll just use them both.
        MapboxAccountManager.start(this, Constants.APIKEY);
        mapView.setAccessToken(Constants.APIKEY);

        // Set whatever style the user prefers
        if( satelliteMapEnabled() )
            mapView.setStyle(Style.SATELLITE_STREETS);
        else
            mapView.setStyleUrl(Style.MAPBOX_STREETS);

        // This block does some initialization for the map, instead of the mapview
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(MapboxMap mapboxMap) {
                // Callback to an empty function on FPS changes
                mapboxMap.setOnFpsChangedListener(new EyesOnFPSChangedListener());

                // Put a little blue marker on our position
                mapboxMap.setMyLocationEnabled(true);

                // Set a callback to create our custom info window when a marker is tapped
                mapboxMap.setInfoWindowAdapter(getInfoWindowHandler());

                // This whole mess is to get the map to autoscroll to the user's location on start
                Location l = null;
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                boolean netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                try {
                    if (netEnabled)
                        l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } catch( SecurityException e ) {
                    acquireLocationPerms();
                }
                if( l != null ) {
                    CameraPosition pos = new CameraPosition.Builder()
                            .target(new LatLng(l.getLatitude(), l.getLongitude()))
                            .zoom(14.0)
                            .tilt(0)
                            .build();
                    mapboxMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
                }
            }
        });


        // Set up the image cache for pins
        Images.init(this);

        // This finally creates the map.
        // Note: Access token MUST be set before this line
        mapView.onCreate(savedInstanceState);

        if( score.scoresEnabled(getUsername()) ) {
            drawScores();
            score.updateScore(getUsername());
        } else {
            scorebar.setVisibility(View.INVISIBLE);
        }
    }

    // This returns a codeblock to make the little info windows for pins
    // It's in an isolated function because it's a mess and I want to contain it.
    public MapboxMap.InfoWindowAdapter getInfoWindowHandler() {
        return new MapboxMap.InfoWindowAdapter() {
            @Override
            public View getInfoWindow(final Marker marker) {
                View infoWindow = LayoutInflater.from(MainActivity.this).inflate(R.layout.pin_infobox, null);
                ImageButton info = (ImageButton) infoWindow.findViewById(R.id.marker_info);
                TextView title = (TextView) infoWindow.findViewById(R.id.tooltip_title);
                final LatLng coord = marker.getPosition();
                final Location loc = new Location("");
                loc.setLatitude(coord.getLatitude());
                loc.setLongitude(coord.getLongitude());
                title.setText(marker.getTitle());

                // When info button is pressed, pop up a dialog for removal.
                info.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_HOLO_DARK);
                        builder.setTitle(marker.getTitle())
                                .setMessage(R.string.this_is_a_camera)
                                .setCancelable(false)
                                .setNegativeButton(R.string.dismiss, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        dialog.dismiss();
                                    }
                                })
                                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        String username = getUsername();
                                        if( username.length() == 0 ) {
                                            alertNoUsername();
                                        } else {
                                            new UnmarkPin().execute(new MarkData(username, loc, MainActivity.this, MainActivity.this));
                                            gps.refreshPins();
                                        }
                                        dialog.dismiss();
                                    }
                                });
                        AlertDialog removePin = builder.create();
                        removePin.show();
                    }
                });
                return infoWindow;
            }
        };
    }

    // Draws the scorebar if we have a valid username, hides it otherwise
    public void drawScores() {
        String username = getUsername();
        if( username.length() > 0 ) {
            scorebar.setVisibility(View.VISIBLE);
            usernameLabel.setText(getUsername());
        } else {
            scorebar.setVisibility(View.INVISIBLE);
        }
    }

    public String getUsername() {
        Context context = MainActivity.this;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String username = prefs.getString("username_preference", "");
        return username;
    }

    public Boolean satelliteMapEnabled() {
        Context context = MainActivity.this;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("use_satellite_map", false);
    }

    // Centers the map over the last known user location
    public void recenterMap() {
        final Location l = gps.getLocation();

        // This shouldn't normally happen, but does on the Android emulator.
        // It only occurs if recenterMap is called before we receive our first
        // gps ping. We normally receive a ping within a second or two of app
        // launch, but *not* if the emulator only pings on demand.
        if( l == null )
            return;

        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(MapboxMap mapboxMap) {
                // We'll maintain zoom level and tilt, just want to change position
                CameraPosition old = mapboxMap.getCameraPosition();
                CameraPosition pos = new CameraPosition.Builder()
                        .target(new LatLng(l.getLatitude(), l.getLongitude()))
                        .zoom(old.zoom)
                        .tilt(old.tilt)
                        .build();
                mapboxMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
            }
        });
    }

    public void alertNoUsername() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_HOLO_DARK);
        builder.setMessage(R.string.no_username_alert)
                .setCancelable(false)
                .setNegativeButton(R.string.okay, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                })
                .setPositiveButton(R.string.register, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + Constants.REGISTER_URL));
                        startActivity(browserIntent);
                    }
                });
        AlertDialog noUsername = builder.create();
        noUsername.show();
    }

    // This is called when the eye button is pressed. It does all the config validation before
    // we actually go make a network request.
    public void markOrVerifyCamera(View view) {
        // Get the current username from preferences
        Context context = MainActivity.this;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String username = prefs.getString("username_preference", "");
        boolean confirmMarking = prefs.getBoolean("confirm_marking_pins", true);

        // No username is set yet, make the users go set one.
        if (username.length() == 0) {
            alertNoUsername();
            return;
        }

        // Always recenter when marking a pin so users aren't misled about where it will go
        recenterMap();

        // Put up the dialog to confirm marking pins
        if (confirmMarking) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK);
            builder.setTitle(R.string.confirm)
                    .setMessage(R.string.mark_camera_confirmation)
                    .setCancelable(false)
                    .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            commitMarkingCamera(username);
                            dialog.cancel();
                        }
                    })
                    .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });
            AlertDialog noUsername = builder.create();
            noUsername.show();
        } else {
            commitMarkingCamera(username);
        }
    }

    // This is called once we have validated our config and know we want to mark a pin
    public void commitMarkingCamera(String username)
    {
        Log.d("CAMERA_BUTTON", "Marking with username: " + username);

        // Mark the new pin, then re-download pins so it will show up
        new MarkOrVerifyPin().execute(new MarkData(username, gps.getLocation(), MainActivity.this, this));
        gps.refreshPins();
        if( score.scoresEnabled(getUsername()) )
            score.updateScore(getUsername());

        // Set the eyecon to a dimmer color for a bit so it looks like we clicked it
        try {
            camera.setImageResource(R.drawable.eye_faded);
            Thread.sleep(300);
        }catch(Exception e) {
            Log.d("CAMERA_BUTTON", "Aborted sleep");
        }
        camera.setImageResource(R.drawable.eye);
    }

    public void openSettings(View view) {
        Intent settings = new Intent(this, SettingsActivity.class);
        startActivity(settings);
    }

    public void openHelp(View view) {
        Intent help = new Intent(this, HelpActivity.class);
        startActivity(help);
    }

    // Put up a little prompt and then redirect users to settings to give us more power
    private void acquireLocationPerms() {
        // Only ask for permission (and get a callback) if we don't already have it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED)
            return;

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                LOCATION_PERMS_REQUEST);
    }

    // The user hit 'accept or deny' on giving us location perms, now we need to process that
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case LOCATION_PERMS_REQUEST: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // Permission was granted, yay!
                    // Now we need to restart the main activity, so that the map can be initialized
                    // with the user's location.
                    this.recreate();

                } else {
                    // Permission denied, boo! We can't run without location data, so let's tell
                    // the user what they've done wrong.
                    final Activity activity = this;

                    /*
                        Put up a dialog explaining why we need permissions and give the user
                        the option to go to settings and enable location. After the user presses
                        either button we check if we suddenly have location access, and restart
                        the main activity if we do.

                        The check for permissions and the activity restart *must* both occur inside
                        the button click handlers. This is because the dialog is created
                        asynchronously, so we can't just put the if statement down below.
                     */
                    AlertDialog.Builder needPerms = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK);
                    needPerms.setMessage(R.string.needs_location_explanation);
                    needPerms.setCancelable(false);
                    needPerms.setPositiveButton(
                            R.string.view_permissions_button,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    // Redirect the user to the location settings screen
                                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                                    // If the user just gave us permissions then it's time to restart.
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                                            == PackageManager.PERMISSION_GRANTED) {
                                        Log.d("REQUESTING PERMISSIONS", "We didn't have location permission, but the user fixed it!");
                                        activity.recreate();
                                    }
                                }
                            });
                    needPerms.setNegativeButton(
                            R.string.dismiss,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                                            == PackageManager.PERMISSION_GRANTED) {
                                        Log.d("REQUESTING PERMISSIONS", "We didn't have location permission, but the user fixed it!");
                                        activity.recreate();
                                    }
                                }
                            });
                    needPerms.show();
                    // Finally done with that mess.

                }
                return;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        recenterMap();

        // If we were just brought out of the background then it's probably
        // time to check for more pins.
        gps.refreshPins();

        if( score.scoresEnabled(getUsername()) ) {
            drawScores();
        } else {
            scorebar.setVisibility(View.INVISIBLE);
        }

        // Set the correct style if it's changed in preferences
        // We do this in onResume() so when we return from the Settings activity we'll immediately
        // load the new style.
        String style = mapView.getStyleUrl();
        if( satelliteMapEnabled() && style.equals(Style.MAPBOX_STREETS) )
            mapView.setStyle(Style.SATELLITE_STREETS);
        else if( !satelliteMapEnabled() && style.equals(Style.SATELLITE_STREETS) )
            mapView.setStyle(Style.MAPBOX_STREETS);

    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
        Log.d("Main", "Mischief managed.");
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}