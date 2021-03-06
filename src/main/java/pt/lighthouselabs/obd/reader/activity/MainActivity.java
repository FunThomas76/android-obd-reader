package pt.lighthouselabs.obd.reader.activity;

import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.google.inject.Inject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pt.lighthouselabs.obd.commands.ObdCommand;

import pt.lighthouselabs.obd.enums.AvailableCommandNames;
import pt.lighthouselabs.obd.reader.ObdProgressListener;
import pt.lighthouselabs.obd.reader.R;
import pt.lighthouselabs.obd.reader.io.AbstractGatewayService;
import pt.lighthouselabs.obd.reader.io.MockObdGatewayService;
import pt.lighthouselabs.obd.reader.io.ObdCommandJob;
import pt.lighthouselabs.obd.reader.io.ObdGatewayService;
import pt.lighthouselabs.obd.reader.net.ObdReading;
import pt.lighthouselabs.obd.reader.net.ObdService;
import pt.lighthouselabs.obd.reader.config.ObdConfig;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;
import roboguice.activity.RoboActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;

// Some code taken from https://github.com/barbeau/gpstest

@ContentView(R.layout.main)
public class MainActivity extends RoboActivity implements ObdProgressListener, LocationListener, GpsStatus.Listener {

  private static boolean bluetoothDefaultIsEnable = false;

  public Map<String, String> commandResult = new HashMap<String, String>();

  boolean mGpsIsStarted = false;
  private LocationManager mLocService;
  private LocationProvider mLocProvider;
  private GpsStatus mGpsStatus;
  private Location mLastLocation;

  // todo movo to Preferences
  private long gpsMinTime = 1000; // Min Time between location updates, in milliseconds
  private float gpsMinDistance = 3; // Min Distance between location updates, in meters

  private static final String TAG = MainActivity.class.getName();
  private static final int NO_BLUETOOTH_ID = 0;
  private static final int BLUETOOTH_DISABLED = 1;
  private static final int START_LIVE_DATA = 2;
  private static final int STOP_LIVE_DATA = 3;
  private static final int SETTINGS = 4;
  private static final int GET_DTC = 5;
  private static final int TABLE_ROW_MARGIN = 7;
  private static final int NO_ORIENTATION_SENSOR = 8;
  private static final int NO_GPS_SUPPORT = 9;

  private final SensorEventListener orientListener = new SensorEventListener() {
    public void onSensorChanged(SensorEvent event) {
      float x = event.values[0];
      String dir = "";
      if (x >= 337.5 || x < 22.5) {
        dir = "N";
      } else if (x >= 22.5 && x < 67.5) {
        dir = "NE";
      } else if (x >= 67.5 && x < 112.5) {
        dir = "E";
      } else if (x >= 112.5 && x < 157.5) {
        dir = "SE";
      } else if (x >= 157.5 && x < 202.5) {
        dir = "S";
      } else if (x >= 202.5 && x < 247.5) {
        dir = "SW";
      } else if (x >= 247.5 && x < 292.5) {
        dir = "W";
      } else if (x >= 292.5 && x < 337.5) {
        dir = "NW";
      }
      updateTextView(compass, dir);
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
      // do nothing
    }
  };

  private final Runnable mQueueCommands = new Runnable() {
    public void run() {
      if (service!=null && service.isRunning() && service.queueEmpty()) {
        queueCommands();

        double lat = 0;
        double lon = 0;
        if(mGpsIsStarted && mLastLocation != null) {
          lat = mLastLocation.getLatitude();
          lon = mLastLocation.getLongitude();

          TextView gpsPos = (TextView) vv.findViewWithTag("GPS_POS");
          StringBuffer sb = new StringBuffer();
          sb.append("Lat: ");
          sb.append(mLastLocation.getLatitude());
          sb.append(" Lon: ");
          sb.append(mLastLocation.getLongitude());
          gpsPos.setText(sb.toString());
        }

        if (prefs.getBoolean(ConfigActivity.UPLOAD_DATA_KEY, false)) {
          final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
          Map<String, String> temp = new HashMap<String, String>();
          temp.putAll(commandResult);
          ObdReading reading = new ObdReading(lat, lon, System.currentTimeMillis(), vin, temp);
          new UploadAsyncTask().execute(reading);
        }
        commandResult.clear();
      }
      // run again in period defined in preferences
      new Handler().postDelayed(mQueueCommands, ConfigActivity.getUpdatePeriod(prefs));
    }
  };
  @InjectView(R.id.compass_text)
  private TextView compass;

  @InjectView(R.id.vehicle_view)
  private LinearLayout vv;

  @InjectView(R.id.data_table)
  private TableLayout tl;
  @Inject
  private SensorManager sensorManager;
  @Inject
  private PowerManager powerManager;
  @Inject
  private SharedPreferences prefs;
  private boolean isServiceBound;

  private AbstractGatewayService service;
  private ServiceConnection serviceConn = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
      Log.d(TAG, className.toString()  + " service is bound");
      isServiceBound = true;
      service = ((AbstractGatewayService.AbstractGatewayServiceBinder)binder).getService();
      service.setContext(MainActivity.this);
      Log.d(TAG, "Starting live data");
      try { service.startService(); }
      catch ( IOException ioe) {
          Log.e(TAG, "Failure Starting live data");
          doUnbindService();
        }
    }

    // This method is *only* called when the connection to the service is lost unexpectedly
    // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
    // So the isServiceBound attribute should also be set to false when we unbind from the service.
    @Override
    public void onServiceDisconnected(ComponentName className) {
      Log.d(TAG, className.toString()  + " service is unbound");
      isServiceBound = false;
    }
  };

  private Sensor orientSensor = null;
  private PowerManager.WakeLock wakeLock = null;
  private boolean preRequisites = true;

  public void updateTextView(final TextView view, final String txt) {
    new Handler().post(new Runnable() {
      public void run() {
        view.setText(txt);
      }
    });
  }
   public static String LookUpCommand(String txt) {
        for (AvailableCommandNames item : AvailableCommandNames.values()) {
            if (item.getValue().equals(txt)) return item.name();
        }  return txt;
    }

  public void stateUpdate(final ObdCommandJob job) {
    final String cmdName = job.getCommand().getName();
    String cmdResult = "";
    final String cmdID = LookUpCommand(cmdName);

    if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR))
        cmdResult = job.getCommand().getResult();
    else
        cmdResult = job.getCommand().getFormattedResult();

    if ( vv.findViewWithTag(cmdID) != null ) {
        TextView existingTV = (TextView) vv.findViewWithTag(cmdID);
        existingTV.setText(cmdResult);
    }
    else addTableRow(cmdID, cmdName, cmdResult);

    commandResult.put(cmdID, cmdResult);
  }

  private boolean gpsInit() {
    mLocService = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    if(mLocService != null ) {
      mLocProvider = mLocService.getProvider(LocationManager.GPS_PROVIDER);
      if (mLocProvider != null) {
        mLocService.addGpsStatusListener(this);
        return true;
      }
    }
    showDialog(NO_GPS_SUPPORT);
    Log.e(TAG, "Unable to get GPS PROVIDER");
    // todo disable gps controls into Preferences
    return false;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    if(btAdapter != null)
      bluetoothDefaultIsEnable = btAdapter.isEnabled();

    // get Orientation sensor
    List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ORIENTATION);
    if (sensors.size() > 0)
        orientSensor = sensors.get(0);
    else
        showDialog(NO_ORIENTATION_SENSOR);
    gpsInit();
  }

  @Override
  protected void onStart() {
    super.onStart();
    Log.d(TAG, "Entered onStart...");
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    if(mLocService != null) {
      mLocService.removeGpsStatusListener(this);
      mLocService.removeUpdates(this);
    }

    releaseWakeLockIfHeld();
    if (isServiceBound) {
      doUnbindService();
    }

    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    if(btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable );
      btAdapter.disable();
  }

  @Override
  protected void onPause() {
    super.onPause();
    Log.d(TAG, "Pausing..");
    releaseWakeLockIfHeld();
  }

  /**
   * If lock is held, release. Lock will be held when the service is running.
   */
  private void releaseWakeLockIfHeld() {
    if (wakeLock.isHeld())
      wakeLock.release();
  }

  protected void onResume() {
    super.onResume();
    Log.d(TAG, "Resuming..");
    sensorManager.registerListener(orientListener, orientSensor,
        SensorManager.SENSOR_DELAY_UI);
    wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
        "ObdReader");

    // get Bluetooth device
    final BluetoothAdapter btAdapter = BluetoothAdapter
        .getDefaultAdapter();

    preRequisites = btAdapter != null && btAdapter.isEnabled();
    if ( !preRequisites && prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)) {
         preRequisites = btAdapter.enable();
    }

    if (!preRequisites) {
      showDialog(BLUETOOTH_DISABLED);
      Toast.makeText(this, "Bluetooth is disabled, will use Mock service instead", Toast.LENGTH_SHORT).show();
    } else {
      Toast.makeText(this, "Bluetooth ok", Toast.LENGTH_SHORT).show();
    }
  }

  private void updateConfig() {
    startActivity(new Intent(this, ConfigActivity.class));
  }

  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, START_LIVE_DATA, 0, "Start Live Data");
    menu.add(0, STOP_LIVE_DATA, 0, "Stop Live Data");
    menu.add(0, GET_DTC, 0, "Get DTC");
    menu.add(0, SETTINGS, 0, "Settings");
    return true;
  }

  // private void staticCommand() {
  // Intent commandIntent = new Intent(this, ObdReaderCommandActivity.class);
  // startActivity(commandIntent);
  // }

  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case START_LIVE_DATA:
        startLiveData();
        return true;
      case STOP_LIVE_DATA:
        stopLiveData();
        return true;
      case SETTINGS:
        updateConfig();
        return true;
      case GET_DTC:
        getTroubleCodes();
        return true;
      // case COMMAND_ACTIVITY:
      // staticCommand();
      // return true;
    }
    return false;
  }

  private void getTroubleCodes() {
    startActivity(new Intent(this, TroubleCodesActivity.class));
  }

  private void startLiveData() {
    Log.d(TAG, "Starting live data..");
    tl.removeAllViews(); //start fresh
    doBindService();

    // start command execution
    new Handler().post(mQueueCommands);

    if(prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false))
      gpsStart();

    // screen won't turn off until wakeLock.release()
    wakeLock.acquire();
  }

  private void stopLiveData() {
    Log.d(TAG, "Stopping live data..");

    gpsStop();

    doUnbindService();

    releaseWakeLockIfHeld();
  }

  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder build = new AlertDialog.Builder(this);
    switch (id) {
      case NO_BLUETOOTH_ID:
        build.setMessage("Sorry, your device doesn't support Bluetooth.");
        return build.create();
      case BLUETOOTH_DISABLED:
        build.setMessage("You have Bluetooth disabled. Please enable it!");
        return build.create();
      case NO_ORIENTATION_SENSOR:
        build.setMessage("Orientation sensor missing?");
        return build.create();
      case NO_GPS_SUPPORT:
        build.setMessage("Sorry, your device doesn't support GPS.");
        return build.create();
    }
    return null;
  }

  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem startItem = menu.findItem(START_LIVE_DATA);
    MenuItem stopItem = menu.findItem(STOP_LIVE_DATA);
    MenuItem settingsItem = menu.findItem(SETTINGS);
    MenuItem getDTCItem = menu.findItem(GET_DTC);

    if (service!=null && service.isRunning()) {
      getDTCItem.setEnabled(false);
      startItem.setEnabled(false);
      stopItem.setEnabled(true);
      settingsItem.setEnabled(false);
    } else {
      getDTCItem.setEnabled(true);
      stopItem.setEnabled(false);
      startItem.setEnabled(true);
      settingsItem.setEnabled(true);
    }

    return true;
  }

  private void addTableRow(String id, String key, String val) {

    TableRow tr = new TableRow(this);
    MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    params.setMargins(TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN,
        TABLE_ROW_MARGIN);
    tr.setLayoutParams(params);

    TextView name = new TextView(this);
    name.setGravity(Gravity.RIGHT);
    name.setText(key + ": ");
    TextView value = new TextView(this);
    value.setGravity(Gravity.LEFT);
    value.setText(val);
    value.setTag(id);
    tr.addView(name);
    tr.addView(value);
    tl.addView(tr, params);
  }

  /**
   *
   */
  private void queueCommands() {
    if (isServiceBound) {
      for (ObdCommand Command : ObdConfig.getCommands()  ) {
          if (prefs.getBoolean(Command.getName(),true))
             service.queueJob(new ObdCommandJob(Command));
           }
    }
  }

  private void doBindService() {
    if (!isServiceBound) {
      Log.d(TAG, "Binding OBD service..");
      if(preRequisites) {
        Intent serviceIntent = new Intent(this, ObdGatewayService.class);
        bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
      } else {
        Intent serviceIntent = new Intent(this, MockObdGatewayService.class);
        bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
      }
    }
  }

  private void doUnbindService() {
    if (isServiceBound) {
      if (service.isRunning()) {
        service.stopService();
      }
      Log.d(TAG, "Unbinding OBD service..");
      unbindService(serviceConn);
      isServiceBound = false;
    }
  }

  /**
   * Uploading asynchronous task
   */
  private class UploadAsyncTask extends AsyncTask<ObdReading, Void, Void> {

    @Override
    protected Void doInBackground(ObdReading... readings) {
      Log.d(TAG, "Uploading " + readings.length + " readings..");
      // instantiate reading service client
      final String endpoint = prefs.getString(ConfigActivity.UPLOAD_URL_KEY, "");
      RestAdapter restAdapter = new RestAdapter.Builder()
          .setEndpoint(endpoint)
          .build();
      ObdService service = restAdapter.create(ObdService.class);
      // upload readings
      for (ObdReading reading : readings) {
        try {
          Response response = service.uploadReading(reading);
          assert response.getStatus() == 200;
        }
        catch  (RetrofitError re)
        {Log.e(TAG, re.toString());}

      }
      Log.d(TAG, "Done");
      return null;
    }

  }

  public void onLocationChanged(Location location) {
    mLastLocation = location;
  }

  public void onStatusChanged(String provider, int status, Bundle extras) {
  }

  public void onProviderEnabled(String provider) {
  }

  public void onProviderDisabled(String provider) {
  }

  public void onGpsStatusChanged(int event) {
    mGpsStatus = mLocService.getGpsStatus(mGpsStatus);

    switch (event) {
      case GpsStatus.GPS_EVENT_STARTED:
        Toast.makeText(this, "GPS_EVENT_STARTED.", Toast.LENGTH_LONG).show();
        break;
      case GpsStatus.GPS_EVENT_STOPPED:
        Toast.makeText(this, "GPS_EVENT_STOPPED.", Toast.LENGTH_LONG).show();
        break;
      case GpsStatus.GPS_EVENT_FIRST_FIX:
        Toast.makeText(this, "GPS_EVENT_FIRST_FIX.", Toast.LENGTH_LONG).show();
        break;
      case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
        break;
    }
  }

  private synchronized void gpsStart() {
    if (!mGpsIsStarted && mLocProvider != null && mLocService != null) {
      mLocService.requestLocationUpdates(mLocProvider.getName(), gpsMinTime, gpsMinDistance, this);
      mGpsIsStarted = true;
    }
  }

  private synchronized void gpsStop() {
    if (mGpsIsStarted) {
      mLocService.removeUpdates(this);
      mGpsIsStarted = false;
    }
  }
}