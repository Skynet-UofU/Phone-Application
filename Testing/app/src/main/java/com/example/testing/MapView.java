package com.example.testing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Set;

public class MapView extends View {

    public class Loc {

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
    private Hashtable<String, Loc> locations;
    private Canvas mCanvas;
    //private Path mPath;
    Context context;
    private Paint mPaint;
    private Paint textPaint;
    private float mX, mY;
    private static final float TOLERANCE = 5;

    public void updateCanvas(Canvas canvas) {
        mCanvas = canvas;
    }

    public MapView(Context c, AttributeSet attrs) {
        super(c, attrs);
        context = c;

        // and we set a new Paint with the desired attributes
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setColor(Color.RED);
        mPaint.setStyle(Paint.Style.STROKE);
        //mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeWidth(14f);
        textPaint = new Paint();
        textPaint.setTypeface(Typeface.SANS_SERIF);
        textPaint.setTextSize(48f);
        locations = new Hashtable<String, Loc>();
    }

    private void drawPoints(Canvas canvas) {
        convertLocationToPoint(canvas);
    }

    // override onDraw
    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);
        drawPoints(canvas);
    }

    private float convertLatToRelative(double point, double min, double range, double max, double height) {
        return (float)((range - (point - min))/range * height);
    }

    private float convertLonToRelative(double point, double min, double range, double width) {
        return (float)((((point - min))/range) * width);
    }

    private void convertLocationToPoint(Canvas mapCanvas) {
        Set<String> loc_keys = locations.keySet();
        //float[] points = new float[loc_keys.size() * 2];
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
        double latDistance = 0.00001;
        double lonDistance = 0.00001;
        if(latDistance < maxLat - minLat) {
            latDistance = maxLat - minLat;
        }
        minLat -= latDistance * 0.1;
        maxLat += latDistance * 0.1;
        latDistance += latDistance * 0.2;
        if(lonDistance < maxLon - minLon) {
            lonDistance = maxLon - minLon;
        }
        minLon -= lonDistance * 0.1;
        maxLon += lonDistance * 0.1;
        lonDistance += lonDistance * 0.2;
        ArrayList<Loc> locs = new ArrayList();
        for(String key : loc_keys) {
            Loc current = locations.get(key);
            if(current.isUnique(locs)) {
                locs.add(current);

                float relLat = convertLatToRelative(current.lat, minLat, latDistance, maxLat, mapCanvas.getHeight());
                float relLon = convertLonToRelative(current.lon, minLon, lonDistance, mapCanvas.getWidth());
                mapCanvas.drawText(key,relLon,relLat-10, textPaint);
                mapCanvas.drawPoint(relLon, relLat, mPaint);
            }
        }
    }


    public void updateLocation(String id, double lat, double lon) {
        if(locations == null) {
            locations = new Hashtable<String, Loc>();
        }
        locations.put(id + "", new Loc(id, lat, lon));
        invalidate();
    }
}
