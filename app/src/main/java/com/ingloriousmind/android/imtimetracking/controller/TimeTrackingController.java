package com.ingloriousmind.android.imtimetracking.controller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.ingloriousmind.android.imtimetracking.R;
import com.ingloriousmind.android.imtimetracking.controller.task.TimeTrackerTask;
import com.ingloriousmind.android.imtimetracking.model.Tracking;
import com.ingloriousmind.android.imtimetracking.persistence.DbHelper;
import com.ingloriousmind.android.imtimetracking.util.FileUtil;
import com.ingloriousmind.android.imtimetracking.util.TimeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;

import timber.log.Timber;

/**
 * time tracking controller
 *
 * @author lavong.soysavanh
 */
public class TimeTrackingController {

    /**
     * default timer period
     */
    private static final long TIMER_PERIOD = 1000;
    /**
     * time tracking
     */
    private static Tracking tracking;
    /**
     * timer
     */
    private static Timer timer;

    /**
     * starts time tracking and returns the timer task being executed periodically ({@link #TIMER_PERIOD}).
     *
     * @param t        a {@link com.ingloriousmind.android.imtimetracking.model.Tracking} to start or continue
     * @param listener a time tracking tick listener to register
     * @return {@link com.ingloriousmind.android.imtimetracking.model.Tracking}
     */
    public static Tracking start(Tracking t, TimeTrackerTask.TimeTrackingAware listener) {
        long now = System.currentTimeMillis();
        if (t == null) {
            tracking = new Tracking();
            tracking.setCreated(now);
        } else {
            tracking = t;
        }
        if (!tracking.isTracking())
            tracking.setLastTrackingStarted(now);
        tracking.setTracking(true);
        storeTracking(tracking);
        stopTimer();
        timer = new Timer(true);
        timer.schedule(new TimeTrackerTask(listener, tracking.getLastTrackingStarted(), tracking.getDuration()), 0, TIMER_PERIOD);
        Timber.v("started: %s", tracking);
        return tracking;
    }

    /**
     * stops/pauses time tracking
     *
     * @param keepRunningFlag whether to keep {@link com.ingloriousmind.android.imtimetracking.model.Tracking#tracking} set to true
     * @return current tracking
     */
    public static Tracking stop(boolean keepRunningFlag) {
        if (timer != null && tracking != null) {
            long now = System.currentTimeMillis();
            tracking.setDuration(now - tracking.getLastTrackingStarted() + tracking.getDuration());
            tracking.setTracking(keepRunningFlag);
            if (keepRunningFlag)
                tracking.setLastTrackingStarted(now);
            storeTracking(tracking);
            stopTimer();
            Timber.v("stopped: %s", tracking);
            return tracking;
        }
        return null;
    }

    /**
     * stops time tracking
     *
     * @return current tracking
     */
    public static Tracking stop() {
        return stop(false);
    }

    /**
     * cancels timer
     */
    private static void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

    public static Tracking currentTracking() {
        return tracking;
    }

    /**
     * fetches persisted {@link com.ingloriousmind.android.imtimetracking.model.Tracking} from db
     *
     * @return list of trackings
     */
    public static List<Tracking> fetchTrackings() {
        List<Tracking> trackings = DbHelper.getInstance().fetchTrackings();
        Timber.d("%d trackings loaded:", trackings.size());
        Timber.v("%s", trackings);
        return trackings;
    }

    /**
     * fetches the most recent tracking from db
     *
     * @return most recent tracking
     */
    public static Tracking fetchMostRecentTracking() {
        Tracking mostRecentTracking = DbHelper.getInstance().fetchMostRecentTracking();
        Timber.d("most recent: %s", mostRecentTracking);
        return mostRecentTracking;
    }

    /**
     * removes given tracking from db
     *
     * @param t tracking to remove
     */
    public static void removeTracking(Tracking t) {
        if (t == null)
            return;

        boolean deleted = DbHelper.getInstance().removeTracking(t);

        if (deleted)
            Timber.d("removed %s", t.toString());
        else
            Timber.w("unable to remove: %s", t.toString());
    }

    /**
     * persists given tracking
     *
     * @param t tracking to persist
     */
    public static void storeTracking(Tracking t) {
        if (t == null)
            return;

        boolean stored = DbHelper.getInstance().storeTracking(t);

        if (stored)
            Timber.d("stored: %s", t.toString());
        else
            Timber.w("unable to store: %s", t.toString());
    }

    /**
     * exports persisted trackings as pdf file
     *
     * @param ctx a context
     * @return pdf file
     */
    public static File exportPdf(Context ctx) {

        // get trackings
        List<Tracking> trackings = fetchTrackings();
        if (trackings == null || trackings.isEmpty())
            return null;
        Timber.d("exporting %d trackings to pdf", trackings.size());

        // create file
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String todayString = sdf.format(new Date(System.currentTimeMillis()));
        String pdfFileName = "im-timetracking-" + todayString + ".pdf";
        File pdfFile = new File(FileUtil.appDir, pdfFileName);

        // create pdf
        TextPaint p = new TextPaint();
        p.setTextSize(10);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.MONOSPACE);
        p.setAntiAlias(true);
        TextPaint gp = new TextPaint(p);
        gp.setColor(ctx.getResources().getColor(R.color.im_green));
        gp.setFakeBoldText(true);
        PdfDocument doc = new PdfDocument();
        int a4Width = (int) (210 / 25.4 * 72);
        int a4Height = (int) (297 / 25.4 * 72);
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(a4Width, a4Height, 1).create();
        PdfDocument.Page page = doc.startPage(pageInfo);

        Canvas c = page.getCanvas();
        int padding = ctx.getResources().getInteger(R.integer.export_pdf_page_padding);
        String unnamed = ctx.getString(R.string.list_item_tracking_unnamed_title);
        StringBuilder sb = new StringBuilder();
        long totalDuration = 0;

        // prepare entries
        for (Tracking t : trackings) {
            sb.append(sdf.format(new Date(t.getCreated()))).append(" ");
            sb.append(TimeUtil.getTimeString(t.getDuration())).append(" | ");
            sb.append(TextUtils.isEmpty(t.getTitle()) ? unnamed : t.getTitle());
            sb.append("\n");
            totalDuration += t.getDuration();
        }

        // write entries - TODO care about pagination at some point
        c.save();
        c.translate(padding, padding);
        StaticLayout sl = new StaticLayout(sb.toString(), p, a4Width / 5 * 4, Layout.Alignment.ALIGN_NORMAL, 1.0f, 1.0f, false);
        sl.draw(c);

        // write total
        c.translate(0, sl.getHeight());
        sb.setLength(0);
        sb.append(ctx.getResources().getString(R.string.total));
        sb.append(TimeUtil.getTimeString(totalDuration));
        sl = new StaticLayout(sb.toString(), gp, a4Width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 1.0f, false);
        sl.draw(c);
        c.restore();

        // draw icon
        c.save();
        c.translate(a4Width * 0.80f, a4Height * 0.85f);
        Paint iconPaint = new Paint();
        iconPaint.setAlpha(42);
        Bitmap appIcon = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.ic_launcher);
        appIcon = Bitmap.createScaledBitmap(appIcon, 80, 80, true);
        c.drawBitmap(appIcon, 0, 0, iconPaint);
        c.restore();
        appIcon.recycle();

        doc.finishPage(page);

        try {
            // write to file
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
            Timber.v("writing pdf file %s", pdfFile.getAbsolutePath());
            doc.writeTo(new FileOutputStream(pdfFile));
        } catch (IOException e) {
            Timber.e(e, "failed writing pdf file");
            return null;
        } finally {
            doc.close();
        }

        return pdfFile;
    }

}
