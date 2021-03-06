package com.eveningoutpost.dexdrip.Models;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.util.Pair;

import com.eveningoutpost.dexdrip.Models.UserError.Log;
import com.activeandroid.Model;
import com.activeandroid.annotation.Column;
import com.activeandroid.annotation.Table;
import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.ImportedLibraries.dexcom.records.EGVRecord;
import com.eveningoutpost.dexdrip.ImportedLibraries.dexcom.records.SensorRecord;
import com.eveningoutpost.dexdrip.ShareModels.ShareUploadableBg;
import com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.BgSendQueue;
import com.eveningoutpost.dexdrip.UtilityModels.CollectionServiceStarter;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.UtilityModels.Notifications;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.internal.bind.DateTypeAdapter;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Table(name = "BgReadings", id = BaseColumns._ID)
public class BgReading extends Model implements ShareUploadableBg{
    public static final double AGE_ADJUSTMENT_TIME = 86400000 * 1.9;
    public static final double AGE_ADJUSTMENT_FACTOR = .45;
    private static boolean predictBG;
    private final static String TAG = BgReading.class.getSimpleName();
    private final static String TAG_ALERT = TAG +" AlertBg";
    //TODO: Have these as adjustable settings!!
    public final static double BESTOFFSET = (60000 * 0); // Assume readings are about x minutes off from actual!

    @Column(name = "sensor", index = true)
    public Sensor sensor;

    @Column(name = "calibration", index = true)
    public Calibration calibration;

    @Expose
    @Column(name = "timestamp", index = true)
    public long timestamp;

    @Expose
    @Column(name = "time_since_sensor_started")
    public double time_since_sensor_started;

    @Expose
    @Column(name = "raw_data")
    public double raw_data;

    @Expose
    @Column(name = "filtered_data")
    public double filtered_data;

    @Expose
    @Column(name = "age_adjusted_raw_value")
    public double age_adjusted_raw_value;

    @Expose
    @Column(name = "calibration_flag")
    public boolean calibration_flag;

    @Expose
    @Column(name = "calculated_value")
    public double calculated_value;

    @Expose
    @Column(name = "filtered_calculated_value")
    public double filtered_calculated_value;

    @Expose
    @Column(name = "calculated_value_slope")
    public double calculated_value_slope;

    @Expose
    @Column(name = "a")
    public double a;

    @Expose
    @Column(name = "b")
    public double b;

    @Expose
    @Column(name = "c")
    public double c;

    @Expose
    @Column(name = "ra")
    public double ra;

    @Expose
    @Column(name = "rb")
    public double rb;

    @Expose
    @Column(name = "rc")
    public double rc;
    @Expose
    @Column(name = "uuid", unique = true , onUniqueConflicts = Column.ConflictAction.IGNORE)
    public String uuid;

    @Expose
    @Column(name = "calibration_uuid")
    public String calibration_uuid;

    @Expose
    @Column(name = "sensor_uuid", index = true)
    public String sensor_uuid;

    @Column(name = "snyced")
    public boolean synced;

    @Column(name = "raw_calculated")
    public double raw_calculated;

    @Column(name = "hide_slope")
    public boolean hide_slope;

    @Column(name = "noise")
    public String noise;

    public double calculated_value_mmol() {
        return mmolConvert(calculated_value);
    }

    public double mmolConvert(double mgdl) {
        return mgdl * Constants.MGDL_TO_MMOLL;
    }

    public String displayValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String unit = prefs.getString("units", "mgdl");
        DecimalFormat df = new DecimalFormat("#");
        df.setMaximumFractionDigits(0);

        if (calculated_value >= 400) {
            return "HIGH";
        } else if (calculated_value >= 40) {
            if(unit.compareTo("mgdl") == 0) {
                df.setMaximumFractionDigits(0);
                return df.format(calculated_value);
            } else {
                df.setMaximumFractionDigits(1);
                return df.format(calculated_value_mmol());
            }
        } else {
            return "LOW";
        }
    }

    public static double activeSlope() {
        BgReading bgReading = BgReading.lastNoSenssor();
        if (bgReading != null) {
            double slope = (2 * bgReading.a * (new Date().getTime() + BESTOFFSET)) + bgReading.b;
            Log.i(TAG, "ESTIMATE SLOPE" + slope);
            return slope;
        }
        return 0;
    }

    public static double activePrediction() {
        BgReading bgReading = BgReading.lastNoSenssor();
        if (bgReading != null) {
            double currentTime = new Date().getTime();
            if (currentTime >=  bgReading.timestamp + (60000 * 7)) { currentTime = bgReading.timestamp + (60000 * 7); }
            double time = currentTime + BESTOFFSET;
            return ((bgReading.a * time * time) + (bgReading.b * time) + bgReading.c);
        }
        return 0;
    }

    public static Pair<Double, Boolean> calculateSlope(BgReading current, BgReading last) {
        
        if (current.timestamp == last.timestamp || 
            current.timestamp - last.timestamp > BgGraphBuilder.MAX_SLOPE_MINUTES * 60 * 1000) {
            return Pair.create(0d, true);

        }
        double slope =  (last.calculated_value - current.calculated_value) / (last.timestamp - current.timestamp);
        return Pair.create(slope, false);
    }

    public static double currentSlope(){
        List<BgReading> last_2 = BgReading.latest(2);
        if (last_2.size() == 2) {
            Pair<Double, Boolean> slopePair = calculateSlope(last_2.get(0), last_2.get(1));
            return slopePair.first;
        } else{
            return 0d;
        }

    }


    //*******CLASS METHODS***********//
    public static void create(EGVRecord[] egvRecords, long addativeOffset, Context context) {
        for(EGVRecord egvRecord : egvRecords) { BgReading.create(egvRecord, addativeOffset, context); }
    }

    public static void create(SensorRecord[] sensorRecords, long addativeOffset, Context context) {
        for(SensorRecord sensorRecord : sensorRecords) { BgReading.create(sensorRecord, addativeOffset, context); }
    }

    public static void create(SensorRecord sensorRecord, long addativeOffset, Context context) {
        Log.i(TAG, "create: gonna make some sensor records: " + sensorRecord.getUnfiltered());
        if(BgReading.is_new(sensorRecord, addativeOffset)) {
            BgReading bgReading = new BgReading();
            Sensor sensor = Sensor.currentSensor();
            Calibration calibration = Calibration.getForTimestamp(sensorRecord.getSystemTime().getTime() + addativeOffset);
            if(sensor != null && calibration != null) {
                bgReading.sensor = sensor;
                bgReading.sensor_uuid = sensor.uuid;
                bgReading.calibration = calibration;
                bgReading.calibration_uuid = calibration.uuid;
                bgReading.raw_data = (sensorRecord.getUnfiltered() / 1000);
                bgReading.filtered_data = (sensorRecord.getFiltered() / 1000);
                bgReading.timestamp = sensorRecord.getSystemTime().getTime() + addativeOffset;
                if(bgReading.timestamp > new Date().getTime()) { return; }
                bgReading.uuid = UUID.randomUUID().toString();
                bgReading.time_since_sensor_started = bgReading.timestamp - sensor.started_at;
                bgReading.synced = false;
                bgReading.calculateAgeAdjustedRawValue(context);
                bgReading.save();
            }
        }
    }

    public static void create(EGVRecord egvRecord, long addativeOffset, Context context) {
        BgReading bgReading = BgReading.getForTimestamp(egvRecord.getSystemTime().getTime() + addativeOffset);
        Log.i(TAG, "create: Looking for BG reading to tag this thing to: " + egvRecord.getBGValue());
        if(bgReading != null) {
            bgReading.calculated_value = egvRecord.getBGValue();
            if (egvRecord.getBGValue() <= 13) {
                Calibration calibration = bgReading.calibration;
                double firstAdjSlope = calibration.first_slope + (calibration.first_decay * (Math.ceil(new Date().getTime() - calibration.timestamp)/(1000 * 60 * 10)));
                double calSlope = (calibration.first_scale / firstAdjSlope)*1000;
                double calIntercept = ((calibration.first_scale * calibration.first_intercept) / firstAdjSlope)*-1;
                bgReading.raw_calculated = (((calSlope * bgReading.raw_data) + calIntercept) - 5);
            }
            Log.i(TAG, "create: NEW VALUE CALCULATED AT: " + bgReading.calculated_value);
            Pair<Double, Boolean> slopePair = BgReading.slopefromName(egvRecord.getTrend().friendlyTrendName());
            bgReading.calculated_value_slope = slopePair.first;
            bgReading.hide_slope = slopePair.second;
            bgReading.noise = egvRecord.noiseValue();
            bgReading.save();
            bgReading.find_new_curve();
            bgReading.find_new_raw_curve();
            context.startService(new Intent(context, Notifications.class));
            BgSendQueue.handleNewBgReading(bgReading, "create", context);
        }
    }

    public static BgReading getForTimestamp(double timestamp) {
        Sensor sensor = Sensor.currentSensor();
        if(sensor != null) {
            BgReading bgReading = new Select()
                    .from(BgReading.class)
                    .where("Sensor = ? ", sensor.getId())
                    .where("timestamp <= ?", (timestamp + (60 * 1000))) // 1 minute padding (should never be that far off, but why not)
                    .where("calculated_value = 0")
                    .where("raw_calculated = 0")
                    .orderBy("timestamp desc")
                    .executeSingle();
            if(bgReading != null && Math.abs(bgReading.timestamp - timestamp) < (3*60*1000)) { //cool, so was it actually within 4 minutes of that bg reading?
                Log.i(TAG, "getForTimestamp: Found a BG timestamp match");
                return bgReading;
            }
        }
        Log.d(TAG, "getForTimestamp: No luck finding a BG timestamp match");
        return null;
    }

    public static boolean is_new(SensorRecord sensorRecord, long addativeOffset) {
        double timestamp = sensorRecord.getSystemTime().getTime() + addativeOffset;
        Sensor sensor = Sensor.currentSensor();
        if(sensor != null) {
            BgReading bgReading = new Select()
                    .from(BgReading.class)
                    .where("Sensor = ? ", sensor.getId())
                    .where("timestamp <= ?",  (timestamp + (60*1000))) // 1 minute padding (should never be that far off, but why not)
                    .orderBy("timestamp desc")
                    .executeSingle();
            if(bgReading != null && Math.abs(bgReading.timestamp - timestamp) < (3*60*1000)) { //cool, so was it actually within 4 minutes of that bg reading?
                Log.i(TAG, "isNew; Old Reading");
                return false;
            }
        }
        Log.i(TAG, "isNew: New Reading");
        return true;
    }

    public static BgReading create(double raw_data, double filtered_data, Context context, Long timestamp) {
        BgReading bgReading = new BgReading();
        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) {
            Log.i("BG GSON: ",bgReading.toS());

            return bgReading;
        }

        Calibration calibration = Calibration.last();
        if (calibration == null) {
            Log.d(TAG, "create: No calibration yet");
            bgReading.sensor = sensor;
            bgReading.sensor_uuid = sensor.uuid;
            bgReading.raw_data = (raw_data / 1000);
            bgReading.filtered_data = (filtered_data / 1000);
            bgReading.timestamp = timestamp;
            bgReading.uuid = UUID.randomUUID().toString();
            bgReading.time_since_sensor_started = bgReading.timestamp - sensor.started_at;
            bgReading.synced = false;
            bgReading.calibration_flag = false;

            bgReading.calculateAgeAdjustedRawValue(context);

            bgReading.save();
            bgReading.perform_calculations();
        } else {
            Log.d(TAG,"Calibrations, so doing everything");
            bgReading.sensor = sensor;
            bgReading.sensor_uuid = sensor.uuid;
            bgReading.calibration = calibration;
            bgReading.calibration_uuid = calibration.uuid;
            bgReading.raw_data = (raw_data/1000);
            bgReading.filtered_data = (filtered_data/1000);
            bgReading.timestamp = timestamp;
            bgReading.uuid = UUID.randomUUID().toString();
            bgReading.time_since_sensor_started = bgReading.timestamp - sensor.started_at;
            bgReading.synced = false;

            bgReading.calculateAgeAdjustedRawValue(context);

            if(calibration.check_in) {
                double firstAdjSlope = calibration.first_slope + (calibration.first_decay * (Math.ceil(new Date().getTime() - calibration.timestamp)/(1000 * 60 * 10)));
                double calSlope = (calibration.first_scale / firstAdjSlope)*1000;
                double calIntercept = ((calibration.first_scale * calibration.first_intercept) / firstAdjSlope)*-1;
                bgReading.calculated_value = (((calSlope * bgReading.raw_data) + calIntercept) - 5);
                bgReading.filtered_calculated_value = (((calSlope * bgReading.ageAdjustedFiltered()) + calIntercept) -5);

            } else {
                BgReading lastBgReading = BgReading.last();
                if (lastBgReading != null && lastBgReading.calibration != null) {
                    if (lastBgReading.calibration_flag == true && ((lastBgReading.timestamp + (60000 * 20)) > bgReading.timestamp) && ((lastBgReading.calibration.timestamp + (60000 * 20)) > bgReading.timestamp)) {
                        lastBgReading.calibration.rawValueOverride(BgReading.weightedAverageRaw(lastBgReading.timestamp, bgReading.timestamp, lastBgReading.calibration.timestamp, lastBgReading.age_adjusted_raw_value, bgReading.age_adjusted_raw_value), context);
                    }
                }
                bgReading.calculated_value = ((calibration.slope * bgReading.age_adjusted_raw_value) + calibration.intercept);
                bgReading.filtered_calculated_value = ((calibration.slope * bgReading.ageAdjustedFiltered()) + calibration.intercept);
            }
            updateCalculatedValue(bgReading);

            bgReading.save();
            bgReading.perform_calculations();
            context.startService(new Intent(context, Notifications.class));
            BgSendQueue.handleNewBgReading(bgReading, "create", context);
        }

        Log.i("BG GSON: ",bgReading.toS());

        return bgReading;
    }

    static void updateCalculatedValue(BgReading bgReading ) {
        if (bgReading.calculated_value < 10) {
            bgReading.calculated_value = 38;
            bgReading.hide_slope = true;
        } else {
            bgReading.calculated_value = Math.min(400, Math.max(39, bgReading.calculated_value));
        }
        Log.i(TAG, "NEW VALUE CALCULATED AT: " + bgReading.calculated_value);
    }

    // Used by xDripViewer
    public static void create(Context context, double raw_data, double age_adjusted_raw_value, double filtered_data, Long timestamp,
            double calculated_bg,  double calculated_current_slope, boolean hide_slope, double xdrip_filtered_calculated_value) {
        
        BgReading bgReading = new BgReading();
        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) {
            Log.w(TAG, "No sensor, ignoring this bg reading");
            return ;
        }

        Calibration calibration = Calibration.last();
        if (calibration == null) {
            Log.d(TAG, "create: No calibration yet");
        } else {
            Log.d(TAG,"Calibrations, so doing everything bgReading = " + bgReading);
            bgReading.calibration = calibration;
            bgReading.calibration_uuid = calibration.uuid;
        }
        
        bgReading.sensor = sensor;
        bgReading.sensor_uuid = sensor.uuid;
        bgReading.raw_data = (raw_data/1000);
        bgReading.age_adjusted_raw_value = age_adjusted_raw_value;
        bgReading.filtered_data = (filtered_data/1000);
        bgReading.timestamp = timestamp;
        bgReading.uuid = UUID.randomUUID().toString();
        bgReading.calculated_value = calculated_bg;
        bgReading.calculated_value_slope = calculated_current_slope;
        bgReading.hide_slope = hide_slope;
        bgReading.filtered_calculated_value = xdrip_filtered_calculated_value;
        bgReading.save();

        BgSendQueue.handleNewBgReading(bgReading, "create", context);

        Log.i("BG GSON: ",bgReading.toS());
    }

    public static String activeSlopeArrow() {
        double slope = (float) (BgReading.activeSlope() * 60000);
        return slopeToArrowSymbol(slope);
    }

    public static String slopeToArrowSymbol(double slope) {
        if (slope <= (-3.5)) {
            return "\u21ca";
        } else if (slope <= (-2)) {
            return "\u2193";
        } else if (slope <= (-1)) {
            return "\u2198";
        } else if (slope <= (1)) {
            return "\u2192";
        } else if (slope <= (2)) {
            return "\u2197";
        } else if (slope <= (3.5)) {
            return "\u2191";
        } else {
            return "\u21c8";
        }
    }

    public String slopeArrow(){
        return slopeToArrowSymbol(this.calculated_value_slope*60000);
    }

    public String slopeName() {
        double slope_by_minute = calculated_value_slope * 60000;
        String arrow = "NONE";
        if (slope_by_minute <= (-3.5)) {
            arrow = "DoubleDown";
        } else if (slope_by_minute <= (-2)) {
            arrow = "SingleDown";
        } else if (slope_by_minute <= (-1)) {
            arrow = "FortyFiveDown";
        } else if (slope_by_minute <= (1)) {
            arrow = "Flat";
        } else if (slope_by_minute <= (2)) {
            arrow = "FortyFiveUp";
        } else if (slope_by_minute <= (3.5)) {
            arrow = "SingleUp";
        } else if (slope_by_minute <= (40)) {
            arrow = "DoubleUp";
        }
        if(hide_slope) {
            arrow = "NOT COMPUTABLE";
        }
        return arrow;
    }

    public static  Pair<Double, Boolean> slopefromName(String slope_name) {

        double slope_by_minute = 0;
        boolean hide = false;
        if (slope_name.compareTo("DoubleDown") == 0) {
            slope_by_minute = -3.5;
        } else if (slope_name.compareTo("SingleDown") == 0) {
            slope_by_minute = -2;
        } else if (slope_name.compareTo("FortyFiveDown") == 0) {
            slope_by_minute = -1;
        } else if (slope_name.compareTo("Flat") == 0) {
            slope_by_minute = 0;
        } else if (slope_name.compareTo("FortyFiveUp") == 0) {
            slope_by_minute = 2;
        } else if (slope_name.compareTo("SingleUp") == 0) {
            slope_by_minute = 3.5;
        } else if (slope_name.compareTo("DoubleUp") == 0) {
            slope_by_minute = 4;
        } else if (slope_name.compareTo("NOT_COMPUTABLE") == 0 ||
                   slope_name.compareTo("NOT COMPUTABLE") == 0 ||
                   slope_name.compareTo("OUT_OF_RANGE")   == 0 ||
                   slope_name.compareTo("OUT OF RANGE")   == 0 ||
                   slope_name.compareTo("NONE") == 0) {
            slope_by_minute = 0;
            hide = true;
        }

        slope_by_minute /= 60000;
        return Pair.create(slope_by_minute, hide);
    }

    public static BgReading last() {
        Sensor sensor = Sensor.currentSensor();
        if (sensor != null) {
            return new Select()
                    .from(BgReading.class)
                    .where("Sensor = ? ", sensor.getId())
                    .where("calculated_value != 0")
                    .where("raw_data != 0")
                    .orderBy("timestamp desc")
                    .executeSingle();
        }
        return null;
    }
    public static List<BgReading> latest_by_size(int number) {
        Sensor sensor = Sensor.currentSensor();
        return new Select()
                .from(BgReading.class)
                .where("Sensor = ? ", sensor.getId())
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .limit(number)
                .execute();
    }

    public static BgReading lastNoSenssor() {
        return new Select()
                .from(BgReading.class)
                .where("calculated_value != 0")
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .executeSingle();
    }

    public static List<BgReading> latest(int number) {
        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) { return null; }
        return new Select()
                .from(BgReading.class)
                .where("Sensor = ? ", sensor.getId())
                .where("calculated_value != 0")
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .limit(number)
                .execute();
    }

    public static List<BgReading> latestUnCalculated(int number) {
        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) { return null; }
        return new Select()
                .from(BgReading.class)
                .where("Sensor = ? ", sensor.getId())
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .limit(number)
                .execute();
    }

    public static List<BgReading> latestForGraph(int number, long startTime) {
        return latestForGraph(number, startTime, Long.MAX_VALUE);
    }

    public static List<BgReading> latestForGraph(int number, long startTime, long endTime) {
        return new Select()
                .from(BgReading.class)
                .where("timestamp >= " + Math.max(startTime, 0))
                .where("timestamp <= " + endTime)
                .where("calculated_value != 0")
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .limit(number)
                .execute();
    }

    public static List<BgReading> last30Minutes() {
        double timestamp = (new Date().getTime()) - (60000 * 30);
        return new Select()
                .from(BgReading.class)
                .where("timestamp >= " + timestamp)
                .where("calculated_value != 0")
                .where("raw_data != 0")
                .orderBy("timestamp desc")
                .execute();
    }

    public static List<BgReading> futureReadings() {
        double timestamp = new Date().getTime();
        return new Select()
                .from(BgReading.class)
                .where("timestamp > " + timestamp)
                .orderBy("timestamp desc")
                .execute();
    }

    public static BgReading findByUuid(String uuid) {
        return new Select()
                .from(BgReading.class)
                .where("uuid = ?", uuid)
                .executeSingle();
    }

    public static double estimated_bg(double timestamp) {
        timestamp = timestamp + BESTOFFSET;
        BgReading latest = BgReading.last();
        if (latest == null) {
            return 0;
        } else {
            return (latest.a * timestamp * timestamp) + (latest.b * timestamp) + latest.c;
        }
    }

    public static double estimated_raw_bg(double timestamp) {
        timestamp = timestamp + BESTOFFSET;
        double estimate;
        BgReading latest = BgReading.last();
        if (latest == null) {
            Log.i(TAG, "No data yet, assume perfect!");
            estimate = 160;
        } else {
            estimate = (latest.ra * timestamp * timestamp) + (latest.rb * timestamp) + latest.rc;
        }
        Log.i(TAG, "ESTIMATE RAW BG" + estimate);
        return estimate;
    }

    //*******INSTANCE METHODS***********//
    public void perform_calculations() {
        find_new_curve();
        find_new_raw_curve();
        find_slope();
    }

    public void find_slope() {
        List<BgReading> last_2 = BgReading.latest(2);

        assert last_2.get(0)==this : "Invariant condition not fulfilled: calculating slope and current reading wasn't saved before";

        hide_slope = true;
        if (last_2.size() == 2) {
            Pair<Double, Boolean> slopePair = calculateSlope(this, last_2.get(1));
            calculated_value_slope = slopePair.first;
            hide_slope = slopePair.second;
        } else if (last_2.size() == 1) {
            calculated_value_slope = 0;
        } else {
            Log.w(TAG, "NO BG? COULDNT FIND SLOPE!");
            calculated_value_slope = 0;
        }
        save();
    }


    public void find_new_curve() {
        List<BgReading> last_3 = BgReading.latest(3);
        if (last_3.size() == 3) {
            BgReading second_latest = last_3.get(1);
            BgReading third_latest = last_3.get(2);

            double y3 = calculated_value;
            double x3 = timestamp;
            double y2 = second_latest.calculated_value;
            double x2 = second_latest.timestamp;
            double y1 = third_latest.calculated_value;
            double x1 = third_latest.timestamp;

            a = y1/((x1-x2)*(x1-x3))+y2/((x2-x1)*(x2-x3))+y3/((x3-x1)*(x3-x2));
            b = (-y1*(x2+x3)/((x1-x2)*(x1-x3))-y2*(x1+x3)/((x2-x1)*(x2-x3))-y3*(x1+x2)/((x3-x1)*(x3-x2)));
            c = (y1*x2*x3/((x1-x2)*(x1-x3))+y2*x1*x3/((x2-x1)*(x2-x3))+y3*x1*x2/((x3-x1)*(x3-x2)));

            Log.i(TAG, "find_new_curve: BG PARABOLIC RATES: "+a+"x^2 + "+b+"x + "+c);

            save();
        } else if (last_3.size() == 2) {

            Log.i(TAG, "find_new_curve: Not enough data to calculate parabolic rates - assume Linear");
                BgReading latest = last_3.get(0);
                BgReading second_latest = last_3.get(1);

                double y2 = latest.calculated_value;
                double x2 = timestamp;
                double y1 = second_latest.calculated_value;
                double x1 = second_latest.timestamp;

                if(y1 == y2) {
                    b = 0;
                } else {
                    b = (y2 - y1)/(x2 - x1);
                }
                a = 0;
                c = -1 * ((latest.b * x1) - y1);

            Log.i(TAG, ""+latest.a+"x^2 + "+latest.b+"x + "+latest.c);
                save();
            } else {
            Log.i(TAG, "find_new_curve: Not enough data to calculate parabolic rates - assume static data");
            a = 0;
            b = 0;
            c = calculated_value;

            Log.i(TAG, ""+a+"x^2 + "+b+"x + "+c);
            save();
        }
    }

    public void calculateAgeAdjustedRawValue(Context context){
        double adjust_for = AGE_ADJUSTMENT_TIME - time_since_sensor_started;
        if (adjust_for <= 0 || CollectionServiceStarter.isLimitter(context)) {
                age_adjusted_raw_value = raw_data;
            } else {
                age_adjusted_raw_value = ((AGE_ADJUSTMENT_FACTOR * (adjust_for / AGE_ADJUSTMENT_TIME)) * raw_data) + raw_data;
                Log.i(TAG, "calculateAgeAdjustedRawValue: RAW VALUE ADJUSTMENT FROM:" + raw_data + " TO: " + age_adjusted_raw_value);
        }
    }

    public void find_new_raw_curve() {
        List<BgReading> last_3 = BgReading.latest(3);
        if (last_3.size() == 3) {
            BgReading second_latest = last_3.get(1);
            BgReading third_latest = last_3.get(2);

            double y3 = age_adjusted_raw_value;
            double x3 = timestamp;
            double y2 = second_latest.age_adjusted_raw_value;
            double x2 = second_latest.timestamp;
            double y1 = third_latest.age_adjusted_raw_value;
            double x1 = third_latest.timestamp;

            ra = y1/((x1-x2)*(x1-x3))+y2/((x2-x1)*(x2-x3))+y3/((x3-x1)*(x3-x2));
            rb = (-y1*(x2+x3)/((x1-x2)*(x1-x3))-y2*(x1+x3)/((x2-x1)*(x2-x3))-y3*(x1+x2)/((x3-x1)*(x3-x2)));
            rc = (y1*x2*x3/((x1-x2)*(x1-x3))+y2*x1*x3/((x2-x1)*(x2-x3))+y3*x1*x2/((x3-x1)*(x3-x2)));

            Log.i(TAG, "find_new_raw_curve: RAW PARABOLIC RATES: "+ra+"x^2 + "+rb+"x + "+rc);
            save();
        } else if (last_3.size() == 2) {
            BgReading latest = last_3.get(0);
            BgReading second_latest = last_3.get(1);

            double y2 = latest.age_adjusted_raw_value;
            double x2 = timestamp;
            double y1 = second_latest.age_adjusted_raw_value;
            double x1 = second_latest.timestamp;
            if(y1 == y2) {
                rb = 0;
            } else {
                rb = (y2 - y1)/(x2 - x1);
            }
            ra = 0;
            rc = -1 * ((latest.rb * x1) - y1);

            Log.i(TAG, "find_new_raw_curve: Not enough data to calculate parabolic rates - assume Linear data");

            Log.i(TAG, "RAW PARABOLIC RATES: "+ra+"x^2 + "+rb+"x + "+rc);
            save();
        } else {
            Log.i(TAG, "find_new_raw_curve: Not enough data to calculate parabolic rates - assume static data");
            BgReading latest_entry = BgReading.lastNoSenssor();
            ra = 0;
            rb = 0;
            if (latest_entry != null) {
                rc = latest_entry.age_adjusted_raw_value;
            } else {
                rc = 105;
            }

            save();
        }
    }
    public static double weightedAverageRaw(double timeA, double timeB, double calibrationTime, double rawA, double rawB) {
        double relativeSlope = (rawB -  rawA)/(timeB - timeA);
        double relativeIntercept = rawA - (relativeSlope * timeA);
        return ((relativeSlope * calibrationTime) + relativeIntercept);
    }

    public String toS() {
        Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeAdapter(Date.class, new DateTypeAdapter())
                .serializeSpecialFloatingPointValues()
                .create();
        return gson.toJson(this);
    }

    public int noiseValue() {
        if(noise == null || noise.compareTo("") == 0) {
            return 1;
        } else {
            return Integer.valueOf(noise);
        }
    }

    // list(0) is the most recent reading.
    public static List<BgReading> getXRecentPoints(int NumReadings) {
        List<BgReading> latest = BgReading.latest(NumReadings);
        if (latest == null || latest.size() != NumReadings) {
            // for less than NumReadings readings, we can't tell what the situation
            //
            Log.d(TAG_ALERT, "getXRecentPoints we don't have enough readings, returning null");
            return null;
        }
        // So, we have at least three values...
        for(BgReading bgReading : latest) {
            Log.d(TAG_ALERT, "getXRecentPoints - reading: time = " + bgReading.timestamp + " calculated_value " + bgReading.calculated_value);
        }

        // now let's check that they are relevant. the last reading should be from the last 5 minutes,
        // x-1 more readings should be from the last (x-1)*5 minutes. we will allow 5 minutes for the last
        // x to allow one packet to be missed.
        if (new Date().getTime() - latest.get(NumReadings - 1).timestamp > (NumReadings * 5 + 6) * 60 * 1000) {
            Log.d(TAG_ALERT, "getXRecentPoints we don't have enough points from the last " + (NumReadings * 5 + 6) + " minutes, returning null");
            return null;
        }
        return latest;

    }

    public static void checkForRisingAllert(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Boolean rising_alert = prefs.getBoolean("rising_alert", false);
        if(!rising_alert) {
            return;
        }
        if(prefs.getLong("alerts_disabled_until", 0) > new Date().getTime()){
            Log.i("NOTIFICATIONS", "checkForRisingAllert: Notifications are currently disabled!!");
            return;
        }

        if(IsUnclearTime(context)) {
            Log.d(TAG_ALERT, "checkForRisingAllert we are in an clear time, returning without doing anything");
            return ;
        }

        String riseRate = prefs.getString("rising_bg_val", "2");
        float friseRate = 2;

        try
        {
            friseRate = Float.parseFloat(riseRate);
        }
        catch (NumberFormatException nfe)
        {
            Log.e(TAG_ALERT, "checkForRisingAllert reading falling_bg_val failed, continuing with 2", nfe);
        }
        Log.d(TAG_ALERT, "checkForRisingAllert will check for rate of " + friseRate);

        boolean riseAlert = checkForDropRiseAllert(friseRate, false);
        Notifications.RisingAlert(context, riseAlert);
    }


    public static void checkForDropAllert(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Boolean falling_alert = prefs.getBoolean("falling_alert", false);
        if(!falling_alert) {
            return;
        }
        if(prefs.getLong("alerts_disabled_until", 0) > new Date().getTime()){
            Log.d("NOTIFICATIONS", "checkForDropAllert: Notifications are currently disabled!!");
            return;
        }

        if(IsUnclearTime(context)) {
            Log.d(TAG_ALERT, "checkForDropAllert we are in an clear time, returning without doing anything");
            return ;
        }

        String dropRate = prefs.getString("falling_bg_val", "2");
        float fdropRate = 2;

        try
        {
            fdropRate = Float.parseFloat(dropRate);
        }
        catch (NumberFormatException nfe)
        {
            Log.e(TAG_ALERT, "reading falling_bg_val failed, continuing with 2", nfe);
        }
        Log.i(TAG_ALERT, "checkForDropAllert will check for rate of " + fdropRate);

        boolean dropAlert = checkForDropRiseAllert(fdropRate, true);
        Notifications.DropAlert(context, dropAlert);
    }

    // true say, alert is on.
    private static boolean checkForDropRiseAllert(float MaxSpeed, boolean drop) {
        Log.d(TAG_ALERT, "checkForDropRiseAllert called drop=" + drop);
        List<BgReading> latest = getXRecentPoints(4);
        if(latest == null) {
            Log.d(TAG_ALERT, "checkForDropRiseAllert we don't have enough points from the last 15 minutes, returning false");
            return false;
        }
        float time3 = (latest.get(0).timestamp - latest.get(3).timestamp) / 60000;
        double bg_diff3 = latest.get(3).calculated_value - latest.get(0).calculated_value;
        if (!drop) {
            bg_diff3 *= (-1);
        }
        Log.i(TAG_ALERT, "bg_diff3=" + bg_diff3 + " time3 = " + time3);
        if(bg_diff3 < time3 * MaxSpeed) {
            Log.d(TAG_ALERT, "checkForDropRiseAllert for latest 4 points not fast enough, returning false");
            return false;
        }
        // we should alert here, but if the last measurement was less than MaxSpeed / 2, I won't.


        float time1 = (latest.get(0).timestamp - latest.get(1).timestamp) / 60000;
        double bg_diff1 = latest.get(1).calculated_value - latest.get(0).calculated_value;
        if (!drop) {
            bg_diff1 *= (-1);
        }

        if(time1 > 7.0) {
            Log.d(TAG_ALERT, "checkForDropRiseAllert the two points are not close enough, returning true");
            return true;
        }
        if(bg_diff1 < time1 * MaxSpeed /2) {
            Log.d(TAG_ALERT, "checkForDropRiseAllert for latest 2 points not fast enough, returning false");
            return false;
        }
        Log.d(TAG_ALERT, "checkForDropRiseAllert returning true speed is " + (bg_diff3 / time3));
        return true;
    }

    private static boolean IsUnclearTime(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Boolean bg_unclear_readings_alerts = prefs.getBoolean("bg_unclear_readings_alerts", false);
        if(bg_unclear_readings_alerts) {
            Long UnclearTimeSetting = Long.parseLong(prefs.getString("bg_unclear_readings_minutes", "90")) * 60000;
            Long unclearTime = getUnclearTime(UnclearTimeSetting);
            if (unclearTime > 0) {
                Log.d(TAG_ALERT, "IsUnclearTime we are in an clear time, returning true");
                return true;
            }
        }
        return false;
    }
    /*
     * This function comes to check weather we are in a case that we have an allert but since things are
     * getting better we should not do anything. (This is only in the case that the alert was snoozed before.)
     * This means that if this is a low alert, and we have two readings in the last 15 minutes, and
     * either we have gone in 10 in the last two readings, or we have gone in 3 in the last reading, we
     * don't play the alert again, but rather wait for the alert to finish.
     *  I'll start with having the same values for the high alerts.
    */

    public static boolean trendingToAlertEnd(Context context, boolean above) {
        // TODO: check if we are not in an UnclerTime.
        Log.d(TAG_ALERT, "trendingToAlertEnd called");

        if(IsUnclearTime(context)) {
            Log.d(TAG_ALERT, "trendingToAlertEnd we are in an clear time, returning false");
            return false;
        }

        List<BgReading> latest = getXRecentPoints(3);
        if(latest == null) {
            Log.d(TAG_ALERT, "trendingToAlertEnd we don't have enough points from the last 15 minutes, returning false");
            return false;
        }

        if(above == false) {
            // This is a low alert, we should be going up
            if((latest.get(0).calculated_value - latest.get(1).calculated_value > 4) ||
               (latest.get(0).calculated_value - latest.get(2).calculated_value > 10)) {
                Log.d(TAG_ALERT, "trendingToAlertEnd returning true for low alert");
                return true;
            }
        } else {
            // This is a high alert we should be heading down
            if((latest.get(1).calculated_value - latest.get(0).calculated_value > 4) ||
               (latest.get(2).calculated_value - latest.get(0).calculated_value > 10)) {
                Log.d(TAG_ALERT, "trendingToAlertEnd returning true for high alert");
                return true;
            }
        }
        Log.d(TAG_ALERT, "trendingToAlertEnd returning false, not in the right direction (or not fast enough)");
        return false;

    }

    // Should that be combined with noiseValue?
    private Boolean Unclear() {
        Log.d(TAG_ALERT, "Unclear filtered_data=" + filtered_data + " raw_data=" + raw_data);
        return raw_data > filtered_data * 1.3 || raw_data < filtered_data * 0.7;
    }

    /*
     * returns the time (in ms) that the state is not clear and no alerts should work
     * The base of the algorithm is that any period can be bad or not. bgReading.Unclear() tells that.
     * a non clear bgReading means MAX_INFLUANCE time after it we are in a bad position
     * Since this code is based on heuristics, and since times are not accurate, boundary issues can be ignored.
     *
     * interstingTime is the period to check. That is if the last period is bad, we want to know how long does it go bad...
     * */

    // The extra 120,000 is to allow the packet to be delayed for some time and still be counted in that group
    // Please don't use for MAX_INFLUANCE a number that is complete multiply of 5 minutes (300,000)
    static final int MAX_INFLUANCE = 30 * 60000 - 120000; // A bad point means data is untrusted for 30 minutes.
    private static Long getUnclearTimeHelper(List<BgReading> latest, Long interstingTime, final Long now) {

        // The code ignores missing points (that is they some times are treated as good and some times as bad.
        // If this bothers someone, I believe that the list should be filled with the missing points as good and continue to run.

        Long LastGoodTime = 0l; // 0 represents that we are not in a good part

        Long UnclearTime = 0l;
        for(BgReading bgReading : latest) {
            // going over the readings from latest to first
            if(bgReading.timestamp < now - (interstingTime + MAX_INFLUANCE)) {
                // Some readings are missing, we can stop checking
                break;
            }
            if(bgReading.timestamp <= now - MAX_INFLUANCE  && UnclearTime == 0) {
                Log.d(TAG_ALERT, "We did not have a problematic reading for MAX_INFLUANCE time, so now all is well");
                return 0l;

            }
            if (bgReading.Unclear()) {
                // here we assume that there are no missing points. Missing points might join the good and bad values as well...
                // we should have checked if we have a period, but it is hard to say how to react to them.
                Log.d(TAG_ALERT, "We have a bad reading, so setting UnclearTime to " + bgReading.timestamp);
                UnclearTime = bgReading.timestamp;
                LastGoodTime = 0l;
            } else {
                if (LastGoodTime == 0l) {
                    Log.d(TAG_ALERT, "We are starting a good period at "+ bgReading.timestamp);
                    LastGoodTime = bgReading.timestamp;
                } else {
                    // we have some good period, is it good enough?
                    if(LastGoodTime - bgReading.timestamp >= MAX_INFLUANCE) {
                        // Here UnclearTime should be already set, otherwise we will return a toob big value
                        if (UnclearTime ==0) {
                            Log.wtf(TAG_ALERT, "ERROR - UnclearTime must not be 0 here !!!");
                        }
                        Log.d(TAG_ALERT, "We have a good period from " + bgReading.timestamp + " to " + LastGoodTime + "returning " + (now - UnclearTime +5 *60000));
                        return now - UnclearTime + 5 *60000;
                    }
                }
            }
        }
        // if we are here, we have a problem... or not.
        if(UnclearTime == 0l) {
            Log.d(TAG_ALERT, "Since we did not find a good period, but we also did not find a single bad value, we assume things are good");
            return 0l;
        }
        Log.d(TAG_ALERT, "We scanned all over, but could not find a good period. we have a bad value, so assuming that the whole period is bad" +
                " returning " + interstingTime);
        // Note that we might now have all the points, and in this case, since we don't have a good period I return a bad period.
        return interstingTime;

    }

    // This is to enable testing of the function, by passing different values
    public static Long getUnclearTime(Long interstingTime) {
        List<BgReading> latest = BgReading.latest((interstingTime.intValue() + MAX_INFLUANCE)/ 60000 /5 );
        if (latest == null) {
            return 0L;
        }
        final Long now = new Date().getTime();
        return getUnclearTimeHelper(latest, interstingTime, now);

    }

    public static Long getTimeSinceLastReading() {
        BgReading bgReading = BgReading.last();
        if (bgReading != null) {
            return (new Date().getTime() - bgReading.timestamp);
        }
        return (long) 0;
    }

    public double usedRaw() {
        Calibration calibration = Calibration.last();
        if (calibration != null && calibration.check_in) {
            return raw_data;
        }
        return age_adjusted_raw_value;
    }

    public double ageAdjustedFiltered(){
        double usedRaw = usedRaw();
        if(usedRaw == raw_data || raw_data == 0d){
            return filtered_data;
        } else {
            // adjust the filtered_data with the same factor as the age adjusted raw value
            return filtered_data * (usedRaw/raw_data);
        }
    }

    // the input of this function is a string. each char can be g(=good) or b(=bad) or s(=skip, point unmissed).
    static List<BgReading> createlatestTest(String input, Long now) {
        Random randomGenerator = new Random();
        List<BgReading> out = new LinkedList<BgReading> ();
        char[] chars=  input.toCharArray();
        for(int i=0; i < chars.length; i++) {
            BgReading bg = new BgReading();
            int rand = randomGenerator.nextInt(20000) - 10000;
            bg.timestamp = now - i * 5 * 60000 + rand;
            bg.raw_data = 150;
            if(chars[i] == 'g') {
                bg.filtered_data = 151;
            } else if (chars[i] == 'b') {
                bg.filtered_data = 110;
            } else {
                continue;
            }
            out.add(bg);
        }
        return out;


    }
    static void TestgetUnclearTime(String input, Long interstingTime, Long expectedResult) {
        final Long now = new Date().getTime();
        List<BgReading> readings = createlatestTest(input, now);
        Long result = getUnclearTimeHelper(readings, interstingTime * 60000, now);
        if (result >= expectedResult * 60000 - 20000 && result <= expectedResult * 60000+20000) {
            Log.d(TAG_ALERT, "Test passed");
        } else {
            Log.d(TAG_ALERT, "Test failed expectedResult = " + expectedResult + " result = "+ result / 60000.0);
        }

    }

    public static void TestgetUnclearTimes() {
        TestgetUnclearTime("gggggggggggggggggggggggg", 90l, 0l * 5);
        TestgetUnclearTime("bggggggggggggggggggggggg", 90l, 1l * 5);
        TestgetUnclearTime("bbgggggggggggggggggggggg", 90l, 2l *5 );
        TestgetUnclearTime("gbgggggggggggggggggggggg", 90l, 2l * 5);
        TestgetUnclearTime("gbgggbggbggbggbggbggbgbg", 90l, 18l * 5);
        TestgetUnclearTime("bbbgggggggbbgggggggggggg", 90l, 3l * 5);
        TestgetUnclearTime("ggggggbbbbbbgggggggggggg", 90l, 0l * 5);
        TestgetUnclearTime("ggssgggggggggggggggggggg", 90l, 0l * 5);
        TestgetUnclearTime("ggssbggssggggggggggggggg", 90l, 5l * 5);
        TestgetUnclearTime("bb",                       90l, 18l * 5);

        // intersting time is 2 minutes, we should always get 0 (in 5 minutes units
        TestgetUnclearTime("gggggggggggggggggggggggg", 2l, 0l  * 5);
        TestgetUnclearTime("bggggggggggggggggggggggg", 2l, 2l);
        TestgetUnclearTime("bbgggggggggggggggggggggg", 2l, 2l);
        TestgetUnclearTime("gbgggggggggggggggggggggg", 2l, 2l);
        TestgetUnclearTime("gbgggbggbggbggbggbggbgbg", 2l, 2l);

        // intersting time is 10 minutes, we should always get 0 (in 5 minutes units
        TestgetUnclearTime("gggggggggggggggggggggggg", 10l, 0l  * 5);
        TestgetUnclearTime("bggggggggggggggggggggggg", 10l, 1l * 5);
        TestgetUnclearTime("bbgggggggggggggggggggggg", 10l, 2l * 5);
        TestgetUnclearTime("gbgggggggggggggggggggggg", 10l, 2l * 5);
        TestgetUnclearTime("gbgggbggbggbggbggbggbgbg", 10l, 2l * 5);
        TestgetUnclearTime("bbbgggggggbbgggggggggggg", 10l, 2l * 5);
        TestgetUnclearTime("ggggggbbbbbbgggggggggggg", 10l, 0l * 5);
        TestgetUnclearTime("ggssgggggggggggggggggggg", 10l, 0l * 5);
        TestgetUnclearTime("ggssbggssggggggggggggggg", 10l, 2l * 5);
        TestgetUnclearTime("bb",                       10l, 2l * 5);
    }

    public int getSlopeOrdinal() {
        double slope_by_minute = calculated_value_slope * 60000;
        int ordinal = 0;
        if(!hide_slope) {
            if (slope_by_minute <= (-3.5)) {
                ordinal = 7;
            } else if (slope_by_minute <= (-2)) {
                ordinal = 6;
            } else if (slope_by_minute <= (-1)) {
                ordinal = 5;
            } else if (slope_by_minute <= (1)) {
                ordinal = 4;
            } else if (slope_by_minute <= (2)) {
                ordinal = 3;
            } else if (slope_by_minute <= (3.5)) {
                ordinal = 2;
            } else {
                ordinal = 1;
            }
        }
        return ordinal;
    }

    public int getMgdlValue() {
        return (int) calculated_value;
    }

    public long getEpochTimestamp() {
        return timestamp;
    }
}
